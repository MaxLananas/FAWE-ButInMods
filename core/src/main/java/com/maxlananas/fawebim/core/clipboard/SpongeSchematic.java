package com.maxlananas.fawebim.core.clipboard;

import com.maxlananas.fawebim.core.math.BlockBox;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.platform.Config;
import com.maxlananas.fawebim.core.platform.Log;
import com.maxlananas.fawebim.core.util.InputException;
import com.maxlananas.fawebim.core.util.NbtCompound;
import com.maxlananas.fawebim.core.world.BlockState;
import com.maxlananas.fawebim.core.world.BlockStateRegistry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Sponge schematic format, versions 1 to 3, as specified by
 * SpongePowered's Schematic-Specification and written by WorldEdit and FAWE:
 * {@code .schem} files of gzipped NBT.
 *
 * <p>Positions in the file are relative to the schematic's minimum corner.
 * Where a paste puts the clipboard travels in WorldEdit's metadata:
 * {@code Metadata.WEOffsetX/Y/Z} is the minimum corner minus the origin.</p>
 *
 * <p>Earlier builds of this mod wrote a layout of their own under the same
 * extension: the dimensions at the root, the v3 palette and data directly under
 * {@code Schematic}, block entities and entities in compounds, lengths as
 * varints, v1/v2 block data as shorts, and the blocks counted from the
 * clipboard's origin. No other program reads those files; this class still
 * does, so nothing a player saved is lost.</p>
 */
final class SpongeSchematic {

    /** Every side is an unsigned short. */
    static final int MAX_SIDE = 0xFFFF;

    /**
     * The largest palette index accepted. No version of the game has anywhere
     * near a million block states, so a larger index is a broken file, and
     * refusing it keeps the palette table small.
     */
    private static final int MAX_PALETTE_INDEX = 1 << 20;

    private SpongeSchematic() {
    }

    /** The root compound's name the format expects: v1 and v2 name it, v3 does not. */
    static String rootName(int version) {
        return version >= 3 ? "" : "Schematic";
    }

    /**
     * The schematic of a clipboard. The clipboard is only read: its block
     * entities and entities are copied, never changed.
     *
     * @throws InputException when a side exceeds {@link #MAX_SIDE} or the block
     *                        data would not fit one NBT array
     */
    static NbtCompound write(BlockArrayClipboard clipboard, int version) {
        BlockBox box = clipboard.getBox();
        int width = box.width();
        int height = box.height();
        int length = box.length();
        if (width > MAX_SIDE || height > MAX_SIDE || length > MAX_SIDE) {
            throw new InputException("A schematic is at most " + MAX_SIDE + " blocks on each side; the clipboard is "
                    + width + "x" + height + "x" + length);
        }
        long volume = box.volume();
        if (volume > Integer.MAX_VALUE - 8) {
            throw new InputException("The clipboard holds " + volume
                    + " blocks, more than one schematic file can store");
        }

        BlockStateRegistry registry = BlockState.registry();
        int air = registry.air();
        int[] paletteIndex = new int[Math.max(air + 1, registry.stateCount())];
        Arrays.fill(paletteIndex, -1);
        List<Integer> palette = new ArrayList<>();
        // Air comes first, as FAWE writes it.
        paletteIndex[air] = 0;
        palette.add(air);
        VarIntBuffer data = new VarIntBuffer((int) volume);
        for (int y = box.minY(); y <= box.maxY(); y++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                for (int x = box.minX(); x <= box.maxX(); x++) {
                    int state = clipboard.getBlock(x, y, z);
                    if (state <= 0 || state >= paletteIndex.length) {
                        state = air;
                    }
                    int index = paletteIndex[state];
                    if (index < 0) {
                        index = palette.size();
                        paletteIndex[state] = index;
                        palette.add(state);
                    }
                    data.write(index);
                }
            }
        }
        NbtCompound paletteTag = new NbtCompound();
        for (int i = 0; i < palette.size(); i++) {
            paletteTag.putInt(registry.describe(palette.get(i)), i);
        }

        NbtCompound body = new NbtCompound();
        body.putInt("Version", version);
        body.putInt("DataVersion", Config.DATA_VERSION);
        BlockVector3 origin = clipboard.getOrigin();
        NbtCompound metadata = new NbtCompound();
        metadata.putInt("WEOffsetX", box.minX() - origin.x());
        metadata.putInt("WEOffsetY", box.minY() - origin.y());
        metadata.putInt("WEOffsetZ", box.minZ() - origin.z());
        body.put("Metadata", metadata);
        body.putShort("Width", width);
        body.putShort("Height", height);
        body.putShort("Length", length);
        body.putIntArray("Offset", new int[]{box.minX(), box.minY(), box.minZ()});

