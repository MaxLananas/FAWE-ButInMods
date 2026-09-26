package com.maxlananas.fawebim.core.world;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The tree types WorldEdit's {@code TreeGenerator.TreeType} declares, and every
 * name it answers to.
 *
 * <p>{@code /tree}, {@code //forest}, {@code //forestgen} and the forest brushes
 * all take one of these names. Upstream accepts the type's own name
 * ({@code mega_redwood}) and each of its aliases ({@code largespruce}), so a
 * command line written for WorldEdit keeps working; the world then plants the
 * vanilla tree closest to the type.</p>
 */
public final class TreeTypes {

    /** Canonical name to the other spellings upstream accepts for it. */
    private static final Map<String, List<String>> TYPES = new LinkedHashMap<>();

    static {
        type("tree", "oak", "regular");
        type("big_tree", "largeoak", "bigoak", "big", "bigtree");
        type("redwood", "spruce", "sequoia", "sequoioideae");
        type("tall_redwood", "tallspruce", "bigspruce", "tallsequoia", "tallsequoioideae");
        type("mega_redwood", "largespruce", "megaredwood");
        type("random_redwood", "randspruce", "randredwood", "randomredwood", "anyredwood");
        type("birch", "white", "whitebark");
        type("tall_birch", "tallbirch");
        type("random_birch", "randbirch", "randombirch");
        type("jungle");
        type("small_jungle");
        type("short_jungle");
        type("random_jungle", "randjungle", "randomjungle");
        type("jungle_bush", "junglebush", "jungleshrub");
        type("red_mushroom", "redmushroom", "redgiantmushroom");
        type("brown_mushroom", "brownmushroom", "browngiantmushroom");
        type("crimson_fungus", "crimsonfungus", "rednethermushroom");
        type("warped_fungus", "warpedfungus", "greennethermushroom");
        type("random_mushroom", "randmushroom", "randommushroom");
        type("swamp", "swamptree");
        type("acacia");
        type("dark_oak", "darkoak");
        type("pine");
        type("chorus_plant", "chorusplant");
        type("mangrove");
        type("tall_mangrove");
        type("cherry");
        type("pale_oak");
        type("pale_oak_creaking");
        type("random", "rand");
    }

    private TreeTypes() {
    }

    private static void type(String name, String... aliases) {
        TYPES.put(name, List.of(aliases));
    }

    /**
     * The canonical name of a tree type, or {@code null} when WorldEdit has no
     * such type.
     *
     * <p>A name is accepted with or without its underscores and in any case, so
     * {@code dark_oak} and {@code darkoak} are the same type.</p>
     */
    public static String canonical(String name) {
        if (name == null) {
            return null;
        }
        String key = name.toLowerCase(Locale.ROOT).trim();
        if (key.isEmpty()) {
            return null;
        }
        if (TYPES.containsKey(key)) {
            return key;
        }
        for (Map.Entry<String, List<String>> entry : TYPES.entrySet()) {
            for (String alias : entry.getValue()) {
                if (alias.equals(key)) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }

    /**
     * The vanilla placed feature a tree type grows.
     *
     * <p>Several types share one feature — the small and the tall birch are the
     * same feature, which grows either — and the random types pick among the
     * features of their family, which is what makes a forest look mixed.</p>
     */
    public static String feature(String canonical, java.util.Random random) {
        return switch (canonical) {
            case "tree" -> "minecraft:oak_checked";
            case "big_tree" -> "minecraft:fancy_oak_checked";
            case "redwood", "tall_redwood" -> "minecraft:spruce_checked";
            case "mega_redwood" -> "minecraft:mega_spruce_checked";
            case "random_redwood" -> pick(random, "minecraft:spruce_checked", "minecraft:mega_spruce_checked");
            case "birch", "tall_birch", "random_birch" -> "minecraft:birch_checked";
            case "jungle", "small_jungle", "short_jungle" -> "minecraft:jungle_tree";
            case "random_jungle" -> pick(random, "minecraft:jungle_tree", "minecraft:mega_jungle_tree");
            case "jungle_bush" -> "minecraft:jungle_bush";
            case "red_mushroom" -> "minecraft:huge_red_mushroom";
            case "brown_mushroom" -> "minecraft:huge_brown_mushroom";
            case "random_mushroom" ->
                    pick(random, "minecraft:huge_red_mushroom", "minecraft:huge_brown_mushroom");
            case "crimson_fungus" -> "minecraft:crimson_fungus";
            case "warped_fungus" -> "minecraft:warped_fungus";
            case "swamp" -> "minecraft:oak_checked";
            case "acacia" -> "minecraft:acacia_checked";
            case "dark_oak" -> "minecraft:dark_oak_checked";
            case "pine" -> "minecraft:pine_checked";
            case "chorus_plant" -> "minecraft:chorus_plant";
            case "mangrove", "tall_mangrove" -> "minecraft:mangrove_checked";
            case "cherry" -> "minecraft:cherry_checked";
            case "pale_oak", "pale_oak_creaking" -> "minecraft:pale_oak_checked";
            case "random" -> pick(random, "minecraft:oak_checked", "minecraft:birch_checked",
                    "minecraft:spruce_checked", "minecraft:acacia_checked", "minecraft:dark_oak_checked");
            default -> "minecraft:oak_checked";
        };
    }

    private static String pick(java.util.Random random, String... ids) {
        return ids[random.nextInt(ids.length)];
    }

    /** Every canonical name, for the message an unknown type answers with. */
    public static String names() {
        return String.join(", ", TYPES.keySet());
    }
}
