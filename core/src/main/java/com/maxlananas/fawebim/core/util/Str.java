package com.maxlananas.fawebim.core.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Small string helpers used by the command dispatcher and the parsers. */
public final class Str {

    private Str() {
    }

    /** Splits on whitespace, honouring double quotes and backslash escapes. */
    public static List<String> split(String input) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        boolean escaped = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (escaped) {
                current.append(c);
                escaped = false;
                continue;
            }
            switch (c) {
                case '\\' -> escaped = true;
                case '"' -> inQuotes = !inQuotes;
                case ' ' , '\t' -> {
                    if (inQuotes) {
                        current.append(c);
                    } else if (current.length() > 0) {
                        out.add(current.toString());
                        current.setLength(0);
                    }
                }
                default -> current.append(c);
            }
        }
        if (current.length() > 0) {
            out.add(current.toString());
        }
        return out;
    }

    /** Splits a comma separated list, keeping {@code [...]} groups and quotes intact. */
    public static List<String> splitCommas(String input) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        boolean inQuotes = false;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            }
            if (!inQuotes) {
                if (c == '[' || c == '(' || c == '{') {
                    depth++;
                } else if (c == ']' || c == ')' || c == '}') {
                    depth--;
                } else if (c == ',' && depth == 0) {
                    out.add(current.toString());
                    current.setLength(0);
                    continue;
                }
            }
            current.append(c);
        }
        if (current.length() > 0) {
            out.add(current.toString());
        }
        return out;
    }

    /** Splits on a separator at depth 0 (outside brackets/quotes). */
    public static List<String> splitTopLevel(String input, char separator) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        boolean inQuotes = false;
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            }
            if (!inQuotes) {
                if (c == '[' || c == '(' || c == '{') {
                    depth++;
                } else if (c == ']' || c == ')' || c == '}') {
                    depth--;
                } else if (c == separator && depth == 0) {
                    out.add(current.toString());
                    current.setLength(0);
                    continue;
                }
            }
            current.append(c);
        }
        if (current.length() > 0) {
            out.add(current.toString());
        }
        return out;
    }

    /**
     * The {@code [a][b][c]} argument groups of a rich parser input, e.g. FAWE's
     * {@code #mask[mask][pattern][pattern]}. An input without brackets yields no
     * group, which the callers treat as a missing argument.
     */
    public static List<String> bracketGroups(String input) {
        List<String> out = new ArrayList<>();
        int start = input.indexOf('[');
        if (start < 0) {
            return out;
        }
        int depth = 0;
        StringBuilder current = new StringBuilder();
        for (int i = start; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '[') {
                depth++;
                if (depth == 1) {
                    current.setLength(0);
                    continue;
                }
            } else if (c == ']') {
                depth--;
                if (depth == 0) {
                    out.add(current.toString());
                    continue;
                }
            }
            if (depth > 0) {
                current.append(c);
            }
        }
        return out;
    }

    public static String join(List<String> parts, String separator) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                sb.append(separator);
            }
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    public static String join(String[] parts, int from, String separator) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < parts.length; i++) {
            if (i > from) {
                sb.append(separator);
            }
            sb.append(parts[i]);
        }
        return sb.toString();
    }

    public static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    public static boolean isInteger(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        int start = value.charAt(0) == '-' || value.charAt(0) == '+' ? 1 : 0;
        if (start == value.length()) {
            return false;
        }
        for (int i = start; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    public static boolean isDouble(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        try {
            Double.parseDouble(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** {@code minecraft:stone} -&gt; {@code stone}; leaves other keys untouched. */
    public static String stripNamespace(String key) {
        int idx = key.indexOf(':');
        return idx < 0 ? key : key.substring(idx + 1);
    }

    /**
     * A registry id as a name to read, the way the game names most things:
     * {@code minecraft:wooden_axe} is Wooden Axe.
     */
    public static String itemName(String id) {
        String path = stripNamespace(id);
        StringBuilder name = new StringBuilder(path.length());
        boolean start = true;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '_') {
                name.append(' ');
                start = true;
            } else {
                name.append(start ? Character.toUpperCase(c) : c);
                start = false;
            }
        }
        return name.toString();
    }

    /** Ensures the key has a namespace. */
    public static String withNamespace(String key) {
        return key.indexOf(':') < 0 ? "minecraft:" + key : key;
    }

    public static String tabulate(int count) {
        return " ".repeat(Math.max(0, count));
    }

    /** Simple plural helper for user facing messages. */
    public static String plural(long count, String singular, String plural) {
        return count + " " + (count == 1 ? singular : plural);
    }

    /** Truncates for chat output. */
    public static String limit(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 3) + "...";
    }

    /**
     * The name a mistyped word most likely stands for, to suggest it back: the
     * first name the word begins, else the nearest within one edit - two for a
     * word of six letters or more, a swap of two letters counting as one - or
     * null when none is that close. The first of equally near names wins, so a
     * sorted list gives the same answer every time.
     */
    public static String closest(String typed, Iterable<String> names) {
        String word = typed.toLowerCase(Locale.ROOT);
        int allowed = word.length() >= 6 ? 2 : 1;
        String best = null;
        int bestDistance = allowed + 1;
        for (String name : names) {
            if (word.length() >= 2 && name.startsWith(word)) {
                return name;
            }
            // Every edit changes the length by one at most: a name much shorter
            // or longer is out of reach, and a hostile word costs nothing.
            if (Math.abs(name.length() - word.length()) > allowed) {
                continue;
            }
            int distance = editDistance(word, name);
            if (distance < bestDistance) {
                best = name;
                bestDistance = distance;
            }
        }
        return best;
    }

    /** Insertions, deletions, substitutions and swaps of neighbours turning a into b. */
    private static int editDistance(String a, String b) {
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) {
            d[i][0] = i;
        }
        for (int j = 0; j <= b.length(); j++) {
            d[0][j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
                }
            }
        }
        return d[a.length()][b.length()];
    }

    /**
     * Parses a duration into milliseconds: groups of a number and a unit such
     * as {@code 30s}, {@code 1.5h}, {@code 8h5m12s} or {@code 2 weeks}, with
     * spaces allowed around the units. The units are seconds, minutes, hours,
     * days, weeks and years, by their letter or their name; a number without a
     * unit counts seconds, as FAWE's time arguments do.
     *
     * <p>There were two parsers: this one read a bare number as milliseconds
     * and took no fraction, the commands' own read it as minutes and took a
     * single group.</p>
     */
    public static long parseDuration(String text) {
        String value = text.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            throw new InputException("Expected a duration such as 8h5m12s, got nothing");
        }
        double total = 0;
        int index = 0;
        while (index < value.length()) {
            int start = index;
            while (index < value.length() && (isAsciiDigit(value.charAt(index)) || value.charAt(index) == '.')) {
                index++;
            }
            if (start == index) {
                throw new InputException("Expected a duration such as 8h5m12s, got '" + text + "'");
            }
            double amount;
            try {
                amount = Double.parseDouble(value.substring(start, index));
            } catch (NumberFormatException e) {
                throw new InputException("Expected a duration such as 8h5m12s, got '" + text + "'");
            }
            while (index < value.length() && Character.isWhitespace(value.charAt(index))) {
                index++;
            }
            int unitStart = index;
            while (index < value.length() && value.charAt(index) >= 'a' && value.charAt(index) <= 'z') {
                index++;
            }
            total += amount * unitMillis(value.substring(unitStart, index), text);
            while (index < value.length() && Character.isWhitespace(value.charAt(index))) {
                index++;
            }
        }
        // A duration is subtracted from the clock: past half of what a long
        // holds, it is a typo rather than a date.
        if (!Double.isFinite(total) || total > Long.MAX_VALUE / 2.0) {
            throw new InputException("The duration '" + text + "' is too long");
        }
        return Math.round(total);
    }

    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static long unitMillis(String unit, String text) {
        return switch (unit) {
            case "", "s", "sec", "secs", "second", "seconds" -> 1000L;
            case "m", "min", "mins", "minute", "minutes" -> 60_000L;
            case "h", "hr", "hrs", "hour", "hours" -> 3_600_000L;
            case "d", "day", "days" -> 86_400_000L;
            case "w", "wk", "wks", "week", "weeks" -> 604_800_000L;
            case "y", "yr", "yrs", "year", "years" -> 31_536_000_000L;
            default -> throw new InputException("Unknown time unit '" + unit + "' in '" + text
                    + "'. Use s, m, h, d, w or y.");
        };
    }
}
