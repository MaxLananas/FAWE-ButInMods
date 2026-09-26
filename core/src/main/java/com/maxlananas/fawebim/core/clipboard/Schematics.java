package com.maxlananas.fawebim.core.clipboard;

import com.maxlananas.fawebim.core.platform.Config;
import com.maxlananas.fawebim.core.util.NbtCompound;
import com.maxlananas.fawebim.core.util.NbtIo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * The schematic folder: listing, naming, loading and saving files.
 *
 * <p>The formats themselves are {@link SpongeSchematic} (v1 to v3,
 * {@code .schem}), {@link McEditSchematic} ({@code .schematic}) and
 * {@link StructureSchematic} (the game's {@code .nbt} structures).</p>
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
        // The name is checked first, so the answer never tells about a file outside the folder.
        resolve(name);
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

    /**
     * Writes the clipboard using the given format ({@code sponge.3}, {@code sponge.2}, {@code mcedit}).
     *
     * @return the file that was written, whose name carries the format's extension
     */
    public static Path save(BlockArrayClipboard clipboard, String name, String format) {
        return write(serialize(clipboard, name, format));
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

    /**
     * The bytes of a schematic: gzipped NBT in every format, with the root name
     * each format is recognised by.
     */
    private static Serialized serialize(BlockArrayClipboard clipboard, String name, String format) {
        String lower = format.toLowerCase(Locale.ROOT);
        NbtCompound root;
        String rootName;
        String suffix;
        if (lower.startsWith("mcedit") || lower.startsWith("legacy")) {
            root = McEditSchematic.write(clipboard);
            rootName = McEditSchematic.ROOT_NAME;
            suffix = ".schematic";
        } else if (lower.startsWith("structure") || lower.startsWith("nbt")) {
            root = StructureSchematic.write(clipboard);
            rootName = "";
            suffix = ".nbt";
        } else {
            int version = lower.endsWith("2") ? 2 : lower.endsWith("1") ? 1 : 3;
            root = SpongeSchematic.write(clipboard, version);
            rootName = SpongeSchematic.rootName(version);
            suffix = ".schem";
        }
        String fileName = name.endsWith(suffix) ? name : name + suffix;
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            NbtIo.write(root, rootName, buffer, true);
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
        Path path = resolveExisting(name);
        try {
            return readDetected(readAny(path, name), name);
        } catch (java.nio.file.NoSuchFileException e) {
            throw new com.maxlananas.fawebim.core.util.InputException("No schematic named '" + name + "'");
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("Could not load schematic '" + name + "'", e);
        }
    }

    /**
     * How many blocks of the clipboard the legacy format cannot name, so the
     * command can warn before writing them as air.
     */
    public static int legacyLosses(BlockArrayClipboard clipboard) {
        return McEditSchematic.losses(clipboard);
    }

    /**
     * Works out which format the NBT is and reads it. Every reader checks the
     * size against {@code limits.max-schematic-size} before it allocates the
     * blocks.
     */
    private static BlockArrayClipboard readDetected(NbtCompound root, String name) {
        long maxVolume = Config.get().maxSchematicSize;
        int dataVersion = dataVersion(root);
        if (dataVersion > Config.DATA_VERSION) {
            com.maxlananas.fawebim.core.platform.Log.warn("Schematic '" + name + "' was written by a newer game"
                    + " (data version " + dataVersion + ", this one is " + Config.DATA_VERSION
                    + "): blocks it adds load as air");
        }
        if (SpongeSchematic.isSponge(root)) {
            return SpongeSchematic.read(root, name, maxVolume);
        }
        if (StructureSchematic.isStructure(root)) {
            return StructureSchematic.read(root, name, maxVolume);
        }
        if (McEditSchematic.isMcEdit(root)) {
            return McEditSchematic.read(root, name, maxVolume);
        }
        NbtCompound nested = root.getCompoundOrNull("Schematic");
        if (nested != null && McEditSchematic.isMcEdit(nested)) {
            return McEditSchematic.read(nested, name, maxVolume);
        }
        throw new com.maxlananas.fawebim.core.util.InputException("'" + name
                + "' is not a schematic this mod can read");
    }

    /**
     * Reads the NBT of a schematic file whatever its container: gzipped or
     * plain (the magic number says which), standard NBT or the varint NBT
     * earlier builds of this mod wrote.
     *
     * <p>The file is inflated as it is parsed, and the parse has the memory
     * budget of {@link NbtIo#defaultBudget()}, so neither a huge file nor a
     * small one that claims huge arrays can exhaust the heap. Standard NBT is
     * tried first; varint NBT read as standard NBT, or the other way round,
     * fails early or yields a compound that is not a schematic.</p>
     */
    private static NbtCompound readAny(Path path, String name) throws IOException {
        boolean gzipped;
        try (java.io.InputStream in = Files.newInputStream(path)) {
            byte[] head = in.readNBytes(2);
            gzipped = NbtIo.isGzip(head.length == 2 ? new byte[]{head[0], head[1], 0} : head);
        }
        long budget = NbtIo.defaultBudget();
        for (boolean varint : new boolean[]{false, true}) {
            try (java.io.InputStream in = Files.newInputStream(path)) {
                NbtCompound root = NbtIo.read(in, varint, gzipped, budget);
                if (isSchematicRoot(root)) {
                    return root;
                }
            } catch (NbtIo.TooLargeException e) {
                if (!varint) {
                    throw new com.maxlananas.fawebim.core.util.InputException("'" + name
                            + "' needs more memory to read than the server can spare");
                }
            } catch (java.nio.file.NoSuchFileException e) {
                throw e;
            } catch (IOException | RuntimeException e) {
                // Not this layout; the next one gets a turn.
            }
        }
        throw new com.maxlananas.fawebim.core.util.InputException("'" + name + "' is not a readable schematic file");
    }

    /** True when a parsed root carries one of the containers a schematic uses. */
    private static boolean isSchematicRoot(NbtCompound root) {
        return root.contains("Schematic") || root.contains("Palette") || root.contains("BlockData")
                || root.contains("Blocks") || root.contains("Width") || root.contains("Height")
                || root.contains("palette") || root.contains("blocks") || root.contains("size");
    }

    /** The data version a schematic declares, 0 when it declares none. */
    private static int dataVersion(NbtCompound root) {
        NbtCompound nested = root.getCompoundOrNull("Schematic");
        if (nested != null && nested.contains("DataVersion")) {
            return nested.getInt("DataVersion", 0);
        }
        return root.getInt("DataVersion", 0);
    }
}
