package com.maxlananas.fawebim.core.world;

import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.math.Vector3;

import java.util.Locale;

/** The six block directions, matching WorldEdit's ordering and parsing rules. */
public enum Direction {

    NORTH(0, 0, -1),
    EAST(1, 0, 0),
    SOUTH(0, 0, 1),
    WEST(-1, 0, 0),
    UP(0, 1, 0),
    DOWN(0, -1, 0);

    private final int dx;
    private final int dy;
    private final int dz;

    Direction(int dx, int dy, int dz) {
        this.dx = dx;
        this.dy = dy;
        this.dz = dz;
    }

    public BlockVector3 toVector() {
        return new BlockVector3(dx, dy, dz);
    }

    public Vector3 toVector3() {
        return new Vector3(dx, dy, dz);
    }

    public int x() {
        return dx;
    }

    public int y() {
        return dy;
    }

    public int z() {
        return dz;
    }

    public Direction opposite() {
        return switch (this) {
            case NORTH -> SOUTH;
            case SOUTH -> NORTH;
            case EAST -> WEST;
            case WEST -> EAST;
            case UP -> DOWN;
            case DOWN -> UP;
        };
    }

    /** Parses a direction, accepting both names and the classic compass aliases. */
    public static Direction parse(String input) {
        String s = input.toLowerCase(Locale.ROOT);
        return switch (s) {
            case "north", "n" -> NORTH;
            case "south", "s" -> SOUTH;
            case "east", "e" -> EAST;
            case "west", "w" -> WEST;
            case "up", "u" -> UP;
            case "down", "d" -> DOWN;
            default -> throw new IllegalArgumentException("Unknown direction: " + input);
        };
    }
}
