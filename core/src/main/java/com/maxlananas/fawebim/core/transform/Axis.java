package com.maxlananas.fawebim.core.transform;

/** The three axes transforms operate on. */
public enum Axis {

    X, Y, Z;

    /** Parses an axis from a direction name or a single letter. */
    public static Axis parse(String input) {
        return switch (input.toLowerCase(java.util.Locale.ROOT)) {
            case "x", "east", "west" -> X;
            case "y", "up", "down" -> Y;
            case "z", "north", "south" -> Z;
            default -> throw new IllegalArgumentException("Unknown axis: " + input);
        };
    }
}
