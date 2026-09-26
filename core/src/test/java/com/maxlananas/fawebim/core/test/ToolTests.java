package com.maxlananas.fawebim.core.test;

import com.maxlananas.fawebim.core.command.CommandManager;
import com.maxlananas.fawebim.core.extent.EditSession;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.tool.Tool;
import com.maxlananas.fawebim.core.tool.Tools;
import com.maxlananas.fawebim.core.util.NbtCompound;
import com.maxlananas.fawebim.core.world.BlockState;
import com.maxlananas.fawebim.core.world.BlockStateRegistry;
import com.maxlananas.fawebim.core.world.Direction;

import java.util.List;

import static com.maxlananas.fawebim.core.test.SelfTestMain.check;
import static com.maxlananas.fawebim.core.test.SelfTestMain.checkEquals;
import static com.maxlananas.fawebim.core.test.SelfTestMain.section;

/**
 * The {@code /tool} bindings do what WorldEdit's do with the arguments
 * WorldEdit gives them, and the masks kept in a session read the world the
 * player edits.
 *
 * <p>The replacer, the long range builder and the stacker took no argument:
 * their pattern was taken for the item to bind to, the builder carved spheres
 * of air and the stacker pasted the clipboard.</p>
 */
final class ToolTests {

    private ToolTests() {
    }

    static void run() {
        section("tools");
        everyListedToolCanBeBound();
        theReplacerTakesItsPatternAndPicksWithItsData();
        theLongRangeBuilderPlacesAgainstTheFaceOrClears();
        theStackerRepeatsTheClickedBlockIntoAir();
        theWandsNeedABlockInSight();
        theNavigationWandJumpsAndTheCompassIsOne();
        jumpToLandsOnFreeSpaceAboveTheTarget();
        theTreeToolPlantsOnTheClickedBlock();
        keptMasksReadTheWorldThePlayerEdits();
        BlockStateRegistry previous = BlockState.registry();
        BlockState.setRegistry(new PropertyTestRegistry());
        try {
            theCyclerSelectsAPropertyAndCyclesIt();
        } finally {
            BlockState.setRegistry(previous);
        }
    }

    private static TestActor actor(String name) {
        TestWorld world = new TestWorld(name);
        world.fillFlat(70);
        return new TestActor(name, world, new BlockVector3(0, 71, 0));
    }

    private static String answer(TestActor actor, String line) {
        actor.clearMessages();
        CommandManager.get().dispatch(actor, line);
        return String.join("\n", actor.messages()).replaceAll("\u00a7.", "");
    }

    private static int state(String name) {
        return BlockState.registry().defaultState(name);
    }

    private static Tool.ToolContext click(TestActor actor, int x, int y, int z, Direction face) {
        return new Tool.ToolContext(actor, new BlockVector3(x, y, z), face, null);
    }

    /** Runs a click the way the platform does: inside the dispatcher's guard, which answers failures. */
    private static boolean use(TestActor actor, java.util.function.BooleanSupplier action) {
        return com.maxlananas.fawebim.core.command.CommandRegistry.interact(actor, "tool", action);
    }

    private static String bound(TestActor actor) {
        return String.valueOf(actor.session().getBindings().get("tool-item"));
    }

    /** The list /tool offers named a "command" tool that /tool refused. */
    private static void everyListedToolCanBeBound() {
        TestActor actor = actor("ToolNames");
        for (String name : Tools.NAMES) {
            if (name.equals("none")) {
                continue;
            }
            String arguments = switch (name) {
                case "repl" -> " stone";
                case "lrbuild" -> " stone air";
                case "featureplacer" -> " oak";
                case "structureplacer" -> " village";
                default -> "";
            };
            Tools.clear(actor.session());
            String reply = answer(actor, "/tool " + name + arguments);
            check("/tool " + name + " binds a tool (" + reply + ")", Tools.current(actor.session()) != null);
            check("/tool " + name + " binds it to the held item", bound(actor).equals(actor.heldItem()));
        }
    }

