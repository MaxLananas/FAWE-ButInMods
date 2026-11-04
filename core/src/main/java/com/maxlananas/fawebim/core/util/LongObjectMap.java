package com.maxlananas.fawebim.core.util;

/**
 * A map from a long to an object in one open-addressed table.
 *
 * <p>The engine looks a chunk buffer up by packed chunk key for every block it
 * reads or writes. A {@code Map<Long, ...>} boxes that key on every call, which
 * showed up as a real share of the write path; here the keys stay primitive and
 * a slot is occupied exactly when its value is not null.</p>
 *
 * <p>Values must never be null, which is what the "occupied" test rests on.</p>
 */
public final class LongObjectMap<V> {

    private static final float LOAD_FACTOR = 0.65f;

    private long[] keys;
    private Object[] values;
    private int size;
    private int threshold;

    public LongObjectMap() {
        this(16);
    }

    public LongObjectMap(int expected) {
        int capacity = Integer.highestOneBit(Math.max(16, expected * 2 - 1)) << 1;
        this.keys = new long[capacity];
        this.values = new Object[capacity];
        this.threshold = (int) (capacity * LOAD_FACTOR);
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    @SuppressWarnings("unchecked")
    public V get(long key) {
        int mask = keys.length - 1;
        int slot = spread(key) & mask;
        while (true) {
            Object value = values[slot];
            if (value == null) {
                return null;
            }
            if (keys[slot] == key) {
                return (V) value;
            }
            slot = (slot + 1) & mask;
        }
    }

    /** Stores a value, returning the one it replaced or null. */
    @SuppressWarnings("unchecked")
    public V put(long key, V value) {
        if (value == null) {
            throw new IllegalArgumentException("A null value would read as an empty slot");
        }
        if (size >= threshold) {
            grow();
        }
        int mask = keys.length - 1;
        int slot = spread(key) & mask;
        while (true) {
            Object existing = values[slot];
            if (existing == null) {
                keys[slot] = key;
                values[slot] = value;
                size++;
                return null;
            }
            if (keys[slot] == key) {
                values[slot] = value;
                return (V) existing;
            }
            slot = (slot + 1) & mask;
        }
    }

    /** Every value, in insertion-hash order. Used by the flush of an edit session. */
    @SuppressWarnings("unchecked")
    public java.util.List<V> values() {
        java.util.List<V> out = new java.util.ArrayList<>(size);
        for (Object value : values) {
            if (value != null) {
                out.add((V) value);
            }
        }
        return out;
    }

    /** Every key, ordered by hash. */
    public long[] keys() {
        long[] out = new long[size];
        int index = 0;
        for (int slot = 0; slot < values.length; slot++) {
            if (values[slot] != null) {
                out[index++] = keys[slot];
            }
        }
        return out;
    }

    /**
     * Drops a key, so a caller can hold one representation of a section at a
     * time: a section kept as a whole array is not the same entry as the runs a
     * partial read of it makes.
     *
     * <p>The slot is closed rather than emptied: see {@link #closeHole}.</p>
     */
    @SuppressWarnings("unchecked")
    public V remove(long key) {
        int mask = keys.length - 1;
        int slot = spread(key) & mask;
        while (true) {
            Object value = values[slot];
            if (value == null) {
                return null;
            }
            if (keys[slot] == key) {
                V removed = (V) value;
                size--;
                closeHole(slot, mask);
                return removed;
            }
            slot = (slot + 1) & mask;
        }
    }

    /**
     * Closes the slot a removal emptied, by shifting the rest of the cluster
     * back into it.
     *
     * <p>A lookup walks forward from the home slot of a key until it meets the
     * key or an empty slot, so emptying a slot in the middle of a cluster cut
     * every walk that passed through it: a key inserted after the removed one
     * was still in the table and could never be found again. In an edit session
     * that is a chunk buffer dropped under a neighbour that had already been
     * written to - the writes it still held were lost, silently.</p>
     */
    private void closeHole(int hole, int mask) {
        values[hole] = null;
        int scan = (hole + 1) & mask;
        while (values[scan] != null) {
            int home = spread(keys[scan]) & mask;
            // The entry may move back into the hole only when the hole lies on
            // its own walk from its home slot, that is when the home reaches the
            // hole no later than the slot the entry sits in - the walk is
            // forward, so both distances are taken from the home slot.
            if (((hole - home) & mask) <= ((scan - home) & mask)) {
                keys[hole] = keys[scan];
                values[hole] = values[scan];
                values[scan] = null;
                hole = scan;
            }
            scan = (scan + 1) & mask;
        }
    }

    public void clear() {
        java.util.Arrays.fill(values, null);
        size = 0;
    }

    private void grow() {
        long[] oldKeys = keys;
        Object[] oldValues = values;
        keys = new long[oldKeys.length << 1];
        values = new Object[keys.length];
        threshold = (int) (keys.length * LOAD_FACTOR);
        size = 0;
        for (int slot = 0; slot < oldValues.length; slot++) {
            Object value = oldValues[slot];
            if (value != null) {
                put(oldKeys[slot], cast(value));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private V cast(Object value) {
        return (V) value;
    }

    private static int spread(long key) {
        return (int) (key * 0x9E3779B97F4A7C15L >>> 40);
    }
}
