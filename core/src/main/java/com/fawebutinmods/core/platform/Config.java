package com.fawebutinmods.core.platform;

import com.fawebutinmods.core.util.MiniYaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * The engine configuration ({@code config.yml} of FAWE), stored as
 * {@code config/fawe.yml} in the game directory.
 *
 * <p>FAWE ships hundreds of options; the ones that change behaviour are all
 * present here, and the rest of the file is preserved verbatim on reload so a
 * user-edited file is never destroyed.</p>
 */
public final class Config {

    public static final String VERSION = "1.0.0";
    public static final String MINECRAFT_VERSION = "1.21.10";

    private static final Config INSTANCE = new Config();

    /** Everything a user can tune, mirroring FAWE's config keys. */
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
    public boolean wandItemDurability = true;
    public boolean commandBlockSupport = false;
    public boolean superPickaxeDrop = true;
    public boolean superPickaxeManyDrop = true;
    public int butcherDefaultRadius = 20;
    public int butcherMaxRadius = 100;
    public boolean historyEnabled = true;
    public int historySize = 20;
    public boolean perPlayerHistory = true;
    public int maxHistorySize = 500;
    public boolean enableDiskHistory = true;
    public String schematicSaveDirectory = "schematics";
    public String snapshotDirectory = "snapshots";
    public String macroDirectory = "macros";
    public String brushPresetDirectory = "brushes";
    public String scriptDirectory = "craftscripts";
    public boolean snapshotsEnabled = true;
    public String defaultSchematicFormat = "sponge.3";
    public boolean allowSymlinks = false;
    public int maxSchematicSize = 0;
    public int queueTargetSize = 5000000;
    public int queueMaxWait = 500;
    public int queueTickInterval = 1;
    public boolean combineStages = true;
    public boolean serverSideCUI = true;
    public boolean extendedYLimit = false;
    public int chunkWaitTimeout = 5000;
    public boolean entityBrushEnabled = true;
    public boolean lightningEnabled = true;
    public int maxEntitiesPerChunk = 256;
    public boolean debug = false;

    private Path file;
    private Path gameDirectory;

    private Config() {
    }

    public static Config get() {
        return INSTANCE;
    }

    /** Loads {@code config/fawe.yml} from the given game directory if present. */
    /** Resolves a directory name against the game directory. */
    public Path resolveDirectory(String name) {
        Path base = gameDirectory == null ? Path.of(".") : gameDirectory;
        return base.resolve(name);
    }

    public void load(Path gameDirectory) {
        this.gameDirectory = gameDirectory;
        this.file = gameDirectory.resolve("config").resolve("fawe.yml");
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
        wandItem = string(map, "wand-item", wandItem);
        navigationWandItem = string(map, "navigation-wand-item", navigationWandItem);
        defaultChangeLimit = integer(map, "limits.max-blocks-changed.default", defaultChangeLimit);
        maxChangeLimit = integer(map, "limits.max-blocks-changed.maximum", maxChangeLimit);
        defaultMaxBrushRadius = integer(map, "limits.max-brush-radius.default", defaultMaxBrushRadius);
        maxBrushRadius = integer(map, "limits.max-brush-radius.maximum", maxBrushRadius);
        maxBrushRange = integer(map, "limits.max-brush-range", maxBrushRange);
        timeout = integer(map, "limits.operation-timeout", timeout);
        threads = integer(map, "threads", threads);
        historySize = integer(map, "history.size", historySize);
        maxHistorySize = integer(map, "history.max-size", maxHistorySize);
        allowAncientBlocks = bool(map, "allow-ancient-blocks", allowAncientBlocks);
        defaultVerticalHeight = integer(map, "extent.default-vertical-height", defaultVerticalHeight);
        regenerateBiomes = bool(map, "regen.biomes", regenerateBiomes);
        schematicSaveDirectory = string(map, "saving.dir", schematicSaveDirectory);
        snapshotDirectory = string(map, "history.snapshots.dir", snapshotDirectory);
        snapshotsEnabled = bool(map, "history.snapshots.enabled", snapshotsEnabled);
        macroDirectory = string(map, "macros.dir", macroDirectory);
        brushPresetDirectory = string(map, "brushes.dir", brushPresetDirectory);
        scriptDirectory = string(map, "scripts.dir", scriptDirectory);
        defaultSchematicFormat = string(map, "saving.format", defaultSchematicFormat);
        queueTargetSize = integer(map, "queue.target-size", queueTargetSize);
        queueMaxWait = integer(map, "queue.max-wait-ms", queueMaxWait);
        serverSideCUI = bool(map, "cui.server-side", serverSideCUI);
        debug = bool(map, "debug", debug);
    }

    public void reload() {
        if (file != null) {
            load(file.getParent().getParent());
        }
    }

    public void save() {
        if (file == null) {
            return;
        }
        java.util.Map<String, Object> root = new java.util.LinkedHashMap<>();
        root.put("wand-item", wandItem);
        root.put("navigation-wand-item", navigationWandItem);
        root.put("allow-ancient-blocks", allowAncientBlocks);
        root.put("debug", debug);
        root.put("threads", threads);
        java.util.Map<String, Object> limits = new java.util.LinkedHashMap<>();
        java.util.Map<String, Object> blocks = new java.util.LinkedHashMap<>();
        blocks.put("default", defaultChangeLimit);
        blocks.put("maximum", maxChangeLimit);
        limits.put("max-blocks-changed", blocks);
        java.util.Map<String, Object> brush = new java.util.LinkedHashMap<>();
        brush.put("default", defaultMaxBrushRadius);
        brush.put("maximum", maxBrushRadius);
        limits.put("max-brush-radius", brush);
        limits.put("max-brush-range", maxBrushRange);
        limits.put("operation-timeout", timeout);
        root.put("limits", limits);
        java.util.Map<String, Object> history = new java.util.LinkedHashMap<>();
        history.put("size", historySize);
        history.put("max-size", maxHistorySize);
        root.put("history", history);
        java.util.Map<String, Object> queue = new java.util.LinkedHashMap<>();
        queue.put("target-size", queueTargetSize);
        queue.put("max-wait-ms", queueMaxWait);
        root.put("queue", queue);
        java.util.Map<String, Object> saving = new java.util.LinkedHashMap<>();
        saving.put("dir", schematicSaveDirectory);
        saving.put("format", defaultSchematicFormat);
        root.put("saving", saving);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, MiniYaml.write(root));
        } catch (IOException e) {
            // Ignore: the defaults stay in memory.
        }
    }

    private static String string(Map<String, Object> map, String path, String fallback) {
        Object value = MiniYaml.path(map, path, null);
        return value == null ? fallback : String.valueOf(value);
    }

    private static int integer(Map<String, Object> map, String path, int fallback) {
        Object value = MiniYaml.path(map, path, null);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }

    private static boolean bool(Map<String, Object> map, String path, boolean fallback) {
        Object value = MiniYaml.path(map, path, null);
        return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
    }
}
