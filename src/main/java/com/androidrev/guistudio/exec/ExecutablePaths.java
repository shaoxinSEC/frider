package com.androidrev.guistudio.exec;

import java.nio.file.Files;
import java.nio.file.Path;

public final class ExecutablePaths {
    private ExecutablePaths() {
    }

    /**
     * Returns an error message when {@code configured} points at a local file that is missing.
     * Bare names (e.g. {@code adb}) are resolved via PATH at runtime and are not checked here.
     */
    public static String validateLocalExecutable(String configured, String toolLabel) {
        if (configured == null || configured.isBlank()) {
            return toolLabel + " 路径未配置";
        }
        String trimmed = configured.trim();
        if (!isLocalFilePath(trimmed)) {
            return null;
        }
        Path path = resolve(trimmed);
        if (!Files.isRegularFile(path)) {
            return toolLabel + " 不存在: " + path;
        }
        return null;
    }

    public static boolean isLocalFilePath(String path) {
        return path.contains("/")
                || path.contains("\\")
                || path.toLowerCase().endsWith(".exe")
                || path.toLowerCase().endsWith(".bat")
                || path.toLowerCase().endsWith(".cmd");
    }

    public static Path resolve(String path) {
        Path p = Path.of(path);
        if (!p.isAbsolute()) {
            p = Path.of(System.getProperty("user.dir")).resolve(p);
        }
        return p.normalize();
    }
}
