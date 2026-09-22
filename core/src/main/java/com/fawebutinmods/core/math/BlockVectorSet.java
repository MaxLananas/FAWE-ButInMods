package com.fawebutinmods.core.math;

import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * A growable set of block positions backed by an open-addressed long hash table.
 *
 * <p>FAWE keeps region iteration sets and "already visited" sets for the bulk
 * visitors; storing one position per {@code long} (instead of one object per
 * position) is what keeps a 100M block {@code //set} from exhausting the heap.
 * Positions outside the packable range fall back to a regular hash set.</p>
 */
public final class BlockVectorSet implements Iterable<BlockVector3> {

    private static final float LOAD_FACTOR = 0.65f;
    private static final int MIN_Y = -2048;
    private static final int MAX_Y = 2047;

    private long[] keys;
    private int size;
    private int threshold;
    private java.util.HashSet<BlockVector3> fallback;

    public BlockVectorSet() {
        this(16);
    }

    public BlockVectorSet(int expected) {
        int cap = Integer.highestOneBit(Math.max(16, expected * 2 - 1)) << 1;
        this.keys = new long[cap];
        this.threshold = (int) (cap * LOAD_FACTOR);
    }

    private static boolean packable(int y) {
        return y >= MIN_Y && y <= MAX_Y;
    }

    private static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF) | (1L << 63);
    }

    private static int unpackX(long k) {
        int v = (int) ((k >>> 38) & 0x3FFFFFF);
        return (v << 6) >> 6; // sign extend 26 bits
    }

    private static int unpackZ(long k) {
        int v = (int) ((k >>> 12) & 0x3FFFFFF);
        return (v << 6) >> 6;
    }

    private static int unpackY(long k) {
        int v = (int) (k & 0xFFF);
        return (v << 20) >> 20; // sign extend 12 bits
    }

    public int size() {
        return size + (fallback == null ? 0 : fallback.size());
    }

    public boolean isEmpty() {
        return size() == 0;
    }

    public boolean add(int x, int y, int z) {
        if (!packable(y)) {
            if (fallback == null) {
                fallback = new java.util.HashSet<>();
            }
            return fallback.add(new BlockVector3(x, y, z));
        }
        long key = pack(x, y, z);
        int mask = keys.length - 1;
        int idx = hash(key) & mask;
        while (true) {
            long cur = keys[idx];
            if (cur == 0) {
                keys[idx] = key;
                if (++size >= threshold) {
                    grow();
                }
                return true;
            }
            if (cur == key) {
                return false;
            }
            idx = (idx + 1) & mask;
        }
    }

    public boolean add(BlockVector3 v) {
        return add(v.x(), v.y(), v.z());
    }

    public boolean remove(int x, int y, int z) {
        if (!packable(y)) {
            return fallback != null && fallback.remove(new BlockVector3(x, y, z));
        }
        long key = pack(x, y, z);
        int mask = keys.length - 1;
        int idx = hash(key) & mask;
        while (true) {
            long cur = keys[idx];
            if (cur == 0) {
                return false;
            }
            if (cur == key) {
                // Backward-shift deletion to keep probe chains intact.
                int next = (idx + 1) & mask;
                while (keys[next] != 0) {
                    int home = hash(keys[next]) & mask;
                    // Can the entry at `next` move into the free slot at `idx`?
                    if (circularDistance(home, idx, mask) < circularDistance(home, next, mask)) {
                        keys[idx] = keys[next];
                        idx = next;
                    }
                    next = (next + 1) & mask;
                }
                keys[idx] = 0;
                size--;
                return true;
            }
            idx = (idx + 1) & mask;
        }
    }

    private static int circularDistance(int home, int slot, int mask) {
        return (slot - home) & mask;
    }

    public boolean contains(int x, int y, int z) {
        if (!packable(y)) {
            return fallback != null && fallback.contains(new BlockVector3(x, y, z));
        }
        long key = pack(x, y, z);
        int mask = keys.length - 1;
        int idx = hash(key) & mask;
        while (true) {
            long cur = keys[idx];
            if (cur == 0) {
                return false;
            }
            if (cur == key) {
                return true;
            }
            idx = (idx + 1) & mask;
        }
    }

    public boolean contains(BlockVector3 v) {
        return contains(v.x(), v.y(), v.z());
    }

    public void clear() {
        java.util.Arrays.fill(keys, 0L);
        size = 0;
        if (fallback != null) {
            fallback.clear();
        }
    }

    private static int hash(long key) {
        long h = key * 0x9E3779B97F4A7C15L;
        return (int) (h >>> 32);
    }

    private void grow() {
        long[] old = keys;
        keys = new long[old.length << 1];
        threshold = (int) (keys.length * LOAD_FACTOR);
        int mask = keys.length - 1;
        for (long key : old) {
            if (key == 0) {
                continue;
            }
            int idx = hash(key) & mask;
            while (keys[idx] != 0) {
                idx = (idx + 1) & mask;
            }
            keys[idx] = key;
        }
    }

    @Override
    public Iterator<BlockVector3> iterator() {
        return new Iterator<>() {
            private int idx = 0;
            private BlockVector3 next;
            private Iterator<BlockVector3> extra;

            private void advance() {
                while (next == null && idx < keys.length) {
                    long k = keys[idx++];
                    if (k != 0) {
                        next = new BlockVector3(unpackX(k), unpackY(k), unpackZ(k));
                    }
                }
                if (next == null && extra == null && fallback != null) {
                    extra = fallback.iterator();
                }
                if (next == null && extra != null && extra.hasNext()) {
                    next = extra.next();
                }
            }

            @Override
            public boolean hasNext() {
                advance();
                return next != null;
            }

            @Override
            public BlockVector3 next() {
                advance();
                if (next == null) {
                    throw new NoSuchElementException();
                }
                BlockVector3 v = next;
                next = null;
                return v;
            }
        };
    }

    /** Allocates the set the same way FAWE does for a known block count. */
    public static BlockVectorSet forVolume(long volume) {
        if (volume > Integer.MAX_VALUE / 2) {
            return new BlockVectorSet(1 << 20);
        }
        return new BlockVectorSet((int) Math.max(16, Math.min(volume, 1 << 22)));
    }
}
