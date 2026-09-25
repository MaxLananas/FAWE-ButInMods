package com.maxlananas.fawebim.core.session;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Keeps one {@link LocalSession} per actor (players and the console). */
public final class SessionManager {

    private static final SessionManager INSTANCE = new SessionManager();

    private final Map<UUID, LocalSession> sessions = new ConcurrentHashMap<>();
    /** The history every session uses when {@code history.per-player} is off. */
    private static final com.maxlananas.fawebim.core.history.History SHARED_HISTORY =
            new com.maxlananas.fawebim.core.history.History(
                    com.maxlananas.fawebim.core.platform.Config.get().historySize);

    private final LocalSession consoleSession = new LocalSession();

    private SessionManager() {
        consoleSession.enableSnapshots();
    }

    public static SessionManager get() {
        return INSTANCE;
    }

    public LocalSession of(UUID uuid) {
        return of(uuid, null);
    }

    /** The session of a player, snapshots included, created on first use. */
    public LocalSession of(UUID uuid, String ownerName) {
        if (uuid == null) {
            return consoleSession;
        }
        LocalSession session = sessions.computeIfAbsent(uuid, k -> newSession(ownerName));
        if (ownerName != null) {
            session.setOwnerName(ownerName);
        }
        return session;
    }

    private static LocalSession newSession(String ownerName) {
        // With per-player history off every session shares one history, which is
        // how a server that answers //undo for the whole team is set up.
        LocalSession session = com.maxlananas.fawebim.core.platform.Config.get().perPlayerHistory
                ? new LocalSession()
                : new LocalSession(SHARED_HISTORY);
        session.setOwnerName(ownerName);
        session.enableSnapshots();
        return session;
    }

    /**
     * The session of a player by name, which is what {@code //undo <player>} and
     * {@code /snapshot} need; {@code null} when that player has no session.
     */
    public LocalSession byName(String name) {
        if (name == null) {
            return null;
        }
        if (name.equalsIgnoreCase(consoleSession.ownerName())) {
            return consoleSession;
        }
        for (LocalSession session : sessions.values()) {
            if (name.equalsIgnoreCase(session.ownerName())) {
                return session;
            }
        }
        return null;
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
