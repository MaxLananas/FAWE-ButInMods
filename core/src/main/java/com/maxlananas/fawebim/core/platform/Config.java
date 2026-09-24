package com.maxlananas.fawebim.core.platform;

import com.maxlananas.fawebim.core.util.MiniYaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The engine configuration of the mod, stored as {@code config/fawebim.yml} in
 * the game directory.
 *
 * <p>Key names follow WorldEdit's and FAWE's configuration files, so a value can
 * be copied straight out of one of them. Every key is declared once, in the
 * table built by the constructor: that declaration carries the path in the file,
 * the type, a sentence explaining what it does and the field behind it, which is
 * what lets {@code /fawebim settings} list and edit the whole configuration from
 * inside the game without a second list to keep in sync.</p>
 */
public final class Config {

    public static final String VERSION = "1.0.0";
    public static final String MINECRAFT_VERSION = "1.21.10";

    private static final Config INSTANCE = new Config();

    /** Everything a user can tune, mirroring WorldEdit's and FAWE's keys. */
    public String wandItem = "minecraft:wooden_axe";
    public String navigationWandItem = "minecraft:compass";
    public boolean wandItemIsTool = true;
    public int defaultChangeLimit = -1;
    public int maxChangeLimit = -1;
    public int defaultMaxBrushRadius = 6;
    public int maxBrushRadius = 1000;
    public int maxBrushRange = 100;
    public int timeout = 20;
    public int threads = Runtime.getRuntime().availableProcessors();
    public boolean allowAncientBlocks = true;
    public boolean allowNonPlayerEntities = true;
    public int defaultVerticalHeight = 256;
    public boolean regenerateBiomes = true;
    public int maxRegenVolume = 100000000;
    public boolean superPickaxeDrop = true;
    public boolean superPickaxeManyDrop = true;
    public int butcherDefaultRadius = 20;
    public int butcherMaxRadius = 100;
    public boolean historyEnabled = true;
    public int historySize = 20;
    public int maxHistorySize = 500;
    public boolean perPlayerHistory = true;
    public boolean enableDiskHistory = true;
    public String historyDirectory = "history";
    public boolean snapshotsEnabled = true;
    public String snapshotDirectory = "snapshots";
    public String schematicSaveDirectory = "schematics";
    public String defaultSchematicFormat = "sponge.3";
    public int maxSchematicSize = 0;
    public String macroDirectory = "macros";
    public String brushPresetDirectory = "brushes";
    public String scriptDirectory = "craftscripts";
    public boolean allowSymlinks = false;
    public int queueTargetSize = 5000000;
    public int queueMaxWait = 500;
    public boolean commandBlockSupport = false;
    public boolean debug = false;

    private List<Setting<?>> settings = new ArrayList<>();
    private Path file;
    private Path gameDirectory;

