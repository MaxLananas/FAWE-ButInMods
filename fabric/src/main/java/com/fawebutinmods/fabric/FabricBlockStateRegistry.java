package com.fawebutinmods.fabric;

import com.fawebutinmods.core.world.BlockStateRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The bridge between the engine's integer block ids and Minecraft's block
 * registry.
 *
 * <p>Ids are the Minecraft block-state ids ({@code Block.BLOCK_STATE_REGISTRY}),
 * which is exactly what FAWE uses: a primitive {@code int} per block lets the
 * engine pack a 16³ section, a clipboard or a history record into arrays.</p>
 */
public final class FabricBlockStateRegistry implements BlockStateRegistry {

    /** FAWE's block categories ({@code ##wool}, {@code ##logs}, ...) mapped to tags. */
    private static final Map<String, String[]> CATEGORY_TAGS = Map.ofEntries(
            Map.entry("wool", new String[]{"minecraft:wool"}),
            Map.entry("planks", new String[]{"minecraft:planks"}),
            Map.entry("logs", new String[]{"minecraft:logs"}),
            Map.entry("leaves", new String[]{"minecraft:leaves"}),
            Map.entry("saplings", new String[]{"minecraft:saplings"}),
            Map.entry("flowers", new String[]{"minecraft:flowers", "minecraft:small_flowers"}),
            Map.entry("crops", new String[]{"minecraft:crops"}),
            Map.entry("ores", new String[]{"minecraft:ores"}),
            Map.entry("stone", new String[]{"minecraft:stone_bricks", "minecraft:base_stone_overworld"}),
            Map.entry("stairs", new String[]{"minecraft:stairs"}),
            Map.entry("slabs", new String[]{"minecraft:slabs"}),
            Map.entry("walls", new String[]{"minecraft:walls"}),
            Map.entry("fences", new String[]{"minecraft:fences"}),
            Map.entry("glass", new String[]{"minecraft:impermeable"}),
            Map.entry("terracotta", new String[]{"minecraft:terracotta"}),
            Map.entry("concrete", new String[]{"minecraft:concrete"}),
            Map.entry("sand", new String[]{"minecraft:sand"}),
            Map.entry("dirt", new String[]{"minecraft:dirt"}),
            Map.entry("plants", new String[]{"minecraft:replaceable_by_trees", "minecraft:flowers"}),
            Map.entry("air", new String[]{}));

    private final Map<Integer, String> nameCache = new ConcurrentHashMap<>();
    private final Map<Integer, Map<String, String>> propertyCache = new ConcurrentHashMap<>();
    private int airId = -1;
    private int stateCount;

    public FabricBlockStateRegistry() {
        refresh();
    }

    /** Recomputes the tallies; called once after the registries are frozen. */
    public void refresh() {
        airId = idOf(net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
        int max = 0;
        for (Block block : BuiltInRegistries.BLOCK) {
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                max = Math.max(max, idOf(state));
            }
            if (block.getStateDefinition().getPossibleStates().isEmpty()) {
                max = Math.max(max, idOf(block.defaultBlockState()));
            }
        }
        stateCount = max + 1;
    }

    private static int idOf(BlockState state) {
        return Block.getId(state);
    }

    private static BlockState stateOf(int id) {
        return Block.stateById(id);
    }

    @Override
    public int air() {
        return airId < 0 ? 0 : airId;
    }

    @Override
    public boolean isAir(int stateId) {
        BlockState state = stateOf(stateId);
        return state != null && state.isAir();
    }

    @Override
    public int stateCount() {
        return stateCount;
    }

    @Override
    public int parse(String input) {
        String key = input.trim();
        if (key.isEmpty()) {
            return -1;
        }
        String properties = "";
        int bracket = key.indexOf('[');
        if (bracket >= 0 && key.endsWith("]")) {
            properties = key.substring(bracket + 1, key.length() - 1);
            key = key.substring(0, bracket);
        }
        ResourceLocation id = ResourceLocation.tryParse(key.contains(":") ? key : "minecraft:" + key);
        if (id == null) {
            return -1;
        }
        Block block = BuiltInRegistries.BLOCK.getValue(id);
        if (block == null) {
            return -1;
        }
        BlockState state = block.defaultBlockState();
        if (!properties.isEmpty()) {
            for (String pair : properties.split(",")) {
                int equals = pair.indexOf('=');
                if (equals <= 0) {
                    continue;
                }
                String name = pair.substring(0, equals).trim();
                String value = pair.substring(equals + 1).trim();
                BlockState updated = applyProperty(state, name, value);
                if (updated == null) {
                    return -1;
                }
                state = updated;
            }
        }
        return idOf(state);
    }

    /** Applies a property by name, or returns null when it does not exist. */
    private static BlockState applyProperty(BlockState state, String name, String value) {
        for (Property<?> property : state.getProperties()) {
            if (!property.getName().equals(name)) {
                continue;
            }
            for (Comparable<?> possible : property.getPossibleValues()) {
                if (String.valueOf(possible).equalsIgnoreCase(value)) {
                    @SuppressWarnings({"unchecked", "rawtypes"})
                    BlockState updated = state.setValue((Property) property, (Comparable) possible);
                    return updated;
                }
            }
            return null;
        }
        return null;
    }

