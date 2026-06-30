package com.androidrev.guistudio.adb;

public record RemoteEntry(String name, Type type, long sizeBytes) {
    public enum Type {
        DIRECTORY,
        FILE
    }

    public String fullPath(String parentDir) {
        String base = AdbClient.normalizeRemotePath(parentDir);
        if ("/".equals(base)) {
            return "/" + name;
        }
        return base + "/" + name;
    }

    public boolean isDirectory() {
        return type == Type.DIRECTORY;
    }

    public String displaySize() {
        if (isDirectory()) {
            return "";
        }
        if (sizeBytes < 1024) {
            return sizeBytes + " B";
        }
        if (sizeBytes < 1024 * 1024) {
            return String.format("%.1f KB", sizeBytes / 1024.0);
        }
        if (sizeBytes < 1024L * 1024 * 1024) {
            return String.format("%.1f MB", sizeBytes / (1024.0 * 1024));
        }
        return String.format("%.2f GB", sizeBytes / (1024.0 * 1024 * 1024));
    }

    public String typeLabel() {
        return isDirectory() ? "文件夹" : "文件";
    }
}
