package com.maxlananas.fawebim.core.region;

import com.maxlananas.fawebim.core.math.BlockVector2;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.math.Vector2;
import com.maxlananas.fawebim.core.math.Vector3;

import java.util.Iterator;

/**
 * A vertical elliptical cylinder ({@code //sel cyl}).
 *
 * <p>As with the {@link EllipsoidRegion}, a column is inside when its centre is
 * within the radius plus half a block, which is WorldEdit's test.</p>
 */
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
        this.radiusX = Math.abs(radiusX);
        this.radiusZ = Math.abs(radiusZ);
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
        this.radiusX = Math.abs(radiusX);
    }

    public void setRadiusZ(double radiusZ) {
        this.radiusZ = Math.abs(radiusZ);
    }

    /** The lowest corner of the blocks inside; see {@link EllipsoidRegion#getMinimumPoint()}. */
    @Override
    public BlockVector3 getMinimumPoint() {
        return new BlockVector3((int) Math.ceil(center.x() - radiusX - 1), minY,
                (int) Math.ceil(center.z() - radiusZ - 1));
    }

    @Override
    public BlockVector3 getMaximumPoint() {
        return new BlockVector3((int) Math.floor(center.x() + radiusX), maxY,
                (int) Math.floor(center.z() + radiusZ));
    }

    /** WorldEdit's formula: {@code pi * rX * rZ * height} over the radii it tests, rounded down. */
    @Override
    public long getVolume() {
        return java.math.BigDecimal.valueOf(radiusX + 0.5)
                .multiply(java.math.BigDecimal.valueOf(radiusZ + 0.5))
                .multiply(java.math.BigDecimal.valueOf(Math.PI))
                .multiply(java.math.BigDecimal.valueOf(getHeight()))
                .setScale(0, java.math.RoundingMode.FLOOR)
                .longValue();
    }

    @Override
    public boolean contains(int x, int y, int z) {
        return y >= minY && y <= maxY && containsColumn(x, z);
    }

    private boolean containsColumn(int x, int z) {
        double dx = (x + 0.5 - center.x()) / (radiusX + 0.5);
        double dz = (z + 0.5 - center.z()) / (radiusZ + 0.5);
        return dx * dx + dz * dz <= 1.0;
    }

    /**
     * WorldEdit's expansion: the centre moves by half the horizontal amounts,
     * which therefore have to be even, each radius grows by half of the amount
     * along it, and a vertical amount moves the top or the bottom.
     */
    @Override
    public boolean expand(BlockVector3 amount) {
        center = center.add(half(amount));
        radiusX += Math.abs(amount.x()) / 2;
        radiusZ += Math.abs(amount.z()) / 2;
        if (amount.y() > 0) {
            maxY += amount.y();
        } else {
            minY += amount.y();
        }
        return !amount.equals(BlockVector3.ZERO);
    }

    /**
     * The reverse of {@link #expand}: the radii keep WorldEdit's floor of one
     * block, and the height never goes past the opposite side.
     */
    @Override
    public boolean contract(BlockVector3 amount) {
        center = center.subtract(half(amount));
        radiusX = Math.max(1, radiusX - Math.abs(amount.x()) / 2);
        radiusZ = Math.max(1, radiusZ - Math.abs(amount.z()) / 2);
        int height = maxY - minY;
        if (amount.y() > 0) {
            minY += Math.min(height, amount.y());
        } else {
            maxY += Math.max(-height, amount.y());
        }
        return !amount.equals(BlockVector3.ZERO);
    }

    private static Vector2 half(BlockVector3 amount) {
        if ((amount.x() & 1) != 0 || (amount.z() & 1) != 0) {
            throw new com.maxlananas.fawebim.core.util.InputException(
                    "A cylinder grows and shrinks by an even amount on each horizontal axis");
        }
        return new Vector2(amount.x() / 2, amount.z() / 2);
    }

    @Override
    public boolean shift(BlockVector3 amount) {
        center = center.add(new Vector2(amount.x(), amount.z()));
        minY += amount.y();
        maxY += amount.y();
        return !amount.equals(BlockVector3.ZERO);
    }

    private void fillColumns(int baseX, int baseZ, int[] lo, int[] hi) {
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int column = z << 4 | x;
                if (containsColumn(baseX + x, baseZ + z)) {
                    lo[column] = minY;
                    hi[column] = maxY;
                } else {
                    lo[column] = 1;
                    hi[column] = 0;
                }
            }
        }
    }

    @Override
    public long forEachPosition(BlockVisitor visitor) {
        BlockVector3 min = getMinimumPoint();
        BlockVector3 max = getMaximumPoint();
        return ColumnWalk.walk(min.x(), min.z(), max.x(), max.z(), this::fillColumns, visitor);
    }

    @Override
    public Iterator<BlockVector3> iterator() {
        BlockVector3 min = getMinimumPoint();
        BlockVector3 max = getMaximumPoint();
        return ColumnWalk.iterator(min.x(), min.z(), max.x(), max.z(), this::fillColumns);
    }

    @Override
    public String describe() {
        return "cylinder (" + BlockVector2.at((int) center.x(), (int) center.z()) + " r=" + radiusX + "," + radiusZ
                + " y=" + minY + ".." + maxY + ")";
    }
}