    private static void theReplacerTakesItsPatternAndPicksWithItsData() {
        TestActor actor = actor("ToolReplacer");
        TestWorld world = (TestWorld) actor.world();
        Tools.clear(actor.session());
        check("the replacer needs its pattern", answer(actor, "/tool repl").contains("Usage: /tool repl <pattern>"));
        check("and binds nothing without it", Tools.current(actor.session()) == null);
        check("a pattern that does not parse is refused",
                answer(actor, "/tool repl notablock").contains("notablock"));
        check("and binds nothing either", Tools.current(actor.session()) == null);

        answer(actor, "/tool repl gold_block");
        Tool tool = Tools.current(actor.session());
        check("the replacer is bound to the held item, not to its pattern", bound(actor).equals(actor.heldItem()));
        use(actor, () -> tool.onRightClick(click(actor, 0, 68, 0, Direction.UP)));
        checkEquals("a right click places the pattern", state("minecraft:gold_block"), world.getBlock(0, 68, 0));
        use(actor, () -> tool.onRightClick(new Tool.ToolContext(actor, new BlockVector3(0, 90, 0), null, null)));
        check("nothing in sight places nothing", BlockState.registry().isAirLike(world.getBlock(0, 90, 0)));

        EditSession edit = new EditSession(world, actor.session(), "chest", false);
        try {
            edit.setBlock(5, 70, 5, state("minecraft:chest"));
            NbtCompound item = new NbtCompound().putByte("Slot", 0).putString("id", "minecraft:diamond")
                    .putInt("count", 7);
            edit.setBlockEntity(5, 70, 5, new NbtCompound().putString("id", "minecraft:chest")
                    .putList("Items", List.of(item)));
        } finally {
            edit.close();
        }
        use(actor, () -> tool.onLeftClick(click(actor, 5, 70, 5, Direction.UP)));
        use(actor, () -> tool.onRightClick(click(actor, 8, 69, 8, Direction.UP)));
        checkEquals("a left click picks the clicked block", state("minecraft:chest"), world.getBlock(8, 69, 8));
        NbtCompound copied = world.getBlockEntity(8, 69, 8);
        check("with its data", copied != null && copied.getCompoundList("Items").size() == 1
                && copied.getCompoundList("Items").get(0).getString("id", "").equals("minecraft:diamond"));

        Tools.clear(actor.session());
        answer(actor, "/tool repl stone minecraft:stick");
        checkEquals("the item after the pattern is the one bound", "minecraft:stick", bound(actor));
    }

    private static void theLongRangeBuilderPlacesAgainstTheFaceOrClears() {
        TestActor actor = actor("ToolLongRange");
        TestWorld world = (TestWorld) actor.world();
        check("the builder needs both patterns",
                answer(actor, "/tool lrbuild stone").contains("Usage: /tool lrbuild"));
        answer(actor, "/tool lrbuild air gold_block");
        Tool tool = Tools.current(actor.session());
        use(actor, () -> tool.onRightClick(click(actor, 3, 69, 3, Direction.UP)));
        checkEquals("a right click places against the clicked face", state("minecraft:gold_block"),
                world.getBlock(3, 70, 3));
        checkEquals("and leaves the clicked block", state("minecraft:grass_block"), world.getBlock(3, 69, 3));
        use(actor, () -> tool.onSwing(click(actor, 3, 70, 3, Direction.UP)));
        check("a pattern of air clears the block in sight", BlockState.registry().isAirLike(world.getBlock(3, 70, 3)));
        checkEquals("and only that block", state("minecraft:grass_block"), world.getBlock(3, 69, 3));
        use(actor, () -> tool.onRightClick(click(actor, 3, 69, 3, Direction.EAST)));
        checkEquals("the face decides the side", state("minecraft:gold_block"), world.getBlock(4, 69, 3));
        actor.clearMessages();
        use(actor, () -> tool.onSwing(new Tool.ToolContext(actor, new BlockVector3(3, 150, 3), null, null)));
        check("nothing in sight is said", String.join(" ", actor.messages()).contains("No block in sight"));
        int changed = 0;
        for (int y = 70; y < 160; y++) {
            if (!BlockState.registry().isAirLike(world.getBlock(3, y, 3))) {
                changed++;
            }
        }
        checkEquals("and changes nothing", 0, changed);
    }

