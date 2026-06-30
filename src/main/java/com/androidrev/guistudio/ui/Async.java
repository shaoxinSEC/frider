package com.androidrev.guistudio.ui;

public final class Async {
    private Async() {
    }

    public static void run(Runnable task) {
        Thread t = new Thread(task, "async-worker");
        t.setDaemon(true);
        t.start();
    }
}
