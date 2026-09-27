package com.maxlananas.fawebim.core.test;

import com.maxlananas.fawebim.core.command.CommandManager;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.world.BlockState;
import com.maxlananas.fawebim.core.world.BlockStateRegistry;

import static com.maxlananas.fawebim.core.test.SelfTestMain.check;
import static com.maxlananas.fawebim.core.test.SelfTestMain.checkEquals;
import static com.maxlananas.fawebim.core.test.SelfTestMain.section;

/**
 * {@code //snow}, {@code //thaw} and {@code //green} treat the terrain as FAWE
 * does.
 *
 * <p>The snow took the first block of a column that was not air for its
 * ground: a flower got a layer on top of it, and so did flowing water, a
 * bottom slab and ice, and a second run put a layer on the first. The ground
 * under the snow never turned snowy, and the thaw took only one layer deep
 * snow and left the grass under it snowy. The green went through flowing
 * water to the dirt under it.</p>
 */
final class SnowAndGreenTests {

    private SnowAndGreenTests() {
    }

    static void run() {
        section("snow, thaw and green");
        BlockStateRegistry previous = BlockState.registry();
        BlockState.setRegistry(new PropertyTestRegistry());
        try {
            snowLiesWhereTheGameLetsItLie();
            snowStacksAndKeepsOffCoveredColumns();
            thawTakesDeepSnowAndTheSnowyGround();
            greenStopsAtAnyWater();
        } finally {
            BlockState.setRegistry(previous);
        }
    }

    /**
     * A floor of grass at y 60 with, on it or in it: a poppy, a bottom and a top
     * slab, a block of ice, still water, flowing water and still water by a
     * light.
     */
    private static TestWorld meadow(String name) {
        TestWorld world = new TestWorld(name);
        box(world, -6, 59, -6, 6, 59, 6, state("minecraft:stone"));
        box(world, -6, 60, -6, 6, 60, 6, state("minecraft:grass_block"));
        world.setBlock(1, 61, 0, state("minecraft:poppy"));
        world.setBlock(2, 61, 0, parse("minecraft:oak_slab[type=bottom,waterlogged=false]"));
        world.setBlock(3, 61, 0, parse("minecraft:oak_slab[type=top,waterlogged=false]"));
        world.setBlock(-1, 60, 0, state("minecraft:ice"));
        world.setBlock(0, 60, -2, parse("minecraft:water[level=0]"));
        world.setBlock(0, 60, -3, parse("minecraft:water[level=3]"));
        world.setBlock(0, 60, -4, parse("minecraft:water[level=0]"));
        world.setBlockLight(0, 60, -4, 12);
        return world;
    }

    private static void snowLiesWhereTheGameLetsItLie() {
        TestWorld world = meadow("Snow");
        int air = BlockState.registry().air();
        int layer = state("minecraft:snow");
        TestActor actor = new TestActor("Snow", world, new BlockVector3(0, 61, 0));
        answer(actor, "//snow 5");
        checkEquals("//snow puts a layer on the grass", layer, world.getBlock(-2, 61, -2));
        checkEquals("and the grass under it turns snowy", parse("minecraft:grass_block[snowy=true]"),
                world.getBlock(-2, 60, -2));
        checkEquals("it passes the poppy without covering it", state("minecraft:poppy"), world.getBlock(1, 61, 0));
        checkEquals("and puts nothing above it", air, world.getBlock(1, 62, 0));
        checkEquals("nothing lies on a bottom slab", air, world.getBlock(2, 62, 0));
        checkEquals("a top slab holds it", layer, world.getBlock(3, 62, 0));
        checkEquals("nothing lies on ice", air, world.getBlock(-1, 61, 0));
        checkEquals("still water freezes", state("minecraft:ice"), world.getBlock(0, 60, -2));
        checkEquals("flowing water does not", parse("minecraft:water[level=3]"), world.getBlock(0, 60, -3));
        checkEquals("and gets no snow on it", air, world.getBlock(0, 61, -3));
        checkEquals("water by a light does not freeze", parse("minecraft:water[level=0]"), world.getBlock(0, 60, -4));
        check("a second run finds nothing to do", answer(actor, "//snow 5").contains("Snowed: 0 block(s)"));
        checkEquals("and puts no layer on the first", air, world.getBlock(-2, 62, -2));
    }

