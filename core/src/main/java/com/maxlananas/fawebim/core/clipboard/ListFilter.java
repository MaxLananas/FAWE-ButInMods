package com.maxlananas.fawebim.core.clipboard;

import java.util.Locale;

/**
 * The filter {@code /list} sets for the schematic listings, FAWE's filter
 * names: {@code global} and {@code public} for the shared folder, {@code
 * local}, {@code private}, {@code me} and {@code mine} for the player's own,
 * {@code all} for both.
 *
 * <p>A player has a folder of their own in FAWE only with per-player
 * schematics on. This mod keeps every schematic in the shared folder, as FAWE
 * does by default, so the filter is remembered and every listing shows the
 * shared folder.</p>
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
