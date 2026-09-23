package com.maxlananas.fawebim.core.math;

/** An immutable (x, z) column position. */
public record BlockVector2(int x, int z) implements Comparable<BlockVector2> {

    public static final BlockVector2 ZERO = new BlockVector2(0, 0);

    public static BlockVector2 at(int x, int z) {
        return new BlockVector2(x, z);
    }

    public BlockVector3 toBlockVector3(int y) {
        return new BlockVector3(x, y, z);
    }

    public BlockVector2 add(BlockVector2 o) {
        return new BlockVector2(x + o.x, z + o.z);
    }

    public BlockVector2 subtract(BlockVector2 o) {
        return new BlockVector2(x - o.x, z - o.z);
    }

    public Vector2 toVector2() {
        return new Vector2(x, z);
    }

    public double distance(BlockVector2 o) {
        int dx = x - o.x;
        int dz = z - o.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    @Override
    public int compareTo(BlockVector2 o) {
        int c = Integer.compare(x, o.x);
        return c != 0 ? c : Integer.compare(z, o.z);
    }

    @Override
    public String toString() {
        return "(" + x + ", " + z + ")";
    }
}
