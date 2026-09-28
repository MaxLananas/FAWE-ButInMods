package com.maxlananas.fawebim.core.util;

/**
 * The look of everything the mod writes in chat: the name in front of an
 * answer, the gradient it is drawn in, and the colour of every part of a line.
 *
 * <p>This is the one place to change the mod's identity. {@link Msg} builds
 * every line from these values and nothing else, and the commands only say what
 * kind of line they write - a result, an error, an item of a list - so a change
 * here reaches every answer of every command at once.</p>
 *
 * <p>The palette is deliberately small: one gradient, on the name at the start
 * of a line, and one colour per role after it. A line is told apart by the
 * colour of its marker and its text - grey for information, green for a
 * success, gold for a warning, red for an error - and the values in it, the
 * numbers, coordinates and names a reader looks for, always take the same
 * colours.</p>
 */
public final class Theme {

    /** The name in front of every answer, drawn in the brand gradient; empty for none. */
    public static final String TAG = "FAWE";
    /** The two ends of the brand gradient. */
    public static final int BRAND_FROM = 0x8FE3FF;
    public static final int BRAND_TO = 0x6C9BFF;
    /** The separator between the name and the line; its colour is the kind of line. */
    public static final String MARKER = "\u00bb";

    /** Sentences. */
    public static final String TEXT = "\u00a77";
    /** Names and quoted text, the values of a key. */
    public static final String STRONG = "\u00a7f";
    /** Separators, bullets and rules. */
    public static final String MUTED = "\u00a78";
    /** Counts, sizes, coordinates, durations. */
    public static final String NUMBER = "\u00a7b";
    /** A {@code -switch} of a command line. */
    public static final String FLAG = "\u00a7e";
    /** A {@code #mask}, {@code #pattern} or category name. */
    public static final String NAME = "\u00a7d";
    /** The label of a result, the key of a value and a heading: the brand's own colour. */
    public static final String LABEL = hex(BRAND_TO);
    public static final String SUCCESS = "\u00a7a";
    public static final String WARNING = "\u00a76";
    public static final String ERROR = "\u00a7c";

    private Theme() {
    }

    /** A colour in the {@code §x§R§R§G§G§B§B} form. */
    public static String hex(int rgb) {
        StringBuilder out = new StringBuilder(14).append("\u00a7x");
        for (int shift = 20; shift >= 0; shift -= 4) {
            out.append('\u00a7').append(Character.forDigit((rgb >> shift) & 0xF, 16));
        }
        return out.toString();
    }
}
