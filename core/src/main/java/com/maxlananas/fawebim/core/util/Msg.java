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

    /** Light grey, the colour FAWE uses for values. */
    public static Msg value(Object value) {
        return new Msg("§b" + value);
    }

    public static Msg error(String text) {
        return new Msg("§c" + text);
    }

    public static Msg success(String text) {
        return new Msg("§a" + text);
    }

    public static Msg warn(String text) {
        return new Msg("§e" + text);
    }

    public static Msg info(String text) {
        return new Msg("§7" + text);
    }

    /** {@code §bkey§7: §fvalue} style line. */
    public static Msg keyValue(String key, Object value) {
        return new Msg("§b" + key + "§7: §f" + value);
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
