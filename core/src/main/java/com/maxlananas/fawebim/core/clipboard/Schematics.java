package com.maxlananas.fawebim.core.clipboard;

import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.platform.Config;
import com.maxlananas.fawebim.core.util.NbtCompound;
import com.maxlananas.fawebim.core.util.NbtIo;
import com.maxlananas.fawebim.core.world.BlockState;
import com.maxlananas.fawebim.core.world.BlockStateRegistry;
import com.maxlananas.fawebim.core.world.EntityData;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Schematic reading and writing.
 *
 * <p>All the formats WorldEdit/FAWE 7.3.17 can read or write are supported:</p>
 * <ul>
 *   <li>Sponge schematic v1, v2 and v3 ({@code .schem}) — v3 uses varint
 *       payloads, hence the version sniffing below,</li>
 *   <li>the legacy MCEdit {@code .schematic} format (WE {@code -f mcedit}),</li>
 *   <li>structure block {@code .nbt} files.</li>
 * </ul>
 */
public final class Schematics {

    private Schematics() {
    }

    private static Path directory;

    /** Sets the directory schematics are read from and written to. */
    public static void setDirectory(Path dir) {
        directory = dir;
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            // Ignored: listing will simply be empty.
        }
    }

    public static Path directory() {
        if (directory == null) {
            directory = Path.of(Config.get().schematicSaveDirectory);
        }
        return directory;
    }

    /** The schematic formats that can be read and written. */
    public static List<String> formats() {
        return List.of("sponge.3", "sponge.2", "mcedit", "schem", "structure");
    }

    public static List<String> list() {
        return list(ListFilter.ALL, null);
    }

    /**
     * Lists schematics, optionally only the shared ones or only the player's own.
     *
     * @param owner the player whose directory {@link ListFilter#LOCAL} reads
     */
    public static List<String> list(ListFilter filter, String owner) {
        List<String> names = new ArrayList<>();
        if (filter != ListFilter.LOCAL) {
            collect(directory(), names);
        }
        if (filter != ListFilter.GLOBAL && owner != null) {
            collect(directory().resolve(owner.toLowerCase(java.util.Locale.ROOT)), names);
        }
        names.sort(String::compareToIgnoreCase);
        return names;
    }

    /** The format a listed name was written in, derived from its extension. */
    public static String formatOf(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".schem")) {
            return "sponge.3";
        }
        if (lower.endsWith(".schematic")) {
            return "mcedit";
        }
        if (lower.endsWith(".nbt")) {
            return "structure";
        }
        return "unknown";
    }

    /** True when a schematic of that name already exists in the directory. */
    public static boolean exists(String name, String format) {
        Path folder = directory();
        for (String candidate : List.of(name, name + suffixOf(format))) {
            if (Files.isRegularFile(folder.resolve(candidate))) {
                return true;
            }
        }
        for (String listed : list()) {
            if (listed.equalsIgnoreCase(name) || listed.toLowerCase(java.util.Locale.ROOT)
                    .startsWith(name.toLowerCase(java.util.Locale.ROOT) + ".")) {
                return true;
            }
        }
        return false;
    }

    /** The file suffix of a format name, {@code .schem} for {@code sponge.3}. */
    public static String suffixOf(String format) {
        String key = format == null ? "" : format.toLowerCase(java.util.Locale.ROOT);
        return switch (key) {
            case "mcedit", "schematic", "mcedit2" -> ".schematic";
            case "structure", "nbt" -> ".nbt";
            case "sponge.2" -> ".schem";
            default -> ".schem";
        };
    }

    /** When the file behind a listed schematic was last written, or -1. */
    public static long timeOf(String name) {
        for (Path folder : List.of(directory(), directory().resolve("global"))) {
            Path file = folder.resolve(name);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try {
                return Files.getLastModifiedTime(file).toMillis();
            } catch (IOException e) {
                return -1;
            }
        }
        return -1;
    }

    /** The write time of a listed schematic, formatted for {@code //schem list -d}. */
    public static String lastModified(String name) {
        long time = timeOf(name);
        return time < 0 ? "unknown" : java.time.Instant.ofEpochMilli(time)
                .atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    }

    private static void collect(Path folder, List<String> names) {
        if (!Files.isDirectory(folder)) {
            return;
        }
        try (Stream<Path> files = Files.list(folder)) {
            files.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(Schematics::isSchematic)
                    .forEach(names::add);
        } catch (IOException e) {
            // An unreadable directory simply lists nothing.
        }
    }

    private static boolean isSchematic(String name) {
        return name.endsWith(".schem") || name.endsWith(".schematic") || name.endsWith(".nbt");
    }

    /**
     * Loads every schematic of the folder matching a glob, as {@code /schem loadall}
     * does; the pool it fills is what {@code //paste} then picks from at random.
     *
     * @param format the format name the command was given, kept for the message
     * @param glob   a file name pattern, {@code *} for everything
     */
    public static List<BlockArrayClipboard> loadAll(String format, String glob) {
        java.nio.file.PathMatcher matcher = java.nio.file.FileSystems.getDefault()
                .getPathMatcher("glob:" + (glob == null || glob.isBlank() ? "*" : glob));
        List<BlockArrayClipboard> loaded = new ArrayList<>();
        for (String name : list(ListFilter.ALL, null)) {
            if (!matcher.matches(java.nio.file.Path.of(name))) {
                continue;
            }
            try {
                loaded.add(load(name));
            } catch (RuntimeException e) {
                // A file that is not a readable schematic is skipped, like FAWE does.
            }
        }
        return loaded;
    }

    public static void delete(String name) {
        try {
            Files.deleteIfExists(resolveExisting(name));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("Could not delete schematic '" + name + "'", e);
        }
    }

    /**
     * The file a name reads from, whatever extension it was written with.
     *
     * <p>{@code //schem save house} writes {@code house.schem}, so
     * {@code //schem load house} has to find it: the load tries the name as
     * typed and then the extension of every format the writer can produce, which
     * is the resolution WorldEdit and FAWE do for the same reason.</p>
     */
    private static Path resolveExisting(String name) {
        Path exact = resolve(name);
        if (Files.isRegularFile(exact)) {
            return exact;
        }
        Path folder = directory();
        for (String extension : FILE_EXTENSIONS) {
            Path candidate = folder.resolve(name + extension);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return exact;
    }

    /** The extensions a schematic file can carry, in the order a load tries them. */
    private static final List<String> FILE_EXTENSIONS =
            List.of(".schem", ".schematic", ".nbt");

    private static Path resolve(String name) {
        Path path = directory().resolve(name);
        if (!path.getFileName().toString().equals(name)) {
            throw new com.maxlananas.fawebim.core.util.InputException("Invalid schematic name '" + name + "'");
        }
        if (!com.maxlananas.fawebim.core.platform.Config.get().allowSymlinks && Files.isSymbolicLink(path)) {
            throw new com.maxlananas.fawebim.core.util.InputException("Symbolic links are disabled"
                    + " (files.allow-symbolic-links in config/fawebim.yml)");
        }
        return path;
    }

    /**
     * Above this many blocks, {@code //schem save} hands the disk write to the
     * world's worker pool instead of blocking the server thread.
     */
    public static final int ASYNC_SAVE_THRESHOLD = 1_000_000;

    /** Writes the clipboard using the given format ({@code sponge.3}, {@code sponge.2}, {@code mcedit}). */
    public static void save(BlockArrayClipboard clipboard, String name, String format) {
        write(serialize(clipboard, name, format));
    }

    /**
     * Serialises the clipboard and writes the result on the given executor, which
     * is how FAWE keeps a large save from stalling the tick loop. Serialising
     * itself stays on the calling thread, because it reads the clipboard.
     *
     * @return a future that completes with the file that was written
     */
    public static java.util.concurrent.CompletableFuture<Path> saveAsync(BlockArrayClipboard clipboard, String name,
                                                                          String format,
                                                                          java.util.concurrent.Executor executor) {
        Serialized data = serialize(clipboard, name, format);
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> write(data), executor);
    }

    /** The file name the format produces and the bytes of the schematic. */
    private record Serialized(String fileName, byte[] data) {
    }

    private static Serialized serialize(BlockArrayClipboard clipboard, String name, String format) {
        String lower = format.toLowerCase(Locale.ROOT);
        NbtCompound root;
        String fileName = name;
        if (lower.startsWith("mcedit") || lower.startsWith("legacy")) {
            root = writeMcedit(clipboard);
            if (!fileName.endsWith(".schematic")) {
                fileName = fileName + ".schematic";
            }
        } else if (lower.startsWith("structure") || lower.startsWith("nbt")) {
            root = writeStructure(clipboard);
            if (!fileName.endsWith(".nbt")) {
                fileName = fileName + ".nbt";
            }
        } else {
            int version = lower.endsWith("2") ? 2 : lower.endsWith("1") ? 1 : 3;
            root = writeSponge(clipboard, version);
            if (!fileName.endsWith(".schem")) {
                fileName = fileName + ".schem";
            }
        }
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            if (lower.startsWith("mcedit") || lower.startsWith("legacy")) {
                // MCEdit files are plain (uncompressed) NBT.
                NbtIo.write(root, buffer, false, false);
            } else if (version3(lower)) {
                // Sponge v3: varint payload, gzipped on disk (same as FAWE).
                NbtIo.write(root, buffer, true, true);
            } else {
                // Sponge v2 and the legacy schematic format: gzipped NBT.
                NbtIo.write(root, buffer, false, true);
            }
            return new Serialized(fileName, buffer.toByteArray());
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("Could not save schematic '" + name + "'", e);
        }
    }

    /**
     * Writes a serialised schematic in one step: overwriting a schematic with a
     * write that fails half way used to leave neither the old one nor the new.
     */
    private static Path write(Serialized data) {
        try {
            return com.maxlananas.fawebim.core.util.AtomicFiles.write(resolve(data.fileName()), data.data());
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("Could not save schematic '" + data.fileName() + "'", e);
        }
    }

    public static BlockArrayClipboard load(String name) {
        try {
            BlockArrayClipboard clipboard = readAll(resolveExisting(name), name);
            checkSize(clipboard, name);
            return clipboard;
        } catch (java.nio.file.NoSuchFileException e) {
            throw new com.maxlananas.fawebim.core.util.InputException("No schematic named '" + name + "'");
        } catch (java.util.zip.ZipException | java.io.EOFException | java.io.UTFDataFormatException e) {
            throw new com.maxlananas.fawebim.core.util.InputException("'" + name
                    + "' is not a readable schematic file");
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("Could not load schematic '" + name + "'", e);
        }
    }

    /** Reads a schematic file, whatever layout it uses. */
    private static BlockArrayClipboard readAll(Path path, String name) throws IOException {
        byte[] data = Files.readAllBytes(path);
        NbtCompound root = readAny(data);
        if (root == null) {
            throw new com.maxlananas.fawebim.core.util.InputException("Corrupted schematic file");
        }
        return readDetected(root, name);
    }

    /** Refuses a schematic bigger than {@code limits.max-schematic-size}. */
    private static void checkSize(BlockArrayClipboard clipboard, String name) {
        int maximum = com.maxlananas.fawebim.core.platform.Config.get().maxSchematicSize;
        if (maximum <= 0) {
            return;
        }
        long volume = (long) clipboard.getWidth() * clipboard.getHeight() * clipboard.getLength();
        if (volume > maximum) {
            throw new com.maxlananas.fawebim.core.util.InputException("Schematic '" + name + "' holds " + volume
                    + " blocks, more than limits.max-schematic-size (" + maximum + ")");
        }
    }

    public static BlockArrayClipboard fromBytes(byte[] data, String name) {
        NbtCompound root = readAny(data);
        if (root == null) {
            throw new com.maxlananas.fawebim.core.util.InputException("Corrupted schematic data");
        }
        if (root.contains("Palette") || root.contains("BlockData") || root.contains("Blocks") && root.contains("Palette")) {
            return readSponge(root, name == null ? "schematic" : name);
        }
        if (root.contains("Schematic")) {
            return readMcedit(root);
        }
        return readSponge(root, name == null ? "schematic" : name);
    }

    /**
     * Works out which of the four supported containers the NBT is: Sponge v1-3
     * (flat, or nested under {@code Schematic} as in v3), MCEdit or structure blocks.
     */
    private static BlockArrayClipboard readDetected(NbtCompound root, String name) {
        NbtCompound nested = root.getCompoundOrNull("Schematic");
        if (nested != null) {
            if (nested.contains("Palette") || nested.contains("BlockData") || nested.contains("Data")) {
                return readSponge(root, name);
            }
            if (nested.contains("Blocks")) {
                return readMcedit(root);
            }
        }
        if (root.contains("Palette") || root.contains("BlockData")) {
            return readSponge(root, name);
        }
        if (root.contains("palette") && root.contains("blocks")) {
            return readStructure(root);
        }
        if (root.contains("Blocks")) {
            return readMcedit(root);
        }
        return readSponge(root, name);
    }

    // ---------------------------------------------------------------- Sponge

    private static boolean version3(String format) {
        return format.startsWith("sponge.3") || format.equals("sponge") || format.equals("schem");
    }

    private static NbtCompound writeSponge(BlockArrayClipboard clipboard, int version) {
        BlockVector3 origin = clipboard.getOrigin();
        int width = clipboard.getWidth();
        int height = clipboard.getHeight();
        int length = clipboard.getLength();

        // Build the palette in encounter order; FAWE writes the air state as index 0.
        List<Integer> paletteStates = new ArrayList<>();
        java.util.Map<Integer, Integer> paletteIds = new java.util.HashMap<>();
        int airId = BlockState.registry().air();
        paletteIds.put(airId, 0);
        paletteStates.add(airId);

        int[] data = new int[width * height * length];
        int index = 0;
        for (int y = 0; y < height; y++) {
            for (int z = 0; z < length; z++) {
                for (int x = 0; x < width; x++) {
                    int state = clipboard.getBlock(origin.x() + x, origin.y() + y, origin.z() + z);
                    int paletteIndex = paletteIds.computeIfAbsent(state, key -> {
                        paletteStates.add(key);
                        return paletteIds.size();
                    });
                    data[index++] = paletteIndex;
                }
            }
        }

        NbtCompound root = new NbtCompound();
        if (version >= 3) {
            root.putInt("Version", 3);
            root.putInt("DataVersion", 4189);
        } else {
            root.putInt("Version", version);
            root.putInt("DataVersion", version == 2 ? 2975 : 1631);
        }
        root.putInt("Width", width);
        root.putInt("Height", height);
        root.putInt("Length", length);
        NbtCompound offset = new NbtCompound();
        offset.putInt("x", origin.x());
        offset.putInt("y", origin.y());
        offset.putInt("z", origin.z());
        root.put("Offset", offset);

        if (version >= 3) {
            NbtCompound schem = new NbtCompound();
            schem.putInt("Version", 3);
            NbtCompound palette = new NbtCompound();
            for (int i = 0; i < paletteStates.size(); i++) {
                palette.putInt(BlockState.registry().describe(paletteStates.get(i)), i);
            }
            schem.put("Palette", palette);
            schem.putByteArray("Data", packVarInts(data));
            schem.put("BlockEntities", blockEntities(clipboard, origin));
            if (!clipboard.entities().isEmpty()) {
                schem.put("Entities", entities(clipboard, origin));
            }
            root.put("Schematic", schem);
        } else {
            NbtCompound palette = new NbtCompound();
            for (int i = 0; i < paletteStates.size(); i++) {
                palette.putInt(BlockState.registry().describe(paletteStates.get(i)), i);
            }
            root.put("Palette", palette);
            root.putByteArray("BlockData", packShorts(data));
            if (version == 2) {
                root.put("BlockEntities", blockEntities(clipboard, origin));
                if (!clipboard.entities().isEmpty()) {
                    root.put("Entities", entities(clipboard, origin));
                }
            } else {
                // v1 splits metadata/block entities differently.
                NbtCompound blockEntities = blockEntities(clipboard, origin);
                root.put("TileEntities", new java.util.ArrayList<>(blockEntities.values()));
            }
        }
        return root;
    }

    private static BlockArrayClipboard readSponge(NbtCompound root, String name) {
        NbtCompound schem = root.contains("Schematic") ? root.getCompound("Schematic") : root;
        int version = root.contains("Version") ? root.getInt("Version", 0) : schem.getInt("Version", 0);
        int width = schem.contains("Width") ? schem.getInt("Width", 0) : root.getInt("Width", 0);
        int height = schem.contains("Height") ? schem.getInt("Height", 0) : root.getInt("Height", 0);
        int length = schem.contains("Length") ? schem.getInt("Length", 0) : root.getInt("Length", 0);
        if (width <= 0 || height <= 0 || length <= 0) {
            throw new com.maxlananas.fawebim.core.util.InputException("Schematic has no size information");
        }
        NbtCompound palette = schem.contains("Palette") ? schem.getCompound("Palette")
                : root.getCompound("Palette");
        java.util.List<String> names = new ArrayList<>(palette.keySet().size());
        for (String key : palette.keySet()) {
            names.add(key);
        }
        java.util.Map<Integer, Integer> paletteIds = new java.util.HashMap<>();
        int maxIndex = 0;
        for (String key : names) {
            int paletteIndex = palette.getInt(key, 0);
            maxIndex = Math.max(maxIndex, paletteIndex);
            int state = BlockState.registry().parse(key);
            if (state < 0) {
                state = BlockState.registry().air();
            }
            paletteIds.put(paletteIndex, state);
        }
        int[] paletteStates = new int[maxIndex + 1];
        for (java.util.Map.Entry<Integer, Integer> entry : paletteIds.entrySet()) {
            paletteStates[entry.getKey()] = entry.getValue();
        }

        byte[] payload = schem.contains("Data") ? schem.getByteArray("Data")
                : schem.contains("BlockData") ? schem.getByteArray("BlockData") : root.getByteArray("BlockData");
        int[] indices = version >= 3 ? readVarInts(payload, width * height * length)
                : readShorts(payload, width * height * length);

        BlockArrayClipboard clipboard = new BlockArrayClipboard(BlockVector3.ZERO);
        clipboard.setName(name);
        int index = 0;
        for (int y = 0; y < height; y++) {
            for (int z = 0; z < length; z++) {
                for (int x = 0; x < width; x++) {
                    int paletteIndex = indices[index++];
                    int state = paletteIndex >= 0 && paletteIndex < paletteStates.length
                            ? paletteStates[paletteIndex] : BlockState.registry().air();
                    clipboard.setBlock(x, y, z, state);
                }
            }
        }
        // Block entities.
        NbtCompound blockEntities = schem.contains("BlockEntities") ? schem.getCompound("BlockEntities")
                : root.contains("BlockEntities") ? root.getCompound("BlockEntities")
                : root.contains("TileEntities") ? root.getCompound("TileEntities") : null;
        if (blockEntities != null) {
            for (Object value : blockEntities.values()) {
                if (value instanceof NbtCompound compound) {
                    int[] pos = compound.contains("Pos") ? ints(compound.getList("Pos")) : new int[]{0, 0, 0};
                    clipboard.addBlockEntity(new BlockVector3(pos[0], pos[1], pos[2]), compound);
                }
            }
        }
        clipboard.normalize();
        return clipboard;
    }

    private static int[] ints(java.util.List<Object> values) {
        int[] out = new int[values.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = (int) Double.parseDouble(String.valueOf(values.get(i)));
        }
        return out;
    }

    private static NbtCompound blockEntities(BlockArrayClipboard clipboard, BlockVector3 origin) {
        NbtCompound list = new NbtCompound();
        int i = 0;
        for (var entry : clipboard.blockEntities().entrySet()) {
            NbtCompound compound = entry.getValue();
            // Positions must be relative to the schematic origin.
            NbtCompound pos = new NbtCompound();
            pos.putInt("x", entry.getKey().x() - origin.x());
            pos.putInt("y", entry.getKey().y() - origin.y());
            pos.putInt("z", entry.getKey().z() - origin.z());
            compound.put("Pos", pos);
            list.put("be" + i++, compound);
        }
        return list;
    }

    private static NbtCompound entities(BlockArrayClipboard clipboard, BlockVector3 origin) {
        NbtCompound list = new NbtCompound();
        int i = 0;
        for (EntityData entity : clipboard.entities()) {
            NbtCompound compound = entity.nbt() == null ? new NbtCompound() : entity.nbt();
            NbtCompound pos = new NbtCompound();
            pos.putDouble("x", entity.position().x() - origin.x());
            pos.putDouble("y", entity.position().y() - origin.y());
            pos.putDouble("z", entity.position().z() - origin.z());
            compound.put("Pos", pos);
            compound.putString("Id", entity.type());
            list.put("e" + i++, compound);
        }
        return list;
    }

    // ---------------------------------------------------------------- MCEdit

    /**
     * How many blocks of the clipboard the legacy format cannot name, so the
     * command can warn before writing them as air.
     */
    public static int legacyLosses(BlockArrayClipboard clipboard) {
        BlockVector3 origin = clipboard.getOrigin();
        BlockStateRegistry registry = BlockState.registry();
        int unsupported = 0;
        for (int y = 0; y < clipboard.getHeight(); y++) {
            for (int z = 0; z < clipboard.getLength(); z++) {
                for (int x = 0; x < clipboard.getWidth(); x++) {
                    int state = clipboard.getBlock(origin.x() + x, origin.y() + y, origin.z() + z);
                    if (!registry.isAirLike(state) && registry.legacyId(state) < 0) {
                        unsupported++;
                    }
                }
            }
        }
        return unsupported;
    }

    private static NbtCompound writeMcedit(BlockArrayClipboard clipboard) {
        BlockVector3 origin = clipboard.getOrigin();
        int width = clipboard.getWidth();
        int height = clipboard.getHeight();
        int length = clipboard.getLength();
        byte[] blocks = new byte[width * height * length];
        byte[] data = new byte[width * height * length];
        BlockStateRegistry registry = BlockState.registry();
        int index = 0;
        for (int y = 0; y < height; y++) {
            for (int z = 0; z < length; z++) {
                for (int x = 0; x < width; x++) {
                    int state = clipboard.getBlock(origin.x() + x, origin.y() + y, origin.z() + z);
                    // Blocks the legacy ids cannot express are written as air, which
                    // is what WorldEdit's MCEdit writer does.
                    int id = registry.legacyId(state);
                    blocks[index] = (byte) (id < 0 ? 0 : id);
                    data[index] = (byte) (id < 0 ? 0 : registry.legacyMetadata(state) & 0xF);
                    index++;
                }
            }
        }
        NbtCompound root = new NbtCompound();
        root.putString("Materials", "Alpha");
        root.putShort("Width", (short) width);
        root.putShort("Height", (short) height);
        root.putShort("Length", (short) length);
        root.putByteArray("Blocks", blocks);
        root.putByteArray("Data", data);
        root.put("Entities", entities(clipboard, origin));
        root.put("TileEntities", blockEntities(clipboard, origin));
        return root;
    }

    private static BlockArrayClipboard readMcedit(NbtCompound root) {
        NbtCompound schem = root.contains("Schematic") ? root.getCompound("Schematic") : root;
        int width = schem.getShort("Width", 0);
        int height = schem.getShort("Height", 0);
        int length = schem.getShort("Length", 0);
        byte[] blocks = schem.getByteArray("Blocks");
        byte[] data = schem.getByteArray("Data");
        BlockArrayClipboard clipboard = new BlockArrayClipboard(BlockVector3.ZERO);
        int index = 0;
        BlockStateRegistry registry = BlockState.registry();
        for (int y = 0; y < height; y++) {
            for (int z = 0; z < length; z++) {
                for (int x = 0; x < width; x++) {
                    int id = blocks[index] & 0xFF;
                    int meta = data.length > index ? data[index] & 0xFF : 0;
                    index++;
                    // WorldEdit only reads the numeric ids of old files when the
                    // configuration allows ancient blocks.
                    int state = com.maxlananas.fawebim.core.platform.Config.get().allowAncientBlocks
                            ? registry.legacyState(id, meta)
                            : registry.air();
                    clipboard.setBlock(x, y, z, state);
                }
            }
        }
        clipboard.normalize();
        return clipboard;
    }

    // -------------------------------------------------------------- structure

    private static NbtCompound writeStructure(BlockArrayClipboard clipboard) {
        BlockVector3 origin = clipboard.getOrigin();
        int width = clipboard.getWidth();
        int height = clipboard.getHeight();
        int length = clipboard.getLength();
        List<Integer> paletteStates = new ArrayList<>();
        java.util.Map<Integer, Integer> paletteIds = new java.util.HashMap<>();
        java.util.List<NbtCompound> paletteEntries = new ArrayList<>();
        java.util.List<NbtCompound> blocks = new ArrayList<>();
        for (int y = 0; y < height; y++) {
            for (int z = 0; z < length; z++) {
                for (int x = 0; x < width; x++) {
                    int state = clipboard.getBlock(origin.x() + x, origin.y() + y, origin.z() + z);
                    if (BlockState.registry().isAirLike(state)) {
                        continue;
                    }
                    int paletteIndex = paletteIds.computeIfAbsent(state, key -> {
                        paletteStates.add(key);
                        NbtCompound entry = new NbtCompound();
                        entry.putString("Name", BlockState.registry().name(key));
                        java.util.Map<String, String> properties = BlockState.registry().properties(key);
                        if (!properties.isEmpty()) {
                            NbtCompound props = new NbtCompound();
                            properties.forEach(props::putString);
                            entry.put("Properties", props);
                        }
                        paletteEntries.add(entry);
                        return paletteIds.size();
                    });
                    NbtCompound block = new NbtCompound();
                    block.putIntArray("pos", new int[]{x, y, z});
                    block.putInt("state", paletteIndex);
                    blocks.add(block);
                }
            }
        }
        NbtCompound root = new NbtCompound();
        root.putIntArray("size", new int[]{width, height, length});
        root.putList("palette", paletteEntries);
        root.putList("blocks", blocks);
        root.put("entities", new java.util.ArrayList<>());
        return root;
    }

    private static BlockArrayClipboard readStructure(NbtCompound root) {
        java.util.List<Object> palette = root.getList("palette");
        List<Integer> states = new ArrayList<>();
        for (Object entry : palette) {
            if (entry instanceof NbtCompound compound) {
                StringBuilder name = new StringBuilder(compound.getString("Name", "minecraft:air"));
                NbtCompound properties = compound.getCompound("Properties");
                if (properties != null && !properties.keySet().isEmpty()) {
                    name.append('[');
                    boolean first = true;
                    for (String key : properties.keySet()) {
                        if (!first) {
                            name.append(',');
                        }
                        name.append(key).append('=').append(properties.getString(key, ""));
                        first = false;
                    }
                    name.append(']');
                }
                int state = BlockState.registry().parse(name.toString());
                states.add(state < 0 ? BlockState.registry().air() : state);
            }
        }
        BlockArrayClipboard clipboard = new BlockArrayClipboard(BlockVector3.ZERO);
        for (Object entry : root.getList("blocks")) {
            if (entry instanceof NbtCompound compound) {
                java.util.List<Object> pos = compound.getList("pos");
                int x = Integer.parseInt(String.valueOf(pos.get(0)));
                int y = Integer.parseInt(String.valueOf(pos.get(1)));
                int z = Integer.parseInt(String.valueOf(pos.get(2)));
                int index = compound.getInt("state", 0);
                clipboard.setBlock(x, y, z, index < states.size() ? states.get(index)
                        : BlockState.registry().air());
            }
        }
        clipboard.normalize();
        return clipboard;
    }

    // ----------------------------------------------------------------- bytes

    /** Sponge v1/v2 pack the block data as big-endian shorts. */
    private static byte[] packShorts(int[] values) {
        byte[] out = new byte[values.length * 2];
        for (int i = 0; i < values.length; i++) {
            out[i * 2] = (byte) ((values[i] >> 8) & 0xFF);
            out[i * 2 + 1] = (byte) (values[i] & 0xFF);
        }
        return out;
    }

    /** Sponge v3 packs the block data as varints. */
    private static byte[] packVarInts(int[] values) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(values.length);
        for (int value : values) {
            int v = value;
            while ((v & ~0x7F) != 0) {
                out.write((v & 0x7F) | 0x80);
                v >>>= 7;
            }
            out.write(v);
        }
        return out.toByteArray();
    }

    private static int[] readVarInts(byte[] data, int count) {
        int[] values = new int[count];
        try (ByteArrayInputStream in = new ByteArrayInputStream(data)) {
            NbtIo.Reader reader = new NbtIo.Reader(in, false);
            for (int i = 0; i < count; i++) {
                values[i] = reader.readVarInt();
            }
        } catch (IOException e) {
            // Short file: remaining blocks stay air.
        }
        return values;
    }

    private static int[] readShorts(byte[] data, int count) {
        int[] values = new int[count];
        for (int i = 0; i < count && i * 2 + 1 < data.length; i++) {
            values[i] = ((data[i * 2] & 0xFF) << 8) | (data[i * 2 + 1] & 0xFF);
        }
        return values;
    }

    /**
     * Reads a schematic whatever the container is: plain NBT, gzipped NBT, plain
     * varint NBT (Sponge v3) or gzipped varint NBT.
     *
     * <p>The layout is guessed from the file and then checked: varint NBT read as
     * plain NBT yields a parse that happens to succeed for a large payload, so a
     * candidate only counts when the compound it produced is a schematic.</p>
     */
    private static NbtCompound readAny(byte[] data) {
        boolean gzipped = data.length > 2 && (data[0] & 0xFF) == 0x1F && (data[1] & 0xFF) == 0x8B;
        for (boolean varint : new boolean[]{gzipped, !gzipped}) {
            for (boolean gzip : new boolean[]{gzipped, false}) {
                try {
                    NbtCompound root = NbtIo.read(new ByteArrayInputStream(data), varint, gzip);
                    if (isSchematicRoot(root)) {
                        return root;
                    }
                } catch (Exception ignored) {
                    // Not this layout; the next candidate gets a turn.
                }
            }
        }
        throw new com.maxlananas.fawebim.core.util.InputException("Corrupted schematic data");
    }

    /** True when a parsed root carries one of the containers a schematic uses. */
    private static boolean isSchematicRoot(NbtCompound root) {
        return root.contains("Schematic") || root.contains("Palette") || root.contains("BlockData")
                || root.contains("Blocks") || root.contains("Width") || root.contains("Height")
                || root.contains("palette") || root.contains("blocks") || root.contains("size");
    }

    /** A schematic's data version, used to warn about newer formats. */
    public static int dataVersion(NbtCompound root) {
        if (root.contains("Schematic")) {
            NbtCompound schem = root.getCompound("Schematic");
            return schem.contains("DataVersion") ? schem.getInt("DataVersion", 0) : root.getInt("DataVersion", 0);
        }
        return root.getInt("DataVersion", 0);
    }
}