    @Override
    public int defaultState(String blockName) {
        ResourceLocation id = ResourceLocation.tryParse(blockName.contains(":") ? blockName
                : "minecraft:" + blockName);
        if (id == null) {
            return -1;
        }
        Block block = BuiltInRegistries.BLOCK.getValue(id);
        if (block == null) {
            return -1;
        }
        return idOf(block.defaultBlockState());
    }

    @Override
    public int legacyState(int blockId, int metadata) {
        // 1.12 numeric ids: stone = 1, grass = 2, dirt = 3, ... only the first
        // few are unambiguous, the rest is best-effort.
        String name = switch (blockId) {
            case 0 -> "minecraft:air";
            case 1 -> "minecraft:stone";
            case 2 -> "minecraft:grass_block";
            case 3 -> "minecraft:dirt";
            case 4 -> "minecraft:cobblestone";
            case 5 -> "minecraft:oak_planks";
            case 7 -> "minecraft:bedrock";
            case 8, 9 -> "minecraft:water";
            case 10, 11 -> "minecraft:lava";
            case 12 -> "minecraft:sand";
            case 13 -> "minecraft:gravel";
            case 14 -> "minecraft:gold_ore";
            case 15 -> "minecraft:iron_ore";
            case 16 -> "minecraft:coal_ore";
            case 17 -> "minecraft:oak_log";
            case 18 -> "minecraft:oak_leaves";
            case 20 -> "minecraft:glass";
            case 24 -> "minecraft:sandstone";
            case 45 -> "minecraft:bricks";
            case 49 -> "minecraft:obsidian";
            case 54 -> "minecraft:chest";
            case 56 -> "minecraft:diamond_ore";
            case 57 -> "minecraft:diamond_block";
            case 73, 74 -> "minecraft:redstone_ore";
            case 79 -> "minecraft:ice";
            case 80 -> "minecraft:snow_block";
            case 82 -> "minecraft:clay";
            case 87 -> "minecraft:netherrack";
            case 89 -> "minecraft:glowstone";
            case 98 -> "minecraft:stone_bricks";
            case 110 -> "minecraft:mycelium";
            case 121 -> "minecraft:end_stone";
            case 129 -> "minecraft:emerald_ore";
            case 133 -> "minecraft:emerald_block";
            default -> null;
        };
        if (name == null) {
            return air();
        }
        Block block = BuiltInRegistries.BLOCK.getValue(ResourceLocation.parse(name));
        if (block == null) {
            return air();
        }
        BlockState state = block.defaultBlockState();
        if (metadata != 0 && block == net.minecraft.world.level.block.Blocks.OAK_LOG) {
            BlockState rotated = applyProperty(state, "axis", metadata == 1 ? "x" : metadata == 2 ? "z" : "y");
            if (rotated != null) {
                state = rotated;
            }
        }
        return idOf(state);
    }

    @Override
    public String name(int stateId) {
        return nameCache.computeIfAbsent(stateId, id -> {
            BlockState state = stateOf(id);
            if (state == null) {
                return "minecraft:air";
            }
            return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        });
    }

