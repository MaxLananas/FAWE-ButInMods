package com.maxlananas.fawebim.core.test;

import com.maxlananas.fawebim.core.command.CommandManager;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.world.BlockState;
import com.maxlananas.fawebim.core.world.BlockStateRegistry;

import static com.maxlananas.fawebim.core.test.SelfTestMain.check;
import static com.maxlananas.fawebim.core.test.SelfTestMain.checkEquals;
import static com.maxlananas.fawebim.core.test.SelfTestMain.section;

/**
 * {@code //fill}, {@code //fillr}, {@code //drain} and {@code //fixwater} walk
 * the world the way FAWE's visitors do.
 *
 * <p>The fills wrote every air block of their sphere, joined to the player or
 * not. The drain took every block holding water for a liquid, waterlogged
 * stairs and slabs included, and only started from the block at the player's
 * feet. The liquid fixes needed a selection and only stilled the flowing water
 * inside it.</p>
 */
final class FillAndLiquidTests {

    private FillAndLiquidTests() {
    }

    static void run() {
        section("fills and liquids");
        fillFillsTheHoleAndNotTheCavesBesideIt();
        fillrFollowsTheHoleBelowItsStart();
        aDirectedFillNeverStepsBack();
        BlockStateRegistry previous = BlockState.registry();
        BlockState.setRegistry(new PropertyTestRegistry());
        try {
            drainEmptiesThePoolAndLeavesTheWaterloggedBlocks();
            drainTakesThePlantsAndTheWaterWhenAsked();
            fixWaterStillsTheLakeAndFillsItsHoles();
        } finally {
            BlockState.setRegistry(previous);
        }
    }

    /**
     * A block of stone with a pit dug into it, a cave beside the pit at the
     * player's level and a tunnel leaving the bottom of the pit, all inside
     * the radius.
     */
    private static TestWorld pit(String name) {
        TestWorld world = new TestWorld(name);
        int stone = state("minecraft:stone");
        int air = BlockState.registry().air();
        box(world, -6, 50, -6, 6, 60, 6, stone);
        box(world, -1, 56, -1, 1, 60, 1, air);
        box(world, 4, 57, 0, 4, 58, 0, air);
        box(world, 2, 56, 0, 3, 56, 0, air);
        return world;
    }

    /**
     * FAWE's downward fill spreads across the level it starts on and then only
     * goes down: the pit fills to the depth, the cave beside it and the tunnel
     * leaving its bottom stay empty.
     */
    private static void fillFillsTheHoleAndNotTheCavesBesideIt() {
        TestWorld world = pit("FillHole");
        TestActor actor = new TestActor("FillHole", world, new BlockVector3(0, 58, 0));
        int dirt = state("minecraft:dirt");
        int air = BlockState.registry().air();
        answer(actor, "//fill dirt 5 3");
        check("//fill fills the pit across its level", world.getBlock(-1, 58, 1) == dirt
                && world.getBlock(1, 58, -1) == dirt);
        check("//fill fills the pit down to its depth", world.getBlock(0, 56, 0) == dirt
                && world.getBlock(1, 57, 1) == dirt);
        checkEquals("//fill leaves what is above its start", air, world.getBlock(0, 59, 0));
        checkEquals("//fill leaves the cave the pit does not reach", air, world.getBlock(4, 58, 0));
        checkEquals("//fill does not follow a tunnel below its level", air, world.getBlock(2, 56, 0));
    }

    /** The recursive fill follows the tunnel, still not the cave and still not above its start. */
    private static void fillrFollowsTheHoleBelowItsStart() {
        TestWorld world = pit("FillRecursive");
        TestActor actor = new TestActor("FillRecursive", world, new BlockVector3(0, 58, 0));
        int dirt = state("minecraft:dirt");
        int air = BlockState.registry().air();
        answer(actor, "//fillr dirt 5");
        check("//fillr fills the pit and the tunnel leaving it", world.getBlock(0, 56, 0) == dirt
                && world.getBlock(3, 56, 0) == dirt);
        checkEquals("//fillr leaves the cave the pit does not reach", air, world.getBlock(4, 57, 0));
        checkEquals("//fillr leaves what is above its start", air, world.getBlock(0, 59, 0));
    }

    /**
     * A fill given a direction never takes a step against it: in a tunnel,
     * {@code north} fills the part of the tunnel in front and nothing behind.
     */
    private static void aDirectedFillNeverStepsBack() {
        TestWorld world = new TestWorld("FillNorth");
        int stone = state("minecraft:stone");
        int air = BlockState.registry().air();
        int dirt = state("minecraft:dirt");
        box(world, -3, 60, -8, 3, 64, 8, stone);
        box(world, 0, 61, -8, 0, 62, 8, air);
        TestActor actor = new TestActor("FillNorth", world, new BlockVector3(0, 61, 0));
        answer(actor, "//fill dirt 4 1 north");
        check("a fill to the north fills the tunnel in front", world.getBlock(0, 61, -4) == dirt
                && world.getBlock(0, 62, -3) == dirt && world.getBlock(0, 61, 0) == dirt);
        checkEquals("and stays inside its radius", air, world.getBlock(0, 61, -5));
        checkEquals("and never steps back to the south", air, world.getBlock(0, 61, 1));
    }

