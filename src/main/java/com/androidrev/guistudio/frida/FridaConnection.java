package com.androidrev.guistudio.frida;

import com.androidrev.guistudio.config.Config;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Frida CLI / frida-ps与frida-server的连接参数（-U / -H）。 */
public final class FridaConnection {
    public static final String MODE_USB = "usb";
    public static final String MODE_REMOTE = "remote";
    /** frida-ps固定参数（连接方式单独注入）。 */
    public static final List<String> PS_FIXED_ARGS = List.of("-a", "-i");

    private FridaConnection() {
    }

    public static String normalizeMode(String mode) {
        if (mode != null && MODE_REMOTE.equalsIgnoreCase(mode.trim())) {
            return MODE_REMOTE;
        }
        return MODE_USB;
    }

    public static List<String> deviceArgs(Config config) throws IOException {
        if (MODE_REMOTE.equals(normalizeMode(config.getFridaConnection()))) {
            String host = config.getFridaRemoteHost() == null ? "" : config.getFridaRemoteHost().trim();
            if (host.isEmpty()) {
                throw new IOException("远程连接请填写Host");
            }
            int port = parsePort(config.getFridaRemotePort());
            return List.of("-H", host + ":" + port);
        }
        return List.of("-U");
    }

    public static List<String> fridaPsCommandArgs(Config config) throws IOException {
        List<String> args = new ArrayList<>(deviceArgs(config));
        args.addAll(PS_FIXED_ARGS);
        return args;
    }

    public static void validate(Config config) throws IOException {
        if (MODE_REMOTE.equals(normalizeMode(config.getFridaConnection()))) {
            String host = config.getFridaRemoteHost();
            if (host == null || host.isBlank()) {
                throw new IOException("远程连接请填写Host");
            }
            parsePort(config.getFridaRemotePort());
        }
    }

    public static int parsePort(String portText) throws IOException {
        String text = portText == null ? "" : portText.trim();
        if (text.isEmpty()) {
            throw new IOException("远程连接请填写Port");
        }
        try {
            int port = Integer.parseInt(text);
            if (port <= 0 || port > 65535) {
                throw new IOException("Port超出范围: " + port);
            }
            return port;
        } catch (NumberFormatException e) {
            throw new IOException("Port无效: " + text);
        }
    }

    public static String defaultPort() {
        return "27042";
    }
}
