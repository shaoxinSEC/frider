package com.androidrev.guistudio.frida;

import com.androidrev.guistudio.adb.AdbClient;
import com.androidrev.guistudio.config.Config;
import com.androidrev.guistudio.config.ConfigManager;
import com.androidrev.guistudio.exec.ExecutablePaths;
import com.androidrev.guistudio.exec.ProcessUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class FridaClient {
    private static final Pattern FRIDA_PS_LINE = Pattern.compile(
            "^(\\S+|-)\\s+(.+?)\\s+([a-zA-Z][\\w]*(?:\\.[a-zA-Z][\\w]*)+)$");

    private volatile Config config;
    private volatile ConfigManager configManager;
    private volatile Map<String, String> appLabels = Map.of();
    private volatile String unavailableReason;

    public FridaClient(Config config) {
        updateConfig(config, null);
    }

    public void updateConfig(Config config, ConfigManager configManager) {
        this.config = config;
        this.configManager = configManager;
        refreshAvailability();
    }

    public boolean isAvailable() {
        return unavailableReason == null;
    }

    public String getUnavailableReason() {
        return unavailableReason;
    }

    private void refreshAvailability() {
        unavailableReason = ExecutablePaths.validateLocalExecutable(resolveClientPath(), "frida client");
    }

    private void ensureAvailable() throws IOException {
        if (unavailableReason != null) {
            throw new IOException(unavailableReason);
        }
    }

    public String resolveClientPath() {
        return config.getFridaClientPath();
    }

    public Path fridaToolsDirAbs() throws IOException {
        if (configManager != null) {
            return configManager.fridaToolsDirAbs();
        }
        String dir = config.getFridaToolsDir();
        if (dir == null || dir.isBlank()) {
            dir = "tools/frida-tools";
        }
        Path p = Path.of(dir);
        if (!p.isAbsolute()) {
            p = Path.of(System.getProperty("user.dir")).resolve(p);
        }
        return p.normalize();
    }

    public String resolveFridaPsPath() throws IOException {
        return FridaTools.findToolInDir(fridaToolsDirAbs(), "frida-ps");
    }

    private String resolveSubToolPath(String nameContains) throws IOException {
        try {
            return FridaTools.findToolInDir(fridaToolsDirAbs(), nameContains);
        } catch (IOException primary) {
            try {
                return FridaTools.findToolBesideClient(resolveClientPath(), nameContains);
            } catch (IOException ignored) {
                throw primary;
            }
        }
    }

    public String getAppLabel(String packageName) {
        return appLabels.get(packageName);
    }

    public synchronized void refreshAppLabels() throws IOException, InterruptedException {
        Map<String, String> labels = fetchAppLabelsViaFridaPs();
        if (labels.isEmpty()) {
            throw new IOException("frida-ps 未返回任何应用名");
        }
        appLabels = Map.copyOf(labels);
    }

    public synchronized void ensureAppLabels(Collection<String> packages)
            throws IOException, InterruptedException {
        boolean stale = appLabels.isEmpty();
        if (!stale && packages != null) {
            for (String pkg : packages) {
                if (!appLabels.containsKey(pkg)) {
                    stale = true;
                    break;
                }
            }
        }
        if (stale) {
            refreshAppLabels();
        }
    }

    private Map<String, String> fetchAppLabelsViaFridaPs() throws IOException, InterruptedException {
        ensureAvailable();
        List<String> command = new ArrayList<>();
        command.add(resolveFridaPsPath());
        command.addAll(config.getFridaPsArgs());

        ProcessBuilder pb = new ProcessBuilder(command);
        ProcessUtil.prepareProcess(pb);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        Map<String, String> labels = new LinkedHashMap<>();
        List<String> output = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), ProcessUtil.consoleCharset()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.add(line);
                parseFridaPsLine(line, labels);
            }
        }

        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("frida-ps 执行超时");
        }
        if (process.exitValue() != 0) {
            throw new IOException(formatFridaPsFailure(process.exitValue(), output));
        }
        return labels;
    }

    static void parseFridaPsLine(String line, Map<String, String> labels) {
        line = line == null ? "" : line.trim();
        if (line.isEmpty() || line.startsWith("PID") || line.startsWith("---")) {
            return;
        }
        Matcher matcher = FRIDA_PS_LINE.matcher(line);
        if (!matcher.matches()) {
            return;
        }
        String identifier = matcher.group(3).trim();
        String name = matcher.group(2).trim();
        if (AdbClient.isValidPackageName(identifier) && !name.isEmpty()) {
            labels.put(identifier, name);
        }
    }

    private String formatFridaPsFailure(int exitCode, List<String> output) {
        String detail = "";
        for (int i = output.size() - 1; i >= 0; i--) {
            String line = output.get(i).trim();
            if (!line.isEmpty()) {
                detail = line;
                break;
            }
        }
        if (detail.contains("unable to communicate with remote frida-server")) {
            detail = detail + "（请确认 frida client、frida-tools 目录中的 frida-ps 与设备上 frida-server 类型一致）";
        }
        return detail.isBlank()
                ? "frida-ps 退出码 " + exitCode
                : "frida-ps 退出码 " + exitCode + ": " + detail;
    }

    public static List<FridaScript> listScripts(Path scriptsDir) throws IOException {
        if (!Files.exists(scriptsDir)) {
            return List.of();
        }
        List<FridaScript> scripts = new ArrayList<>();
        try (Stream<Path> stream = Files.list(scriptsDir)) {
            stream.filter(p -> !Files.isDirectory(p))
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".js"))
                    .sorted()
                    .forEach(p -> scripts.add(new FridaScript(
                            p.getFileName().toString(),
                            p.toString(),
                            readFirstLineComment(p)
                    )));
        }
        return scripts;
    }

    private static String readFirstLineComment(Path path) {
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            String line = reader.readLine();
            if (line != null) {
                line = line.trim();
                if (line.startsWith("//")) {
                    return line.substring(2).trim();
                }
            }
        } catch (IOException ignored) {
        }
        return "";
    }

    public Process runTool(FridaTools.Kind tool, String packageName, String pid) throws IOException {
        ensureAvailable();
        String marker = tool.executableMarker();
        if (marker == null) {
            throw new IOException("不支持的工具: " + tool.getLabel());
        }
        String toolPath = resolveSubToolPath(marker);
        List<String> args = new ArrayList<>();
        args.add(toolPath);
        args.add("-U");
        if (tool.needsTarget()) {
            if (pid != null && !pid.isBlank() && !"N/A".equals(pid)) {
                args.add(pid.split("\\s+")[0]);
            } else if (packageName != null && !packageName.isBlank()) {
                args.add(packageName);
            } else {
                throw new IOException(tool.getLabel() + " 需要选择目标应用");
            }
        }
        ProcessBuilder pb = new ProcessBuilder(args);
        ProcessUtil.prepareProcess(pb);
        Process process = pb.start();
        ProcessUtil.hideWindowIfWindows(process);
        return process;
    }

    public Process runScript(RunOptions opt) throws IOException {
        ensureAvailable();
        if (opt.getScriptPath() == null || opt.getScriptPath().isBlank()) {
            throw new IOException("未选择脚本");
        }
        List<String> args = new ArrayList<>();
        args.add(resolveClientPath());
        args.add("-U");
        args.add("-l");
        args.add(opt.getScriptPath());
        if (opt.isSpawn()) {
            if (opt.getPackageName() == null || opt.getPackageName().isBlank()) {
                throw new IOException("Spawn 模式请指定目标应用");
            }
            args.add("-f");
            args.add(opt.getPackageName());
        } else if (opt.getPid() != null && !opt.getPid().isBlank() && !"N/A".equals(opt.getPid())) {
            args.add("-p");
            args.add(opt.getPid().split("\\s+")[0]);
        } else if (opt.getPackageName() != null && !opt.getPackageName().isBlank()) {
            args.add(opt.getPackageName());
        } else {
            throw new IOException("Attach 模式请指定目标应用或 PID");
        }

        ProcessBuilder pb = new ProcessBuilder(args);
        ProcessUtil.prepareProcess(pb);
        Process process = pb.start();
        ProcessUtil.hideWindowIfWindows(process);
        return process;
    }
}
