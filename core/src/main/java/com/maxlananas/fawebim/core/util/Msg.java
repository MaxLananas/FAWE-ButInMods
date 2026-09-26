package com.maxlananas.fawebim.core.util;

import java.util.Locale;

/**
 * A line of chat: plain text plus legacy {@code §} colour codes. The engine
 * formats everything into this form; the Fabric adapter turns it into a chat
 * component and the console strips the codes.
 *
 * <p>Commands say what kind of line they write and this class draws it from
 * the {@link Theme}:</p>
 * <ul>
 *   <li>{@link #result} - what a command did: {@code FAWE » Label: detail},</li>
 *   <li>{@link #title} - the heading of a listing,</li>
 *   <li>{@link #success}, {@link #info}, {@link #warn}, {@link #error} - a
 *       sentence, coloured by what it tells,</li>
 *   <li>{@link #keyValue}, {@link #item}, {@link #hint} - the lines under a
 *       heading, indented and without the name in front, so a listing does not
 *       repeat it on every line.</li>
 * </ul>
 * <p>Inside a line the parts a reader looks for are coloured the same way
 * everywhere - numbers and coordinates, quoted names, switches and {@code #}
 * names - by {@link #highlight}, so a command writes plain sentences.</p>
 */
public final class Msg {

    private final String text;

    private Msg(String text) {
        this.text = text;
    }

    /** The name in front of a line, in the brand gradient; drawn once. */
    private static final String TAG = Theme.TAG.isEmpty() ? ""
            : gradient(Theme.TAG, Theme.BRAND_FROM, Theme.BRAND_TO) + " ";

    /** The start of a line: the name, then the marker in the colour of the kind of line. */
    private static String prefix(String markerColour) {
        return TAG + markerColour + Theme.MARKER + " ";
    }

    /** The start of an information line, for the few lines that are put together by hand. */
    public static final String MARKER = prefix(Theme.MUTED);

    /**
     * A line written by hand. It keeps the colour it opens with, white when it
     * has none, and gets the highlighting of every other line; it has no name
     * in front, which is what a free-form line such as a banner wants.
     */
    public static Msg of(String text) {
        return new Msg(highlight(text, openingColour(text, Theme.STRONG)));
    }

    /** The colour a line written by hand opens with, or a plain default. */
    private static String openingColour(String text, String fallback) {
        int at = text.indexOf('§');
        return at >= 0 ? text.substring(at, Math.min(text.length(), at + codeLength(text, at))) : fallback;
    }

    public static Msg empty() {
        return new Msg("");
    }

    /**
     * The code a fragment ends with to hand the line its own colour back. A line
     * builder replaces it with the colour of the line, so a value can sit in the
     * middle of a sentence without the command knowing what colour the sentence
     * is written in.
     */
    public static final String BACK = "\u00a7r";

    /** A value inside a line - a number, a size, a coordinate - in the value colour. */
    public static Msg value(Object value) {
        return new Msg(Theme.NUMBER + highlight(String.valueOf(value), Theme.NUMBER) + BACK);
    }

    /*
     * A line is highlighted when it is built, and the fragments inside it were
     * highlighted when they were: highlighting a line again leaves it as it is,
     * which is what lets a built line pass through Msg.of on its way to a link.
     */

    /** {@code 12x5x9} - the three sizes of a box, as one value. */
    public static String size(long width, long height, long length) {
        return Theme.NUMBER + width + "x" + height + "x" + length + BACK;
    }

    public static Msg error(String text) {
        return sentence(Theme.ERROR, Theme.ERROR, text);
    }

    public static Msg success(String text) {
        return sentence(Theme.SUCCESS, Theme.SUCCESS, text);
    }

    public static Msg warn(String text) {
        return sentence(Theme.WARNING, Theme.WARNING, text);
    }

    public static Msg info(String text) {
        return sentence(Theme.MUTED, Theme.TEXT, text);
    }

    private static Msg sentence(String marker, String colour, String text) {
        return new Msg(prefix(marker) + colour + highlight(text, colour));
    }

    /** {@code  key: value}, a line of a card under a heading. */
    public static Msg keyValue(String key, Object value) {
        return new Msg("  " + Theme.LABEL + key + Theme.MUTED + ": " + Theme.STRONG
                + highlight(String.valueOf(value), Theme.STRONG));
    }

    /** {@code  - text}, an entry of a list under a heading. */
    public static Msg item(String text) {
        return new Msg("  " + Theme.MUTED + "- " + Theme.STRONG + highlight(text, Theme.STRONG));
    }

    /** {@code  - name: detail}, an entry of a list with something to say about it. */
    public static Msg item(String name, String detail) {
        return new Msg("  " + Theme.MUTED + "- " + Theme.STRONG + highlight(name, Theme.STRONG)
                + Theme.MUTED + ": " + Theme.TEXT + highlight(detail, Theme.TEXT));
    }

    /** A heading inside a listing, such as the group of a page of commands. */
    public static Msg section(String name) {
        return new Msg("  " + Theme.LABEL + "\u00a7l" + name);
    }