    private static void theStackerRepeatsTheClickedBlockIntoAir() {
        TestActor actor = actor("ToolStacker");
        TestWorld world = (TestWorld) actor.world();
        check("a range above the brush range is refused",
                answer(actor, "/tool stacker 100000").contains("between 1 and"));
        answer(actor, "/tool stacker 3");
        Tool tool = Tools.current(actor.session());
        int grass = state("minecraft:grass_block");
        use(actor, () -> tool.onRightClick(click(actor, 0, 69, 0, Direction.UP)));
        checkEquals("the clicked block is repeated", grass, world.getBlock(0, 70, 0));
        checkEquals("as many times as the range", grass, world.getBlock(0, 72, 0));
        check("and no more", BlockState.registry().isAirLike(world.getBlock(0, 73, 0)));

        world.setBlock(2, 72, 0, state("minecraft:stone"));
        use(actor, () -> tool.onRightClick(click(actor, 2, 69, 0, Direction.UP)));
        checkEquals("it stacks up to the first block that is not air", grass, world.getBlock(2, 71, 0));
        checkEquals("which stays", state("minecraft:stone"), world.getBlock(2, 72, 0));

        answer(actor, "/tool stacker 2 stone");
        Tool masked = Tools.current(actor.session());
        use(actor, () -> masked.onRightClick(click(actor, 0, 67, 0, Direction.DOWN)));
        checkEquals("a mask says what it may stack into", state("minecraft:stone"), world.getBlock(0, 66, 0));
        use(actor, () -> masked.onRightClick(click(actor, 0, 69, 0, Direction.EAST)));
        checkEquals("and what it may not", state("minecraft:grass_block"), world.getBlock(1, 69, 0));
    }

    private static void theWandsNeedABlockInSight() {
        TestActor actor = actor("ToolFarWand");
        answer(actor, "/tool farwand");
        Tool tool = Tools.current(actor.session());
        use(actor, () -> tool.onSwing(click(actor, 40, 69, 40, Direction.UP)));
        use(actor, () -> tool.onRightClick(click(actor, 45, 69, 41, Direction.UP)));
        com.maxlananas.fawebim.core.region.Region region = actor.session().getSelector(actor.world()).getRegion();
        checkEquals("a left click in the air selects the block in sight", new BlockVector3(40, 69, 40),
                region.getMinimumPoint());
        checkEquals("a right click the second corner", new BlockVector3(45, 69, 41), region.getMaximumPoint());
        actor.clearMessages();
        use(actor, () -> tool.onSwing(new Tool.ToolContext(actor, new BlockVector3(0, 200, 0), null, null)));
        check("nothing in sight is said", String.join(" ", actor.messages()).contains("No block in sight"));
        checkEquals("and selects nothing", new BlockVector3(40, 69, 40),
                actor.session().getSelector(actor.world()).getRegion().getMinimumPoint());
    }

    private static void theNavigationWandJumpsAndTheCompassIsOne() {
        TestActor actor = actor("ToolNavigation");
        Tools.clear(actor.session());
        Tool compass = Tools.forItem(actor.session(),
                com.maxlananas.fawebim.core.platform.Config.get().navigationWandItem);
        check("the navigation wand item is the navigation wand", compass != null && compass.name().equals("navwand"));
        check("another item is no tool", Tools.forItem(actor.session(), "minecraft:stick") == null);
        use(actor, () -> compass.onLeftClick(click(actor, 20, 69, 20, Direction.UP)));
        checkEquals("a left click jumps onto the block in sight", new BlockVector3(20, 70, 20), actor.position());
        answer(actor, "/tool farwand");
        check("a tool bound to the item comes first", Tools.forItem(actor.session(), actor.heldItem()) != null
                && Tools.forItem(actor.session(), actor.heldItem()).name().equals("farwand"));
    }

    /** //jumpto dropped the player on top of the target, inside whatever was above it. */
    private static void jumpToLandsOnFreeSpaceAboveTheTarget() {
        TestActor actor = actor("ToolJumpTo");
        answer(actor, "//jumpto 5,60,5");
        checkEquals("the player lands on the first free space above the target", new BlockVector3(5, 70, 5),
                actor.position());
        answer(actor, "//jumpto 5,60,5 -f");
        checkEquals("-f goes to the target itself", new BlockVector3(5, 60, 5), actor.position());
    }

