package com.maxlananas.fawebim.core.math;

/**
 * An immutable integer block position (x, y, z). Mirrors WorldEdit's
 * {@code BlockVector3} semantics: all arithmetic returns new instances and the
 * container is cheap enough to allocate freely in hot loops.
 */
public record BlockVector3(int x, int y, int z) implements Comparable<BlockVector3> {

    public static final BlockVector3 ZERO = new BlockVector3(0, 0, 0);
    public static final BlockVector3 ONE = new BlockVector3(1, 1, 1);
    public static final BlockVector3 UNIT_X = new BlockVector3(1, 0, 0);
    public static final BlockVector3 UNIT_Y = new BlockVector3(0, 1, 0);
    public static final BlockVector3 UNIT_Z = new BlockVector3(0, 0, 1);

    public static BlockVector3 at(int x, int y, int z) {
        return new BlockVector3(x, y, z);
    }

    public static BlockVector3 floor(Vector3 v) {
        return new BlockVector3((int) Math.floor(v.x()), (int) Math.floor(v.y()), (int) Math.floor(v.z()));
    }

    public static BlockVector3 round(Vector3 v) {
        return new BlockVector3((int) Math.round(v.x()), (int) Math.round(v.y()), (int) Math.round(v.z()));
    }

    public BlockVector3 add(int dx, int dy, int dz) {
        return new BlockVector3(x + dx, y + dy, z + dz);
    }

    public BlockVector3 add(BlockVector3 other) {
        return new BlockVector3(x + other.x, y + other.y, z + other.z);
    }

    public BlockVector3 subtract(BlockVector3 other) {
        return new BlockVector3(x - other.x, y - other.y, z - other.z);
    }

    public BlockVector3 multiply(int n) {
        return new BlockVector3(x * n, y * n, z * n);
    }

    public BlockVector3 withX(int nx) {
        return new BlockVector3(nx, y, z);
    }

    public BlockVector3 withY(int ny) {
        return new BlockVector3(x, ny, z);
    }

    public BlockVector3 withZ(int nz) {
        return new BlockVector3(x, y, nz);
    }

    public BlockVector3 up() {
        return new BlockVector3(x, y + 1, z);
    }

    public BlockVector3 up(int n) {
        return new BlockVector3(x, y + n, z);
    }

    public BlockVector3 down() {
        return new BlockVector3(x, y - 1, z);
    }

    public BlockVector3 down(int n) {
        return new BlockVector3(x, y - n, z);
    }

    public BlockVector3 north() {
        return new BlockVector3(x, y, z - 1);
    }

    public BlockVector3 south() {
        return new BlockVector3(x, y, z + 1);
    }

    public BlockVector3 west() {
        return new BlockVector3(x - 1, y, z);
    }

    public BlockVector3 east() {
        return new BlockVector3(x + 1, y, z);
    }

    public Vector3 toVector3() {
        return new Vector3(x, y, z);
    }

    /** Center of the block, i.e. {@code (x + 0.5, y + 0.5, z + 0.5)}. */
    public Vector3 toCenter() {
        return new Vector3(x + 0.5, y + 0.5, z + 0.5);
    }

    public BlockVector2 toBlockVector2() {
        return new BlockVector2(x, z);
    }

    public int lengthSq() {
        return x * x + y * y + z * z;
    }

    public double length() {
        return Math.sqrt(lengthSq());
    }

    public int distanceSq(BlockVector3 o) {
        int dx = x - o.x;
        int dy = y - o.y;
        int dz = z - o.z;
        return dx * dx + dy * dy + dz * dz;
    }

    public double distance(BlockVector3 o) {
        return Math.sqrt(distanceSq(o));
    }

    public BlockVector3 min(BlockVector3 o) {
        return new BlockVector3(Math.min(x, o.x), Math.min(y, o.y), Math.min(z, o.z));
    }

    public BlockVector3 max(BlockVector3 o) {
        return new BlockVector3(Math.max(x, o.x), Math.max(y, o.y), Math.max(z, o.z));
    }

    public BlockVector3 clamp(BlockVector3 min, BlockVector3 max) {
        return new BlockVector3(
                Math.max(min.x, Math.min(max.x, x)),
                Math.max(min.y, Math.min(max.y, y)),
                Math.max(min.z, Math.min(max.z, z)));
    }

    public BlockBox toBlockBox() {
        return new BlockBox(this);
    }

    public BlockVector3 toBlockPoint() {
        return this;
    }

    @Override
    public int compareTo(BlockVector3 o) {
        int c = Integer.compare(y, o.y);
        if (c != 0) {
            return c;
        }
        c = Integer.compare(x, o.x);
        return c != 0 ? c : Integer.compare(z, o.z);
    }

    @Override
    public String toString() {
        return "(" + x + ", " + y + ", " + z + ")";
    }
}