    private static void snowStacksAndKeepsOffCoveredColumns() {
        TestWorld world = meadow("SnowStack");
        TestActor actor = new TestActor("SnowStack", world, new BlockVector3(0, 61, 0));
        answer(actor, "//snow 5");
        answer(actor, "//snow -s 5");
        checkEquals("-s adds a layer", parse("minecraft:snow[layers=2]"), world.getBlock(-2, 61, -2));
        world.setBlock(-2, 61, -2, parse("minecraft:snow[layers=7]"));
        answer(actor, "//snow -s 5");
        checkEquals("the eighth layer is the last", parse("minecraft:snow[layers=8]"), world.getBlock(-2, 61, -2));
        answer(actor, "//snow -s 5");
        checkEquals("and stays so", parse("minecraft:snow[layers=8]"), world.getBlock(-2, 61, -2));

        TestWorld covered = meadow("SnowCovered");
        covered.setBlock(4, 64, 4, state("minecraft:stone"));
        TestActor under = new TestActor("SnowCovered", covered, new BlockVector3(0, 61, 0));
        answer(under, "//snow 6 2");
        checkEquals("a column whose block above the cylinder is solid is under cover",
                BlockState.registry().air(), covered.getBlock(4, 61, 4));
        checkEquals("the open ones are snowed", state("minecraft:snow"), covered.getBlock(4, 61, 3));
    }

    private static void thawTakesDeepSnowAndTheSnowyGround() {
        TestWorld world = meadow("Thaw");
        world.setBlock(-2, 60, -2, parse("minecraft:grass_block[snowy=true]"));
        world.setBlock(-2, 61, -2, parse("minecraft:snow[layers=3]"));
        world.setBlock(2, 60, 2, state("minecraft:packed_ice"));
        TestActor actor = new TestActor("Thaw", world, new BlockVector3(0, 61, 0));
        answer(actor, "//thaw 5");
        int air = BlockState.registry().air();
        checkEquals("//thaw takes snow of any depth", air, world.getBlock(-2, 61, -2));
        checkEquals("and the ground under it is no longer snowy", parse("minecraft:grass_block[snowy=false]"),
                world.getBlock(-2, 60, -2));
        checkEquals("ice melts", state("minecraft:water"), world.getBlock(-1, 60, 0));
        checkEquals("packed ice does not, as in FAWE", state("minecraft:packed_ice"), world.getBlock(2, 60, 2));
    }

    private static void greenStopsAtAnyWater() {
        TestWorld world = new TestWorld("Green");
        box(world, -4, 60, -4, 4, 60, 4, state("minecraft:dirt"));
        world.setBlock(1, 61, 0, parse("minecraft:water[level=3]"));
        world.setBlock(-1, 61, 0, state("minecraft:poppy"));
        world.setBlock(0, 60, 2, state("minecraft:coarse_dirt"));
        TestActor actor = new TestActor("Green", world, new BlockVector3(0, 61, 0));
        answer(actor, "//green 3");
        int grass = state("minecraft:grass_block");
        checkEquals("//green turns the dirt into grass", grass, world.getBlock(0, 60, 0));
        checkEquals("flowing water stops a column", state("minecraft:dirt"), world.getBlock(1, 60, 0));
        checkEquals("a poppy does not", grass, world.getBlock(-1, 60, 0));
        checkEquals("coarse dirt stays without -f", state("minecraft:coarse_dirt"), world.getBlock(0, 60, 2));
        answer(actor, "//green -f 3");
        checkEquals("and turns with it", grass, world.getBlock(0, 60, 2));
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

    private static int parse(String description) {
        return BlockState.registry().parse(description);
    }

    private static String answer(TestActor actor, String line) {
        actor.clearMessages();
        CommandManager.get().dispatch(actor, line);
        return String.join("\n", actor.messages()).replaceAll("\u00a7.", "");
    }
}
