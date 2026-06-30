package com.androidrev.guistudio.config;

import java.util.Set;

public record ProxyEndpoint(String protocol, String host, String port) {
    public static final Set<String> SUPPORTED_PROTOCOLS = Set.of("http", "https", "socks5", "socks4");

    public ProxyEndpoint {
        if (protocol == null || protocol.isBlank()) {
            protocol = "http";
        }
        protocol = protocol.toLowerCase();
        if (!SUPPORTED_PROTOCOLS.contains(protocol)) {
            throw new IllegalArgumentException("不支持的代理协议: " + protocol
                    + "（支持: http, https, socks5, socks4）");
        }
    }

    public String toUrl() {
        return protocol + "://" + host + ":" + port;
    }

    public String toHostPort() {
        return host + ":" + port;
    }

    public static ProxyEndpoint parse(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("代理地址不能为空");
        }
        String s = input.trim();
        String protocol = "http";
        int schemeIdx = s.indexOf("://");
        if (schemeIdx > 0) {
            protocol = s.substring(0, schemeIdx).trim().toLowerCase();
            s = s.substring(schemeIdx + 3).trim();
        }
        HostPort hp = parseHostPort(s);
        return new ProxyEndpoint(protocol, hp.host(), hp.port());
    }

    private static HostPort parseHostPort(String s) {
        int idx = s.lastIndexOf(':');
        if (idx <= 0 || idx >= s.length() - 1) {
            throw new IllegalArgumentException("代理格式应为 [协议://]IP:端口，例如 http://192.168.1.100:8080");
        }
        String host = s.substring(0, idx).trim();
        String port = s.substring(idx + 1).trim();
        if (host.isEmpty()) {
            throw new IllegalArgumentException("代理主机不能为空");
        }
        try {
            int p = Integer.parseInt(port);
            if (p < 1 || p > 65535) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("无效端口: " + port);
        }
        return new HostPort(host, port);
    }

    private record HostPort(String host, String port) {
    }
}
