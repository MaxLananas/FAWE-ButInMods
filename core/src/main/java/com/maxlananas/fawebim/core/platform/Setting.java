package com.maxlananas.fawebim.core.platform;

import java.util.function.Supplier;

/**
 * One entry of the mod configuration.
 *
 * <p>A setting knows the key a player types in game, the path the same value has
 * in {@code config/fawebim.yml}, the type it holds, what it does, and how to read
 * and write the field behind it. {@link Config} owns the table and the in-game
 * command surface edits it through this class, so a value typed in game and a
 * value written in the file always mean the same thing.</p>
 */
public final class Setting<T> {

    /** What a setting holds, which is also how it is written back to the file. */
    public enum Kind {
        BOOLEAN,
        INTEGER,
        TEXT
    }

    private final String key;
    private final String path;
    private final Kind kind;
    private final String description;
    private final T defaultValue;
    private final Supplier<T> reader;
    private final java.util.function.Consumer<T> writer;
    private java.util.List<String> choices = java.util.List.of();
    private java.util.function.UnaryOperator<String> canonical;

    Setting(String key, String path, Kind kind, String description, T defaultValue,
            Supplier<T> reader, java.util.function.Consumer<T> writer) {
        this.key = key;
        this.path = path;
        this.kind = kind;
        this.description = description;
        this.defaultValue = defaultValue;
        this.reader = reader;
        this.writer = writer;
    }

    /**
     * Limits a text setting to named values: a typo is refused when it is
     * typed, where it used to be stored and only failed once the value was
     * used.
     *
     * @param choices   the values, as they are listed and completed
     * @param canonical maps what was typed to the value it names - an alias
     *                  included - or to null when it names none
     */
    Setting<T> limitedTo(java.util.List<String> choices, java.util.function.UnaryOperator<String> canonical) {
        this.choices = java.util.List.copyOf(choices);
        this.canonical = canonical;
        return this;
    }

    /** The values a text setting is limited to, empty when it takes any text. */
    public java.util.List<String> choices() {
        return choices;
    }

    /** The short name used in game, without the surrounding slashes. */
    public String key() {
        return key;
    }

    /** The path of the same value in the configuration file. */
    public String path() {
        return path;
    }

    public Kind kind() {
        return kind;
    }

    /** One line explaining what the setting changes. */
    public String description() {
        return description;
    }

    public String defaultValue() {
        return String.valueOf(defaultValue);
    }

    public String value() {
        return String.valueOf(reader.get());
    }

    /** True when the given token names this setting, by key or by file path. */
    public boolean matches(String token) {
        return key.equalsIgnoreCase(token) || path.equalsIgnoreCase(token);
    }

    /**
     * Writes a value typed in game.
     *
     * @return an error message when the value does not fit the setting, or
     *         {@code null} when it was applied
     */
    public String apply(String raw) {
        T parsed = parse(raw);
        if (parsed == null) {
            return expected();
        }
        writer.accept(parsed);
        return null;
    }

    /** Puts the field back to the value the mod ships with. */
    public void reset() {
        writer.accept(defaultValue);
    }

    /** What the setting expects, for the message of a bad value. */
    public String expected() {
        return switch (kind) {
            case BOOLEAN -> "Expected true or false";
            case INTEGER -> "Expected a whole number";
            case TEXT -> choices.isEmpty() ? "Expected a text value"
                    : "Expected one of " + String.join(", ", choices);
        };
    }

    @SuppressWarnings("unchecked")
    private T parse(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        return (T) switch (kind) {
            case BOOLEAN -> switch (text.toLowerCase(java.util.Locale.ROOT)) {
                case "true", "yes", "on", "1" -> Boolean.TRUE;
                case "false", "no", "off", "0" -> Boolean.FALSE;
                default -> null;
            };
            case INTEGER -> {
                try {
                    yield Integer.valueOf(text);
                } catch (NumberFormatException e) {
                    yield null;
                }
            }
            case TEXT -> canonical == null ? text : canonical.apply(text);
        };
    }

    /** Builds a setting whose value lives in a field of the given config. */
    static <T> Setting<T> of(String key, String path, Kind kind, String description, T defaultValue,
                             Supplier<T> reader, java.util.function.Consumer<T> writer) {
        return new Setting<>(key, path, kind, description, defaultValue, reader, writer);
    }

}
