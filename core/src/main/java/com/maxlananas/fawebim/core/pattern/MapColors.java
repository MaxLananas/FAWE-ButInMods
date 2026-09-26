package com.maxlananas.fawebim.core.pattern;

import com.maxlananas.fawebim.core.world.BlockStateRegistry;

import java.util.Arrays;

/**
 * The colours of blocks, for {@code #color}, {@code #lighten} and its kin, and
 * {@code //image}: what colour a block is, and which block is nearest a colour.
 *
 * <p>FAWE reads the colours off the game's textures, which a server does not
 * have. The blocks images and colour patterns are built from - wool, concrete,
 * terracotta, stones, woods and the like - carry a colour here measured from
 * those textures, and they are the blocks a colour is matched against. Any
 * other block takes the map colour the platform gives, and without a platform
 * (the tests) a colour made from its name.</p>
 */
public final class MapColors {

    private static final String[] BLOCKS = {
        "minecraft:white_wool", "minecraft:orange_wool", "minecraft:magenta_wool",
        "minecraft:light_blue_wool", "minecraft:yellow_wool", "minecraft:lime_wool",
        "minecraft:pink_wool", "minecraft:gray_wool", "minecraft:light_gray_wool",
        "minecraft:cyan_wool", "minecraft:purple_wool", "minecraft:blue_wool",
        "minecraft:brown_wool", "minecraft:green_wool", "minecraft:red_wool",
        "minecraft:black_wool",
        "minecraft:white_concrete", "minecraft:orange_concrete", "minecraft:magenta_concrete",
        "minecraft:light_blue_concrete", "minecraft:yellow_concrete", "minecraft:lime_concrete",
        "minecraft:pink_concrete", "minecraft:gray_concrete", "minecraft:light_gray_concrete",
        "minecraft:cyan_concrete", "minecraft:purple_concrete", "minecraft:blue_concrete",
        "minecraft:brown_concrete", "minecraft:green_concrete", "minecraft:red_concrete",
        "minecraft:black_concrete",
        "minecraft:terracotta", "minecraft:white_terracotta", "minecraft:orange_terracotta",
        "minecraft:yellow_terracotta", "minecraft:brown_terracotta", "minecraft:red_terracotta",
        "minecraft:stone", "minecraft:cobblestone", "minecraft:andesite", "minecraft:diorite",
        "minecraft:granite", "minecraft:deepslate", "minecraft:blackstone", "minecraft:obsidian",
        "minecraft:dirt", "minecraft:coarse_dirt", "minecraft:grass_block", "minecraft:podzol",
        "minecraft:sand", "minecraft:red_sand", "minecraft:sandstone", "minecraft:gravel",
        "minecraft:oak_planks", "minecraft:spruce_planks", "minecraft:birch_planks",
        "minecraft:dark_oak_planks", "minecraft:oak_log", "minecraft:spruce_log",
        "minecraft:oak_leaves", "minecraft:spruce_leaves", "minecraft:birch_leaves",
        "minecraft:water", "minecraft:lava", "minecraft:ice", "minecraft:snow_block",
        "minecraft:packed_ice", "minecraft:blue_ice", "minecraft:moss_block", "minecraft:clay",
        "minecraft:bricks", "minecraft:netherrack", "minecraft:nether_bricks", "minecraft:soul_sand",
        "minecraft:end_stone", "minecraft:purpur_block", "minecraft:quartz_block",
        "minecraft:iron_block", "minecraft:gold_block", "minecraft:diamond_block",
        "minecraft:emerald_block", "minecraft:redstone_block", "minecraft:lapis_block",
        "minecraft:coal_block", "minecraft:copper_block", "minecraft:amethyst_block",
        "minecraft:glass", "minecraft:bookshelf", "minecraft:hay_block", "minecraft:melon",
        "minecraft:pumpkin", "minecraft:bone_block", "minecraft:sea_lantern", "minecraft:glowstone",
    };

    private static final int[] COLORS = {
        0xE9ECEC, 0xF07613, 0xBD44B3, 0x3AAFD9, 0xF8C627, 0x70B919, 0xED8DAC, 0x3E4447,
        0x8E8E86, 0x158991, 0x792AAC, 0x35399D, 0x724728, 0x546D1B, 0xA12722, 0x141519,
        0xCFD5D6, 0xE06101, 0xA9309F, 0x248FC1, 0xF0AF15, 0x5EA818, 0xD5658E, 0x54585A,
        0x7D7D73, 0x157788, 0x64209C, 0x2D2F8F, 0x603C20, 0x495B24, 0x8E2121, 0x080A0F,
        0x985E43, 0xD2B1A1, 0xA15325, 0xBA8523, 0x4D3223, 0x8E3C2E,
        0x7D7D7D, 0x7A7A7A, 0x8A8A8D, 0xBCBCBC, 0x9A6A4F, 0x646464, 0x2B2926, 0x101019,
        0x976D4D, 0x7F6144, 0x6A7039, 0x5B4C31, 0xDBD3A0, 0xB86A28, 0xD5C98D, 0x847E7C,
        0xB8945F, 0x73553B, 0xC7B584, 0x4A3219, 0x6A5025, 0x4A3A22, 0x4C7B32, 0x3B5B2C,
        0x5E7A45, 0x3F76E4, 0xD45A12, 0x7DADEB, 0xF0FCFC, 0x8EB4E8, 0x74A8F0, 0x59A74A,
        0x9FA3A6, 0x985B45, 0x6B2A2A, 0x2D1717, 0x54402F, 0xDBDEA0, 0xA97BA8, 0xE5E0D8,
        0xD8D8D8, 0xF9EF4E, 0x4AEDD9, 0x2CCB5A, 0xAA0F0F, 0x1C48A0, 0x101010, 0xC0724A,
        0x8A6EC7, 0xFFFFFF, 0x6B4F31, 0xB0A03C, 0x7A9B2E, 0xD18E21, 0xD9D3A1, 0x9BE7E7,
        0xB9A24C,
    };

