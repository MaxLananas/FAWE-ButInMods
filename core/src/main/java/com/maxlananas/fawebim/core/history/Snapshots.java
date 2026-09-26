package com.maxlananas.fawebim.core.history;

import com.maxlananas.fawebim.core.extent.EditSession;
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
            throw com.maxlananas.fawebim.core.command.CommandRegistry.error(
                    "Snapshot directory not configured");
        }
        return directory;
    }

    /** Writes a history record to disk, named after the operation. */
    /**
     * Serialising a record takes as long as the edit was big, so the write is
     * handed to the writer the platform installed instead of running on the
     * thread that just finished the edit. Without one it runs inline, which is
     * what the tests and a plain JVM want.
     */
    private static volatile java.util.concurrent.Executor writer = Runnable::run;

    public static void setWriter(java.util.concurrent.Executor executor) {
        writer = executor == null ? Runnable::run : executor;
    }

    /** Writes a record off the editing thread, ignoring a failed write. */
    public static void saveAsync(History.Record record, String owner) {
        writer.execute(() -> {
            try {
                save(record, owner);
            } catch (IOException | RuntimeException e) {
                // A failing snapshot must never take an edit down with it.
            }
        });
    }

    public static Path save(History.Record record, String owner) throws IOException {
        Path folder = ownerFolder(owner);
        Files.createDirectories(folder);
        Path file = folder.resolve(timestamp() + ".snap");
        try (OutputStream out = Files.newOutputStream(file)) {
            NbtIo.write(of(record, owner), out, false, true);
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

    /**
     * Puts the biomes a snapshot recorded back into the world, which is what
     * {@code /snapshot restore -b} does.
     *
     * @return the number of biome cells restored
     */
    public static int restoreBiomes(EditSession session, NbtCompound snapshot) {
        int restored = 0;
        for (NbtCompound section : snapshot.getCompoundList("biomes")) {
            com.maxlananas.fawebim.core.history.BiomeChangeSet set =
                    new com.maxlananas.fawebim.core.history.BiomeChangeSet(
                            section.getInt("x", 0), section.getInt("z", 0), section.getInt("y", 0));
            int[] cells = section.getIntArray("i");
            int[] before = section.getIntArray("b");
            if (cells == null || before == null) {
                continue;
            }
            for (int i = 0; i < cells.length && i < before.length; i++) {
                set.restore(cells[i], before[i]);
            }
            restored += session.applyBiomeChangeSet(set, true);
        }
        return restored;
    }

    /**
     * Puts the entities a snapshot recorded back into the world, which is what
     * {@code /snapshot restore -e} does. Entities the edit removed are spawned
     * again; entities the edit added are removed, so the region ends up in the
     * state the snapshot describes.
     *
     * @return the number of entities restored
     */
    public static int restoreEntities(EditSession session, NbtCompound snapshot) {
        int restored = 0;
        for (NbtCompound entity : snapshot.getCompoundList("entities")) {
            com.maxlananas.fawebim.core.math.Vector3 position = new com.maxlananas.fawebim.core.math.Vector3(
                    entity.getDouble("x", 0), entity.getDouble("y", 0), entity.getDouble("z", 0));
            com.maxlananas.fawebim.core.world.EntityData data = new com.maxlananas.fawebim.core.world.EntityData(
                    entity.getString("type", "minecraft:pig"), entity.getCompound("nbt"), position);
            // A change recorded as "removed" was undone by putting the entity
            // back; one recorded as added is taken away again.
            session.getWorld().getEntities(com.maxlananas.fawebim.core.world.Extent.Region3i.of(
                            position.toBlockPoint(), position.toBlockPoint().add(1, 1, 1)))
                    .stream()
                    .filter(existing -> existing.type().equals(data.type()))
                    .findFirst()
                    .ifPresent(existing -> session.getWorld().removeEntity(existing));
            if (entity.getBoolean("removed", false)) {
                session.addEntity(data);
                restored++;
            }
        }
        return restored;
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
        for (NbtCompound section : snapshot.getCompoundList("biomes")) {
            int chunkX = section.getInt("x", 0);
            int chunkZ = section.getInt("z", 0);
            int sectionY = section.getInt("y", 0);
            int[] cells = section.getIntArray("i");
            int[] before = section.getIntArray("b");
            int[] after = section.getIntArray("a");
            if (cells == null) {
                continue;
            }
            for (int i = 0; i < cells.length; i++) {
                int cell = cells[i];
                int x = (chunkX << 4) + ((cell & 3) << 2);
                int y = (sectionY << 4) + (((cell >> 4) & 3) << 2);
                int z = (chunkZ << 4) + (((cell >> 2) & 3) << 2);
                int previous = before != null && i < before.length ? before[i] : 0;
                int current = after != null && i < after.length ? after[i] : previous;
                record.addBiome(x, y, z, previous, current);
            }
        }
        return record;
    }

    /** The snapshot form of a record, as it is written to disk. */
    public static NbtCompound of(History.Record record, String owner) {
        return of(record, owner, System.currentTimeMillis());
    }

    /** Serialises a record with the timestamp of the edit, not of the write. */
    public static NbtCompound of(History.Record record, String owner, long time) {
        List<NbtCompound> sections = new ArrayList<>();
        int[] min = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
        int[] max = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (List<ChangeSet> sets : record.changes().values()) {
            for (ChangeSet set : sets) {
                NbtCompound section = new NbtCompound();
                section.putInt("x", set.chunkX());
                section.putInt("z", set.chunkZ());
                section.putInt("y", set.sectionY());
                section.putIntArray("i", set.cells());
                section.putIntArray("b", set.beforeStates());
                section.putIntArray("a", set.afterStates());
                sections.add(section);
                for (int i = 0; i < set.size(); i++) {
                    int index = set.cellAt(i);
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
        root.putLong("time", time);
        root.putInt("changes", record.changeCount());
        root.putIntArray("min", min[0] == Integer.MAX_VALUE ? new int[]{0, 0, 0} : min);
        root.putIntArray("max", max[0] == Integer.MIN_VALUE ? new int[]{0, 0, 0} : max);
        root.putList("sections", sections);
        List<NbtCompound> biomes = new ArrayList<>();
        for (List<com.maxlananas.fawebim.core.history.BiomeChangeSet> sets : record.biomeChanges().values()) {
            for (com.maxlananas.fawebim.core.history.BiomeChangeSet set : sets) {
                NbtCompound section = new NbtCompound();
                section.putInt("x", set.chunkX());
                section.putInt("z", set.chunkZ());
                section.putInt("y", set.sectionY());
                section.putIntArray("i", shrink(set.cells(), set.size()));
                section.putIntArray("b", shrink(set.before(), set.size()));
                section.putIntArray("a", shrink(set.after(), set.size()));
                biomes.add(section);
            }
        }
        // Biome and entity restore are opt-in on /snapshot restore (-b, -e), so
        // both are written only when the record actually holds them.
        if (!biomes.isEmpty()) {
            root.putList("biomes", biomes);
            root.putInt("biomeChanges", record.biomeChangeCount());
        }
        List<NbtCompound> entities = new ArrayList<>();
        for (History.EntityChange change : record.entities()) {
            NbtCompound entity = new NbtCompound();
            entity.putString("type", change.type);
            entity.putDouble("x", change.x);
            entity.putDouble("y", change.y);
            entity.putDouble("z", change.z);
            entity.putBoolean("removed", change.removed);
            if (change.nbt != null) {
                entity.putCompound("nbt", change.nbt);
            }
            entities.add(entity);
        }
        if (!entities.isEmpty()) {
            root.putList("entities", entities);
        }
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
