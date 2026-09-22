package com.fawebutinmods.core.test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A very small JSON reader, used by the doc generator to read
 * {@code docs/commands-inventory.json} without pulling in a dependency.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc8259">RFC 8259</a>
 */
final class Json {

    private final String text;
    private int index;

    private Json(String text) {
        this.text = text;
    }

    static Object parse(String text) {
        Json json = new Json(text);
        json.skipWhitespace();
        Object value = json.readValue();
        json.skipWhitespace();
        if (json.index != text.length()) {
            throw new IllegalArgumentException("Trailing content at " + json.index);
        }
        return value;
    }

    private Object readValue() {
        char c = text.charAt(index);
        return switch (c) {
            case '{' -> readObject();
            case '[' -> readArray();
            case '"' -> readString();
            case 't' -> readLiteral("true", Boolean.TRUE);
            case 'f' -> readLiteral("false", Boolean.FALSE);
            case 'n' -> readLiteral("null", null);
            default -> readNumber();
        };
    }

    private Map<String, Object> readObject() {
        Map<String, Object> map = new LinkedHashMap<>();
        index++; // {
        skipWhitespace();
        if (text.charAt(index) == '}') {
            index++;
            return map;
        }
        while (true) {
            skipWhitespace();
            String key = readString();
            skipWhitespace();
            index++; // :
            skipWhitespace();
            map.put(key, readValue());
            skipWhitespace();
            char c = text.charAt(index++);
            if (c == '}') {
                return map;
            }
        }
    }

    private List<Object> readArray() {
        List<Object> list = new ArrayList<>();
        index++; // [
        skipWhitespace();
        if (text.charAt(index) == ']') {
            index++;
            return list;
        }
        while (true) {
            skipWhitespace();
            list.add(readValue());
            skipWhitespace();
            char c = text.charAt(index++);
            if (c == ']') {
                return list;
            }
        }
    }

    private String readString() {
        if (text.charAt(index) != '"') {
            throw new IllegalArgumentException("Expected a string at " + index);
        }
        index++;
        StringBuilder out = new StringBuilder();
        while (true) {
            char c = text.charAt(index++);
            if (c == '"') {
                return out.toString();
            }
            if (c != '\\') {
                out.append(c);
                continue;
            }
            char escaped = text.charAt(index++);
            switch (escaped) {
                case '"' -> out.append('"');
                case '\\' -> out.append('\\');
                case '/' -> out.append('/');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    out.append((char) Integer.parseInt(text.substring(index, index + 4), 16));
                    index += 4;
                }
                default -> out.append(escaped);
            }
        }
    }

    private Object readNumber() {
        int start = index;
        while (index < text.length() && "-+.eE0123456789".indexOf(text.charAt(index)) >= 0) {
            index++;
        }
        String number = text.substring(start, index);
        if (number.contains(".") || number.contains("e") || number.contains("E")) {
            return Double.parseDouble(number);
        }
        return Long.parseLong(number);
    }

    private Object readLiteral(String literal, Object value) {
        if (!text.startsWith(literal, index)) {
            throw new IllegalArgumentException("Expected " + literal + " at " + index);
        }
        index += literal.length();
        return value;
    }

    private void skipWhitespace() {
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
            index++;
        }
    }
}
