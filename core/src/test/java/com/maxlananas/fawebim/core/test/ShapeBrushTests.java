package com.maxlananas.fawebim.core.test;

import com.maxlananas.fawebim.core.brush.Brush;
import com.maxlananas.fawebim.core.brush.BrushFactory;
import com.maxlananas.fawebim.core.brush.Brushes;
import com.maxlananas.fawebim.core.command.CommandManager;
import com.maxlananas.fawebim.core.extent.EditSession;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.platform.Config;
import com.maxlananas.fawebim.core.world.BlockState;
import com.maxlananas.fawebim.core.world.BlockStateRegistry;

import java.io.IOException;
import java.nio.file.Files;

import static com.maxlananas.fawebim.core.test.SelfTestMain.check;
import static com.maxlananas.fawebim.core.test.SelfTestMain.checkEquals;
import static com.maxlananas.fawebim.core.test.SelfTestMain.section;

/**
 * The brushes FAWE builds on a shape - forest, feature, structure, snow,
 * biome, raise and lower - work on that shape, as FAWE's do.
 *
 * <p>The forest brush asked the world for a feature named after its tree and
 * planted nothing; the feature brush placed one feature at the click, whatever
 * its shape and density, and said so on every click; the structure brush
 * asked for a feature. The snow brush covered a disc whatever the shape, put
 * a layer on flowers and flowing water, and stacked eight layers in a click.
 * The biome brush set the biome with the id 0, never the one it was bound
 * with. Raise and lower added and took blocks off the top of a disc, where
 * WorldEdit deforms the shape. A brush bound without a required argument was
 * bound anyway, a sphere of air for {@code /brush sphere}, and a brush preset
 * was saved without the name of its brush, so it loaded nothing.</p>
 */
final class ShapeBrushTests {

    private ShapeBrushTests() {
    }

    static void run() {
        section("brushes with a shape");
        BlockStateRegistry previous = BlockState.registry();
        BlockState.setRegistry(new PropertyTestRegistry());
        try {
            theForestBrushPlantsOnItsShape();
            theHeightBrushRaisesTheGroundItself();
            featuresAndStructuresPaintTheGround();
            raiseAndLowerDeformTheShape();
            theSnowBrushSnowsAsSnowDoes();
        } finally {
            BlockState.setRegistry(previous);
        }
        theBiomeBrushSetsItsBiomeInItsShape();
        aRequiredArgumentIsRequired();
        aPresetBindsItsBrushAgain();
        theCircleIsFawesDiscFacingThePlayer();
    }

    /**
     * FAWE's circle is the blocks of the ball, radius plus half a block, within
     * half a block of the plane square to the line from the player's feet to
     * the click: facing a click ten blocks north, a filled circle of radius 3
     * is the 37 blocks with x² + y² at most 12.25, and it is symmetric about
     * its centre. The circle was walked by angles and floored, which made it
     * lopsided.
     */
    private static void theCircleIsFawesDiscFacingThePlayer() {
        TestWorld world = new TestWorld("CircleBrush");
        TestActor actor = new TestActor("CircleBrush", world, new BlockVector3(0, 80, -10));
        actor.session().setMaxBlocksChanged(1_000_000);
        answer(actor, "/brush circle gold_block 3 true");
        int disc = stroke(actor, 0, 80, 0);
        checkEquals("a filled circle of radius 3 has FAWE's 37 blocks", 37, disc);
        int gold = state("minecraft:gold_block");
        boolean flat = true;
        boolean symmetric = true;
        for (int x = -5; x <= 5; x++) {
            for (int y = 75; y <= 85; y++) {
                for (int z = -5; z <= 5; z++) {
                    boolean here = world.getBlock(x, y, z) == gold;
                    flat &= !here || z == 0;
                    symmetric &= here == (world.getBlock(-x, 160 - y, z) == gold);
                }
            }
        }
        check("in the plane square to the line from the player", flat);
        check("and symmetric about its centre", symmetric);

        TestActor aside = new TestActor("CircleRing", world, new BlockVector3(20, 80, -10));
        answer(aside, "/brush circle diamond_block 3");
        int ring = stroke(aside, 20, 80, 0);
        int diamond = state("minecraft:diamond_block");
        check("a circle that is not filled is a ring (" + ring + " blocks)", ring > 0 && ring < disc
                && world.getBlock(20, 80, 0) != diamond && world.getBlock(23, 80, 0) == diamond);

        TestActor above = new TestActor("CircleAbove", world, new BlockVector3(40, 90, 0));
        answer(above, "/brush circle gold_block 2 true");
        stroke(above, 40, 80, 0);
        boolean level = true;
        for (int x = 36; x <= 44; x++) {
            for (int y = 76; y <= 84; y++) {
                for (int z = -4; z <= 4; z++) {
                    level &= world.getBlock(x, y, z) != gold || y == 80;
                }
            }
        }
        check("seen from above the circle lies flat", level && world.getBlock(42, 80, 0) == gold);
    }

