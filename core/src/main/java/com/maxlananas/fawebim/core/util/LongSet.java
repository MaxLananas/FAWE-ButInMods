package com.maxlananas.fawebim.core.util;

/**
 * A set of longs in an open-addressed table of primitive keys.
 *
 * <p>The walks that follow connected blocks - a fill, a drain, a tree - keep
 * the positions they have seen. A {@code Set<BlockVector3>} holds a vector and
 * a map entry per position; this holds the packed position in a slot of a
 * {@code long[]}.</p>
 *
 * <p>Zero marks a free slot, so the value zero is remembered on the side.</p>
 */
public final class LongSet {

    private static final float LOAD_FACTOR = 0.6f;

    private long[] keys;
    private boolean containsZero;
    private int size;
    private int threshold;

    public LongSet() {
        this(16);
    }

    public LongSet(int expected) {
        int capacity = Integer.highestOneBit(Math.max(8, expected * 2 - 1)) << 1;
        this.keys = new long[capacity];
        this.threshold = (int) (capacity * LOAD_FACTOR);
    }

    /** Adds a value; returns true when it was not in the set yet. */
    public boolean add(long value) {
        if (value == 0) {
            if (containsZero) {
                return false;
            }
            containsZero = true;
            size++;
            return true;
        }
        if (size >= threshold) {
            rehash(keys.length << 1);
        }
        return insert(value);
    }

    public boolean contains(long value) {
        if (value == 0) {
            return containsZero;
        }
        int mask = keys.length - 1;
        int slot = spread(value) & mask;
        while (true) {
            long candidate = keys[slot];
            if (candidate == value) {
                return true;
            }
            if (candidate == 0) {
                return false;
            }
            slot = (slot + 1) & mask;
        }
    }

    public int size() {
        return size;
    }

    private boolean insert(long value) {
        int mask = keys.length - 1;
        int slot = spread(value) & mask;
        while (true) {
            long candidate = keys[slot];
            if (candidate == value) {
                return false;
            }
            if (candidate == 0) {
                keys[slot] = value;
                size++;
                return true;
            }
            slot = (slot + 1) & mask;
        }
    }

    private void rehash(int capacity) {
        long[] old = keys;
        keys = new long[capacity];
        threshold = (int) (capacity * LOAD_FACTOR);
        size = containsZero ? 1 : 0;
        for (long key : old) {
            if (key != 0) {
                insert(key);
            }
        }
    }

    private static int spread(long value) {
        long mixed = value * 0x9E3779B97F4A7C15L;
        return (int) (mixed ^ (mixed >>> 32));
    }
}
