package com.fawebutinmods.core.region;

import com.fawebutinmods.core.math.BlockVector3;

import java.util.Iterator;
import java.util.NoSuchElementException;

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

    public void setPos1(BlockVector3 pos) {
        this.minX = pos.x();
        this.minY = pos.y();
        this.minZ = pos.z();
    }

    public void setPos2(BlockVector3 pos) {
        this.maxX = pos.x();
        this.maxY = pos.y();
        this.maxZ = pos.z();
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
            changed |= minX != minX + amount.x();
            minX += amount.x();
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
        return changed;
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

    @Override
    public Iterator<BlockVector3> iterator() {
        return new Iterator<>() {
            private int x = minX;
            private int y = minY;
            private int z = minZ;
            private boolean done = getVolume() == 0 || maxY < minY;

            @Override
            public boolean hasNext() {
                return !done;
            }

            @Override
            public BlockVector3 next() {
                if (done) {
                    throw new NoSuchElementException();
                }
                BlockVector3 result = new BlockVector3(x, y, z);
                // Iterate x fastest so writes land inside the same chunk section.
                if (++x > maxX) {
                    x = minX;
                    if (++z > maxZ) {
                        z = minZ;
                        if (++y > maxY) {
                            done = true;
                        }
                    }
                }
                return result;
            }
        };
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
