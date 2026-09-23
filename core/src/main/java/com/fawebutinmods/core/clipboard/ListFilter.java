package com.fawebutinmods.core.clipboard;

import java.util.Locale;

/**
 * The filter {@code /list} sets for the schematic listings, FAWE's
 * {@code ListFilters}.
 *
 * <p>Schematics live either in the shared directory, which every player sees
 * ({@code global}, {@code public}), or in a player's own directory
 * ({@code local}, {@code private}, {@code me}, {@code mine}). {@code all} lists
 * both.</p>
 */
public enum ListFilter {

    ALL,
    GLOBAL,
    LOCAL;

    /** Parses one of the filter names, or null when the name is unknown. */
    public static ListFilter parse(String input) {
        return switch (input.toLowerCase(Locale.ROOT)) {
            case "all" -> ALL;
            case "global", "public" -> GLOBAL;
            case "local", "private", "me", "mine" -> LOCAL;
            default -> null;
        };
    }

    public String describe() {
        return switch (this) {
            case ALL -> "all schematics";
            case GLOBAL -> "shared schematics";
            case LOCAL -> "your own schematics";
        };
    }
}
