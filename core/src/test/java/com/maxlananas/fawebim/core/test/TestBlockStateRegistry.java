package com.maxlananas.fawebim.core.test;

import com.maxlananas.fawebim.core.world.BlockStateRegistry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A head-less block registry: enough of the Minecraft 1.21.10 block list to
 * exercise every engine code path without starting the game.
 *
 * <p>State ids are assigned as {@code blockIndex * 16 + variant}, mirroring the
 * real registry's "one id per block state" model.</p>
 */
public final class TestBlockStateRegistry implements BlockStateRegistry {

    private static final String[] BLOCKS = {
            "minecraft:air", "minecraft:stone", "minecraft:cobblestone", "minecraft:dirt",
            "minecraft:coarse_dirt", "minecraft:grass_block", "minecraft:mycelium",
            "minecraft:podzol", "minecraft:farmland", "minecraft:sand", "minecraft:red_sand",
            "minecraft:gravel", "minecraft:clay", "minecraft:bedrock", "minecraft:water",
            "minecraft:lava", "minecraft:snow_block", "minecraft:snow", "minecraft:ice",
            "minecraft:packed_ice", "minecraft:blue_ice", "minecraft:oak_log", "minecraft:oak_leaves",
            "minecraft:oak_planks", "minecraft:spruce_log", "minecraft:birch_log",
            "minecraft:oak_stairs", "minecraft:oak_slab", "minecraft:glass", "minecraft:sandstone",
            "minecraft:obsidian", "minecraft:netherrack", "minecraft:end_stone",
            "minecraft:white_wool", "minecraft:red_wool", "minecraft:blue_wool",
            "minecraft:green_wool", "minecraft:black_wool", "minecraft:gold_ore",
            "minecraft:iron_ore", "minecraft:coal_ore", "minecraft:diamond_ore",
            "minecraft:redstone_ore", "minecraft:lapis_ore", "minecraft:emerald_ore",
            "minecraft:copper_ore", "minecraft:deepslate", "minecraft:cobbled_deepslate",
            "minecraft:granite", "minecraft:diorite", "minecraft:andesite",
            "minecraft:tuff", "minecraft:calcite", "minecraft:dripstone_block",
            "minecraft:moss_block", "minecraft:mud", "minecraft:bone_block",
            "minecraft:pumpkin", "minecraft:melon", "minecraft:hay_block",
            "minecraft:chest", "minecraft:furnace", "minecraft:torch", "minecraft:glowstone",
            "minecraft:sea_lantern", "minecraft:crafting_table", "minecraft:bookshelf",
            "minecraft:sponge", "minecraft:netherite_block", "minecraft:diamond_block",
            "minecraft:iron_block", "minecraft:gold_block", "minecraft:coal_block",
            "minecraft:emerald_block", "minecraft:lapis_block", "minecraft:redstone_block",
            "minecraft:copper_block", "minecraft:slime_block", "minecraft:honey_block",
            "minecraft:mossy_cobblestone", "minecraft:stone_bricks", "minecraft:bricks",
            "minecraft:prismarine", "minecraft:dark_prismarine", "minecraft:quartz_block",
            "minecraft:purpur_block", "minecraft:magma_block", "minecraft:nether_bricks",
            "minecraft:lava_cauldron", "minecraft:fire", "minecraft:soul_fire",
            "minecraft:grass", "minecraft:fern", "minecraft:dandelion", "minecraft:poppy",
            "minecraft:oak_sapling", "minecraft:wheat", "minecraft:carrots",
            "minecraft:potatoes", "minecraft:sugar_cane", "minecraft:cactus",
            "minecraft:bamboo", "minecraft:tall_grass", "minecraft:kelp",
            "minecraft:seagrass", "minecraft:vine", "minecraft:lily_pad",
            "minecraft:crimson_stem", "minecraft:warped_stem", "minecraft:shroomlight",
            "minecraft:crying_obsidian", "minecraft:ancient_debris", "minecraft:basalt",
            "minecraft:blackstone", "minecraft:twisting_vines", "minecraft:weeping_vines",
            "minecraft:cobweb", "minecraft:barrier", "minecraft:light", "minecraft:spawner",
            "minecraft:beacon", "minecraft:conduit", "minecraft:bell", "minecraft:campfire",
            "minecraft:composter", "minecraft:barrel", "minecraft:smoker", "minecraft:blast_furnace",
            "minecraft:lectern", "minecraft:stonecutter", "minecraft:grindstone", "minecraft:loom",
            "minecraft:cartography_table", "minecraft:fletching_table", "minecraft:smithing_table",
            "minecraft:anvil", "minecraft:cauldron", "minecraft:end_rod", "minecraft:lantern",
            "minecraft:sea_pickle", "minecraft:turtle_egg", "minecraft:sniffer_egg",
    };

