package com.androidrev.guistudio.adb;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Lightweight snapshot of installed packages and their running PIDs. */
public record AppSnapshot(Map<String, String> packageToPid) {
    public AppSnapshot {
        packageToPid = Collections.unmodifiableMap(new LinkedHashMap<>(packageToPid));
    }

    public static AppSnapshot empty() {
        return new AppSnapshot(Map.of());
    }
}