    private Config() {
        text("wand-item", "wand-item", wandItem,
                "Item used as the selection wand.", () -> wandItem, value -> wandItem = value);
        text("navigation-wand-item", "navigation-wand.item", navigationWandItem,
                "Item used as the navigation wand.", () -> navigationWandItem,
                value -> navigationWandItem = value);
        bool("wand-item-tool", "wand-item-tool", wandItemIsTool,
                "Whether the wand also acts as a tool, so a right click uses the bound tool.",
                () -> wandItemIsTool, value -> wandItemIsTool = value);
        integer("default-change-limit", "limits.max-blocks-changed.default", defaultChangeLimit,
                "Blocks a new player may change per command, -1 for no limit.",
                () -> defaultChangeLimit, value -> defaultChangeLimit = value);
        integer("max-change-limit", "limits.max-blocks-changed.maximum", maxChangeLimit,
                "Highest value /limit accepts, -1 for no ceiling.", () -> maxChangeLimit,
                value -> maxChangeLimit = value);
        integer("default-max-brush-radius", "limits.max-brush-radius.default", defaultMaxBrushRadius,
                "Brush radius a new player starts with.", () -> defaultMaxBrushRadius,
                value -> defaultMaxBrushRadius = value);
        integer("max-brush-radius", "limits.max-brush-radius.maximum", maxBrushRadius,
                "Largest brush radius /tool size accepts.", () -> maxBrushRadius,
                value -> maxBrushRadius = value);
        integer("max-brush-range", "limits.max-brush-range", maxBrushRange,
                "How far a brush reaches from the player, in blocks.", () -> maxBrushRange,
                value -> maxBrushRange = value);
        integer("timeout", "calculation.timeout", timeout,
                "Seconds an operation may run before it is stopped.", () -> timeout,
                value -> timeout = value);
        integer("threads", "threads", threads,
                "Worker threads the engine may use.", () -> threads, value -> threads = value);
        bool("allow-ancient-blocks", "allow-ancient-blocks", allowAncientBlocks,
                "Accept the numeric block ids of Minecraft 1.12 and older.",
                () -> allowAncientBlocks, value -> allowAncientBlocks = value);
        bool("allow-non-player-entities", "allow-non-player-entities", allowNonPlayerEntities,
                "Allow command blocks and other non-player sources to run commands.",
                () -> allowNonPlayerEntities, value -> allowNonPlayerEntities = value);
        integer("vertical-height", "limits.vertical-height.default", defaultVerticalHeight,
                "Height of the vertical cylinder and sphere selections.", () -> defaultVerticalHeight,
                value -> defaultVerticalHeight = value);
        bool("regenerate-biomes", "regen.biomes", regenerateBiomes,
                "Restore biomes together with the terrain in //regen.", () -> regenerateBiomes,
                value -> regenerateBiomes = value);
        integer("max-regen-volume", "regen.max-volume", maxRegenVolume,
                "Largest region //regen accepts, in blocks.", () -> maxRegenVolume,
                value -> maxRegenVolume = value);
        bool("super-pickaxe-drop", "super-pickaxe.drop-items", superPickaxeDrop,
                "Drop the blocks the super pickaxe breaks.", () -> superPickaxeDrop,
                value -> superPickaxeDrop = value);
        bool("super-pickaxe-many-drop", "super-pickaxe.many-drop-items", superPickaxeManyDrop,
                "Drop the blocks of an area super-pickaxe break.", () -> superPickaxeManyDrop,
                value -> superPickaxeManyDrop = value);
        integer("butcher-default-radius", "limits.butcher-radius.default", butcherDefaultRadius,
                "Radius //butcher takes when the command gives none.", () -> butcherDefaultRadius,
                value -> butcherDefaultRadius = value);
        integer("butcher-max-radius", "limits.butcher-radius.maximum", butcherMaxRadius,
                "Largest radius //butcher accepts.", () -> butcherMaxRadius,
                value -> butcherMaxRadius = value);
        bool("history-enabled", "history.enabled", historyEnabled,
                "Record the changes so //undo and //redo work.", () -> historyEnabled,
                value -> historyEnabled = value);
        integer("history-size", "history.size", historySize,
                "Changes a player can undo.", () -> historySize, value -> historySize = value);
        integer("max-history-size", "history.max-size", maxHistorySize,
                "Largest history a player may ask for.", () -> maxHistorySize,
                value -> maxHistorySize = value);
        bool("per-player-history", "history.per-player", perPlayerHistory,
                "Keep a separate history per player instead of one shared one.",
                () -> perPlayerHistory, value -> perPlayerHistory = value);
        bool("enable-disk-history", "history.use-disk", enableDiskHistory,
                "Keep history in the world folder so it survives a restart.",
                () -> enableDiskHistory, value -> enableDiskHistory = value);
        text("history-directory", "history.dir", historyDirectory,
                "Folder the history files are written to.", () -> historyDirectory,
                value -> historyDirectory = value);
        bool("snapshots-enabled", "history.snapshots.enabled", snapshotsEnabled,
                "Take snapshots that //restore can bring back.", () -> snapshotsEnabled,
                value -> snapshotsEnabled = value);
        text("snapshot-directory", "snapshots.directory", snapshotDirectory,
                "Folder the snapshots are written to, inside the world or game directory.",
                () -> snapshotDirectory, value -> snapshotDirectory = value);
        text("schematic-directory", "saving.dir", schematicSaveDirectory,
                "Folder the schematics are read from and written to.", () -> schematicSaveDirectory,
                value -> schematicSaveDirectory = value);
        text("schematic-format", "saving.format", defaultSchematicFormat,
                "Format //schem save writes when the command gives none.", () -> defaultSchematicFormat,
                value -> defaultSchematicFormat = value);
        integer("max-schematic-size", "limits.max-schematic-size", maxSchematicSize,
                "Largest schematic that may be loaded, in blocks, 0 for no limit.",
                () -> maxSchematicSize, value -> maxSchematicSize = value);
        text("macro-directory", "macros.dir", macroDirectory,
                "Folder the macros are read from.", () -> macroDirectory, value -> macroDirectory = value);
        text("brush-preset-directory", "brushes.dir", brushPresetDirectory,
                "Folder the saved brushes are kept in.", () -> brushPresetDirectory,
                value -> brushPresetDirectory = value);
        text("script-directory", "scripting.dir", scriptDirectory,
                "Folder the scripts are read from.", () -> scriptDirectory, value -> scriptDirectory = value);
        bool("allow-symlinks", "files.allow-symbolic-links", allowSymlinks,
                "Follow symbolic links when reading files.", () -> allowSymlinks, value -> allowSymlinks = value);
        integer("queue-target-size", "queue.target-size", queueTargetSize,
                "Blocks the edit queue holds before it is written out.", () -> queueTargetSize,
                value -> queueTargetSize = value);
        integer("queue-max-wait", "queue.max-wait-ms", queueMaxWait,
                "Longest the queue may wait before it is flushed, in milliseconds.", () -> queueMaxWait,
                value -> queueMaxWait = value);
        bool("command-block-support", "command-block-support", commandBlockSupport,
                "Let command blocks run the mod's commands.", () -> commandBlockSupport,
                value -> commandBlockSupport = value);
        bool("debug", "debug", debug,
                "Write extra engine diagnostics to the log and to /we report.", () -> debug,
                value -> debug = value);
        settings = Collections.unmodifiableList(settings);
    }

