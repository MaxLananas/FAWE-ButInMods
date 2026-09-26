package com.maxlananas.fawebim.core.region;

import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.math.Vector3;

import java.util.Iterator;

/**
 * A sphere/ellipsoid region ({@code //sel sphere|ellipsoid}).
 *
 * <p>The radii are the ones a player picks. Like WorldEdit, a block is inside
 * when its centre is within the radius plus half a block of the centre: a
 * sphere of radius 5 reaches the blocks five away along an axis and the ones a
 * little further on the diagonals, which is the rounded ball players know from
 * WorldEdit. Testing the bare radius made every round selection one shell thinner
 * than upstream's.</p>
 */
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
        this.radii = abs(radii);
        this.minY = Math.min(minY, maxY);
        this.maxY = Math.max(minY, maxY);
    }

    private static Vector3 abs(Vector3 radii) {
        return new Vector3(Math.abs(radii.x()), Math.abs(radii.y()), Math.abs(radii.z()));
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
        this.radii = abs(radii);
    }

    public void setYBounds(int minY, int maxY) {
        this.minY = Math.min(minY, maxY);
        this.maxY = Math.max(minY, maxY);
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

    /**
     * The lowest corner of the blocks the shape holds: a block is in when its
     * centre is within the radius plus half a block, so the lowest one is the
     * first whose coordinate reaches {@code center - radius - 1}.
     */
    @Override
    public BlockVector3 getMinimumPoint() {
        return new BlockVector3((int) Math.ceil(center.x() - radii.x() - 1),
                Math.max(minY, (int) Math.ceil(center.y() - radii.y() - 1)),
                (int) Math.ceil(center.z() - radii.z() - 1));
    }

    @Override
    public BlockVector3 getMaximumPoint() {
        return new BlockVector3((int) Math.floor(center.x() + radii.x()),
                Math.min(maxY, (int) Math.floor(center.y() + radii.y())),
                (int) Math.floor(center.z() + radii.z()));
    }

    /** WorldEdit's formula: {@code 4/3 * pi * rX * rY * rZ} over the radii it tests, rounded down. */
    @Override
    public long getVolume() {
        return (long) java.math.BigDecimal.valueOf(4.0 / 3.0 * Math.PI)
                .multiply(java.math.BigDecimal.valueOf(radii.x() + 0.5))
                .multiply(java.math.BigDecimal.valueOf(radii.y() + 0.5))
                .multiply(java.math.BigDecimal.valueOf(radii.z() + 0.5))
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
        double dx = (x + 0.5 - center.x()) / (radii.x() + 0.5);
        double dy = (y + 0.5 - center.y()) / (radii.y() + 0.5);
        double dz = (z + 0.5 - center.z()) / (radii.z() + 0.5);
        return dx * dx + dy * dy + dz * dz <= 1.0;
    }

    /**
     * WorldEdit's expansion: the centre moves by half the sum of the amounts,
     * which therefore has to be even on each axis, and each radius grows by
     * half of the amounts along it. Growing both horizontal radii by the larger
     * amount, whatever its sign, was a different shape from the one asked for.
     */
    @Override
    public boolean expand(BlockVector3 amount) {
        center = center.add(half(amount));
        radii = radii.add(new Vector3(Math.abs(amount.x()) / 2, Math.abs(amount.y()) / 2,
                Math.abs(amount.z()) / 2));
        return !amount.equals(BlockVector3.ZERO);
    }

    /** The reverse of {@link #expand}, with WorldEdit's floor of one block per radius. */
    @Override
    public boolean contract(BlockVector3 amount) {
        center = center.subtract(half(amount));
        radii = new Vector3(Math.max(1, radii.x() - Math.abs(amount.x()) / 2),
                Math.max(1, radii.y() - Math.abs(amount.y()) / 2),
                Math.max(1, radii.z() - Math.abs(amount.z()) / 2));
        return !amount.equals(BlockVector3.ZERO);
    }

    private static Vector3 half(BlockVector3 amount) {
        if ((amount.x() & 1) != 0 || (amount.y() & 1) != 0 || (amount.z() & 1) != 0) {
            throw new com.maxlananas.fawebim.core.util.InputException(
                    "A round selection grows and shrinks by an even amount on each axis");
        }
        return new Vector3(amount.x() / 2, amount.y() / 2, amount.z() / 2);
    }

    /**
     * Moves the centre. The Y bounds are the world's, which the shape is clipped
     * to, so they stay where they are.
     */
    @Override
    public boolean shift(BlockVector3 amount) {
        center = center.add(amount.toVector3());
        return !amount.equals(BlockVector3.ZERO);
    }

    /** Fills the Y run of each column, from the same test {@link #contains} makes. */
    private void fillColumns(int baseX, int baseZ, int[] lo, int[] hi) {
        double ry = radii.y() + 0.5;
        for (int z = 0; z < 16; z++) {
            double dz = (baseZ + z + 0.5 - center.z()) / (radii.z() + 0.5);
            for (int x = 0; x < 16; x++) {
                int column = z << 4 | x;
                double dx = (baseX + x + 0.5 - center.x()) / (radii.x() + 0.5);
                double rest = 1.0 - dx * dx - dz * dz;
                if (rest < 0) {
                    lo[column] = 1;
                    hi[column] = 0;
                    continue;
                }
                double reach = ry * Math.sqrt(rest);
                int low = (int) Math.ceil(center.y() - 0.5 - reach);
                int high = (int) Math.floor(center.y() - 0.5 + reach);
                // The rounding of the square root is settled against the test
                // itself, so the walk and contains never disagree on a block.
                int cellX = baseX + x;
                int cellZ = baseZ + z;
                while (low <= high && !contains(cellX, low, cellZ)) {
                    low++;
                }
                while (high >= low && !contains(cellX, high, cellZ)) {
                    high--;
                }
                if (low <= high) {
                    while (contains(cellX, low - 1, cellZ)) {
                        low--;
                    }
                    while (contains(cellX, high + 1, cellZ)) {
                        high++;
                    }
                }
                lo[column] = low;
                hi[column] = high;
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
        return "ellipsoid (" + radii.x() + "x" + radii.y() + "x" + radii.z() + ")";
    }
}
