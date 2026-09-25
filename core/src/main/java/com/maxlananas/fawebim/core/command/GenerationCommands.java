package com.maxlananas.fawebim.core.command;

import com.maxlananas.fawebim.core.clipboard.Schematics;
import com.maxlananas.fawebim.core.expression.Expression;
import com.maxlananas.fawebim.core.extent.EditSession;
import com.maxlananas.fawebim.core.function.Operations;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.mask.Mask;
import com.maxlananas.fawebim.core.pattern.Pattern;
import com.maxlananas.fawebim.core.pattern.Patterns;
import com.maxlananas.fawebim.core.region.Region;
import com.maxlananas.fawebim.core.util.Images;
import com.maxlananas.fawebim.core.util.Msg;
import com.maxlananas.fawebim.core.world.BlockState;
import com.maxlananas.fawebim.core.world.BlockStateRegistry;
import com.maxlananas.fawebim.core.world.World;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * The generation commands FAWE adds on top of WorldEdit's shapes.
 *
 * <p>WorldEdit keeps {@code //sphere}, {@code //cyl}, {@code //pyramid} and
 * friends in the same class; those live in {@link Commands} here, and this class
 * holds the terrain generators: {@code //ores}, {@code //caves}, {@code //img},
 * {@code //generatebiome}, {@code //forestgen}, {@code //feature},
 * {@code //structure} and {@code //generate}.</p>
 */
final class GenerationCommands {

    private static final List<String> TREE_TYPES = List.of(
            "tree", "pine", "jungle", "mega_jungle", "mega_pine", "mega_spruce", "brown_mushroom",
            "red_mushroom", "crimson_fungus", "warped_fungus", "azalea", "mangrove");

    private final CommandRegistry registry;

    GenerationCommands(CommandRegistry registry) {
        this.registry = registry;
    }

    void register() {
        ores();
        caves();
        image();
        generateBiome();
        forestGen();
        feature();
        structure();
        generate();
    }

    /** {@code //ores} — scatters ore veins through the matching stone. */
    private void ores() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("ores", "/ore");
        if (entry == null) {
            return;
        }
        entry.description = "Generates ores in the region";
        entry.group = "generation";
        entry.requiresSelection = true;
        entry.arguments.add("mask");
        entry.arguments.add("pattern");
        entry.arguments.add("[size]");
        entry.arguments.add("[frequency]");
        entry.arguments.add("[rarity]");
        entry.handler = ctx -> {
            Region region = ctx.selection();
            Mask mask = ctx.mask(0);
            Pattern pattern = ctx.pattern(1);
            int size = ctx.intArg(2, 6);
            double frequency = ctx.doubleArg(3, 500);
            double rarity = ctx.doubleArg(4, 1);
            if (size < 1 || size > 64) {
                throw CommandRegistry.error("Size must be between 1 and 64");
            }
            if (frequency <= 0 || rarity <= 0) {
                throw CommandRegistry.error("Frequency and rarity must be greater than 0");
            }
            EditSession session = ctx.editSession("ores");
            int changed = Operations.ore(ctx.world(), session, region, mask, pattern, size, frequency, rarity,
                    new Random());
            session.flushQueue();
            ctx.actor().message(Msg.success(changed + " ore block(s) generated"));
        };
    }

    /** {@code //caves} — carves tunnels with the noise-driven cave generator. */
    private void caves() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("caves", "/carvecaves");
        if (entry == null) {
            return;
        }
        entry.description = "Generates caves in the region";
        entry.group = "generation";
        entry.requiresSelection = true;
        entry.arguments.add("[frequency]");
        entry.arguments.add("[rarity]");
        entry.arguments.add("[size]");
        entry.handler = ctx -> {
            Region region = ctx.selection();
            double frequency = ctx.doubleArg(0, 8);
            double rarity = ctx.doubleArg(1, 1);
            int size = ctx.intArg(2, 1);
            if (frequency <= 0 || frequency > 100) {
                throw CommandRegistry.error("Frequency is the chance in percent of a cave per 1000 blocks (0-100)");
            }
            if (rarity < 0 || rarity > 1) {
                throw CommandRegistry.error("Rarity is the chance a vein carves anything (0-1)");
            }
            if (size < 1 || size > 32) {
                throw CommandRegistry.error("Size must be between 1 and 32");
            }
            EditSession session = ctx.editSession("caves");
            int changed = Operations.caves(ctx.world(), session, region, new Random(), frequency, rarity, size);
            session.flushQueue();
            ctx.actor().message(Msg.success(changed + " cave block(s) carved"));
        };
    }

    /**
     * {@code //img} — builds terrain from a heightmap image read from the
     * schematics directory. {@code -a} adds the terrain, {@code -r} clears what
     * is above it.
     */
    private void image() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("img", "/image");
        if (entry == null) {
            return;
        }
        entry.description = "Build terrain from a heightmap image";
        entry.group = "generation";
        entry.requiresSelection = true;
        entry.arguments.add("image");
        entry.arguments.add("[pattern]");
        entry.booleanFlags.addAll(List.of("a", "r"));
        entry.handler = ctx -> {
            Path file = Schematics.directory().resolve(ctx.arg(0));
            Images.PixelSource image = Images.load(file);
            if (image == null) {
                throw CommandRegistry.error("Image '" + ctx.arg(0) + "' not found in " + Schematics.directory());
            }
            Region region = ctx.selection();
            BlockVector3 min = region.getMinimumPoint();
            BlockVector3 max = region.getMaximumPoint();
            int width = max.x() - min.x() + 1;
            int depth = max.z() - min.z() + 1;
            if (width != image.width() || depth != image.height()) {
                throw CommandRegistry.error("Image is " + image.width() + "x" + image.height()
                        + " but the selection is " + width + "x" + depth + "; select a matching area first");
            }
            boolean add = ctx.hasFlag("a");
            boolean remove = ctx.hasFlag("r");
            if (!add && !remove) {
                throw CommandRegistry.error("Use -a to add blocks, -r to clear above the heightmap, or both");
            }
            BlockStateRegistry states = BlockState.registry();
            int air = states.air();
            Pattern pattern = ctx.patternOrDefault(1, new Patterns.Single(air));
            EditSession session = ctx.editSession("img");
            int changed = 0;
            for (int x = min.x(); x <= max.x(); x++) {
                for (int z = min.z(); z <= max.z(); z++) {
                    session.checkTimeout();
                    int px = x - min.x();
                    int pz = z - min.z();
                    boolean empty = image.transparent(px, pz);
                    int height = empty ? -1
                            : (int) Math.round((image.rgb(px, pz) & 0xFF) / 255.0 * (max.y() - min.y()));
                    if (remove) {
                        for (int y = min.y() + height + 1; y <= max.y(); y++) {
                            if (session.setBlock(x, y, z, air)) {
                                changed++;
                            }
                        }
                    }
                    if (add) {
                        for (int y = min.y(); y <= min.y() + height; y++) {
                            if (session.setBlock(x, y, z, pattern.apply(new BlockVector3(x, y, z)))) {
                                changed++;
                            }
                        }
                    }
                }
            }
            session.flushQueue();
            ctx.actor().message(Msg.success("Image applied: " + changed + " block(s) changed"));
        };
    }

    /**
     * {@code //generatebiome} — assigns the biome returned by a formula to every
     * column of the selection. The formula sees {@code x}, {@code z},
     * {@code miny} and {@code maxy} and returns a biome id.
     */
    private void generateBiome() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("generatebiome", "/genbiome", "/gb");
        if (entry == null) {
            return;
        }
        entry.description = "Sets biome according to a formula";
        entry.group = "generation";
        entry.requiresSelection = true;
        // -c scales the formula around the centre of the selection, -o around the
        // player, -r is the plain world origin and the default scales the
        // selection to the -1..1 unit box, as WorldEdit's shape generator does.
        entry.booleanFlags.add("c");
        entry.booleanFlags.add("h");
        entry.booleanFlags.add("r");
        entry.booleanFlags.add("o");
        entry.arguments.add("biome");
        entry.arguments.add("formula");
        entry.handler = ctx -> {
            Region region = ctx.selection();
            int biomeId = Parsers.biome(ctx.arg(0));
            // The formula is everything after the biome, so it may be one
            // argument or several.
            Expression expression = Expression.compile(ctx.joined(1));
            World world = ctx.world();
            EditSession session = ctx.editSession("generatebiome");
            BlockVector3 min = region.getMinimumPoint();
            BlockVector3 max = region.getMaximumPoint();
            double[] origin = origin(ctx, region);
            double[] scale = scale(ctx, region, origin);
            Expression.Variables variables = new Expression.Variables();
            variables.set("miny", world.minY());
            variables.set("maxy", world.maxY());
            int changed = 0;
            // Biomes live on a 4x4x4 grid, so one sample per cell is enough.
            for (int x = min.x(); x <= max.x(); x++) {
                for (int z = min.z(); z <= max.z(); z++) {
                    session.checkTimeout();
                    for (int y = world.minY(); y < world.maxY(); y += 4) {
                        variables.set("x", (x - origin[0]) / scale[0]);
                        variables.set("y", (y - origin[1]) / scale[1]);
                        variables.set("z", (z - origin[2]) / scale[2]);
                        if (ctx.hasFlag("h") && !isSurface(world, x, y, z)) {
                            continue;
                        }
                        if (expression.evaluate(variables) <= 0) {
                            continue;
                        }
                        if (session.setBiome(x, y, z, biomeId)) {
                            changed++;
                        }
                    }
                }
            }
            session.flushQueue();
            ctx.actor().message(Msg.success("Biome set for " + changed + " biome cell(s)"));
        };
    }

    /** The point the formula's coordinates are measured from, per FAWE's switches. */
    private static double[] origin(Ctx ctx, Region region) {
        BlockVector3 min = region.getMinimumPoint();
        BlockVector3 max = region.getMaximumPoint();
        if (ctx.hasFlag("r")) {
            return new double[]{0, 0, 0};
        }
        if (ctx.hasFlag("o") && ctx.actor().position() != null) {
            BlockVector3 placement = ctx.actor().position();
            return new double[]{placement.x(), placement.y(), placement.z()};
        }
        // Both the plain form and -c measure from the centre; only the unit
        // differs, see scale().
        return new double[]{(min.x() + max.x()) / 2.0, (min.y() + max.y()) / 2.0, (min.z() + max.z()) / 2.0};
    }

    /** The unit the formula's coordinates are divided by, per FAWE's switches. */
    private static double[] scale(Ctx ctx, Region region, double[] origin) {
        if (ctx.hasFlag("r") || ctx.hasFlag("o") || ctx.hasFlag("c")) {
            return new double[]{1, 1, 1};
        }
        BlockVector3 min = region.getMinimumPoint();
        BlockVector3 max = region.getMaximumPoint();
        return new double[]{
                Math.max(1, Math.max(origin[0] - min.x(), max.x() - origin[0])),
                Math.max(1, Math.max(origin[1] - min.y(), max.y() - origin[1])),
                Math.max(1, Math.max(origin[2] - min.z(), max.z() - origin[2]))};
    }

    private void forestGen() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("//forestgen", "/forestgen");
        if (entry == null) {
            return;
        }
        entry.description = "Generate a forest in the selection";
        entry.group = "generation";
        entry.requiresSelection = true;
        entry.arguments.add("[size]");
        entry.arguments.add("[type]");
        entry.arguments.add("[density]");
        entry.handler = ctx -> {
            int size = ctx.intArg(0, 10);
            String type = ctx.arg(1, "tree").toLowerCase(Locale.ROOT);
            double density = ctx.doubleArg(2, 5) / 100.0;
            if (size < 1 || size > 50) {
                throw CommandRegistry.error("Tree size must be between 1 and 50");
            }
            if (!TREE_TYPES.contains(type)) {
                throw CommandRegistry.error("Unknown tree type '" + type + "'. Try: " + String.join(", ", TREE_TYPES));
            }
            if (density <= 0 || density > 0.5) {
                throw CommandRegistry.error("Density is a percentage between 0.1 and 50");
            }
            Region region = ctx.selection();
            World world = ctx.world();
            Random random = new Random();
            BlockVector3 min = region.getMinimumPoint();
            BlockVector3 max = region.getMaximumPoint();
            int attempts = (int) (region.getVolume() * density);
            int planted = 0;
            for (int i = 0; i < attempts; i++) {
                int x = min.x() + random.nextInt(max.x() - min.x() + 1);
                int z = min.z() + random.nextInt(max.z() - min.z() + 1);
                int ground = world.getHighestBlockY(x, z);
                if (ground < min.y() || ground > max.y()) {
                    continue;
                }
                if (world.generateTree(new BlockVector3(x, ground + 1, z), type, random)) {
                    planted++;
                }
            }
            ctx.actor().message(Msg.success("Planted " + planted + " tree(s) out of " + attempts + " attempt(s)"));
        };
    }

    /**
     * {@code //feature} — places a configured worldgen feature, i.e. anything
     * the server's {@code PlacedFeature} registry knows: trees, ores, geodes,
     * lakes, ...
     */
    private void feature() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("feature");
        if (entry == null) {
            return;
        }
        entry.description = "Generate a feature at your position";
        entry.group = "generation";
        entry.arguments.add("feature");
        entry.arguments.add("[position]");
        entry.handler = ctx -> {
            String name = ctx.arg(0);
            BlockVector3 position = ctx.args().size() > 1
                    ? ctx.blockVector(1) : ctx.world().getTargetBlock(ctx.actor(), 100);
            if (!ctx.world().generateFeature(position, name, new Random())) {
                throw CommandRegistry.error("Unknown feature '" + name
                        + "'. Use a namespaced feature id such as minecraft:trees_oak or minecraft:ore_gold");
            }
            ctx.actor().message(Msg.success("Placed feature " + name + " at " + position));
        };
    }

    /** {@code //structure} — generates a worldgen structure over the selection. */
    private void structure() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("structure", "/struct");
        if (entry == null) {
            return;
        }
        entry.description = "Generate a structure over the selection";
        entry.group = "generation";
        entry.requiresSelection = true;
        entry.arguments.add("structure");
        entry.handler = ctx -> {
            String name = ctx.arg(0);
            Region region = ctx.selection();
            BlockVector3 min = region.getMinimumPoint();
            if (!ctx.world().generateStructure(name, min, new Random())) {
                throw CommandRegistry.error("Unknown structure '" + name
                        + "'. Try a worldgen structure id such as minecraft:village_plains");
            }
            ctx.actor().message(Msg.success("Generated structure " + name + " at " + min));
        };
    }

    /**
     * {@code //generate} — fills the selection with a pattern, optionally only
     * where a formula is true. Without a formula the pattern replaces the whole
     * selection, which is what {@code //g <pattern>} does in FAWE.
     */
    /**
     * {@code //generate} — builds the part of the selection a formula picks out.
     *
     * <p>This is WorldEdit's shape generator: the formula is evaluated once per
     * block and the block belongs to the shape when the value is above zero, which
     * is how {@code //generate stone y%10<5} carves a pattern out of a selection.
     * What the formula's {@code x}/{@code y}/{@code z} mean follows the switches:
     * {@code -r} is the world origin at scale one, {@code -o} the position of the
     * player running it, {@code -c} the centre of the selection at scale one, and
     * the plain form measures from the centre in units of the selection's half
     * size, so the selection is the box from -1 to 1. {@code -h} writes only the
     * shell: the blocks of the shape that have a neighbour outside it.</p>
     *
     * <p>WorldEdit's {@code type} and {@code data} variables carry the numeric ids
     * the game used before block states existed, and a formula that assigns them
     * picks the block of a cell. The ids are states here, so {@code type} is the
     * state a cell holds and {@code data} is left unset.</p>
     */
    private void generate() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("//generate", "//gen", "//g");
        if (entry == null) {
            return;
        }
        entry.description = "Generates a shape according to a formula";
        entry.group = "generation";
        entry.requiresSelection = true;
        // -h writes the shell, -r/-o/-c choose what the formula's coordinates
        // are measured from.
        entry.booleanFlags.add("h");
        entry.booleanFlags.add("r");
        entry.booleanFlags.add("o");
        entry.booleanFlags.add("c");
        entry.arguments.add("<pattern>");
        entry.arguments.add("<formula>");
        entry.handler = ctx -> {
            Region region = ctx.selection();
            Pattern pattern = ctx.pattern(0);
            if (ctx.args().size() < 2) {
                throw CommandRegistry.error("Usage: " + entry.usage());
            }
            String formula = ctx.joined(1);
            Expression expression = Expression.compile(formula);
            boolean assignsState = usesTypeVariable(formula);
            double[] origin = origin(ctx, region);
            double[] scale = scale(ctx, region, origin);
            boolean hollow = ctx.hasFlag("h");
            EditSession session = ctx.editSession("//generate");
            BlockVector3 min = region.getMinimumPoint();
            BlockVector3 max = region.getMaximumPoint();
            int width = max.x() - min.x() + 1;
            int height = max.y() - min.y() + 1;
            int length = max.z() - min.z() + 1;
            long volume = (long) width * height * length;
            if (volume > Integer.MAX_VALUE) {
                throw CommandRegistry.error("A selection of " + volume + " blocks is too large to generate into");
            }
            // A bit per block, plus one block of border on each side when the
            // formula has to be asked about the neighbours of the shape.
            int pad = hollow ? 1 : 0;
            int padWidth = width + 2 * pad;
            int padLength = length + 2 * pad;
            long[] inside = hollow ? new long[(padWidth * (height + 2) * padLength + 63) >>> 6] : null;
            Expression.Variables variables = new Expression.Variables();
            World world = ctx.world();
            variables.set("miny", world.minY());
            variables.set("maxy", world.maxY());
            if (inside != null) {
                for (int y = min.y() - pad; y <= max.y() + pad; y++) {
                    for (int z = min.z() - pad; z <= max.z() + pad; z++) {
                        for (int x = min.x() - pad; x <= max.x() + pad; x++) {
                            if (inShape(expression, variables, world, x, y, z, origin, scale, assignsState)) {
                                int index = (x - min.x() + pad)
                                        + (z - min.z() + pad) * padWidth
                                        + (y - min.y() + pad) * padWidth * padLength;
                                inside[index >>> 6] |= 1L << (index & 63);
                            }
                        }
                    }
                }
            }
            int changed = 0;
            for (int y = min.y(); y <= max.y(); y++) {
                for (int z = min.z(); z <= max.z(); z++) {
                    for (int x = min.x(); x <= max.x(); x++) {
                        session.checkTimeout();
                        int index = (x - min.x() + pad) + (z - min.z() + pad) * padWidth
                                + (y - min.y() + pad) * padWidth * padLength;
                        if (inside != null) {
                            if ((inside[index >>> 6] & (1L << (index & 63))) == 0) {
                                continue;
                            }
                            if (!touchesOutside(inside, padWidth, padLength, index)) {
                                continue;
                            }
                        } else if (!inShape(expression, variables, world, x, y, z, origin, scale, assignsState)) {
                            continue;
                        }
                        int state = expressionState(variables);
                        if (state < 0) {
                            state = pattern.apply(new BlockVector3(x, y, z));
                        }
                        if (session.setBlock(x, y, z, state)) {
                            changed++;
                        }
                    }
                }
            }
            session.flushQueue();
            ctx.actor().message(Msg.success("Generated " + changed + " block(s)"));
        };
    }

    /**
     * True when the formula is above zero at a position, i.e. the position is part
     * of the shape. The coordinates are the position relative to the origin, in
     * units of the selection's half size.
     */
    private static boolean inShape(Expression expression, Expression.Variables variables, World world,
            int x, int y, int z, double[] origin, double[] scale, boolean assignsState) {
        variables.set("x", (x - origin[0]) / scale[0]);
        variables.set("y", (y - origin[1]) / scale[1]);
        variables.set("z", (z - origin[2]) / scale[2]);
        if (assignsState) {
            // The state the cell holds, which is what WorldEdit's numeric block id
            // used to be, and the value a formula assigning "type" reads back.
            variables.set("type", world.getBlock(x, y, z));
            variables.set("data", 0);
        }
        return expression.evaluate(variables) > 0;
    }

    /**
     * The state a formula that assigns {@code type} asked for, or -1 when the cell
     * keeps the pattern. Read after {@link #inShape}, which binds the variable.
     */
    private static int expressionState(Expression.Variables variables) {
        return variables.has("type") ? (int) variables.get("type") : -1;
    }

    /** True when the formula mentions WorldEdit's numeric block id variables. */
    private static boolean usesTypeVariable(String formula) {
        return java.util.regex.Pattern.compile("\\b(?:type|data)\\b").matcher(formula).find();
    }

    /** True when one of the six neighbours of a shape cell is outside the shape. */
    private static boolean touchesOutside(long[] inside, int padWidth, int padLength, int index) {
        int stepY = padWidth * padLength;
        return bitCleared(inside, index + 1)
                || bitCleared(inside, index - 1)
                || bitCleared(inside, index + padWidth)
                || bitCleared(inside, index - padWidth)
                || bitCleared(inside, index + stepY)
                || bitCleared(inside, index - stepY);
    }

    private static boolean bitCleared(long[] bits, int index) {
        return (bits[index >>> 6] & (1L << (index & 63))) == 0;
    }

    /** True when a neighbour of the block is air, i.e. the block is exposed. */
    private static boolean isSurface(World world, int x, int y, int z) {
        BlockStateRegistry states = BlockState.registry();
        return states.isAirLike(world.getBlock(x, y + 1, z))
                || states.isAirLike(world.getBlock(x, y - 1, z))
                || states.isAirLike(world.getBlock(x + 1, y, z))
                || states.isAirLike(world.getBlock(x - 1, y, z))
                || states.isAirLike(world.getBlock(x, y, z + 1))
                || states.isAirLike(world.getBlock(x, y, z - 1));
    }
}
