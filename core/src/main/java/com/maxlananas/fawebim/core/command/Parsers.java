package com.maxlananas.fawebim.core.command;

import com.maxlananas.fawebim.core.mask.Mask;
import com.maxlananas.fawebim.core.mask.Masks;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.pattern.Pattern;
import com.maxlananas.fawebim.core.pattern.MapColors;
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
import java.util.Random;

/**
 * Parsers for the WorldEdit input syntax: blocks, patterns, masks and region
 * shapes. Ids, prefixes and aliases match FAWE exactly ({@code ##tag},
 * {@code #clipboard}, {@code 25%stone}, {@code #solid}, {@code #region}, ...).
 */
public final class Parsers {

    private Parsers() {
    }

    // ------------------------------------------------------------------ blocks

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

    // ---------------------------------------------------------------- patterns

    /** Parses a pattern: blocks, weighted lists, {@code #clipboard}, {@code ^} ... */
    public static Pattern pattern(String input, Ctx ctx) {
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
        if (trimmed.startsWith("#")) {
            return hashPattern(trimmed, ctx);
        }
        if (trimmed.startsWith("^")) {
            Pattern inner = pattern(trimmed.substring(1), ctx);
            return new Patterns.TypeOrStateApplying(inner);
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
        if (trimmed.startsWith("##")) {
            // Category pattern: random block from the category.
            List<String> names = new ArrayList<>();
            BlockStateRegistry registry = BlockState.registry();
            String category = trimmed.substring(2);
            for (int id = 0; id < registry.stateCount(); id++) {
                if (registry.matchesCategory(id, category)) {
                    names.add(registry.describe(id));
                }
            }
            if (names.isEmpty()) {
                throw CommandRegistry.error("Unknown block category '" + category + "'");
            }
            Patterns.RandomState state = new Patterns.RandomState(
                    names.stream().map(n -> BlockState.registry().parse(n)).filter(id -> id >= 0).toList());
            return state;
        }
        return new Patterns.Single(block(ctx, trimmed));
    }

    private static Pattern hashPattern(String input, Ctx ctx) {
        String key = input.substring(1);
        String lower = key.toLowerCase(Locale.ROOT);
        int bracket = lower.indexOf('[');
        String id = bracket < 0 ? lower : lower.substring(0, bracket);
        String args = bracket < 0 ? "" : key.substring(bracket + 1, key.length() - 1);

        Extent extent = ctx.hasSelection() ? extOf(ctx) : ctx.world();
        switch (id) {
            case "clipboard", "copy", "c", "fullcopy" -> {
                if (!ctx.session().hasClipboard()) {
                    throw CommandRegistry.error("No clipboard: copy something first");
                }
                var holder = ctx.session().getClipboard();
                BlockVector3 origin = holder.getClipboard().getOrigin();
                return new Patterns.ClipboardPattern(holder.getClipboard(), origin, id.equals("fullcopy"), false);
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
            case "offset" -> {
                String[] parts = args.isEmpty() ? new String[]{"0", "0", "0"} : args.split(",");
                return new Patterns.Offset(new Patterns.Single(BlockState.registry().air()),
                        new BlockVector3(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()),
                                Integer.parseInt(parts[2].trim())));
            }
            case "spread", "randomoffset", "solidspread", "surfacespread" -> {
                String[] parts = args.isEmpty() ? new String[]{"5", "5", "5"} : args.split(",");
                int dx = Integer.parseInt(parts[0].trim());
                int dy = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : dx;
                int dz = parts.length > 2 ? Integer.parseInt(parts[2].trim()) : dx;
                return new Patterns.RandomOffset(new Patterns.Single(BlockState.registry().air()), dx, dy, dz,
                        id.contains("solid"));
            }
            case "l", "linear", "l3d", "l2d" -> {
                List<String> parts = Str.splitCommas(args);
                if (parts.size() < 2) {
                    throw CommandRegistry.error("#linear needs two blocks, e.g. #l3d[stone][dirt]");
                }
                Pattern from = pattern(parts.get(0).replace("[", "").replace("]", ""), ctx);
                Pattern to = pattern(parts.get(1).replace("[", "").replace("]", ""), ctx);
                return new Patterns.Linear(from, to, true, true, true);
            }
            case "simplex", "perlin", "rmf", "voronoi" -> {
                List<String> parts = Str.splitCommas(args);
                Noise noise = switch (id) {
                    case "simplex" -> new Noise.Simplex(0);
                    case "perlin" -> new Noise.Perlin(0);
                    case "voronoi" -> new Noise.Voronoi(0);
                    default -> new Noise.RidgedMultiFractal(0, 3, 2, 0.5);
                };
                Pattern low = new Patterns.Single(BlockState.registry().air());
                Pattern high = new Patterns.Single(BlockState.registry().defaultState("minecraft:stone"));
                if (parts.size() >= 2) {
                    low = pattern(parts.get(0).trim(), ctx);
                    high = pattern(parts.get(1).trim(), ctx);
                }
                return new Patterns.NoisePattern(noise, 0.05, low, high);
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
                return new Patterns.Color(rgb, MapColors.palette(BlockState.registry()));
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

    private static Extent extOf(Ctx ctx) {
        Extent extent = com.maxlananas.fawebim.core.mask.Masks.ExtentHolder.get();
        return extent == null ? ctx.world() : extent;
    }

    // ------------------------------------------------------------------- masks

    /** Parses a mask expression: unions ({@code ,}), intersections ({@code &}) and {@code #id}s. */
    public static Mask mask(String input, Ctx ctx) {
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
        if (trimmed.startsWith("^")) {
            return angleMask("angle", trimmed.substring(1), extOf(ctx));
        }
        // Plain block list, e.g. "stone,dirt,oak_log[axis=y]".
        return new Masks.BlockMask(extOf(ctx), List.of(trimmed));
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
        String args = bracket < 0 ? "" : key.substring(bracket + 1, key.length() - 1);
        Extent extent = extOf(ctx);
        switch (id) {
            case "air" -> {
                return new Masks.AirMask(extent, false);
            }
            case "existing" -> {
                return new Masks.ExistingMask(extent, false);
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
                return new Masks.IntersectionMask(List.of(new Masks.ExistingMask(extent, false),
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
                String[] parts = args.split(",");
                int dx = parts.length > 0 && !parts[0].isEmpty() ? Integer.parseInt(parts[0]) : 0;
                int dy = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
                int dz = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
                Mask delegate = ctx.session().getMask();
                if (delegate == null) {
                    throw CommandRegistry.error("#offset needs a sub-mask, e.g. #offset[0,1,0][stone]");
                }
                return new Masks.OffsetMask(extent, delegate, dx, dy, dz);
            }
            case "simplex" -> {
                double threshold = args.isEmpty() ? 0.5 : Double.parseDouble(args);
                return new Masks.SimplexMask(threshold, 0.02);
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
