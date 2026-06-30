package com.androidrev.guistudio.frida;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

public final class FridaTools {
    public enum Kind {
        CLIENT("Frida Client", "脚本注入与交互调试", false),
        SERVER("Frida Server", "推送到 Android 设备的 frida-server", false),
        PS("frida-ps", "列出进程与应用", false),
        KILL("frida-kill", "终止目标进程", true),
        TRACE("frida-trace", "函数/API 调用追踪", true),
        DEXDUMP("frida-dexdump", "从内存导出 DEX", true),
        DISCOVER("frida-discover", "发现可 hook 的 API", true),
        LS_DEVICES("frida-ls-devices", "列出 Frida 可用设备", false),
        LS("frida-ls", "列出设备端文件", false),
        PULL("frida-pull", "从设备拉取文件", false),
        PUSH("frida-push", "向设备推送文件", false),
        RM("frida-rm", "删除设备端文件", false),
        APK("frida-apk", "向 APK 注入 Frida Gadget", false),
        COMPILE("frida-compile", "编译 TypeScript/JavaScript 模块", false),
        CREATE("frida-create", "创建 Frida 项目模板", false),
        JOIN("frida-join", "连接 Frida Portal", true),
        ITRACE("frida-itrace", "指令级追踪（交互式）", true);

        private final String label;
        private final String description;
        private final boolean needsTarget;

        Kind(String label, String description, boolean needsTarget) {
            this.label = label;
            this.description = description;
            this.needsTarget = needsTarget;
        }

        public String getLabel() {
            return label;
        }

        public String getDescription() {
            return description;
        }

        public boolean needsTarget() {
            return needsTarget;
        }

        public String executableMarker() {
            return name().equals("CLIENT") || name().equals("SERVER") || name().equals("PS")
                    ? null
                    : label.toLowerCase(Locale.ROOT);
        }
    }

    private static final Set<String> SUBTOOL_MARKERS = Set.of(
            "frida-ps", "frida-kill", "frida-trace", "frida-dexdump", "frida-discover",
            "frida-ls-devices", "frida-ls", "frida-pull", "frida-push", "frida-rm",
            "frida-apk", "frida-compile", "frida-create", "frida-join", "frida-itrace"
    );

    private FridaTools() {
    }

