package com.maxlananas.fawebim.core.world;

import java.util.List;
import java.util.Map;

/**
 * The bridge between the engine's integer block-state ids and the platform's
 * block registry.
 *
 * <p>FAWE stores blocks as interned integers so that a region can be held in a
 * primitive array; this interface is implemented by the Fabric adapter (over
 * {@code BuiltInRegistries.BLOCK}) and by the head-less test registry.</p>
 *
 * <p>Every id handed out here is stable for the lifetime of the registry: the
 * engine caches ids inside chunk sets, history and clipboards.</p>
 */
public interface BlockStateRegistry {

    /** State id used for "nothing here". */
    int air();

    boolean isAir(int stateId);

    int stateCount();

    /**
     * Parses {@code minecraft:oak_log[axis=y]} (namespace optional) into a state
     * id. Returns {@code -1} when the block or property is unknown.
     */
    int parse(String input);

    /** Default state id of a block name, or {@code -1} when unknown. */
    int defaultState(String blockName);

    /**
     * Every state a block can take, which is what {@code *oak_log} draws from.
     * Implementations that cannot enumerate the states return the default one.
     */
    default List<Integer> statesOf(String blockName) {
        int state = defaultState(blockName);
        return state < 0 ? List.of() : List.of(state);
    }

    /**
     * Maps a legacy numeric id/metadata pair (MCEdit {@code .schematic} files)
     * to a modern state id. Implementations that cannot flatten legacy ids may
     * return {@link #air()}.
     */
    default int legacyState(int blockId, int metadata) {
        return air();
    }

    /**
     * The legacy numeric id of a state, or {@code -1} when the legacy format
     * cannot express it. MCEdit {@code .schematic} files store blocks as an id
     * and a metadata nibble, so writing one only makes sense with this.
     */
    default int legacyId(int stateId) {
        return -1;
    }

    /** The legacy metadata of a state, the other half of {@link #legacyId(int)}. */
    default int legacyMetadata(int stateId) {
        return 0;
    }

    /** Canonical name of a state id, without properties, e.g. {@code minecraft:stone}. */
    String name(int stateId);

    /** Full string form including properties, e.g. {@code minecraft:oak_log[axis=y]}. */
    String describe(int stateId);

    /** Current property values of a state. */
    Map<String, String> properties(int stateId);

    /** Property definitions (name -> allowed values) for a state's block. */
    Map<String, List<String>> propertyDefs(int stateId);

    /** Returns the state id with one property replaced, or {@code -1}. */
    int withProperty(int stateId, String property, String value);

    /** True when the block's tag list contains {@code #namespace:path}. */
    boolean hasTag(int stateId, String tag);

    /** True when the block belongs to a FAWE block category ({@code ##wool}). */
    boolean matchesCategory(int stateId, String category);

    /** Categories known to this registry (used by {@code ##} masks/patterns). */
    List<String> categories();

    boolean isSolid(int stateId);

    boolean isLiquid(int stateId);

    boolean isFullCube(int stateId);

    /** Blocks that behave like air for building purposes (air, cave_air, void_air). */
    boolean isAirLike(int stateId);

    /** Whether light can pass through unchanged (used for relight decisions). */
    boolean blocksLight(int stateId);

    int lightEmission(int stateId);

    double blastResistance(int stateId);

    /** All block names, alphabetically. Used by suggestions and {@code /searchitem}. */
    List<String> blockNames();

    /** All block tag keys, e.g. {@code minecraft:logs}. */
    List<String> blockTags();

    /**
     * Maps an item name to the block state it places (FAWE's item -&gt; block
     * conversion used by {@code /tool} hotbar masks and {@code //replace} with items).
     */
    int blockFromItem(String itemName);

    // ------------------------------------------------------------------ biomes

    int biome(String name);

    String biomeName(int biomeId);

    List<String> biomeNames();

    // ------------------------------------------------------------------- items

    List<String> itemNames();

    /** {@code minecraft:wooden_axe}-style name of the item holding tool/brush bindings. */
    String itemName(int itemId);

    /**
     * Looks up an item id by name. Implementations that do not track items can
     * return a stable synthetic id (only identity comparisons are performed).
     */
    int item(String name);
}
