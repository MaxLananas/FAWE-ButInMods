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
     * Parses a {@code 8h5m12s} style duration into milliseconds; units are
     * seconds, minutes, hours, days, weeks and years.
     */
    public static long parseDuration(String text) {
        long total = 0;
        long value = 0;
        boolean digits = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isDigit(c)) {
                value = value * 10 + (c - '0');
                digits = true;
                continue;
            }
            if (!digits) {
                throw com.maxlananas.fawebim.core.command.CommandRegistry.error(
                        "Expected a duration such as 8h5m12s, got '" + text + "'");
            }
            total += value * unitMillis(c, text);
            value = 0;
            digits = false;
        }
        if (digits) {
            total += value;
        }
        return total;
    }

    private static long unitMillis(char unit, String text) {
        return switch (Character.toLowerCase(unit)) {
            case 's' -> 1000L;
            case 'm' -> 60_000L;
            case 'h' -> 3_600_000L;
            case 'd' -> 86_400_000L;
            case 'w' -> 604_800_000L;
            case 'y' -> 31_536_000_000L;
            default -> throw new IllegalArgumentException("Unknown time unit '" + unit + "' in '" + text + "'");
        };
    }
}
