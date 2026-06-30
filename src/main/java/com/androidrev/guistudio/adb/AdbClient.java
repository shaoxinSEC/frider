package com.androidrev.guistudio.adb;

import com.androidrev.guistudio.config.Config;
import com.androidrev.guistudio.config.ProxyEndpoint;
import com.androidrev.guistudio.exec.ExecutablePaths;
import com.androidrev.guistudio.exec.ProcessUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public class AdbClient {
    private static final Pattern PACKAGE_NAME = Pattern.compile("^[a-zA-Z][\\w]*(\\.[a-zA-Z][\\w]*)+$");
    private static final int PID_BATCH_SIZE = 60;
    private static final long TIMEOUT_SECONDS = 60;
    private static final String[] FRIDA_ATTACH_CANDIDATES = {
            "com.android.settings",
            "com.android.systemui",
            "system_server"
    };

    private volatile Config config;
    private volatile String selectedSerial;
    private volatile String unavailableReason;

    public AdbClient(Config config) {
        updateConfig(config);
    }

    public void updateConfig(Config config) {
        this.config = config;
        unavailableReason = ExecutablePaths.validateLocalExecutable(config.getAdbPath(), "adb");
    }

    public boolean isAvailable() {
        return unavailableReason == null;
    }

    public String getUnavailableReason() {
        return unavailableReason;
    }

    private void ensureAvailable() throws IOException {
        if (unavailableReason != null) {
            throw new IOException(unavailableReason);
        }
    }

    public String getSelectedSerial() {
        return selectedSerial;
    }

    public void setSelectedSerial(String serial) {
        this.selectedSerial = (serial == null || serial.isBlank()) ? null : serial.trim();
    }

    public String run(String... args) throws IOException, InterruptedException {
        return runForSerial(selectedSerial, args);
    }

    private String runForSerial(String serial, String... args) throws IOException, InterruptedException {
        ensureAvailable();
        ProcessBuilder pb = new ProcessBuilder(buildCommand(serial, args));
        ProcessUtil.prepareProcess(pb);
        pb.redirectErrorStream(false);
        Process process = pb.start();
        String stdout = readStream(process.getInputStream());
        String stderr = readStream(process.getErrorStream());
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("命令执行超时");
        }
        if (process.exitValue() != 0) {
            String msg = stderr.isBlank() ? "exit code " + process.exitValue() : stderr.trim();
            throw new IOException(msg);
        }
        return stdout.trim();
    }

    public Process runStream(String... args) throws IOException {
        ensureAvailable();
        ProcessBuilder pb = new ProcessBuilder(buildCommand(selectedSerial, args));
        ProcessUtil.prepareProcess(pb);
        Process process = pb.start();
        ProcessUtil.hideWindowIfWindows(process);
        return process;
    }

    public Process shellStream(String... shellArgs) throws IOException {
        String[] args = new String[shellArgs.length + 1];
        args[0] = "shell";
        System.arraycopy(shellArgs, 0, args, 1, shellArgs.length);
        return runStream(args);
    }

    /** Host-side {@code adb logcat} (supports {@code --color} and other host options). */
    public Process logcatStream(String... logcatArgs) throws IOException {
        return runStream(logcatArgs);
    }

    private String shell(String... args) throws IOException, InterruptedException {
        String[] all = new String[args.length + 1];
        all[0] = "shell";
        System.arraycopy(args, 0, all, 1, args.length);
        return run(all);
    }

    /** Runs a device shell command as a single {@code adb shell} argument (avoids {@code sh -c} splitting). */
    private String shellScript(String command) throws IOException, InterruptedException {
        return shell(command);
    }

    private String shellRoot(String cmd) throws IOException, InterruptedException {
        String root = config.getRootCommand();
        if (root == null || root.isBlank()) {
            root = "su";
        }
        return shell(root, "-c", cmd);
    }

    public record DeviceCheck(boolean connected, String serial) {
    }

    public List<AdbDevice> listDevices() throws IOException, InterruptedException {
        String out = runForSerial(null, "devices");
        List<AdbDevice> devices = new ArrayList<>();
        for (String line : out.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("List of devices")) {
                continue;
            }
            String[] parts = line.split("\\s+");
            if (parts.length >= 2) {
                devices.add(new AdbDevice(parts[0], parts[1], null));
            }
        }
        return devices;
    }

    public String fetchDeviceModel(String serial) throws IOException, InterruptedException {
        String model = runForSerial(serial, "shell", "getprop", "ro.product.model").trim();
        return model.isEmpty() ? serial : model;
    }

    public DeviceCheck hasDevice() throws IOException, InterruptedException {
        List<AdbDevice> devices = listDevices();
        if (selectedSerial != null) {
            for (AdbDevice device : devices) {
                if (device.serial().equals(selectedSerial)) {
                    return new DeviceCheck(device.isReady(), selectedSerial);
                }
            }
            return new DeviceCheck(false, selectedSerial);
        }
        for (AdbDevice device : devices) {
            if (device.isReady()) {
                return new DeviceCheck(true, device.serial());
            }
        }
        if (!devices.isEmpty()) {
            return new DeviceCheck(false, devices.get(0).serial());
        }
        return new DeviceCheck(false, "");
    }

    public List<AppInfo> listThirdPartyApps() throws IOException, InterruptedException {
        AppSnapshot snapshot = pollThirdPartySnapshot();
        List<AppInfo> apps = new ArrayList<>();
        for (Map.Entry<String, String> entry : snapshot.packageToPid().entrySet()) {
            AppInfo info = new AppInfo(entry.getKey());
            applyPid(info, entry.getValue());
            try {
                fetchInstallPath(info);
            } catch (Exception ignored) {
            }
            apps.add(info);
        }
        return apps;
    }

    /** Returns installed third-party package names (one ADB round-trip). */
    public List<String> listThirdPartyPackageNames() throws IOException, InterruptedException {
        String out = shell("pm", "list", "packages", "-3");
        List<String> packages = new ArrayList<>();
        for (String line : out.split("\n")) {
            line = line.trim();
            if (!line.startsWith("package:")) {
                continue;
            }
            String pkg = line.substring("package:".length()).trim();
            if (isValidPackageName(pkg)) {
                packages.add(pkg);
            }
        }
        return packages;
    }

    public static boolean isValidPackageName(String name) {
        return name != null && !name.isBlank() && PACKAGE_NAME.matcher(name).matches();
    }

    /**
     * Polls third-party packages via direct {@code pm list} and batch {@code pidof} on device.
     * Avoids piping {@code pm} into shell loops, which can produce help text on some ROMs.
     */
    public AppSnapshot pollThirdPartySnapshot() throws IOException, InterruptedException {
        List<String> packages = listThirdPartyPackageNames();
        Map<String, String> map = new LinkedHashMap<>();
        for (String pkg : packages) {
            map.put(pkg, "");
        }
        for (int i = 0; i < packages.size(); i += PID_BATCH_SIZE) {
            List<String> batch = packages.subList(i, Math.min(i + PID_BATCH_SIZE, packages.size()));
            Map<String, String> pids = pollPidsBatch(batch);
            for (Map.Entry<String, String> entry : pids.entrySet()) {
                map.put(entry.getKey(), entry.getValue());
            }
        }
        return new AppSnapshot(map);
    }

    private Map<String, String> pollPidsBatch(List<String> packages) throws IOException, InterruptedException {
        StringBuilder script = new StringBuilder();
        for (String pkg : packages) {
            if (!isValidPackageName(pkg)) {
                continue;
            }
            String q = shellQuote(pkg);
            script.append("pid=$(pidof ").append(q).append(" 2>/dev/null | awk '{print $1}'); ");
            script.append("printf '%s|%s\\n' ").append(q).append(" \"${pid:-}\"; ");
        }
        if (script.isEmpty()) {
            return Map.of();
        }
        String out = shellScript(script.toString());
        Map<String, String> pids = new HashMap<>();
        for (String line : out.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            int idx = line.indexOf('|');
            if (idx <= 0) {
                continue;
            }
            String pkg = line.substring(0, idx).trim();
            String pid = line.substring(idx + 1).trim();
            if (isValidPackageName(pkg)) {
                pids.put(pkg, pid);
            }
        }
        return pids;
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    public void pull(String remotePath, java.nio.file.Path localPath) throws IOException, InterruptedException {
        java.nio.file.Files.createDirectories(localPath.getParent());
        run("pull", remotePath, localPath.toString());
    }

    public void push(String localPath, String remotePath) throws IOException, InterruptedException {
        run("push", localPath, remotePath);
    }

    public static String normalizeRemotePath(String path) {
        if (path == null || path.isBlank()) {
            return "/sdcard";
        }
        String p = path.trim().replace('\\', '/');
        while (p.contains("//")) {
            p = p.replace("//", "/");
        }
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        if (p.length() > 1 && p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    public static String parentRemotePath(String path) {
        String p = normalizeRemotePath(path);
        if ("/".equals(p)) {
            return "/";
        }
        int idx = p.lastIndexOf('/');
        if (idx <= 0) {
            return "/";
        }
        return p.substring(0, idx);
    }

    public List<RemoteEntry> listRemoteDirectory(String path) throws IOException, InterruptedException {
        String dir = normalizeRemotePath(path);
        String script = "dir=" + shellQuote(dir) + "; "
                + "if [ ! -d \"$dir\" ]; then echo '__ERR__|目录不存在或无权访问'; exit 1; fi; "
                + "ls -1A \"$dir\" 2>/dev/null | while IFS= read -r name; do "
                + "  [ -z \"$name\" ] && continue; "
                + "  fp=\"$dir/$name\"; "
                + "  if [ -d \"$fp\" ]; then printf 'D\\t%s\\n' \"$name\"; "
                + "  elif [ -f \"$fp\" ] || [ -L \"$fp\" ]; then "
                + "    sz=$(stat -c %s \"$fp\" 2>/dev/null || echo 0); "
                + "    printf 'F\\t%s\\t%s\\n' \"$name\" \"$sz\"; "
                + "  fi; "
                + "done";
        String out;
        try {
            out = shellScript(script);
        } catch (IOException e) {
            throw new IOException("无法访问目录: " + dir + " (" + e.getMessage() + ")", e);
        }
        if (out.startsWith("__ERR__|")) {
            throw new IOException(out.substring("__ERR__|".length()));
        }
        List<RemoteEntry> entries = new ArrayList<>();
        for (String line : out.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split("\t", 3);
            if (parts.length < 2) {
                continue;
            }
            if ("D".equals(parts[0])) {
                entries.add(new RemoteEntry(parts[1], RemoteEntry.Type.DIRECTORY, 0));
            } else if ("F".equals(parts[0])) {
                long size = 0;
                if (parts.length >= 3) {
                    try {
                        size = Long.parseLong(parts[2].trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
                entries.add(new RemoteEntry(parts[1], RemoteEntry.Type.FILE, size));
            }
        }
        entries.sort((a, b) -> {
            if (a.isDirectory() != b.isDirectory()) {
                return a.isDirectory() ? -1 : 1;
            }
            return a.name().compareToIgnoreCase(b.name());
        });
        return entries;
    }

    public void makeRemoteDirectory(String path) throws IOException, InterruptedException {
        String dir = normalizeRemotePath(path);
        shell("mkdir", "-p", dir);
    }

    public void removeRemote(String path) throws IOException, InterruptedException {
        String target = normalizeRemotePath(path);
        if ("/".equals(target)) {
            throw new IOException("不能删除根目录");
        }
        shellScript("if [ -d " + shellQuote(target) + " ]; then rm -rf " + shellQuote(target)
                + "; elif [ -e " + shellQuote(target) + " ]; then rm -f " + shellQuote(target)
                + "; else exit 1; fi");
    }

    public record CommandResult(int exitCode, String stdout, String stderr) {
        public boolean success() {
            return exitCode == 0;
        }

        public String combinedOutput() {
            if (stderr == null || stderr.isBlank()) {
                return stdout != null ? stdout : "";
            }
            if (stdout == null || stdout.isBlank()) {
                return stderr;
            }
            return stdout + "\n" + stderr;
        }
    }

    /** Runs an ADB command and returns stdout/stderr without throwing on non-zero exit. */
    public CommandResult runResult(String... args) {
        return runResultForSerial(selectedSerial, args);
    }

    public CommandResult runResultForSerial(String serial, String... args) {
        try {
            ensureAvailable();
        } catch (IOException e) {
            return new CommandResult(-1, "", e.getMessage());
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(buildCommand(serial, args));
            ProcessUtil.prepareProcess(pb);
            pb.redirectErrorStream(false);
            Process process = pb.start();
            String stdout = readStream(process.getInputStream());
            String stderr = readStream(process.getErrorStream());
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new CommandResult(-1, stdout, "命令执行超时");
            }
            return new CommandResult(process.exitValue(), stdout.trim(), stderr.trim());
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CommandResult(-1, "", e.getMessage());
        }
    }

    public String getVersion() throws IOException, InterruptedException {
        return runForSerial(null, "version");
    }

    public void killServer() throws IOException, InterruptedException {
        runForSerial(null, "kill-server");
    }

    public void startServer() throws IOException, InterruptedException {
        runForSerial(null, "start-server");
    }

    public void connect(String hostPort) throws IOException, InterruptedException {
        if (hostPort == null || hostPort.isBlank()) {
            throw new IOException("请输入 host:port");
        }
        runForSerial(null, "connect", hostPort.trim());
    }

    public void disconnect() throws IOException, InterruptedException {
        runForSerial(null, "disconnect");
    }

    public void disconnect(String hostPort) throws IOException, InterruptedException {
        if (hostPort == null || hostPort.isBlank()) {
            disconnect();
            return;
        }
        runForSerial(null, "disconnect", hostPort.trim());
    }

    public void enableTcpIp(int port) throws IOException, InterruptedException {
        if (port <= 0 || port > 65535) {
            throw new IOException("无效的端口号: " + port);
        }
        run("tcpip", String.valueOf(port));
    }

    public void reboot() throws IOException, InterruptedException {
        run("reboot");
    }

    public void rebootBootloader() throws IOException, InterruptedException {
        run("reboot", "bootloader");
    }

    public void rebootRecovery() throws IOException, InterruptedException {
        run("reboot", "recovery");
    }

    public void install(String apkPath, boolean replace) throws IOException, InterruptedException {
        if (replace) {
            run("install", "-r", apkPath);
        } else {
            run("install", apkPath);
        }
    }

    public void uninstall(String packageName) throws IOException, InterruptedException {
        if (!isValidPackageName(packageName)) {
            throw new IOException("无效的包名: " + packageName);
        }
        run("uninstall", packageName.trim());
    }

    public String listForwards() throws IOException, InterruptedException {
        return run("forward", "--list");
    }

    public void forwardTcp(int localPort, int remotePort) throws IOException, InterruptedException {
        if (localPort <= 0 || localPort > 65535 || remotePort <= 0 || remotePort > 65535) {
            throw new IOException("无效的端口号");
        }
        run("forward", "tcp:" + localPort, "tcp:" + remotePort);
    }

    public void forwardRemove(int localPort) throws IOException, InterruptedException {
        if (localPort <= 0 || localPort > 65535) {
            throw new IOException("无效的本地端口");
        }
        run("forward", "--remove", "tcp:" + localPort);
    }

    public void screencap(Path localPath) throws IOException, InterruptedException {
        Files.createDirectories(localPath.getParent());
        String remote = "/sdcard/screencap_" + System.currentTimeMillis() + ".png";
        shell("screencap", "-p", remote);
        pull(remote, localPath);
        try {
            shell("rm", remote);
        } catch (IOException ignored) {
        }
    }

    public String getDeviceInfo() throws IOException, InterruptedException {
        String[] props = {
                "ro.product.model",
                "ro.product.brand",
                "ro.product.manufacturer",
                "ro.build.version.release",
                "ro.build.version.sdk",
                "ro.serialno",
                "ro.build.id",
                "ro.build.display.id"
        };
        StringBuilder sb = new StringBuilder();
        for (String prop : props) {
            String value = shell("getprop", prop).trim();
            sb.append(prop).append(": ").append(value.isEmpty() ? "(空)" : value).append('\n');
        }
        return sb.toString().trim();
    }

    public String shellInteractive(String command) throws IOException, InterruptedException {
        if (command == null || command.isBlank()) {
            throw new IOException("请输入 shell 命令");
        }
        return shellScript(command.trim());
    }

    /** Returns PIDs to try for Frida attach (Settings / SystemUI / system_server). */
    public List<String> findFridaAttachPids() throws IOException, InterruptedException {
        List<String> pids = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String name : FRIDA_ATTACH_CANDIDATES) {
            addUniquePid(pids, seen, firstPid(name));
        }
        return pids;
    }

    /** Returns a PID suitable for Frida attach (Settings / SystemUI / system_server). */
    public String findFridaAttachPid() throws IOException, InterruptedException {
        List<String> pids = findFridaAttachPids();
        return pids.isEmpty() ? null : pids.get(0);
    }

    private static void addUniquePid(List<String> pids, Set<String> seen, String pid) {
        if (pid == null || pid.isBlank()) {
            return;
        }
        pid = pid.split("\\s+")[0];
        if (seen.add(pid)) {
            pids.add(pid);
        }
    }

    private String firstPid(String processName) throws IOException, InterruptedException {
        try {
            String out = shell("pidof", processName).trim();
            if (out.isEmpty()) {
                return null;
            }
            return out.split("\\s+")[0];
        } catch (IOException e) {
            return null;
        }
    }

    /** Loads install path via {@code pm path} (ADB). */
    public void fetchInstallPath(AppInfo info) throws IOException, InterruptedException {
        String pkg = info.getPackageName();
        String pathOut = shell("pm", "path", pkg);
        for (String line : pathOut.split("\n")) {
            line = line.trim();
            if (line.startsWith("package:")) {
                info.setInstallPath(line.substring("package:".length()).trim());
                return;
            }
        }
    }

    private static void applyPid(AppInfo info, String pidOut) {
        pidOut = pidOut == null ? "" : pidOut.trim();
        if (pidOut.isEmpty()) {
            info.setPid("N/A");
        } else {
            info.setPid(pidOut.split("\\s+")[0]);
        }
    }

    public void killProcess(String pid) throws IOException, InterruptedException {
        if (pid == null || pid.isBlank() || "N/A".equals(pid)) {
            throw new IOException("无效的 PID");
        }
        try {
            Integer.parseInt(pid);
        } catch (NumberFormatException e) {
            throw new IOException("无效的 PID: " + pid);
        }
        shell("kill", "-9", pid);
    }

    /** Starts the app's launcher activity. */
    public void launchApp(String packageName) throws IOException, InterruptedException {
        if (!isValidPackageName(packageName)) {
            throw new IOException("无效的包名: " + packageName);
        }
        String component = resolveLauncherActivity(packageName);
        if (component != null) {
            shell("am", "start", "-n", component);
        } else {
            shell("monkey", "-p", packageName, "-c", "android.intent.category.LAUNCHER", "1");
        }
    }

    /** Clears the app waiting for a debugger ({@code adb shell am clear-debug-app}). */
    public void clearDebugApp() throws IOException, InterruptedException {
        shell("am", "clear-debug-app");
    }

    private String resolveLauncherActivity(String packageName) throws IOException, InterruptedException {
        try {
            String out = shell("cmd", "package", "resolve-activity", "--brief",
                    "-c", "android.intent.category.LAUNCHER", packageName);
            String component = null;
            for (String line : out.split("\n")) {
                line = line.trim();
                if (line.isEmpty() || !line.contains("/")) {
                    continue;
                }
                for (String part : line.split("\\s+")) {
                    if (part.contains("/")) {
                        component = part;
                    }
                }
            }
            return component;
        } catch (IOException e) {
            return null;
        }
    }

    public String listNatRules() throws IOException, InterruptedException {
        return shellRoot("iptables -t nat -L -n -v");
    }

    public void addRedirectRule(int dport, String proxyHost, String proxyPort) throws IOException, InterruptedException {
        String cmd = String.format(
                "iptables -t nat -A OUTPUT -p tcp --dport %d -j DNAT --to-destination %s:%s",
                dport, proxyHost, proxyPort
        );
        shellRoot(cmd);
    }

    public void addRedirectRuleFromTemplate(String template, String proxyHostPort)
            throws IOException, InterruptedException {
        addRedirectRuleFromTemplate(template, proxyHostPort, null);
    }

    public void addRedirectRuleFromTemplate(String template, String proxyHostPort, String uid)
            throws IOException, InterruptedException {
        if (template.contains("{uid}") && (uid == null || uid.isBlank())) {
            throw new IOException("规则模板需要应用 UID");
        }
        ProxyEndpoint endpoint = ProxyEndpoint.parse(
                proxyHostPort.contains("://") ? proxyHostPort : "http://" + proxyHostPort);
        String cmd = template
                .replace("{proxy}", endpoint.toHostPort())
                .replace("{proxy_host}", endpoint.host())
                .replace("{proxy_port}", endpoint.port())
                .replace("{uid}", uid != null ? uid.trim() : "");
        shellRoot(cmd);
    }

    /** Returns the primary UID assigned to the installed package. */
    public String fetchPackageUid(String packageName) throws IOException, InterruptedException {
        if (!isValidPackageName(packageName)) {
            throw new IOException("无效的包名: " + packageName);
        }
        String out = shell("dumpsys", "package", packageName);
        for (String line : out.split("\n")) {
            line = line.trim();
            if (line.startsWith("userId=")) {
                String uid = line.substring("userId=".length()).split("\\s+")[0];
                if (isValidUid(uid)) {
                    return uid;
                }
            }
        }
        try {
            String list = shell("cmd", "package", "list", "packages", "-U", packageName);
            for (String line : list.split("\n")) {
                line = line.trim();
                int idx = line.indexOf("uid:");
                if (idx >= 0) {
                    String uid = line.substring(idx + 4).trim();
                    if (isValidUid(uid)) {
                        return uid;
                    }
                }
            }
        } catch (IOException ignored) {
        }
        throw new IOException("无法获取 " + packageName + " 的 UID");
    }

    private static boolean isValidUid(String uid) {
        if (uid == null || uid.isBlank()) {
            return false;
        }
        try {
            return Integer.parseInt(uid.trim()) >= 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public void clearNatOutputRules() throws IOException, InterruptedException {
        shellRoot("iptables -t nat -F OUTPUT");
    }

    public void pushFridaServer(String localPath) throws IOException, InterruptedException {
        pushFridaServerTo(localPath, fridaServerRemotePath());
    }

    public void pushFridaServerTo(String localPath, String remotePath) throws IOException, InterruptedException {
        if (remotePath == null || remotePath.isBlank()) {
            remotePath = fridaServerRemotePath();
        }
        run("push", localPath, remotePath);
    }

    public void chmodFridaServer() throws IOException, InterruptedException {
        chmodFridaServer(null);
    }

    public void chmodFridaServer(String remotePath) throws IOException, InterruptedException {
        if (remotePath == null || remotePath.isBlank()) {
            remotePath = fridaServerRemotePath();
        }
        shell("chmod", "755", remotePath);
    }

    public void startFridaServer() throws IOException, InterruptedException {
        startFridaServer(null);
    }

    public void startFridaServer(String overrideCommand) throws IOException, InterruptedException {
        String startCmd = overrideCommand;
        if (startCmd == null || startCmd.isBlank()) {
            startCmd = config.getFridaServerStartCommand();
            if (startCmd == null || startCmd.isBlank()) {
                startCmd = fridaServerRemotePath() + " &";
            }
        }
        startCmd = startCmd.trim();
        if (startCmd.startsWith("adb shell ")) {
            startCmd = startCmd.substring("adb shell ".length());
        }
        shell(startCmd);
    }

    /** Lists executable files under {@code /data/local/tmp} with running PIDs if any. */
    public List<TmpExecutable> listTmpExecutables() throws IOException, InterruptedException {
        List<TmpExecutable> executables = new ArrayList<>();
        String listing;
        try {
            listing = shell("ls", "/data/local/tmp");
        } catch (IOException e) {
            return executables;
        }
        for (String name : listing.split("\n")) {
            name = name.trim();
            if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
                continue;
            }
            if (name.endsWith("/")) {
                name = name.substring(0, name.length() - 1);
            }
            String path = "/data/local/tmp/" + name;
            if (!isRemoteExecutableFile(path)) {
                continue;
            }
            executables.add(new TmpExecutable(path, findTmpExecutablePid(path, name)));
        }
        executables.sort(Comparator.comparing(TmpExecutable::name));
        return executables;
    }

    private boolean isRemoteExecutableFile(String path) throws IOException, InterruptedException {
        try {
            shellScript("test -f " + shellQuote(path) + " && test -x " + shellQuote(path));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private String findTmpExecutablePid(String path, String basename) throws IOException, InterruptedException {
        try {
            String out = shell("pgrep", "-f", path).trim();
            if (!out.isEmpty()) {
                return out.split("\\s+")[0];
            }
        } catch (IOException ignored) {
        }
        try {
            String out = shell("pidof", basename).trim();
            if (!out.isEmpty()) {
                return out.split("\\s+")[0];
            }
        } catch (IOException ignored) {
        }
        return "";
    }

    public boolean isTmpExecutableRunning(TmpExecutable executable) throws IOException, InterruptedException {
        if (executable == null || !executable.isRunning()) {
            return false;
        }
        String pid = executable.pid().split("\\s+")[0];
        try {
            shell("kill", "-0", pid);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public void stopTmpExecutable(TmpExecutable executable) throws IOException, InterruptedException {
        if (executable == null) {
            throw new IOException("未选择可执行文件");
        }
        if (!isTmpExecutableRunning(executable)) {
            throw new IOException(executable.name() + " 未运行");
        }
        String pid = executable.pid().split("\\s+")[0];
        try {
            shell("kill", pid);
        } catch (IOException e) {
            shellRoot("kill -9 " + pid);
        }
    }

    public boolean isFridaServerRunning() throws IOException, InterruptedException {
        for (TmpExecutable executable : listTmpExecutables()) {
            if (executable.isRunning()) {
                return true;
            }
        }
        return false;
    }

    public void stopFridaServer() throws IOException, InterruptedException {
        for (TmpExecutable executable : listTmpExecutables()) {
            if (isTmpExecutableRunning(executable)) {
                stopTmpExecutable(executable);
                return;
            }
        }
        throw new IOException("frida-server 未运行");
    }

    private String fridaServerRemotePath() {
        String remote = config.getFridaServerPath();
        if (remote == null || remote.isBlank()) {
            return "/data/local/tmp/frida-server";
        }
        return remote;
    }

    private String[] buildCommand(String serial, String... args) {
        if (serial != null && !serial.isBlank()) {
            String[] withSerial = new String[args.length + 2];
            withSerial[0] = "-s";
            withSerial[1] = serial;
            System.arraycopy(args, 0, withSerial, 2, args.length);
            return withArgs(config.getAdbPath(), withSerial);
        }
        return withArgs(config.getAdbPath(), args);
    }

    private static String[] withArgs(String executable, String... args) {
        String[] cmd = new String[args.length + 1];
        cmd[0] = executable;
        System.arraycopy(args, 0, cmd, 1, args.length);
        return cmd;
    }

    private static String readStream(InputStream stream) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append(line);
            }
        }
        return sb.toString();
    }
}
