package com.maxlananas.fawebim.core.test;

import com.maxlananas.fawebim.core.brush.Brush;
import com.maxlananas.fawebim.core.brush.BrushFactory;
import com.maxlananas.fawebim.core.command.CommandManager;
import com.maxlananas.fawebim.core.extent.EditSession;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.world.BlockState;

import static com.maxlananas.fawebim.core.test.SelfTestMain.check;
import static com.maxlananas.fawebim.core.test.SelfTestMain.checkEquals;
import static com.maxlananas.fawebim.core.test.SelfTestMain.plain;
import static com.maxlananas.fawebim.core.test.SelfTestMain.section;

/**
 * The brushes FAWE declares with an optional argument before a required one,
 * or with a list at the end, and the shapes and layers those arguments carry.
 *
 * <p>The arguments were bound by position: {@code /brush set cuboid stone}
 * read stone as the radius, {@code /brush forest sphere oak} bound a brush of
 * radius 0, {@code /brush command} kept the first word of its command and
 * {@code /brush layer} placed air, its list being read by no one. The set
 * brush ignored its shape, and the sphere brush measured the bare radius where
 * WorldEdit grows it by half a block.</p>
 */
final class BrushArgumentTests {

    private BrushArgumentTests() {
    }

    static void run() {
        section("brush arguments");
        anOptionalArgumentGivesWayToARequiredOne();
        theSetBrushBuildsItsShape();
        theSphereBrushIsWorldEditsSphere();
        aListTakesTheRestOfTheLine();
        theLayerBrushLaysItsListInwards();
        replaceWithOneArgumentReplacesEveryBlock();
    }

    private static TestActor actor(String name) {
        TestWorld world = new TestWorld(name);
        world.fillFlat(70);
        return new TestActor(name, world, new BlockVector3(0, 71, 0));
    }

    private static int state(String name) {
        return BlockState.registry().defaultState(name);
    }

    private static String bind(TestActor actor, String line) {
        actor.clearMessages();
        CommandManager.get().dispatch(actor, line);
        return plain(actor.lastMessage());
    }

    private static int apply(TestActor actor, BlockVector3 at) {
        Brush brush = BrushFactory.current(actor);
        EditSession edit = new EditSession(actor.world(), actor.session(), "brush");
        try {
            return brush.apply(edit, at, actor);
        } finally {
            edit.close();
        }
    }

    private static void anOptionalArgumentGivesWayToARequiredOne() {
        TestActor actor = actor("BrushOptional");
        check("/brush set <shape> <pattern> keeps the default radius",
                bind(actor, "/brush set cuboid minecraft:stone").contains("equipped (radius 5)"));
        check("and takes a radius when there is room for one",
                bind(actor, "/brush set cuboid 3 minecraft:stone").contains("equipped (radius 3)"));
        check("/brush biome <shape> <biome> keeps the default radius",
                bind(actor, "/brush biome sphere minecraft:plains").contains("equipped (radius 5)"));
        check("/brush forest <shape> <type> keeps the radius and the density",
                bind(actor, "/brush forest sphere oak").contains("equipped (radius 5)"));
        check("/brush forest <shape> <radius> <type> skips the density",
                bind(actor, "/brush forest sphere 4 oak").contains("equipped (radius 4)"));
    }

    private static void theSetBrushBuildsItsShape() {
        TestActor actor = actor("BrushShape");
        TestWorld world = (TestWorld) actor.world();
        int stone = state("minecraft:stone");
        bind(actor, "/brush set cuboid 2 minecraft:stone");
        checkEquals("the cuboid shape is the cube of the radius", 125, apply(actor, new BlockVector3(0, 100, 0)));
        check("with its corners", world.getBlock(2, 102, 2) == stone && world.getBlock(-2, 98, -2) == stone);
        bind(actor, "/brush set cyl 2 minecraft:stone");
        apply(actor, new BlockVector3(20, 100, 20));
        check("the cylinder shape is a disc one block high", world.getBlock(22, 100, 20) == stone
                && world.getBlock(20, 101, 20) != stone && world.getBlock(20, 99, 20) != stone);
        check("an unknown shape is refused when the brush is bound",
                bind(actor, "/brush set blob minecraft:stone").contains("Unknown shape 'blob'"));
    }

    private static void theSphereBrushIsWorldEditsSphere() {
        TestActor actor = actor("BrushSphere");
        bind(actor, "/brush sphere minecraft:stone 2");
        // The cells within 2.5 blocks of the centre, as //sphere 2 builds it.
        checkEquals("a sphere brush of radius 2 is WorldEdit's 81 blocks", 81,
                apply(actor, new BlockVector3(0, 100, 0)));
    }

    private static void aListTakesTheRestOfTheLine() {
        TestActor actor = actor("BrushList");
        bind(actor, "/brush command 5 fawebimnothing with three words");
        actor.clearMessages();
        apply(actor, new BlockVector3(0, 69, 0));
        check("/brush command runs every word of its command",
                actor.messages().stream().anyMatch(m -> plain(m).contains("fawebimnothing with three words")));
    }

    private static void theLayerBrushLaysItsListInwards() {
        TestActor actor = actor("BrushLayer");
        TestWorld world = (TestWorld) actor.world();
        int gold = state("minecraft:gold_block");
        int diamond = state("minecraft:diamond_block");
        int emerald = state("minecraft:emerald_block");
        check("/brush layer takes its list",
                bind(actor, "/brush layer 3 minecraft:gold_block,minecraft:diamond_block,minecraft:emerald_block")
                        .contains("equipped (radius 3)"));
        apply(actor, new BlockVector3(0, 69, 0));
        check("the surface takes the first entry, the blocks under it the next ones",
                world.getBlock(0, 69, 0) == gold && world.getBlock(0, 68, 0) == diamond
                        && world.getBlock(0, 67, 0) == emerald
                        && world.getBlock(0, 66, 0) == state("minecraft:stone"));
        check("nothing past the radius changes", world.getBlock(4, 69, 0) == state("minecraft:grass_block")
                && world.getBlock(4, 68, 0) == state("minecraft:dirt"));
        check("and the air above stays air", world.getBlock(0, 70, 0) == BlockState.registry().air());
    }

    private static void replaceWithOneArgumentReplacesEveryBlock() {
        TestActor actor = actor("ReplaceOne");
        TestWorld world = (TestWorld) actor.world();
        CommandManager.get().dispatch(actor, "//pos1 0,69,0");
        CommandManager.get().dispatch(actor, "//pos2 1,70,1");
        CommandManager.get().dispatch(actor, "//replace minecraft:gold_block");
        check("//replace <pattern> replaces the blocks that are not air",
                world.getBlock(0, 69, 0) == state("minecraft:gold_block")
                        && world.getBlock(0, 70, 0) == BlockState.registry().air());
        actor.setPosition(new BlockVector3(0, 70, 0));
        CommandManager.get().dispatch(actor, "//replacenear 1 minecraft:diamond_block");
        check("//replacenear <size> <pattern> as well", world.getBlock(0, 69, 0) == state("minecraft:diamond_block")
                && world.getBlock(0, 70, 0) == BlockState.registry().air());
    }
}
