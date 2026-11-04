package com.maxlananas.fawebim.core.util;

/**
 * A tiny YAML-subset reader/writer for the mod configuration.
 *
 * <p>FAWE ships a large {@code config.yml}; we keep the same key names so that
 * existing FAWE configuration values (limits, history size, queue settings,
 * brush radius, schematic directories...) can be copied over, but only the flat
 * {@code key: value} subset plus nested maps is supported.</p>
 */
public final class MiniYaml {

    private MiniYaml() {
    }

    public static java.util.Map<String, Object> parse(String text) {
        java.util.Map<String, Object> root = new java.util.LinkedHashMap<>();
        java.util.Deque<java.util.Map<String, Object>> stack = new java.util.ArrayDeque<>();
        java.util.Deque<Integer> indents = new java.util.ArrayDeque<>();
        stack.push(root);
        indents.push(-1);
        for (String rawLine : text.split("\r?\n")) {
            if (rawLine.trim().isEmpty() || rawLine.trim().startsWith("#")) {
                continue;
            }
            int indent = 0;
            while (indent < rawLine.length() && rawLine.charAt(indent) == ' ') {
                indent++;
            }
            String line = rawLine.trim();
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            while (indents.peek() >= indent) {
                stack.pop();
                indents.pop();
            }
            if (value.isEmpty()) {
                java.util.Map<String, Object> child = new java.util.LinkedHashMap<>();
                stack.peek().put(key, child);
                stack.push(child);
                indents.push(indent);
            } else {
                stack.peek().put(key, scalar(value));
            }
        }
        return root;
    }

    @SuppressWarnings("unchecked")
    public static Object path(java.util.Map<String, Object> root, String path, Object fallback) {
        String[] parts = path.split("\\.");
        Object current = root;
        for (String part : parts) {
            if (!(current instanceof java.util.Map<?, ?> map) || !map.containsKey(part)) {
                return fallback;
            }
            current = ((java.util.Map<String, Object>) map).get(part);
        }
        return current == null ? fallback : current;
    }

    public static Object scalar(String value) {
        String v = value;
        if (v.length() > 1 && v.startsWith("\"") && v.endsWith("\"")) {
            return unquote(v.substring(1, v.length() - 1));
        }
        if (v.length() > 1 && v.startsWith("'") && v.endsWith("'")) {
            return v.substring(1, v.length() - 1);
        }
        if (v.startsWith("[") && v.endsWith("]")) {
            java.util.List<Object> list = new java.util.ArrayList<>();
            String inner = v.substring(1, v.length() - 1).trim();
            if (!inner.isEmpty()) {
                for (String part : Str.splitCommas(inner)) {
                    list.add(scalar(part.trim()));
                }
            }
            return list;
        }
        if (v.equalsIgnoreCase("true") || v.equalsIgnoreCase("false")) {
            return Boolean.parseBoolean(v);
        }
        if (Str.isInteger(v)) {
            try {
                return Integer.parseInt(v);
            } catch (NumberFormatException ignored) {
                return Long.parseLong(v);
            }
        }
        if (Str.isDouble(v)) {
            return Double.parseDouble(v);
        }
        return v;
    }

    /** The text of a quoted value, with the escapes {@link #quote} writes undone. */
    private static String unquote(String text) {
        if (text.indexOf('\\') < 0) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '\\' || i + 1 >= text.length()) {
                out.append(c);
                continue;
            }
            char escaped = text.charAt(++i);
            switch (escaped) {
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                default -> out.append(escaped);
            }
        }
        return out.toString();
    }

    /**
     * A string as it has to be written to come back as the same string.
     *
     * <p>The reader turns {@code 12}, {@code true}, {@code [a, b]} into an
     * integer, a boolean and a list, strips the quotes around a quoted value and
     * reads an empty value as a nested map. A string value written raw therefore
     * came back as another type - a schematic folder named {@code 2024}, a macro
     * whose name is {@code true}, or the empty string, which came back as an
     * empty section. Such a value is quoted, and so is one whose first character
     * or spacing would be eaten by the reader.</p>
     */
    private static String quoteIfNeeded(String text) {
        if (text.isEmpty() || !text.equals(text.trim()) || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0
                || text.equalsIgnoreCase("true") || text.equalsIgnoreCase("false")
                || Str.isInteger(text) || Str.isDouble(text)
                || (text.startsWith("[") && text.endsWith("]"))
                || text.indexOf(':') >= 0 || text.startsWith("#")
                || text.indexOf('"') >= 0 || text.indexOf('\\') >= 0) {
            return quote(text);
        }
        return text;
    }

    /**
     * A list item as it has to be written. A comma or a bracket in an item is
     * list syntax to the reader - {@code [a, b]} is two items - so an item
     * holding one is quoted whatever {@link #quoteIfNeeded} would say.
     */
    private static String quoteItem(String text) {
        if (text.indexOf(',') >= 0 || text.indexOf('[') >= 0 || text.indexOf(']') >= 0) {
            return quote(text);
        }
        return quoteIfNeeded(text);
    }

    private static String quote(String text) {
        StringBuilder out = new StringBuilder(text.length() + 2).append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                // One value, one line: a newline would end the line the reader
                // is splitting on and the rest of the value would be read as a key.
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c);
            }
        }
        return out.append('"').toString();
    }

    public static String write(java.util.Map<String, Object> map) {
        StringBuilder sb = new StringBuilder();
        writeMap(sb, map, 0);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeMap(StringBuilder sb, java.util.Map<String, Object> map, int indent) {
        for (var entry : map.entrySet()) {
            sb.append(" ".repeat(indent)).append(entry.getKey()).append(':');
            Object value = entry.getValue();
            if (value instanceof java.util.Map<?, ?> nested) {
                sb.append('\n');
                writeMap(sb, (java.util.Map<String, Object>) nested, indent + 2);
            } else if (value instanceof java.util.List<?> list) {
                sb.append(" [");
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    Object item = list.get(i);
                    sb.append(item instanceof String text ? quoteItem(text) : String.valueOf(item));
                }
                sb.append("]\n");
            } else if (value instanceof String text) {
                sb.append(' ').append(quoteIfNeeded(text)).append('\n');
            } else {
                sb.append(' ').append(String.valueOf(value)).append('\n');
            }
        }
    }
}
