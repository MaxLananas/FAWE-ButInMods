package com.maxlananas.fawebim.core.session;

import java.util.Locale;

/**
 * The work an edit does besides writing the blocks themselves, which is what
 * {@code /perf} and {@code //update} switch on and off.
 *
 * <p>WorldEdit names eleven of these; this platform performs the six below, so
 * these are the ones it accepts, the way a platform only lists the side effects
 * it supports. The state of each one is kept in a {@link SideEffectSet}, and an
 * effect that is off is skipped rather than deferred.</p>
 */
public enum SideEffect {

    HISTORY(State.ON, "History"),
    LIGHTING(State.ON, "Lighting"),
    NEIGHBORS(State.OFF, "Neighbors"),
    UPDATE(State.ON, "Update"),
    ENTITY_EVENTS(State.ON, "Entity events"),
    NETWORK(State.ON, "Client sync");

    /** Whether an effect runs with the edit, later, or not at all. */
    public enum State {
        OFF("Off"),
        ON("On"),
        DELAYED("Delayed");

        private final String displayName;

        State(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }

        /** The state of a name typed on the command line, or {@code null}. */
        public static State parse(String word) {
            for (State state : values()) {
                if (state.name().equalsIgnoreCase(word)) {
                    return state;
                }
            }
            return null;
        }

        public String lowerName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final State defaultValue;
    private final String displayName;

    SideEffect(State defaultValue, String displayName) {
        this.defaultValue = defaultValue;
        this.displayName = displayName;
    }

    public State getDefaultValue() {
        return defaultValue;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String lowerName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The effect of a name typed on the command line, or {@code null}. */
    public static SideEffect parse(String word) {
        for (SideEffect effect : values()) {
            if (effect.name().equalsIgnoreCase(word)) {
                return effect;
            }
        }
        return null;
    }

    /** Every name {@code /perf} accepts, in the order the info box lists them. */
    public static String names() {
        StringBuilder names = new StringBuilder();
        for (SideEffect effect : values()) {
            if (names.length() > 0) {
                names.append(", ");
            }
            names.append(effect.lowerName());
        }
        return names.toString();
    }
}
