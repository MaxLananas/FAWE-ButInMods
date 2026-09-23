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
    private int valuesPerLong;
    private long mask;
    private boolean uniform;

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
        palette = new int[1];
        palette[0] = stateId;
        paletteSize = 1;
        Arrays.fill(indexKeys, 0);
        indexInsert(stateId, 0);
        bitsPerBlock = 1;
        reallocate();
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
        int paletteIndex = paletteLookup(stateId);
        if (paletteIndex < 0) {
            paletteIndex = paletteAdd(stateId);
            if (paletteIndex >= (1 << bitsPerBlock)) {
                growBits();
            }
        }
        int slot = index / valuesPerLong;
        int offset = (index - slot * valuesPerLong) * bitsPerBlock;
        long clearMask = ~(mask << offset);
        data[slot] = (data[slot] & clearMask) | ((long) paletteIndex << offset);
        uniform = false;
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

    public PackedBlockArray copy() {
        PackedBlockArray copy = new PackedBlockArray(bitsPerBlock);
        copy.palette = palette.clone();
        copy.paletteSize = paletteSize;
        copy.bitsPerBlock = bitsPerBlock;
        copy.valuesPerLong = valuesPerLong;
        copy.mask = mask;
        copy.data = data.clone();
        copy.uniform = uniform;
        copy.rehashIndex(Math.max(16, paletteSize * 2));
        return copy;
    }
}
