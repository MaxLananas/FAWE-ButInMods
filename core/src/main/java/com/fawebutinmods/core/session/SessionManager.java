package com.fawebutinmods.core.session;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Keeps one {@link LocalSession} per actor (players and the console). */
public final class SessionManager {

    private static final SessionManager INSTANCE = new SessionManager();

    private final Map<UUID, LocalSession> sessions = new ConcurrentHashMap<>();
    private final LocalSession consoleSession = new LocalSession();

    private SessionManager() {
    }

    public static SessionManager get() {
        return INSTANCE;
    }

    public LocalSession of(UUID uuid) {
        if (uuid == null) {
            return consoleSession;
        }
        return sessions.computeIfAbsent(uuid, k -> new LocalSession());
    }

    public LocalSession console() {
        return consoleSession;
    }

    public void remove(UUID uuid) {
        sessions.remove(uuid);
    }

    public void clear() {
        sessions.clear();
    }

    public int size() {
        return sessions.size();
    }
}
