package com.maxlananas.fawebim.core.world;

import java.util.ArrayList;
import java.util.List;

/**
 * The per-chunk write buffer used by the bulk edit queue.
 *
 * <p>Edits are collected here (packed sections, biomes, entities) instead of
 * being pushed into the world one block at a time; the platform then applies
 * the whole buffer at once. This is FAWE's "chunk queue" and it is what makes
 * {@code //set} on a 100x100x100 region a single chunk-level write per chunk
 * instead of 1M block updates.</p>
 */
public final class ChunkSet {

    private final int chunkX;
    private final int chunkZ;
    private final int minSection;
    private final int sectionCount;

    private PackedBlockArray[] sections;
    private int[][] biomes;
    private List<EntityData> entities;
    private int changedCount;
    private boolean dirty;

    public ChunkSet(int chunkX, int chunkZ, int minY, int maxY) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.minSection = minY >> 4;
        this.sectionCount = ((maxY >> 4) - minSection) + 1;
        this.sections = new PackedBlockArray[sectionCount];
    }

    public int chunkX() {
        return chunkX;
    }

    public int chunkZ() {
        return chunkZ;
    }

    public int minSection() {
        return minSection;
    }

    public int sectionCount() {
        return sectionCount;
    }

    public PackedBlockArray[] sections() {
        return sections;
    }

    /**
     * True when nothing was written at all. Note that a buffer holding only
     * "set to air" changes is not empty: those writes must still reach the
     * world (they are how {@code //undo} removes blocks).
     */
    public boolean isEmpty() {
        return !dirty && !hasBiomes() && !hasEntities();
    }

    /** How many blocks this buffer holds, air included. */
    public int size() {
        return changedCount;
    }

    /** Receives a position this buffer holds a value for. */
    @FunctionalInterface
    public interface BlockVisitor {

        void visit(int x, int y, int z);
    }

    /**
     * Walks the positions this buffer holds, section by section, in index order,
     * without building a position object per block.
     */
    public void forEachChanged(BlockVisitor visitor) {
        for (int section = 0; section < sections.length; section++) {
            PackedBlockArray packed = sections[section];
            if (packed == null) {
                continue;
            }
            int baseX = chunkX << 4;
            int baseZ = chunkZ << 4;
            int baseY = (minSection + section) << 4;
            packed.forEachWritten(index -> visitor.visit(baseX + (index & 15),
                    baseY + ((index >> 8) & 15), baseZ + ((index >> 4) & 15)));
        }
    }

    public List<EntityData> entities() {
        if (entities == null) {
            entities = new ArrayList<>();
        }
        return entities;
    }

    public boolean hasEntities() {
        return entities != null && !entities.isEmpty();
    }

    public int[][] biomes() {
        return biomes;
    }

    public boolean hasBiomes() {
        return biomes != null;
    }

    private static int localX(int x) {
        return x & 15;
    }

    private static int localZ(int z) {
        return z & 15;
    }

    private static int localY(int y) {
        return y & 15;
    }

    private static int index(int x, int y, int z) {
        return (localY(y) << 8) | (localZ(z) << 4) | localX(x);
    }

    /** True when this position holds a value in the buffer (air included). */
    public boolean isSet(int x, int y, int z) {
        PackedBlockArray section = sectionFor(y, false);
        return section != null && section.isWritten(index(x, y, z));
    }

    /**
     * The buffered value, or {@code -1} when this position was never written.
     * The distinction matters: writing air must be recorded, otherwise an undo
     * could not restore "there was nothing here".
     */
    public int getBlock(int x, int y, int z) {
        PackedBlockArray section = sectionFor(y, false);
        int index = index(x, y, z);
        return section == null || !section.isWritten(index) ? -1 : section.get(index);
    }

    /** Sets the block straight into the buffer; returns true when the value changed. */
    public boolean set(int x, int y, int z, int stateId, int airId) {
        PackedBlockArray section = sectionFor(y, true);
        if (section == null) {
            return false;
        }
        int index = index(x, y, z);
        boolean wasSet = section.isWritten(index);
        if (wasSet && section.get(index) == stateId) {
            return false;
        }
        section.set(index, stateId);
        if (!wasSet) {
            section.markWritten(index);
            changedCount++;
        }
        dirty = true;
        return true;
    }

    /** Biome at the position, or -1 when the buffer holds nothing for it. */
    public int getBiome(int x, int y, int z) {
        if (biomes == null) {
            return -1;
        }
        int si = (y >> 4) - minSection;
        if (si < 0 || si >= sectionCount || biomes[si] == null) {
            return -1;
        }
        int qi = (((y >> 2) & 3) << 4) | (((z >> 2) & 3) << 2) | ((x >> 2) & 3);
        return biomes[si][qi];
    }

    public void setBiome(int x, int y, int z, int biomeId, int minY) {
        dirty = true;
        if (biomes == null) {
            biomes = new int[sectionCount][];
        }
        int si = (y >> 4) - minSection;
        if (si < 0 || si >= sectionCount) {
            return;
        }
        if (biomes[si] == null) {
            biomes[si] = new int[64];
            java.util.Arrays.fill(biomes[si], -1);
        }
        int qi = (((y >> 2) & 3) << 4) | (((z >> 2) & 3) << 2) | ((x >> 2) & 3);
        biomes[si][qi] = biomeId;
    }

    private PackedBlockArray sectionFor(int y, boolean create) {
        int si = (y >> 4) - minSection;
        if (si < 0 || si >= sectionCount) {
            return null;
        }
        PackedBlockArray section = sections[si];
        if (section == null && create) {
            section = new PackedBlockArray(4);
            sections[si] = section;
        }
        return section;
    }
}
