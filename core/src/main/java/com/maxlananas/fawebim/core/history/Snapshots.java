package com.maxlananas.fawebim.core.history;

import com.maxlananas.fawebim.core.extent.EditSession;
import com.maxlananas.fawebim.core.math.BlockBox;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.util.NbtCompound;
import com.maxlananas.fawebim.core.util.NbtIo;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * File-backed edit snapshots, the equivalent of FAWE's {@code snapshots} folder.
 *
 * <p>Every recorded edit can be written to disk as the state of the blocks
 * <em>before</em> it ran. That is what {@code /snapshot list}, {@code /snapshot
 * restore} and {@code /history import} work with: a snapshot is a portable undo
 * point that survives a server restart, unlike the in-memory history.</p>
 */
public final class Snapshots {

    private static volatile Path directory;

    private Snapshots() {
    }

    public static void setDirectory(Path path) {
        directory = path;
    }

    public static Path directory() {
        if (directory == null) {
            throw new IllegalStateException("Snapshot directory not configured");
        }
        return directory;
    }

    /** Writes a history record to disk, named after the operation. */
    public static Path save(History.Record record, String owner) throws IOException {
        Path folder = ownerFolder(owner);
        Files.createDirectories(folder);
        Path file = folder.resolve(timestamp() + ".snap");
        try (OutputStream out = Files.newOutputStream(file)) {
            NbtIo.write(gzip(record, owner), out, true, true);
        }
        return file;
    }

