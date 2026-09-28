package com.maxlananas.fawebim.core.test;

import com.maxlananas.fawebim.core.clipboard.BlockArrayClipboard;
import com.maxlananas.fawebim.core.clipboard.Clipboards;
import com.maxlananas.fawebim.core.command.CommandManager;
import com.maxlananas.fawebim.core.extent.EditSession;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.math.Vector2;
import com.maxlananas.fawebim.core.region.CylinderRegion;
import com.maxlananas.fawebim.core.transform.Transform;
import com.maxlananas.fawebim.core.world.BlockState;

import java.util.List;

import static com.maxlananas.fawebim.core.test.SelfTestMain.check;
import static com.maxlananas.fawebim.core.test.SelfTestMain.checkEquals;
import static com.maxlananas.fawebim.core.test.SelfTestMain.section;

/**
 * What a paste covers, and what the clipboard is after the commands that
 * replace or clear it.
 *
 * <p>A copy's box grew with the blocks it stored, and it stores no air: air
 * along the sides of a selection, or whole sections of sky above a build,
 * were left out, and the paste left the ground it landed on standing there.
 * FAWE's clipboard is the selection, so its paste clears what the selection
 * had as air, and a shape other than a box pastes inside its outline only.</p>
 */
final class ClipboardPasteTests {

    private ClipboardPasteTests() {
    }

    static void run() {
        section("clipboard paste");
        airAlongTheSidesIsPasted();
        skyAboveTheBuildIsPasted();
        aCopyOfAirClearsItsBox();
        aCylinderPastesInsideItsOutline();
        clearClipboardEmptiesTheClipboard();
        aNewCopyReplacesTheLoadedSchematics();
        lazyCutKeepsTheBuild();
        lazyCopyWithoutAirLeavesTheAir();
    }

    /** A 2x2x2 block of stone in the corner of a 10x7x10 selection of air. */
    private static TestActor cornerBuild(String name) {
        TestWorld world = new TestWorld(name);
        box(world, 0, 64, 0, 1, 65, 1, state("minecraft:stone"));
        box(world, 100, 64, 100, 109, 70, 109, state("minecraft:dirt"));
        TestActor actor = new TestActor(name, world, new BlockVector3(0, 64, 0));
        actor.session().setMaxBlocksChanged(1_000_000);
        return actor;
    }

    private static void airAlongTheSidesIsPasted() {
        TestActor actor = cornerBuild("PasteSides");
        TestWorld world = (TestWorld) actor.world();
        answer(actor, "//pos1 0,64,0");
        answer(actor, "//pos2 9,70,9");
        String copied = answer(actor, "//copy");
        check("//copy counts the blocks it stored (" + copied + ")", copied.contains("Copied: 8 blocks"));
        checkEquals("the clipboard is the selection", 700L,
                actor.session().getClipboard().getClipboard().volume());
        String size = answer(actor, "//size -c");
        check("//size -c gives the selection's size (" + size + ")", size.contains("10 x 7 x 10"));
        answer(actor, "//paste 100,64,100");
        checkEquals("the stone lands on the paste", state("minecraft:stone"), world.getBlock(100, 64, 100));
        checkEquals("and the air of the selection clears the far corner", BlockState.registry().air(),
                world.getBlock(109, 70, 109));
        checkEquals("and every other block the selection covers",
                0, count(world, 100, 64, 100, 109, 70, 109, state("minecraft:dirt")));
    }

    private static void skyAboveTheBuildIsPasted() {
        TestWorld world = new TestWorld("PasteSky");
        box(world, 0, 64, 0, 15, 64, 15, state("minecraft:stone"));
        box(world, 200, 64, 200, 200, 127, 200, state("minecraft:dirt"));
        TestActor actor = new TestActor("PasteSky", world, new BlockVector3(0, 64, 0));
        actor.session().setMaxBlocksChanged(1_000_000);
        // Sections y 80 to 127 of the selection are empty, which the copy
        // skips without reading them.
        answer(actor, "//pos1 0,64,0");
        answer(actor, "//pos2 15,127,15");
        answer(actor, "//copy");
        answer(actor, "//paste 200,64,200");
        checkEquals("a pillar standing in the pasted sky is cleared", BlockState.registry().air(),
                world.getBlock(200, 100, 200));
        checkEquals("up to the top of the selection", BlockState.registry().air(), world.getBlock(200, 127, 200));
    }

    private static void aCopyOfAirClearsItsBox() {
        TestActor actor = cornerBuild("PasteAir");
        TestWorld world = (TestWorld) actor.world();
        actor.setPosition(new BlockVector3(20, 64, 20));
        answer(actor, "//pos1 20,64,20");
        answer(actor, "//pos2 29,70,29");
        answer(actor, "//copy");
        String pasted = answer(actor, "//paste 100,64,100");
        check("a copy of air pastes its whole box (" + pasted + ")", pasted.contains("Pasted: 700 blocks"));
        checkEquals("which leaves no dirt", 0, count(world, 100, 64, 100, 109, 70, 109, state("minecraft:dirt")));
    }