    /** Grass at y 60 over eight layers of stone, thirteen by thirteen around the origin. */
    private static TestActor ground(String name) {
        TestWorld world = new TestWorld(name);
        for (int x = -6; x <= 6; x++) {
            for (int z = -6; z <= 6; z++) {
                for (int y = 52; y < 60; y++) {
                    world.setBlock(x, y, z, state("minecraft:stone"));
                }
                world.setBlock(x, 60, z, state("minecraft:grass_block"));
            }
        }
        TestActor actor = new TestActor(name, world, new BlockVector3(0, 61, 0));
        actor.session().setMaxBlocksChanged(1_000_000);
        return actor;
    }

    private static void theForestBrushPlantsOnItsShape() {
        TestActor actor = ground("ForestBrush");
        TestWorld world = (TestWorld) actor.world();
        int log = state("minecraft:oak_log");
        answer(actor, "/brush forest cyl 2 100 oak");
        int planted = stroke(actor, 0, 60, 0);
        // The cylinder of radius 2 is WorldEdit's: 21 columns, the square of
        // five without its corners.
        checkEquals("the forest brush plants a tree on every column of its shape at a density of 100", 21,
                planted);
        check("on the ground of the column", world.getBlock(2, 61, 0) == log && world.getBlock(1, 61, 1) == log);
        check("and on none outside the shape", world.getBlock(2, 61, 2) != log);
        answer(actor, "//undo");
        checkEquals("its trees are in the history", 0, count(world, "minecraft:oak_log"));

        answer(actor, "/brush forest cyl 2 0 oak");
        checkEquals("a density of 0 plants nothing", 0, stroke(actor, 0, 60, 0));
        answer(actor, "/brush forest cyl 2 100 oak");
        answer(actor, "/mask minecraft:stone");
        stroke(actor, 0, 60, 0);
        checkEquals("the brush's mask keeps its trees out of the air", 0, count(world, "minecraft:oak_log"));
        check("an unknown tree is refused when the brush is bound",
                answer(actor, "/brush forest sphere nope").contains("Unknown tree type 'nope'"));
    }

    /**
     * The height and cliff brushes raise the ground as WorldEdit's heightmap
     * does, the top block going up and the column following it; they take no
     * pattern, and raising placed air, so they only ever lowered.
     */
    private static void theHeightBrushRaisesTheGroundItself() {
        TestActor actor = ground("HeightBrush");
        TestWorld world = (TestWorld) actor.world();
        answer(actor, "/brush height 3 1");
        check("the height brush raises flat ground", stroke(actor, 0, 60, 0) > 0);
        int top = world.getHighestBlockY(0, 0);
        check("the middle goes up (" + top + ")", top > 60);
        checkEquals("with the grass on top", state("minecraft:grass_block"), world.getBlock(0, top, 0));
        checkEquals("and the ground under it, not air", state("minecraft:stone"), world.getBlock(0, top - 1, 0));
        answer(actor, "//undo");
        checkEquals("the raise is undone", 60, world.getHighestBlockY(0, 0));
        answer(actor, "/brush cliff 2");
        stroke(actor, 0, 60, 0);
        check("the cliff brush raises a plateau", world.getHighestBlockY(0, 0) > 60
                && world.getHighestBlockY(1, 0) == world.getHighestBlockY(0, 0));
    }