    private static final String[] BIOMES = {
            "minecraft:ocean", "minecraft:plains", "minecraft:desert", "minecraft:forest",
            "minecraft:taiga", "minecraft:swamp", "minecraft:river", "minecraft:nether_wastes",
            "minecraft:the_end", "minecraft:beach", "minecraft:jungle", "minecraft:savanna",
            "minecraft:badlands", "minecraft:mountains", "minecraft:snowy_plains",
            "minecraft:mushroom_fields", "minecraft:deep_ocean", "minecraft:warm_ocean",
            "minecraft:cherry_grove", "minecraft:lush_caves", "minecraft:dripstone_caves",
            "minecraft:deep_dark", "minecraft:mangrove_swamp",
    };

    private static final Map<String, String[]> CATEGORIES = new LinkedHashMap<>();

    static {
        CATEGORIES.put("wool", new String[]{"white_wool", "red_wool", "blue_wool", "green_wool", "black_wool"});
        CATEGORIES.put("leaves", new String[]{"oak_leaves"});
        CATEGORIES.put("logs", new String[]{"oak_log", "spruce_log", "birch_log"});
        CATEGORIES.put("ores", new String[]{"gold_ore", "iron_ore", "coal_ore", "diamond_ore", "redstone_ore",
                "lapis_ore", "emerald_ore", "copper_ore"});
        CATEGORIES.put("planks", new String[]{"oak_planks"});
    }

    private final Map<String, Integer> names = new LinkedHashMap<>();
    private final Map<Integer, String> ids = new HashMap<>();
    private final Map<Integer, String> descriptions = new HashMap<>();
    private final Map<String, Integer> items = new LinkedHashMap<>();
    private final List<String> tags = new ArrayList<>();

    public TestBlockStateRegistry() {
        for (String name : BLOCKS) {
            int id = names.size() * 16;
            names.put(name, id);
            ids.put(id, name);
            descriptions.put(id, name);
        }
        // One block with properties, so property handling is covered.
        int stairs = names.get("minecraft:oak_stairs");
        ids.put(stairs + 1, "minecraft:oak_stairs");
        descriptions.put(stairs + 1, "minecraft:oak_stairs[facing=north,half=bottom]");
        int log = names.get("minecraft:oak_log");
        ids.put(log + 1, "minecraft:oak_log");
        descriptions.put(log + 1, "minecraft:oak_log[axis=y]");
        items.put("minecraft:wooden_axe", 0);
        items.put("minecraft:stone", 1);
        items.put("minecraft:diamond_pickaxe", 2);
        tags.add("minecraft:logs");
        tags.add("minecraft:wool");
        tags.add("minecraft:mineable/pickaxe");
    }

    @Override
    public int air() {
        return names.get("minecraft:air");
    }

    @Override
    public boolean isAir(int stateId) {
        return stateId == air();
    }

    @Override
    public int stateCount() {
        int max = 0;
        for (int id : descriptions.keySet()) {
            max = Math.max(max, id);
        }
        return max + 1;
    }

    @Override
    public int parse(String input) {
        String key = input.toLowerCase(Locale.ROOT);
        Integer exact = names.get(key);
        if (exact != null) {
            return exact;
        }
        // Property form: minecraft:oak_log[axis=y]
        int bracket = key.indexOf('[');
        if (bracket > 0) {
            String base = key.substring(0, bracket);
            Integer baseId = names.get(base);
            if (baseId == null) {
                return -1;
            }
            for (Map.Entry<Integer, String> entry : descriptions.entrySet()) {
                if (entry.getValue().startsWith(base + "[")) {
                    return entry.getKey();
                }
            }
            return baseId;
        }
        String withNamespace = key.contains(":") ? key : "minecraft:" + key;
        Integer namespaced = names.get(withNamespace);
        return namespaced == null ? -1 : namespaced;
    }

    @Override
    public int defaultState(String blockName) {
        String key = blockName.contains(":") ? blockName.toLowerCase(Locale.ROOT)
                : "minecraft:" + blockName.toLowerCase(Locale.ROOT);
        Integer id = names.get(key);
        return id == null ? -1 : id;
    }

    @Override
    public int legacyState(int blockId, int metadata) {
        if (blockId == 0) {
            return air();
        }
        List<String> all = new ArrayList<>(names.keySet());
        // Legacy ids 1..N map to the list order used above.
        int index = blockId - 1;
        if (blockId == 1) {
            index = 1; // stone
        }
        return index >= 0 && index < all.size() ? names.get(all.get(index)) : air();
    }

    @Override
    public String name(int stateId) {
        return ids.getOrDefault(stateId, "minecraft:air");
    }

    @Override
    public String describe(int stateId) {
        return descriptions.getOrDefault(stateId, "minecraft:air");
    }

