package com.androidrev.guistudio.ui;

import com.androidrev.guistudio.adb.AppInfo;

public final class ForwardTarget {
    public static final ForwardTarget ALL = new ForwardTarget(true, null);

    private final boolean allApps;
    private final AppInfo app;

    private ForwardTarget(boolean allApps, AppInfo app) {
        this.allApps = allApps;
        this.app = app;
    }

    public static ForwardTarget of(AppInfo app) {
        return new ForwardTarget(false, app);
    }

    public boolean isAllApps() {
        return allApps;
    }

    public AppInfo app() {
        return app;
    }

    public String displayName() {
        if (allApps) {
            return "All Applications";
        }
        String name = app.getAppName();
        if (name == null || name.isBlank() || "N/A".equals(name)) {
            return app.getPackageName();
        }
        return name + " (" + app.getPackageName() + ")";
    }
}
