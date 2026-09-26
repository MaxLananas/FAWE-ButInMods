package com.maxlananas.fawebim.core.region;

import java.util.Arrays;

/**
 * Block positions packed into a {@code long}, for the regions kept as a set of
 * blocks rather than as a shape.
 *
 * <p>A key holds x and z in 26 bits each and y in 12, which covers the world
 * border and every height a dimension can have. A walk goes in the order the
 * world stores blocks: chunk column by chunk column, and inside one from the
 * bottom up, row by row. A set has no order of its own, and an edit written in
 * hash order would jump to another chunk at almost every block.</p>
 */
final class BlockKeys {

    private BlockKeys() {
    }

    static long key(int x, int y, int z) {
        return ((long) x & 0x3FFFFFF) << 38 | ((long) y & 0xFFF) << 26 | ((long) z & 0x3FFFFFF);
    }

    static int x(long key) {
        return (int) (key >> 38);
    }

    static int y(long key) {
        return (int) (key << 26 >> 52);
    }

    static int z(long key) {
        return (int) (key << 38 >> 38);
    }

    /**
     * The positions sorted chunk first. The sort key packs, from the top, the
     * chunk x and z (22 bits each, which the world border fits), the y (12
     * bits) and the z and x inside the chunk (4 bits each).
     */
    static long[] chunkOrder(long[] positions) {
        long[] sortKeys = new long[positions.length];
        for (int i = 0; i < positions.length; i++) {
            long key = positions[i];
            int x = x(key);
            int y = y(key);
            int z = z(key);
            long sortKey = ((long) ((x >> 4) + (1 << 21)) << 42) | ((long) ((z >> 4) + (1 << 21)) << 20)
                    | ((long) (y + 2048) << 8) | ((z & 15) << 4) | (x & 15);
            // The sign bit is flipped so that the signed sort is the unsigned order.
            sortKeys[i] = sortKey ^ Long.MIN_VALUE;
        }
        Arrays.sort(sortKeys);
        long[] ordered = new long[positions.length];
        for (int i = 0; i < sortKeys.length; i++) {
            long sortKey = sortKeys[i] ^ Long.MIN_VALUE;
            int chunkX = (int) (sortKey >>> 42) - (1 << 21);
            int chunkZ = (int) ((sortKey >>> 20) & 0x3FFFFF) - (1 << 21);
            int y = (int) ((sortKey >>> 8) & 0xFFF) - 2048;
            int z = chunkZ << 4 | (int) ((sortKey >>> 4) & 15);
            int x = chunkX << 4 | (int) (sortKey & 15);
            ordered[i] = key(x, y, z);
        }
        return ordered;
    }

    /** Visits each position once, in the order given. */
    static long forEach(long[] ordered, Region.BlockVisitor visitor) {
        long visited = 0;
        for (long key : ordered) {
            if (visitor.visit(x(key), y(key), z(key))) {
                visited++;
            }
        }
        return visited;
    }

    /** The positions as vectors, for the few callers that iterate a region. */
    static java.util.Iterator<com.maxlananas.fawebim.core.math.BlockVector3> iterator(long[] ordered) {
        return new java.util.Iterator<>() {
            private int next;

            @Override
            public boolean hasNext() {
                return next < ordered.length;
            }

            @Override
            public com.maxlananas.fawebim.core.math.BlockVector3 next() {
                if (next >= ordered.length) {
                    throw new java.util.NoSuchElementException();
                }
                long key = ordered[next++];
                return new com.maxlananas.fawebim.core.math.BlockVector3(x(key), y(key), z(key));
            }
        };
    }
}
