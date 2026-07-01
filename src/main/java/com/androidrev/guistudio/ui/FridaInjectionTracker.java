package com.androidrev.guistudio.ui;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

final class FridaInjectionTracker {
    record Session(String id, List<String> scriptNames, Process process) {
        String label() {
            if (scriptNames.size() == 1) {
                return scriptNames.get(0);
            }
            return String.join(" + ", scriptNames);
        }

        boolean includes(String scriptName) {
            return scriptNames.contains(scriptName);
        }
    }

    private final CopyOnWriteArrayList<Session> sessions = new CopyOnWriteArrayList<>();
    private Runnable onChange = () -> {};

    void setOnChange(Runnable onChange) {
        this.onChange = onChange != null ? onChange : () -> {};
    }

    Session register(List<String> scriptNames, Process process) {
        Session session = new Session(UUID.randomUUID().toString(), List.copyOf(scriptNames), process);
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
        return sessions.stream().anyMatch(s -> s.includes(scriptName));
    }

    int countForScript(String scriptName) {
        return (int) sessions.stream().filter(s -> s.includes(scriptName)).count();
    }

    int totalCount() {
        return sessions.stream().mapToInt(s -> s.scriptNames().size()).sum();
    }

    Set<String> runningScriptNames() {
        return sessions.stream()
                .flatMap(s -> s.scriptNames().stream())
                .collect(Collectors.toSet());
    }

    void stopAll() {
        for (Session session : List.copyOf(sessions)) {
            stopSession(session);
            sessions.remove(session);
        }
        notifyChange();
    }

    List<Session> sessions() {
        return List.copyOf(sessions);
    }

    void stopSessionById(String sessionId) {
        for (Session session : List.copyOf(sessions)) {
            if (session.id().equals(sessionId)) {
                stopSession(session);
                sessions.remove(session);
                notifyChange();
                return;
            }
        }
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
