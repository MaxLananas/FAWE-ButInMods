package com.maxlananas.fawebim.core.transform;

import com.maxlananas.fawebim.core.math.Vector3;
import com.maxlananas.fawebim.core.world.BlockState;
import com.maxlananas.fawebim.core.world.BlockStateRegistry;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns block states the way a transform turns the blocks' positions: a
 * rotated or mirrored paste has its stairs, logs, doors, rails and signs face
 * the way the build now does, as WorldEdit's {@code BlockTransformExtent}
 * does it.
 *
 * <p>The state is read through its properties, so the rules hold for every
 * block of the game and of mods that name their properties the same way:</p>
 * <ul>
 *   <li>a property whose values are directions ({@code facing},
 *       {@code vertical_direction}) points where its direction now points,
 *       or the nearest value it allows;</li>
 *   <li>{@code axis}, and the sixteen steps of {@code rotation} (signs,
 *       banners, heads) turn with the transform;</li>
 *   <li>properties named after a direction ({@code north} of a fence, a wall,
 *       redstone, vines, mushroom blocks) trade values the way the directions
 *       trade places;</li>
 *   <li>a mirror swaps {@code left} and {@code right} (stair shapes, door
 *       hinges, double chests), and turning upside down swaps {@code top} and
 *       {@code bottom}, {@code upper} and {@code lower}, {@code floor} and
 *       {@code ceiling}, and whether a lantern hangs;</li>
 *   <li>rail shapes and the {@code orientation} of jigsaws and crafters turn
 *       each of the directions they are made of.</li>
 * </ul>
 * <p>A value the block does not allow - a hopper turned to face up - leaves
 * that property as it was. Each state is worked out once per transform.</p>
 */
public final class BlockStateTransform {

