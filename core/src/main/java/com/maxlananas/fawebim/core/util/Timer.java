package com.maxlananas.fawebim.core.util;

import java.util.Locale;

/**
 * Wall clock for the "in 0.42s" part of an answer. A block edit that touches a
 * million positions is over in a blink on a small selection and takes a moment
 * on a big one; saying how long it took is what tells the player whether the
 * size they picked is the one they wanted.
 */
public final class Timer {

    private final long started = System.nanoTime();

    /** The elapsed time, as a short phrase: {@code 812ms}, {@code 1.23s}, {@code 2m 5s}. */
    public String phrase() {
        return phrase((System.nanoTime() - started) / 1e9);
    }

    /** The same phrase for a duration someone else measured, in seconds. */
    public static String phrase(double seconds) {
        if (seconds < 1.0) {
            return String.format(Locale.ROOT, "%.0fms", seconds * 1000.0);
        }
        if (seconds < 60.0) {
            return String.format(Locale.ROOT, "%.2fs", seconds);
        }
        long minutes = (long) (seconds / 60.0);
        return String.format(Locale.ROOT, "%dm %.0fs", minutes, seconds - minutes * 60.0);
    }
}
