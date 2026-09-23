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

    private static final int[] BITS_TO_VALUES_PER_LONG = {0, 64, 32, 21, 16, 12, 10, 9, 8, 7, 6, 5, 5, 4, 4, 4, 4};

    private int[] palette;
    private int paletteSize;
    private int bitsPerBlock;
    private long[] data;
    private int valuesPerLong;
    private long mask;
    private boolean uniform;

    public PackedBlockArray(int initialBits) {
        this.bitsPerBlock = clampBits(initialBits);
        this.palette = new int[1 << Math.min(12, this.bitsPerBlock)];
        this.paletteSize = 0;
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

    /** Builds an array filled with a single state (FAWE's "uniform section"). */
    public static PackedBlockArray uniform(int stateId) {
        PackedBlockArray arr = new PackedBlockArray(1);
        arr.fill(stateId);
        return arr;
    }

    public void fill(int stateId) {
        Arrays.fill(data, 0L);
        palette = new int[1];
        palette[0] = stateId;
        paletteSize = 1;
        bitsPerBlock = 1;
        reallocate();
        uniform = true;
    }

    public int paletteSize() {
        return paletteSize;
    }

    public int bitsPerBlock() {
        return bitsPerBlock;
    }

    public boolean isUniform() {
        return uniform;
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
        for (int i = 0; i < paletteSize; i++) {
            if (palette[i] == stateId) {
                return i;
            }
        }
        return -1;
    }

    private int paletteAdd(int stateId) {
        if (paletteSize == palette.length) {
            palette = Arrays.copyOf(palette, Math.min(1 << 12, palette.length << 1));
        }
        palette[paletteSize] = stateId;
        return paletteSize++;
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
        return copy;
    }

    /** Raw palette, used by the schematic serializer. */
    public int[] palette() {
        return palette;
    }

    public long[] rawData() {
        return data;
    }

    /** Iterates all 4096 state ids into a flat array (used for clipboard copies). */
    public int[] toFlatArray() {
        int[] flat = new int[VOLUME];
        if (uniform) {
            Arrays.fill(flat, palette[0]);
        } else {
            for (int i = 0; i < VOLUME; i++) {
                flat[i] = get(i);
            }
        }
        return flat;
    }

    /** Static helper mirroring {@link #BITS_TO_VALUES_PER_LONG}. */
    public static int valuesPerLong(int bits) {
        return bits >= 1 && bits < BITS_TO_VALUES_PER_LONG.length ? BITS_TO_VALUES_PER_LONG[bits] : 64 / bits;
    }
}
