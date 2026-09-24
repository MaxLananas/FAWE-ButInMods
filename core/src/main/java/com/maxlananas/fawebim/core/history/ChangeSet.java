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
 * list of boxed objects. The values a row needs share one array here: a recorded
 * block then lands in one cache line instead of three, which is what a history of
 * millions of changes spends its time on, and the array grows once per step
 * instead of three times.</p>
 *
 * <p>A set whose changes all write the same state - the case of a fill - keeps
 * that state once and drops it from the rows. A change that writes something else
 * widens the rows to carry a state each, once.</p>
 */
public final class ChangeSet {

    /** A section holds this many blocks; a change set never needs more rows. */
    private static final int VOLUME = com.maxlananas.fawebim.core.world.PackedBlockArray.VOLUME;
    /** One row: the cell, the state before it, the state after it. */
    private static final int ROW = 3;
    /** One row while every change of the set writes the same state: no "after". */
    private static final int PAIR = 2;

    private static final int CELL = 0;
    private static final int BEFORE = 1;
    private static final int AFTER = 2;

    private int[] rows = new int[PAIR * 64];
    private int size;
    /** The ints one row takes; two until a change writes a state of its own. */
    private int stride = PAIR;
    /**
     * True while every change recorded here writes the same state, which is what
     * a {@code //set}, a {@code //replace} or a brush that fills does. The state
     * is then kept once for the whole set and a row only carries the cell and the
     * state the cell held, so the history of a filled region costs a third fewer
     * bytes - and the bytes are what a history of millions of changes spends.
     */
    private boolean uniform = true;
    private int uniformAfter;
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
        if (uniform && current != uniformAfter) {
            if (size == 0) {
                uniformAfter = current;
            } else {
                widen();
            }
        }
        if ((long) (size + 1) * stride > rows.length) {
            grow();
        }
        int at = size * stride;
        rows[at + CELL] = (y & 15) << 8 | (z & 15) << 4 | (x & 15);
        rows[at + BEFORE] = previous;
        if (!uniform) {
            rows[at + AFTER] = current;
        }
        size++;
    }

    /**
     * Gives every row a state of its own, in one pass.
     *
     * <p>The rows recorded so far all wrote the same state, so that state is
     * their "after" value; from here on a row carries its own.</p>
     */
    private void widen() {
        int[] wide = new int[rows.length / PAIR * ROW];
        for (int row = 0; row < size; row++) {
            wide[row * ROW + CELL] = rows[row * PAIR + CELL];
            wide[row * ROW + BEFORE] = rows[row * PAIR + BEFORE];
            wide[row * ROW + AFTER] = uniformAfter;
        }
        rows = wide;
        stride = ROW;
        uniform = false;
    }

    private void grow() {
        // Growing in bigger steps than doubling: a region edit fills most of the
        // sections it touches, and the copying of a doubling run was a measurable
        // share of the edit. A section holds VOLUME blocks and an edit usually
        // writes each of them once, so the last step before that is reached takes
        // the set to exactly one section; rows beyond it only exist when a cell is
        // written twice, and doubling keeps the waste of those rare rows bounded.
        int newSize = size < VOLUME ? Math.max(64, size * 8) : size * 2;
        rows = Arrays.copyOf(rows, newSize * stride);
    }

    /** The 12-bit cell of a row, as {@code y << 8 | z << 4 | x}. */
    public int cellAt(int row) {
        return rows[row * stride + CELL];
    }

    /** The state the cell held before the edit. */
    public int beforeAt(int row) {
        return rows[row * stride + BEFORE];
    }

    /** The state the cell held after the edit. */
    public int afterAt(int row) {
        return uniform ? uniformAfter : rows[row * stride + AFTER];
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
            out[row] = offset == CELL ? cellAt(row) : offset == BEFORE ? beforeAt(row) : afterAt(row);
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
            int y = baseY + ((rows[row * stride + CELL] >> 8) & 15);
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
            int cell = rows[row * stride + CELL];
            out.add(new BlockVector3(
                    baseX + (cell & 15),
                    baseY + ((cell >> 8) & 15),
                    baseZ + ((cell >> 4) & 15)));
        }
        return out;
    }
}
