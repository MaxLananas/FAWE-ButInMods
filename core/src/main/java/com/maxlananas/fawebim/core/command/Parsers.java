package com.maxlananas.fawebim.core.command;

import com.maxlananas.fawebim.core.mask.Mask;
import com.maxlananas.fawebim.core.mask.Masks;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.pattern.Pattern;
import com.maxlananas.fawebim.core.pattern.Patterns;
import com.maxlananas.fawebim.core.region.Region;
import com.maxlananas.fawebim.core.util.RandomCollection;
import com.maxlananas.fawebim.core.util.Str;
import com.maxlananas.fawebim.core.util.noise.Noise;
import com.maxlananas.fawebim.core.world.BlockState;
import com.maxlananas.fawebim.core.world.BlockStateRegistry;
import com.maxlananas.fawebim.core.world.Extent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Parsers for the WorldEdit input syntax: blocks, patterns, masks and region
 * shapes. Ids, prefixes and aliases match FAWE exactly ({@code ##tag},
 * {@code #clipboard}, {@code 25%stone}, {@code #solid}, {@code #region}, ...).
 */
public final class Parsers {

    private Parsers() {
    }

    /**
     * The largest coordinate a command accepts on any axis. Minecraft's world
     * border stops at 30 million blocks, and staying inside it keeps every sum
     * and difference of two coordinates inside an {@code int}: a position of
     * two billion made the next {@code max + 1} of a region wrap round.
     */
    public static final int MAX_COORDINATE = 30_000_000;

    /**
     * A number argument, refused unless it is a finite number: Java reads
     * {@code NaN} and {@code Infinity} as numbers too, and a {@code NaN} radius
     * or a count of infinity walked as zero or as the largest integer.
     */
    public static double finiteArg(String input, String what) {
        double value;
        try {
            value = Double.parseDouble(input.trim());
        } catch (NumberFormatException e) {
            throw CommandRegistry.error("Expected a number for " + what + " but got '" + input + "'");
        }
        if (!Double.isFinite(value)) {
            throw CommandRegistry.error("Expected a finite number for " + what + " but got '" + input + "'");
        }
        return value;
    }

    /**
     * An integer argument. A decimal is rounded, as the commands always did; a
     * value past what an {@code int} holds is refused instead of wrapping round
     * to a negative one.
     */
    public static int intArg(String input, String what) {
        long rounded = Math.round(finiteArg(input, what));
        if (rounded < Integer.MIN_VALUE || rounded > Integer.MAX_VALUE) {
            throw CommandRegistry.error("The " + what + " '" + input + "' is out of range");
        }
        return (int) rounded;
    }

    /** A whole number that has to fit a {@code long}, such as a seed. */
    public static long longArg(String input, String what) {
        try {
            return Long.parseLong(input.trim());
        } catch (NumberFormatException e) {
            throw CommandRegistry.error("Expected a whole number for " + what + " but got '" + input + "'");
        }
    }

    /** One of the six directions, by name or first letter. */
    public static com.maxlananas.fawebim.core.world.Direction direction(String input) {
        try {
            return com.maxlananas.fawebim.core.world.Direction.parse(input.trim());
        } catch (IllegalArgumentException e) {
            throw CommandRegistry.error("Unknown direction '" + input
                    + "'; use north, south, east, west, up or down");
        }
    }

    /** An axis, by letter or by a direction along it. */
    public static com.maxlananas.fawebim.core.transform.Axis axis(String input) {
        try {
            return com.maxlananas.fawebim.core.transform.Axis.parse(input.trim());
        } catch (IllegalArgumentException e) {
            throw CommandRegistry.error("Unknown direction '" + input
                    + "'; use x, y, z or a direction such as north or up");
        }
    }

    /** Refuses a coordinate outside the world border. */
    public static int coordinate(double value, String input) {
        if (!Double.isFinite(value) || Math.abs(value) > MAX_COORDINATE) {
            throw CommandRegistry.error("'" + input + "' is outside the world (coordinates stop at "
                    + com.maxlananas.fawebim.core.util.Msg.formatNumber(MAX_COORDINATE) + ")");
        }
        return (int) Math.floor(value);
    }

    /**
     * A radii argument: one number, or a comma separated pair, which is how
     * WorldEdit's {@code @Radii} arguments read.
     */
    public static java.util.List<Double> radii(String input) {
        java.util.List<Double> values = new java.util.ArrayList<>(2);
        double maximum = com.maxlananas.fawebim.core.platform.Config.get().maxRadius;
        for (String part : input.split(",")) {
            String token = part.trim();
            if (token.isEmpty()) {
                continue;
            }
            double radius;
            try {
                radius = Double.parseDouble(token);
            } catch (NumberFormatException e) {
                throw CommandRegistry.error("Expected a radius, got '" + token + "'");
            }
            if (!Double.isFinite(radius)) {
                throw CommandRegistry.error("Expected a radius, got '" + token + "'");
            }
            // A radius is walked cell by cell, so an absurd one would lock the
            // game up before the change limit could stop it. -1 means no ceiling.
            if (maximum > 0 && radius > maximum) {
                throw CommandRegistry.error("Maximum radius (in configuration): " + maximum);
            }
            values.add(radius);
        }
        return values;
    }

    /** A true/false argument, refused when it is neither. */
    public static boolean booleanArg(Ctx ctx, int index, boolean fallback) {
        if (index >= ctx.args().size()) {
            return fallback;
        }
        String value = ctx.arg(index).toLowerCase(java.util.Locale.ROOT);
        return switch (value) {
            case "true", "yes", "on", "1" -> true;
            case "false", "no", "off", "0" -> false;
            default -> throw CommandRegistry.error("Expected true or false, got '" + ctx.arg(index) + "'");
        };
    }

    /** The {@code active}/{@code inactive} pair of upstream's {@code HookMode}. */
    public static boolean hookMode(String word) {
        return switch (word.toLowerCase(java.util.Locale.ROOT)) {
            case "active" -> true;
            case "inactive" -> false;
            default -> throw CommandRegistry.error("Hook mode must be active or inactive, got '" + word + "'");
        };
    }

    /**
     * A side effect list, which is a comma separated list of names with an
     * optional {@code =on}, {@code =off} or {@code =delayed} behind each one; a
     * name on its own turns that effect on, and a state on its own applies to
     * every effect, the way {@code /perf} takes it.
     */
    public static com.maxlananas.fawebim.core.session.SideEffectSet sideEffectSet(String input) {
        com.maxlananas.fawebim.core.session.SideEffectSet set =
                com.maxlananas.fawebim.core.session.SideEffectSet.defaults();
        for (String part : input.split(",")) {
            String token = part.trim();
            if (token.isEmpty()) {
                continue;
            }
            int separator = token.indexOf('=');
            String name = separator < 0 ? token : token.substring(0, separator);
            com.maxlananas.fawebim.core.session.SideEffect effect =
                    com.maxlananas.fawebim.core.session.SideEffect.parse(name);
            if (effect != null) {
                com.maxlananas.fawebim.core.session.SideEffect.State state =
                        com.maxlananas.fawebim.core.session.SideEffect.State.ON;
                if (separator >= 0) {
                    state = com.maxlananas.fawebim.core.session.SideEffect.State
                            .parse(token.substring(separator + 1));
                    if (state == null) {
                        throw CommandRegistry.error("A side effect state must be on, off or delayed: '"
                                + token + "'");
                    }
                }
                set = set.with(effect, state);
                continue;
            }
            com.maxlananas.fawebim.core.session.SideEffect.State state =
                    com.maxlananas.fawebim.core.session.SideEffect.State.parse(token);
            if (state == null) {
                throw CommandRegistry.error("Unknown side effect '" + token + "'; try one of "
                        + com.maxlananas.fawebim.core.session.SideEffect.names());
            }
            for (com.maxlananas.fawebim.core.session.SideEffect each
                    : com.maxlananas.fawebim.core.session.SideEffect.values()) {
                set = set.with(each, state);
            }
        }
        return set;
    }

    public static int block(Ctx ctx, String input) {
        BlockStateRegistry registry = BlockState.registry();
        int id = registry.parse(input);
        if (id < 0) {
            id = registry.blockFromItem(input);
        }
        if (id < 0) {
            throw CommandRegistry.error("Unknown block or item '" + input + "'");
        }
        return id;
    }

    /** Parses a biome name, failing with the message FAWE uses. */
    public static int biome(String input) {
        int id = BlockState.registry().biome(input);
        if (id < 0) {
            throw CommandRegistry.error("Unknown biome '" + input + "'");
        }
        return id;
    }

    /**
     * A tree type as WorldEdit names them - oak, redwood, random - in its
     * canonical spelling, or a worldgen feature id, which grows what it names:
     * {@code /tool tree minecraft:azalea_tree}.
     */
    public static String treeType(String input) {
        String type = com.maxlananas.fawebim.core.world.TreeTypes.canonical(input);
        if (type != null) {
            return type;
        }
        if (input != null && input.indexOf(':') > 0) {
            return input.trim().toLowerCase(Locale.ROOT);
        }
        throw CommandRegistry.error("Unknown tree type '" + input + "'. Try: "
                + com.maxlananas.fawebim.core.world.TreeTypes.names());
    }

    /**
     * A worldgen feature id, namespaced as the game names it, refused when the
     * world knows its features and this is not one of them.
     */
    public static String feature(com.maxlananas.fawebim.core.world.World world, String input) {
        return registryId(world == null ? List.of() : world.featureIds(), input, "feature");
    }

    /** A worldgen structure id, as {@link #feature}. */
    public static String structure(com.maxlananas.fawebim.core.world.World world, String input) {
        return registryId(world == null ? List.of() : world.structureIds(), input, "structure");
    }

    private static String registryId(List<String> known, String input, String kind) {
        String id = input.trim().toLowerCase(Locale.ROOT);
        if (id.indexOf(':') < 0) {
            id = "minecraft:" + id;
        }
        if (input.isBlank() || !known.isEmpty() && java.util.Collections.binarySearch(known, id) < 0) {
            throw CommandRegistry.error("Unknown " + kind + " '" + input.trim() + "'");
        }
        return id;
    }

    /** Parses a pattern: blocks, weighted lists, {@code #clipboard}, {@code ^} ... */
    public static Pattern pattern(String input, Ctx ctx) {
        try {
            return pattern0(input, ctx);
        } catch (IndexOutOfBoundsException | IllegalArgumentException e) {
            throw CommandRegistry.error("Invalid pattern '" + input.trim() + "': " + e.getMessage());
        }
    }

    private static Pattern pattern0(String input, Ctx ctx) {
        String trimmed = input.trim();
        if (trimmed.isEmpty()) {
            throw CommandRegistry.error("Empty pattern");
        }
        if (trimmed.startsWith("*")) {
            // "*oak_log": a random state of the block, the way WorldEdit parses it.
            List<Integer> states = BlockState.registry().statesOf(trimmed.substring(1).trim());
            if (states.isEmpty()) {
                throw CommandRegistry.error("Unknown block '" + trimmed.substring(1) + "'");
            }
            return new Patterns.RandomState(states);
        }
        if (trimmed.startsWith("$")) {
            return biomePattern(trimmed.substring(1).trim(), ctx);
        }
        if (trimmed.startsWith("##")) {
            return tagPattern(trimmed.substring(2));
        }
        if (trimmed.startsWith("#")) {
            return hashPattern(trimmed, ctx);
        }
        if (trimmed.startsWith("^")) {
            return typeOrStatePattern(trimmed.substring(1), ctx);
        }
        if (trimmed.contains(",")) {
            Patterns.Weighted weighted = new Patterns.Weighted();
            for (String part : Str.splitCommas(trimmed)) {
                String piece = part.trim();
                double weight = 1;
                String body = piece;
                int percent = piece.indexOf('%');
                if (percent > 0 && Str.isDouble(piece.substring(0, percent))) {
                    weight = Double.parseDouble(piece.substring(0, percent));
                    body = piece.substring(percent + 1);
                }
                weighted.add(weight, pattern(body, ctx));
            }
            return weighted;
        }
        if (trimmed.startsWith("=")) {
            return new Patterns.ExpressionPattern(trimmed.substring(1));
        }
        return new Patterns.Single(block(ctx, trimmed));
    }

    /**
     * WorldEdit's {@code ##tag} pattern: a random block of a block tag, or of
     * one of the mod's categories, in its default state, and with
     * {@code ##*tag} in any of its states. It was never reached: the {@code #}
     * patterns were asked first and did not know it.
     */
    private static Pattern tagPattern(String input) {
        boolean anyState = input.startsWith("*");
        String name = (anyState ? input.substring(1) : input).trim().toLowerCase(Locale.ROOT);
        BlockStateRegistry registry = BlockState.registry();
        boolean category = registry.categories().contains(name);
        String tag = name.indexOf(':') < 0 ? "minecraft:" + name : name;
        if (!category && !registry.blockTags().contains(tag)) {
            throw CommandRegistry.error("Unknown block tag '" + name + "'");
        }
        List<Integer> states = new ArrayList<>();
        for (String block : registry.blockNames()) {
            int defaultState = registry.defaultState(block);
            if (defaultState < 0 || !(category ? registry.matchesCategory(defaultState, name)
                    : registry.hasTag(defaultState, tag))) {
                continue;
            }
            if (anyState) {
                states.addAll(registry.statesOf(block));
            } else {
                states.add(defaultState);
            }
        }
        if (states.isEmpty()) {
            throw CommandRegistry.error("The block tag '" + name + "' has no blocks");
        }
        return new Patterns.RandomState(states);
    }

    /**
     * WorldEdit's {@code ^} pattern: {@code ^type} applies a block type and keeps
     * the properties of each block it replaces, {@code ^[property=value,...]}
     * applies properties and keeps each block, and {@code ^type[...]} both.
     */
    private static Pattern typeOrStatePattern(String input, Ctx ctx) {
        int bracket = input.indexOf('[');
        String type = (bracket < 0 ? input : input.substring(0, bracket)).trim();
        Map<String, String> states = new java.util.LinkedHashMap<>();
        if (bracket >= 0) {
            if (!input.endsWith("]")) {
                throw CommandRegistry.error("Missing ] in '^" + input + "'");
            }
            for (String pair : Str.splitCommas(input.substring(bracket + 1, input.length() - 1))) {
                int equals = pair.indexOf('=');
                if (equals <= 0 || equals == pair.length() - 1) {
                    throw CommandRegistry.error("Expected property=value in '^" + input + "', got '"
                            + pair.trim() + "'");
                }
                states.put(pair.substring(0, equals).trim().toLowerCase(Locale.ROOT),
                        pair.substring(equals + 1).trim().toLowerCase(Locale.ROOT));
            }
        }
        if (type.isEmpty() && states.isEmpty()) {
            throw CommandRegistry.error("Syntax: ^<block>, ^[property=value] or ^<block>[property=value]");
        }
        int typeState = -1;
        if (!type.isEmpty()) {
            typeState = BlockState.registry().defaultState(Str.withNamespace(type.toLowerCase(Locale.ROOT)));
            if (typeState < 0) {
                throw CommandRegistry.error("Unknown block '" + type + "'");
            }
        }
        return new Patterns.TypeOrStateApplying(typeState, states);
    }

    private static Pattern hashPattern(String input, Ctx ctx) {
        String key = input.substring(1);
        String lower = key.toLowerCase(Locale.ROOT);
        int bracket = lower.indexOf('[');
        String id = bracket < 0 ? lower : lower.substring(0, bracket);
        String args = bracketArguments(key, bracket);

        Extent extent = ctx.hasSelection() ? extOf(ctx) : ctx.world();
        switch (id) {
            case "clipboard", "copy", "c", "fullcopy" -> {
                if (!ctx.session().hasClipboard()) {
                    throw CommandRegistry.error("No clipboard: copy something first");
                }
                var holder = ctx.session().getClipboard();
                // The pattern reads the clipboard from its lowest corner, which
                // is not where the origin is since //copy takes the player's.
                BlockVector3 corner = holder.getClipboard().getBox().min();
                return new Patterns.ClipboardPattern(holder.getClipboard(), corner, id.equals("fullcopy"), false);
            }
            case "existing" -> {
                return new Patterns.Existing(extent);
            }
            case "biome" -> {
                return biomePattern(args, ctx);
            }
            case "hotbar" -> {
                List<Integer> blocks = ctx.actor().hotbarBlocks();
                if (blocks.isEmpty()) {
                    throw CommandRegistry.error("#hotbar needs blocks in the hotbar");
                }
                return new Patterns.RandomState(blocks);
            }
            case "mask" -> {
                List<String> parts = Str.bracketGroups(input);
                if (parts.size() != 3) {
                    throw CommandRegistry.error("Syntax: #mask[mask][pattern][pattern]");
                }
                return new Patterns.Masked(mask(parts.get(0), ctx), pattern(parts.get(1), ctx),
                        pattern(parts.get(2), ctx));
            }
            case "buffer", "buffer2d" -> {
                List<String> parts = Str.bracketGroups(input);
                if (parts.isEmpty()) {
                    throw CommandRegistry.error(id.equals("buffer2d")
                            ? "Syntax: #buffer2d[pattern][size]"
                            : "Syntax: #buffer[pattern][size]");
                }
                Pattern inner = pattern(parts.get(0), ctx);
                int size = parts.size() > 1 ? Integer.parseInt(parts.get(1).trim()) : 128;
                return new Patterns.Buffered(inner, size, id.equals("buffer2d"));
            }
            case "nx", "nox", "!x", "ny", "noy", "!y", "nz", "noz", "!z" -> {
                List<String> parts = Str.bracketGroups(input);
                if (parts.size() != 1) {
                    throw CommandRegistry.error("Syntax: #" + id + "[pattern]");
                }
                int axis = id.endsWith("x") ? 0 : id.endsWith("y") ? 1 : 2;
                return new Patterns.NoAxis(pattern(parts.get(0), ctx), axis);
            }
            case "offset", "spread", "randomoffset", "solidspread" -> {
                // FAWE's #offset[pattern][x][y][z] and #spread[pattern][x][y][z],
                // or one distance for the three: [pattern][n].
                List<String> parts = Str.bracketGroups(input);
                if (parts.size() != 2 && parts.size() != 4) {
                    throw CommandRegistry.error("Syntax: #" + id + "[pattern][x][y][z] or #" + id
                            + "[pattern][distance], e.g. #" + id + "[stone][2][0][4]");
                }
                Pattern inner = pattern(parts.get(0), ctx);
                int dx = intArgument(parts.get(1));
                int dy = parts.size() == 4 ? intArgument(parts.get(2)) : dx;
                int dz = parts.size() == 4 ? intArgument(parts.get(3)) : dx;
                if (id.equals("offset")) {
                    return new Patterns.Offset(inner, new BlockVector3(dx, dy, dz));
                }
                if (dx < 0 || dy < 0 || dz < 0) {
                    throw CommandRegistry.error("The distances of #" + id + " must not be negative");
                }
                return new Patterns.RandomOffset(inner, dx, dy, dz, id.equals("solidspread"));
            }
            case "surfacespread" -> {
                List<String> parts = Str.bracketGroups(input);
                if (parts.size() != 2) {
                    throw CommandRegistry.error("Syntax: #surfacespread[pattern][distance], e.g. "
                            + "#surfacespread[#existing][4]");
                }
                return new Patterns.SurfaceSpread(pattern(parts.get(0), ctx), intArgument(parts.get(1)),
                        ctx.world().minY(), ctx.world().maxY());
            }
            case "l", "linear", "l2d", "linear2d", "l3d", "linear3d" -> {
                return linearPattern(id, input, ctx);
            }
            case "simplex", "perlin", "voronoi", "rmf" -> {
                List<String> parts = Str.bracketGroups(input);
                if (parts.size() != 2) {
                    throw CommandRegistry.error("Syntax: #" + id + "[scale][pattern] (e.g. #"
                            + id + "[5][dirt,stone])");
                }
                double scale = 1d / Math.max(1d, parseDouble(parts.get(0).trim()));
                Noise noise = switch (id) {
                    case "simplex" -> new Noise.Simplex(0);
                    case "perlin" -> new Noise.Perlin(0);
                    case "voronoi" -> new Noise.Voronoi(0);
                    default -> new Noise.RidgedMultiFractal(0, 3, 2, 0.5);
                };
                Pattern inner = pattern(parts.get(1).trim(), ctx);
                if (inner instanceof Patterns.Weighted weighted) {
                    return new Patterns.NoiseChoice(noise, weighted, scale);
                }
                if (inner instanceof Patterns.Single) {
                    // A single block cannot be spread out by noise, so upstream
                    // hands it back untouched.
                    return inner;
                }
                throw CommandRegistry.error("Only a list of blocks can be used with #" + id
                        + ", got '" + parts.get(1).trim() + "'");
            }
            case "swaptype", "ts", "typeswap" -> {
                return new Patterns.TypeSwap();
            }
            case "rel", "r", "relative", "~" -> {
                BlockVector3 origin = ctx.placement();
                return new Patterns.Relative(pattern(args, ctx), origin);
            }
            case "color", "colour", "averagecolor", "anglecolor" -> {
                List<String> parts = Str.splitCommas(args);
                int rgb = 0xFFFFFF;
                if (parts.size() >= 3) {
                    rgb = (Integer.parseInt(parts.get(0).trim()) << 16)
                            | (Integer.parseInt(parts.get(1).trim()) << 8)
                            | Integer.parseInt(parts.get(2).trim());
                }
                return new Patterns.Color(rgb);
            }
            case "lighten", "darken", "saturate", "desaturate" -> {
                double amount = args.isEmpty() ? 0.1 : Double.parseDouble(args);
                Patterns.ColorAdjust.Mode mode = switch (id) {
                    case "lighten" -> Patterns.ColorAdjust.Mode.LIGHTEN;
                    case "darken" -> Patterns.ColorAdjust.Mode.DARKEN;
                    case "saturate" -> Patterns.ColorAdjust.Mode.SATURATE;
                    default -> Patterns.ColorAdjust.Mode.DESATURATE;
                };
                return new Patterns.ColorAdjust(extOf(ctx), mode, amount);
            }
            default -> throw CommandRegistry.error("Unknown pattern '" + input + "'");
        }
    }

    /**
     * FAWE's linear patterns over a list of blocks: {@code #linear[pattern]} the
     * entries one after the other, {@code #linear2d[pattern][xscale][zscale]}
     * and {@code #linear3d[pattern][xscale][yscale][zscale]} in bands across the
     * edit. A single block is itself; anything but a list is refused.
     */
    private static Pattern linearPattern(String id, String input, Ctx ctx) {
        List<String> parts = Str.bracketGroups(input);
        boolean flat = id.contains("2d");
        boolean sequence = !flat && !id.contains("3d");
        int scales = sequence ? 0 : flat ? 2 : 3;
        if (parts.isEmpty() || parts.size() > 1 + scales) {
            throw CommandRegistry.error(sequence ? "Syntax: #linear[pattern], e.g. #linear[stone,dirt]"
                    : "Syntax: #" + id + "[pattern]" + (flat ? "[xscale][zscale]" : "[xscale][yscale][zscale]")
                    + ", e.g. #" + id + "[stone,dirt]");
        }
        Pattern inner = pattern(parts.get(0), ctx);
        if (inner instanceof Patterns.Single) {
            return inner;
        }
        if (!(inner instanceof Patterns.Weighted list)) {
            throw CommandRegistry.error("Only a list of blocks can be used with #" + id + ", got '"
                    + parts.get(0).trim() + "'");
        }
        if (sequence) {
            // Upstream's list is a set: a block written twice is one entry.
            List<Pattern> entries = new ArrayList<>();
            java.util.Set<Integer> seen = new java.util.HashSet<>();
            for (int i = 0; i < list.size(); i++) {
                Pattern entry = list.get(i);
                if (!(entry instanceof Patterns.Single single) || seen.add(single.stateId())) {
                    entries.add(entry);
                }
            }
            return new Patterns.LinearCycle(entries.toArray(new Pattern[0]));
        }
        int[] scale = {1, 1, 1};
        for (int i = 1; i < parts.size(); i++) {
            scale[i - 1] = intArgument(parts.get(i));
            if (scale[i - 1] == 0) {
                throw CommandRegistry.error("The scales of #" + id + " must not be 0");
            }
        }
        return flat ? new Patterns.LinearChoice(list, scale[0], 0, scale[1])
                : new Patterns.LinearChoice(list, scale[0], scale[1], scale[2]);
    }

    /**
     * FAWE's wall mask, written as {@code #beside[mask][min][max]} or
     * {@code |[mask][min][max]}: how many of the four horizontal neighbours the
     * sub-mask accepts. Without arguments it is the default of one to eight.
     */
    private static Mask besideMask(String input, Ctx ctx) {
        List<String> parts = Str.bracketGroups(input);
        if (parts.isEmpty()) {
            throw CommandRegistry.error("Syntax: #beside[mask][min][max]");
        }
        Mask source = mask(parts.get(0), ctx);
        int min = parts.size() > 1 ? Integer.parseInt(parts.get(1).trim()) : 1;
        int max = parts.size() > 2 ? Integer.parseInt(parts.get(2).trim()) : 8;
        return new Masks.WallMask(source, min, max);
    }

    /** The biome pattern, written as {@code #biome[<biome>]} or {@code $<biome>}. */
    private static Pattern biomePattern(String biome, Ctx ctx) {
        int biomeId = biome.isEmpty() ? 0 : BlockState.registry().biome(biome);
        if (biomeId < 0) {
            throw CommandRegistry.error("Unknown biome '" + biome + "'");
        }
        return new Patterns.Biome(biomeId, extOf(ctx));
    }

    /**
     * What a parsed mask or pattern reads: the extent of the operation that
     * parses it when that operation set one, else the session's reader of the
     * world the player edits, which a mask kept for later keeps following.
     */
    private static Extent extOf(Ctx ctx) {
        Extent extent = com.maxlananas.fawebim.core.mask.Masks.ExtentHolder.get();
        return extent == null ? ctx.session().worldReader() : extent;
    }

    /** Parses a mask expression: unions ({@code ,}), intersections ({@code &}) and {@code #id}s. */
    public static Mask mask(String input, Ctx ctx) {
        try {
            return mask0(input, ctx);
        } catch (IndexOutOfBoundsException | IllegalArgumentException e) {
            throw CommandRegistry.error("Invalid mask '" + input.trim() + "': " + e.getMessage());
        }
    }

    private static Mask mask0(String input, Ctx ctx) {
        String trimmed = input.trim();
        if (trimmed.isEmpty()) {
            throw CommandRegistry.error("Empty mask");
        }
        List<String> union = Str.splitTopLevel(trimmed, ',');
        if (union.size() > 1) {
            List<Mask> masks = new ArrayList<>();
            for (String part : union) {
                masks.add(mask(part, ctx));
            }
            return new Masks.UnionMask(masks);
        }
        List<String> intersection = Str.splitTopLevel(trimmed, '&');
        if (intersection.size() > 1) {
            List<Mask> masks = new ArrayList<>();
            for (String part : intersection) {
                masks.add(mask(part, ctx));
            }
            return new Masks.IntersectionMask(masks);
        }
        if (trimmed.startsWith("!")) {
            return new Masks.NegateMask(mask(trimmed.substring(1), ctx));
        }
        if (trimmed.startsWith("%")) {
            // WorldEdit's noise mask: "%50", or "%[50]" in FAWE's richer syntax.
            String percentage = trimmed.substring(1).trim();
            if (percentage.startsWith("[") && percentage.endsWith("]")) {
                percentage = percentage.substring(1, percentage.length() - 1).trim();
            }
            int value;
            try {
                value = Integer.parseInt(percentage);
            } catch (NumberFormatException e) {
                throw CommandRegistry.error("Invalid noise percentage '" + percentage
                        + "'; write for example %50");
            }
            return new Masks.RandomMask(value / 100.0);
        }
        if (trimmed.startsWith("=")) {
            return new Masks.ExpressionMask(trimmed.substring(1), extOf(ctx), new Random());
        }
        if (trimmed.startsWith("##")) {
            return new Masks.BlockMask(extOf(ctx), List.of(trimmed));
        }
        if (trimmed.startsWith("#")) {
            return hashMask(trimmed, ctx);
        }
        if (trimmed.startsWith("~")) {
            // FAWE's adjacent mask: ~[mask][min][max], ~2d[...] for the flat variant.
            boolean flat = trimmed.startsWith("~2d");
            List<String> parts = Str.bracketGroups(trimmed);
            if (parts.isEmpty()) {
                throw CommandRegistry.error("Syntax: ~[mask][min][max]");
            }
            Mask source = mask(parts.get(0), ctx);
            int min = parts.size() > 1 ? Integer.parseInt(parts.get(1).trim()) : 1;
            int max = parts.size() > 2 ? Integer.parseInt(parts.get(2).trim()) : flat ? 4 : 8;
            if (min == 1 && max >= (flat ? 4 : 8)) {
                return flat ? new Masks.AdjacentAny2DMask(source) : new Masks.AdjacentAnyMask(source);
            }
            return flat ? new Masks.Adjacent2DMask(source, min, max) : new Masks.AdjacentMask(source, min, max);
        }
        if (trimmed.startsWith("|")) {
            return besideMask(trimmed, ctx);
        }
        if (trimmed.startsWith("{")) {
            List<String> parts = Str.bracketGroups(trimmed);
            if (parts.size() != 2) {
                throw CommandRegistry.error("Syntax: {[min][max]");
            }
            return new Masks.RadiusShellMask(Integer.parseInt(parts.get(0).trim()),
                    Integer.parseInt(parts.get(1).trim()));
        }
        if (trimmed.startsWith("/")) {
            return angleMask("angle", trimmed.substring(1), extOf(ctx));
        }
        if (trimmed.startsWith(">") || trimmed.startsWith("<")) {
            // WorldEdit's offset masks: ">stone" is a block over stone, "<stone"
            // one under it, and alone the sign stands for any block.
            Mask beside = trimmed.length() > 1 ? mask(trimmed.substring(1), ctx)
                    : new Masks.ExistingMask(extOf(ctx));
            return new Masks.OffsetMask(extOf(ctx), beside, 0, trimmed.charAt(0) == '>' ? -1 : 1, 0);
        }
        if (trimmed.startsWith("$")) {
            return biomeMask(trimmed.substring(1), extOf(ctx));
        }
        if (trimmed.startsWith("^")) {
            return blockStateMask(trimmed, extOf(ctx));
        }
        // Plain block list, e.g. "stone,dirt,oak_log[axis=y]".
        return new Masks.BlockMask(extOf(ctx), List.of(trimmed));
    }

    /**
     * WorldEdit's biome mask, {@code $plains}, or with FAWE's brackets several
     * biomes at once, {@code $[plains,desert]}.
     */
    private static Mask biomeMask(String input, Extent extent) {
        String list = input.startsWith("[") && input.endsWith("]") ? input.substring(1, input.length() - 1) : input;
        List<String> names = Str.splitCommas(list);
        int[] ids = new int[names.size()];
        for (int i = 0; i < ids.length; i++) {
            String name = names.get(i).trim();
            ids[i] = BlockState.registry().biome(name);
            if (ids[i] < 0) {
                throw CommandRegistry.error("Unknown biome '" + name + "'");
            }
        }
        if (ids.length == 0) {
            throw CommandRegistry.error("Syntax: $<biome> or $[<biome>,<biome>]");
        }
        return new Masks.BiomeMask(extent, ids);
    }

    /**
     * WorldEdit's block state mask: {@code ^[property=value,...]}, or strict,
     * {@code ^=[...]}, where a block must have at least one of the properties.
     */
    private static Mask blockStateMask(String input, Extent extent) {
        boolean strict = input.startsWith("^=");
        String body = input.substring(strict ? 2 : 1).trim();
        if (!body.startsWith("[") || !body.endsWith("]")) {
            throw CommandRegistry.error("Syntax: ^[property=value,...] or ^=[property=value,...]");
        }
        Map<String, String> states = new java.util.LinkedHashMap<>();
        for (String pair : Str.splitCommas(body.substring(1, body.length() - 1))) {
            int equals = pair.indexOf('=');
            if (equals <= 0 || equals == pair.length() - 1) {
                throw CommandRegistry.error("Expected property=value in '" + input + "', got '" + pair.trim() + "'");
            }
            states.put(pair.substring(0, equals).trim().toLowerCase(Locale.ROOT),
                    pair.substring(equals + 1).trim().toLowerCase(Locale.ROOT));
        }
        return new Masks.BlockStateMask(extent, states, strict);
    }

    /**
     * Parses FAWE's slope masks: {@code #angle[<min>,<max>][,o]},
     * {@code #roc[...]} and {@code #surfaceangle[<min>,<max>[,<size>]]}.
     *
     * <p>Limits are tangents unless they end in {@code d}, which is FAWE's way of
     * writing degrees; {@code #angle} and {@code #roc} take the extra {@code -o} or
     * {@code o} flag to keep only the blocks with air next to them.</p>
     */
    private static Mask angleMask(String id, String args, Extent extent) {
        // FAWE writes the limits as [min][max], older documentation as min,max.
        String[] tokens = args.replace("[", ",").replace("]", ",").replace("-", "").split("[,\\s]+");
        java.util.List<String> values = new java.util.ArrayList<>();
        boolean overlay = false;
        for (String token : tokens) {
            if (token.isEmpty()) {
                continue;
            }
            if (token.equals("o")) {
                overlay = true;
            } else {
                values.add(token);
            }
        }
        if (id.equals("surfaceangle")) {
            double min = values.isEmpty() ? 0 : Double.parseDouble(values.get(0).replace("d", ""));
            double max = values.size() < 2 ? 90 : Double.parseDouble(values.get(1).replace("d", ""));
            int size = values.size() < 3 ? 1 : Integer.parseInt(values.get(2));
            return new Masks.SurfaceAngleMask(extent, min, max, size);
        }
        double min = values.isEmpty() ? 0 : slopeLimit(values.get(0));
        double max = values.size() < 2 ? Math.tan(Math.PI / 2) : slopeLimit(values.get(1));
        if (id.equals("roc")) {
            return new Masks.ROCAngleMask(extent, min, max, overlay, 4);
        }
        return new Masks.AngleMask(extent, min, max, overlay, 1);
    }

    /** Degrees with the {@code d} suffix become tangents; anything else is a tangent. */
    /** A percentage of the noise band as FAWE reads it: {@code 50} is the middle. */
    private static double noisePercent(String value) {
        return (Double.parseDouble(value.trim()) - 50d) / 50d;
    }

    /**
     * The text inside the brackets of a rich input such as {@code #perlin[5][stone]}.
     * An input that never closes its bracket has no arguments, which every parser
     * below reports as the syntax error it is; slicing to the last character used
     * to answer with a string index rather than a message.
     */
    private static String bracketArguments(String key, int bracket) {
        if (bracket < 0 || key.length() <= bracket + 1 || !key.endsWith("]")) {
            return "";
        }
        return key.substring(bracket + 1, key.length() - 1);
    }

    private static int intArgument(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw CommandRegistry.error("Expected a whole number, got '" + value.trim() + "'");
        }
    }

    private static double parseDouble(String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            throw CommandRegistry.error("Expected a number, got '" + value + "'");
        }
    }

    private static double slopeLimit(String value) {
        if (value.endsWith("d")) {
            return Math.tan(Math.toRadians(Double.parseDouble(value.substring(0, value.length() - 1))));
        }
        return Double.parseDouble(value);
    }

    private static Mask hashMask(String input, Ctx ctx) {
        String key = input.substring(1);
        String lower = key.toLowerCase(Locale.ROOT);
        int bracket = lower.indexOf('[');
        String id = bracket < 0 ? lower : lower.substring(0, bracket);
        String args = bracketArguments(key, bracket);
        Extent extent = extOf(ctx);
        switch (id) {
            case "air" -> {
                return new Masks.AirMask(extent, false);
            }
            case "existing" -> {
                return new Masks.ExistingMask(extent);
            }
            case "solid" -> {
                return new Masks.SolidMask(extent);
            }
            case "liquid" -> {
                return new Masks.LiquidMask(extent);
            }
            case "fullcube" -> {
                return new Masks.FullCubeMask(extent);
            }
            case "wall" -> {
                // FAWE's #wall: a block that exists and has a horizontal air side.
                return new Masks.IntersectionMask(List.of(new Masks.ExistingMask(extent),
                        new Masks.WallMask(new Masks.AirMask(extent, false), 1, 8)));
            }
            case "surface" -> {
                int offset = args.isEmpty() ? 1 : Integer.parseInt(args);
                return new Masks.SurfaceMask(extent, offset, false);
            }
            case "angle", "surfaceangle", "roc" -> {
                return angleMask(id, args, extent);
            }
            case "beside" -> {
                return besideMask(input, ctx);
            }
            case "extrema" -> {
                String[] parts = args.split(",");
                int minY = parts.length > 0 && !parts[0].isEmpty() ? Integer.parseInt(parts[0]) : -64;
                int maxY = parts.length > 1 ? Integer.parseInt(parts[1]) : 319;
                return new Masks.ExtremaMask(extent, minY, maxY);
            }
            case "xaxis", "yaxis", "zaxis" -> {
                int axis = id.equals("xaxis") ? 0 : id.equals("yaxis") ? 1 : 2;
                double radius = args.isEmpty() ? 8 : Double.parseDouble(args);
                return new Masks.AxisMask(axis, radius);
            }
            case "true" -> {
                return new Masks.ConstantMask(true);
            }
            case "false" -> {
                return new Masks.ConstantMask(false);
            }
            case "exposed" -> {
                return new Masks.ExposedMask(extent);
            }
            case "biome" -> {
                int biomeId = BlockState.registry().biome(args);
                if (biomeId < 0) {
                    throw CommandRegistry.error("Unknown biome '" + args + "'");
                }
                return new Masks.BiomeMask(extent, biomeId);
            }
            case "region", "sel", "selection" -> {
                if (!ctx.hasSelection()) {
                    throw CommandRegistry.error("No selection for #region mask");
                }
                return new Masks.RegionMask(ctx.selection());
            }
            case "dregion", "dsel", "dselection" -> {
                return new Masks.LazyRegionMask(() -> ctx.session().getSelection(ctx.world()));
            }
            case "offset" -> {
                // FAWE's #offset[x][y][z][mask]: the mask tested that far away.
                List<String> parts = Str.bracketGroups(input);
                if (parts.size() == 4) {
                    return new Masks.OffsetMask(extent, mask(parts.get(3), ctx), intArgument(parts.get(0)),
                            intArgument(parts.get(1)), intArgument(parts.get(2)));
                }
                // The older #offset[x,y,z], which offsets the global mask.
                String[] offset = args.split(",");
                Mask delegate = ctx.session().getMask();
                if (parts.size() != 1 || offset.length != 3 || delegate == null) {
                    throw CommandRegistry.error("Syntax: #offset[x][y][z][mask], e.g. #offset[0][-1][0][stone]");
                }
                return new Masks.OffsetMask(extent, delegate, intArgument(offset[0]), intArgument(offset[1]),
                        intArgument(offset[2]));
            }
            case "simplex" -> {
                List<String> parts = Str.bracketGroups(input);
                if (parts.size() != 3) {
                    throw CommandRegistry.error("Syntax: #simplex[scale][min][max], min and max"
                            + " being percentages of the noise band");
                }
                double scale = 1d / Math.max(1d, parseDouble(parts.get(0).trim()));
                return new Masks.SimplexMask(scale, noisePercent(parts.get(1)),
                        noisePercent(parts.get(2)));
            }
            default -> throw CommandRegistry.error("Unknown mask '" + input + "'");
        }
    }

    /** Region shapes for {@code /brush} style arguments. */
    public static Region region(Ctx ctx, String shape, BlockVector3 center, double radius) {
        com.maxlananas.fawebim.core.region.RegionFactory factory =
                com.maxlananas.fawebim.core.region.RegionFactories.parse(shape, ctx.world().minY(), ctx.world().maxY());
        if (factory == null) {
            throw CommandRegistry.error("Unknown shape '" + shape + "'. Try sphere, cyl or cuboid.");
        }
        return factory.createCenteredAt(center, radius);
    }

    public static double parseDoubleMock(String input) {
        return Double.parseDouble(input);
    }

    public static RandomCollection<String> weightedList(List<String> inputs) {
        RandomCollection<String> collection = new RandomCollection<>();
        for (String input : inputs) {
            String piece = input.trim();
            double weight = 1;
            String body = piece;
            int percent = piece.indexOf('%');
            if (percent > 0) {
                weight = Double.parseDouble(piece.substring(0, percent));
                body = piece.substring(percent + 1);
            }
            collection.add(weight, body);
        }
        return collection;
    }
}
