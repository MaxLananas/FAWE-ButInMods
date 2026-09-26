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
     * the {@code Facing} of an item frame and the {@code facing} of a painting.
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
            turned.putByte("Facing", nearest(transform, BY_3D_VALUE[facing], BY_3D_VALUE, facing));
        }
        if (turned.get("facing") instanceof Byte facing && facing >= 0 && facing < BY_2D_VALUE.length) {
            turned.putByte("facing", nearest(transform, BY_2D_VALUE[facing], BY_2D_VALUE, facing));
        }
        return turned;
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
