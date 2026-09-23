package com.maxlananas.fawebim.core.history;

import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.util.NbtCompound;
import com.maxlananas.fawebim.core.util.NbtIo;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * The edits every player made in this server, newest first.
 *
 * <p>FAWE answers {@code /history find}, {@code /history rollback} and
 * {@code /history restore} from a SQL database. A mod has no such database, but
 * it does have the same records in memory, so the log keeps a bounded list of
 * the finished edits of every session together with who made them and when.
 * That is what the {@code -u}, {@code -t} and {@code -r} filters search.</p>
 *
 * <p>With {@code history.use-disk} on, every finished edit is also written next
 * to the world as a snapshot file and read back when the server starts, which is
 * what makes {@code /history find} and {@code /history rollback} reach the edits
 * of a previous session.</p>
 */
public final class EditLog {

    /** How many edits stay searchable; older ones fall off the end. */
    private static final int CAPACITY = 128;

    private static final Deque<Entry> ENTRIES = new ArrayDeque<>();

    private static volatile Path directory;

    /** Executes the history writes, inline by default so a test sees its file. */
    private static volatile java.util.concurrent.Executor writer = Runnable::run;

    /** Makes two edits finished in the same millisecond land in two files. */
    private static final java.util.concurrent.atomic.AtomicInteger SEQUENCE =
            new java.util.concurrent.atomic.AtomicInteger();

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
            this(actor, world, record, System.currentTimeMillis());
        }

        /** An entry of an edit that happened at a known moment. */
        Entry(String actor, String world, History.Record record, long time) {
            this.actor = actor;
            this.world = world;
            this.record = record;
            this.time = time;
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

    /** The folder the edits are written to when disk history is on. */
    public static void setDirectory(Path path) {
        directory = path;
        if (path != null) {
            try {
                Files.createDirectories(path);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    public static Path directory() {
        return directory;
    }

    /** Records a finished edit; called when a session closes a history record. */
    public static void add(String actor, String world, History.Record record) {
        Entry entry = addQuietly(actor, world, record);
        if (entry != null && com.maxlananas.fawebim.core.platform.Config.get().enableDiskHistory) {
            // The file holds the whole record, so writing it must not hold up
            // the thread that just finished the edit.
            writer.execute(() -> persist(entry, record));
        }
    }

    /** Where the history files are written; inline until a platform installs one. */
    public static void setWriter(java.util.concurrent.Executor executor) {
        writer = executor == null ? Runnable::run : executor;
    }

    /** Adds an entry to the in-memory log, returning it, without touching disk. */
    public static synchronized Entry addQuietly(String actor, String world, History.Record record) {
        if (record == null || record.isEmpty()) {
            return null;
        }
        Entry entry = new Entry(actor == null ? "console" : actor, world == null ? "world" : world, record);
        ENTRIES.addFirst(entry);
        while (ENTRIES.size() > CAPACITY) {
            ENTRIES.removeLast();
        }
        return entry;
    }

    /** Writes one edit to the history folder, named after who made it and when. */
    public static synchronized void persist(Entry entry, History.Record record) {
        Path folder = directory;
        if (folder == null) {
            return;
        }
        String name = entry.time + "-" + SEQUENCE.incrementAndGet() + "-"
                + entry.actor.replaceAll("[^A-Za-z0-9_.-]", "_") + ".snap";
        try {
            Files.createDirectories(folder);
            NbtCompound root = Snapshots.of(record, entry.actor, entry.time);
            root.putString("world", entry.world);
            // Gzipped, like every other file the mod reads back.
            Files.write(folder.resolve(name), NbtIo.write(root, false, true));
        } catch (IOException e) {
            // A history that cannot be written must never take the edit down.
        }
    }

    /** Reads the history folder back into the log, oldest entry first. */
    public static synchronized int load() {
        Path folder = directory;
        if (folder == null || !Files.isDirectory(folder)) {
            return 0;
        }
        List<Entry> loaded = new ArrayList<>();
        try (Stream<Path> files = Files.list(folder)) {
            for (Path path : files.filter(file -> file.getFileName().toString().endsWith(".snap")).toList()) {
                try {
                    NbtCompound root = NbtIo.readNbtOrGzip(Files.readAllBytes(path));
                    History.Record record = Snapshots.toRecord(root);
                    // The file carries the moment of the edit, which is what the
                    // -t filter of /history find compares against.
                    Entry entry = new Entry(root.getString("owner", "console"),
                            root.getString("world", "world"), record, root.getLong("time", 0L));
                    loaded.add(entry);
                } catch (IOException | RuntimeException e) {
                    // A single unreadable file must not stop the rest.
                }
            }
        } catch (IOException e) {
            return 0;
        }
        loaded.sort(java.util.Comparator.comparingLong(entry -> entry.time));
        for (Entry entry : loaded) {
            ENTRIES.addFirst(entry);
        }
        while (ENTRIES.size() > CAPACITY) {
            ENTRIES.removeLast();
        }
        return loaded.size();
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
