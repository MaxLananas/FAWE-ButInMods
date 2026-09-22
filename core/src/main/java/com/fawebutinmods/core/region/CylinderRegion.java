package com.fawebutinmods.core.region;

import com.fawebutinmods.core.math.BlockVector2;
import com.fawebutinmods.core.math.BlockVector3;
import com.fawebutinmods.core.math.Vector2;
import com.fawebutinmods.core.math.Vector3;

import java.util.Iterator;
import java.util.NoSuchElementException;

/** A vertical elliptical cylinder ({@code //sel cyl}). */
public class CylinderRegion implements Region {

    private Vector2 center;
    private double radiusX;
    private double radiusZ;
    private int minY;
    private int maxY;

    public CylinderRegion(Vector2 center, double radiusX, double radiusZ, int minY, int maxY) {
        this.center = center;
        this.radiusX = Math.abs(radiusX);
        this.radiusZ = Math.abs(radiusZ);
        this.minY = Math.min(minY, maxY);
        this.maxY = Math.max(minY, maxY);
    }

    public CylinderRegion(Vector3 center, double radiusX, double radiusZ, int minY, int maxY) {
        this(new Vector2(center.x(), center.z()), radiusX, radiusZ, minY, maxY);
    }

    public Vector2 getCenter2D() {
        return center;
    }

    public void setCenter(Vector2 center) {
        this.center = center;
    }

    public double getRadiusX() {
        return radiusX;
    }

    public double getRadiusZ() {
        return radiusZ;
    }

    public void setRadius(double radiusX, double radiusZ) {
        this.radiusX = radiusX;
        this.radiusZ = radiusZ;
    }

    public int getMinY() {
        return minY;
    }

    public int getMaxY() {
        return maxY;
    }

    public void setYRange(int minY, int maxY) {
        this.minY = Math.min(minY, maxY);
        this.maxY = Math.max(minY, maxY);
    }

    public void extendY(int y) {
        minY = Math.min(minY, y);
        maxY = Math.max(maxY, y);
    }

    /** Widens the radius so that a point on the rim is included, like WorldEdit's {@code //hcyl}. */
    public void extendRadius(Vector2 point) {
        double dx = Math.abs(point.x() + 0.5 - center.x());
        double dz = Math.abs(point.z() + 0.5 - center.z());
        radiusX = Math.max(radiusX, dx);
        radiusZ = Math.max(radiusZ, dz);
    }

    public void setRadiusX(double radiusX) {
        this.radiusX = radiusX;
    }

    public void setRadiusZ(double radiusZ) {
        this.radiusZ = radiusZ;
    }

    @Override
    public BlockVector3 getMinimumPoint() {
        return new BlockVector3(
                (int) Math.floor(center.x() - radiusX), minY, (int) Math.floor(center.z() - radiusZ));
    }

    @Override
    public BlockVector3 getMaximumPoint() {
        return new BlockVector3(
                (int) Math.floor(center.x() + radiusX), maxY, (int) Math.floor(center.z() + radiusZ));
    }

    @Override
    /** Same formula as WorldEdit: {@code pi * rX * rZ * height}, rounded down. */
    public long getVolume() {
        return java.math.BigDecimal.valueOf(radiusX)
                .multiply(java.math.BigDecimal.valueOf(radiusZ))
                .multiply(java.math.BigDecimal.valueOf(Math.PI))
                .multiply(java.math.BigDecimal.valueOf(getHeight()))
                .setScale(0, java.math.RoundingMode.FLOOR)
                .longValue();
    }

    @Override
    public boolean contains(int x, int y, int z) {
        if (y < minY || y > maxY || radiusX < 1e-6 || radiusZ < 1e-6) {
            return false;
        }
        double dx = (x + 0.5 - center.x()) / radiusX;
        double dz = (z + 0.5 - center.z()) / radiusZ;
        return dx * dx + dz * dz <= 1.0;
    }

    @Override
    public boolean expand(BlockVector3 amount) {
        if (amount.x() != 0 || amount.z() != 0) {
            double n = Math.max(Math.abs(amount.x()), Math.abs(amount.z()));
            radiusX += n;
            radiusZ += n;
        }
        if (amount.y() < 0) {
            minY += amount.y();
        } else if (amount.y() > 0) {
            maxY += amount.y();
        }
        return true;
    }

    @Override
    public boolean contract(BlockVector3 amount) {
        if (amount.x() != 0 || amount.z() != 0) {
            double n = amount.x() != 0 ? Math.abs(amount.x()) : Math.abs(amount.z());
            radiusX = Math.max(0, radiusX - n);
            radiusZ = Math.max(0, radiusZ - n);
        }
        if (amount.y() > 0) {
            minY += amount.y();
        } else if (amount.y() < 0) {
            maxY += amount.y();
        }
        return true;
    }

    @Override
    public Iterator<BlockVector3> iterator() {
        BlockVector3 min = getMinimumPoint();
        BlockVector3 max = getMaximumPoint();
        return new Iterator<>() {
            private int x = min.x();
            private int y = min.y();
            private int z = min.z();
            private BlockVector3 next = seek();

            private BlockVector3 seek() {
                while (y <= max.y()) {
                    while (z <= max.z()) {
                        while (x <= max.x()) {
                            int cx = x++;
                            if (contains(cx, y, z)) {
                                return new BlockVector3(cx, y, z);
                            }
                        }
                        x = min.x();
                        z++;
                    }
                    z = min.z();
                    y++;
                }
                return null;
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

    @Override
    public String describe() {
        return "cylinder (" + BlockVector2.at((int) center.x(), (int) center.z()) + " r=" + radiusX + "," + radiusZ
                + " y=" + minY + ".." + maxY + ")";
    }
}
