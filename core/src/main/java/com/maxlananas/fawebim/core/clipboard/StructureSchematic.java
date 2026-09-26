package com.maxlananas.fawebim.core.clipboard;

import com.maxlananas.fawebim.core.math.BlockBox;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.math.Vector3;
import com.maxlananas.fawebim.core.platform.Config;
import com.maxlananas.fawebim.core.platform.Log;
import com.maxlananas.fawebim.core.util.InputException;
import com.maxlananas.fawebim.core.util.NbtCompound;
import com.maxlananas.fawebim.core.world.BlockState;
import com.maxlananas.fawebim.core.world.BlockStateRegistry;
import com.maxlananas.fawebim.core.world.EntityData;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The game's structure files ({@code .nbt}), as structure blocks and
 * {@code /place template} read them: gzipped NBT with a {@code size}, a
 * {@code palette} of block states and a list of {@code blocks}, each with a
 * {@code pos}, a {@code state} index and, for a block entity, its {@code nbt}.
 *
 * <p>Every block is listed except structure voids, air included, as FAWE writes
 * them: placing the structure clears what the air covers. A position the file
 * does not list is left alone by a paste.</p>
 */
final class StructureSchematic {

    private static final String STRUCTURE_VOID = "minecraft:structure_void";

    private StructureSchematic() {
    }

