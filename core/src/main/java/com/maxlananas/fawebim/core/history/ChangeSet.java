package com.maxlananas.fawebim.core.history;

import com.maxlananas.fawebim.core.math.BlockVector3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The recorded changes of one chunk section.
 *
 * <p>FAWE stores undo data as primitive arrays of indices / previous states /
 * new states, so that undoing a 1M block edit costs a few megabytes instead of a
 * list of boxed objects. The three values a row needs share one array here: a
 * recorded block then lands in one cache line instead of three, which is what a
 * history of millions of changes spends its time on, and the array grows once
 * per step instead of three times.</p>
 */
public final class ChangeSet {

    /** A section holds this many blocks; a change set never needs more rows. */
    private static final int VOLUME = com.maxlananas.fawebim.core.world.PackedBlockArray.VOLUME;
    /** One row: the cell, the state before it, the state after it. */
    private static final int ROW = 3;

    private static final int CELL = 0;
    private static final int BEFORE = 1;
    private static final int AFTER = 2;

    private int[] rows = new int[ROW * 64];
    private int size;
    private final int chunkX;
    private final int chunkZ;
    private final int sectionY;

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

    public void add(int x, int y, int z, int previous, int current) {
        if (size * ROW == rows.length) {
            // Growing in bigger steps than doubling: a region edit fills most of
            // the sections it touches, and the copying of a doubling run was a
            // measurable share of the edit. A section holds VOLUME blocks and an
            // edit usually writes each of them once, so the last step before that
            // is reached takes the set to exactly one section; rows beyond it only
            // exist when a cell is written twice, and doubling keeps the waste of
            // those rare rows bounded.
            int newSize = size < VOLUME ? Math.max(64, size * 8) : size * 2;
            rows = Arrays.copyOf(rows, newSize * ROW);
        }
        int at = size * ROW;
        rows[at + CELL] = (y & 15) << 8 | (z & 15) << 4 | (x & 15);
        rows[at + BEFORE] = previous;
        rows[at + AFTER] = current;
        size++;
    }

    /** The 12-bit cell of a row, as {@code y << 8 | z << 4 | x}. */
    public int cellAt(int row) {
        return rows[row * ROW + CELL];
    }

    /** The state the cell held before the edit. */
    public int beforeAt(int row) {
        return rows[row * ROW + BEFORE];
    }

    /** The state the cell held after the edit. */
    public int afterAt(int row) {
        return rows[row * ROW + AFTER];
    }

    /** The cells of the rows, as the snapshot file writes them. */
    public int[] cells() {
        return column(CELL);
    }

    /** The previous states of the rows, as the snapshot file writes them. */
    public int[] beforeStates() {
        return column(BEFORE);
    }

    /** The new states of the rows, as the snapshot file writes them. */
    public int[] afterStates() {
        return column(AFTER);
    }

    private int[] column(int offset) {
        int[] out = new int[size];
        for (int row = 0; row < size; row++) {
            out[row] = rows[row * ROW + offset];
        }
        return out;
    }

    /**
     * The layers this set reaches, from the rows it recorded.
     *
     * <p>Kept out of {@link #add}: a history filter asks this once, the write path
     * would have paid two branches for it on every block.</p>
     */
    public int[] yRange() {
        int baseY = sectionY << 4;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (int row = 0; row < size; row++) {
            int y = baseY + ((rows[row * ROW + CELL] >> 8) & 15);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
        }
        return new int[]{minY, maxY};
    }

    public List<BlockVector3> positions() {
        List<BlockVector3> out = new ArrayList<>(size);
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        int baseY = sectionY << 4;
        for (int row = 0; row < size; row++) {
            int cell = rows[row * ROW + CELL];
            out.add(new BlockVector3(
                    baseX + (cell & 15),
                    baseY + ((cell >> 8) & 15),
                    baseZ + ((cell >> 4) & 15)));
        }
        return out;
    }
}
