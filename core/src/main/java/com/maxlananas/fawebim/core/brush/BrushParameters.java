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
    private final int[] layers;
    private final Mask mask;
    private final Map<String, Mask> masks;
    private final Map<String, String> values;
    private final BrushOptions options;
    private final BlockVector3 placement;
    private final com.maxlananas.fawebim.core.world.World world;
    private final com.maxlananas.fawebim.core.session.ClipboardHolder clipboard;

    private BrushParameters(String name, Pattern pattern, int[] layers, Mask mask, Map<String, Mask> masks,
                            Map<String, String> values, BrushOptions options, BlockVector3 placement,
                            com.maxlananas.fawebim.core.world.World world,
                            com.maxlananas.fawebim.core.session.ClipboardHolder clipboard) {
        this.name = name;
        this.pattern = pattern;
        this.layers = layers;
        this.mask = mask;
        this.masks = masks;
        this.values = values;
        this.options = options;
        this.placement = placement;
        this.world = world;
        this.clipboard = clipboard;
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
        int required = 0;
        boolean rest = false;
        for (String declaration : arguments(row)) {
            int split = declaration.indexOf('=');
            // A list takes the rest of the line; only the last argument is one.
            rest = (split < 0 ? declaration : declaration.substring(0, split)).endsWith("...");
            names.add(parameterName(declaration, names.size()));
            // A required argument is declared without a default at all.
            defaults.add(split < 0 ? null : declaration.substring(split + 1));
            if (split < 0) {
                required++;
            }
        }
        // WorldEdit's parser: an optional argument takes a word only while the
        // line holds more words than the required arguments still to fill, so
        // /brush set cuboid stone leaves the radius at its default and hands
        // stone to the pattern.
        int given = options.argumentCount();
        int next = 0;
        for (int index = 0; index < names.size(); index++) {
            String fallback = defaults.get(index);
            if (fallback == null) {
                required--;
                // WorldEdit refuses a line short of a required argument, where
                // an empty one bound a brush of air or of the default shape.
                if (next >= given && ctx != null) {
                    throw CommandRegistry.error("Missing argument " + (index + 1) + " for " + ctx.entry().usage());
                }
            }
            String value = null;
            if (next < given && (fallback == null || given - next > required)) {
                value = options.argument(next++);
                if (rest && index == names.size() - 1) {
                    StringBuilder line = new StringBuilder(value);
                    while (next < given) {
                        line.append(' ').append(options.argument(next++));
                    }
                    value = line.toString();
                }
            }
            values.put(names.get(index), value != null ? value : fallback == null ? "" : fallback);
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
        // The layers of /brush layer: a comma separated list of patterns, each
        // standing for the block it gives at the origin, as FAWE reads them.
        int[] layers = new int[0];
        String layerArgument = values.get("patternLayers");
        if (ctx != null && layerArgument != null && !layerArgument.isBlank()) {
            List<String> pieces = com.maxlananas.fawebim.core.util.Str.splitTopLevel(layerArgument, ',');
            layers = new int[pieces.size()];
            for (int i = 0; i < layers.length; i++) {
                layers[i] = Parsers.pattern(pieces.get(i).trim(), ctx).apply(0, 0, 0);
            }
        }
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
        return new BrushParameters(row[0], pattern, layers, null, masks, values, options, placement,
                ctx == null ? null : ctx.world(), ctx == null ? null : clipboardOf(ctx.session()));
    }

    /**
     * The clipboard of the session as it is now, with its transform: a holder
     * of its own, which a later //copy, //rotate or //flip leaves as it was.
     */
    private static com.maxlananas.fawebim.core.session.ClipboardHolder clipboardOf(
            com.maxlananas.fawebim.core.session.LocalSession session) {
        if (!session.hasClipboard()) {
            return null;
        }
        com.maxlananas.fawebim.core.session.ClipboardHolder current = session.getClipboard();
        com.maxlananas.fawebim.core.session.ClipboardHolder kept =
                new com.maxlananas.fawebim.core.session.ClipboardHolder(current.getClipboard());
        kept.setTransform(current.getTransform());
        return kept;
    }

    /** The parameters of a brush built without a command line, i.e. a preset. */
    public static BrushParameters of(String[] row, double radius, Pattern pattern, BrushOptions options) {
        Map<String, String> values = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        List<String> names = new ArrayList<>();
        List<String> defaults = new ArrayList<>();
        for (String declaration : arguments(row)) {
            int split = declaration.indexOf('=');
            names.add(parameterName(declaration, names.size()));
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
        return new BrushParameters(row[0], pattern, new int[0], null, Map.of(), values, options, null, null, null);
    }

    /** The name of the brush, as FAWE spells it. */
    public String name() {
        return name;
    }

    /** The fill pattern, or null for the clipboard brush. */
    public Pattern pattern() {
        return pattern;
    }

    /** The block states of the {@code patternLayers} argument, outermost first. */
    public int[] layers() {
        return layers.clone();
    }

    /**
     * The mask a brush is bound with: none. FAWE's brush takes its mask from
     * /tool mask; the session's global mask is not copied into it, which kept
     * the mask of the moment of binding after //gmask changed, since the edit
     * applies the global mask of the moment at every stroke.
     */
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

    /** The world of the player binding the brush, or null for a preset built without one. */
    public com.maxlananas.fawebim.core.world.World world() {
        return world;
    }

    /**
     * The clipboard as it was when the brush was bound, or null when the
     * session had none or the brush was built without a command line.
     */
    public com.maxlananas.fawebim.core.session.ClipboardHolder clipboard() {
        return clipboard;
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

    /**
     * The radius of the brush; for a brush whose radius has one value per
     * axis, the largest of them, which is its size.
     */
    public double radius() {
        if (AXIS_RADII.contains(name)) {
            double[] radii = radii();
            return Math.max(radii[0], Math.max(radii[1], radii[2]));
        }
        return expression("radius", 5);
    }

    /** FAWE's brushes whose radius is a vector, one value per axis. */
    private static final java.util.Set<String> AXIS_RADII = java.util.Set.of("rock");

    /**
     * The radius of each axis, as FAWE reads a vector argument: one number for
     * the three axes, or three separated by commas, such as {@code 10,5,10}
     * for a rock twice as wide as it is high.
     */
    public double[] radii() {
        String value = values.get("radius");
        if (value == null || value.isBlank()) {
            return new double[] {5, 5, 5};
        }
        String[] parts = value.split(",", -1);
        if (parts.length != 1 && parts.length != 3) {
            throw CommandRegistry.error("'" + value + "' is not a radius: give one number, or three like 10,5,10");
        }
        double[] radii = new double[3];
        for (int axis = 0; axis < 3; axis++) {
            double radius = parse(parts[parts.length == 1 ? 0 : axis], "radius");
            if (!(radius > 0) || Double.isInfinite(radius)) {
                throw CommandRegistry.error("Each radius must be a positive number, got '" + value + "'");
            }
            radii[axis] = radius;
        }
        return radii;
    }

    public static List<String> arguments(String[] row) {
        return splitList(row[2], "\\s*\\|\\s*");
    }

    public static List<String> switches(String[] row) {
        return splitList(row[3], COMMA);
    }

    public static List<String> valueFlags(String[] row) {
        return splitList(row[4], COMMA);
    }

    public static List<String> aliases(String[] row) {
        return splitList(row[1], COMMA);
    }

    private static final String COMMA = "\\s*,\\s*";

    /**
     * Splits a column of a brush signature: aliases, switches and value flags are
     * comma separated, while the arguments column separates its entries with a
     * pipe so an argument can keep a comma for a default such as {@code a,b}.
     */
    private static List<String> splitList(String csv, String separator) {
        List<String> parts = new ArrayList<>();
        if (csv == null || csv.isEmpty()) {
            return parts;
        }
        for (String part : csv.split(separator)) {
            String trimmed = part.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty()) {
                parts.add(trimmed);
            }
        }
        return parts;
    }

    /**
     * The name a declaration fills: what comes before its default and its
     * list mark, or {@code argumentN} for the parameters the upstream extractor
     * could not name.
     */
    private static String parameterName(String declaration, int index) {
        int split = declaration.indexOf('=');
        String argument = split < 0 ? declaration : declaration.substring(0, split);
        if (argument.endsWith("...")) {
            argument = argument.substring(0, argument.length() - 3);
        }
        return isIdentifier(argument) ? argument : "argument" + index;
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
