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
    private final Set<String> metadataPending = ConcurrentHashMap.newKeySet();
    private final Set<String> metadataFailed = ConcurrentHashMap.newKeySet();
    private final Set<String> installPathPending = ConcurrentHashMap.newKeySet();
    private volatile long lastLabelRetryMs = 0;
    private static final long LABEL_RETRY_INTERVAL_MS = 10_000;

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
            metadataFailed.clear();
            metadataPending.clear();
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
        metadataPending.clear();
        metadataFailed.clear();
        installPathPending.clear();
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
                fetchAppLabelAsync(info, pkg);
                continue;
            }

            if (!pid.equals(existing.getPid())) {
                if ("N/A".equals(existing.getPid()) && !"N/A".equals(pid)) {
                    changes.add("已启动: " + pkg + " (PID " + pid + ")");
                } else if (!"N/A".equals(existing.getPid()) && "N/A".equals(pid)) {
                    changes.add("已停止: " + pkg);
                } else {
                    changes.add("PID 变更: " + pkg + " -> " + pid);
                }
                existing.setPid(pid);
            }

            if ("N/A".equals(existing.getInstallPath())
                    && installPathPending.add(pkg)) {
                fetchInstallPathAsync(existing, pkg);
            }

            if ("N/A".equals(existing.getAppName())
                    && !metadataFailed.contains(pkg)
                    && metadataPending.add(pkg)) {
                fetchAppLabelAsync(existing, pkg);
            }
        }

        List<String> removed = new ArrayList<>();
        for (String pkg : cache.keySet()) {
            if (!snapshot.packageToPid().containsKey(pkg)) {
                removed.add(pkg);
            }
        }
        for (String pkg : removed) {
            cache.remove(pkg);
            metadataFailed.remove(pkg);
            installPathPending.remove(pkg);
            changes.add("已卸载: " + pkg);
        }
        return changes;
    }

    private void maybeRetryFridaLabels() {
        boolean needsLabels = cache.values().stream().anyMatch(a -> "N/A".equals(a.getAppName()));
        if (!needsLabels) {
            return;
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
        metadataFailed.clear();
        refreshAllAppLabelsAsync();
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
                        app.getLogger().log(AppContext.SOURCE_APPS, "获取 %s 安装路径失败: %s", pkg, e.getMessage()));
            } finally {
                installPathPending.remove(pkg);
            }
        });
    }

    private void fetchAppLabelAsync(AppInfo info, String pkg) {
        Async.run(() -> {
            try {
                if (!app.getAdb().isFridaServerRunning()) {
                    throw new IOException("frida-server 未运行");
                }
                app.getFrida().ensureAppLabels(List.of(pkg));

                String label = app.getFrida().getAppLabel(pkg);
                if (label != null && !label.isBlank()) {
                    info.setAppName(label);
                    metadataFailed.remove(pkg);
                }

                Platform.runLater(() -> {
                    table.refresh();
                    app.getShared().setApps(new ArrayList<>(cache.values()));
                });
            } catch (Exception e) {
                metadataFailed.add(pkg);
                Platform.runLater(() ->
                        app.getLogger().log(AppContext.SOURCE_APPS, "获取 %s 应用名失败: %s", pkg, e.getMessage()));
            } finally {
                metadataPending.remove(pkg);
            }
        });
    }

    private void refreshAllAppLabelsAsync() {
        Async.run(() -> {
            try {
                if (!app.getAdb().isFridaServerRunning()) {
                    Platform.runLater(() ->
                            app.getLogger().log(AppContext.SOURCE_APPS, "Frida 加载应用名跳过: frida-server 未运行"));
                    return;
                }
                app.getLogger().log(AppContext.SOURCE_APPS,
                        "通过 frida-ps 加载应用名: %s", app.getFrida().resolveFridaPsPath());
                app.getFrida().refreshAppLabels();
                Platform.runLater(() -> {
                    metadataFailed.clear();
                    for (AppInfo info : cache.values()) {
                        applyLabel(info);
                    }
                    table.refresh();
                    app.getShared().setApps(new ArrayList<>(cache.values()));
                    app.getLogger().log(AppContext.SOURCE_APPS, "已通过 frida-ps 加载应用名");
                });
            } catch (Exception e) {
                Platform.runLater(() ->
                        app.getLogger().log(AppContext.SOURCE_APPS, "Frida 加载应用名失败: %s", e.getMessage()));
            }
        });
    }

    private void applyLabel(AppInfo info) {
        String label = app.getFrida().getAppLabel(info.getPackageName());
        if (label != null && !label.isBlank()) {
            info.setAppName(label);
        }
    }
}
