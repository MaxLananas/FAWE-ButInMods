package com.maxlananas.fawebim.core.region;

import com.maxlananas.fawebim.core.math.BlockVector3;

import java.util.Iterator;

/** The classic two-corner selection ({@code //pos1} / {@code //pos2}). */
public class CuboidRegion implements Region {

    protected int minX;
    protected int minY;
    protected int minZ;
    protected int maxX;
    protected int maxY;
    protected int maxZ;

    public CuboidRegion(BlockVector3 corner1, BlockVector3 corner2) {
        this(
                Math.min(corner1.x(), corner2.x()), Math.min(corner1.y(), corner2.y()), Math.min(corner1.z(), corner2.z()),
                Math.max(corner1.x(), corner2.x()), Math.max(corner1.y(), corner2.y()), Math.max(corner1.z(), corner2.z()));
    }

    public CuboidRegion(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
    }

    @Override
    public BlockVector3 getMinimumPoint() {
        return new BlockVector3(minX, minY, minZ);
    }

    @Override
    public BlockVector3 getMaximumPoint() {
        return new BlockVector3(maxX, maxY, maxZ);
    }

    public int minX() {
        return minX;
    }

    public int minY() {
        return minY;
    }

    public int minZ() {
        return minZ;
    }

    public int maxX() {
        return maxX;
    }

    public int maxY() {
        return maxY;
    }

    public int maxZ() {
        return maxZ;
    }

    /** Sets both corners at once, in any order. */
    public void setCorners(BlockVector3 corner1, BlockVector3 corner2) {
        this.minX = Math.min(corner1.x(), corner2.x());
        this.minY = Math.min(corner1.y(), corner2.y());
        this.minZ = Math.min(corner1.z(), corner2.z());
        this.maxX = Math.max(corner1.x(), corner2.x());
        this.maxY = Math.max(corner1.y(), corner2.y());
        this.maxZ = Math.max(corner1.z(), corner2.z());
    }

    /** Sets the lower corner; a corner past the upper one swaps with it on that axis. */
    public void setPos1(BlockVector3 pos) {
        this.minX = pos.x();
        this.minY = pos.y();
        this.minZ = pos.z();
        normalize();
    }

    /** Sets the upper corner; a corner past the lower one swaps with it on that axis. */
    public void setPos2(BlockVector3 pos) {
        this.maxX = pos.x();
        this.maxY = pos.y();
        this.maxZ = pos.z();
        normalize();
    }

    /**
     * Keeps {@code min <= max} on every axis.
     *
     * <p>A contraction past the opposite side, or a corner set past the other
     * one, used to leave the minimum above the maximum: the volume came out as a
     * product of negative sizes, positive again, and the walk visited nothing
     * while the size claimed blocks. WorldEdit keeps the two corners a player
     * set and reads the box from them, so going past the other side flips the
     * box, which is what swapping does here.</p>
     */
    private void normalize() {
        if (minX > maxX) {
            int swap = minX;
            minX = maxX;
            maxX = swap;
        }
        if (minY > maxY) {
            int swap = minY;
            minY = maxY;
            maxY = swap;
        }
        if (minZ > maxZ) {
            int swap = minZ;
            minZ = maxZ;
            maxZ = swap;
        }
    }

    @Override
    public long getVolume() {
        return (long) getWidth() * getHeight() * getLength();
    }

    @Override
    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    @Override
    public boolean expand(BlockVector3 amount) {
        boolean changed = false;
        if (amount.x() < 0) {
            minX += amount.x();
            changed = true;
        } else if (amount.x() > 0) {
            maxX += amount.x();
            changed = true;
        }
        if (amount.y() < 0) {
            minY += amount.y();
            changed = true;
        } else if (amount.y() > 0) {
            maxY += amount.y();
            changed = true;
        }
        if (amount.z() < 0) {
            minZ += amount.z();
            changed = true;
        } else if (amount.z() > 0) {
            maxZ += amount.z();
            changed = true;
        }
        normalize();
        return changed;
    }

    @Override
    public boolean contract(BlockVector3 amount) {
        boolean changed = false;
        if (amount.x() > 0) {
            minX += amount.x();
            changed = true;
        } else if (amount.x() < 0) {
            maxX += amount.x();
            changed = true;
        }
        if (amount.y() > 0) {
            minY += amount.y();
            changed = true;
        } else if (amount.y() < 0) {
            maxY += amount.y();
            changed = true;
        }
        if (amount.z() > 0) {
            minZ += amount.z();
            changed = true;
        } else if (amount.z() < 0) {
            maxZ += amount.z();
            changed = true;
        }
        normalize();
        return changed;
    }

    @Override
    public boolean shift(BlockVector3 amount) {
        minX += amount.x();
        maxX += amount.x();
        minY += amount.y();
        maxY += amount.y();
        minZ += amount.z();
        maxZ += amount.z();
        return !amount.equals(BlockVector3.ZERO);
    }

    /** The cuboid's corners, in the order WorldEdit reports them. */
    public BlockVector3[] getCorners() {
        return new BlockVector3[]{
                new BlockVector3(minX, minY, minZ),
                new BlockVector3(maxX, minY, minZ),
                new BlockVector3(minX, minY, maxZ),
                new BlockVector3(maxX, minY, maxZ),
                new BlockVector3(minX, maxY, minZ),
                new BlockVector3(maxX, maxY, minZ),
                new BlockVector3(minX, maxY, maxZ),
                new BlockVector3(maxX, maxY, maxZ)
        };
    }

    public boolean isFlatX() {
        return minX == maxX;
    }

    public boolean isFlatZ() {
        return minZ == maxZ;
    }

    /**
     * Walks the region one chunk section at a time, in section index order.
     *
     * <p>An edit writes into a buffer keyed by chunk and section, so an order
     * that crosses a chunk every sixteen blocks looks the buffer up again and
     * again: the whole region is walked here a section at a time, which is what
     * WorldEdit's region iterator does as well. Inside a section the walk is x
     * first, then z, then y, which is the order the section stores its cells in,
     * so the writes of a section are sequential.</p>
     */
    @Override
    public long forEachPosition(BlockVisitor visitor) {
        long visited = 0;
        for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
            int xStart = Math.max(minX, chunkX << 4);
            int xEnd = Math.min(maxX, (chunkX << 4) + 15);
            for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) {
                int zStart = Math.max(minZ, chunkZ << 4);
                int zEnd = Math.min(maxZ, (chunkZ << 4) + 15);
                for (int sectionY = minY >> 4; sectionY <= maxY >> 4; sectionY++) {
                    int yStart = Math.max(minY, sectionY << 4);
                    int yEnd = Math.min(maxY, (sectionY << 4) + 15);
                    for (int y = yStart; y <= yEnd; y++) {
                        for (int z = zStart; z <= zEnd; z++) {
                            for (int x = xStart; x <= xEnd; x++) {
                                if (visitor.visit(x, y, z)) {
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

    /** The same order as {@link #forEachPosition}, one position at a time. */
    @Override
    public Iterator<BlockVector3> iterator() {
        return ColumnWalk.iterator(minX, minZ, maxX, maxZ, (baseX, baseZ, lo, hi) -> {
            java.util.Arrays.fill(lo, minY);
            java.util.Arrays.fill(hi, maxY);
        });
    }

    @Override
    public String describe() {
        return "cuboid: " + getWidth() + "x" + getHeight() + "x" + getLength();
    }

    @Override
    public String toString() {
        return "CuboidRegion(" + getMinimumPoint() + " -> " + getMaximumPoint() + ")";
    }
}