    private static void featuresAndStructuresPaintTheGround() {
        TestActor actor = ground("FeatureBrush");
        TestWorld world = (TestWorld) actor.world();
        int poppy = state("minecraft:poppy");
        answer(actor, "/brush feature cyl 2 100 minecraft:poppy");
        actor.clearMessages();
        checkEquals("the feature brush places its feature on every column of its shape at 100", 21,
                stroke(actor, 0, 60, 0));
        check("on the ground", world.getBlock(-2, 61, 0) == poppy && world.getBlock(1, 61, -1) == poppy);
        check("and not outside the shape", world.getBlock(2, 61, 2) != poppy);
        check("without a word in chat (" + actor.messages() + ")", actor.messages().isEmpty());
        answer(actor, "/brush feature cyl 2 0 minecraft:poppy");
        checkEquals("its density is a percentage of the columns", 0, stroke(actor, 4, 60, 4));

        int log = state("minecraft:oak_log");
        answer(actor, "/brush structure cuboid 1 100 minecraft:oak_log");
        checkEquals("the structure brush places a structure on every column of its shape", 9,
                stroke(actor, 4, 60, 4));
        check("a structure, not a feature", world.getBlock(5, 61, 5) == log && world.getBlock(5, 62, 5) == log);
    }

    private static void raiseAndLowerDeformTheShape() {
        TestActor actor = ground("RaiseBrush");
        TestWorld world = (TestWorld) actor.world();
        int grass = state("minecraft:grass_block");
        int stone = state("minecraft:stone");
        int air = BlockState.registry().air();
        answer(actor, "/brush raise sphere 2");
        stroke(actor, 0, 60, 0);
        checkEquals("the raise brush lifts the ground in its shape by a block", grass, world.getBlock(0, 61, 0));
        checkEquals("with what was under it", stone, world.getBlock(0, 60, 0));
        checkEquals("and leaves the ground outside it", air, world.getBlock(3, 61, 0));

        TestActor lowered = ground("LowerBrush");
        TestWorld low = (TestWorld) lowered.world();
        answer(lowered, "/brush lower sphere 2");
        stroke(lowered, 0, 60, 0);
        checkEquals("the lower brush sinks it by a block", air, low.getBlock(0, 60, 0));
        checkEquals("the grass going down with it", grass, low.getBlock(0, 59, 0));
        checkEquals("and leaves the ground outside it", grass, low.getBlock(3, 60, 0));
    }

    private static void theSnowBrushSnowsAsSnowDoes() {
        TestActor actor = ground("SnowBrush");
        TestWorld world = (TestWorld) actor.world();
        int layer = state("minecraft:snow");
        int air = BlockState.registry().air();
        world.setBlock(1, 61, 0, state("minecraft:poppy"));
        answer(actor, "/brush snow cyl 3");
        stroke(actor, 0, 60, 0);
        checkEquals("the snow brush lays a layer on the ground", layer, world.getBlock(2, 61, 0));
        checkEquals("which turns snowy", BlockState.registry().parse("minecraft:grass_block[snowy=true]"),
                world.getBlock(2, 60, 0));
        checkEquals("a flower is passed, not covered", air, world.getBlock(1, 62, 0));
        checkEquals("its cylinder is WorldEdit's, half a block wider than the radius", layer,
                world.getBlock(3, 61, 1));
        checkEquals("and no wider", air, world.getBlock(3, 61, 3));

        answer(actor, "/brush snow cyl 3 -s");
        stroke(actor, 0, 60, 0);
        checkEquals("-s adds one layer a click", BlockState.registry().parse("minecraft:snow[layers=2]"),
                world.getBlock(2, 61, 0));

        TestActor square = ground("SnowSphere");
        answer(square, "/brush snow sphere 2");
        stroke(square, 0, 60, 0);
        checkEquals("a sphere snows the square around it, as FAWE lays a layer", layer,
                square.world().getBlock(2, 61, 2));
    }

