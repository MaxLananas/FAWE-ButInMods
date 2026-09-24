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
    /**
     * The reciprocal of {@link #valuesPerLong}, so the slot of a cell is a
     * multiply and a shift instead of a division. The divisor is a field and the
     * JIT cannot turn it into a multiplication on its own, which the profile of a
     * region edit showed as a share of every write. It is exact for the widths a
     * section uses: the divisor is at most 64 and a cell index is below 4096, so
     * the error of the reciprocal stays under one slot.
     */
    private long valuesMagic;
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
        this.valuesMagic = ((1L << 32) + valuesPerLong - 1) / valuesPerLong;
        this.mask = (1L << bitsPerBlock) - 1;
        int longCount = (VOLUME + valuesPerLong - 1) / valuesPerLong;
        this.data = new long[longCount];
    }

    /** The long a cell lives in. */
    private int slotOf(int index) {
        return (int) ((index * valuesMagic) >>> 32);
    }

    public int get(int index) {
        int slot = slotOf(index);
        int offset = (index - slot * valuesPerLong) * bitsPerBlock;
        int value = (int) ((data[slot] >>> offset) & mask);
        return palette[value];
    }

    public void set(int index, int stateId) {
        int paletteIndex = stateId == lastState ? lastStateIndex : paletteIndex(stateId);
        int slot = slotOf(index);
        int offset = (index - slot * valuesPerLong) * bitsPerBlock;
        long clearMask = ~(mask << offset);
        data[slot] = (data[slot] & clearMask) | ((long) paletteIndex << offset);
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
        // The palette comes first: adding a state can widen the cells, and a
        // cell's position in the array depends on that width. A state that is
        // not in the palette is in no cell either, so the check below cannot
        // miss anything by running after it. The cached state cannot widen
        // anything, so the common write reads the entry here instead of calling
        // for it: a buffer is filled a run at a time and a run repeats its
        // state, which made the call itself the share the profile showed.
        int paletteIndex = stateId == lastState ? lastStateIndex : paletteIndex(stateId);
        int slot = slotOf(index);
        int offset = (index - slot * valuesPerLong) * bitsPerBlock;
        if (wasWritten && palette[(int) ((data[slot] >>> offset) & mask)] == stateId) {
            return -1;
        }
        data[slot] = (data[slot] & ~(mask << offset)) | ((long) paletteIndex << offset);
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
        return lookupOrAdd(stateId);
    }

    private int lookupOrAdd(int stateId) {
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
        // The width the palette needs, and never a single step: a section that
        // holds more than the two states one bit carries is a section that is
        // likely to hold a few more, and every step remaps all 4096 cells.
        int needed = 32 - Integer.numberOfLeadingZeros(paletteSize);
        int newBits = clampBits(Math.max(needed, 4));
        if (newBits <= bitsPerBlock) {
            newBits = clampBits(bitsPerBlock + 1);
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
