package com.fawebutinmods.core.anvil;

import com.fawebutinmods.core.util.NbtCompound;
import com.fawebutinmods.core.world.BlockState;
import com.fawebutinmods.core.world.BlockStateRegistry;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads the parts of a stored chunk the anvil commands need: whether it is made
 * of air only, which biomes it holds, how long it was inhabited, and how many
 * blocks of each type it stores.
 *
 * <p>The layout is the vanilla one: {@code sections} is a list of compounds with
 * {@code Y}, a {@code block_states} palette plus packed {@code data}, and a
 * {@code biomes} palette. Sections index blocks with {@code (y << 8) | (z << 4) | x},
 * the same order the engine uses for its own chunk buffers.</p>
 */
public final class ChunkData {

    private static final int SECTION_VOLUME = 4096;

    private ChunkData() {
    }

    /** True when every stored section is air, i.e. the chunk can be dropped. */
    public static boolean isAirOnly(NbtCompound chunk) {
        if (chunk == null) {
            return false;
        }
        BlockStateRegistry registry = BlockState.registry();
        for (NbtCompound section : chunk.getCompoundList("sections")) {
            NbtCompound states = section.getCompoundOrNull("block_states");
            if (states == null) {
                continue;
            }
            List<Integer> palette = paletteIds(states);
            for (int id : palette) {
                if (!registry.isAirLike(id)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** The chunk's biomes, one per stored section, as registry names. */
    public static List<String> biomes(NbtCompound chunk) {
        List<String> names = new java.util.ArrayList<>();
        if (chunk == null) {
            return names;
        }
        for (NbtCompound section : chunk.getCompoundList("sections")) {
            NbtCompound biomes = section.getCompoundOrNull("biomes");
            if (biomes == null) {
                continue;
            }
            for (Object entry : biomes.getList("palette")) {
                names.add(String.valueOf(entry).toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }

    /** Ticks the chunk spent with a player nearby. */
    public static long inhabitedTicks(NbtCompound chunk) {
        return chunk == null ? 0 : chunk.getLong("InhabitedTime", 0);
    }

    /** Adds every block the chunk stores to the given counter. */
    public static void count(NbtCompound chunk, Map<String, Long> counts) {
        if (chunk == null) {
            return;
        }
        BlockStateRegistry registry = BlockState.registry();
        for (NbtCompound section : chunk.getCompoundList("sections")) {
            NbtCompound states = section.getCompoundOrNull("block_states");
            if (states == null) {
                continue;
            }
            List<Integer> palette = paletteIds(states);
            if (palette.isEmpty()) {
                continue;
            }
            int[] blocks = unpack(states, palette);
            for (int id : blocks) {
                counts.merge(registry.name(id), 1L, Long::sum);
            }
        }
    }

    /** The block states of a section's palette, air where an entry is unknown. */
    private static List<Integer> paletteIds(NbtCompound blockStates) {
        List<Integer> ids = new java.util.ArrayList<>();
        BlockStateRegistry registry = BlockState.registry();
        for (Object entry : blockStates.getList("palette")) {
            if (!(entry instanceof NbtCompound compound)) {
                ids.add(registry.air());
                continue;
            }
            String name = compound.getString("Name", "minecraft:air");
            NbtCompound properties = compound.getCompoundOrNull("Properties");
            if (properties != null && !properties.keySet().isEmpty()) {
                StringBuilder full = new StringBuilder(name).append('[');
                boolean first = true;
                for (String key : properties.keySet()) {
                    if (!first) {
                        full.append(',');
                    }
                    full.append(key).append('=').append(properties.getString(key, ""));
                    first = false;
                }
                name = full.append(']').toString();
            }
            int state = registry.parse(name);
            ids.add(state < 0 ? registry.air() : state);
        }
        return ids;
    }

    /**
     * Expands a section's packed block data. Since 1.16 the values never span two
     * longs, so a section is {@code ceil(4096 / valuesPerLong)} longs wide.
     */
    private static int[] unpack(NbtCompound blockStates, List<Integer> palette) {
        int[] blocks = new int[SECTION_VOLUME];
        if (palette.size() == 1) {
            java.util.Arrays.fill(blocks, palette.get(0));
            return blocks;
        }
        long[] data = blockStates.getLongArray("data");
        if (data == null || data.length == 0) {
            return blocks;
        }
        int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
        int perLong = 64 / bits;
        long mask = (1L << bits) - 1;
        for (int index = 0; index < SECTION_VOLUME; index++) {
            int longIndex = index / perLong;
            if (longIndex >= data.length) {
                break;
            }
            int shift = (index % perLong) * bits;
            int paletteIndex = (int) ((data[longIndex] >>> shift) & mask);
            blocks[index] = paletteIndex < palette.size() ? palette.get(paletteIndex) : palette.get(0);
        }
        return blocks;
    }

    /** The colour a block shows on a map, used by the distribution output. */
    public static int colorOf(String blockName) {
        return BlockState.registry().parse(blockName);
    }
}
