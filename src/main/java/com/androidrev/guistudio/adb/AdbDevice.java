package com.androidrev.guistudio.adb;

public record AdbDevice(String serial, String state, String model) {
    public boolean isReady() {
        return "device".equals(state);
    }

    public boolean isError() {
        return !isReady();
    }

    public String displayModel() {
        if (model != null && !model.isBlank()) {
            return model.trim();
        }
        return serial;
    }
}
