package com.maxlananas.fawebim.core.transform;

import com.maxlananas.fawebim.core.math.Vector3;
import com.maxlananas.fawebim.core.util.NbtCompound;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the data of an entity the way a transform turns a build: where a mob
 * looks, the wall an item frame or a painting hangs on. The position itself is
 * the paste's business.
 */
public final class EntityTransforms {

    /** Directions by the game's 3D data value: down, up, north, south, west, east. */
    private static final double[][] BY_3D_VALUE = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};
    /** Horizontal directions by the game's 2D data value: south, west, north, east. */
    private static final double[][] BY_2D_VALUE = {{0, 0, 1}, {-1, 0, 0}, {0, 0, -1}, {1, 0, 0}};

    private EntityTransforms() {
    }

    /**
     * A copy of the data with its facing turned: the yaw of {@code Rotation},
     * the {@code Facing} of an item frame with the {@code ItemRotation} of what
     * it holds, and the {@code facing} of a painting.
     */
    public static NbtCompound turn(NbtCompound nbt, Transform transform) {
        if (nbt == null) {
            return null;
        }
        NbtCompound turned = nbt.clone();
        if (turned.get("Rotation") instanceof List<?> rotation && rotation.size() == 2
                && rotation.get(0) instanceof Float yaw && rotation.get(1) instanceof Float pitch) {
            double radians = Math.toRadians(yaw);
            Vector3 look = transform.applyDirection(new Vector3(-Math.sin(radians), 0, Math.cos(radians)));
            if (Math.abs(look.x()) > 1e-9 || Math.abs(look.z()) > 1e-9) {
                float turnedYaw = (float) Math.toDegrees(Math.atan2(-look.x(), look.z()));
                List<Object> values = new ArrayList<>(2);
                values.add(turnedYaw);
                values.add(pitch);
                turned.putList("Rotation", values);
            }
        }
        if (turned.get("Facing") instanceof Byte facing && facing >= 0 && facing < BY_3D_VALUE.length) {
            int newFacing = nearest(transform, BY_3D_VALUE[facing], BY_3D_VALUE, facing);
            turned.putByte("Facing", newFacing);
            if (turned.get("ItemRotation") instanceof Byte rotation) {
                boolean map = turned.getCompoundOrNull("Item") instanceof NbtCompound item
                        && "minecraft:filled_map".equals(item.getString("id", null));
                turned.putByte("ItemRotation", itemRotation(transform, facing, newFacing, rotation, map ? 4 : 8));
            }
        }
        if (turned.get("facing") instanceof Byte facing && facing >= 0 && facing < BY_2D_VALUE.length) {
            turned.putByte("facing", nearest(transform, BY_2D_VALUE[facing], BY_2D_VALUE, facing));
        }
        return turned;
    }

    /**
     * The {@code ItemRotation} of an item frame after the transform, as
     * WorldEdit computes it: the direction the top of the item points to,
     * tilted by the rotation around the way the frame faces, is turned, then
     * measured again against the frame's new facing. A map turns in quarter
     * turns, anything else in eighths.
     */
    private static int itemRotation(Transform transform, int facing, int newFacing, int rotation, int steps) {
        double step = 2 * Math.PI / steps;
        Vector3 top = rotate(itemBase(facing), vector(facing), -rotation * step);
        Vector3 turnedTop = transform.applyDirection(top);
        Vector3 base = itemBase(newFacing);
        double angle = Math.atan2(vector(newFacing).dot(base.cross(turnedTop)), base.dot(turnedTop));
        return Math.floorMod((int) Math.round(-angle / step), steps);
    }

    /**
     * Where the game draws the top of an unrotated item in a frame facing that
     * way: up the wall, north on a floor, south on a ceiling. WorldEdit counts
     * a ceiling frame from north on the way in and from south on the way out,
     * so each paste that turns one also turns its item half a turn; both ends
     * count from south here.
     */
    private static Vector3 itemBase(int facing) {
        return switch (facing) {
            case 0 -> new Vector3(0, 0, 1);
            case 1 -> new Vector3(0, 0, -1);
            default -> new Vector3(0, 1, 0);
        };
    }

    private static Vector3 vector(int facing) {
        double[] d = BY_3D_VALUE[facing];
        return new Vector3(d[0], d[1], d[2]);
    }

    /** {@code v} turned by {@code angle} radians around {@code axis}, by Rodrigues' formula. */
    private static Vector3 rotate(Vector3 v, Vector3 axis, double angle) {
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        return v.multiply(cos).add(axis.cross(v).multiply(sin)).add(axis.multiply(axis.dot(v) * (1 - cos)));
    }

    /** The value of the direction closest to where {@code direction} turns, or {@code fallback}. */
    private static int nearest(Transform transform, double[] direction, double[][] values, int fallback) {
        Vector3 v = transform.applyDirection(new Vector3(direction[0], direction[1], direction[2]));
        int best = fallback;
        double bestDot = 1e-9;
        for (int i = 0; i < values.length; i++) {
            double dot = v.x() * values[i][0] + v.y() * values[i][1] + v.z() * values[i][2];
            if (dot > bestDot) {
                bestDot = dot;
                best = i;
            }
        }
        return best;
    }
}