    public static Config get() {
        return INSTANCE;
    }

    /** Every setting, in the order the configuration file writes them. */
    public List<Setting<?>> settings() {
        return settings;
    }

    /** The setting a token names, by key or by file path, or null. */
    public Setting<?> find(String token) {
        for (Setting<?> setting : settings) {
            if (setting.matches(token)) {
                return setting;
            }
        }
        return null;
    }

    /**
     * Applies a value typed in game and writes the file.
     *
     * @return an error message when the key is unknown or the value does not fit
     */
    public String set(String token, String value) {
        Setting<?> setting = find(token);
        if (setting == null) {
            return "Unknown setting '" + token + "'. Use /fawebim settings to list them";
        }
        String error = setting.apply(value);
        if (error != null) {
            return error + " for " + setting.key() + " (currently " + setting.value() + ")";
        }
        save();
        return null;
    }

    /** Resolves a directory name against the game directory. */
    public Path resolveDirectory(String name) {
        Path base = gameDirectory == null ? Path.of(".") : gameDirectory;
        return base.resolve(name);
    }

    /** The configuration file, once the game directory is known. */
    public Path configFile() {
        return file;
    }

    public void load(Path gameDirectory) {
        // The game hands out a relative directory in a development run; the file
        // is resolved to an absolute path once, so that looking it up again - to
        // reload it, or to print it - needs nothing else.
        this.gameDirectory = gameDirectory == null ? Path.of(".") : gameDirectory.toAbsolutePath().normalize();
        this.file = this.gameDirectory.resolve("config").resolve("fawebim.yml");
        if (!Files.exists(file)) {
            save();
            return;
        }
        try {
            apply(MiniYaml.parse(Files.readString(file)));
        } catch (IOException e) {
            // A broken config must never stop the mod from loading.
        }
    }

    private void apply(Map<String, Object> map) {
        for (Setting<?> setting : settings) {
            Object value = MiniYaml.path(map, setting.path(), null);
            if (value != null) {
                setting.apply(String.valueOf(value));
            }
        }
    }

    public void reload() {
        if (gameDirectory != null) {
            load(gameDirectory);
        }
    }

    /** Writes the whole configuration back, creating the config folder. */
    public void save() {
        if (file == null) {
            return;
        }
        Map<String, Object> root = new LinkedHashMap<>();
        for (Setting<?> setting : settings) {
            put(root, setting.path(), setting.value());
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, MiniYaml.write(root));
        } catch (IOException e) {
            // Ignore: the values stay in memory.
        }
    }

    /** Flattens a dotted path into the nested map the file is written from. */
    @SuppressWarnings("unchecked")
    private static void put(Map<String, Object> root, String path, String value) {
        String[] parts = path.split("\\.");
        Map<String, Object> node = root;
        for (int i = 0; i < parts.length - 1; i++) {
            node = (Map<String, Object>) node.computeIfAbsent(parts[i], key -> new LinkedHashMap<String, Object>());
        }
        node.put(parts[parts.length - 1], MiniYaml.scalar(value));
    }

    private void bool(String key, String path, boolean fallback, String description,
                      Supplier<Boolean> reader, Consumer<Boolean> writer) {
        settings.add(Setting.of(key, path, Setting.Kind.BOOLEAN, description, fallback, reader, writer));
    }

    private void integer(String key, String path, int fallback, String description,
                         Supplier<Integer> reader, Consumer<Integer> writer) {
        settings.add(Setting.of(key, path, Setting.Kind.INTEGER, description, fallback, reader, writer));
    }

    private void text(String key, String path, String fallback, String description,
                      Supplier<String> reader, Consumer<String> writer) {
        settings.add(Setting.of(key, path, Setting.Kind.TEXT, description, fallback, reader, writer));
    }
}
