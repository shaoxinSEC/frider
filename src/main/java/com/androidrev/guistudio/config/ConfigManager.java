package com.androidrev.guistudio.config;

import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public class ConfigManager implements AutoCloseable {
    private final Path path;
    private final Consumer<Config> onReload;
    private final AtomicReference<Config> config = new AtomicReference<>();
    private WatchService watcher;
    private Thread watchThread;
    private volatile long ignoreWatchUntilMs;

    public ConfigManager(Consumer<Config> onReload) throws IOException {
        this.path = configPath();
        this.onReload = onReload;
        if (Files.exists(path)) {
            config.set(loadFromFile());
        } else {
            Config defaults = Config.defaultConfig();
            config.set(defaults);
            writeConfig(defaults);
        }
        startWatcher();
    }

    public static Path configPath() throws IOException {
        Path exeDir = Paths.get(System.getProperty("user.dir"));
        try {
            Path jar = Paths.get(ConfigManager.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            if (Files.isRegularFile(jar)) {
                exeDir = jar.getParent();
            }
        } catch (Exception ignored) {
            // fall back to user.dir
        }
        return exeDir.resolve("config.toml");
    }

    public static String formatReloadMessage() {
        return "配置已更新并生效";
    }

    public static String formatSaveMessage() {
        return "配置已保存并生效";
    }

    /** Persists config to disk and updates in-memory state. Does not notify listeners. */
    public void save(Config cfg) throws IOException {
        writeConfig(cfg);
        config.set(cfg);
        ignoreWatchUntilMs = System.currentTimeMillis() + 1000;
    }

    /** Reloads config from disk without writing. */
    public Config reload() throws IOException {
        Config cfg = loadFromFile();
        config.set(cfg);
        if (onReload != null) {
            onReload.accept(cfg);
        }
        return cfg;
    }

    private Config loadFromFile() throws IOException {
        TomlParseResult result = Toml.parse(path);
        if (result.hasErrors()) {
            throw new IOException(result.errors().get(0).toString());
        }
        Config cfg = new Config();
        cfg.setRootCommand(stringOrDefault(result, "root_command", "su"));
        cfg.setFridaServerPath(stringOrDefault(result, "frida_server_path", "/data/local/tmp/frida-server"));
        cfg.setFridaServerStartCommand(stringOrDefault(result, "frida_server_start_command", "/data/local/tmp/frida-server &"));
        cfg.setFridaClientPath(stringOrDefault(result, "frida_client_path", "frida"));
        cfg.setFridaToolsDir(stringOrDefault(result, "frida_tools_dir", "tools/frida-tools"));
        TomlArray fridaPsArgs = result.getArray("frida_ps_args");
        if (fridaPsArgs != null && fridaPsArgs.size() > 0) {
            List<String> args = new ArrayList<>();
            for (int i = 0; i < fridaPsArgs.size(); i++) {
                String arg = fridaPsArgs.getString(i);
                if (arg != null && !arg.isBlank()) {
                    args.add(arg);
                }
            }
            cfg.setFridaPsArgs(args);
        }
        cfg.setAdbPath(stringOrDefault(result, "adb_path", "adb"));
        cfg.setScrcpyPath(stringOrDefault(result, "scrcpy_path", "scrcpy"));
        cfg.setScriptsDir(stringOrDefault(result, "scripts_dir", "scripts"));
        cfg.setDefaultProxy(stringOrDefault(result, "default_proxy", ""));

        List<RedirectRule> rules = new ArrayList<>();
        TomlArray rulesArray = result.getArray("iptables_redirect_rules");
        if (rulesArray != null) {
            for (int i = 0; i < rulesArray.size(); i++) {
                TomlTable table = rulesArray.getTable(i);
                if (table != null) {
                    rules.add(new RedirectRule(
                            table.getString("name"),
                            table.getString("template")
                    ));
                }
            }
        }
        cfg.setIptablesRedirectRules(rules);
        return cfg;
    }

    private void writeConfig(Config cfg) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# FRIDER 配置文件\n");
        sb.append("# 可在程序「设置」页修改；保存后自动生效\n\n");
        sb.append("root_command = \"").append(escape(cfg.getRootCommand())).append("\"\n");
        sb.append("frida_server_path = \"").append(escape(cfg.getFridaServerPath())).append("\"\n");
        sb.append("frida_server_start_command = \"").append(escape(cfg.getFridaServerStartCommand())).append("\"\n");
        sb.append("frida_client_path = \"").append(escape(cfg.getFridaClientPath())).append("\"\n");
        sb.append("frida_tools_dir = \"").append(escape(cfg.getFridaToolsDir())).append("\"\n");
        sb.append("# frida-ps 等子工具从 frida_tools_dir 目录中按名称匹配\n");
        sb.append("frida_ps_args = [");
        List<String> psArgs = cfg.getFridaPsArgs();
        for (int i = 0; i < psArgs.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append("\"").append(escape(psArgs.get(i))).append("\"");
        }
        sb.append("]\n");
        sb.append("adb_path = \"").append(escape(cfg.getAdbPath())).append("\"\n");
        sb.append("scrcpy_path = \"").append(escape(cfg.getScrcpyPath())).append("\"\n");
        sb.append("scripts_dir = \"").append(escape(cfg.getScriptsDir())).append("\"\n");
        sb.append("default_proxy = \"").append(escape(cfg.getDefaultProxy())).append("\"\n");
        Files.writeString(path, sb.toString());
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String stringOrDefault(TomlTable table, String key, String defaultValue) {
        String value = table.getString(key);
        return value != null ? value : defaultValue;
    }

    private void startWatcher() throws IOException {
        watcher = FileSystems.getDefault().newWatchService();
        path.getParent().register(watcher, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_CREATE);
        watchThread = new Thread(this::watchLoop, "config-watcher");
        watchThread.setDaemon(true);
        watchThread.start();
    }

    private void watchLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            WatchKey key;
            try {
                key = watcher.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ClosedWatchServiceException e) {
                return;
            }
            for (WatchEvent<?> event : key.pollEvents()) {
                Path changed = (Path) event.context();
                if (path.getFileName().equals(changed)) {
                    reloadQuietly();
                }
            }
            key.reset();
        }
    }

    private void reloadQuietly() {
        if (System.currentTimeMillis() < ignoreWatchUntilMs) {
            return;
        }
        try {
            Config cfg = loadFromFile();
            config.set(cfg);
            if (onReload != null) {
                onReload.accept(cfg);
            }
        } catch (IOException ignored) {
            // keep previous config on parse errors
        }
    }

    public Config get() {
        return config.get();
    }

    public Path getPath() {
        return path;
    }

    public Path scriptsDirAbs() throws IOException {
        String dir = get().getScriptsDir();
        Path p = Paths.get(dir);
        if (p.isAbsolute()) {
            return p;
        }
        return path.getParent().resolve(dir);
    }

    public Path fridaToolsDirAbs() throws IOException {
        String dir = get().getFridaToolsDir();
        if (dir == null || dir.isBlank()) {
            dir = "tools/frida-tools";
        }
        Path p = Paths.get(dir);
        if (p.isAbsolute()) {
            return p;
        }
        return path.getParent().resolve(dir);
    }

    public void ensureFridaToolsDir() throws IOException {
        Files.createDirectories(fridaToolsDirAbs());
    }

    public void ensureScriptsDir() throws IOException {
        Files.createDirectories(scriptsDirAbs());
    }

    @Override
    public void close() {
        if (watchThread != null) {
            watchThread.interrupt();
        }
        if (watcher != null) {
            try {
                watcher.close();
            } catch (IOException ignored) {
            }
        }
    }
}
