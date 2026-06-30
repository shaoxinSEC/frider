package com.androidrev.guistudio.exec;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public final class ProcessUtil {
    private ProcessUtil() {
    }

    public static void prepareProcess(ProcessBuilder builder) {
        builder.redirectErrorStream(false);
        if (isWindows()) {
            try {
                builder.getClass().getMethod("redirectError", ProcessBuilder.Redirect.class)
                        .invoke(builder, ProcessBuilder.Redirect.PIPE);
            } catch (ReflectiveOperationException ignored) {
            }
        }
    }

    public static void hideWindowIfWindows(Process process) {
        if (!isWindows()) {
            return;
        }
        try {
            Field handleField = process.getClass().getDeclaredField("handle");
            handleField.setAccessible(true);
            long handle = handleField.getLong(process);
            if (handle == 0) {
                return;
            }
            Class<?> kernel32 = Class.forName("com.sun.jna.platform.win32.Kernel32");
            // JNA not available — skip silently
        } catch (Exception ignored) {
            // no-op without JNA
        }
    }

    public static int waitFor(Process process, long timeoutSeconds) throws InterruptedException, IOException {
        if (process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            return process.exitValue();
        }
        process.destroyForcibly();
        throw new IOException("命令执行超时");
    }

    /** Charset for reading stdout/stderr from host CLI tools (adb, frida) on this OS. */
    public static Charset consoleCharset() {
        if (isWindows()) {
            return Charset.forName("GBK");
        }
        return StandardCharsets.UTF_8;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