    /**
     * {@code  - //name <arguments> - what it does}: a command of a listing, its
     * name first in the value colour, so the row is read by what it is about.
     */
    public static Msg usage(String name, String arguments, String description) {
        return new Msg("  " + Theme.MUTED + "- " + Theme.NUMBER + name
                + (arguments.isEmpty() ? "" : Theme.MUTED + " " + arguments)
                + Theme.MUTED + " - " + Theme.TEXT + description);
    }

    /** An indented line of advice under a heading or a result: what to type next. */
    public static Msg hint(String text) {
        return new Msg("  " + Theme.TEXT + highlight(text, Theme.TEXT));
    }

    /** A horizontal rule, for a banner. */
    public static Msg rule(int width) {
        return new Msg(Theme.MUTED + "\u00a7m" + " ".repeat(Math.max(1, width)));
    }

    /**
     * Colours the parts of a message a reader looks for: numbers and coordinates,
     * names between quotes, switches and {@code #} pattern, mask and category
     * names. The colour in force is put back after each of them, so the line keeps
     * the colour it was built with, and text that already carries codes - a
     * heading, an appended {@link #value} - is left as it is.
     */
    public static String highlight(String text, String role) {
        StringBuilder out = new StringBuilder(text.length() + 24);
        String current = role;
        int index = 0;
        while (index < text.length()) {
            if (text.charAt(index) == '§') {
                int length = codeLength(text, index);
                // A fragment hands the line its colour back with BACK.
                current = length == 2 && text.charAt(index + 1) == 'r' ? role : text.substring(index, index + length);
                out.append(current);
                index += length;
                continue;
            }
            int next = text.indexOf('§', index);
            if (next < 0) {
                next = text.length();
            }
            highlightRun(text.substring(index, next), current, out);
            index = next;
        }
        return out.toString();
    }

    /**
     * The length of the colour code at {@code index}: the {@code §x} form carries
     * six hex digits after it, every other one is a single character.
     */
    private static int codeLength(String text, int index) {
        if (index + 1 < text.length() && text.charAt(index + 1) == 'x' && index + 13 < text.length()) {
            return 14;
        }
        return Math.min(2, text.length() - index);
    }

    private static void highlightRun(String run, String colour, StringBuilder out) {
        int index = 0;
        while (index < run.length()) {
            char c = run.charAt(index);
            if (c == ' ' || c == '\t') {
                out.append(c);
                index++;
                continue;
            }
            if (c == '\'' || c == '"') {
                int close = run.indexOf(c, index + 1);
                if (close > index) {
                    coloured(out, Theme.STRONG, run.substring(index, close + 1), colour);
                    index = close + 1;
                    continue;
                }
            }
            // A coordinate triple is one value to the reader, so the whole
            // "(12, 64, -3)" takes the value colour instead of each number
            // taking it with the separators left behind.
            if (c == '(') {
                int close = coordinateEnd(run, index);
                if (close > index) {
                    coloured(out, Theme.NUMBER, run.substring(index, close + 1), colour);
                    index = close + 1;
                    continue;
                }
            }
            int end = index;
            while (end < run.length() && run.charAt(end) != ' ' && run.charAt(end) != '\t') {
                end++;
            }
            highlightToken(run.substring(index, end), colour, out);
            index = end;
        }
    }

    /** Writes text in a colour and goes back to the line's, without codes that change nothing. */
    private static void coloured(StringBuilder out, String highlight, String text, String colour) {
        if (highlight.equals(colour)) {
            out.append(text);
        } else {
            out.append(highlight).append(text).append(colour);
        }
    }

    /**
     * Colours the value inside a token. A number is coloured without the
     * brackets and the punctuation around it - the {@code 12} of {@code (12,} -
     * while a {@code #name} or a switch keeps its brackets, which are part of
     * it, and only leaves the punctuation of the sentence out.
     */
    private static void highlightToken(String token, String colour, StringBuilder out) {
        int start = 0;
        while (start < token.length() && "([{".indexOf(token.charAt(start)) >= 0) {
            start++;
        }
        int end = token.length();
        while (end > start && ")]},.;:!?".indexOf(token.charAt(end - 1)) >= 0) {
            end--;
        }
        String highlight = null;
        if (end > start && isNumber(token.substring(start, end))) {
            highlight = Theme.NUMBER;
        } else {
            start = 0;
            end = token.length();
            while (end > start && ",.;:!?".indexOf(token.charAt(end - 1)) >= 0) {
                end--;
            }
            if (end > start) {
                highlight = coreColour(token.substring(start, end));
            }
        }
        if (highlight == null || highlight.equals(colour)) {
            out.append(token);
            return;
        }
        out.append(token, 0, start).append(highlight).append(token, start, end).append(colour)
                .append(token, end, token.length());
    }

