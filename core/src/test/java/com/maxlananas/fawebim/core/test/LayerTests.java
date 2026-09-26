package com.maxlananas.fawebim.core.test;

import com.maxlananas.fawebim.core.command.CommandManager;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.world.BlockState;

import static com.maxlananas.fawebim.core.test.SelfTestMain.check;
import static com.maxlananas.fawebim.core.test.SelfTestMain.checkEquals;
import static com.maxlananas.fawebim.core.test.SelfTestMain.section;

/**
 * {@code //overlay}, {@code //lay}, {@code //naturalize} and {@code //center}
 * as FAWE runs them.
 *
 * <p>{@code //overlay} replaced the top block of each column instead of putting
 * the pattern on it; {@code //lay} took the top block away and put the pattern
 * on what was under it; {@code //naturalize} turned every block of a column
 * into grass, dirt or stone, with two blocks of dirt; and {@code //center}
 * took the block below a centre that was not a whole or a half number.</p>
 */
final class LayerTests {

    private LayerTests() {
    }

    static void run() {
        section("overlay, lay, naturalize, center");
        overlayPutsThePatternOnTopOfTheSurface();
        overlayReachesOneBlockAboveTheSelection();
        overlayLeavesAnEmptyColumnAlone();
        layReplacesTheSurfaceBlock();
        naturalizeOnlyTurnsGrassDirtAndStone();
        naturalizeLeavesUndergroundColumnsAlone();
        centerTakesFaweBox();
    }

    private static TestActor actor(String name) {
        TestWorld world = new TestWorld(name);
        world.fillFlat(70);
        return new TestActor(name, world, new BlockVector3(0, 71, 0));
    }

    private static int state(String name) {
        return BlockState.registry().defaultState(name);
    }

    private static void select(TestActor actor, String from, String to) {
        CommandManager.get().dispatch(actor, "//pos1 " + from);
        CommandManager.get().dispatch(actor, "//pos2 " + to);
    }

    private static void overlayPutsThePatternOnTopOfTheSurface() {
        TestActor actor = actor("Overlay");
        TestWorld world = (TestWorld) actor.world();
        select(actor, "0,60,0", "3,75,3");
        CommandManager.get().dispatch(actor, "//overlay minecraft:gold_block");
        check("//overlay puts the pattern on the grass, not in its place",
                world.getBlock(1, 70, 1) == state("minecraft:gold_block")
                        && world.getBlock(1, 69, 1) == state("minecraft:grass_block"));
        check("//overlay answers with the blocks it put", SelfTestMain.plain(actor.lastMessage())
                .contains("16 block(s)"));
    }

    private static void overlayReachesOneBlockAboveTheSelection() {
        TestActor actor = actor("OverlayTop");
        TestWorld world = (TestWorld) actor.world();
        // The selection ends at the grass: the surface is its top block and the
        // pattern goes on the block above, outside the selection, as in FAWE.
        select(actor, "0,60,0", "0,69,0");
        CommandManager.get().dispatch(actor, "//overlay minecraft:gold_block");
        checkEquals("//overlay writes one block above a selection that ends at the surface",
                state("minecraft:gold_block"), world.getBlock(0, 70, 0));
    }

    private static void overlayLeavesAnEmptyColumnAlone() {
        TestActor actor = actor("OverlayEmpty");
        TestWorld world = (TestWorld) actor.world();
        // Every column of this selection is air: FAWE's search finds no block
        // and only writes when its fallback lands on one.
        select(actor, "0,90,0", "3,95,3");
        int before = world.setCount();
        CommandManager.get().dispatch(actor, "//overlay minecraft:gold_block");
        checkEquals("//overlay over nothing but air writes nothing", before, world.setCount());
    }

