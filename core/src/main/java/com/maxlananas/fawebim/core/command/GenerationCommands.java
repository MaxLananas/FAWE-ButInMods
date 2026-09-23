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
        entry.description = "Sets the biome according to a formula";
        entry.group = "generation";
        entry.requiresSelection = true;
        entry.arguments.add("formula");
        entry.handler = ctx -> {
            Region region = ctx.selection();
            Expression expression = Expression.compile(ctx.joined(0));
            BlockStateRegistry states = BlockState.registry();
            World world = ctx.world();
            EditSession session = ctx.editSession("generatebiome");
            Expression.Variables variables = new Expression.Variables();
            variables.set("miny", world.minY());
            variables.set("maxy", world.maxY());
            int changed = 0;
            for (int x = region.getMinimumPoint().x(); x <= region.getMaximumPoint().x(); x++) {
                for (int z = region.getMinimumPoint().z(); z <= region.getMaximumPoint().z(); z++) {
                    session.checkTimeout();
                    variables.set("x", x);
                    variables.set("z", z);
                    int biomeId = (int) Math.floor(expression.evaluate(variables));
                    if (states.biomeName(biomeId) == null) {
                        continue;
                    }
                    for (int y = world.minY(); y < world.maxY(); y += 4) {
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

    /**
     * {@code //forestgen} — plants trees over the selection. The planting spots
     * are picked at random and the height comes from the world's heightmap, so
     * the result follows the terrain instead of a flat plane.
     */
    private void forestGen() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("forestgen");
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
    private void generate() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("generate", "/gen", "/g");
        if (entry == null) {
            return;
        }
        entry.description = "Replace the selection with a pattern, optionally using a formula";
        entry.group = "generation";
        entry.requiresSelection = true;
        entry.arguments.add("pattern");
        entry.arguments.add("[formula]");
        entry.booleanFlags.add("h");
        entry.handler = ctx -> {
            Region region = ctx.selection();
            Pattern pattern = ctx.pattern(0);
            Expression expression = ctx.args().size() > 1 ? Expression.compile(ctx.joined(1)) : null;
            boolean hollow = ctx.hasFlag("h");
            EditSession session = ctx.editSession("generate");
            World world = ctx.world();
            Expression.Variables variables = new Expression.Variables();
            variables.set("miny", world.minY());
            variables.set("maxy", world.maxY());
            BlockVector3 min = region.getMinimumPoint();
            BlockVector3 max = region.getMaximumPoint();
            int changed = 0;
            for (int x = min.x(); x <= max.x(); x++) {
                for (int y = min.y(); y <= max.y(); y++) {
                    for (int z = min.z(); z <= max.z(); z++) {
                        session.checkTimeout();
                        if (hollow && !isSurface(world, x, y, z)) {
                            continue;
                        }
                        if (expression != null) {
                            variables.set("x", x);
                            variables.set("y", y);
                            variables.set("z", z);
                            if (expression.evaluate(variables) == 0) {
                                continue;
                            }
                        }
                        if (session.setBlock(x, y, z, pattern.apply(new BlockVector3(x, y, z)))) {
                            changed++;
                        }
                    }
                }
            }
            session.flushQueue();
            ctx.actor().message(Msg.success(changed + " block(s) generated"));
        };
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