    /**
     * A biome is kept by cells of four blocks, and the test world keeps one a
     * column: the corner cell of a stroke at (2, 70, 2), x and z from 4 to 7,
     * holds a single block of a cuboid of radius 2 and none of its sphere.
     */
    private static void theBiomeBrushSetsItsBiomeInItsShape() {
        TestWorld world = new TestWorld("BiomeBrush");
        TestActor actor = new TestActor("BiomeBrush", world, new BlockVector3(0, 71, 0));
        int desert = BlockState.registry().biome("minecraft:desert");
        int plains = BlockState.registry().biome("minecraft:plains");
        int unchanged = world.getBiome(5, 70, 5);
        answer(actor, "/brush biome sphere 2 minecraft:desert");
        stroke(actor, 2, 70, 2);
        checkEquals("the biome brush sets the biome it was bound with", desert, world.getBiome(2, 70, 2));
        checkEquals("in its sphere", desert, world.getBiome(5, 70, 1));
        checkEquals("and not outside it", unchanged, world.getBiome(5, 70, 5));
        answer(actor, "/brush biome cuboid 2 minecraft:plains");
        stroke(actor, 2, 70, 2);
        checkEquals("a cuboid has its corners", plains, world.getBiome(5, 70, 5));

        // A sphere of radius 1 at (20, 70, 20), and its column at (40, 70, 40),
        // cross the corner of four columns of cells; the sphere, one cell high.
        answer(actor, "/brush biome sphere 1 minecraft:desert");
        checkEquals("a sphere changes the cells it crosses", 4, stroke(actor, 20, 70, 20));
        answer(actor, "/brush biome sphere 1 minecraft:desert -c");
        checkEquals("-c turns it into a cylinder through the height of the world",
                4 * (world.maxY() - world.minY() + 1) / 4, stroke(actor, 40, 70, 40));
        check("an unknown biome is refused when the brush is bound",
                answer(actor, "/brush biome sphere nope").contains("Unknown biome 'nope'"));
    }

    private static void aRequiredArgumentIsRequired() {
        TestWorld world = new TestWorld("BrushRequired");
        TestActor actor = new TestActor("BrushRequired", world, new BlockVector3(0, 71, 0));
        String sphere = answer(actor, "/brush sphere");
        check("/brush sphere without its pattern is refused (" + sphere + ")",
                sphere.contains("Missing argument 1 for /brush sphere <pattern>"));
        check("and binds nothing", BrushFactory.current(actor) == null);
        check("/brush biome without its biome is refused",
                answer(actor, "/brush biome sphere").contains("Missing argument 3 for /brush biome"));
        check("/brush snow without its shape is refused",
                answer(actor, "/brush snow").contains("Missing argument 1 for /brush snow <shape>"));
    }

    private static void aPresetBindsItsBrushAgain() {
        TestWorld world = new TestWorld("BrushPreset");
        TestActor actor = new TestActor("BrushPreset", world, new BlockVector3(0, 71, 0));
        String name = "shapebrushtests_preset";
        try {
            answer(actor, "/brush snow cyl 4 -s");
            answer(actor, "/brush savebrush " + name);
            answer(actor, "/brush sphere minecraft:stone 2");
            String loaded = answer(actor, "/brush loadbrush " + name);
            check("a preset binds its brush again (" + loaded + ")", loaded.contains("Brush 'snow' equipped (radius 4)"));
            Brush brush = BrushFactory.current(actor);
            check("with its shape and its switches (" + (brush == null ? null : brush.describe()) + ")",
                    brush != null && brush.describe().contains("shape=cyl -s"));
        } finally {
            try {
                Files.deleteIfExists(Config.get().resolveDirectory(Config.get().brushPresetDirectory)
                        .resolve(name + ".txt"));
            } catch (IOException ignored) {
                // Left behind in an ignored folder, which the next run overwrites.
            }
        }
    }

    private static int stroke(TestActor actor, int x, int y, int z) {
        Brush brush = BrushFactory.current(actor);
        EditSession edit = new EditSession(actor.world(), actor.session(), "brush");
        try {
            return Brushes.apply(brush, edit, new BlockVector3(x, y, z), actor);
        } finally {
            edit.close();
        }
    }

    private static int count(TestWorld world, String block) {
        int found = 0;
        int wanted = state(block);
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                for (int y = 58; y <= 75; y++) {
                    if (world.getBlock(x, y, z) == wanted) {
                        found++;
                    }
                }
            }
        }
        return found;
    }

    private static int state(String name) {
        return BlockState.registry().defaultState(name);
    }

    private static String answer(TestActor actor, String line) {
        actor.clearMessages();
        CommandManager.get().dispatch(actor, line);
        return String.join("\n", actor.messages()).replaceAll("\u00a7.", "");
    }
}
