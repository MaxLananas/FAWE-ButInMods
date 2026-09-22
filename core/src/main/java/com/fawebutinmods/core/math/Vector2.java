package com.fawebutinmods.core.math;

/** An immutable double-precision (x, z) vector. */
public record Vector2(double x, double z) {

    public static final Vector2 ZERO = new Vector2(0, 0);

    public static Vector2 at(double x, double z) {
        return new Vector2(x, z);
    }

    public Vector2 add(Vector2 o) {
        return new Vector2(x + o.x, z + o.z);
    }

    public Vector2 subtract(Vector2 o) {
        return new Vector2(x - o.x, z - o.z);
    }

    public Vector2 multiply(double n) {
        return new Vector2(x * n, z * n);
    }

    public double length() {
        return Math.sqrt(x * x + z * z);
    }

    public Vector2 normalize() {
        double len = length();
        return len == 0 ? ZERO : multiply(1 / len);
    }

    public BlockVector2 toBlockVector2() {
        return new BlockVector2((int) Math.floor(x), (int) Math.floor(z));
    }
}
