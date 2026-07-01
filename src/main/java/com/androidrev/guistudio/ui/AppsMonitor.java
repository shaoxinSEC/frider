package com.androidrev.guistudio.ui;

import com.androidrev.guistudio.adb.AdbClient;
import com.androidrev.guistudio.adb.AppInfo;
import com.androidrev.guistudio.adb.AppSnapshot;
import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.scene.control.TableView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Periodically polls the selected device for package list and running PIDs.
 */
public final class AppsMonitor {
    private static final long POLL_INTERVAL_SECONDS = 3;

    private final AppContext app;
    private final DeviceManager deviceManager;
    private final ObservableList<AppInfo> items;
    private final TableView<AppInfo> table;
    private final Map<String, AppInfo> cache = new LinkedHashMap<>();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "apps-monitor");
                t.setDaemon(true);
                return t;
            });
    private final AtomicBoolean polling = new AtomicBoolean(false);
    private final Set<String> installPathPending = ConcurrentHashMap.newKeySet();
    private final Set<String> loggedOnce = ConcurrentHashMap.newKeySet();
    private volatile boolean fridaLabelsBlocked;
    private volatile long lastLabelRetryMs = 0;
    private static final long LABEL_RETRY_INTERVAL_MS = 10_000;
    private static final long LABEL_BLOCKED_RETRY_INTERVAL_MS = 60_000;

    public AppsMonitor(AppContext app, ObservableList<AppInfo> items, TableView<AppInfo> table) {
        this.app = app;
        this.deviceManager = app.getDeviceManager();
        this.items = items;
        this.table = table;
    }

    public void start() {
        deviceManager.addDeviceSwitchListener(serial -> scheduler.execute(() -> resetForDevice(serial)));
        deviceManager.addDeviceConnectListener(() -> scheduler.execute(this::onDeviceConnected));
        scheduler.scheduleWithFixedDelay(this::tick, 0, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS);
        app.getStage().addEventHandler(javafx.stage.WindowEvent.WINDOW_CLOSE_REQUEST, e -> stop());
    }

    public void stop() {
        scheduler.shutdownNow();
    }

    public void forceRefresh() {
        scheduler.execute(() -> {
            fridaLabelsBlocked = false;
            loggedOnce.remove("frida-labels-fail");
            loggedOnce.remove("frida-labels-skip");
            installPathPending.clear();
            lastLabelRetryMs = 0;
            for (AppInfo info : cache.values()) {
                fetchInstallPathAsync(info, info.getPackageName());
            }
            refreshAllAppLabelsAsync();
            tick();
        });
    }

    private void resetForDevice(String serial) {
        cache.clear();
        installPathPending.clear();
        fridaLabelsBlocked = false;
        loggedOnce.clear();
        Platform.runLater(() -> items.clear());
        app.getShared().setApps(List.of());
        tick();
    }

    private void onDeviceConnected() {
        refreshAllAppLabelsAsync();
        tick();
    }

    private void tick() {
        if (!polling.compareAndSet(false, true)) {
            return;
        }
        try {
            if (!app.getAdb().isAvailable() || !deviceManager.isDeviceConnected()) {
                return;
            }

            AppSnapshot snapshot = app.getAdb().pollThirdPartySnapshot();
            List<String> changes = mergeSnapshot(snapshot);
            if (!changes.isEmpty()) {
                publishChanges(changes);
            }
            maybeRetryFridaLabels();
        } catch (Exception e) {
            Platform.runLater(() -> Logger.showError(app, AppContext.SOURCE_APPS, e));
        } finally {
            polling.set(false);
        }
    }

    private void publishChanges(List<String> changes) {
        List<AppInfo> sorted = new ArrayList<>(cache.values());
        sorted.sort(Comparator.comparing(AppInfo::getPackageName));
        app.getShared().setApps(sorted);
        String selectedPkg = table.getSelectionModel().getSelectedItem() != null
                ? table.getSelectionModel().getSelectedItem().getPackageName()
                : null;
        Platform.runLater(() -> {
            items.setAll(sorted);
            table.refresh();
            if (selectedPkg != null) {
                sorted.stream()
                        .filter(a -> selectedPkg.equals(a.getPackageName()))
                        .findFirst()
                        .ifPresent(a -> table.getSelectionModel().select(a));
            }
            for (String msg : changes) {
                if (msg.startsWith("新应用:")) {
                    continue;
                }
                app.getLogger().log(AppContext.SOURCE_APPS, "%s", msg);
            }
        });
    }

    private List<String> mergeSnapshot(AppSnapshot snapshot) {
        cache.keySet().removeIf(pkg -> !AdbClient.isValidPackageName(pkg));

        List<String> changes = new ArrayList<>();
        for (Map.Entry<String, String> entry : snapshot.packageToPid().entrySet()) {
            String pkg = entry.getKey();
            if (!AdbClient.isValidPackageName(pkg)) {
                continue;
            }
            String pidRaw = entry.getValue();
            String pid = pidRaw == null || pidRaw.isBlank() ? "N/A" : pidRaw.split("\\s+")[0];

            AppInfo existing = cache.get(pkg);
            if (existing == null) {
                AppInfo info = new AppInfo(pkg);
                info.setPid(pid);
                applyLabel(info);
                cache.put(pkg, info);
                changes.add("新应用: " + pkg);
                fetchInstallPathAsync(info, pkg);
                continue;
            }

            if (!pid.equals(existing.getPid())) {
                if ("N/A".equals(existing.getPid()) && !"N/A".equals(pid)) {
                    changes.add("已启动: " + pkg + " (PID " + pid + ")");
                } else if (!"N/A".equals(existing.getPid()) && "N/A".equals(pid)) {
                    changes.add("已停止: " + pkg);
                } else {
                    changes.add("PID变更: " + pkg + " -> " + pid);
                }
                existing.setPid(pid);
            }

            if ("N/A".equals(existing.getInstallPath())
                    && installPathPending.add(pkg)) {
                fetchInstallPathAsync(existing, pkg);
            }

            applyLabel(existing);
        }

        List<String> removed = new ArrayList<>();
        for (String pkg : cache.keySet()) {
            if (!snapshot.packageToPid().containsKey(pkg)) {
                removed.add(pkg);
            }
        }
        for (String pkg : removed) {
            cache.remove(pkg);
            installPathPending.remove(pkg);
            changes.add("已卸载: " + pkg);
        }
        return changes;
    }

    private void maybeRetryFridaLabels() {
        boolean needsLabels = cache.values().stream().anyMatch(a -> "N/A".equals(a.getAppName()));
        if (!needsLabels) {
            fridaLabelsBlocked = false;
            return;
        }
        if (fridaLabelsBlocked) {
            long interval = LABEL_BLOCKED_RETRY_INTERVAL_MS;
            long now = System.currentTimeMillis();
            if (now - lastLabelRetryMs < interval) {
                return;
            }
        }
        long now = System.currentTimeMillis();
        if (now - lastLabelRetryMs < LABEL_RETRY_INTERVAL_MS) {
            return;
        }
        try {
            if (!app.getAdb().isFridaServerRunning()) {
                return;
            }
        } catch (IOException | InterruptedException e) {
            return;
        }
        lastLabelRetryMs = now;
        refreshAllAppLabelsAsync();
    }

    private void logOnce(String key, String format, Object... args) {
        if (loggedOnce.add(key)) {
            app.getLogger().log(AppContext.SOURCE_APPS, format, args);
        }
    }

    private void fetchInstallPathAsync(AppInfo info, String pkg) {
        Async.run(() -> {
            try {
                app.getAdb().fetchInstallPath(info);
                Platform.runLater(() -> {
                    table.refresh();
                    app.getShared().setApps(new ArrayList<>(cache.values()));
                });
            } catch (Exception e) {
                Platform.runLater(() ->
                        app.getLogger().log(AppContext.SOURCE_APPS, "获取 %s安装路径失败: %s", pkg, e.getMessage()));
            } finally {
                installPathPending.remove(pkg);
            }
        });
    }

    private void refreshAllAppLabelsAsync() {
        Async.run(() -> {
            try {
                if (!app.getAdb().isFridaServerRunning()) {
                    logOnce("frida-labels-skip", "Frida加载应用名跳过: frida-server未运行");
                    return;
                }
                app.getFrida().refreshAppLabels();
                fridaLabelsBlocked = false;
                loggedOnce.remove("frida-labels-fail");
                Platform.runLater(() -> {
                    for (AppInfo info : cache.values()) {
                        applyLabel(info);
                    }
                    table.refresh();
                    app.getShared().setApps(new ArrayList<>(cache.values()));
                });
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                if (isFridaEnumerateError(msg)) {
                    fridaLabelsBlocked = true;
                }
                logOnce("frida-labels-fail", "Frida加载应用名失败: %s", msg);
            }
        });
    }

    private static boolean isFridaEnumerateError(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String lower = message.toLowerCase();
        return lower.contains("system_server") || lower.contains("failed to enumerate");
    }

    private void applyLabel(AppInfo info) {
        String label = app.getFrida().getAppLabel(info.getPackageName());
        if (label != null && !label.isBlank()) {
            info.setAppName(label);
        }
    }
}