    static NbtCompound write(BlockArrayClipboard clipboard) {
        BlockBox box = clipboard.getBox();
        BlockStateRegistry registry = BlockState.registry();
        int air = registry.air();
        int[] paletteIndex = new int[Math.max(air + 1, registry.stateCount())];
        Arrays.fill(paletteIndex, -1);
        List<NbtCompound> palette = new ArrayList<>();
        List<NbtCompound> blocks = new ArrayList<>();
        Map<BlockVector3, NbtCompound> blockEntities = clipboard.blockEntities();
        for (int y = box.minY(); y <= box.maxY(); y++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                for (int x = box.minX(); x <= box.maxX(); x++) {
                    int state = clipboard.getBlock(x, y, z);
                    if (state <= 0 || state >= paletteIndex.length) {
                        state = air;
                    }
                    int index = paletteIndex[state];
                    if (index < 0) {
                        if (STRUCTURE_VOID.equals(registry.name(state))) {
                            paletteIndex[state] = Integer.MAX_VALUE;
                            continue;
                        }
                        index = palette.size();
                        paletteIndex[state] = index;
                        palette.add(paletteEntry(registry, state));
                    } else if (index == Integer.MAX_VALUE) {
                        continue;
                    }
                    NbtCompound block = new NbtCompound();
                    block.putList("pos", List.of(x - box.minX(), y - box.minY(), z - box.minZ()));
                    block.putInt("state", index);
                    NbtCompound blockEntity = blockEntities.isEmpty() ? null
                            : blockEntities.get(new BlockVector3(x, y, z));
                    if (blockEntity != null) {
                        NbtCompound nbt = SchematicData.blockEntityData(blockEntity);
                        nbt.putString("id", SchematicData.blockEntityId(blockEntity, clipboard,
                                new BlockVector3(x, y, z)));
                        block.put("nbt", nbt);
                    }
                    blocks.add(block);
                }
            }
        }
        List<NbtCompound> entities = new ArrayList<>();
        for (EntityData entity : clipboard.entities()) {
            List<Double> pos = SchematicData.relativePosition(entity, box);
            NbtCompound nbt = SchematicData.entityData(entity);
            nbt.putString("id", entity.type());
            NbtCompound tag = new NbtCompound();
            tag.putList("pos", pos);
            tag.putList("blockPos", List.of((int) Math.floor(pos.get(0)), (int) Math.floor(pos.get(1)),
                    (int) Math.floor(pos.get(2))));
            tag.put("nbt", nbt);
            entities.add(tag);
        }
        NbtCompound root = new NbtCompound();
        root.putInt("DataVersion", Config.DATA_VERSION);
        root.putList("size", List.of(box.width(), box.height(), box.length()));
        root.putList("palette", palette);
        root.putList("blocks", blocks);
        root.putList("entities", entities);
        return root;
    }

    private static NbtCompound paletteEntry(BlockStateRegistry registry, int state) {
        NbtCompound entry = new NbtCompound();
        entry.putString("Name", registry.name(state));
        Map<String, String> properties = registry.properties(state);
        if (!properties.isEmpty()) {
            NbtCompound props = new NbtCompound();
            properties.forEach(props::putString);
            entry.put("Properties", props);
        }
        return entry;
    }

    /** True when the NBT has the structure layout. */
    static boolean isStructure(NbtCompound root) {
        return root.contains("blocks") && (root.contains("palette") || root.contains("palettes"));
    }

    /**
     * @param maxVolume as for {@link SpongeSchematic#read}
     */
    static BlockArrayClipboard read(NbtCompound root, String name, long maxVolume) {
        int[] size = SchematicData.intTriple(root.get("size"));
        if (size == null || size[0] <= 0 || size[1] <= 0 || size[2] <= 0) {
            throw new InputException("'" + name + "' has no valid size");
        }
        SchematicData.checkVolume(name, (long) size[0] * size[1] * size[2], maxVolume);
        // Some structures (shipwrecks) carry several palettes to pick from; the first is the plain one.
        Object paletteTag = root.contains("palette") ? root.get("palette")
                : root.get("palettes") instanceof List<?> palettes && !palettes.isEmpty() ? palettes.get(0) : null;
        List<NbtCompound> entries = SchematicData.compounds(paletteTag);
        BlockStateRegistry registry = BlockState.registry();
        int[] states = new int[entries.size()];
        Set<String> unknown = new LinkedHashSet<>();
        for (int i = 0; i < states.length; i++) {
            String state = stateString(entries.get(i));
            int id = registry.parse(state);
            if (id < 0) {
                unknown.add(state);
                id = registry.air();
            }
            // A structure void stands for "leave the world alone", which is what a position without a block is.
            states[i] = STRUCTURE_VOID.equals(registry.name(id)) ? -1 : id;
        }
        if (!unknown.isEmpty()) {
            Log.warn("Structure '" + name + "' names " + unknown.size() + " block state(s) this game does not have,"
                    + " loaded as air: " + String.join(", ", unknown.stream().limit(5).toList())
                    + (unknown.size() > 5 ? ", ..." : ""));
        }
        BlockArrayClipboard clipboard = new BlockArrayClipboard(BlockVector3.ZERO);
        clipboard.setName(name);
        for (NbtCompound block : SchematicData.compounds(root.get("blocks"))) {
            int[] pos = SchematicData.intTriple(block.get("pos"));
            int index = block.getInt("state", -1);
            if (pos == null || pos[0] < 0 || pos[1] < 0 || pos[2] < 0
                    || pos[0] >= size[0] || pos[1] >= size[1] || pos[2] >= size[2]
                    || index < 0 || index >= states.length || states[index] < 0) {
                continue;
            }
            clipboard.setBlock(pos[0], pos[1], pos[2], states[index]);
            NbtCompound nbt = block.getCompoundOrNull("nbt");
            if (nbt != null) {
                clipboard.addBlockEntity(new BlockVector3(pos[0], pos[1], pos[2]),
                        SchematicData.readBlockEntity(nbt, false));
            }
        }
        for (NbtCompound tag : SchematicData.compounds(root.get("entities"))) {
            double[] pos = SchematicData.doubleTriple(tag.get("pos"));
            NbtCompound nbt = tag.getCompoundOrNull("nbt");
            String id = nbt == null ? null : nbt.getString("id", null);
            if (pos != null && id != null) {
                NbtCompound data = nbt.clone();
                data.remove("id");
                data.remove("Pos");
                clipboard.addEntity(new EntityData(id, data, new Vector3(pos[0], pos[1], pos[2])));
            }
        }
        // The template's corner is where the game places it, whichever of its positions hold a block.
        clipboard.setOrigin(BlockVector3.ZERO);
        return clipboard;
    }

    /** {@code minecraft:oak_stairs[facing=north,half=bottom]} from a palette entry. */
    private static String stateString(NbtCompound entry) {
        String name = entry.getString("Name", "minecraft:air");
        NbtCompound properties = entry.getCompoundOrNull("Properties");
        if (properties == null || properties.isEmpty()) {
            return name;
        }
        StringBuilder out = new StringBuilder(name).append('[');
        boolean first = true;
        for (String key : properties.keySet()) {
            if (!first) {
                out.append(',');
            }
            out.append(key).append('=').append(properties.getString(key, ""));
            first = false;
        }
        return out.append(']').toString();
    }
}
