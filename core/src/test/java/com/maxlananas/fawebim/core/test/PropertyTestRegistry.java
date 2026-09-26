package com.maxlananas.fawebim.core.test;

import com.maxlananas.fawebim.core.world.BlockStateRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A registry with the game's property model for a handful of blocks: every
 * combination of property values is a state of its own, as in the game. The
 * main test registry has one state per block; the transform tests need
 * stairs, rails, doors and signs with all their states.
 */
final class PropertyTestRegistry implements BlockStateRegistry {

    private static final List<String> HORIZONTAL = List.of("north", "south", "west", "east");
    private static final List<String> ALL_DIRECTIONS = List.of("north", "east", "south", "west", "up", "down");
    private static final List<String> BOOLEAN = List.of("true", "false");

    private record Block(String name, int base, List<String> keys, List<List<String>> values, int count) {
    }

    private final List<Block> blocks = new ArrayList<>();
    private final Map<String, Block> byName = new LinkedHashMap<>();
    private int next;

    PropertyTestRegistry() {
        block("minecraft:air");
        block("minecraft:stone");
        block("minecraft:dirt");
        block("minecraft:grass_block");
        block("minecraft:oak_stairs", "facing", HORIZONTAL, "half", List.of("top", "bottom"),
                "shape", List.of("straight", "inner_left", "inner_right", "outer_left", "outer_right"),
                "waterlogged", BOOLEAN);
        block("minecraft:oak_log", "axis", List.of("x", "y", "z"));
        block("minecraft:oak_fence", "north", BOOLEAN, "east", BOOLEAN, "south", BOOLEAN, "west", BOOLEAN);
        block("minecraft:cobblestone_wall", "up", BOOLEAN, "north", List.of("none", "low", "tall"),
                "east", List.of("none", "low", "tall"), "south", List.of("none", "low", "tall"),
                "west", List.of("none", "low", "tall"));
        block("minecraft:redstone_wire", "north", List.of("up", "side", "none"), "east", List.of("up", "side", "none"),
                "south", List.of("up", "side", "none"), "west", List.of("up", "side", "none"));
        List<String> steps = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            steps.add(String.valueOf(i));
        }
        block("minecraft:oak_sign", "rotation", steps, "waterlogged", BOOLEAN);
        block("minecraft:rail", "shape", List.of("north_south", "east_west", "ascending_east", "ascending_west",
                "ascending_north", "ascending_south", "south_east", "south_west", "north_west", "north_east"));
        block("minecraft:powered_rail", "shape", List.of("north_south", "east_west", "ascending_east",
                "ascending_west", "ascending_north", "ascending_south"), "powered", BOOLEAN);
        block("minecraft:oak_door", "facing", HORIZONTAL, "half", List.of("upper", "lower"),
                "hinge", List.of("left", "right"), "open", BOOLEAN);
        block("minecraft:chest", "facing", HORIZONTAL, "type", List.of("single", "left", "right"));
        block("minecraft:hopper", "facing", List.of("down", "north", "south", "west", "east"), "enabled", BOOLEAN);
        block("minecraft:observer", "facing", ALL_DIRECTIONS, "powered", BOOLEAN);
        block("minecraft:lantern", "hanging", BOOLEAN, "waterlogged", BOOLEAN);
        block("minecraft:oak_slab", "type", List.of("top", "bottom", "double"), "waterlogged", BOOLEAN);
        block("minecraft:stone_button", "face", List.of("floor", "wall", "ceiling"), "facing", HORIZONTAL,
                "powered", BOOLEAN);
        block("minecraft:jigsaw", "orientation", List.of("down_east", "down_north", "down_south", "down_west",
                "up_east", "up_north", "up_south", "up_west", "west_up", "east_up", "north_up", "south_up"));
        block("minecraft:vine", "east", BOOLEAN, "north", BOOLEAN, "south", BOOLEAN, "up", BOOLEAN, "west", BOOLEAN);
        block("minecraft:brown_mushroom_block", "down", BOOLEAN, "east", BOOLEAN, "north", BOOLEAN, "south", BOOLEAN,
                "up", BOOLEAN, "west", BOOLEAN);
        block("minecraft:bell", "attachment", List.of("floor", "ceiling", "single_wall", "double_wall"),
                "facing", HORIZONTAL);
        block("minecraft:piston_head", "facing", ALL_DIRECTIONS, "type", List.of("normal", "sticky"));
    }

    private void block(String name, Object... properties) {
        List<String> keys = new ArrayList<>();
        List<List<String>> values = new ArrayList<>();
        int count = 1;
        for (int i = 0; i < properties.length; i += 2) {
            keys.add((String) properties[i]);
            @SuppressWarnings("unchecked")
            List<String> allowed = (List<String>) properties[i + 1];
            values.add(allowed);
            count *= allowed.size();
        }
        Block block = new Block(name, next, keys, values, count);
        blocks.add(block);
        byName.put(name, block);
        next += count;
    }

    private Block blockOf(int state) {
        for (Block block : blocks) {
            if (state >= block.base() && state < block.base() + block.count()) {
                return block;
            }
        }
        return null;
    }

    /** Every state of a block, for the tests that walk them all. */
    List<Integer> statesOfBlock(String name) {
        Block block = byName.get(name);
        List<Integer> states = new ArrayList<>();
        for (int i = 0; i < block.count(); i++) {
            states.add(block.base() + i);
        }
        return states;
    }

    List<String> names() {
        return new ArrayList<>(byName.keySet());
    }

    private int[] digits(Block block, int state) {
        int[] digits = new int[block.keys().size()];
        int rest = state - block.base();
        for (int i = digits.length - 1; i >= 0; i--) {
            int size = block.values().get(i).size();
            digits[i] = rest % size;
            rest /= size;
        }
        return digits;
    }

    private int encode(Block block, int[] digits) {
        int index = 0;
        for (int i = 0; i < digits.length; i++) {
            index = index * block.values().get(i).size() + digits[i];
        }
        return block.base() + index;
    }

    @Override
    public int air() {
        return 0;
    }

    @Override
    public boolean isAir(int stateId) {
        return stateId == 0;
    }

    @Override
    public int stateCount() {
        return next;
    }

    @Override
    public int parse(String input) {
        String trimmed = input.trim();
        int bracket = trimmed.indexOf('[');
        String name = bracket < 0 ? trimmed : trimmed.substring(0, bracket);
        if (!name.contains(":")) {
            name = "minecraft:" + name;
        }
        Block block = byName.get(name);
        if (block == null) {
            return -1;
        }
        int state = block.base();
        if (bracket >= 0 && trimmed.endsWith("]")) {
            for (String pair : trimmed.substring(bracket + 1, trimmed.length() - 1).split(",")) {
                String[] parts = pair.split("=");
                if (parts.length != 2) {
                    return -1;
                }
                state = withProperty(state, parts[0].trim(), parts[1].trim());
                if (state < 0) {
                    return -1;
                }
            }
        }
        return state;
    }

    @Override
    public int defaultState(String blockName) {
        Block block = byName.get(blockName.contains(":") ? blockName : "minecraft:" + blockName);
        return block == null ? -1 : block.base();
    }

    @Override
    public String name(int stateId) {
        Block block = blockOf(stateId);
        return block == null ? "minecraft:air" : block.name();
    }

    @Override
    public String describe(int stateId) {
        Block block = blockOf(stateId);
        if (block == null) {
            return "minecraft:air";
        }
        if (block.keys().isEmpty()) {
            return block.name();
        }
        StringBuilder out = new StringBuilder(block.name()).append('[');
        int[] digits = digits(block, stateId);
        for (int i = 0; i < digits.length; i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(block.keys().get(i)).append('=').append(block.values().get(i).get(digits[i]));
        }
        return out.append(']').toString();
    }

    @Override
    public Map<String, String> properties(int stateId) {
        Block block = blockOf(stateId);
        Map<String, String> properties = new LinkedHashMap<>();
        if (block != null) {
            int[] digits = digits(block, stateId);
            for (int i = 0; i < digits.length; i++) {
                properties.put(block.keys().get(i), block.values().get(i).get(digits[i]));
            }
        }
        return properties;
    }

    @Override
    public Map<String, List<String>> propertyDefs(int stateId) {
        Block block = blockOf(stateId);
        Map<String, List<String>> definitions = new LinkedHashMap<>();
        if (block != null) {
            for (int i = 0; i < block.keys().size(); i++) {
                definitions.put(block.keys().get(i), block.values().get(i));
            }
        }
        return definitions;
    }

    @Override
    public int withProperty(int stateId, String property, String value) {
        Block block = blockOf(stateId);
        if (block == null) {
            return -1;
        }
        int key = block.keys().indexOf(property);
        if (key < 0) {
            return -1;
        }
        int valueIndex = block.values().get(key).indexOf(value);
        if (valueIndex < 0) {
            return -1;
        }
        int[] digits = digits(block, stateId);
        digits[key] = valueIndex;
        return encode(block, digits);
    }

    @Override
    public boolean hasTag(int stateId, String tag) {
        return false;
    }

    @Override
    public boolean matchesCategory(int stateId, String category) {
        return false;
    }

    @Override
    public List<String> categories() {
        return List.of();
    }

    @Override
    public boolean isSolid(int stateId) {
        return stateId != 0;
    }

    @Override
    public boolean isLiquid(int stateId) {
        return false;
    }

    @Override
    public boolean isFullCube(int stateId) {
        return stateId != 0;
    }

    @Override
    public boolean isAirLike(int stateId) {
        return stateId == 0;
    }

    @Override
    public boolean blocksLight(int stateId) {
        return stateId != 0;
    }

    @Override
    public int lightEmission(int stateId) {
        return 0;
    }

    @Override
    public double blastResistance(int stateId) {
        return 1;
    }

    @Override
    public List<String> blockNames() {
        return names();
    }

    @Override
    public List<String> blockTags() {
        return List.of();
    }

    @Override
    public int blockFromItem(String itemName) {
        return defaultState(itemName);
    }

    @Override
    public int biome(String name) {
        return name.equals("minecraft:plains") ? 0 : -1;
    }

    @Override
    public String biomeName(int biomeId) {
        return "minecraft:plains";
    }

    @Override
    public List<String> biomeNames() {
        return List.of("minecraft:plains");
    }

    @Override
    public List<String> itemNames() {
        return List.of();
    }

    @Override
    public String itemName(int itemId) {
        return "minecraft:air";
    }

    @Override
    public int item(String name) {
        return -1;
    }
}
