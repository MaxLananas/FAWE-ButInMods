package com.fawebutinmods.core.clipboard;

import com.fawebutinmods.core.math.BlockBox;
import com.fawebutinmods.core.math.BlockVector3;
import com.fawebutinmods.core.world.BlockStateRegistry;
import com.fawebutinmods.core.world.EntityData;
import com.fawebutinmods.core.world.Extent;

import java.util.ArrayList;
import java.util.List;

/**
 * A dense block array clipboard: this is what {@code //copy} produces and what
 * {@code //paste}, {@code /brush clipboard} and the schematic writers consume.
 *
 * <p>Blocks are stored in a palette-packed structure per section, which is the
 * same layout FAWE uses so that large copies stay small in memory.</p>
 */
public final class BlockArrayClipboard implements Extent {

    private final BlockBox box = new BlockBox();
    private BlockVector3 origin;
    private final List<EntityData> entities = new ArrayList<>();
    private String name = "";
    private final java.util.Map<Long, int[]> sections = new java.util.HashMap<>();
    private final java.util.Map<BlockVector3, com.fawebutinmods.core.util.NbtCompound> blockEntities =
            new java.util.LinkedHashMap<>();
    private final java.util.Map<Long, int[]> paletteCache = new java.util.HashMap<>();
    private int minY = Integer.MAX_VALUE;
    private int maxY = Integer.MIN_VALUE;

    public BlockArrayClipboard(BlockVector3 origin) {
        this.origin = origin;
        box.set(origin.x(), origin.y(), origin.z(), origin.x(), origin.y(), origin.z());
    }

    public BlockVector3 getOrigin() {
        return origin;
    }

    public void setOrigin(BlockVector3 origin) {
        this.origin = origin;
    }

    public BlockBox getBox() {
        return box;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<EntityData> getEntitiesCopy() {
        List<EntityData> copy = new ArrayList<>(entities.size());
        for (EntityData entity : entities) {
            copy.add(entity.clone());
        }
        return copy;
    }

    public List<EntityData> entities() {
        return entities;
    }

    public void addEntity(EntityData data) {
        entities.add(data);
    }

    /** Block entity (chest contents, signs, ...) copied along with the blocks. */
    public void addBlockEntity(BlockVector3 position, com.fawebutinmods.core.util.NbtCompound nbt) {
        blockEntities.put(position, nbt);
    }

    public com.fawebutinmods.core.util.NbtCompound getBlockEntity(BlockVector3 position) {
        return blockEntities.get(position);
    }

    public java.util.Map<BlockVector3, com.fawebutinmods.core.util.NbtCompound> blockEntities() {
        return blockEntities;
    }

    public static long sectionKey(int x, int y, int z) {
        return ((long) (x >> 4) << 40) ^ ((long) (y >> 4) << 20) ^ (z >> 4);
    }

    @Override
    public int getBlock(int x, int y, int z) {
        if (!box.contains(x, y, z)) {
            return 0;
        }
        int[] section = sections.get(sectionKey(x, y, z));
        if (section == null) {
            return 0;
        }
        return section[((y & 15) << 8) | ((z & 15) << 4) | (x & 15)];
    }

    public int getBlock(BlockVector3 position) {
        return getBlock(position.x(), position.y(), position.z());
    }

    @Override
    public boolean setBlock(int x, int y, int z, int stateId) {
        int[] section = sections.computeIfAbsent(sectionKey(x, y, z), k -> new int[4096]);
        section[((y & 15) << 8) | ((z & 15) << 4) | (x & 15)] = stateId;
        box.set(
                Math.min(box.minX(), x), Math.min(box.minY(), y), Math.min(box.minZ(), z),
                Math.max(box.maxX(), x), Math.max(box.maxY(), y), Math.max(box.maxZ(), z));
        minY = Math.min(minY, y);
        maxY = Math.max(maxY, y);
        return true;
    }

    @Override
    public int minY() {
        return minY == Integer.MAX_VALUE ? 0 : minY;
    }

    @Override
    public int maxY() {
        return maxY == Integer.MIN_VALUE ? 255 : maxY;
    }

    public int getWidth() {
        return box.width();
    }

    public int getHeight() {
        return box.height();
    }

    public int getLength() {
        return box.length();
    }

    public long volume() {
        return box.volume();
    }

    /** Iterates the clipboard in the region's natural order. */
    public Iterable<BlockVector3> positions() {
        List<BlockVector3> positions = new ArrayList<>();
        for (int y = box.minY(); y <= box.maxY(); y++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                for (int x = box.minX(); x <= box.maxX(); x++) {
                    positions.add(new BlockVector3(x, y, z));
                }
            }
        }
        return positions;
    }

    /** True when the clipboard contains no non-air blocks (nothing to paste). */
    public boolean isEmpty(BlockStateRegistry registry) {
        for (int[] section : sections.values()) {
            for (int state : section) {
                if (!registry.isAirLike(state)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Shifts the clipboard so that its origin becomes the minimum corner. */
    public void normalize() {
        origin = new BlockVector3(box.minX(), box.minY(), box.minZ());
    }
}
