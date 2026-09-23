package com.maxlananas.fawebim.core.history;

import com.maxlananas.fawebim.core.math.BlockVector3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * The edits every player made in this server, newest first.
 *
 * <p>FAWE answers {@code /history find}, {@code /history rollback} and
 * {@code /history restore} from a SQL database. A mod has no such database, but
 * it does have the same records in memory, so the log keeps a bounded list of
 * the finished edits of every session together with who made them and when.
 * That is what the {@code -u}, {@code -t} and {@code -r} filters search.</p>
 */
public final class EditLog {

    /** How many edits stay searchable; older ones fall off the end. */
    private static final int CAPACITY = 128;

    private static final Deque<Entry> ENTRIES = new ArrayDeque<>();

    private EditLog() {
    }

    /** One finished edit. */
    public static final class Entry {

        public final String actor;
        public final String world;
        public final long time;
        public final History.Record record;
        private final BlockVector3 minimum;
        private final BlockVector3 maximum;

        Entry(String actor, String world, History.Record record) {
            this.actor = actor;
            this.world = world;
            this.record = record;
            this.time = System.currentTimeMillis();
            int[] bounds = record.bounds();
            this.minimum = bounds == null ? null : BlockVector3.at(bounds[0], bounds[1], bounds[2]);
            this.maximum = bounds == null ? null : BlockVector3.at(bounds[3], bounds[4], bounds[5]);
        }

        /** Distance in blocks from a position to the closest changed block. */
        public double distanceTo(BlockVector3 position) {
            if (minimum == null) {
                return Double.MAX_VALUE;
            }
            double dx = Math.max(Math.max(minimum.x() - position.x(), 0), position.x() - maximum.x());
            double dy = Math.max(Math.max(minimum.y() - position.y(), 0), position.y() - maximum.y());
            double dz = Math.max(Math.max(minimum.z() - position.z(), 0), position.z() - maximum.z());
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }

    /** Records a finished edit; called when a session closes a history record. */
    public static synchronized void add(String actor, String world, History.Record record) {
        if (record == null || record.isEmpty()) {
            return;
        }
        ENTRIES.addFirst(new Entry(actor == null ? "console" : actor, world == null ? "world" : world, record));
        while (ENTRIES.size() > CAPACITY) {
            ENTRIES.removeLast();
        }
    }

    public static synchronized List<Entry> entries() {
        return new ArrayList<>(ENTRIES);
    }

    public static synchronized void clear() {
        ENTRIES.clear();
    }

    /**
     * The entries matching the filters of {@code /history find|rollback|restore}.
     *
     * @param user   a player name, matched without case, or null for everyone
     * @param world  restrict to one world, or null for all of them
     * @param radius only edits within that many blocks of {@code origin}, negative for all
     * @param since  only edits newer than that instant, negative for all
     */
    public static List<Entry> find(String user, String world, double radius, long since, BlockVector3 origin) {
        List<Entry> matches = new ArrayList<>();
        for (Entry entry : entries()) {
            if (user != null && !entry.actor.toLowerCase(Locale.ROOT).startsWith(user.toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (world != null && !entry.world.equals(world)) {
                continue;
            }
            if (since >= 0 && entry.time < since) {
                continue;
            }
            if (radius >= 0 && origin != null && entry.distanceTo(origin) > radius) {
                continue;
            }
            matches.add(entry);
        }
        return matches;
    }
}
