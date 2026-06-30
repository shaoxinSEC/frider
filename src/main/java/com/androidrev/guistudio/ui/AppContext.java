package com.androidrev.guistudio.ui;

import com.androidrev.guistudio.adb.AdbClient;
import com.androidrev.guistudio.adb.AppInfo;
import com.androidrev.guistudio.config.Config;
import com.androidrev.guistudio.config.ConfigManager;
import com.androidrev.guistudio.frida.FridaClient;
import com.androidrev.guistudio.scrcpy.ScrcpyClient;
import javafx.stage.Stage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public class AppContext {
    public static final String SOURCE_APPS = "应用管理";
    public static final String SOURCE_IPTABLES = "流量转发";
    public static final String SOURCE_FRIDA = "Frida";
    public static final String SOURCE_LOGCAT = "Logcat";
    public static final String SOURCE_ADB = "ADB管理";
    public static final String SOURCE_SYSTEM = "系统";
    public static final String SOURCE_SETTINGS = "设置";

    private final Stage stage;
    private final Logger logger;
    private ConfigManager configManager;
    private final AdbClient adb;
    private final FridaClient frida;
    private final ScrcpyClient scrcpy;
    private final DeviceManager deviceManager;
    private final SharedState shared = new SharedState();
    private final List<Consumer<Config>> configListeners = new CopyOnWriteArrayList<>();

    public AppContext(Stage stage, Logger logger, ConfigManager configManager, Config initialConfig) {
        this.stage = stage;
        this.logger = logger;
        this.configManager = configManager;
        this.adb = new AdbClient(initialConfig);
        this.frida = new FridaClient(initialConfig);
        this.scrcpy = new ScrcpyClient(initialConfig);
        this.deviceManager = new DeviceManager(this);
    }

    public void reportToolAvailability() {
        reportToolIssue(adb.getUnavailableReason());
        reportToolIssue(frida.getUnavailableReason());
        reportToolIssue(scrcpy.getUnavailableReason());
    }

    public Stage getStage() {
        return stage;
    }

    public Logger getLogger() {
        return logger;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public void setConfigManager(ConfigManager configManager) {
        this.configManager = configManager;
    }

    public Config getConfig() {
        return configManager != null ? configManager.get() : Config.defaultConfig();
    }

    public Path scriptsDirAbs() throws IOException {
        if (configManager != null) {
            return configManager.scriptsDirAbs();
        }
        String dir = getConfig().getScriptsDir();
        if (dir == null || dir.isBlank()) {
            dir = "scripts";
        }
        Path p = Paths.get(dir);
        if (!p.isAbsolute()) {
            p = Paths.get(System.getProperty("user.dir")).resolve(p);
        }
        Files.createDirectories(p);
        return p;
    }

    public AdbClient getAdb() {
        return adb;
    }

    public FridaClient getFrida() {
        return frida;
    }

    public ScrcpyClient getScrcpy() {
        return scrcpy;
    }

    public DeviceManager getDeviceManager() {
        return deviceManager;
    }

    public SharedState getShared() {
        return shared;
    }

    public void onConfigReload(Config cfg) {
        applyConfig(cfg);
        logger.log(SOURCE_SYSTEM, ConfigManager.formatReloadMessage());
    }

    public void saveConfig(Config cfg) throws IOException {
        if (configManager == null) {
            throw new IOException("配置管理器不可用");
        }
        configManager.save(cfg);
        applyConfig(cfg);
        logger.log(SOURCE_SETTINGS, "%s → %s", ConfigManager.formatSaveMessage(), configManager.getPath());
    }

    public void reloadConfigFromFile() throws IOException {
        if (configManager == null) {
            throw new IOException("配置管理器不可用");
        }
        configManager.reload();
    }

    private void applyConfig(Config cfg) {
        adb.updateConfig(cfg);
        frida.updateConfig(cfg, configManager);
        scrcpy.updateConfig(cfg);
        if (configManager != null) {
            try {
                configManager.ensureScriptsDir();
                configManager.ensureFridaToolsDir();
            } catch (IOException e) {
                logger.log(SOURCE_SYSTEM, "工具目录创建失败: %s", e.getMessage());
            }
        }
        Logger.clearShownDialogs();
        reportToolAvailability();
        for (Consumer<Config> listener : configListeners) {
            listener.accept(cfg);
        }
    }

    private void reportToolIssue(String reason) {
        if (reason == null) {
            return;
        }
        Logger.showError(this, SOURCE_SYSTEM, new IOException(reason));
    }

    public void addConfigListener(Consumer<Config> listener) {
        configListeners.add(listener);
        listener.accept(getConfig());
    }

    public static class SharedState {
        private final Object lock = new Object();
        private List<AppInfo> apps = List.of();
        private final List<Consumer<List<AppInfo>>> appsListeners = new CopyOnWriteArrayList<>();

        public void addAppsListener(Consumer<List<AppInfo>> listener) {
            appsListeners.add(listener);
            listener.accept(getApps());
        }

        public void setApps(List<AppInfo> apps) {
            List<AppInfo> copy;
            synchronized (lock) {
                this.apps = new ArrayList<>(apps);
                copy = new ArrayList<>(this.apps);
            }
            for (Consumer<List<AppInfo>> listener : appsListeners) {
                listener.accept(copy);
            }
        }

        public List<AppInfo> getApps() {
            synchronized (lock) {
                return new ArrayList<>(apps);
            }
        }

        public List<String> getPackages() {
            synchronized (lock) {
                return apps.stream().map(AppInfo::getPackageName).toList();
            }
        }
    }
}