    private static void aCylinderPastesInsideItsOutline() {
        TestWorld world = new TestWorld("PasteCylinder");
        int stone = state("minecraft:stone");
        int dirt = state("minecraft:dirt");
        int air = BlockState.registry().air();
        world.setBlock(0, 64, 0, stone);
        box(world, 95, 64, 95, 105, 64, 105, dirt);
        TestActor actor = new TestActor("PasteCylinder", world, new BlockVector3(0, 64, 0));
        EditSession copy = new EditSession(world, actor.session(), "copy");
        CylinderRegion cylinder = new CylinderRegion(new Vector2(0, 0), 3, 3, 64, 64);
        BlockArrayClipboard clipboard = Clipboards.copy(world, cylinder, copy);
        clipboard.setOrigin(new BlockVector3(0, 64, 0));
        // The selection changing afterwards does not change what was copied.
        cylinder.setCenter(new Vector2(50, 50));
        EditSession paste = new EditSession(world, actor.session(), "paste");
        Clipboards.paste(clipboard, new BlockVector3(100, 64, 100), paste, Transform.identity(), false, false, false);
        paste.flushQueue();
        checkEquals("the centre of the cylinder is pasted", stone, world.getBlock(100, 64, 100));
        checkEquals("its air clears the inside", air, world.getBlock(102, 64, 100));
        checkEquals("up to its edge", air, world.getBlock(100, 64, 97));
        checkEquals("and the corners of its box, outside it, are left alone", dirt, world.getBlock(103, 64, 103));
        checkEquals("on every side", dirt, world.getBlock(97, 64, 97));
    }

    private static void clearClipboardEmptiesTheClipboard() {
        TestActor actor = cornerBuild("ClearClipboard");
        TestWorld world = (TestWorld) actor.world();
        answer(actor, "//pos1 0,64,0");
        answer(actor, "//pos2 1,65,1");
        answer(actor, "//copy");
        answer(actor, "//clearclipboard");
        check("//clearclipboard leaves no clipboard", !actor.session().hasClipboard());
        actor.setPosition(new BlockVector3(100, 70, 100));
        String pasted = answer(actor, "//paste");
        check("so //paste says there is none (" + pasted + ")", pasted.contains("No clipboard"));
        checkEquals("and writes nothing", state("minecraft:dirt"), world.getBlock(100, 70, 100));
    }

    private static void aNewCopyReplacesTheLoadedSchematics() {
        TestActor actor = cornerBuild("CopyAfterLoadAll");
        TestWorld world = (TestWorld) actor.world();
        BlockArrayClipboard gold = single(state("minecraft:gold_block"));
        BlockArrayClipboard glass = single(state("minecraft:glass"));
        actor.session().setClipboard(gold);
        actor.session().setClipboardPool(List.of(gold, glass));
        actor.session().setClipboardDynamicRotation(true);
        answer(actor, "//pos1 0,64,0");
        answer(actor, "//pos2 0,64,0");
        answer(actor, "//copy");
        check("//copy drops the clipboards //schem loadall gathered", actor.session().getClipboardPool().isEmpty());
        check("and the random rotation //schem load -d asked for", !actor.session().isClipboardDynamicRotation());
        answer(actor, "//paste 100,70,100");
        checkEquals("so //paste pastes the copy", state("minecraft:stone"), world.getBlock(100, 70, 100));
    }

    private static void lazyCutKeepsTheBuild() {
        TestActor actor = cornerBuild("LazyCut");
        TestWorld world = (TestWorld) actor.world();
        answer(actor, "//pos1 0,64,0");
        answer(actor, "//pos2 1,65,1");
        answer(actor, "//lazycut");
        checkEquals("//lazycut clears the selection", 0, count(world, 0, 64, 0, 1, 65, 1, state("minecraft:stone")));
        answer(actor, "//paste 0,90,0");
        checkEquals("and a paste brings the build back", 8, count(world, 0, 90, 0, 1, 91, 1, state("minecraft:stone")));
    }

    private static void lazyCopyWithoutAirLeavesTheAir() {
        TestActor actor = cornerBuild("LazyCopyAir");
        TestWorld world = (TestWorld) actor.world();
        answer(actor, "//pos1 0,64,0");
        answer(actor, "//pos2 9,70,9");
        answer(actor, "//lazycopy");
        answer(actor, "//paste -a 100,64,100");
        checkEquals("//paste -a of a lazy copy writes its blocks", state("minecraft:stone"),
                world.getBlock(100, 64, 100));
        checkEquals("and not its air", state("minecraft:dirt"), world.getBlock(109, 70, 109));
    }

    private static BlockArrayClipboard single(int state) {
        BlockArrayClipboard clipboard = new BlockArrayClipboard(BlockVector3.ZERO);
        clipboard.setBlock(0, 0, 0, state);
        return clipboard;
    }

    private static int count(TestWorld world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, int state) {
        int found = 0;
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    if (world.getBlock(x, y, z) == state) {
                        found++;
                    }
                }
            }
        }
        return found;
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
