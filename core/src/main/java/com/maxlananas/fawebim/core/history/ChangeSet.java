package com.maxlananas.fawebim.core.history;

import com.maxlananas.fawebim.core.math.BlockVector3;

import java.util.ArrayList;
import java.util.List;

/**
 * The recorded changes of one chunk section.
 *
 * <p>FAWE stores undo data exactly like this: parallel primitive arrays of
 * indices / previous states / new states, so that undoing a 1M block edit costs
 * a few megabytes instead of a list of boxed objects.</p>
 */
public final class ChangeSet {

    private int[] indices = new int[64];
    private int[] before = new int[64];
    private int[] after = new int[64];
    private int size;
    private final int chunkX;
    private final int chunkZ;
    private final int sectionY;
    private int minY = Integer.MAX_VALUE;
    private int maxY = Integer.MIN_VALUE;

    public ChangeSet(int chunkX, int chunkZ, int sectionY) {
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.sectionY = sectionY;
    }

    public int chunkX() {
        return chunkX;
    }

    public int chunkZ() {
        return chunkZ;
    }

    public int sectionY() {
        return sectionY;
    }

    public int size() {
        return size;
    }

    public int[] indices() {
        return indices;
    }

    public int[] before() {
        return before;
    }

    public int[] after() {
        return after;
    }

    public void add(int x, int y, int z, int previous, int current) {
        if (size == indices.length) {
            int newLength = indices.length * 2;
            indices = java.util.Arrays.copyOf(indices, newLength);
            before = java.util.Arrays.copyOf(before, newLength);
            after = java.util.Arrays.copyOf(after, newLength);
        }
        int localX = x & 15;
        int localY = y & 15;
        int localZ = z & 15;
        indices[size] = (localY << 8) | (localZ << 4) | localX;
        before[size] = previous;
        after[size] = current;
        size++;
        minY = Math.min(minY, y);
        maxY = Math.max(maxY, y);
    }

    public int minY() {
        return minY;
    }

    public int maxY() {
        return maxY;
    }

    public List<BlockVector3> positions() {
        List<BlockVector3> out = new ArrayList<>(size);
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        int baseY = sectionY << 4;
        for (int i = 0; i < size; i++) {
            int index = indices[i];
            out.add(new BlockVector3(
                    baseX + (index & 15),
                    baseY + ((index >> 8) & 15),
                    baseZ + ((index >> 4) & 15)));
        }
        return out;
    }
}
