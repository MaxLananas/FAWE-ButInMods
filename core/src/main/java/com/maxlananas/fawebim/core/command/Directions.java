package com.maxlananas.fawebim.core.command;

import com.maxlananas.fawebim.core.actor.Actor;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.math.Vector3;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The direction and offset arguments of WorldEdit's commands, read the way
 * WorldEdit reads them.
 *
 * <p>A direction is a compass point, up or down, by its name or by any start
 * of it - {@code n}, {@code nor} and {@code north} are the same - or a word
 * relative to where the player looks: {@code me} and {@code forward}, {@code
 * back}, {@code left} and {@code right}, each also by its first letter. Where a
 * command takes diagonals, a start of north or south joined to a start of east
 * or west makes one: {@code ne}, {@code southwest}.</p>
 *
 * <p>Where the player looks is WorldEdit's answer as well: down or up when the
 * pitch is steeper than 67.5 degrees, otherwise the nearest of the eight
 * compass points. A command that takes no diagonal refuses one, so {@code
 * //flip} run while looking north-east says so rather than guessing an
 * axis.</p>
 */
public final class Directions {

    public static final BlockVector3 NORTH = new BlockVector3(0, 0, -1);
    public static final BlockVector3 SOUTH = new BlockVector3(0, 0, 1);
    public static final BlockVector3 EAST = new BlockVector3(1, 0, 0);
    public static final BlockVector3 WEST = new BlockVector3(-1, 0, 0);
    public static final BlockVector3 UP = new BlockVector3(0, 1, 0);
    public static final BlockVector3 DOWN = new BlockVector3(0, -1, 0);

    /** What the direction commands suggest, in WorldEdit's order. */
    public static final List<String> SUGGESTIONS = List.of("north", "south", "east", "west", "up", "down",
            "me", "forward", "back", "left", "right");

    /** The same with the four diagonals, for the commands that take them. */
    public static final List<String> DIAGONAL_SUGGESTIONS = List.of("north", "south", "east", "west", "up",
            "down", "me", "forward", "back", "left", "right", "northeast", "northwest", "southeast", "southwest");

    /**
     * The eight compass points by the octant of a yaw, starting at 0 degrees:
     * Minecraft's yaw is 0 facing south and grows towards the west.
     */
    private static final BlockVector3[] OCTANTS = {
            SOUTH, new BlockVector3(-1, 0, 1), WEST, new BlockVector3(-1, 0, -1),
            NORTH, new BlockVector3(1, 0, -1), EAST, new BlockVector3(1, 0, 1)};

    /** Every name and start of a name WorldEdit reads, diagonals included. */
    private static final Map<String, BlockVector3> NAMES = names();

    private Directions() {
    }

    private static Map<String, BlockVector3> names() {
        Map<String, BlockVector3> names = new HashMap<>();
        String[] words = {"north", "south", "east", "west", "up", "down"};
        BlockVector3[] steps = {NORTH, SOUTH, EAST, WEST, UP, DOWN};
        for (int i = 0; i < words.length; i++) {
            for (int length = 1; length <= words[i].length(); length++) {
                names.put(words[i].substring(0, length), steps[i]);
            }
        }
        for (int vertical = 0; vertical < 2; vertical++) {
            for (int side = 2; side < 4; side++) {
                for (int first = 1; first <= words[vertical].length(); first++) {
                    for (int second = 1; second <= words[side].length(); second++) {
                        names.put(words[vertical].substring(0, first) + words[side].substring(0, second),
                                steps[vertical].add(steps[side]));
                    }
                }
            }
        }
        return Map.copyOf(names);
    }

    /**
     * The unit step a direction argument names.
     *
     * @param diagonals whether the command takes the four diagonals
     * @throws com.maxlananas.fawebim.core.util.InputException for a word that
     *         is no direction, a relative one without a player to be relative
     *         to, or a diagonal where the command takes none
     */
    public static BlockVector3 parse(Actor actor, String input, boolean diagonals) {
        String word = input.trim().toLowerCase(Locale.ROOT);
        BlockVector3 step = NAMES.get(word);
        if (step == null) {
            step = switch (word) {
                case "m", "me", "f", "forward" -> relative(actor, input, 0);
                // Back is the opposite of where the player looks: behind them
                // on the level, and up for a player looking down.
                case "b", "back" -> {
                    BlockVector3 behind = relative(actor, input, 180);
                    yield behind.y() != 0 ? behind.multiply(-1) : behind;
                }
                case "l", "left" -> relative(actor, input, -90);
                case "r", "right" -> relative(actor, input, 90);
                default -> throw CommandRegistry.error("Unknown direction '" + input.trim()
                        + "'; use north, south, east, west, up, down, me, back, left or right");
            };
        }
        if (!diagonals && isDiagonal(step)) {
            throw CommandRegistry.error("'" + input.trim() + "' is " + name(step)
                    + ", a diagonal: this command takes north, south, east, west, up or down");
        }
        return step;
    }

    /**
     * WorldEdit's offset argument, which {@code //move} and {@code //stack}
     * take: a direction, diagonals included; an {@code x,y,z} vector; or a
     * vector starting with {@code ^}, whose axes are forward, up and to the
     * side of where the player looks.
     */
    public static BlockVector3 offset(Actor actor, String input) {
        String text = input.trim();
        if (text.startsWith("^")) {
            if (!actor.isPlayer()) {
                throw CommandRegistry.error("Only a player can use an offset relative to where they look");
            }
            return relativeOffset(actor.yaw(), actor.pitch(), vector(text.substring(1), input));
        }
        if (text.indexOf(',') >= 0) {
            return vector(text, input);
        }
        return parse(actor, text, true);
    }

    /** An {@code x,y,z} vector of whole numbers. */
    private static BlockVector3 vector(String text, String input) {
        String[] parts = text.split(",", -1);
        if (parts.length != 3) {
            throw CommandRegistry.error("'" + input.trim() + "' is not an offset: it needs exactly three"
                    + " numbers, x,y,z");
        }
        int[] values = new int[3];
        for (int i = 0; i < 3; i++) {
            try {
                values[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException e) {
                throw CommandRegistry.error("'" + parts[i].trim() + "' in '" + input.trim()
                        + "' is not a whole number");
            }
        }
        return new BlockVector3(values[0], values[1], values[2]);
    }

    /**
     * Where a player looks, as WorldEdit's {@code getCardinalDirection} has it:
     * down or up past 67.5 degrees of pitch, else the nearest of the eight
     * compass points to the yaw turned by {@code yawOffset} degrees.
     */
    public static BlockVector3 looking(double yaw, double pitch, int yawOffset) {
        if (pitch > 67.5) {
            return DOWN;
        }
        if (pitch < -67.5) {
            return UP;
        }
        double rotation = (yaw + yawOffset) % 360;
        if (rotation < 0) {
            rotation += 360;
        }
        return OCTANTS[(int) Math.floor((rotation + 22.5) / 45) & 7];
    }

    private static BlockVector3 relative(Actor actor, String input, int yawOffset) {
        if (!actor.isPlayer()) {
            throw CommandRegistry.error("Only a player can use '" + input.trim()
                    + "': name a direction such as north or up instead");
        }
        return looking(actor.yaw(), actor.pitch(), yawOffset);
    }

    /** True for the four steps that move along two horizontal axes at once. */
    public static boolean isDiagonal(BlockVector3 step) {
        return step.x() != 0 && step.z() != 0;
    }

    /** The name of a unit step, as a player would say it. */
    public static String name(BlockVector3 step) {
        String vertical = step.z() < 0 ? "north" : step.z() > 0 ? "south" : "";
        String side = step.x() > 0 ? "east" : step.x() < 0 ? "west" : "";
        if (!vertical.isEmpty() && !side.isEmpty()) {
            return vertical + "-" + side;
        }
        if (!vertical.isEmpty() || !side.isEmpty()) {
            return vertical + side;
        }
        return step.y() > 0 ? "up" : step.y() < 0 ? "down" : "nowhere";
    }

    /**
     * WorldEdit's {@code ^} offsets: x goes where the player looks, y towards
     * the top of their view and z to the side, each rounded to the nearest
     * block once turned.
     */
    static BlockVector3 relativeOffset(double yaw, double pitch, BlockVector3 relative) {
        double cosYaw = cosDegrees(yaw + 90.0);
        double sinYaw = sinDegrees(yaw + 90.0);
        double cosPitch = cosDegrees(-pitch + 90.0);
        double sinPitch = sinDegrees(-pitch + 90.0);
        double flat = Math.cos(Math.toRadians(pitch));
        Vector3 forward = new Vector3(-flat * Math.sin(Math.toRadians(yaw)), -Math.sin(Math.toRadians(pitch)),
                flat * Math.cos(Math.toRadians(yaw)));
        Vector3 top = new Vector3(cosYaw * cosPitch, sinPitch, sinYaw * cosPitch);
        Vector3 side = forward.cross(top).multiply(-1.0);
        Vector3 turned = forward.multiply(relative.x()).add(top.multiply(relative.y()))
                .add(side.multiply(relative.z()));
        return new BlockVector3((int) Math.round(turned.x()), (int) Math.round(turned.y()),
                (int) Math.round(turned.z()));
    }

    /** The cosine of an angle in degrees, exact on the right angles as WorldEdit's {@code dCos}. */
    private static double cosDegrees(double degrees) {
        int whole = (int) degrees;
        if (degrees == whole && whole % 90 == 0) {
            return switch (Math.floorMod(whole, 360)) {
                case 0 -> 1.0;
                case 180 -> -1.0;
                default -> 0.0;
            };
        }
        return Math.cos(Math.toRadians(degrees));
    }

    /** The sine of an angle in degrees, exact on the right angles as WorldEdit's {@code dSin}. */
    private static double sinDegrees(double degrees) {
        int whole = (int) degrees;
        if (degrees == whole && whole % 90 == 0) {
            return switch (Math.floorMod(whole, 360)) {
                case 90 -> 1.0;
                case 270 -> -1.0;
                default -> 0.0;
            };
        }
        return Math.sin(Math.toRadians(degrees));
    }
}
