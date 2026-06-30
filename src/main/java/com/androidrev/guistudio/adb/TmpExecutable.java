package com.androidrev.guistudio.adb;

import java.nio.file.Paths;

public record TmpExecutable(String path, String pid) {
    public String name() {
        return Paths.get(path).getFileName().toString();
    }

    public boolean isRunning() {
        return pid != null && !pid.isBlank();
    }

    public String displayName() {
        if (isRunning()) {
            return name() + " (PID " + pid.split("\\s+")[0] + ")";
        }
        return name() + " (未运行)";
    }
}