    @Override
    public Map<String, String> properties(int stateId) {
        if (stateId == names.get("minecraft:oak_stairs") + 1) {
            Map<String, String> properties = new LinkedHashMap<>();
            properties.put("facing", "north");
            properties.put("half", "bottom");
            return properties;
        }
        if (stateId == names.get("minecraft:oak_log") + 1) {
            Map<String, String> properties = new LinkedHashMap<>();
            properties.put("axis", "y");
            return properties;
        }
        return Map.of();
    }

    @Override
    public Map<String, List<String>> propertyDefs(int stateId) {
        Map<String, List<String>> defs = new LinkedHashMap<>();
        String name = name(stateId);
        if (name.equals("minecraft:oak_stairs")) {
            defs.put("facing", List.of("north", "south", "east", "west"));
            defs.put("half", List.of("top", "bottom"));
        } else if (name.equals("minecraft:oak_log")) {
            defs.put("axis", List.of("x", "y", "z"));
        }
        return defs;
    }

    @Override
    public int withProperty(int stateId, String property, String value) {
        Map<String, String> properties = new LinkedHashMap<>(properties(stateId));
        if (!properties.containsKey(property)) {
            return -1;
        }
        properties.put(property, value);
        return stateId;
    }

    @Override
    public boolean hasTag(int stateId, String tag) {
        String name = name(stateId);
        if (tag.endsWith("logs")) {
            return name.contains("log") || name.contains("stem");
        }
        if (tag.endsWith("wool")) {
            return name.contains("wool");
        }
        return false;
    }

    @Override
    public boolean matchesCategory(int stateId, String category) {
        String[] members = CATEGORIES.get(category.toLowerCase(Locale.ROOT));
        if (members == null) {
            return false;
        }
        String name = name(stateId);
        for (String member : members) {
            if (name.equals("minecraft:" + member)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<String> categories() {
        return new ArrayList<>(CATEGORIES.keySet());
    }

    @Override
    public boolean isSolid(int stateId) {
        String name = name(stateId);
        return !isAirLike(stateId) && !name.contains("water") && !name.contains("lava");
    }

    @Override
    public boolean isLiquid(int stateId) {
        String name = name(stateId);
        return name.contains("water") || name.contains("lava");
    }

    @Override
    public boolean isFullCube(int stateId) {
        String name = name(stateId);
        return isSolid(stateId) && !name.contains("stairs") && !name.contains("slab")
                && !name.contains("torch") && !name.contains("sapling") && !name.contains("grass")
                && !name.contains("fern") && !name.contains("poppy") && !name.contains("dandelion");
    }

    @Override
    public boolean isAirLike(int stateId) {
        String name = name(stateId);
        return name.equals("minecraft:air") || name.equals("minecraft:cave_air")
                || name.equals("minecraft:void_air");
    }

    @Override
    public boolean blocksLight(int stateId) {
        return isFullCube(stateId);
    }

    @Override
    public int lightEmission(int stateId) {
        String name = name(stateId);
        if (name.contains("glowstone") || name.contains("sea_lantern") || name.contains("shroomlight")
                || name.contains("lantern")) {
            return 15;
        }
        if (name.contains("torch")) {
            return 14;
        }
        return 0;
    }

    @Override
    public double blastResistance(int stateId) {
        return name(stateId).contains("bedrock") ? Double.MAX_VALUE : 1.0;
    }

    @Override
    public List<String> blockNames() {
        return new ArrayList<>(names.keySet());
    }

    @Override
    public List<String> blockTags() {
        return tags;
    }

    @Override
    public int blockFromItem(String itemName) {
        String key = itemName.contains(":") ? itemName : "minecraft:" + itemName;
        Integer id = names.get(key);
        return id == null ? -1 : id;
    }

    @Override
    public int biome(String name) {
        String key = name.contains(":") ? name.toLowerCase(Locale.ROOT)
                : "minecraft:" + name.toLowerCase(Locale.ROOT);
        for (int i = 0; i < BIOMES.length; i++) {
            if (BIOMES[i].equalsIgnoreCase(key)) {
                return i + 1;
            }
        }
        return -1;
    }

    @Override
    public String biomeName(int biomeId) {
        return biomeId >= 1 && biomeId <= BIOMES.length ? BIOMES[biomeId - 1] : "minecraft:plains";
    }

    @Override
    public List<String> biomeNames() {
        return List.of(BIOMES);
    }

    @Override
    public List<String> itemNames() {
        return new ArrayList<>(items.keySet());
    }

    @Override
    public String itemName(int itemId) {
        for (Map.Entry<String, Integer> entry : items.entrySet()) {
            if (entry.getValue() == itemId) {
                return entry.getKey();
            }
        }
        return "minecraft:air";
    }

    @Override
    public int item(String name) {
        String key = name.contains(":") ? name : "minecraft:" + name;
        return items.getOrDefault(key, -1);
    }
}
