package com.maxlananas.fawebim.core.clipboard;

import com.maxlananas.fawebim.core.math.BlockBox;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.world.BlockStateRegistry;
import com.maxlananas.fawebim.core.world.EntityData;
import com.maxlananas.fawebim.core.world.Extent;
import com.maxlananas.fawebim.core.world.World;

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
    private final java.util.Map<BlockVector3, com.maxlananas.fawebim.core.util.NbtCompound> blockEntities =
            new java.util.LinkedHashMap<>();
    private int minY = Integer.MAX_VALUE;
    private int maxY = Integer.MIN_VALUE;
    private World lazyWorld;

    public BlockArrayClipboard(BlockVector3 origin) {
        this.origin = origin;
        box.set(origin.x(), origin.y(), origin.z(), origin.x(), origin.y(), origin.z());
    }

    /**
     * A clipboard that keeps the region instead of its blocks, like FAWE's
     * {@code //lazycopy}: nothing is read until the clipboard is pasted, so
     * copying a huge selection costs no memory.
     */
    public static BlockArrayClipboard lazy(World world, com.maxlananas.fawebim.core.region.Region region, String name) {
        BlockArrayClipboard clipboard = new BlockArrayClipboard(region.getMinimumPoint());
        clipboard.box.set(region.getMinimumPoint().x(), region.getMinimumPoint().y(), region.getMinimumPoint().z(),
                region.getMaximumPoint().x(), region.getMaximumPoint().y(), region.getMaximumPoint().z());
        clipboard.minY = region.getMinimumPoint().y();
        clipboard.maxY = region.getMaximumPoint().y();
        clipboard.lazyWorld = world;
        clipboard.name = name;
        return clipboard;
    }

    /** True when the blocks are still in the world rather than in this clipboard. */
    public boolean isLazy() {
        return lazyWorld != null;
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
    public void addBlockEntity(BlockVector3 position, com.maxlananas.fawebim.core.util.NbtCompound nbt) {
        blockEntities.put(position, nbt);
    }

    public com.maxlananas.fawebim.core.util.NbtCompound getBlockEntity(BlockVector3 position) {
        return blockEntities.get(position);
    }

    public java.util.Map<BlockVector3, com.maxlananas.fawebim.core.util.NbtCompound> blockEntities() {
        return blockEntities;
    }

    // ------------------------------------------------------------------ biomes

    private final java.util.Map<Long, Integer> biomes = new java.util.HashMap<>();

    /** Stores the biome of a position, filled by {@code //copy -b}. */
    @Override
    public boolean setBiome(int x, int y, int z, int biomeId) {
        biomes.put(positionKey(x, y, z), biomeId);
        return true;
    }

    /** The stored biome of a position, or {@code -1} when there is none. */
    public int getBiome(int x, int y, int z) {
        return biomes.getOrDefault(positionKey(x, y, z), -1);
    }

    public boolean hasBiomes() {
        return !biomes.isEmpty();
    }

    /** Every stored biome, keyed by {@link #positionKey(int, int, int)}. */
    public java.util.Set<java.util.Map.Entry<Long, Integer>> biomeEntries() {
        return biomes.entrySet();
    }

    /** Packs the coordinates of a biome cell the way Minecraft packs a block position. */
    public static long positionKey(int x, int y, int z) {
        return ((long) x & 0x3FFFFFF) << 38 | ((long) y & 0xFFF) << 26 | ((long) z & 0x3FFFFFF);
    }

    /** Unpacks the X coordinate of a {@link #positionKey(int, int, int)}. */
    public static int keyX(long key) {
        return (int) (key << 0 >> 38);
    }

    /** Unpacks the Y coordinate of a {@link #positionKey(int, int, int)}. */
    public static int keyY(long key) {
        return (int) (key << 26 >> 52);
    }

    /** Unpacks the Z coordinate of a {@link #positionKey(int, int, int)}. */
    public static int keyZ(long key) {
        return (int) (key << 38 >> 38);
    }

    public static long sectionKey(int x, int y, int z) {
        return ((long) (x >> 4) << 40) ^ ((long) (y >> 4) << 20) ^ (z >> 4);
    }

    @Override
    public int getBlock(int x, int y, int z) {
        if (!box.contains(x, y, z)) {
            return 0;
        }
        if (lazyWorld != null) {
            return lazyWorld.getBlock(x, y, z);
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
