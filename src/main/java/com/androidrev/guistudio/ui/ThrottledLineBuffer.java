package com.androidrev.guistudio.ui;

import javafx.application.Platform;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Batches line-oriented log output and flushes to the JavaFX thread at a fixed interval.
 * Producers never block on UI work; excess lines are dropped from the head of the queue.
 */
final class ThrottledLineBuffer {
    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ui-line-buffer");
        t.setDaemon(true);
        return t;
    });

    private final Consumer<List<String>> consumer;
    private final int maxPending;
    private final int maxBatch;
    private final long intervalMs;

    private final Deque<String> pending = new ArrayDeque<>();
    private final Object lock = new Object();
    private final AtomicBoolean fxDrainQueued = new AtomicBoolean();
    private volatile boolean tickScheduled;

    ThrottledLineBuffer(Consumer<List<String>> consumer, int maxPending, int maxBatch, long intervalMs) {
        this.consumer = consumer;
        this.maxPending = maxPending;
        this.maxBatch = maxBatch;
        this.intervalMs = intervalMs;
    }

    void append(String line) {
        synchronized (lock) {
            pending.addLast(line);
            trimExcessLocked();
            scheduleTickLocked();
        }
    }

    void clearPending() {
        synchronized (lock) {
            pending.clear();
            tickScheduled = false;
        }
    }

    void flushNow() {
        if (Platform.isFxApplicationThread()) {
            drainAllOnFxThread();
        } else {
            Platform.runLater(this::drainAllOnFxThread);
        }
    }

    private void scheduleTickLocked() {
        if (tickScheduled || pending.isEmpty()) {
            return;
        }
        tickScheduled = true;
        SCHEDULER.schedule(this::onTick, intervalMs, TimeUnit.MILLISECONDS);
    }

    private void onTick() {
        synchronized (lock) {
            tickScheduled = false;
            if (pending.isEmpty()) {
                return;
            }
        }
        requestFxDrain();
    }

    private void requestFxDrain() {
        if (!fxDrainQueued.compareAndSet(false, true)) {
            return;
        }
        Platform.runLater(this::drainOneBatchOnFxThread);
    }

    private void drainOneBatchOnFxThread() {
        fxDrainQueued.set(false);
        List<String> batch = pollBatch(maxBatch);
        if (batch.isEmpty()) {
            synchronized (lock) {
                tickScheduled = false;
            }
            return;
        }
        try {
            consumer.accept(batch);
        } catch (RuntimeException ignored) {
            // Keep draining even if the UI consumer fails for one batch.
        } finally {
            synchronized (lock) {
                if (pending.isEmpty()) {
                    return;
                }
                if (pending.size() >= maxBatch) {
                    requestFxDrain();
                } else {
                    scheduleTickLocked();
                }
            }
        }
    }

    private void drainAllOnFxThread() {
        while (true) {
            List<String> batch = pollBatch(maxBatch);
            if (batch.isEmpty()) {
                synchronized (lock) {
                    tickScheduled = false;
                }
                return;
            }
            try {
                consumer.accept(batch);
            } catch (RuntimeException ignored) {
            }
            if (batch.size() < maxBatch) {
                synchronized (lock) {
                    tickScheduled = false;
                }
                return;
            }
        }
    }

    private List<String> pollBatch(int limit) {
        synchronized (lock) {
            if (pending.isEmpty()) {
                return List.of();
            }
            int take = Math.min(pending.size(), limit);
            List<String> batch = new ArrayList<>(take);
            for (int i = 0; i < take; i++) {
                batch.add(pending.removeFirst());
            }
            return batch;
        }
    }

    private void trimExcessLocked() {
        while (pending.size() > maxPending) {
            pending.removeFirst();
        }
    }
}