    /** Snapshots owned by a player, newest first. */
    public static List<Path> list(String owner) {
        Path folder = ownerFolder(owner);
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(folder)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".snap"))
                    .sorted(Comparator.comparing((Path path) -> path.getFileName().toString()).reversed())
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    public static Path byName(String owner, String name) {
        String target = name.toLowerCase(Locale.ROOT);
        for (Path path : list(owner)) {
            String fileName = path.getFileName().toString();
            if (fileName.equalsIgnoreCase(name) || fileName.toLowerCase(Locale.ROOT).startsWith(target)) {
                return path;
            }
        }
        return null;
    }

    /** The newest snapshot older than the given epoch millis. */
    public static Path before(String owner, long epochMillis) {
        return nearest(owner, epochMillis, true);
    }

    /** The oldest snapshot newer than the given epoch millis. */
    public static Path after(String owner, long epochMillis) {
        return nearest(owner, epochMillis, false);
    }

    private static Path nearest(String owner, long epochMillis, boolean before) {
        Path best = null;
        for (Path path : list(owner)) {
            long time = timestampOf(path);
            if (time < 0) {
                continue;
            }
            if (before ? time < epochMillis : time > epochMillis) {
                if (best == null || before ? time > timestampOf(best) : time < timestampOf(best)) {
                    best = path;
                }
            }
        }
        return best;
    }

    public static long timestampOf(Path path) {
        String name = path.getFileName().toString().replace(".snap", "");
        int dash = name.indexOf('-');
        try {
            return Long.parseLong(dash < 0 ? name : name.substring(0, dash));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public static NbtCompound read(Path path) throws IOException {
        return NbtIo.readNbtOrGzip(Files.readAllBytes(path));
    }

    /** Restores a snapshot, i.e. puts the recorded "before" states back. */
    public static int restore(EditSession session, NbtCompound snapshot) {
        int restored = 0;
        for (NbtCompound section : snapshot.getCompoundList("sections")) {
            int chunkX = section.getInt("x", 0);
            int chunkZ = section.getInt("z", 0);
            int sectionY = section.getInt("y", 0);
            int[] indices = section.getIntArray("i");
            int[] before = section.getIntArray("b");
            if (indices == null || before == null) {
                continue;
            }
            for (int i = 0; i < indices.length && i < before.length; i++) {
                int index = indices[i];
                int x = (chunkX << 4) + (index & 15);
                int z = (chunkZ << 4) + ((index >> 4) & 15);
                int y = (sectionY << 4) + ((index >> 8) & 15);
                if (session.setBlock(x, y, z, before[i])) {
                    restored++;
                }
            }
        }
        return restored;
    }

    /** The box covered by a snapshot, so {@code /snapshot sel} can select it. */
    public static BlockBox bounds(NbtCompound snapshot) {
        int[] min = snapshot.getIntArray("min");
        int[] max = snapshot.getIntArray("max");
        if (min == null || max == null || min.length < 3 || max.length < 3) {
            return null;
        }
        return new BlockBox(new BlockVector3(min[0], min[1], min[2]),
                new BlockVector3(max[0], max[1], max[2]));
    }

    /** Turns a snapshot back into a history record, for {@code /history import}. */
    public static History.Record toRecord(NbtCompound snapshot) {
        History.Record record = new History.Record(snapshot.getString("description", "imported snapshot"));
        for (NbtCompound section : snapshot.getCompoundList("sections")) {
            int chunkX = section.getInt("x", 0);
            int chunkZ = section.getInt("z", 0);
            int sectionY = section.getInt("y", 0);
            int[] indices = section.getIntArray("i");
            int[] before = section.getIntArray("b");
            int[] after = section.getIntArray("a");
            if (indices == null) {
                continue;
            }
            for (int i = 0; i < indices.length; i++) {
                int index = indices[i];
                int x = (chunkX << 4) + (index & 15);
                int z = (chunkZ << 4) + ((index >> 4) & 15);
                int y = (sectionY << 4) + ((index >> 8) & 15);
                int previous = before != null && i < before.length ? before[i] : 0;
                int current = after != null && i < after.length ? after[i] : previous;
                record.addChange(x, y, z, previous, current);
            }
        }
        return record;
    }

    private static NbtCompound gzip(History.Record record, String owner) {
        List<NbtCompound> sections = new ArrayList<>();
        int[] min = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
        int[] max = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (List<ChangeSet> sets : record.changes().values()) {
            for (ChangeSet set : sets) {
                NbtCompound section = new NbtCompound();
                section.putInt("x", set.chunkX());
                section.putInt("z", set.chunkZ());
                section.putInt("y", set.sectionY());
                section.putIntArray("i", shrink(set.indices(), set.size()));
                section.putIntArray("b", shrink(set.before(), set.size()));
                section.putIntArray("a", shrink(set.after(), set.size()));
                sections.add(section);
                for (int i = 0; i < set.size(); i++) {
                    int index = set.indices()[i];
                    int x = (set.chunkX() << 4) + (index & 15);
                    int z = (set.chunkZ() << 4) + ((index >> 4) & 15);
                    int y = (set.sectionY() << 4) + ((index >> 8) & 15);
                    min[0] = Math.min(min[0], x);
                    min[1] = Math.min(min[1], y);
                    min[2] = Math.min(min[2], z);
                    max[0] = Math.max(max[0], x);
                    max[1] = Math.max(max[1], y);
                    max[2] = Math.max(max[2], z);
                }
            }
        }
        NbtCompound root = new NbtCompound();
        root.putString("owner", owner);
        root.putString("description", record.description == null ? "" : record.description);
        root.putLong("time", System.currentTimeMillis());
        root.putInt("changes", record.changeCount());
        root.putIntArray("min", min[0] == Integer.MAX_VALUE ? new int[]{0, 0, 0} : min);
        root.putIntArray("max", max[0] == Integer.MIN_VALUE ? new int[]{0, 0, 0} : max);
        root.putList("sections", sections);
        return root;
    }

    private static int[] shrink(int[] source, int size) {
        if (source.length == size) {
            return source;
        }
        int[] copy = new int[size];
        System.arraycopy(source, 0, copy, 0, size);
        return copy;
    }

    private static Path ownerFolder(String owner) {
        String safe = owner == null || owner.isBlank()
                ? "console" : owner.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
        return directory().resolve(safe);
    }

    private static String timestamp() {
        return System.currentTimeMillis() + "-" + Integer.toHexString((int) (Math.random() * 0xFFFF));
    }

    /** Convenience for callers that only want the failure to be logged. */
    public static Path saveQuietly(History.Record record, String owner) {
        try {
            return save(record, owner);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
