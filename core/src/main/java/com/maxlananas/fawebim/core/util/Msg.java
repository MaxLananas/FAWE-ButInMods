package com.maxlananas.fawebim.core.util;

import java.util.Locale;

/**
 * A Minecraft-flavoured text component: plain text plus legacy {@code §} colour
 * codes. The engine formats everything into this form; the Fabric adapter turns
 * it into a chat component (and the CLI strips the codes).
 */
public final class Msg {

    private final String text;

    private Msg(String text) {
        this.text = text;
    }

    public static Msg of(String text) {
        return new Msg(text);
    }

    public static Msg empty() {
        return new Msg("");
    }

    /** The colour a number, a coordinate or a count is written in. */
    private static final String NUMBER = "§b";
    /** The colour a name between quotes is written in. */
    private static final String QUOTED = "§f";
    /** The colour a switch is written in. */
    private static final String FLAG = "§e";
    /** The colour a {@code #} pattern, mask or category is written in. */
    private static final String NAME = "§d";

    /** Light grey, the colour FAWE uses for values. */
    public static Msg value(Object value) {
        return new Msg("§b" + highlight(String.valueOf(value), "§b"));
    }

    public static Msg error(String text) {
        return new Msg("§c" + highlight(text, "§c"));
    }

    public static Msg success(String text) {
        return new Msg("§a" + highlight(text, "§a"));
    }

    public static Msg warn(String text) {
        return new Msg("§e" + highlight(text, "§e"));
    }

    public static Msg info(String text) {
        return new Msg("§7" + highlight(text, "§7"));
    }

    /** {@code §bkey§7: §fvalue} style line. */
    public static Msg keyValue(String key, Object value) {
        return new Msg("§b" + key + "§7: §f" + highlight(String.valueOf(value), "§f"));
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
        int index = 0;
        while (index < text.length()) {
            if (text.charAt(index) == '§') {
                int length = codeLength(text, index);
                out.append(text, index, index + length);
                index += length;
                continue;
            }
            int next = text.indexOf('§', index);
            if (next < 0) {
                next = text.length();
            }
            highlightRun(text.substring(index, next), role, out);
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

    private static void highlightRun(String run, String role, StringBuilder out) {
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
                    out.append(QUOTED).append(run, index, close + 1).append(role);
                    index = close + 1;
                    continue;
                }
            }
            int end = index;
            while (end < run.length() && run.charAt(end) != ' ' && run.charAt(end) != '\t') {
                end++;
            }
            String token = run.substring(index, end);
            String colour = tokenColour(token);
            if (colour == null || colour.equals(role)) {
                out.append(token);
            } else {
                // Whole tokens are coloured, so the text around them keeps its own
                // colour and a token is never split by a colour code.
                out.append(colour).append(token).append(role);
            }
            index = end;
        }
    }

    /** What colour a single token deserves, if any. */
    private static String tokenColour(String token) {
        int start = 0;
        while (start < token.length() && "([{".indexOf(token.charAt(start)) >= 0) {
            start++;
        }
        int end = token.length();
        while (end > start && ")]},.;:!?".indexOf(token.charAt(end - 1)) >= 0) {
            end--;
        }
        if (end <= start) {
            return null;
        }
        String core = token.substring(start, end);
        if (isNumber(core)) {
            return NUMBER;
        }
        if (core.charAt(0) == '-' && core.length() > 1 && Character.isLetter(core.charAt(1))) {
            return FLAG;
        }
        if (core.charAt(0) == '#' && core.length() > 1) {
            return NAME;
        }
        return null;
    }

    /** A count, a coordinate, a percentage or a decimal, optionally signed. */
    private static boolean isNumber(String token) {
        char first = token.charAt(0);
        if (!Character.isDigit(first) && first != '-' && first != '+') {
            return false;
        }
        for (int i = 1; i < token.length(); i++) {
            char c = token.charAt(i);
            if (!Character.isDigit(c) && c != ',' && c != '.' && c != '%' && c != ':' && c != '-'
                    && c != '+') {
                return false;
            }
        }
        return true;
    }

    /** A heading: the mod's cyan-to-blue run, which every listing starts with. */
    public static Msg title(String text) {
        return new Msg(gradient(text, 0x8FE3FF, 0x6C9BFF));
    }

    /**
     * A per-character colour ramp in the {@code §x} hex form the client reads
     * since 1.16: a heading that shifts colour instead of sitting in one flat
     * tone is what makes a listing look finished.
     */
    public static String gradient(String text, int from, int to) {
        if (text.length() < 2) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length() * 8);
        int last = text.length() - 1;
        for (int i = 0; i <= last; i++) {
            int colour = interpolate(from, to, i / (double) last);
            out.append("§x");
            for (int shift = 20; shift >= 0; shift -= 4) {
                out.append('§').append(Character.forDigit((colour >> shift) & 0xF, 16));
            }
            out.append(text.charAt(i));
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
        return new Msg("§7" + text);
    }

    public Msg gold() {
        return new Msg("§6" + text);
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
