package com.maxlananas.fawebim.core.test;

import com.maxlananas.fawebim.core.command.CommandManager;
import com.maxlananas.fawebim.core.extent.EditSession;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.platform.Config;
import com.maxlananas.fawebim.core.tool.SuperPickaxe;
import com.maxlananas.fawebim.core.util.LongQueue;
import com.maxlananas.fawebim.core.util.LongSet;
import com.maxlananas.fawebim.core.util.StateCounts;
import com.maxlananas.fawebim.core.world.BlockState;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static com.maxlananas.fawebim.core.test.SelfTestMain.check;
import static com.maxlananas.fawebim.core.test.SelfTestMain.checkEquals;
import static com.maxlananas.fawebim.core.test.SelfTestMain.section;

/**
 * The arguments a command walks the world with are bounded before the walk
 * starts, the super pickaxe breaks what WorldEdit's does, and the tallies and
 * walks over millions of blocks hold primitives. Each command below used to
 * lock the server up, act on the wrong setting or break the wrong blocks.
 */
final class CommandLimitTests {

    private CommandLimitTests() {
    }

    static void run() {
        section("command limits");
        radiiAreRefusedBeforeTheWalk();
        heightsStayInsideTheWorld();
        superPickaxeRangeIsChecked();
        superPickaxePlans();
        toggleEditWandTogglesTheWand();
        floodFillToolFillsTheClickedType();
        countAndDistributionAgree();
        fillStaysInsideTheWorld();
        section("primitive collections");
        longSetMatchesAHashSet();
        longQueueMatchesADeque();
        stateCountsMatchAMap();
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

    /**
     * Each of these walked a cube or a flood the size of its argument on the
     * server thread: two billion blocks of radius is a server that never
     * answers again. They answer to limits.max-radius, as WorldEdit's do.
     */
    private static void radiiAreRefusedBeforeTheWalk() {
        String[] lines = {
            "//fillr stone 2147483647",
            "//removenear stone 2147483647",
            "//extinguish 1000000",
            "//pyramid stone 2147483647",
            "//hpyramid stone 2147483647",
        };
        for (String line : lines) {
            TestActor actor = actor("Limit" + line.hashCode());
            check(line + " is refused with the configured maximum",
                    answer(actor, line).contains("Maximum radius"));
        }
        TestActor actor = actor("LimitDefault");
        check("//extinguish keeps its default radius",
                !answer(actor, "//extinguish").contains("Maximum radius"));
    }

    /** A height far below the world used to be walked a block at a time from there. */
    private static void heightsStayInsideTheWorld() {
        TestActor actor = actor("Heights");
        long start = System.nanoTime();
        String above = answer(actor, "//removeabove 1 -2147483647");
        String below = answer(actor, "//removebelow 1 2147483647");
        long millis = (System.nanoTime() - start) / 1_000_000;
        check("//removeabove with a height below the world answers", !above.isEmpty());
        check("//removebelow with a height above the world answers", !below.isEmpty());
        check("both walk the world's height only (" + millis + " ms)", millis < 20_000);
    }

    private static void superPickaxeRangeIsChecked() {
        TestActor actor = actor("Pickaxe");
        int ceiling = Config.get().maxSuperPickaxeSize;
        check("/sp area above the ceiling is refused",
                answer(actor, "/sp area " + (ceiling + 1)).contains("at most " + ceiling));
        check("/sp recursive above the ceiling is refused",
                answer(actor, "/sp recursive " + (ceiling + 0.5)).contains("at most " + ceiling));
        check("a negative range is refused", answer(actor, "/sp area -1").contains("negative"));
        check("a refused range leaves the pickaxe off", !actor.session().isSuperPickaxeEnabled());
        answer(actor, "/sp area 3");
        check("an accepted range turns the pickaxe on", actor.session().isSuperPickaxeEnabled());
        checkEquals("the area mode is kept", SuperPickaxe.AREA, actor.session().getSuperPickaxeMode());
        checkEquals("its range is kept", 3.0, actor.session().getSuperPickaxeRange());
        answer(actor, "/sp single");
        checkEquals("single is its own mode", SuperPickaxe.SINGLE, actor.session().getSuperPickaxeMode());
        answer(actor, "/sp recur 2.5");
        checkEquals("recursive keeps a decimal range", 2.5, actor.session().getSuperPickaxeRange());
    }

    /**
     * The single pick used to break the cube of the area pick, the area pick
     * broke every block of the cube whatever its type, and the recursive pick
     * ran //deltree, which removes the tree the player looks at.
     */
    private static void superPickaxePlans() {
        TestWorld world = new TestWorld("pickaxe-plan");
        world.fillFlat(70);
        int stone = BlockState.registry().defaultState("minecraft:stone");
        int dirt = BlockState.registry().defaultState("minecraft:dirt");
        // A dirt vein in the stone: a line of five along x, and one block apart from it.
        for (int x = 0; x < 5; x++) {
            world.setBlock(x, 60, 0, dirt);
        }
        world.setBlock(0, 60, 2, dirt);

        checkEquals("a click on air breaks nothing", 0,
                SuperPickaxe.targets(world, SuperPickaxe.AREA, 2, 0, 75, 0).length);
        int[] single = SuperPickaxe.targets(world, SuperPickaxe.SINGLE, 5, 0, 60, 0);
        checkEquals("the single pick breaks the clicked block alone", 3, single.length);

        Set<String> area = positions(SuperPickaxe.targets(world, SuperPickaxe.AREA, 2, 0, 60, 0));
        checkEquals("the area pick breaks the clicked type in its cube", 4, area.size());
        check("the area pick takes a block of the type that is not joined to the click",
                area.contains("0,60,2"));
        check("the area pick stops at its range", !area.contains("3,60,0"));

        int[] recursive = SuperPickaxe.targets(world, SuperPickaxe.RECURSIVE, 10, 0, 60, 0);
        Set<String> joined = positions(recursive);
        checkEquals("the recursive pick breaks the joined blocks of the type", 5, joined.size());
        check("the recursive pick leaves a block that is not joined", !joined.contains("0,60,2"));
        checkEquals("the recursive pick starts at the click", "0,60,0",
                recursive[0] + "," + recursive[1] + "," + recursive[2]);
        Set<String> near = positions(SuperPickaxe.targets(world, SuperPickaxe.RECURSIVE, 2.5, 0, 60, 0));
        checkEquals("the recursive pick stops at its distance", 3, near.size());

        Set<String> stoneArea = positions(SuperPickaxe.targets(world, SuperPickaxe.AREA, 1, 10, 60, 10));
        checkEquals("an area pick in plain stone takes the whole cube", 27, stoneArea.size());
        check("the stone plan names stone blocks", world.getBlock(10, 60, 10) == stone);

        // The plan through an edit session is what the platform runs: undo puts it back.
        TestActor actor = new TestActor("PickaxeUndo", world, new BlockVector3(0, 71, 0));
        EditSession edit = new EditSession(world, actor.session(), "superpickaxe");
        for (int i = 0; i < recursive.length; i += 3) {
            edit.setBlock(recursive[i], recursive[i + 1], recursive[i + 2], BlockState.registry().air());
        }
        edit.close();
        check("the planned blocks are broken", BlockState.registry().isAirLike(world.getBlock(4, 60, 0)));
        CommandManager.get().dispatch(actor, "//undo");
        checkEquals("undo puts the vein back", dirt, world.getBlock(4, 60, 0));
    }

    private static Set<String> positions(int[] triples) {
        Set<String> out = new LinkedHashSet<>();
        for (int i = 0; i < triples.length; i += 3) {
            out.add(triples[i] + "," + triples[i + 1] + "," + triples[i + 2]);
        }
        return out;
    }

    /**
     * The flood fill tool started on the block in front of the clicked face -
     * air, as a rule - and with no mask filled everything within 256 blocks of
     * it: the ground, the builds, all of it. WorldEdit's fills the clicked
     * block and the blocks of its type joined to it, within a range capped
     * like the super pickaxe's.
     */
    private static void floodFillToolFillsTheClickedType() {
        TestWorld world = new TestWorld("flood-tool");
        world.fillFlat(70);
        int dirt = BlockState.registry().defaultState("minecraft:dirt");
        int gold = BlockState.registry().defaultState("minecraft:gold_block");
        TestActor actor = new TestActor("FloodTool", world, new BlockVector3(0, 71, 0));
        check("a range above the ceiling is refused",
                answer(actor, "/tool floodfill gold_block 99").contains("at most"));
        answer(actor, "/tool floodfill gold_block 3");
        com.maxlananas.fawebim.core.tool.Tool tool = com.maxlananas.fawebim.core.tool.Tools.current(actor.session());
        check("the flood fill tool is bound", tool != null);
        check("it is bound to the held item, not to its pattern",
                String.valueOf(actor.session().getBindings().get("tool-item")).equals(actor.heldItem()));
        com.maxlananas.fawebim.core.tool.Tool.ToolContext click = new com.maxlananas.fawebim.core.tool.Tool.ToolContext(
                actor, new BlockVector3(0, 68, 0), com.maxlananas.fawebim.core.world.Direction.UP, null);
        tool.onRightClick(click);
        checkEquals("the clicked dirt is filled", gold, world.getBlock(0, 68, 0));
        checkEquals("the dirt within the range is filled", gold, world.getBlock(3, 68, 0));
        checkEquals("the dirt beyond it is not", dirt, world.getBlock(4, 68, 0));
        check("the air above the click is left alone", BlockState.registry().isAirLike(world.getBlock(0, 70, 0)));
        check("the grass beside the dirt is left alone", world.getBlock(0, 69, 0) != gold);
    }

    /** It turned fast mode on - no lighting for every later edit - and said the wand was toggled. */
    private static void toggleEditWandTogglesTheWand() {
        TestActor actor = actor("Wand");
        boolean fast = actor.session().isFastMode();
        answer(actor, "//toggleeditwand");
        check("the wand is off", !actor.session().isSelectionWandEnabled());
        checkEquals("fast mode is left alone", fast, actor.session().isFastMode());
        answer(actor, "//toggleeditwand");
        check("the wand is on again", actor.session().isSelectionWandEnabled());
    }

    private static void countAndDistributionAgree() {
        TestActor actor = actor("Count");
        answer(actor, "//pos1 0,60,0");
        answer(actor, "//pos2 9,79,9");
        // 10 x 20 x 10 of the flat world: stone up to y 67, dirt at 68, grass
        // at 69, air from 70.
        String count = answer(actor, "//count stone");
        check("//count counts the stone of the selection (" + count + ")", count.contains("Count: 800"));
        String distribution = answer(actor, "//distr");
        check("//distr totals the selection", distribution.contains("2,000 blocks"));
        check("//distr names the stone with its share", distribution.contains("minecraft:stone: 800 (40.00%)"));
        check("//distr counts the air of a selection", distribution.contains("minecraft:air: 1,000 (50.00%)"));
        String size = answer(actor, "//size");
        check("//size counts the chunks without listing them", size.contains("Chunks: 1"));
    }

    /**
     * Two shafts in a block of stone at the top of the world, open to the sky
     * only: a recursive fill started in one used to leave the world through
     * its top, walk over the stone and come down the other shaft.
     */
    private static void fillStaysInsideTheWorld() {
        TestWorld world = new TestWorld("fill-bounds");
        int stone = BlockState.registry().defaultState("minecraft:stone");
        int top = world.maxY();
        for (int x = -1; x <= 6; x++) {
            for (int z = -1; z <= 1; z++) {
                for (int y = top - 4; y <= top; y++) {
                    world.setBlock(x, y, z, stone);
                }
            }
        }
        int air = BlockState.registry().air();
        for (int y = top - 2; y <= top; y++) {
            world.setBlock(0, y, 0, air);
            world.setBlock(4, y, 0, air);
        }
        TestActor actor = new TestActor("FillBounds", world, new BlockVector3(0, top - 2, 0));
        int dirt = BlockState.registry().defaultState("minecraft:dirt");
        EditSession edit = new EditSession(world, actor.session(), "flood");
        com.maxlananas.fawebim.core.function.Operations.floodFill(world, edit, new BlockVector3(0, top - 2, 0),
                (x, y, z) -> dirt, 6, false, null, 0);
        edit.close();
        checkEquals("the shaft the fill started in is filled", dirt, world.getBlock(0, top, 0));
        checkEquals("the other shaft is left alone", air, world.getBlock(4, top - 2, 0));

        // /fillr fills down from where it starts, as WorldEdit's does.
        for (int y = top - 2; y <= top; y++) {
            world.setBlock(0, y, 0, air);
        }
        answer(actor, "//fillr dirt 6");
        checkEquals("/fillr fills its start", dirt, world.getBlock(0, top - 2, 0));
        checkEquals("/fillr leaves what is above its start", air, world.getBlock(0, top - 1, 0));

        // Below y 0 the default depth is the whole world: its lowest level used
        // to be computed in int arithmetic, wrapped round, and filled nothing.
        TestWorld deep = new TestWorld("fill-deep");
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                for (int y = -64; y <= -50; y++) {
                    deep.setBlock(x, y, z, (Math.abs(x) == 2 || Math.abs(z) == 2 || y == -64) ? stone : air);
                }
            }
        }
        TestActor low = new TestActor("FillDeep", deep, new BlockVector3(0, -60, 0));
        answer(low, "//fillr dirt 3");
        checkEquals("a fill below y 0 fills", dirt, deep.getBlock(0, -62, 0));
    }

    private static void longSetMatchesAHashSet() {
        Random random = new Random(7);
        LongSet set = new LongSet();
        Set<Long> reference = new HashSet<>();
        boolean agree = true;
        for (int i = 0; i < 200_000 && agree; i++) {
            // Few distinct values so that adds repeat, zero among them.
            long value = random.nextInt(50_000) - 25_000L;
            agree = set.add(value) == reference.add(value) && set.contains(value)
                    && set.contains(value + 1_000_000) == reference.contains(value + 1_000_000);
        }
        check("the long set answers add and contains like a hash set", agree);
        checkEquals("the long set counts what it holds", reference.size(), set.size());
        check("zero is a value like any other", set.contains(0) == reference.contains(0L));
    }

    private static void longQueueMatchesADeque() {
        Random random = new Random(11);
        LongQueue queue = new LongQueue(4);
        ArrayDeque<Long> reference = new ArrayDeque<>();
        boolean agree = true;
        for (int i = 0; i < 100_000 && agree; i++) {
            if (reference.isEmpty() || random.nextInt(3) > 0) {
                long value = random.nextLong();
                queue.add(value);
                reference.add(value);
            } else {
                agree = queue.poll() == reference.poll();
            }
            agree &= queue.size() == reference.size();
        }
        while (agree && !reference.isEmpty()) {
            agree = queue.poll() == reference.poll();
        }
        check("the long queue gives values back in the order they came, across its growth", agree);
        check("the drained queue is empty", queue.isEmpty());
    }

    private static void stateCountsMatchAMap() {
        StateCounts counts = new StateCounts(8);
        Map<Integer, Long> reference = new java.util.LinkedHashMap<>();
        Random random = new Random(3);
        for (int i = 0; i < 50_000; i++) {
            int state = random.nextInt(40) * 97;
            counts.add(state);
            reference.merge(state, 1L, Long::sum);
        }
        counts.add(-1);
        checkEquals("the tally keeps the states in first-seen order", reference.keySet().iterator().next(),
                counts.state(0));
        checkEquals("the tally sees every state", reference.size(), counts.distinct());
        boolean same = true;
        for (Map.Entry<Integer, Long> entry : reference.entrySet()) {
            same &= counts.count(entry.getKey()) == entry.getValue();
        }
        check("each count matches the map's, the array grown past its first size", same);
        checkEquals("the total counts every add", 50_000L, counts.total());
        Map<String, Long> named = counts.byName(state -> state % 2 == 0 ? "even" : "odd");
        checkEquals("counts summed by name total the same", 50_000L,
                named.values().stream().mapToLong(Long::longValue).sum());
    }
}