    /**
     * A pool of water in stone, with flowing water on its surface, a bubble
     * column, kelp and a waterlogged slab at the bottom, and air above it.
     */
    private static TestWorld pool(String name) {
        BlockStateRegistry registry = BlockState.registry();
        TestWorld world = new TestWorld(name);
        box(world, -3, 60, -3, 3, 64, 3, state("minecraft:stone"));
        box(world, -2, 61, -2, 2, 63, 2, state("minecraft:water"));
        box(world, -2, 64, -2, 2, 64, 2, registry.air());
        world.setBlock(1, 63, 1, registry.parse("minecraft:water[level=3]"));
        world.setBlock(-2, 61, -2, registry.parse("minecraft:bubble_column[drag=true]"));
        world.setBlock(0, 61, 2, state("minecraft:kelp_plant"));
        world.setBlock(0, 62, 2, state("minecraft:kelp"));
        world.setBlock(2, 61, 2, registry.parse("minecraft:oak_slab[type=bottom,waterlogged=true]"));
        return world;
    }

    /**
     * Standing on the rim, feet in the air above the water: the pool is found
     * in the cells around the player and emptied, and a waterlogged block is a
     * block - the game counts it as holding water, FAWE does not drain it.
     */
    private static void drainEmptiesThePoolAndLeavesTheWaterloggedBlocks() {
        BlockStateRegistry registry = BlockState.registry();
        TestWorld world = pool("Drain");
        int slab = registry.parse("minecraft:oak_slab[type=bottom,waterlogged=true]");
        check("the test registry holds a waterlogged slab for a liquid, as the game does", registry.isLiquid(slab));
        TestActor actor = new TestActor("Drain", world, new BlockVector3(0, 64, 0));
        answer(actor, "//drain 5");
        int air = registry.air();
        check("//drain empties the pool from the cells around the player", world.getBlock(0, 63, 0) == air
                && world.getBlock(-2, 61, 1) == air && world.getBlock(2, 62, -2) == air);
        checkEquals("//drain takes the flowing water", air, world.getBlock(1, 63, 1));
        checkEquals("//drain takes the bubble column", air, world.getBlock(-2, 61, -2));
        checkEquals("//drain leaves the waterlogged slab as it was", slab, world.getBlock(2, 61, 2));
        checkEquals("//drain leaves the kelp without -p", state("minecraft:kelp_plant"), world.getBlock(0, 61, 2));

        TestWorld swim = pool("DrainSwimming");
        TestActor swimmer = new TestActor("DrainSwimming", swim, new BlockVector3(0, 62, 0));
        answer(swimmer, "//drain 5");
        checkEquals("//drain run from inside the pool empties it too", air, swim.getBlock(1, 61, 1));
        checkEquals("and still leaves the waterlogged slab", slab, swim.getBlock(2, 61, 2));
    }

    /** {@code -p} takes the plants, {@code -w} the water out of the waterlogged blocks - and only the water. */
    private static void drainTakesThePlantsAndTheWaterWhenAsked() {
        BlockStateRegistry registry = BlockState.registry();
        TestWorld world = pool("DrainAll");
        TestActor actor = new TestActor("DrainAll", world, new BlockVector3(0, 64, 0));
        answer(actor, "//drain -w -p 5");
        int air = registry.air();
        check("-p takes the kelp", world.getBlock(0, 61, 2) == air && world.getBlock(0, 62, 2) == air);
        checkEquals("-w leaves the slab without its water", "minecraft:oak_slab[type=bottom,waterlogged=false]",
                registry.describe(world.getBlock(2, 61, 2)));
        checkEquals("the stone around stays", state("minecraft:stone"), world.getBlock(3, 62, 0));
    }

    /**
     * {@code //fixwater} needs no selection: from the water around the player
     * it turns the flowing water still and fills the holes of the surface, and
     * nothing above the player.
     */
    private static void fixWaterStillsTheLakeAndFillsItsHoles() {
        BlockStateRegistry registry = BlockState.registry();
        TestWorld world = new TestWorld("FixWater");
        int water = state("minecraft:water");
        int air = registry.air();
        box(world, -4, 60, -4, 4, 63, 4, state("minecraft:stone"));
        box(world, -3, 61, -3, 3, 62, 3, water);
        box(world, -3, 63, -3, 3, 63, 3, air);
        world.setBlock(3, 62, 3, registry.parse("minecraft:water[level=5]"));
        world.setBlock(-3, 62, 0, air);
        TestActor actor = new TestActor("FixWater", world, new BlockVector3(0, 62, 0));
        String answer = answer(actor, "//fixwater 6");
        check("//fixwater runs without a selection", !answer.contains("No region selected"));
        checkEquals("//fixwater stills the flowing water", water, world.getBlock(3, 62, 3));
        checkEquals("//fixwater fills a hole in the surface", water, world.getBlock(-3, 62, 0));
        checkEquals("//fixwater fills nothing above the player", air, world.getBlock(0, 63, 0));
    }

    private static void box(TestWorld world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, int state) {
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    world.setBlock(x, y, z, state);
                }
            }
        }
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
