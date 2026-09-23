package com.maxlananas.fawebim.core.util;

/**
 * A set of ints in an open-addressed table of primitive keys.
 *
 * <p>A mask tests a state id for every block of an edit, so holding those ids in
 * a {@code Set<Integer>} boxes one object per block tested. This keeps the same
 * test to a couple of array reads and no allocation at all.</p>
 *
 * <p>Slots hold {@code value + 1} so that zero can mark a free one, which means
 * the value {@code -1} is not storable; the engine's {@code -1} is its invalid
 * state id and never reaches a mask.</p>
 */
public final class IntSet {

    private static final float LOAD_FACTOR = 0.65f;

    private int[] keys;
    private int size;
    private int threshold;

    public IntSet() {
        this(8);
    }

    public IntSet(int expected) {
        int capacity = Integer.highestOneBit(Math.max(8, expected * 2 - 1)) << 1;
        this.keys = new int[capacity];
        this.threshold = (int) (capacity * LOAD_FACTOR);
    }

    /** Adds a value; returns true when it was not in the set yet. */
    public boolean add(int value) {
        if (size >= threshold) {
            rehash(keys.length << 1);
        }
        return insert(value);
    }

    public boolean contains(int value) {
        int key = value + 1;
        int mask = keys.length - 1;
        int slot = spread(key) & mask;
        while (true) {
            int candidate = keys[slot];
            if (candidate == key) {
                return true;
            }
            if (candidate == 0) {
                return false;
            }
            slot = (slot + 1) & mask;
        }
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public int size() {
        return size;
    }

    /** The values in the set, in no particular order. */
    public int[] toArray() {
        int[] values = new int[size];
        int index = 0;
        for (int key : keys) {
            if (key != 0) {
                values[index++] = key - 1;
            }
        }
        return values;
    }

    private boolean insert(int value) {
        int key = value + 1;
        int mask = keys.length - 1;
        int slot = spread(key) & mask;
        while (true) {
            int candidate = keys[slot];
            if (candidate == key) {
                return false;
            }
            if (candidate == 0) {
                keys[slot] = key;
                size++;
                return true;
            }
            slot = (slot + 1) & mask;
        }
    }

    private void rehash(int capacity) {
        int[] old = keys;
        keys = new int[capacity];
        threshold = (int) (capacity * LOAD_FACTOR);
        size = 0;
        for (int key : old) {
            if (key != 0) {
                insert(key - 1);
            }
        }
    }

    private static int spread(int key) {
        return key * 0x9E3779B1;
    }
}
