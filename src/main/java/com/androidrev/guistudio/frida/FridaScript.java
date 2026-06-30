package com.androidrev.guistudio.frida;

public class FridaScript {
    private final String name;
    private final String path;
    private final String description;

    public FridaScript(String name, String path, String description) {
        this.name = name;
        this.path = path;
        this.description = description;
    }

    public String getName() {
        return name;
    }

    public String getPath() {
        return path;
    }

    public String getDescription() {
        return description;
    }
}
