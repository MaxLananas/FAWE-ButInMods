package com.maxlananas.fawebim.core.transform;

import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.math.Vector3;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Factory helpers for the transforms FAWE supports. */
public final class Transforms {

    private Transforms() {
    }

    /**
     * Rotation around an axis by an angle in degrees.
     *
     * <p>A quarter turn is exact. {@code Math.cos} of 90 degrees is 6e-17, not
     * 0, so a block at a negative offset landed at -1e-16 and was floored one
     * block over: a rotated paste put two blocks in one place and left a hole
     * next to it.</p>
     */
    public static Transform rotate(BlockVector3 origin, Axis axis, double degrees) {
        double cos = cos(degrees);
        double sin = sin(degrees);
        return point -> {
            double x = point.x() - origin.x();
            double y = point.y() - origin.y();
            double z = point.z() - origin.z();
            double nx;
            double ny;
            double nz;
            switch (axis) {
                case X -> {
                    nx = x;
                    ny = y * cos - z * sin;
                    nz = y * sin + z * cos;
                }
                case Y -> {
                    nx = x * cos + z * sin;
                    ny = y;
                    nz = -x * sin + z * cos;
                }
                default -> {
                    nx = x * cos - y * sin;
                    ny = x * sin + y * cos;
                    nz = z;
                }
            }
            return new Vector3(nx + origin.x(), ny + origin.y(), nz + origin.z());
        };
    }

    public static Transform rotate(BlockVector3 origin, double degrees) {
        return rotate(origin, Axis.Y, degrees);
    }

    /** The cosine of an angle in degrees, exact at every multiple of 90. */
    static double cos(double degrees) {
        double turn = degrees % 360;
        if (turn % 90 == 0) {
            int quarter = (int) Math.floorMod((long) (turn / 90), 4L);
            return quarter == 0 ? 1 : quarter == 2 ? -1 : 0;
        }
        return Math.cos(Math.toRadians(degrees));
    }

    /** The sine of an angle in degrees, exact at every multiple of 90. */
    static double sin(double degrees) {
        double turn = degrees % 360;
        if (turn % 90 == 0) {
            int quarter = (int) Math.floorMod((long) (turn / 90), 4L);
            return quarter == 1 ? 1 : quarter == 3 ? -1 : 0;
        }
        return Math.sin(Math.toRadians(degrees));
    }

    /** Mirroring across an axis. */
    public static Transform flip(BlockVector3 origin, Axis axis) {
        return point -> {
            double x = point.x() - origin.x();
            double y = point.y() - origin.y();
            double z = point.z() - origin.z();
            return switch (axis) {
                case X -> new Vector3(-x + origin.x(), y + origin.y(), z + origin.z());
                case Y -> new Vector3(x + origin.x(), -y + origin.y(), z + origin.z());
                default -> new Vector3(x + origin.x(), y + origin.y(), -z + origin.z());
            };
        };
    }

    public static Transform scale(BlockVector3 origin, double x, double y, double z) {
        return point -> new Vector3(
                (point.x() - origin.x()) * x + origin.x(),
                (point.y() - origin.y()) * y + origin.y(),
                (point.z() - origin.z()) * z + origin.z());
    }

    public static Transform offset(double dx, double dy, double dz) {
        return point -> point.add(dx, dy, dz);
    }

    /** A transform with a random per-block offset; it moves blocks and turns none. */
    public static Transform randomOffset(int dx, int dy, int dz) {
        Random random = new Random();
        return new Transform() {
            @Override
            public Vector3 apply(Vector3 point) {
                return new Vector3(
                        point.x() + (dx == 0 ? 0 : random.nextInt(dx * 2 + 1) - dx),
                        point.y() + (dy == 0 ? 0 : random.nextInt(dy * 2 + 1) - dy),
                        point.z() + (dz == 0 ? 0 : random.nextInt(dz * 2 + 1) - dz));
            }

            @Override
            public Vector3 applyDirection(Vector3 direction) {
                return direction;
            }
        };
    }

    /** A chain of transforms, applied in order. */
    public static final class Set implements Transform {

        private final List<Transform> transforms = new ArrayList<>();

        public Set add(Transform transform) {
            transforms.add(transform);
            return this;
        }

        public List<Transform> transforms() {
            return transforms;
        }

        public boolean isEmpty() {
            return transforms.isEmpty();
        }

        @Override
        public Vector3 apply(Vector3 point) {
            Vector3 result = point;
            for (Transform transform : transforms) {
                result = transform.apply(result);
            }
            return result;
        }

        @Override
        public Vector3 applyDirection(Vector3 direction) {
            Vector3 result = direction;
            for (Transform transform : transforms) {
                result = transform.applyDirection(result);
            }
            return result;
        }

        @Override
        public boolean isIdentity() {
            for (Transform transform : transforms) {
                if (!transform.isIdentity()) {
                    return false;
                }
            }
            return true;
        }
    }
}
