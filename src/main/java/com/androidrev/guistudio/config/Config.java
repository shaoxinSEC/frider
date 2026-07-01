package com.androidrev.guistudio.config;

import java.util.ArrayList;
import java.util.List;

public class Config {
    private String rootCommand = "su";
    private String fridaServerPath = "/data/local/tmp/frida-server";
    private String fridaClientPath = "frida";
    private String fridaToolsDir = "tools/frida-tools";
    private String fridaConnection = "usb";
    private String fridaRemoteHost = "";
    private String fridaRemotePort = "27042";
    private String adbPath = "adb";
    private String scrcpyPath = "scrcpy";
    private String scriptsDir = "scripts";
    private String defaultProxy = "";
    private List<RedirectRule> iptablesRedirectRules = new ArrayList<>();

    public static Config defaultConfig() {
        Config cfg = new Config();
        cfg.iptablesRedirectRules = RedirectRules.allTcpRules();
        return cfg;
    }

    public Config copy() {
        Config cfg = new Config();
        cfg.rootCommand = rootCommand;
        cfg.fridaServerPath = fridaServerPath;
        cfg.fridaClientPath = fridaClientPath;
        cfg.fridaToolsDir = fridaToolsDir;
        cfg.fridaConnection = fridaConnection;
        cfg.fridaRemoteHost = fridaRemoteHost;
        cfg.fridaRemotePort = fridaRemotePort;
        cfg.adbPath = adbPath;
        cfg.scrcpyPath = scrcpyPath;
        cfg.scriptsDir = scriptsDir;
        cfg.defaultProxy = defaultProxy;
        cfg.iptablesRedirectRules = new ArrayList<>();
        if (iptablesRedirectRules != null) {
            for (RedirectRule rule : iptablesRedirectRules) {
                cfg.iptablesRedirectRules.add(new RedirectRule(rule.getName(), rule.getTemplate()));
            }
        }
        return cfg;
    }

    public void validate() {
        if (adbPath == null || adbPath.isBlank()) {
            throw new IllegalStateException("adb_path未配置");
        }
        if (fridaClientPath == null || fridaClientPath.isBlank()) {
            throw new IllegalStateException("frida_client_path未配置");
        }
    }

    public String getRootCommand() {
        return rootCommand;
    }

    public void setRootCommand(String rootCommand) {
        this.rootCommand = rootCommand;
    }

    public String getFridaServerPath() {
        return fridaServerPath;
    }

    public void setFridaServerPath(String fridaServerPath) {
        this.fridaServerPath = fridaServerPath;
    }

    public String getFridaClientPath() {
        return fridaClientPath;
    }

    public void setFridaClientPath(String fridaClientPath) {
        this.fridaClientPath = fridaClientPath;
    }

    public String getFridaToolsDir() {
        return fridaToolsDir;
    }

    public void setFridaToolsDir(String fridaToolsDir) {
        this.fridaToolsDir = fridaToolsDir;
    }

    /** usb或remote，对应Frida的 -U / -H。 */
    public String getFridaConnection() {
        return fridaConnection;
    }

    public void setFridaConnection(String fridaConnection) {
        this.fridaConnection = fridaConnection;
    }

    public String getFridaRemoteHost() {
        return fridaRemoteHost;
    }

    public void setFridaRemoteHost(String fridaRemoteHost) {
        this.fridaRemoteHost = fridaRemoteHost;
    }

    public String getFridaRemotePort() {
        return fridaRemotePort;
    }

    public void setFridaRemotePort(String fridaRemotePort) {
        this.fridaRemotePort = fridaRemotePort;
    }

    public String getAdbPath() {
        return adbPath;
    }

    public void setAdbPath(String adbPath) {
        this.adbPath = adbPath;
    }

    public String getScrcpyPath() {
        return scrcpyPath;
    }

    public void setScrcpyPath(String scrcpyPath) {
        this.scrcpyPath = scrcpyPath;
    }

    public String getScriptsDir() {
        return scriptsDir;
    }

    public void setScriptsDir(String scriptsDir) {
        this.scriptsDir = scriptsDir;
    }

    public String getDefaultProxy() {
        return defaultProxy;
    }

    public void setDefaultProxy(String defaultProxy) {
        this.defaultProxy = defaultProxy;
    }

    public List<RedirectRule> getIptablesRedirectRules() {
        return iptablesRedirectRules;
    }

    public void setIptablesRedirectRules(List<RedirectRule> iptablesRedirectRules) {
        this.iptablesRedirectRules = iptablesRedirectRules;
    }
}
