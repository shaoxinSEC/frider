package com.androidrev.guistudio.config;

import java.util.List;

public final class RedirectRules {
    private RedirectRules() {
    }

    /** Redirect all outbound TCP; skip localhost and the proxy itself to avoid loops. */
    public static List<RedirectRule> allTcpRules() {
        return List.of(
                new RedirectRule("SkipLocalhost",
                        "iptables -t nat -A OUTPUT -p tcp -d 127.0.0.0/8 -j RETURN"),
                new RedirectRule("SkipProxy",
                        "iptables -t nat -A OUTPUT -p tcp -d {proxy_host} -j RETURN"),
                new RedirectRule("AllTCP",
                        "iptables -t nat -A OUTPUT -p tcp -j DNAT --to-destination {proxy}")
        );
    }

    /** Redirect TCP for a single app via UID; placeholders: {uid}, {proxy}, {proxy_host}, {proxy_port}. */
    public static List<RedirectRule> perAppTcpRules() {
        return List.of(
                new RedirectRule("SkipLocalhost",
                        "iptables -t nat -A OUTPUT -p tcp -d 127.0.0.0/8 -j RETURN"),
                new RedirectRule("SkipProxy",
                        "iptables -t nat -A OUTPUT -p tcp -d {proxy_host} -j RETURN"),
                new RedirectRule("AppTCP",
                        "iptables -t nat -A OUTPUT -p tcp -m owner --uid-owner {uid} -j DNAT --to-destination {proxy}")
        );
    }
}