    private static final String[] NAMES = {"north", "east", "south", "west", "up", "down"};
    private static final double[][] VECTORS = {{0, 0, -1}, {1, 0, 0}, {0, 0, 1}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}};
    private static final int UP = 4;

    private final BlockStateRegistry registry;
    private final Transform transform;
    /** Where each of the six directions points once transformed. */
    private final double[][] turned = new double[6][];
    /** For each direction, the direction it becomes, or {@code -1} when it lands between two. */
    private final int[] becomes = new int[6];
    private final boolean mirrored;
    private final boolean upsideDown;
    /** State to turned state, {@code -1} until worked out. */
    private final int[] cache;

    private BlockStateTransform(Transform transform) {
        this.registry = BlockState.registry();
        this.transform = transform;
        for (int i = 0; i < 6; i++) {
            Vector3 v = transform.applyDirection(new Vector3(VECTORS[i][0], VECTORS[i][1], VECTORS[i][2]));
            turned[i] = new double[]{v.x(), v.y(), v.z()};
            becomes[i] = exactDirection(turned[i]);
        }
        double[] east = turned[1];
        double[] south = turned[2];
        mirrored = east[0] * south[2] - east[2] * south[0] < 0;
        upsideDown = turned[UP][1] < 0;
        cache = new int[Math.max(1, registry.stateCount())];
        Arrays.fill(cache, -1);
    }

    /**
     * The state transform of a transform, or {@code null} when it turns no
     * direction and so changes no state: an identity, an offset, a scale.
     */
    public static BlockStateTransform of(Transform transform) {
        if (transform == null || transform.isIdentity()) {
            return null;
        }
        BlockStateTransform states = new BlockStateTransform(transform);
        for (int i = 0; i < 6; i++) {
            if (states.becomes[i] != i) {
                return states;
            }
        }
        return null;
    }

    /** The state turned as the transform turns the blocks. */
    public int apply(int state) {
        if (state < 0 || state >= cache.length) {
            return state;
        }
        int known = cache[state];
        if (known < 0) {
            known = compute(state);
            cache[state] = known;
        }
        return known;
    }

    private int compute(int state) {
        Map<String, String> properties = registry.properties(state);
        if (properties.isEmpty()) {
            return state;
        }
        Map<String, List<String>> definitions = registry.propertyDefs(state);
        Map<String, String> next = new HashMap<>();
        for (Map.Entry<String, String> property : properties.entrySet()) {
            String name = property.getKey();
            if (indexOf(name) >= 0) {
                continue;
            }
            String value = property.getValue();
            List<String> allowed = definitions.getOrDefault(name, List.of());
            String turnedValue = turn(name, value, allowed);
            if (turnedValue != null && !turnedValue.equals(value) && allowed.contains(turnedValue)) {
                next.put(name, turnedValue);
            }
        }
        // Connections: the value of each side moves to the side its direction
        // became. A side that receives nothing - its source is not a property
        // of the block - keeps its value.
        for (int i = 0; i < 6; i++) {
            int target = becomes[i];
            if (target >= 0 && target != i && properties.containsKey(NAMES[i]) && properties.containsKey(NAMES[target])) {
                next.put(NAMES[target], properties.get(NAMES[i]));
            }
        }
        int result = state;
        for (Map.Entry<String, String> change : next.entrySet()) {
            if (change.getValue().equals(registry.properties(result).get(change.getKey()))) {
                continue;
            }
            int changed = registry.withProperty(result, change.getKey(), change.getValue());
            if (changed >= 0) {
                result = changed;
            }
        }
        return result;
    }

    /** The turned value of a property that is not a connection, or {@code null} to keep it. */
    private String turn(String name, String value, List<String> allowed) {
        if (allDirections(allowed)) {
            int direction = indexOf(value);
            return direction < 0 ? null : nearest(turned[direction], allowed);
        }
        switch (name) {
            case "axis" -> {
                int axis = "xyz".indexOf(value);
                if (axis < 0 || value.length() != 1) {
                    return null;
                }
                double[] v = transform(axis == 0 ? VECTORS[1] : axis == 1 ? VECTORS[UP] : VECTORS[2]);
                double ax = Math.abs(v[0]);
                double ay = Math.abs(v[1]);
                double az = Math.abs(v[2]);
                return ax >= ay && ax >= az ? "x" : ay >= az ? "y" : "z";
            }
            case "rotation" -> {
                return rotation(value);
            }
            case "shape" -> {
                return value.contains("left") || value.contains("right") ? mirror(value) : railShape(value, allowed);
            }
            case "orientation" -> {
                int split = value.indexOf('_');
                if (split < 0) {
                    return null;
                }
                int front = indexOf(value.substring(0, split));
                int top = indexOf(value.substring(split + 1));
                if (front < 0 || top < 0) {
                    return null;
                }
                String frontName = nearest(turned[front], List.of(NAMES));
                String topName = nearest(turned[top], List.of(NAMES));
                if (frontName == null || topName == null) {
                    return null;
                }
                String turnedValue = frontName + "_" + topName;
                if (allowed.contains(turnedValue)) {
                    return turnedValue;
                }
                // A jigsaw facing sideways has its top up: turned upside down
                // it keeps its front, which is the side that matters.
                for (String candidate : allowed) {
                    if (candidate.startsWith(frontName + "_")) {
                        return candidate;
                    }
                }
                return null;
            }
            case "hanging" -> {
                return upsideDown ? String.valueOf(!Boolean.parseBoolean(value)) : null;
            }
            default -> {
                // hinge, the type of a double chest, the half of stairs and
                // doors, the type of a slab, the face of a button, a bell's
                // attachment: sides and ends that a mirror or a flip swaps.
                if (value.equals("left") || value.equals("right")) {
                    return mirror(value);
                }
                if (!upsideDown) {
                    return null;
                }
                return switch (value) {
                    case "top" -> "bottom";
                    case "bottom" -> "top";
                    case "upper" -> "lower";
                    case "lower" -> "upper";
                    case "floor" -> "ceiling";
                    case "ceiling" -> "floor";
                    default -> null;
                };
            }
        }
    }

    private String mirror(String value) {
        if (!mirrored) {
            return null;
        }
        if (value.contains("left")) {
            return value.replace("left", "right");
        }
        return value.replace("right", "left");
    }

    /** The sixteen steps of a sign or a banner, 0 facing south and going clockwise from above. */
    private String rotation(String value) {
        int step;
        try {
            step = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
        double angle = step * Math.PI / 8;
        double[] v = transform(new double[]{-Math.sin(angle), 0, Math.cos(angle)});
        if (Math.abs(v[0]) < 1e-9 && Math.abs(v[2]) < 1e-9) {
            return null;
        }
        long turnedStep = Math.round(Math.atan2(-v[0], v[2]) / (Math.PI / 8));
        return String.valueOf(Math.floorMod(turnedStep, 16));
    }

    /** {@code north_south}, {@code ascending_east}, {@code south_west}: each direction turned. */
    private String railShape(String value, List<String> allowed) {
        boolean ascending = value.startsWith("ascending_");
        String[] parts = ascending ? new String[]{value.substring("ascending_".length())} : value.split("_");
        int[] directions = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            int direction = indexOf(parts[i]);
            if (direction < 0 || direction >= UP) {
                return null;
            }
            String turnedName = nearest(turned[direction], List.of("north", "east", "south", "west"));
            if (turnedName == null) {
                return null;
            }
            directions[i] = indexOf(turnedName);
        }
        if (ascending) {
            return "ascending_" + NAMES[directions[0]];
        }
        if (directions.length != 2) {
            return null;
        }
        boolean firstNorthSouth = directions[0] % 2 == 0;
        boolean secondNorthSouth = directions[1] % 2 == 0;
        if (firstNorthSouth && secondNorthSouth) {
            return "north_south";
        }
        if (!firstNorthSouth && !secondNorthSouth) {
            return "east_west";
        }
        String northSouth = NAMES[firstNorthSouth ? directions[0] : directions[1]];
        String eastWest = NAMES[firstNorthSouth ? directions[1] : directions[0]];
        String shape = northSouth + "_" + eastWest;
        return allowed.contains(shape) ? shape : null;
    }

    private double[] transform(double[] direction) {
        Vector3 v = transform.applyDirection(new Vector3(direction[0], direction[1], direction[2]));
        return new double[]{v.x(), v.y(), v.z()};
    }

    /** The allowed direction closest to a vector, or {@code null} when none points its way at all. */
    private static String nearest(double[] vector, List<String> allowed) {
        String best = null;
        double bestDot = 1e-9;
        for (String candidate : allowed) {
            int direction = indexOf(candidate);
            if (direction < 0) {
                continue;
            }
            double[] axis = VECTORS[direction];
            double dot = vector[0] * axis[0] + vector[1] * axis[1] + vector[2] * axis[2];
            if (dot > bestDot) {
                bestDot = dot;
                best = candidate;
            }
        }
        return best;
    }

    /** The direction a vector is, when it is one of the six, else {@code -1}. */
    private static int exactDirection(double[] vector) {
        double length = Math.sqrt(vector[0] * vector[0] + vector[1] * vector[1] + vector[2] * vector[2]);
        if (length < 1e-9) {
            return -1;
        }
        for (int i = 0; i < 6; i++) {
            double dot = (vector[0] * VECTORS[i][0] + vector[1] * VECTORS[i][1] + vector[2] * VECTORS[i][2]) / length;
            if (dot > 1 - 1e-6) {
                return i;
            }
        }
        return -1;
    }

    private static boolean allDirections(List<String> values) {
        if (values.isEmpty()) {
            return false;
        }
        for (String value : values) {
            if (indexOf(value) < 0) {
                return false;
            }
        }
        return true;
    }

    private static int indexOf(String name) {
        for (int i = 0; i < NAMES.length; i++) {
            if (NAMES[i].equals(name)) {
                return i;
            }
        }
        return -1;
    }
}