    private static void layReplacesTheSurfaceBlock() {
        TestActor actor = actor("Lay");
        TestWorld world = (TestWorld) actor.world();
        world.setBlock(2, 70, 2, state("minecraft:oak_log"));
        // A leaf floating above the first column: the search starts at the
        // bottom of the selection and finds the ground first.
        world.setBlock(0, 73, 0, state("minecraft:oak_leaves"));
        select(actor, "0,60,0", "3,75,3");
        CommandManager.get().dispatch(actor, "//lay minecraft:gold_block");
        check("//lay replaces the surface block itself",
                world.getBlock(1, 69, 1) == state("minecraft:gold_block")
                        && world.getBlock(1, 70, 1) == BlockState.registry().air()
                        && world.getBlock(1, 68, 1) == state("minecraft:dirt"));
        check("//lay leaves a floating block and lays on the ground under it",
                world.getBlock(0, 73, 0) == state("minecraft:oak_leaves")
                        && world.getBlock(0, 69, 0) == state("minecraft:gold_block"));
        checkEquals("a block on the ground is the surface of its column", state("minecraft:gold_block"),
                world.getBlock(2, 70, 2));
        check("//lay counts the columns, as FAWE does",
                SelfTestMain.plain(actor.lastMessage()).contains("16 block(s)"));
    }

    private static void naturalizeOnlyTurnsGrassDirtAndStone() {
        TestActor actor = actor("Naturalize");
        TestWorld world = (TestWorld) actor.world();
        int sand = state("minecraft:sand");
        int ore = state("minecraft:iron_ore");
        // A column of stone under a sand cap, with an ore in it.
        for (int y = 60; y <= 69; y++) {
            world.setBlock(0, y, 0, state("minecraft:stone"));
        }
        world.setBlock(0, 69, 0, sand);
        world.setBlock(0, 65, 0, ore);
        select(actor, "0,60,0", "0,69,0");
        CommandManager.get().dispatch(actor, "//naturalize");
        checkEquals("the sand on top is not ground and stays", sand, world.getBlock(0, 69, 0));
        checkEquals("the first ground block under it becomes grass", state("minecraft:grass_block"),
                world.getBlock(0, 68, 0));
        check("three blocks of dirt follow", world.getBlock(0, 67, 0) == state("minecraft:dirt")
                && world.getBlock(0, 66, 0) == state("minecraft:dirt"));
        checkEquals("the ore stays, and counts as depth", ore, world.getBlock(0, 65, 0));
        checkEquals("so the block under it is stone", state("minecraft:stone"), world.getBlock(0, 64, 0));
    }

    private static void naturalizeLeavesUndergroundColumnsAlone() {
        TestActor actor = actor("NaturalizeDeep");
        TestWorld world = (TestWorld) actor.world();
        // Below the dirt of the flat world: the block above the selection is
        // stone, so the column is underground.
        select(actor, "0,50,0", "0,60,0");
        int before = world.setCount();
        CommandManager.get().dispatch(actor, "//naturalize");
        checkEquals("//naturalize leaves a column whose block above is ground", before, world.setCount());
    }

    private static void centerTakesFaweBox() {
        TestActor actor = actor("Center");
        TestWorld world = (TestWorld) actor.world();
        int gold = state("minecraft:gold_block");
        // From -10 to -1 the centre is -5.5: FAWE's box runs from the centre cut
        // towards zero, -5, to the centre rounded away from zero, -6. The
        // selection is one block high, so its centre is that block.
        select(actor, "-10,80,-10", "-1,80,-1");
        CommandManager.get().dispatch(actor, "//center minecraft:gold_block");
        check("//center of an even box fills its two middle blocks",
                world.getBlock(-6, 80, -6) == gold && world.getBlock(-5, 80, -5) == gold
                        && world.getBlock(-7, 80, -7) != gold && world.getBlock(-4, 80, -4) != gold);
        check("//center of a box one block high stays in that block",
                world.getBlock(-6, 81, -6) != gold && world.getBlock(-6, 79, -6) != gold);
        CommandManager.get().dispatch(actor, "//undo");
        select(actor, "7,80,7", "9,80,9");
        CommandManager.get().dispatch(actor, "//center minecraft:gold_block");
        check("//center of an odd box is its middle block", world.getBlock(8, 80, 8) == gold
                && world.getBlock(7, 80, 7) != gold && world.getBlock(9, 80, 9) != gold);
    }
}
