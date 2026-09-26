package com.maxlananas.fawebim.core.util;

/**
 * Working buffers whose size comes from the command line.
 *
 * <p>An operation that reads a whole area before it writes it, such as
 * {@code //deform} or a morph brush, holds a state per block of that area, and
 * the area is a selection or a brush radius the player typed. Past a quarter
 * of the heap the request is refused before anything is read, rather than
 * failing with an {@link OutOfMemoryError} in the middle of a tick.</p>
 */
public final class Buffers {

    private Buffers() {
    }

    /** The memory one edit may hold in working buffers: a quarter of the heap. */
    public static long budget() {
        return Math.max(16L << 20, Runtime.getRuntime().maxMemory() / 4);
    }

    /**
     * Checks that {@code arrays} arrays of {@code cells} ints fit in the budget.
     *
     * @param what the operation, as the start of the sentence the player reads
     * @throws InputException when they do not
     */
    public static void checkInts(long cells, int arrays, String what) {
        long budget = budget();
        if (cells < 0 || cells > Integer.MAX_VALUE - 8 || cells * Integer.BYTES * arrays > budget) {
            throw new InputException(what + " would hold " + Msg.formatNumber(cells)
                    + " blocks in memory, more than the " + (budget >> 20) + " MiB an edit may use");
        }
    }
}