    public static boolean isLocalExecutable(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".exe")) {
            return true;
        }
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return false;
        }
        return Files.isExecutable(path);
    }

    public static boolean isFridaClientFile(Path path) {
        if (!Files.isRegularFile(path) || !isLocalExecutable(path)) {
            return false;
        }
        String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
        for (String marker : SUBTOOL_MARKERS) {
            if (lower.contains(marker)) {
                return false;
            }
        }
        if (lower.equals("frida") || lower.equals("frida.exe")) {
            return true;
        }
        if (lower.matches("frida-[\\d.]+-c(\\.exe)?")) {
            return true;
        }
        if (lower.equals("flo") || lower.equals("flo.exe")) {
            return true;
        }
        return lower.matches("flo-[\\d.]+-c(\\.exe)?");
    }

    public static boolean isFridaServerFile(Path path) {
        if (!Files.isRegularFile(path)) {
            return false;
        }
        String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (lower.startsWith("frida-server")) {
            return true;
        }
        if (lower.matches("flo-[\\d.]+-s(\\.exe)?")) {
            return true;
        }
        if (lower.endsWith("-s") && !lower.endsWith(".exe")) {
            return true;
        }
        return isLocalExecutable(path) && lower.contains("frida-server");
    }

    public static List<String> listLocalClients(Path toolsDir) throws IOException {
        return listMatchingFiles(toolsDir, FridaTools::isFridaClientFile);
    }

    public static List<String> listLocalServers(Path toolsDir) throws IOException {
        return listMatchingFiles(toolsDir, FridaTools::isFridaServerFile);
    }

    private static List<String> listMatchingFiles(Path dir, java.util.function.Predicate<Path> filter)
            throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<String> results = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.filter(filter)
                    .sorted(Comparator.comparing(p -> p.toString(), String.CASE_INSENSITIVE_ORDER))
                    .map(p -> p.toAbsolutePath().normalize().toString())
                    .forEach(results::add);
        }
        return results;
    }

    public static String findToolInDir(Path toolsDir, String nameContains) throws IOException {
        if (toolsDir == null || !Files.isDirectory(toolsDir)) {
            throw new IOException("frida-tools 目录不存在: " + toolsDir);
        }
        String needle = nameContains.toLowerCase(Locale.ROOT);
        try (Stream<Path> stream = Files.walk(toolsDir)) {
            return stream.filter(p -> !Files.isDirectory(p))
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).contains(needle))
                    .filter(FridaTools::isLocalExecutable)
                    .sorted()
                    .findFirst()
                    .map(p -> p.toAbsolutePath().normalize().toString())
                    .orElseThrow(() -> new IOException(
                            "在 " + toolsDir + " 中未找到名称包含 " + nameContains + " 的可执行文件"));
        }
    }

    public static String resolveLocalServerBesideClient(String fridaClientPath, String deviceServerPath)
            throws IOException {
        if (fridaClientPath == null || fridaClientPath.isBlank()) {
            throw new IOException("未配置本地 frida");
        }
        Path client = Path.of(fridaClientPath.trim());
        if (!client.isAbsolute()) {
            client = Path.of(System.getProperty("user.dir")).resolve(client);
        }
        client = client.toAbsolutePath().normalize();
        Path parent = client.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new IOException("无法从本地 frida 所在目录查找 frida-server: " + client);
        }
        String preferredName = null;
        if (deviceServerPath != null && !deviceServerPath.isBlank()) {
            preferredName = Path.of(deviceServerPath.trim()).getFileName().toString().toLowerCase(Locale.ROOT);
        }
        List<String> candidates = listMatchingFiles(parent, FridaTools::isFridaServerFile);
        if (candidates.isEmpty()) {
            throw new IOException("在 " + parent + " 中未找到 frida-server");
        }
        if (preferredName != null) {
            for (String candidate : candidates) {
                if (Path.of(candidate).getFileName().toString().toLowerCase(Locale.ROOT).equals(preferredName)) {
                    return candidate;
                }
            }
        }
        return candidates.get(candidates.size() - 1);
    }

    public static String findToolBesideClient(String fridaClientPath, String nameContains) throws IOException {
        if (fridaClientPath == null || fridaClientPath.isBlank()) {
            throw new IOException("未配置 frida client，无法定位 " + nameContains);
        }
        Path client = Path.of(fridaClientPath.trim());
        if (!client.isAbsolute()) {
            client = Path.of(System.getProperty("user.dir")).resolve(client);
        }
        client = client.toAbsolutePath().normalize();
        Path parent = client.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            throw new IOException("无法从 frida client 所在目录查找 " + nameContains + ": " + client);
        }
        String needle = nameContains.toLowerCase(Locale.ROOT);
        try (Stream<Path> stream = Files.list(parent)) {
            return stream.filter(p -> !Files.isDirectory(p))
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).contains(needle))
                    .filter(FridaTools::isLocalExecutable)
                    .sorted()
                    .findFirst()
                    .map(p -> p.toAbsolutePath().normalize().toString())
                    .orElseThrow(() -> new IOException(
                            "在 " + parent + " 中未找到名称包含 " + nameContains + " 的可执行文件"));
        }
    }

    public static String resolveDefaultClientPath(String configured, Path toolsDir) throws IOException {
        List<String> clients = listLocalClients(toolsDir);
        if (configured != null && !configured.isBlank()) {
            Path configuredPath = Path.of(configured.trim());
            if (!configuredPath.isAbsolute()) {
                configuredPath = Path.of(System.getProperty("user.dir")).resolve(configuredPath);
            }
            configuredPath = configuredPath.toAbsolutePath().normalize();
            if (Files.isRegularFile(configuredPath)) {
                return configuredPath.toString();
            }
            String fileName = configuredPath.getFileName().toString();
            for (String client : clients) {
                if (Path.of(client).getFileName().toString().equalsIgnoreCase(fileName)) {
                    return client;
                }
            }
            if (!configured.trim().equalsIgnoreCase("frida")
                    && !configured.trim().equalsIgnoreCase("flo")) {
                return configuredPath.toString();
            }
        }
        if (clients.isEmpty()) {
            return configured == null || configured.isBlank() ? null : configured.trim();
        }
        return clients.get(clients.size() - 1);
    }

    public static String formatDisplayName(String absolutePath, Path toolsDir) {
        Path path = Path.of(absolutePath).toAbsolutePath().normalize();
        Path base = toolsDir.toAbsolutePath().normalize();
        if (path.startsWith(base)) {
            Path relative = base.relativize(path);
            if (relative.getNameCount() > 1) {
                return relative.toString();
            }
        }
        return path.getFileName().toString();
    }

    public static List<Kind> devicePanelTools() {
        return List.of(Kind.KILL, Kind.TRACE, Kind.DEXDUMP, Kind.DISCOVER, Kind.LS_DEVICES);
    }
}
