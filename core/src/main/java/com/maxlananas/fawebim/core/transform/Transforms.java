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

    /** Rotation around an axis by an angle in degrees. */
    public static Transform rotate(BlockVector3 origin, Axis axis, double degrees) {
        double radians = Math.toRadians(degrees);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
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

    /** A transform with a random per-block offset. */
    public static Transform randomOffset(int dx, int dy, int dz) {
        Random random = new Random();
        return point -> new Vector3(
                point.x() + (dx == 0 ? 0 : random.nextInt(dx * 2 + 1) - dx),
                point.y() + (dy == 0 ? 0 : random.nextInt(dy * 2 + 1) - dy),
                point.z() + (dz == 0 ? 0 : random.nextInt(dz * 2 + 1) - dz));
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
