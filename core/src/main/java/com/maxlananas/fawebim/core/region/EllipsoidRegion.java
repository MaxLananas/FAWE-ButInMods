package com.maxlananas.fawebim.core.region;

import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.math.Vector3;

import java.util.Iterator;
import java.util.NoSuchElementException;

/** A sphere/ellipsoid region ({@code //sel sphere|ellipsoid}). */
public class EllipsoidRegion implements Region {

    private Vector3 center;
    private Vector3 radii;
    private int minY;
    private int maxY;

    public EllipsoidRegion(Vector3 center, Vector3 radii) {
        this(center, radii, Integer.MIN_VALUE / 2, Integer.MAX_VALUE / 2);
    }

    public EllipsoidRegion(Vector3 center, Vector3 radii, int minY, int maxY) {
        this.center = center;
        this.radii = new Vector3(Math.abs(radii.x()), Math.abs(radii.y()), Math.abs(radii.z()));
        this.minY = minY;
        this.maxY = maxY;
    }

    public Vector3 getCenter() {
        return center;
    }

    public void setCenter(Vector3 center) {
        this.center = center;
    }

    public Vector3 getRadii() {
        return radii;
    }

    public void setRadii(Vector3 radii) {
        this.radii = radii;
    }

    public void setYBounds(int minY, int maxY) {
        this.minY = minY;
        this.maxY = maxY;
    }

    public void extendRadius(Vector3 radius) {
        this.radii = new Vector3(
                Math.max(radii.x(), Math.abs(radius.x())),
                Math.max(radii.y(), Math.abs(radius.y())),
                Math.max(radii.z(), Math.abs(radius.z())));
    }

    public void setYRadius(double radius) {
        radii = radii.withY(Math.abs(radius));
    }

    @Override
    public BlockVector3 getMinimumPoint() {
        return BlockVector3.floor(center.subtract(radii));
    }

    @Override
    public BlockVector3 getMaximumPoint() {
        return BlockVector3.floor(center.add(radii));
    }

    @Override
    /** Same formula as WorldEdit: {@code 4/3 * pi * rX * rY * rZ}, rounded down. */
    public long getVolume() {
        return (long) java.math.BigDecimal.valueOf(4.0 / 3.0 * Math.PI)
                .multiply(java.math.BigDecimal.valueOf(radii.x()))
                .multiply(java.math.BigDecimal.valueOf(radii.y()))
                .multiply(java.math.BigDecimal.valueOf(radii.z()))
                .setScale(0, java.math.RoundingMode.FLOOR)
                .longValue();
    }

    public double getRadiusX() {
        return radii.x();
    }

    public double getRadiusY() {
        return radii.y();
    }

    public double getRadiusZ() {
        return radii.z();
    }

    @Override
    public boolean contains(int x, int y, int z) {
        if (y < minY || y > maxY) {
            return false;
        }
        if (radii.x() < 1e-6 || radii.y() < 1e-6 || radii.z() < 1e-6) {
            return false;
        }
        double dx = (x + 0.5 - center.x()) / radii.x();
        double dy = (y + 0.5 - center.y()) / radii.y();
        double dz = (z + 0.5 - center.z()) / radii.z();
        return dx * dx + dy * dy + dz * dz <= 1.0;
    }

    @Override
    public boolean expand(BlockVector3 amount) {
        double dx = amount.x();
        double dy = amount.y();
        double dz = amount.z();
        if (dx != 0 || dz != 0) {
            double n = Math.max(Math.abs(dx), Math.abs(dz));
            radii = new Vector3(radii.x() + n, radii.y(), radii.z() + n);
        }
        if (dy != 0) {
            radii = radii.withY(radii.y() + dy);
        }
        return true;
    }

    @Override
    public boolean contract(BlockVector3 amount) {
        return expand(amount.multiply(-1));
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
        return "ellipsoid (" + radii.x() + "x" + radii.y() + "x" + radii.z() + ")";
    }
}
