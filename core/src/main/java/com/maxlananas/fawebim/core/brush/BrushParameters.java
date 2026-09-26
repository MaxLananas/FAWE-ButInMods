package com.maxlananas.fawebim.core.brush;

import com.maxlananas.fawebim.core.command.CommandRegistry;
import com.maxlananas.fawebim.core.command.Ctx;
import com.maxlananas.fawebim.core.command.Parsers;
import com.maxlananas.fawebim.core.expression.Expression;
import com.maxlananas.fawebim.core.mask.Mask;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.mask.Masks;
import com.maxlananas.fawebim.core.pattern.Pattern;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The values a {@code /brush} command line carries, resolved against the
 * declaration of the brush that was asked for.
 *
 * <p>The declaration says which arguments the brush takes, in which order and
 * with which default, so a brush can never read the wrong positional argument
 * and every argument FAWE declares stays reachable. Flags are read from the
 * same declaration, which is what {@code -m #clipboard} and friends are.</p>
 */
public final class BrushParameters {

    private final String name;
    private final Pattern pattern;
    private final Mask mask;
    private final Map<String, Mask> masks;
    private final Map<String, String> values;
    private final BrushOptions options;
    private final BlockVector3 placement;

    private BrushParameters(String name, Pattern pattern, Mask mask, Map<String, Mask> masks,
                            Map<String, String> values, BrushOptions options, BlockVector3 placement) {
        this.name = name;
        this.pattern = pattern;
        this.mask = mask;
        this.masks = masks;
        this.values = values;
        this.options = options;
        this.placement = placement;
    }

    /**
     * Resolves the arguments of a brush command.
     *
     * @param row     the generated signature, {@code name, aliases, arguments,
     *                switches, valueFlags, description}
     * @param options the flags that were split off the command line
     */
    public static BrushParameters bind(Ctx ctx, String[] row, BrushOptions options) {
        // The signature lowercases its entries, so the lookup of a parameter is
        // case insensitive: -l fills "snowBlockCount" whatever its spelling.
        Map<String, String> values = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        List<String> names = new ArrayList<>();
        List<String> defaults = new ArrayList<>();
        for (String declaration : arguments(row)) {
            int split = declaration.indexOf('=');
            String argument = split < 0 ? declaration : declaration.substring(0, split);
            String fallback = split < 0 ? "" : declaration.substring(split + 1);
            if (!isIdentifier(argument)) {
                argument = "argument" + names.size();
            }
            names.add(argument);
            defaults.add(fallback);
        }
        for (int index = 0; index < names.size(); index++) {
            String value = options.argument(index);
            values.put(names.get(index), value == null ? defaults.get(index) : value);
        }
        for (String flag : valueFlags(row)) {
            // A value flag carries the parameter it fills, e.g. -m <mask>.
            String parameter = flag.contains(":") ? flag.substring(0, flag.indexOf(':')) : flag;
            String switchName = flag.contains(":") ? flag.substring(flag.indexOf(':') + 1) : flag;
            String value = options.value(switchName, null);
            if (value != null) {
                values.put(parameter, value);
            }
        }
        String patternArgument = values.containsKey("pattern") ? values.get("pattern") : values.get("fill");
        Pattern pattern = null;
        if (ctx != null && patternArgument != null && !patternArgument.isEmpty()) {
            pattern = patternArgument.startsWith("#clipboard") ? null : Parsers.pattern(patternArgument, ctx);
        }
        Mask sessionMask = ctx == null ? null : ctx.session().getMask();
        // Mask-valued flags are parsed here, where the session is known: -m on the
        // clipboard brush fills its source mask, -m on the blend ball its mask.
        Map<String, Mask> masks = new LinkedHashMap<>();
        if (ctx != null) {
            for (Map.Entry<String, String> value : values.entrySet()) {
                String parameter = value.getKey();
                if (value.getValue().isEmpty()
                        || !(parameter.equals("mask") || parameter.endsWith("Mask"))) {
                    continue;
                }
                masks.put(parameter, Parsers.mask(value.getValue(), ctx));
            }
        }
        // -o counts from the placement position of the moment the brush is bound.
        BlockVector3 placement = ctx != null && options.switchOn("o") ? ctx.placement() : null;
        return new BrushParameters(row[0], pattern, sessionMask, masks, values, options, placement);
    }

    /** The parameters of a brush built without a command line, i.e. a preset. */
    public static BrushParameters of(String[] row, double radius, Pattern pattern, BrushOptions options) {
        Map<String, String> values = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        List<String> names = new ArrayList<>();
        List<String> defaults = new ArrayList<>();
        for (String declaration : arguments(row)) {
            int split = declaration.indexOf('=');
            String argument = split < 0 ? declaration : declaration.substring(0, split);
            if (!isIdentifier(argument)) {
                argument = "argument" + names.size();
            }
            names.add(argument);
            defaults.add(split < 0 ? "" : declaration.substring(split + 1));
        }
        for (int index = 0; index < names.size(); index++) {
            values.put(names.get(index), defaults.get(index));
        }
        values.put("radius", String.valueOf(radius));
        if (pattern != null && values.containsKey("pattern")) {
            // Presets carry a real pattern, but the parameters of a saved brush
            // are re-parsed from the command line when the brush is reloaded.
            values.put("pattern", "");
        }
        return new BrushParameters(row[0], pattern, null, Map.of(), values, options, null);
    }

    /** The name of the brush, as FAWE spells it. */
    public String name() {
        return name;
    }

    /** The fill pattern, or null for the clipboard brush. */
    public Pattern pattern() {
        return pattern;
    }

    /** The mask of the session, applied by every brush. */
    public Mask mask() {
        return mask;
    }

    /** The mask a {@code -m <mask>} flag added, if any. */
    public Mask flagMask() {
        return maskValue("mask");
    }

    /**
     * The mask a value flag added under the given parameter name, which is how a
     * brush whose flag fills a parameter that is not called {@code mask} reads it
     * (the clipboard brush's {@code -m <mask>} fills {@code sourceMask}).
     */
    public Mask maskValue(String parameter) {
        return masks.get(parameter);
    }

    /** The mask the brush should paint through, session mask and flag merged. */
    public Mask combinedMask() {
        Mask flagMask = flagMask();
        if (mask == null) {
            return flagMask;
        }
        if (flagMask == null) {
            return mask;
        }
        return new Masks.IntersectionMask(List.of(mask, flagMask));
    }

    /** The parsed command line flags. */
    public BrushOptions options() {
        return options;
    }

    /** The placement position when the brush was bound with {@code -o}, else null. */
    public BlockVector3 placement() {
        return placement;
    }

    /** True when the flag was written on the command line. */
    public boolean flag(String flag) {
        return options.switchOn(flag);
    }

    /** A declared argument as text. */
    public String string(String argument, String fallback) {
        String value = values.get(argument);
        return value == null || value.isEmpty() ? fallback : value;
    }

    /** A declared argument as a decimal number. */
    public double number(String argument, double fallback) {
        String value = values.get(argument);
        if (value == null || value.isEmpty()) {
            return fallback;
        }
        double number = parse(value, argument);
        if (!Double.isFinite(number)) {
            throw CommandRegistry.error("'" + value + "' is not a valid number for " + argument);
        }
        return number;
    }

    /** A declared argument as a whole number. */
    public int integer(String argument, int fallback) {
        return (int) Math.round(number(argument, fallback));
    }

    /**
     * A declared argument that may be a plain number or a constant expression,
     * which is how FAWE lets {@code Double radius} be written as {@code 5+2}.
     */
    public double expression(String argument, double fallback) {
        String value = values.get(argument);
        if (value == null || value.isEmpty()) {
            return fallback;
        }
        double number;
        try {
            number = Double.parseDouble(value.trim());
        } catch (NumberFormatException literal) {
            // Not a literal, so it has to be an expression.
            try {
                number = Expression.compile(value).evaluate(new Expression.Variables());
            } catch (RuntimeException e) {
                throw CommandRegistry.error("'" + value + "' is not a valid number for " + argument);
            }
        }
        // A syntax the expression reader did not finish leaves NaN behind, and
        // NaN would travel all the way into the geometry as a silent no-op.
        if (!Double.isFinite(number)) {
            throw CommandRegistry.error("'" + value + "' is not a valid number for " + argument);
        }
        return number;
    }

    /** A literal number, or the error the command line deserves. */
    private static double parse(String value, String argument) {
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            throw CommandRegistry.error("'" + value + "' is not a valid number for " + argument);
        }
    }

    /** The radius of the brush. */
    public double radius() {
        return expression("radius", 5);
    }

    public static List<String> arguments(String[] row) {
        return splitList(row[2]);
    }

    public static List<String> switches(String[] row) {
        return splitList(row[3]);
    }

    public static List<String> valueFlags(String[] row) {
        return splitList(row[4]);
    }

    public static List<String> aliases(String[] row) {
        return splitList(row[1]);
    }

    /**
     * Splits a column of a brush signature: aliases, switches and value flags are
     * comma separated, while the arguments column separates its entries with a
     * pipe so an argument can keep a comma for a default such as {@code a,b}.
     */
    private static List<String> splitList(String csv) {
        List<String> parts = new ArrayList<>();
        if (csv == null || csv.isEmpty()) {
            return parts;
        }
        for (String part : csv.split("\\s*[|,]\\s*")) {
            String trimmed = part.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty()) {
                parts.add(trimmed);
            }
        }
        return parts;
    }

    private static boolean isIdentifier(String value) {
        if (value == null || value.isEmpty() || !Character.isJavaIdentifierStart(value.charAt(0))) {
            return false;
        }
        for (int index = 1; index < value.length(); index++) {
            if (!Character.isJavaIdentifierPart(value.charAt(index))) {
                return false;
            }
        }
        return true;
    }
}
