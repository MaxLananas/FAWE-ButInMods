package com.fawebutinmods.core.math;

/** An immutable double-precision vector, mirroring WorldEdit's {@code Vector3}. */
public record Vector3(double x, double y, double z) {

    public static final Vector3 ZERO = new Vector3(0, 0, 0);
    public static final Vector3 ONE = new Vector3(1, 1, 1);

    public static Vector3 at(double x, double y, double z) {
        return new Vector3(x, y, z);
    }

    public Vector3 add(Vector3 o) {
        return new Vector3(x + o.x, y + o.y, z + o.z);
    }

    public Vector3 add(double dx, double dy, double dz) {
        return new Vector3(x + dx, y + dy, z + dz);
    }

    public Vector3 subtract(Vector3 o) {
        return new Vector3(x - o.x, y - o.y, z - o.z);
    }

    public Vector3 multiply(double n) {
        return new Vector3(x * n, y * n, z * n);
    }

    public Vector3 multiply(Vector3 o) {
        return new Vector3(x * o.x, y * o.y, z * o.z);
    }

    public Vector3 divide(double n) {
        return new Vector3(x / n, y / n, z / n);
    }

    public Vector3 withX(double nx) {
        return new Vector3(nx, y, z);
    }

    public Vector3 withY(double ny) {
        return new Vector3(x, ny, z);
    }

    public Vector3 withZ(double nz) {
        return new Vector3(x, y, nz);
    }

    public double lengthSq() {
        return x * x + y * y + z * z;
    }

    public double length() {
        return Math.sqrt(lengthSq());
    }

    public double distance(Vector3 o) {
        return subtract(o).length();
    }

    public Vector3 normalize() {
        double len = length();
        return len == 0 ? ZERO : divide(len);
    }

    public double dot(Vector3 o) {
        return x * o.x + y * o.y + z * o.z;
    }

    public Vector3 cross(Vector3 o) {
        return new Vector3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x);
    }

    public BlockVector3 toBlockPoint() {
        return BlockVector3.floor(this);
    }

    /** Applies a rotation of {@code yaw}/{@code pitch} degrees, matching Minecraft's rotation order. */
    public Vector3 rotateYawPitch(double yawDeg, double pitchDeg) {
        double yaw = Math.toRadians(yawDeg);
        double pitch = Math.toRadians(pitchDeg);
        double cy = Math.cos(yaw);
        double sy = Math.sin(yaw);
        double cp = Math.cos(pitch);
        double sp = Math.sin(pitch);
        // -Z is "north"/forward in Minecraft.
        return new Vector3(x * cy + z * sy, y * cp - z * sp * cy - x * sp * sy, -x * sy + z * cy);
    }

    @Override
    public String toString() {
        return "(" + x + ", " + y + ", " + z + ")";
    }
}
