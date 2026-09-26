package com.maxlananas.fawebim.core.history;

import com.maxlananas.fawebim.core.extent.EditSession;
import com.maxlananas.fawebim.core.util.NbtCompound;
import com.maxlananas.fawebim.core.util.NbtIo;
import com.maxlananas.fawebim.core.world.BlockState;
import com.maxlananas.fawebim.core.world.BlockStateRegistry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.IntUnaryOperator;
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

    /**
     * Writes a record off the editing thread; a failed write is logged. Nothing
     * is written before the platform names the snapshot folder, which it does
     * when the server starts.
     */
    public static void saveAsync(History.Record record, String owner) {
        if (directory == null) {
            return;
        }
        writer.execute(() -> {
            try {
                save(record, owner);
            } catch (IOException | RuntimeException e) {
                // A failing snapshot must never take an edit down with it.
                com.maxlananas.fawebim.core.platform.Log.warn("Could not write the snapshot of '"
                        + record.description + "' for " + owner, e);
            }
        });
    }

    public static Path save(History.Record record, String owner) throws IOException {
        Path file = ownerFolder(owner).resolve(timestamp() + ".snap");
        NbtCompound snapshot = of(record, owner);
        return com.maxlananas.fawebim.core.util.AtomicFiles.write(file,
                out -> NbtIo.write(snapshot, out, false, true));
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
        long bestTime = 0;
        for (Path path : list(owner)) {
            long time = timestampOf(path);
            if (time < 0) {
                continue;
            }
            if (before ? time < epochMillis : time > epochMillis) {
                if (best == null || (before ? time > bestTime : time < bestTime)) {
                    best = path;
                    bestTime = time;
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

    /**
     * Restores a snapshot, i.e. puts the recorded "before" states back.
     *
     * <p>The sections and their rows are walked back to front: a cell the edit
     * wrote twice is in the snapshot twice, and only the first row holds the
     * state it had before the edit.</p>
     */
    public static int restore(EditSession session, NbtCompound snapshot) {
        int restored = 0;
        IntUnaryOperator states = blockPalette(snapshot);
        List<NbtCompound> sections = snapshot.getCompoundList("sections");
        for (int s = sections.size() - 1; s >= 0; s--) {
            NbtCompound section = sections.get(s);
            int chunkX = section.getInt("x", 0);
            int chunkZ = section.getInt("z", 0);
            int sectionY = section.getInt("y", 0);
            int[] indices = section.getIntArray("i");
            int[] before = section.getIntArray("b");
            for (int i = Math.min(indices.length, before.length) - 1; i >= 0; i--) {
                int index = indices[i];
                int x = (chunkX << 4) + (index & 15);
                int z = (chunkZ << 4) + ((index >> 4) & 15);
                int y = (sectionY << 4) + ((index >> 8) & 15);
                if (session.setBlock(x, y, z, states.applyAsInt(before[i]))) {
                    restored++;
                }
            }
        }
        // The data of the block entities goes back after their blocks, last change first.
        List<NbtCompound> blockEntities = snapshot.getCompoundList("blockEntities");
        for (int i = blockEntities.size() - 1; i >= 0; i--) {
            NbtCompound change = blockEntities.get(i);
            NbtCompound before = change.getCompoundOrNull("b");
            if (before != null) {
                session.setBlockEntity(change.getInt("x", 0), change.getInt("y", 0), change.getInt("z", 0), before);
            }
        }
        return restored;
    }

    /**
     * How the block states of a snapshot read back. A snapshot names its states
     * in a palette, so it restores the same blocks after the game or its mods
     * change the numbering; one written before the palette holds this server's
     * numbers, which only mean the same blocks on the same game.
     */
    private static IntUnaryOperator blockPalette(NbtCompound snapshot) {
        List<Object> names = snapshot.get("palette") instanceof List<?> list ? new ArrayList<>(list) : null;
        if (names == null) {
            return IntUnaryOperator.identity();
        }
        BlockStateRegistry registry = BlockState.registry();
        int air = registry.air();
        int[] states = new int[names.size()];
        for (int i = 0; i < states.length; i++) {
            int state = names.get(i) instanceof String name ? registry.parse(name) : -1;
            states[i] = state < 0 ? air : state;
        }
        return index -> index >= 0 && index < states.length ? states[index] : air;
    }

    /** The same for biomes: {@code biomePalette} names them, older snapshots number them. */
    private static IntUnaryOperator biomePalette(NbtCompound snapshot) {
        List<Object> names = snapshot.get("biomePalette") instanceof List<?> list ? new ArrayList<>(list) : null;
        if (names == null) {
            return IntUnaryOperator.identity();
        }
        BlockStateRegistry registry = BlockState.registry();
        int[] biomes = new int[names.size()];
        for (int i = 0; i < biomes.length; i++) {
            biomes[i] = names.get(i) instanceof String name ? registry.biome(name) : -1;
        }
        return index -> index >= 0 && index < biomes.length ? biomes[index] : -1;
    }

    /**
     * Puts the biomes a snapshot recorded back into the world, which is what
     * {@code /snapshot restore -b} does.
     *
     * @return the number of biome cells restored
     */
    public static int restoreBiomes(EditSession session, NbtCompound snapshot) {
        int restored = 0;
        IntUnaryOperator biomes = biomePalette(snapshot);
        List<NbtCompound> sections = snapshot.getCompoundList("biomes");
        for (int s = sections.size() - 1; s >= 0; s--) {
            NbtCompound section = sections.get(s);
            com.maxlananas.fawebim.core.history.BiomeChangeSet set =
                    new com.maxlananas.fawebim.core.history.BiomeChangeSet(
                            section.getInt("x", 0), section.getInt("z", 0), section.getInt("y", 0));
            int[] cells = section.getIntArray("i");
            int[] before = section.getIntArray("b");
            if (cells == null || before == null) {
                continue;
            }
            for (int i = 0; i < cells.length && i < before.length; i++) {
                int biome = biomes.applyAsInt(before[i]);
                if (biome >= 0) {
                    set.restore(cells[i], biome);
                }
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
        IntUnaryOperator states = blockPalette(snapshot);
        IntUnaryOperator biomes = biomePalette(snapshot);
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
                int previous = i < before.length ? states.applyAsInt(before[i]) : BlockState.registry().air();
                int current = i < after.length ? states.applyAsInt(after[i]) : previous;
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
                int previous = i < before.length ? biomes.applyAsInt(before[i]) : -1;
                int current = i < after.length ? biomes.applyAsInt(after[i]) : previous;
                if (previous >= 0 && current >= 0) {
                    record.addBiome(x, y, z, previous, current);
                }
            }
        }
        for (NbtCompound change : snapshot.getCompoundList("blockEntities")) {
            record.addBlockEntity(change.getInt("x", 0), change.getInt("y", 0), change.getInt("z", 0),
                    change.getCompoundOrNull("b"), change.getCompoundOrNull("a"));
        }
        return record;
    }

    /** The snapshot form of a record, as it is written to disk. */
    public static NbtCompound of(History.Record record, String owner) {
        return of(record, owner, System.currentTimeMillis());
    }

    /** Serialises a record with the timestamp of the edit, not of the write. */
    public static NbtCompound of(History.Record record, String owner, long time) {
        BlockStateRegistry registry = BlockState.registry();
        Palette blocks = new Palette(registry::describe);
        Palette biomeNames = new Palette(registry::biomeName);
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
                section.putIntArray("b", blocks.indices(set.beforeStates()));
                section.putIntArray("a", blocks.indices(set.afterStates()));
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
                section.putIntArray("b", biomeNames.indices(shrink(set.before(), set.size()).clone()));
                section.putIntArray("a", biomeNames.indices(shrink(set.after(), set.size()).clone()));
                biomes.add(section);
            }
        }
        // Biome and entity restore are opt-in on /snapshot restore (-b, -e), so
        // both are written only when the record actually holds them.
        if (!biomes.isEmpty()) {
            root.putList("biomes", biomes);
            root.putInt("biomeChanges", record.biomeChangeCount());
            root.putList("biomePalette", biomeNames.names());
        }
        root.putList("palette", blocks.names());
        List<NbtCompound> blockEntities = new ArrayList<>();
        for (History.BlockEntityChange change : record.blockEntities()) {
            NbtCompound entry = new NbtCompound().putInt("x", change.x()).putInt("y", change.y())
                    .putInt("z", change.z());
            if (change.before() != null) {
                entry.putCompound("b", change.before());
            }
            if (change.after() != null) {
                entry.putCompound("a", change.after());
            }
            blockEntities.add(entry);
        }
        if (!blockEntities.isEmpty()) {
            root.putList("blockEntities", blockEntities);
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

    /** Numbers the states or biomes a snapshot uses in the order it meets them, and names them. */
    private static final class Palette {

        private final java.util.function.IntFunction<String> namer;
        private final com.maxlananas.fawebim.core.util.LongObjectMap<Integer> indexOf =
                new com.maxlananas.fawebim.core.util.LongObjectMap<>();
        private final List<String> names = new ArrayList<>();

        Palette(java.util.function.IntFunction<String> namer) {
            this.namer = namer;
        }

        /** Replaces every id of the array, which the caller owns, by its palette index. */
        int[] indices(int[] ids) {
            for (int i = 0; i < ids.length; i++) {
                Integer index = indexOf.get(ids[i]);
                if (index == null) {
                    index = names.size();
                    indexOf.put(ids[i], index);
                    names.add(namer.apply(ids[i]));
                }
                ids[i] = index;
            }
            return ids;
        }

        List<String> names() {
            return names;
        }
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

    /** Makes two snapshots of the same millisecond land in two files. */
    private static final java.util.concurrent.atomic.AtomicInteger SEQUENCE =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * The name of a new snapshot: its time, which the date lookups read back,
     * then a sequence number. A random suffix could repeat within a
     * millisecond and overwrite the snapshot written just before.
     */
    private static String timestamp() {
        return System.currentTimeMillis() + "-" + Integer.toHexString(SEQUENCE.incrementAndGet());
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
