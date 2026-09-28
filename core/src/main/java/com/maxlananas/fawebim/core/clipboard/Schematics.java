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

    /** The schematic formats that can be written, by the name each is stored under. */
    public static List<String> formats() {
        return SchematicFormat.ids();
    }

    /**
     * The schematics of the shared folder, sorted by name.
     *
     * <p>FAWE keeps a folder per player only with {@code per-player-schematics}
     * on; with it off, its default and the only mode here, every filter of
     * {@code //schem list} lists the shared folder. The player's folder this
     * used to read for {@code local} was written by nothing, and a name listed
     * from it could not be loaded, since every other command reads the shared
     * folder.</p>
     */
    public static List<String> list() {
        List<String> names = new ArrayList<>();
        collect(directory(), names);
        names.sort(String::compareToIgnoreCase);
        return names;
    }

    /**
     * What a folder of the schematic folder holds, as {@code //schem list}
     * shows it: its sub-folders first, each ending in a slash, then its
     * schematics, every name given from the schematic folder - trees/oak.schem
     * - so that it loads as it is listed. The empty name is the schematic
     * folder itself.
     */
    public static List<String> entries(String folder) {
        Path dir = folder.isEmpty() ? directory() : resolve(folder);
        String prefix = folder.isEmpty() ? "" : folder + "/";
        List<String> folders = new ArrayList<>();
        List<String> files = new ArrayList<>();
        if (Files.isDirectory(dir)) {
            boolean links = Config.get().allowSymlinks;
            try (Stream<Path> children = Files.list(dir)) {
                children.forEach(child -> {
                    String name = child.getFileName().toString();
                    if (!links && Files.isSymbolicLink(child)) {
                        return;
                    }
                    if (Files.isDirectory(child)) {
                        folders.add(prefix + name + "/");
                    } else if (Files.isRegularFile(child) && isSchematic(name)) {
                        files.add(prefix + name);
                    }
                });
            } catch (IOException e) {
                // An unreadable directory simply lists nothing.
            }
        }
        folders.sort(String::compareToIgnoreCase);
        files.sort(String::compareToIgnoreCase);
        folders.addAll(files);
        return folders;
    }

    /** True when the name is a folder inside the schematic folder. */
    public static boolean isFolder(String name) {
        return Files.isDirectory(resolve(name));
    }

    /** A file of the schematic folder named as a command takes it: trees/oak.schem. */
    public static String displayName(Path file) {
        Path relative = directory().toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize());
        StringBuilder name = new StringBuilder();
        for (Path part : relative) {
            if (name.length() > 0) {
                name.append('/');
            }
            name.append(part);
        }
        return name.toString();
    }

    /**
     * The names FAWE's list keeps for a word that is not a filter name: those
     * that start with it, else those that contain it, ignoring case.
     */
    public static List<String> matching(List<String> names, String word) {
        String lower = word.toLowerCase(java.util.Locale.ROOT);
        List<String> starting = new ArrayList<>();
        List<String> containing = new ArrayList<>();
        for (String name : names) {
            // A name listed from a sub-folder is matched by its own part.
            String trimmed = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
            String candidate = trimmed.substring(trimmed.lastIndexOf('/') + 1).toLowerCase(java.util.Locale.ROOT);
            if (candidate.startsWith(lower)) {
                starting.add(name);
            } else if (candidate.contains(lower)) {
                containing.add(name);
            }
        }
        return starting.isEmpty() ? containing : starting;
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
        Path file = resolve(name);
        Path folder = file.getParent();
        String leaf = file.getFileName().toString();
        for (String candidate : List.of(leaf, leaf + suffixOf(format))) {
            if (Files.isRegularFile(folder.resolve(candidate))) {
                return true;
            }
        }
        List<String> listed = new ArrayList<>();
        collect(folder, listed);
        for (String other : listed) {
            if (other.equalsIgnoreCase(leaf) || other.toLowerCase(java.util.Locale.ROOT)
                    .startsWith(leaf.toLowerCase(java.util.Locale.ROOT) + ".")) {
                return true;
            }
        }
        return false;
    }

    /**
     * The file suffix of a format name, {@code .schem} for {@code sponge.3} -
     * the one the format is written with, so an existing file is looked for
     * under the name the save would give it.
     */
    public static String suffixOf(String format) {
        SchematicFormat known = SchematicFormat.find(format);
        return known == null ? ".schem" : known.suffix();
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
     * The schematics {@code /schem loadall} puts in the pool {@code //paste}
     * picks from at random: the one a name gives, as FAWE loads it, or every
     * schematic of the folder a glob such as {@code tree*} matches.
     */
    public static List<BlockArrayClipboard> loadAll(String input) {
        if (input.chars().noneMatch(c -> c == '*' || c == '?' || c == '[' || c == '{')) {
            return List.of(load(input));
        }
        // trees/oak* looks in trees: the folder part is a path, only the last
        // part is a glob.
        int slash = input.lastIndexOf('/');
        String folder = slash < 0 ? "" : input.substring(0, slash);
        if (folder.chars().anyMatch(c -> c == '*' || c == '?' || c == '[' || c == '{')) {
            throw new com.maxlananas.fawebim.core.util.InputException("Only the last part of '" + input
                    + "' may hold a wildcard");
        }
        java.nio.file.PathMatcher matcher = java.nio.file.FileSystems.getDefault()
                .getPathMatcher("glob:" + input.substring(slash + 1));
        List<BlockArrayClipboard> loaded = new ArrayList<>();
        List<String> names = new ArrayList<>();
        collect(folder.isEmpty() ? directory() : resolve(folder), names);
        names.sort(String::compareToIgnoreCase);
        for (String name : names) {
            if (!matcher.matches(java.nio.file.Path.of(name))) {
                continue;
            }
            try {
                loaded.add(load(folder.isEmpty() ? name : folder + "/" + name));
            } catch (RuntimeException e) {
                // A file that is not a readable schematic is skipped, like FAWE does.
            }
        }
        return loaded;
    }

    /**
     * Deletes a schematic. A name that is not one - no such file, or a folder -
     * is refused, where it used to be answered as deleted.
     */
    public static void delete(String name) {
        Path file = resolveExisting(name);
        if (!Files.isRegularFile(file)) {
            throw new com.maxlananas.fawebim.core.util.InputException(Files.isDirectory(file)
                    ? "'" + name + "' is a folder, not a schematic" : "No schematic named '" + name + "'");
        }
        try {
            Files.delete(file);
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
        Path folder = exact.getParent();
        String leaf = exact.getFileName().toString();
        for (String extension : FILE_EXTENSIONS) {
            Path candidate = folder.resolve(leaf + extension);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return exact;
    }

    /** The extensions a schematic file can carry, in the order a load tries them. */
    private static final List<String> FILE_EXTENSIONS =
            List.of(".schem", ".schematic", ".nbt");

    /**
     * The folder a command names: trees or trees/, and "." for the schematic
     * folder itself, which a name cannot otherwise reach.
     */
    public static Path folder(String name) {
        String trimmed = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
        return trimmed.isEmpty() || trimmed.equals(".") ? directory() : resolve(trimmed);
    }

    /**
     * True when a name given in a command stands for the file: its name from
     * the schematic folder, with or without the extension, ignoring case.
     */
    public static boolean names(Path file, String name) {
        String shown = displayName(file);
        int dot = shown.lastIndexOf('.');
        return shown.equalsIgnoreCase(name)
                || dot > shown.lastIndexOf('/') && shown.substring(0, dot).equalsIgnoreCase(name);
    }

    /**
     * Moves a schematic file into a folder of the schematic folder, as FAWE's
     * {@code //schem move} moves the files the clipboard was loaded from. The
     * folder is made when it is not there; a file of the same name in it is
     * left alone.
     *
     * @return where the file is now
     */
    public static Path move(Path file, String folder) {
        Path root = directory().toAbsolutePath().normalize();
        Path from = file.toAbsolutePath().normalize();
        if (!from.startsWith(root)) {
            throw new com.maxlananas.fawebim.core.util.InputException("'" + from.getFileName()
                    + "' is not in the schematic folder");
        }
        Path dir = folder(folder);
        Path to = dir.resolve(from.getFileName().toString()).toAbsolutePath().normalize();
        if (to.equals(from)) {
            throw new com.maxlananas.fawebim.core.util.InputException("'" + displayName(from) + "' is already there");
        }
        if (Files.exists(to)) {
            throw new com.maxlananas.fawebim.core.util.InputException("'" + displayName(to) + "' already exists");
        }
        try {
            Files.createDirectories(dir);
            // Without REPLACE_EXISTING: a file that appeared meanwhile fails the
            // move rather than being overwritten.
            return Files.move(from, to);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("Could not move '" + displayName(from) + "'", e);
        }
    }

    /**
     * Rewrites a saved schematic in another format, which is what
     * {@code //schem move <name> <format>} did before it moved files as FAWE's
     * does. The new file is written before the old one goes, so a write that
     * fails leaves the schematic as it was, and another schematic under the
     * new name is not overwritten.
     *
     * @return the file written
     */
    public static Path convert(String name, String formatName) {
        SchematicFormat format = SchematicFormat.of(formatName);
        Path from = resolveExisting(name).toAbsolutePath().normalize();
        if (!Files.isRegularFile(from)) {
            throw new com.maxlananas.fawebim.core.util.InputException("No schematic named '" + name + "'");
        }
        String shown = displayName(from);
        int dot = shown.lastIndexOf('.');
        String base = dot > shown.lastIndexOf('/') ? shown.substring(0, dot) : shown;
        Path to = resolve(base + format.suffix()).toAbsolutePath().normalize();
        if (!to.equals(from) && Files.exists(to)) {
            throw new com.maxlananas.fawebim.core.util.InputException("'" + displayName(to) + "' already exists");
        }
        Path written = save(load(name), base, format.id());
        if (!to.equals(from)) {
            try {
                Files.delete(from);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException("Wrote '" + displayName(written) + "' but could not delete '"
                        + shown + "'", e);
            }
        }
        return written;
    }

    /**
     * The file a name stands for in the schematic folder. As in FAWE the name
     * may go through sub-folders - trees/oak - but it never leads out of the
     * folder: an empty part, a {@code .} or {@code ..}, a backslash or a drive
     * letter is refused, and so is a symbolic link on the way, unless
     * {@code files.allow-symbolic-links} allows them.
     */
    private static Path resolve(String name) {
        if (name.isEmpty() || name.indexOf('\\') >= 0 || name.indexOf(':') >= 0 || name.indexOf('\0') >= 0) {
            throw invalidName(name);
        }
        Path root = directory();
        Path path = root;
        boolean links = com.maxlananas.fawebim.core.platform.Config.get().allowSymlinks;
        try {
            for (String part : name.split("/", -1)) {
                if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                    throw invalidName(name);
                }
                path = path.resolve(part);
                if (!links && Files.isSymbolicLink(path)) {
                    throw new com.maxlananas.fawebim.core.util.InputException("Symbolic links are disabled"
                            + " (files.allow-symbolic-links in config/fawebim.yml)");
                }
            }
        } catch (java.nio.file.InvalidPathException e) {
            throw invalidName(name);
        }
        if (!path.normalize().startsWith(root.normalize())) {
            throw invalidName(name);
        }
        return path;
    }

    private static com.maxlananas.fawebim.core.util.InputException invalidName(String name) {
        return new com.maxlananas.fawebim.core.util.InputException("Invalid schematic name '" + name + "'");
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
    private static Serialized serialize(BlockArrayClipboard clipboard, String name, String formatName) {
        SchematicFormat format = SchematicFormat.of(formatName);
        NbtCompound root;
        String rootName;
        switch (format) {
            case MCEDIT -> {
                root = McEditSchematic.write(clipboard);
                rootName = McEditSchematic.ROOT_NAME;
            }
            case STRUCTURE -> {
                root = StructureSchematic.write(clipboard);
                rootName = "";
            }
            default -> {
                root = SpongeSchematic.write(clipboard, format.spongeVersion());
                rootName = SpongeSchematic.rootName(format.spongeVersion());
            }
        }
        String suffix = format.suffix();
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
            Path target = resolve(data.fileName());
            // trees/oak makes the trees folder, as FAWE's save does.
            Files.createDirectories(target.getParent());
            return com.maxlananas.fawebim.core.util.AtomicFiles.write(target, data.data());
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("Could not save schematic '" + data.fileName() + "'", e);
        }
    }

    public static BlockArrayClipboard load(String name) {
        Path path = resolveExisting(name);
        try {
            BlockArrayClipboard clipboard = readDetected(readAny(path, name), name);
            clipboard.setSource(path.toAbsolutePath().normalize());
            return clipboard;
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
