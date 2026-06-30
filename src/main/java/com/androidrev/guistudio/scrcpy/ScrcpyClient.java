package com.androidrev.guistudio.scrcpy;

import com.androidrev.guistudio.config.Config;
import com.androidrev.guistudio.exec.ExecutablePaths;
import com.androidrev.guistudio.exec.ProcessUtil;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ScrcpyClient {
    private volatile Config config;
    private volatile String unavailableReason;

    public ScrcpyClient(Config config) {
        updateConfig(config);
    }

    public void updateConfig(Config config) {
        this.config = config;
        unavailableReason = ExecutablePaths.validateLocalExecutable(resolvePath(), "scrcpy");
    }

    public boolean isAvailable() {
        return unavailableReason == null;
    }

    public String getUnavailableReason() {
        return unavailableReason;
    }

    public String resolvePath() {
        return config.getScrcpyPath();
    }

    public Process launch(String serial) throws IOException {
        if (unavailableReason != null) {
            throw new IOException(unavailableReason);
        }
        List<String> command = new ArrayList<>();
        command.add(resolvePath());
        if (serial != null && !serial.isBlank()) {
            command.add("-s");
            command.add(serial.trim());
        }

        ProcessBuilder pb = new ProcessBuilder(command);
        ProcessUtil.prepareProcess(pb);
        pb.redirectErrorStream(true);
        pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);

        injectAdbPath(pb);
        Process process = pb.start();
        ProcessUtil.hideWindowIfWindows(process);
        return process;
    }

    private void injectAdbPath(ProcessBuilder pb) {
        String adbPath = config.getAdbPath();
        if (adbPath == null || adbPath.isBlank() || !ExecutablePaths.isLocalFilePath(adbPath.trim())) {
            return;
        }
        Path adbFile = ExecutablePaths.resolve(adbPath.trim());
        Path adbDir = adbFile.getParent();
        if (adbDir == null) {
            return;
        }
        Map<String, String> env = pb.environment();
        String pathKey = env.containsKey("Path") ? "Path" : "PATH";
        String currentPath = env.getOrDefault(pathKey, "");
        env.put(pathKey, adbDir.toString() + File.pathSeparator + currentPath);
    }
}
