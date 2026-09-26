package com.maxlananas.fawebim.core.clipboard;

import com.maxlananas.fawebim.core.math.BlockBox;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.math.Vector3;
import com.maxlananas.fawebim.core.platform.Config;
import com.maxlananas.fawebim.core.util.InputException;
import com.maxlananas.fawebim.core.util.NbtCompound;
import com.maxlananas.fawebim.core.world.BlockState;
import com.maxlananas.fawebim.core.world.BlockStateRegistry;
import com.maxlananas.fawebim.core.world.EntityData;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The MCEdit {@code .schematic} format of Minecraft 1.12 and before: one byte
 * of block id and one nibble of metadata per block, in gzipped NBT whose root
 * is named {@code Schematic}.
 *
 * <p>Block entities are listed with their {@code x}, {@code y} and {@code z}
 * relative to the minimum corner; entities keep the position they had in the
 * world, which {@code WEOriginX/Y/Z} (the minimum corner in the world) relates
 * to the blocks. {@code WEOffsetX/Y/Z} is the minimum corner minus the origin,
 * as in the Sponge format.</p>
 */
final class McEditSchematic {

    static final String ROOT_NAME = "Schematic";

    private McEditSchematic() {
    }

    /**
     * How many blocks of the clipboard the format cannot name, so the command
     * can warn before writing them as air.
     */
    static int losses(BlockArrayClipboard clipboard) {
        BlockBox box = clipboard.getBox();
        BlockStateRegistry registry = BlockState.registry();
        int unsupported = 0;
        for (int y = box.minY(); y <= box.maxY(); y++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                for (int x = box.minX(); x <= box.maxX(); x++) {
                    int state = clipboard.getBlock(x, y, z);
                    if (state > 0 && !registry.isAirLike(state) && registry.legacyId(state) < 0) {
                        unsupported++;
                    }
                }
            }
        }
        return unsupported;
    }

    static NbtCompound write(BlockArrayClipboard clipboard) {
        BlockBox box = clipboard.getBox();
        int width = box.width();
        int height = box.height();
        int length = box.length();
        if (width > SpongeSchematic.MAX_SIDE || height > SpongeSchematic.MAX_SIDE || length > SpongeSchematic.MAX_SIDE) {
            throw new InputException("A schematic is at most " + SpongeSchematic.MAX_SIDE
                    + " blocks on each side; the clipboard is " + width + "x" + height + "x" + length);
        }
        long volume = box.volume();
        if (volume > Integer.MAX_VALUE - 8) {
            throw new InputException("The clipboard holds " + volume
                    + " blocks, more than one schematic file can store");
        }
        byte[] blocks = new byte[(int) volume];
        byte[] data = new byte[(int) volume];
        BlockStateRegistry registry = BlockState.registry();
        int index = 0;
        for (int y = box.minY(); y <= box.maxY(); y++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                for (int x = box.minX(); x <= box.maxX(); x++) {
                    int state = clipboard.getBlock(x, y, z);
                    // A block the legacy ids cannot name is written as air.
                    int id = state > 0 ? registry.legacyId(state) : -1;
                    if (id > 0) {
                        blocks[index] = (byte) id;
                        data[index] = (byte) (registry.legacyMetadata(state) & 0xF);
                    }
                    index++;
                }
            }
        }
        NbtCompound root = new NbtCompound();
        root.putShort("Width", width);
        root.putShort("Height", height);
        root.putShort("Length", length);
        root.putString("Materials", "Alpha");
        root.putByteArray("Blocks", blocks);
        root.putByteArray("Data", data);
        BlockVector3 origin = clipboard.getOrigin();
        root.putInt("WEOriginX", box.minX());
        root.putInt("WEOriginY", box.minY());
        root.putInt("WEOriginZ", box.minZ());
        root.putInt("WEOffsetX", box.minX() - origin.x());
        root.putInt("WEOffsetY", box.minY() - origin.y());
        root.putInt("WEOffsetZ", box.minZ() - origin.z());

        List<NbtCompound> tileEntities = new ArrayList<>();
        for (Map.Entry<BlockVector3, NbtCompound> entry : SchematicData.sortedBlockEntities(clipboard)) {
            BlockVector3 pos = entry.getKey();
            if (!box.contains(pos)) {
                continue;
            }
            NbtCompound tag = SchematicData.blockEntityData(entry.getValue());
            tag.putString("id", SchematicData.blockEntityId(entry.getValue(), clipboard, pos));
            tag.putInt("x", pos.x() - box.minX());
            tag.putInt("y", pos.y() - box.minY());
            tag.putInt("z", pos.z() - box.minZ());
            tileEntities.add(tag);
        }
        root.putList("TileEntities", tileEntities);

        List<NbtCompound> entities = new ArrayList<>();
        for (EntityData entity : clipboard.entities()) {
            NbtCompound tag = SchematicData.entityData(entity);
            tag.putString("id", entity.type());
            Vector3 position = entity.position();
            tag.putList("Pos", List.of(position.x(), position.y(), position.z()));
            entities.add(tag);
        }
        root.putList("Entities", entities);
        return root;
    }

    /** True when the NBT has the MCEdit layout: the block ids as a byte array. */
    static boolean isMcEdit(NbtCompound root) {
        return root.get("Blocks") instanceof byte[];
    }

    /**
     * @param maxVolume as for {@link SpongeSchematic#read}
     */
    static BlockArrayClipboard read(NbtCompound root, String name, long maxVolume) {
        int width = SchematicData.side(root, "Width");
        int height = SchematicData.side(root, "Height");
        int length = SchematicData.side(root, "Length");
        if (width <= 0 || height <= 0 || length <= 0) {
            throw new InputException("'" + name + "' has no valid size");
        }
        long volume = (long) width * height * length;
        SchematicData.checkVolume(name, volume, maxVolume);
        byte[] blocks = root.getByteArray("Blocks");
        byte[] data = root.getByteArray("Data");
        if (blocks.length < volume) {
            throw new InputException("'" + name + "' is corrupted: it has " + blocks.length
                    + " blocks for a " + width + "x" + height + "x" + length + " size");
        }
        // Numeric ids are only read when the configuration allows it, as in WorldEdit.
        boolean ancient = Config.get().allowAncientBlocks;
        BlockStateRegistry registry = BlockState.registry();
        BlockArrayClipboard clipboard = new BlockArrayClipboard(BlockVector3.ZERO);
        clipboard.setName(name);
        int index = 0;
        for (int y = 0; y < height; y++) {
            for (int z = 0; z < length; z++) {
                for (int x = 0; x < width; x++) {
                    int id = blocks[index] & 0xFF;
                    int meta = index < data.length ? data[index] & 0xF : 0;
                    index++;
                    clipboard.setBlock(x, y, z, ancient ? registry.legacyState(id, meta) : registry.air());
                }
            }
        }
        for (NbtCompound tag : SchematicData.compounds(root.get("TileEntities"))) {
            int x = tag.getInt("x", -1);
            int y = tag.getInt("y", -1);
            int z = tag.getInt("z", -1);
            if (x < 0 || y < 0 || z < 0 || x >= width || y >= height || z >= length) {
                continue;
            }
            clipboard.addBlockEntity(new BlockVector3(x, y, z), SchematicData.readBlockEntity(tag, false));
        }
        int originX = root.getInt("WEOriginX", 0);
        int originY = root.getInt("WEOriginY", 0);
        int originZ = root.getInt("WEOriginZ", 0);
        for (NbtCompound tag : SchematicData.compounds(root.get("Entities"))) {
            EntityData entity = SchematicData.readEntity(tag, false);
            if (entity != null) {
                Vector3 position = entity.position();
                clipboard.addEntity(new EntityData(entity.type(), entity.nbt(),
                        new Vector3(position.x() - originX, position.y() - originY, position.z() - originZ)));
            }
        }
        if (root.contains("WEOffsetX")) {
            clipboard.setOrigin(new BlockVector3(-root.getInt("WEOffsetX", 0), -root.getInt("WEOffsetY", 0),
                    -root.getInt("WEOffsetZ", 0)));
        } else {
            clipboard.normalize();
        }
        return clipboard;
    }
}
