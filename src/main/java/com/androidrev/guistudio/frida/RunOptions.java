package com.androidrev.guistudio.frida;

import java.util.ArrayList;
import java.util.List;

public class RunOptions {
    private List<String> scriptPaths = new ArrayList<>();
    private String packageName;
    private String pid;
    private boolean spawn;

    public List<String> getScriptPaths() {
        return scriptPaths;
    }

    public void setScriptPaths(List<String> scriptPaths) {
        this.scriptPaths = scriptPaths != null ? new ArrayList<>(scriptPaths) : new ArrayList<>();
    }

    public String getScriptPath() {
        return scriptPaths.isEmpty() ? null : scriptPaths.get(0);
    }

    public void setScriptPath(String scriptPath) {
        this.scriptPaths = scriptPath == null || scriptPath.isBlank()
                ? new ArrayList<>()
                : new ArrayList<>(List.of(scriptPath));
    }

    public String getPackageName() {
        return packageName;
    }

    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    public String getPid() {
        return pid;
    }

    public void setPid(String pid) {
        this.pid = pid;
    }

    public boolean isSpawn() {
        return spawn;
    }

    public void setSpawn(boolean spawn) {
        this.spawn = spawn;
    }
}