    /** A colour of the cache that has been worked out; the colour is the low 24 bits. */
    private static final int KNOWN = 1 << 24;

    /**
     * The blocks colours are matched against, with their colours: parallel
     * arrays, the default state of every block of {@link #BLOCKS} the game has.
     */
    public record Palette(int[] states, int[] colors) {

        /** The state whose colour is nearest, or -1 for an empty palette. */
        public int closest(int rgb) {
            int best = -1;
            int bestDistance = Integer.MAX_VALUE;
            for (int i = 0; i < states.length; i++) {
                int distance = distance(colors[i], rgb);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = states[i];
                }
            }
            return best;
        }
    }

    /**
     * The colour of every state of a registry asked about so far, with
     * {@link #KNOWN} set on the ones worked out. Ids mean other blocks in
     * another registry, so the colours go with the registry they were read
     * from. A colour is the same whoever works it out: a thread that finds a
     * slot another has not written yet works it out again.
     */
    private record Colors(BlockStateRegistry registry, int[] colors) {
    }

    private record Palettes(BlockStateRegistry registry, Palette palette) {
    }

    private static volatile java.util.function.IntUnaryOperator provider;
    private static volatile Colors colors;
    private static volatile Palettes palettes;

    private MapColors() {
    }

    /** Installed by the platform adapter so the blocks the table does not know take their map colour. */
    public static void setProvider(java.util.function.IntUnaryOperator colorProvider) {
        provider = colorProvider;
        colors = null;
    }

    /** The colour of a block state, as {@code 0xRRGGBB}. */
    public static int colorOf(BlockStateRegistry registry, int stateId) {
        if (stateId < 0) {
            return fallbackColor(registry.name(stateId));
        }
        Colors current = colors;
        if (current == null || current.registry() != registry || stateId >= current.colors().length) {
            current = new Colors(registry, new int[Math.max(stateId + 1, registry.stateCount())]);
            colors = current;
        }
        int[] known = current.colors();
        int value = known[stateId];
        if ((value & KNOWN) != 0) {
            return value & 0xFFFFFF;
        }
        int color = lookUp(registry, stateId) & 0xFFFFFF;
        known[stateId] = color | KNOWN;
        return color;
    }

    private static int lookUp(BlockStateRegistry registry, int stateId) {
        String name = registry.name(stateId);
        for (int i = 0; i < BLOCKS.length; i++) {
            if (BLOCKS[i].equals(name)) {
                return COLORS[i];
            }
        }
        java.util.function.IntUnaryOperator op = provider;
        return op != null ? op.applyAsInt(stateId) : fallbackColor(name);
    }

    /** The blocks a colour is matched against, for the registry in use. */
    public static Palette palette(BlockStateRegistry registry) {
        Palettes cached = palettes;
        if (cached != null && cached.registry() == registry) {
            return cached.palette();
        }
        int[] states = new int[BLOCKS.length];
        int[] stateColors = new int[BLOCKS.length];
        int count = 0;
        for (int i = 0; i < BLOCKS.length; i++) {
            int state = registry.defaultState(BLOCKS[i]);
            if (state >= 0) {
                states[count] = state;
                stateColors[count] = COLORS[i];
                count++;
            }
        }
        Palette palette = new Palette(Arrays.copyOf(states, count), Arrays.copyOf(stateColors, count));
        palettes = new Palettes(registry, palette);
        return palette;
    }

    /** The squared distance of two colours in RGB space. */
    public static int distance(int a, int b) {
        int dr = ((a >> 16) & 0xFF) - ((b >> 16) & 0xFF);
        int dg = ((a >> 8) & 0xFF) - ((b >> 8) & 0xFF);
        int db = (a & 0xFF) - (b & 0xFF);
        return dr * dr + dg * dg + db * db;
    }

    private static int fallbackColor(String name) {
        int hash = name.hashCode();
        return ((hash & 0xFF) << 16) | ((hash >> 8 & 0xFF) << 8) | ((hash >> 16) & 0xFF);
    }
}