    private static void theTreeToolPlantsOnTheClickedBlock() {
        TestActor actor = actor("ToolTree");
        TestWorld world = (TestWorld) actor.world();
        answer(actor, "/tool tree");
        Tool tool = Tools.current(actor.session());
        use(actor, () -> tool.onRightClick(click(actor, 10, 69, 10, Direction.NORTH)));
        checkEquals("the trunk starts on the clicked block, whatever the face", state("minecraft:oak_log"),
                world.getBlock(10, 70, 10));
    }

    /**
     * A mask parsed in one world read that world for as long as it was kept:
     * //set under //gmask stone, after a trip to another dimension, filled the
     * air there because the overworld had stone at the same place.
     */
    private static void keptMasksReadTheWorldThePlayerEdits() {
        TestWorld overworld = new TestWorld("MaskWorldHere");
        overworld.fillFlat(70);
        TestWorld nether = new TestWorld("MaskWorldThere");
        TestActor here = new TestActor("MaskTraveller", overworld, new BlockVector3(0, 71, 0));
        TestActor there = new TestActor("MaskTraveller", nether, new BlockVector3(0, 71, 0));
        answer(here, "//gmask stone");
        answer(there, "//pos1 0,60,0");
        answer(there, "//pos2 3,62,3");
        answer(there, "//set gold_block");
        int gold = state("minecraft:gold_block");
        int written = 0;
        for (int x = 0; x <= 3; x++) {
            for (int y = 60; y <= 62; y++) {
                for (int z = 0; z <= 3; z++) {
                    if (nether.getBlock(x, y, z) == gold) {
                        written++;
                    }
                }
            }
        }
        checkEquals("the global mask tests the blocks of the world being edited", 0, written);
        answer(here, "//pos1 0,60,0");
        answer(here, "//pos2 3,62,3");
        answer(here, "//set gold_block");
        checkEquals("and still those of the first world back there", gold, overworld.getBlock(1, 61, 1));
        answer(here, "//gmask");

        // Bound in the overworld, where the blocks above y 64 are ground, and
        // used in the other world, where they are air.
        answer(here, "/tool stacker 3 air");
        nether.setBlock(9, 64, 9, state("minecraft:stone"));
        Tool stacker = Tools.current(there.session());
        use(there, () -> stacker.onRightClick(click(there, 9, 64, 9, Direction.UP)));
        checkEquals("a tool's mask reads the world it is used in", state("minecraft:stone"),
                nether.getBlock(9, 67, 9));
    }

    private static void theCyclerSelectsAPropertyAndCyclesIt() {
        TestWorld world = new TestWorld("ToolCycler");
        TestActor actor = new TestActor("ToolCycler", world, new BlockVector3(0, 71, 0));
        BlockStateRegistry registry = BlockState.registry();
        int stairs = registry.defaultState("minecraft:oak_stairs");
        world.setBlock(0, 64, 0, stairs);
        answer(actor, "/tool cycler");
        Tool tool = Tools.current(actor.session());
        String firstName = registry.properties(stairs).keySet().iterator().next();
        String before = registry.properties(stairs).get(firstName);
        use(actor, () -> tool.onRightClick(click(actor, 0, 64, 0, Direction.UP)));
        check("a right click cycles the first property",
                !registry.properties(world.getBlock(0, 64, 0)).get(firstName).equals(before));
        use(actor, () -> tool.onLeftClick(click(actor, 0, 64, 0, Direction.UP)));
        List<String> names = new java.util.ArrayList<>(registry.properties(stairs).keySet());
        String second = names.get(1);
        java.util.Map<String, String> beforeSecond = registry.properties(world.getBlock(0, 64, 0));
        use(actor, () -> tool.onRightClick(click(actor, 0, 64, 0, Direction.UP)));
        java.util.Map<String, String> after = registry.properties(world.getBlock(0, 64, 0));
        check("a left click selects the next property, which the right click then cycles",
                !after.get(second).equals(beforeSecond.get(second)));
        checkEquals("leaving the first one", beforeSecond.get(firstName), after.get(firstName));
        world.setBlock(1, 64, 0, registry.defaultState("minecraft:stone"));
        actor.clearMessages();
        use(actor, () -> tool.onRightClick(click(actor, 1, 64, 0, Direction.UP)));
        check("a block without properties is said to be one",
                String.join(" ", actor.messages()).contains("no property"));
    }
}
