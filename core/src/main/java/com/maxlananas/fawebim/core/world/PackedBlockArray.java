package com.maxlananas.fawebim.core.world;

import java.util.Arrays;

/**
 * A palette + bit-packed array of 4096 block state ids (one 16x16x16 section).
 *
 * <p>This is FAWE's core memory optimisation: instead of 16 KB per section
 * (one {@code int} per block) a section costs as little as
 * {@code 4096 * bits} bits, where {@code bits} grows with the palette size.
 * Values never straddle two longs, which keeps {@link #get}/{@link #set} on the
 * hot path branch-predictable.</p>
 */
public final class PackedBlockArray {

    public static final int VOLUME = 4096;


    private int[] palette;
    private int paletteSize;
    /**
     * Palette index of every state in the palette, as an open-addressed table of
     * {@code stateId + 1} (0 marks a free slot). Looking a state up by walking
     * the palette was linear, and a build with a few hundred distinct states made
     * every single block write a few hundred comparisons.
     */
    private int[] indexKeys;
    private int[] indexValues;
    private int indexMask;
    private int bitsPerBlock;
    private long[] data;
    /**
     * One bit per cell, telling whether this buffer holds a value for it. The
     * chunk buffer used a hash set of packed positions for the same question,
     * which cost a hash per write and per read on the flush path; inside a 16^3
     * section the answer is a bit.
     */
    private final long[] written = new long[VOLUME / 64];
    private int valuesPerLong;
    private long mask;
    private boolean uniform;
    /**
     * The state and palette index of the previous write. A buffer is filled a
     * run at a time - the blocks of one row, the cells of one clipboard column -
     * and a run repeats its state, so the one-entry cache answers most writes
     * without touching the palette table at all.
     */
    private int lastState = -1;
    private int lastStateIndex;

    public PackedBlockArray(int initialBits) {
        this.bitsPerBlock = clampBits(initialBits);
        this.palette = new int[1 << Math.min(12, this.bitsPerBlock)];
        this.paletteSize = 0;
        rehashIndex(16);
        reallocate();
    }

    private static int clampBits(int bits) {
        return Math.max(1, Math.min(16, bits));
    }

    private void reallocate() {
        this.valuesPerLong = 64 / bitsPerBlock;
        this.mask = (1L << bitsPerBlock) - 1;
        int longCount = (VOLUME + valuesPerLong - 1) / valuesPerLong;
        this.data = new long[longCount];
    }

    public void fill(int stateId) {
        Arrays.fill(data, 0L);
        Arrays.fill(written, 0L);
        palette = new int[1];
        palette[0] = stateId;
        paletteSize = 1;
        Arrays.fill(indexKeys, 0);
        indexInsert(stateId, 0);
        bitsPerBlock = 1;
        reallocate();
        lastState = stateId;
        lastStateIndex = 0;
        uniform = true;
    }
    public int get(int index) {
        if (uniform) {
            return palette[0];
        }
        int slot = index / valuesPerLong;
        int offset = (index - slot * valuesPerLong) * bitsPerBlock;
        int value = (int) ((data[slot] >>> offset) & mask);
        return palette[value];
    }

    public void set(int index, int stateId) {
        if (uniform && palette[0] == stateId) {
            return;
        }
        int paletteIndex = paletteIndex(stateId);
        int slot = index / valuesPerLong;
        int offset = (index - slot * valuesPerLong) * bitsPerBlock;
        long clearMask = ~(mask << offset);
        data[slot] = (data[slot] & clearMask) | ((long) paletteIndex << offset);
        uniform = false;
    }

    /**
     * Writes a cell and reports what it did: {@code -1} when the cell already
     * held this state, {@code 0} when it was empty, {@code 1} when it held
     * something else.
     *
     * <p>A chunk buffer write is exactly this question: the caller has to know
     * whether the cell was empty, because that is what makes the block count and
     * the dirty flag, and whether the value changed, because an unchanged write
     * must not be recorded. Asking it here means the index arithmetic, the
     * written bit and the palette probe happen once per block instead of once
     * per question.</p>
     */
    public int put(int index, int stateId) {
        int word = index >>> 6;
        long bit = 1L << (index & 63);
        boolean wasWritten = (written[word] & bit) != 0;
        if (uniform && palette[0] == stateId) {
            if (wasWritten) {
                return -1;
            }
            written[word] |= bit;
            return 0;
        }
        int slot = index / valuesPerLong;
        int offset = (index - slot * valuesPerLong) * bitsPerBlock;
        if (wasWritten && palette[(int) ((data[slot] >>> offset) & mask)] == stateId) {
            return -1;
        }
        int paletteIndex = paletteIndex(stateId);
        data[slot] = (data[slot] & ~(mask << offset)) | ((long) paletteIndex << offset);
        uniform = false;
        if (wasWritten) {
            return 1;
        }
        written[word] |= bit;
        return 0;
    }

