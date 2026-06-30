package com.androidrev.guistudio.ui;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

final class FridaInjectionTracker {
    record Session(String id, String scriptName, Process process) {
    }

    private final CopyOnWriteArrayList<Session> sessions = new CopyOnWriteArrayList<>();
    private Runnable onChange = () -> {};

    void setOnChange(Runnable onChange) {
        this.onChange = onChange != null ? onChange : () -> {};
    }

    Session register(String scriptName, Process process) {
        Session session = new Session(UUID.randomUUID().toString(), scriptName, process);
        sessions.add(session);
        notifyChange();
        return session;
    }

    void unregister(Session session) {
        if (sessions.remove(session)) {
            notifyChange();
        }
    }

    boolean isRunning(String scriptName) {
        return sessions.stream().anyMatch(s -> s.scriptName().equals(scriptName));
    }

    int countForScript(String scriptName) {
        return (int) sessions.stream().filter(s -> s.scriptName().equals(scriptName)).count();
    }

    int totalCount() {
        return sessions.size();
    }

    Set<String> runningScriptNames() {
        return sessions.stream().map(Session::scriptName).collect(Collectors.toSet());
    }

    void stopAll() {
        for (Session session : List.copyOf(sessions)) {
            stopSession(session);
            sessions.remove(session);
        }
        notifyChange();
    }

    void stopScript(String scriptName) {
        for (Session session : List.copyOf(sessions)) {
            if (session.scriptName().equals(scriptName)) {
                stopSession(session);
                sessions.remove(session);
            }
        }
        notifyChange();
    }

    private static void stopSession(Session session) {
        Process process = session.process();
        if (process != null && process.isAlive()) {
            process.destroyForcibly();
        }
    }

    private void notifyChange() {
        onChange.run();
    }
}
