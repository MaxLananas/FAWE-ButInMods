package com.maxlananas.fawebim.core.test;

import com.maxlananas.fawebim.core.command.CommandManager;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.tool.Tool;
import com.maxlananas.fawebim.core.tool.Tools;
import com.maxlananas.fawebim.core.world.BlockState;
import com.maxlananas.fawebim.core.world.BlockStateRegistry;
import com.maxlananas.fawebim.core.world.Direction;

import static com.maxlananas.fawebim.core.test.SelfTestMain.check;
import static com.maxlananas.fawebim.core.test.SelfTestMain.checkEquals;
import static com.maxlananas.fawebim.core.test.SelfTestMain.section;

/**
 * Trees, features and structures grow through the edit, as WorldEdit's and
 * FAWE's do: the mask, the change limit and the history see them.
 *
 * <p>The world placed them straight into the level, so //forest, the tree
 * tool and the feature commands could not be undone and went through any
 * mask. //forest took the highest block of a column wherever it was and grew
 * the tree on top of a plant it should have cleared; //forestgen planted over
 * the selection, where WorldEdit plants around the player, and read its size
 * as the size of a tree; //tree planted a tree where FAWE binds the tree tool;
 * and the tree tool said so on every click, tried a shape once, and wrote
 * around the history.</p>
 */
final class GenerationTests {

    private GenerationTests() {
    }

    static void run() {
        section("generation through the edit");
        BlockStateRegistry previous = BlockState.registry();
        BlockState.setRegistry(new PropertyTestRegistry());
        try {
            forestGoesThroughTheEdit();
            aTreeTheMaskForbidsDoesNotGrow();
            aPlantATreeMayReplaceIsCleared();
            forestgenGrowsAroundThePlayer();
            theTreeToolIsQuietAndUndone();
            featuresAndStructuresGrowWhereFaweGrowsThem();
            thePlacerToolsPlaceAsFawesDo();
        } finally {
            BlockState.setRegistry(previous);
        }
        // With the registry the other tests use: //tree is the tree tool.
        TestWorld world = new TestWorld("TreeCommand");
        world.fillFlat(61);
        TestActor actor = new TestActor("TreeCommand", world, new BlockVector3(0, 61, 0));
        actor.session().unbind(actor.heldItem());
        String bound = answer(actor, "//tree oak");
        check("//tree binds the tree tool, as FAWE's does (" + bound + ")",
                bound.contains("Tree tool bound") && Tools.current(actor) instanceof Tools.TreeTool);
        check("and so does /tree", answer(actor, "/tree birch").contains("Tree tool bound"));
    }