    /** The palette index of a state, adding it - and growing the cells - when new. */
    private int paletteIndex(int stateId) {
        if (stateId == lastState) {
            return lastStateIndex;
        }
        int index = paletteLookup(stateId);
        if (index < 0) {
            index = paletteAdd(stateId);
            if (index >= (1 << bitsPerBlock)) {
                growBits();
            }
        }
        lastState = stateId;
        lastStateIndex = index;
        return index;
    }

    /** True when this cell was written into the buffer. */
    public boolean isWritten(int index) {
        return (written[index >>> 6] & (1L << (index & 63))) != 0;
    }

    /** Marks a cell as written; the caller knows it was not written before. */
    public void markWritten(int index) {
        written[index >>> 6] |= 1L << (index & 63);
    }

    /** Receives the index of a cell the buffer holds. */
    @FunctionalInterface
    public interface IndexVisitor {

        void visit(int index);
    }

    /**
     * Walks the cells this buffer holds, in index order.
     *
     * <p>A flush writes the cells that changed, not the whole section, and most
     * sections of an edit are partly filled, so walking the bits costs a fraction
     * of walking 4096 cells and asking each one.</p>
     *
     * @return how many cells were visited
     */
    public int forEachWritten(IndexVisitor visitor) {
        int visited = 0;
        for (int word = 0; word < written.length; word++) {
            long bits = written[word];
            while (bits != 0L) {
                int bit = Long.numberOfTrailingZeros(bits);
                bits &= bits - 1;
                visitor.visit((word << 6) | bit);
                visited++;
            }
        }
        return visited;
    }

    private int paletteLookup(int stateId) {
        int key = stateId + 1;
        int slot = spread(stateId) & indexMask;
        while (true) {
            int candidate = indexKeys[slot];
            if (candidate == 0) {
                return -1;
            }
            if (candidate == key) {
                return indexValues[slot];
            }
            slot = (slot + 1) & indexMask;
        }
    }

    private int paletteAdd(int stateId) {
        if (paletteSize == palette.length) {
            palette = Arrays.copyOf(palette, Math.min(1 << 12, palette.length << 1));
        }
        palette[paletteSize] = stateId;
        int index = paletteSize++;
        if (paletteSize * 2 >= indexKeys.length) {
            rehashIndex(paletteSize * 2);
        } else {
            indexInsert(stateId, index);
        }
        return index;
    }

    /** A power-of-two state table of at least {@code capacity} slots. */
    private void rehashIndex(int capacity) {
        int size = Integer.highestOneBit(Math.max(16, capacity - 1)) << 1;
        indexKeys = new int[size];
        indexValues = new int[size];
        indexMask = size - 1;
        for (int i = 0; i < paletteSize; i++) {
            indexInsert(palette[i], i);
        }
    }

    private void indexInsert(int stateId, int index) {
        int slot = spread(stateId) & indexMask;
        while (indexKeys[slot] != 0) {
            slot = (slot + 1) & indexMask;
        }
        indexKeys[slot] = stateId + 1;
        indexValues[slot] = index;
    }

    private static int spread(int stateId) {
        return stateId * 0x9E3779B1;
    }

    private void growBits() {
        int newBits = clampBits(bitsPerBlock + 1);
        if (newBits == bitsPerBlock) {
            // Palette exhausted at 16 bits; force a dense representation.
            newBits = 16;
        }
        int oldValuesPerLong = valuesPerLong;
        long oldMask = mask;
        long[] oldData = data;
        int oldBits = bitsPerBlock;
        bitsPerBlock = newBits;
        reallocate();
        for (int i = 0; i < VOLUME; i++) {
            int slot = i / oldValuesPerLong;
            int offset = (i - slot * oldValuesPerLong) * oldBits;
            int value = (int) ((oldData[slot] >>> offset) & oldMask);
            int newSlot = i / valuesPerLong;
            int newOffset = (i - newSlot * valuesPerLong) * bitsPerBlock;
            data[newSlot] |= (long) value << newOffset;
        }
    }

}