        List<NbtCompound> blockEntities = blockEntities(clipboard, box, version);
        List<NbtCompound> entities = version >= 2 ? entities(clipboard, box, version) : List.of();
        if (version >= 3) {
            NbtCompound blocks = new NbtCompound();
            blocks.put("Palette", paletteTag);
            blocks.putByteArray("Data", data.toByteArray());
            blocks.putList("BlockEntities", blockEntities);
            body.put("Blocks", blocks);
            if (!entities.isEmpty()) {
                body.putList("Entities", entities);
            }
            NbtCompound root = new NbtCompound();
            root.put("Schematic", body);
            return root;
        }
        body.putInt("PaletteMax", palette.size());
        body.put("Palette", paletteTag);
        body.putByteArray("BlockData", data.toByteArray());
        if (version == 1) {
            body.putList("TileEntities", blockEntities);
        } else {
            body.putList("BlockEntities", blockEntities);
            if (!entities.isEmpty()) {
                body.putList("Entities", entities);
            }
        }
        return body;
    }

    private static List<NbtCompound> blockEntities(BlockArrayClipboard clipboard, BlockBox box, int version) {
        List<NbtCompound> out = new ArrayList<>();
        for (Map.Entry<BlockVector3, NbtCompound> entry : SchematicData.sortedBlockEntities(clipboard)) {
            BlockVector3 pos = entry.getKey();
            if (!box.contains(pos)) {
                continue;
            }
            NbtCompound data = SchematicData.blockEntityData(entry.getValue());
            NbtCompound tag = new NbtCompound();
            if (version >= 3) {
                tag.put("Data", data);
            } else {
                data.entries().forEach(tag::put);
                if (version == 1) {
                    tag.putInt("ContentVersion", 1);
                }
            }
            tag.putIntArray("Pos", new int[]{pos.x() - box.minX(), pos.y() - box.minY(), pos.z() - box.minZ()});
            tag.putString("Id", SchematicData.blockEntityId(entry.getValue(), clipboard, pos));
            out.add(tag);
        }
        return out;
    }

    private static List<NbtCompound> entities(BlockArrayClipboard clipboard, BlockBox box, int version) {
        List<NbtCompound> out = new ArrayList<>();
        for (var entity : clipboard.entities()) {
            NbtCompound data = SchematicData.entityData(entity);
            NbtCompound tag = new NbtCompound();
            if (version >= 3) {
                tag.put("Data", data);
            } else {
                data.entries().forEach(tag::put);
            }
            tag.putList("Pos", SchematicData.relativePosition(entity, box));
            tag.putString("Id", entity.type());
            out.add(tag);
        }
        return out;
    }

    /** True when the NBT is a Sponge schematic, or one of this mod's earlier layouts of it. */
    static boolean isSponge(NbtCompound root) {
        NbtCompound nested = root.getCompoundOrNull("Schematic");
        if (nested != null) {
            return nested.get("Blocks") instanceof NbtCompound
                    || nested.contains("Palette") || nested.contains("Data");
        }
        return root.contains("Palette") && (root.contains("BlockData") || root.contains("Data"));
    }

    /**
     * Reads a Sponge schematic.
     *
     * @param maxVolume the most blocks the schematic may hold, {@code <= 0} for
     *                  no limit; checked before anything is allocated
     */
    static BlockArrayClipboard read(NbtCompound root, String name, long maxVolume) {
        NbtCompound nested = root.getCompoundOrNull("Schematic");
        if (nested != null && nested.get("Blocks") instanceof NbtCompound blocks) {
            return readBody(name, nested, blocks, nested, 3, true, maxVolume);
        }
        if (nested != null) {
            // This mod's earlier v3: dimensions at the root, blocks under Schematic.
            return readBody(name, root, nested, nested, 3, false, maxVolume);
        }
        int version = root.getInt("Version", 2);
        return readBody(name, root, root, root, version, false, maxVolume);
    }

    /**
     * @param sizes      the compound holding Width/Height/Length and Metadata
     * @param blocks     the compound holding the palette, the block data and the block entities
     * @param extras     the compound holding the entities
     * @param nestedData true when block entities and entities keep their data under {@code Data}
     */
    private static BlockArrayClipboard readBody(String name, NbtCompound sizes, NbtCompound blocks,
                                                NbtCompound extras, int version, boolean nestedData,
                                                long maxVolume) {
        int width = SchematicData.side(sizes, "Width");
        int height = SchematicData.side(sizes, "Height");
        int length = SchematicData.side(sizes, "Length");
        if (width <= 0 || height <= 0 || length <= 0) {
            throw new InputException("'" + name + "' has no valid size");
        }
        long volume = (long) width * height * length;
        SchematicData.checkVolume(name, volume, maxVolume);

        int[] states = palette(name, blocks.getCompoundOrNull("Palette"));
        Object payload = blocks.contains("Data") ? blocks.get("Data") : blocks.get("BlockData");
        if (!(payload instanceof byte[] data)) {
            throw new InputException("'" + name + "' has no block data");
        }
        // Spec files hold varints. The v1/v2 files of earlier builds held one
        // short per block; a payload that does not decode to exactly one
        // varint per block, but has two bytes per block, is one of those.
        boolean varints = countVarInts(data, volume);
        if (!varints && (version >= 3 || data.length != volume * 2)) {
            throw new InputException("'" + name + "' is corrupted: its block data does not match its "
                    + width + "x" + height + "x" + length + " size");
        }

        BlockArrayClipboard clipboard = new BlockArrayClipboard(BlockVector3.ZERO);
        clipboard.setName(name);
        int cursor = 0;
        for (int y = 0; y < height; y++) {
            for (int z = 0; z < length; z++) {
                for (int x = 0; x < width; x++) {
                    int index;
                    if (varints) {
                        index = 0;
                        int shift = 0;
                        byte b;
                        do {
                            b = data[cursor++];
                            index |= (b & 0x7F) << shift;
                            shift += 7;
                        } while (b < 0);
                    } else {
                        index = ((data[cursor] & 0xFF) << 8) | (data[cursor + 1] & 0xFF);
                        cursor += 2;
                    }
                    if (index < 0 || index >= states.length || states[index] < 0) {
                        throw new InputException("'" + name + "' is corrupted: a block refers to palette entry "
                                + index + ", which the palette does not have");
                    }
                    clipboard.setBlock(x, y, z, states[index]);
                }
            }
        }

        Object blockEntities = blocks.contains("BlockEntities") ? blocks.get("BlockEntities")
                : blocks.get("TileEntities");
        for (NbtCompound tag : SchematicData.compounds(blockEntities)) {
            int[] pos = SchematicData.intTriple(tag.get("Pos"));
            if (pos == null || pos[0] < 0 || pos[1] < 0 || pos[2] < 0
                    || pos[0] >= width || pos[1] >= height || pos[2] >= length) {
                continue;
            }
            clipboard.addBlockEntity(new BlockVector3(pos[0], pos[1], pos[2]),
                    SchematicData.readBlockEntity(tag, nestedData));
        }
        for (NbtCompound tag : SchematicData.compounds(extras.get("Entities"))) {
            var entity = SchematicData.readEntity(tag, nestedData);
            if (entity != null) {
                clipboard.addEntity(entity);
            }
        }

        NbtCompound metadata = sizes.getCompoundOrNull("Metadata");
        if (metadata != null && metadata.contains("WEOffsetX")) {
            clipboard.setOrigin(new BlockVector3(-metadata.getInt("WEOffsetX", 0),
                    -metadata.getInt("WEOffsetY", 0), -metadata.getInt("WEOffsetZ", 0)));
        } else {
            clipboard.normalize();
        }
        return clipboard;
    }

    /** Palette index to block state, {@code -1} where the palette has no entry. */
    private static int[] palette(String name, NbtCompound palette) {
        if (palette == null || palette.isEmpty()) {
            throw new InputException("'" + name + "' has no block palette");
        }
        BlockStateRegistry registry = BlockState.registry();
        int highest = -1;
        for (String key : palette.keySet()) {
            int index = palette.getInt(key, -1);
            if (index < 0 || index > MAX_PALETTE_INDEX) {
                throw new InputException("'" + name + "' is corrupted: palette entry " + key + " has index " + index);
            }
            highest = Math.max(highest, index);
        }
        int[] states = new int[highest + 1];
        Arrays.fill(states, -1);
        Set<String> unknown = new LinkedHashSet<>();
        for (String key : palette.keySet()) {
            int state = registry.parse(key);
            if (state < 0) {
                unknown.add(key);
                state = registry.air();
            }
            states[palette.getInt(key, 0)] = state;
        }
        if (!unknown.isEmpty()) {
            Log.warn("Schematic '" + name + "' names " + unknown.size() + " block state(s) this game does not have,"
                    + " loaded as air: " + String.join(", ", unknown.stream().limit(5).toList())
                    + (unknown.size() > 5 ? ", ..." : ""));
        }
        return states;
    }

    /**
     * True when the bytes are exactly {@code count} well-formed varints of at
     * most 32 bits each.
     */
    static boolean countVarInts(byte[] data, long count) {
        if (data.length < count) {
            return false;
        }
        long values = 0;
        int bytesInValue = 0;
        for (byte b : data) {
            if (++bytesInValue > 5) {
                return false;
            }
            if (b >= 0) {
                values++;
                bytesInValue = 0;
            }
        }
        return bytesInValue == 0 && values == count;
    }

    /**
     * The block data as it is written: varints in an array sized for one byte
     * per block, which is exact while the palette has at most 128 entries.
     */
    private static final class VarIntBuffer {

        private byte[] bytes;
        private int size;

        VarIntBuffer(int expected) {
            bytes = new byte[Math.max(32, expected)];
        }

        void write(int value) {
            if (bytes.length - size < 5) {
                grow();
            }
            while ((value & ~0x7F) != 0) {
                bytes[size++] = (byte) ((value & 0x7F) | 0x80);
                value >>>= 7;
            }
            bytes[size++] = (byte) value;
        }

        private void grow() {
            long target = Math.min(bytes.length * 2L + 5, Integer.MAX_VALUE - 8);
            if (target - size < 5) {
                throw new InputException("The clipboard's block data is larger than one schematic file can store");
            }
            bytes = Arrays.copyOf(bytes, (int) target);
        }

        byte[] toByteArray() {
            return size == bytes.length ? bytes : Arrays.copyOf(bytes, size);
        }
    }
}
