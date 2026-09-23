package com.maxlananas.fawebim.core.brush;

import com.maxlananas.fawebim.core.command.CommandRegistry;
import com.maxlananas.fawebim.core.command.Ctx;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The switches and value flags a {@code /brush} command line can carry, split
 * into the part the dispatcher owns ({@code -h}, {@code -f}) and the rest, so a
 * brush constructor never has to walk the raw arguments again.
 *
 * <p>FAWE spells its brush flags with a single dash both for the flag name and
 * for its value ({@code -m #clipboard}), so a token starting with a dash is
 * either a known switch or a flag waiting for the token after it.</p>
 */
public final class BrushOptions {

    private final Map<String, String> switches = new LinkedHashMap<>();
    private final Map<String, String> values = new LinkedHashMap<>();
    private List<String> arguments = new ArrayList<>();

    private BrushOptions() {
    }

    /** The flags of a command line that has none, used by the saved presets. */
    public static BrushOptions empty() {
        return new BrushOptions();
    }

    /**
     * Reads the flags the dispatcher already pulled off the command line. A flag
     * is a value flag when the brush declares it as one, which is why the
     * declaration is passed in: {@code -m #clipboard} keeps its mask.
     */
    public static BrushOptions of(Ctx ctx) {
        BrushOptions options = new BrushOptions();
        options.arguments = new ArrayList<>(ctx.args());
        for (Map.Entry<String, List<String>> flag : ctx.flags().entrySet()) {
            options.switches.put(flag.getKey(), "true");
            List<String> values = flag.getValue();
            if (!values.isEmpty()) {
                options.values.put(flag.getKey(), values.get(0));
            }
        }
        return options;
    }

    /** True when the switch is present. */
    public boolean switchOn(String flag) {
        String value = switches.get(flag);
        return value != null && !value.equals("false");
    }

    /** True when the switch was written either as {@code -x} or as {@code -!x}. */
    public boolean switchPresent(String flag) {
        return switches.containsKey(flag);
    }

    /** The value of a {@code -x <value>} flag. */
    public String value(String flag, String fallback) {
        String value = values.get(flag);
        return value == null ? fallback : value;
    }

    /** The integer value of a {@code -x <value>} flag. */
    public int intValue(String flag, int fallback) {
        String value = values.get(flag);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw CommandRegistry.error("Flag -" + flag + " expects a whole number, got '" + value + "'");
        }
    }

    /** The decimal value of a {@code -x <value>} flag. */
    public double doubleValue(String flag, double fallback) {
        String value = values.get(flag);
        if (value == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            throw CommandRegistry.error("Flag -" + flag + " expects a number, got '" + value + "'");
        }
    }

    /**
     * The arguments that were not flags. They are read from the end of the list
     * upwards, because FAWE lets the arguments of a flag trail the positionals.
     */
    public int argumentCount() {
        return arguments.size();
    }

    /** The {@code index}th remaining argument, or {@code null}. */
    public String argument(int index) {
        return index < arguments.size() ? arguments.get(index) : null;
    }

    /** True when the argument exists, is not a flag and is not a number. */
    public boolean isWord(int index) {
        String value = argument(index);
        if (value == null || value.isEmpty()) {
            return false;
        }
        char first = value.charAt(0);
        return (first < '0' || first > '9') && first != '-' && first != '+';
    }
}