    /** Grass at y 60 over stone, nine by nine around the origin, and a builder standing on it. */
    private static TestActor meadow(String name) {
        TestWorld world = new TestWorld(name);
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                world.setBlock(x, 59, z, state("minecraft:stone"));
                world.setBlock(x, 60, z, state("minecraft:grass_block"));
            }
        }
        TestActor actor = new TestActor(name, world, new BlockVector3(0, 61, 0));
        actor.session().setMaxBlocksChanged(1_000_000);
        return actor;
    }

    private static void forestGoesThroughTheEdit() {
        TestActor actor = meadow("ForestUndo");
        TestWorld world = (TestWorld) actor.world();
        answer(actor, "//pos1 -4,58,-4");
        answer(actor, "//pos2 4,72,4");
        String planted = answer(actor, "//forest oak 100");
        int logs = logs(world);
        check("//forest plants trees (" + planted + ")", planted.contains("Planted: ") && logs > 0);
        check("and counts them as trees, not as blocks affected",
                planted.matches("(?s).*Planted: \\d+ trees? in .*"));
        String undone = answer(actor, "//undo");
        checkEquals("and //undo takes every one of them back (" + undone + ")", 0, logs(world));
        checkEquals("down to the leaves", 0, count(world, "minecraft:oak_leaves"));
    }

    private static void aTreeTheMaskForbidsDoesNotGrow() {
        TestActor actor = meadow("ForestMask");
        TestWorld world = (TestWorld) actor.world();
        answer(actor, "//pos1 -4,58,-4");
        answer(actor, "//pos2 4,72,4");
        // The global mask lets only blocks that are not air change: every block
        // a tree writes goes into air.
        answer(actor, "//gmask !minecraft:air");
        answer(actor, "//forest oak 100");
        checkEquals("a tree grows through the mask like any edit", 0, logs(world));
        answer(actor, "//gmask");
    }

    private static void aPlantATreeMayReplaceIsCleared() {
        TestActor actor = meadow("ForestPlants");
        TestWorld world = (TestWorld) actor.world();
        world.setBlock(0, 61, 0, state("minecraft:short_grass"));
        world.setBlock(3, 61, 3, state("minecraft:poppy"));
        answer(actor, "//pos1 0,58,0");
        answer(actor, "//pos2 0,72,0");
        answer(actor, "//forest oak 100");
        checkEquals("the grass a tree may take the place of is cleared and the tree grows there",
                state("minecraft:oak_log"), world.getBlock(0, 61, 0));
        answer(actor, "//pos1 3,58,3");
        answer(actor, "//pos2 3,72,3");
        answer(actor, "//forest oak 100");
        checkEquals("a flower holds no tree, and stays", state("minecraft:poppy"), world.getBlock(3, 61, 3));
        checkEquals("nothing grows on top of it", 0, world.getBlock(3, 62, 3));
    }

    private static void forestgenGrowsAroundThePlayer() {
        TestActor actor = meadow("ForestGen");
        TestWorld world = (TestWorld) actor.world();
        String planted = answer(actor, "//forestgen 2 oak 100");
        check("//forestgen needs no selection, as WorldEdit's (" + planted + ")", planted.contains("Planted: "));
        boolean inside = true;
        int trunks = 0;
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                if (world.getBlock(x, 61, z) == state("minecraft:oak_log")) {
                    trunks++;
                    inside &= Math.abs(x) <= 2 && Math.abs(z) <= 2;
                }
            }
        }
        check("it plants trees", trunks > 0);
        check("in the box of its size around the player", inside);
        check("and they are undone with it", answer(actor, "//undo").contains("Undid: ") && logs(world) == 0);
        check("a density out of 0..100 is refused",
                answer(actor, "//forestgen 2 oak 150").contains("Density must be between 0 and 100"));
    }

    private static void theTreeToolIsQuietAndUndone() {
        TestActor actor = meadow("TreeTool");
        TestWorld world = (TestWorld) actor.world();
        answer(actor, "/tool tree oak");
        Tool tool = Tools.current(actor);
        actor.clearMessages();
        use(actor, () -> tool.onRightClick(new Tool.ToolContext(actor, new BlockVector3(0, 60, 0), Direction.UP,
                null)));
        checkEquals("the tree tool plants on the clicked block", state("minecraft:oak_log"), world.getBlock(0, 61, 0));
        check("and says nothing when it did (" + actor.messages() + ")", actor.messages().isEmpty());
        answer(actor, "//undo");
        checkEquals("its tree is in the history", 0, logs(world));

        world.setBlock(2, 61, 2, state("minecraft:stone"));
        actor.clearMessages();
        use(actor, () -> tool.onRightClick(new Tool.ToolContext(actor, new BlockVector3(2, 61, 2), Direction.UP,
                null)));
        String refused = String.join("\n", actor.messages()).replaceAll("\u00a7.", "");
        check("a tree that cannot go there is said so, as FAWE says it (" + refused + ")",
                refused.contains("A tree can't go there."));
        check("a tree type nobody knows is refused when the tool is bound",
                answer(actor, "/tool tree nope").contains("Unknown tree type 'nope'"));
        check("and a feature id is taken for what it grows",
                answer(actor, "/tool tree minecraft:azalea_tree").contains("Tree tool bound"));
    }

    /**
     * //feature and //structure generate at the placement position, count the
     * blocks they placed and tell a feature that did not fit from one that
     * does not exist, as FAWE does.
     */
    private static void featuresAndStructuresGrowWhereFaweGrowsThem() {
        TestActor actor = meadow("FeatureCommand");
        TestWorld world = (TestWorld) actor.world();
        int poppy = state("minecraft:poppy");
        String created = answer(actor, "//feature minecraft:poppy");
        check("//feature generates at the placement position (" + created + ")",
                world.getBlock(0, 61, 0) == poppy);
        check("and counts what it placed, as FAWE does", created.contains("Feature created: 1 block placed"));
        check("through the edit", answer(actor, "//undo").contains("Undid: 1 block change"));
        answer(actor, "//feature poppy 3,61,3");
        checkEquals("or at the position it is given", poppy, world.getBlock(3, 61, 3));
        check("an id nobody knows is refused",
                answer(actor, "//feature minecraft:nope").contains("Unknown feature 'minecraft:nope'"));
        world.setBlock(2, 60, 2, state("minecraft:stone"));
        TestActor onStone = new TestActor("FeatureCommand", world, new BlockVector3(2, 61, 2));
        check("a feature that does not fit is said so, as FAWE says it",
                answer(onStone, "//feature oak").contains(Tools.FEATURE_FAILED));

        String structure = answer(actor, "//structure minecraft:oak_log");
        check("//structure needs no selection and generates at the placement (" + structure + ")",
                world.getBlock(0, 61, 0) == state("minecraft:oak_log")
                        && world.getBlock(0, 62, 0) == state("minecraft:oak_log"));
        check("counting what it placed", structure.contains("Structure created: 2 blocks placed"));
        check("an unknown structure is refused",
                answer(actor, "//structure village").contains("Unknown structure 'village'"));
    }

    /**
     * The feature placer grows a tree on the face it clicks, as FAWE's does for
     * the features that grow on a block, and an ore - here a poppy - in the
     * block; each placer tries ten times and says what it placed.
     */
    private static void thePlacerToolsPlaceAsFawesDo() {
        TestActor actor = meadow("FeaturePlacer");
        TestWorld world = (TestWorld) actor.world();
        check("the feature placer takes the features the world knows",
                answer(actor, "/tool featureplacer nope").contains("Unknown feature 'nope'"));
        answer(actor, "/tool featureplacer oak");
        Tool placer = Tools.current(actor);
        actor.clearMessages();
        use(actor, () -> placer.onRightClick(new Tool.ToolContext(actor, new BlockVector3(0, 60, 0), Direction.UP,
                null)));
        String grown = String.join("\n", actor.messages()).replaceAll("\u00a7.", "");
        check("the feature placer grows a tree on the clicked face (" + grown + ")",
                world.getBlock(0, 61, 0) == state("minecraft:oak_log"));
        check("and says how many blocks it placed", grown.contains("Feature created: ") && grown.contains("placed"));

        world.setBlock(2, 61, 2, state("minecraft:stone"));
        actor.clearMessages();
        use(actor, () -> placer.onRightClick(new Tool.ToolContext(actor, new BlockVector3(2, 61, 2), Direction.UP,
                null)));
        check("a tree that finds no soil is said not to go there",
                String.join("\n", actor.messages()).replaceAll("\u00a7.", "").contains(Tools.FEATURE_FAILED));

        answer(actor, "/tool featureplacer minecraft:poppy");
        Tool flowers = Tools.current(actor);
        use(actor, () -> flowers.onRightClick(new Tool.ToolContext(actor, new BlockVector3(-2, 60, -2),
                Direction.EAST, null)));
        checkEquals("a feature of the block goes in the clicked block, whatever the face",
                state("minecraft:poppy"), world.getBlock(-2, 61, -2));

        answer(actor, "/tool structureplacer minecraft:oak_log");
        Tool structures = Tools.current(actor);
        actor.clearMessages();
        use(actor, () -> structures.onRightClick(new Tool.ToolContext(actor, new BlockVector3(3, 60, -3),
                Direction.UP, null)));
        check("the structure placer generates at the clicked block",
                world.getBlock(3, 61, -3) == state("minecraft:oak_log")
                        && String.join("\n", actor.messages()).contains("Structure created"));
    }

    private static int logs(TestWorld world) {
        return count(world, "minecraft:oak_log");
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

    private static boolean use(TestActor actor, java.util.function.BooleanSupplier action) {
        return com.maxlananas.fawebim.core.command.CommandRegistry.interact(actor, "tool", action);
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