    @Override
    public String describe(int stateId) {
        BlockState state = stateOf(stateId);
        if (state == null) {
            return "minecraft:air";
        }
        StringBuilder sb = new StringBuilder(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        Map<String, String> properties = properties(stateId);
        if (!properties.isEmpty()) {
            sb.append('[');
            boolean first = true;
            for (Map.Entry<String, String> entry : properties.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                sb.append(entry.getKey()).append('=').append(entry.getValue());
                first = false;
            }
            sb.append(']');
        }
        return sb.toString();
    }

    @Override
    public Map<String, String> properties(int stateId) {
        return propertyCache.computeIfAbsent(stateId, id -> {
            BlockState state = stateOf(id);
            Map<String, String> map = new LinkedHashMap<>();
            if (state != null) {
                for (Map.Entry<Property<?>, Comparable<?>> entry : state.getValues().entrySet()) {
                    map.put(entry.getKey().getName(), String.valueOf(entry.getValue()));
                }
            }
            return map;
        });
    }

    @Override
    public Map<String, List<String>> propertyDefs(int stateId) {
        Map<String, List<String>> defs = new LinkedHashMap<>();
        BlockState state = stateOf(stateId);
        if (state == null) {
            return defs;
        }
        for (Property<?> property : state.getProperties()) {
            List<String> values = new ArrayList<>();
            for (Comparable<?> possible : property.getPossibleValues()) {
                values.add(String.valueOf(possible));
            }
            defs.put(property.getName(), values);
        }
        return defs;
    }

    @Override
    public int withProperty(int stateId, String property, String value) {
        BlockState state = stateOf(stateId);
        if (state == null) {
            return -1;
        }
        BlockState updated = applyProperty(state, property, value);
        return updated == null ? -1 : idOf(updated);
    }

    @Override
    public boolean hasTag(int stateId, String tag) {
        BlockState state = stateOf(stateId);
        if (state == null) {
            return false;
        }
        ResourceLocation id = ResourceLocation.tryParse(tag.startsWith("#") ? tag.substring(1) : tag);
        if (id == null) {
            return false;
        }
        try {
            return state.is(TagKey.create(Registries.BLOCK, id));
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public boolean matchesCategory(int stateId, String category) {
        String[] tags = CATEGORY_TAGS.get(category.toLowerCase(Locale.ROOT));
        if (tags == null) {
            return false;
        }
        for (String tag : tags) {
            if (hasTag(stateId, tag)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<String> categories() {
        return new ArrayList<>(CATEGORY_TAGS.keySet());
    }

    @Override
    public boolean isSolid(int stateId) {
        BlockState state = stateOf(stateId);
        return state != null && state.isSolid();
    }

    @Override
    public boolean isLiquid(int stateId) {
        BlockState state = stateOf(stateId);
        return state != null && !state.getFluidState().isEmpty();
    }

    @Override
    public boolean isFullCube(int stateId) {
        BlockState state = stateOf(stateId);
        return state != null && !state.isAir()
                && state.isCollisionShapeFullBlock(net.minecraft.world.level.EmptyBlockGetter.INSTANCE,
                net.minecraft.core.BlockPos.ZERO);
    }

    @Override
    public boolean isAirLike(int stateId) {
        return isAir(stateId);
    }

    @Override
    public boolean blocksLight(int stateId) {
        BlockState state = stateOf(stateId);
        return state != null && state.canOcclude();
    }

    @Override
    public int lightEmission(int stateId) {
        BlockState state = stateOf(stateId);
        return state == null ? 0 : state.getLightEmission();
    }

    @Override
    public double blastResistance(int stateId) {
        BlockState state = stateOf(stateId);
        return state == null ? 0 : state.getBlock().getExplosionResistance();
    }

    @Override
    public List<String> blockNames() {
        List<String> names = new ArrayList<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            names.add(BuiltInRegistries.BLOCK.getKey(block).toString());
        }
        names.sort(String::compareTo);
        return names;
    }

    @Override
    public List<String> blockTags() {
        List<String> tags = new ArrayList<>();
        for (TagKey<Block> tag : BuiltInRegistries.BLOCK.getTagNames().toList()) {
            tags.add(tag.location().toString());
        }
        tags.sort(String::compareTo);
        return tags;
    }

    @Override
    public int blockFromItem(String itemName) {
        ResourceLocation id = ResourceLocation.tryParse(itemName.contains(":") ? itemName
                : "minecraft:" + itemName);
        if (id == null) {
            return -1;
        }
        var item = BuiltInRegistries.ITEM.getValue(id);
        if (item == null) {
            return -1;
        }
        Block block = Block.byItem(item);
        if (block == net.minecraft.world.level.block.Blocks.AIR) {
            return -1;
        }
        return idOf(block.defaultBlockState());
    }

    // ------------------------------------------------------------------ biomes

    @Override
    public int biome(String name) {
        ResourceLocation id = ResourceLocation.tryParse(name.contains(":") ? name : "minecraft:" + name);
        if (id == null) {
            return -1;
        }
        var biome = BuiltInRegistries.BIOME.getValue(id);
        if (biome == null) {
            return -1;
        }
        return BuiltInRegistries.BIOME.getId(biome);
    }

    @Override
    public String biomeName(int biomeId) {
        var biome = BuiltInRegistries.BIOME.byId(biomeId);
        return biome == null ? "minecraft:plains" : BuiltInRegistries.BIOME.getKey(biome).toString();
    }

    @Override
    public List<String> biomeNames() {
        List<String> names = new ArrayList<>();
        for (var biome : BuiltInRegistries.BIOME) {
            names.add(BuiltInRegistries.BIOME.getKey(biome).toString());
        }
        names.sort(String::compareTo);
        return names;
    }

    // ------------------------------------------------------------------- items

    @Override
    public List<String> itemNames() {
        List<String> names = new ArrayList<>();
        for (var item : BuiltInRegistries.ITEM) {
            names.add(BuiltInRegistries.ITEM.getKey(item).toString());
        }
        names.sort(String::compareTo);
        return names;
    }

    @Override
    public String itemName(int itemId) {
        var item = BuiltInRegistries.ITEM.byId(itemId);
        return item == null ? "minecraft:air" : BuiltInRegistries.ITEM.getKey(item).toString();
    }

    @Override
    public int item(String name) {
        ResourceLocation id = ResourceLocation.tryParse(name.contains(":") ? name : "minecraft:" + name);
        if (id == null) {
            return -1;
        }
        var item = BuiltInRegistries.ITEM.getValue(id);
        if (item == null) {
            return -1;
        }
        return BuiltInRegistries.ITEM.getId(item);
    }
}
