package com.fawebutinmods.core.util;

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
        if (v.length() > 1 && (v.startsWith("\"") && v.endsWith("\"") || v.startsWith("'") && v.endsWith("'"))) {
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
            } else {
                sb.append(' ').append(String.valueOf(value)).append('\n');
            }
        }
    }
}