    /**
     * The index of the bracket that closes a tuple of coordinates.
     *
     * @return the index of the closing parenthesis, or {@code -1} when the run
     *         does not open a coordinate triple
     */
    private static int coordinateEnd(String run, int index) {
        int i = index + 1;
        for (int part = 0; part < 3; part++) {
            int start = i;
            if (i < run.length() && (run.charAt(i) == '-' || run.charAt(i) == '+')) {
                i++;
            }
            while (i < run.length() && Character.isDigit(run.charAt(i))) {
                i++;
            }
            if (i == start) {
                return -1;
            }
            if (part < 2) {
                if (i + 1 >= run.length() || run.charAt(i) != ',' || run.charAt(i + 1) != ' ') {
                    return -1;
                }
                i += 2;
            }
        }
        return i < run.length() && run.charAt(i) == ')' ? i : -1;
    }

    /** What colour a token deserves once its brackets and punctuation are off, if any. */
    private static String coreColour(String core) {
        if (isNumber(core)) {
            return Theme.NUMBER;
        }
        if (core.charAt(0) == '-' && core.length() > 1 && Character.isLetter(core.charAt(1))) {
            return Theme.FLAG;
        }
        if (core.charAt(0) == '#' && core.length() > 1) {
            return Theme.NAME;
        }
        return null;
    }

    /**
     * A count, a coordinate, a percentage or a decimal, optionally signed. A
     * dash on its own is the separator of a sentence, not a number.
     */
    private static boolean isNumber(String token) {
        char first = token.charAt(0);
        if (!Character.isDigit(first) && first != '-' && first != '+') {
            return false;
        }
        boolean digit = Character.isDigit(first);
        for (int i = 1; i < token.length(); i++) {
            char c = token.charAt(i);
            if (Character.isDigit(c)) {
                digit = true;
            } else if (c != ',' && c != '.' && c != '%' && c != ':' && c != '-' && c != '+') {
                return false;
            }
        }
        return digit;
    }

    /** A heading: the heading every listing starts with, in the brand's colour. */
    public static Msg title(String text) {
        return new Msg(prefix(Theme.MUTED) + Theme.LABEL + highlight(text, Theme.LABEL));
    }

    /**
     * The one line a command answers with: what it did, in the brand's colour,
     * then the detail, whose values take their own colour.
     *
     * <p>Every command that touches the world or the session ends on one of
     * these, so an answer is recognisable at a glance - which edit it was, what
     * it changed and how long it took.</p>
     */
    public static Msg result(String label, String detail) {
        return new Msg(prefix(Theme.MUTED) + Theme.LABEL + label + Theme.MUTED + ": " + Theme.TEXT
                + highlight(detail, Theme.TEXT));
    }

    /** A result with nothing to add: {@code FAWE » label}. */
    public static Msg result(String label) {
        return new Msg(prefix(Theme.MUTED) + Theme.LABEL + label);
    }

    /** {@code 1,024} in the value colour - a count, ready to sit inside a sentence. */
    public static String count(long value) {
        return Theme.NUMBER + formatNumber(value) + BACK;
    }

    /**
     * A per-character colour ramp in the {@code §x} hex form the client reads
     * since 1.16. The theme uses one, on the name in front of a line; it is here
     * for that and for the rare banner that draws its own.
     */
    public static String gradient(String text, int from, int to) {
        if (text.length() < 2) {
            return Theme.hex(from) + text;
        }
        StringBuilder out = new StringBuilder(text.length() * 15);
        int last = text.length() - 1;
        for (int i = 0; i <= last; i++) {
            out.append(Theme.hex(interpolate(from, to, i / (double) last))).append(text.charAt(i));
        }
        return out.toString();
    }

    private static int interpolate(int from, int to, double ratio) {
        int red = channel(from, 16) + (int) Math.round((channel(to, 16) - channel(from, 16)) * ratio);
        int green = channel(from, 8) + (int) Math.round((channel(to, 8) - channel(from, 8)) * ratio);
        int blue = channel(from, 0) + (int) Math.round((channel(to, 0) - channel(from, 0)) * ratio);
        return red << 16 | green << 8 | blue;
    }

    private static int channel(int colour, int shift) {
        return colour >> shift & 0xFF;
    }

    public Msg append(Msg other) {
        return new Msg(text + other.text);
    }

    public Msg append(String raw) {
        return new Msg(text + raw);
    }

    public Msg gray() {
        return new Msg(Theme.TEXT + text);
    }

    public Msg gold() {
        return new Msg(Theme.WARNING + text);
    }

    public String raw() {
        return text;
    }

    /** Strips formatting, for console output and logs. */
    public String plain() {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '§' && i + 1 < text.length()) {
                i++;
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** Parses {@code &a}-style codes, as typed by users in commands. */
    public static Msg parse(String input) {
        return new Msg(input.replace('&', '§'));
    }

    @Override
    public String toString() {
        return text;
    }

    public static String formatNumber(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    public static String formatDouble(double value) {
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return String.valueOf((long) value);
        }
        return String.format(Locale.ROOT, "%.3f", value);
    }
}
