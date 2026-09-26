package com.maxlananas.fawebim.core.region;

import com.maxlananas.fawebim.core.math.BlockVector3;

import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * The traversal shared by the round and polygonal shapes: a shape tells, for
 * each column of a chunk, the one run of Y it holds there, and the walk visits
 * those runs a chunk column at a time and a section at a time inside it.
 *
 * <p>That order is the one the edit buffer and the world store blocks in, so a
 * section is written front to back and a chunk is finished before the next one
 * starts. The column runs of a chunk are computed once for all of its height:
 * a cylinder or a polygon was tested block by block before, and a polygon or a
 * polyhedron materialised every one of its blocks as an object first.</p>
 */
final class ColumnWalk {

    /** The column runs of a shape. */
    interface Columns {

        /**
         * Fills the Y run of each column of the chunk whose corner is
         * {@code (baseX, baseZ)}, at index {@code localZ << 4 | localX}. A column
         * the shape does not reach gets {@code lo > hi}.
         */
        void fill(int baseX, int baseZ, int[] lo, int[] hi);
    }

    private ColumnWalk() {
    }

    static long walk(int minX, int minZ, int maxX, int maxZ, Columns columns, Region.BlockVisitor visitor) {
        if (minX > maxX || minZ > maxZ) {
            return 0;
        }
        int[] lo = new int[256];
        int[] hi = new int[256];
        long visited = 0;
        for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
            int baseX = chunkX << 4;
            int x0 = Math.max(minX, baseX) - baseX;
            int x1 = Math.min(maxX, baseX + 15) - baseX;
            for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) {
                int baseZ = chunkZ << 4;
                int z0 = Math.max(minZ, baseZ) - baseZ;
                int z1 = Math.min(maxZ, baseZ + 15) - baseZ;
                columns.fill(baseX, baseZ, lo, hi);
                long span = heightSpan(lo, hi, x0, x1, z0, z1);
                if (span == Long.MIN_VALUE) {
                    continue;
                }
                int yMin = (int) (span >> 32);
                int yMax = (int) span;
                for (int sectionY = yMin >> 4; sectionY <= yMax >> 4; sectionY++) {
                    int yStart = Math.max(yMin, sectionY << 4);
                    int yEnd = Math.min(yMax, (sectionY << 4) + 15);
                    for (int y = yStart; y <= yEnd; y++) {
                        for (int z = z0; z <= z1; z++) {
                            int row = z << 4;
                            for (int x = x0; x <= x1; x++) {
                                int column = row | x;
                                if (y >= lo[column] && y <= hi[column] && visitor.visit(baseX + x, y, baseZ + z)) {
                                    visited++;
                                }
                            }
                        }
                    }
                }
            }
        }
        return visited;
    }

    /**
     * The lowest and highest Y of the columns of a chunk, packed as
     * {@code min << 32 | max}, or {@code Long.MIN_VALUE} when no column holds
     * anything.
     */
    private static long heightSpan(int[] lo, int[] hi, int x0, int x1, int z0, int z1) {
        int yMin = Integer.MAX_VALUE;
        int yMax = Integer.MIN_VALUE;
        for (int z = z0; z <= z1; z++) {
            for (int x = x0; x <= x1; x++) {
                int column = z << 4 | x;
                if (lo[column] <= hi[column]) {
                    yMin = Math.min(yMin, lo[column]);
                    yMax = Math.max(yMax, hi[column]);
                }
            }
        }
        return yMin > yMax ? Long.MIN_VALUE : (long) yMin << 32 | (yMax & 0xFFFFFFFFL);
    }

    /** The same walk, one position at a time, for the callers that iterate. */
    static Iterator<BlockVector3> iterator(int minX, int minZ, int maxX, int maxZ, Columns columns) {
        return new Iterator<>() {
            private final int[] lo = new int[256];
            private final int[] hi = new int[256];
            private int chunkX = minX >> 4;
            private int chunkZ = (minZ >> 4) - 1;
            private int x0;
            private int x1;
            private int z0;
            private int z1;
            private int yEnd;
            private int x;
            private int y;
            private int z;
            private boolean chunkOpen;
            private BlockVector3 next;
            private final boolean empty = minX > maxX || minZ > maxZ;

            private boolean openNextChunk() {
                while (true) {
                    chunkZ++;
                    if (chunkZ > maxZ >> 4) {
                        chunkZ = minZ >> 4;
                        chunkX++;
                        if (chunkX > maxX >> 4) {
                            return false;
                        }
                    }
                    int baseX = chunkX << 4;
                    int baseZ = chunkZ << 4;
                    x0 = Math.max(minX, baseX) - baseX;
                    x1 = Math.min(maxX, baseX + 15) - baseX;
                    z0 = Math.max(minZ, baseZ) - baseZ;
                    z1 = Math.min(maxZ, baseZ + 15) - baseZ;
                    columns.fill(baseX, baseZ, lo, hi);
                    long span = heightSpan(lo, hi, x0, x1, z0, z1);
                    if (span == Long.MIN_VALUE) {
                        continue;
                    }
                    y = (int) (span >> 32);
                    yEnd = (int) span;
                    z = z0;
                    x = x0;
                    return true;
                }
            }

            /**
             * Finds the next position. Inside a chunk the order is y, then z, then
             * x, like {@link #walk}; the section boundaries fall out of walking y
             * in order, since every column of the chunk is walked at each layer.
             */
            private BlockVector3 seek() {
                if (empty) {
                    return null;
                }
                while (true) {
                    if (!chunkOpen) {
                        if (!openNextChunk()) {
                            return null;
                        }
                        chunkOpen = true;
                    }
                    while (y <= yEnd) {
                        while (z <= z1) {
                            while (x <= x1) {
                                int column = z << 4 | x;
                                int localX = x++;
                                if (y >= lo[column] && y <= hi[column]) {
                                    return new BlockVector3((chunkX << 4) + localX, y, (chunkZ << 4) + z);
                                }
                            }
                            x = x0;
                            z++;
                        }
                        z = z0;
                        y++;
                    }
                    chunkOpen = false;
                }
            }

            @Override
            public boolean hasNext() {
                if (next == null) {
                    next = seek();
                }
                return next != null;
            }

            @Override
            public BlockVector3 next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                BlockVector3 result = next;
                next = null;
                return result;
            }
        };
    }
}
