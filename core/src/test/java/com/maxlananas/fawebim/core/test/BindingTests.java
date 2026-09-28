package com.maxlananas.fawebim.core.test;

import com.maxlananas.fawebim.core.brush.Brush;
import com.maxlananas.fawebim.core.brush.BrushFactory;
import com.maxlananas.fawebim.core.brush.Brushes;
import com.maxlananas.fawebim.core.command.CommandManager;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.tool.Tool;
import com.maxlananas.fawebim.core.tool.Tools;
import com.maxlananas.fawebim.core.world.BlockState;

import static com.maxlananas.fawebim.core.test.SelfTestMain.check;
import static com.maxlananas.fawebim.core.test.SelfTestMain.checkEquals;
import static com.maxlananas.fawebim.core.test.SelfTestMain.section;

/**
 * Brushes and tools are bound per item, as FAWE binds them.
 *
 * <p>A session held one brush, one left click brush and one tool, each with
 * the item it was bound to: binding a brush to the shovel took the one of the
 * axe away, so the two brushes FAWE players keep on two items could not be
 * had, and the settings commands changed the brush bound last whatever the
 * item in hand. The brush /tool secondary bound to the left click was never
 * fired by a left click, and a line of /tool secondary that did not parse
 * moved the right click's brush to the left click.</p>
 */
final class BindingTests {

    private static final String AXE = "minecraft:wooden_axe";
    private static final String SHOVEL = "minecraft:wooden_shovel";

    private BindingTests() {
    }

    static void run() {
        section("bindings per item");
        eachItemKeepsItsOwnBrush();
        aToolAndABrushShareNoItem();
        theSettingsAreThoseOfTheBrushInHand();
        theLeftClickHasABrushOfItsOwn();
        unbindingTakesOffWhatTheHeldItemHolds();
        aPresetSavesTheBrushInHand();
        theBrushToolFiresTheBrushBoundLast();
        aLineThatNamesNoToolOrBrushKeepsWhatTheItemHolds();
        eachClipboardBrushKeepsTheClipboardItWasBoundWith();
        aBrushIsBoundToBothClicksAsFaweBindsIt();
    }

    /**
     * FAWE's /brush gives the brush tool's primary and secondary settings the
     * same brush: a left click fires it as a right click does, a setting
     * changes it for both, and /tool secondary is what gives the left click
     * another brush. /tool primary and /tool secondary on an item that holds
     * no brush bind theirs to both clicks too. The left click fired nothing
     * until /tool secondary gave it a brush.
     */
    private static void aBrushIsBoundToBothClicksAsFaweBindsIt() {
        TestActor actor = actor("BindBothClicks");
        actor.setHeldItem(AXE);
        answer(actor, "/brush sphere stone 2");
        check("a left click fires the brush /brush bound", BrushFactory.currentSecondary(actor) != null
                && BrushFactory.currentSecondary(actor) == BrushFactory.current(actor));
        answer(actor, "/tool size 4");
        check("and a setting changes it for both clicks", BrushFactory.currentSecondary(actor).radius() == 4.0);
        answer(actor, "/tool secondary cylinder dirt 3");
        check("/tool secondary gives the left click a brush of its own",
                BrushFactory.currentSecondary(actor) instanceof Brushes.CylinderBrush
                        && BrushFactory.current(actor) instanceof Brushes.SphereBrush);
        answer(actor, "/brush smooth 3");
        check("and a /brush takes both clicks again", BrushFactory.current(actor) instanceof Brushes.SmoothBrush
                && BrushFactory.currentSecondary(actor) == BrushFactory.current(actor));

        actor.setHeldItem(SHOVEL);
        answer(actor, "/tool primary sphere stone 2");
        check("/tool primary on an item with no brush binds both clicks",
                BrushFactory.current(actor) instanceof Brushes.SphereBrush
                        && BrushFactory.currentSecondary(actor) == BrushFactory.current(actor));
        actor.setHeldItem("minecraft:stick");
        String line = answer(actor, "/tool secondary cylinder dirt 3");
        check("so does /tool secondary, which then names no left click of its own (" + line + ")",
                BrushFactory.current(actor) instanceof Brushes.CylinderBrush
                        && BrushFactory.currentSecondary(actor) == BrushFactory.current(actor)
                        && !line.contains("Left click"));
    }

    private static int stroke(TestActor actor, int x, int y, int z) {
        Brush brush = BrushFactory.current(actor);
        com.maxlananas.fawebim.core.extent.EditSession session =
                new com.maxlananas.fawebim.core.extent.EditSession(actor.world(), actor.session(), "brush");
        try {
            return Brushes.apply(brush, session, new BlockVector3(x, y, z), actor);
        } finally {
            session.close();
        }
    }

    /**
     * WorldEdit's clipboard brush keeps the clipboard it is bound with, and
     * its transform: a FAWE player copies one tree, binds it, copies another
     * and binds it to a second item. Every clipboard brush pasted the last
     * copy, turned by the last //rotate, and one was bound with no clipboard
     * at all, to answer "No clipboard" on every click.
     */
    private static void eachClipboardBrushKeepsTheClipboardItWasBoundWith() {
        TestActor actor = actor("BindClipboards");
        TestWorld world = (TestWorld) actor.world();
        int gold = com.maxlananas.fawebim.core.world.BlockState.registry().defaultState("minecraft:gold_block");
        int diamond = com.maxlananas.fawebim.core.world.BlockState.registry().defaultState("minecraft:diamond_block");
        int stone = com.maxlananas.fawebim.core.world.BlockState.registry().defaultState("minecraft:stone");
        world.setBlock(5, 70, 5, gold);
        world.setBlock(6, 70, 5, stone);
        answer(actor, "//pos1 5,70,5");
        answer(actor, "//pos2 6,70,5");
        answer(actor, "//copy");
        actor.setHeldItem(AXE);
        String bound = answer(actor, "/brush clipboard");
        check("a brush with no radius is bound without one (" + bound + ")",
                bound.endsWith("Brush 'clipboard' equipped"));
        String shown = answer(actor, "/brush");
        check("and shown without one (" + shown + ")", shown.contains("Both clicks: /brush clipboard")
                && !shown.contains("size"));
        world.setBlock(5, 70, 5, diamond);
        answer(actor, "//copy");
        answer(actor, "//rotate 90");
        actor.setHeldItem(SHOVEL);
        answer(actor, "/brush clipboard");

        actor.setHeldItem(AXE);
        stroke(actor, 20, 80, 20);
        check("the axe pastes the gold it was bound with, not the diamond copied after",
                world.getBlock(20, 80, 20) == gold);
        check("and as it was copied, not turned by the //rotate after", world.getBlock(21, 80, 20) == stone);
        actor.setHeldItem(SHOVEL);
        stroke(actor, 40, 80, 40);
        // WorldEdit centres the box as it was copied and turns it about the
        // origin of the copy, so the turned paste is found where it landed.
        BlockVector3 found = null;
        for (int x = 25; x <= 55 && found == null; x++) {
            for (int y = 70; y <= 90 && found == null; y++) {
                for (int z = 25; z <= 55 && found == null; z++) {
                    if (world.getBlock(x, y, z) == diamond) {
                        found = new BlockVector3(x, y, z);
                    }
                }
            }
        }
        BlockVector3 at = found;
        check("the shovel pastes the diamond, turned by the //rotate before it was bound", at != null
                && world.getBlock(at.x() + 1, at.y(), at.z()) != stone
                && (world.getBlock(at.x(), at.y(), at.z() + 1) == stone
                        || world.getBlock(at.x(), at.y(), at.z() - 1) == stone));

        TestActor empty = actor("BindNoClipboard");
        empty.setHeldItem(AXE);
        String refused = answer(empty, "/brush clipboard");
        check("with no clipboard the brush is refused (" + refused + ")",
                refused.contains("No clipboard: use //copy first") && BrushFactory.current(empty) == null);
    }

    /**
     * FAWE requires the tool after /tool and lists the tools when it is not
     * there or not known. A bare /tool was taken for /tool none, so a player
     * looking for the tools lost the brush in hand; and a word after /brush
     * that named no brush was ignored, the brushes bound shown as if it were
     * not there. FAWE's global /none, and /tool inspect with an item after it,
     * did not answer as the other tools do.
     */
    private static void aLineThatNamesNoToolOrBrushKeepsWhatTheItemHolds() {
        TestActor actor = actor("BindBareTool");
        actor.setHeldItem(AXE);
        answer(actor, "/brush sphere stone 2");
        String bare = answer(actor, "/tool");
        check("a bare /tool lists the tools (" + bare + ")",
                bare.contains("No tool given. Options: none, selwand, navwand, info, inspect, tree,")
                        && bare.endsWith("farwand, lrbuild"));
        check("and keeps the brush in hand", BrushFactory.current(actor) instanceof Brushes.SphereBrush);
        String unknown = answer(actor, "/tool nope");
        check("an unknown tool is named, with the tools (" + unknown + ")",
                unknown.contains("Unknown tool 'nope'. Options: none, selwand,"));
        check("and binds nothing in place of the brush", BrushFactory.current(actor) instanceof Brushes.SphereBrush);

        check("a mistyped brush is named back",
                answer(actor, "/brush sphear 3").endsWith("Unknown brush 'sphear'. Did you mean /brush sphere?"));
        check("two letters swapped as well",
                answer(actor, "//brush shpere").endsWith("Unknown brush 'shpere'. Did you mean /brush sphere?"));
        check("a word near no brush points to the list",
                answer(actor, "/br zzz").endsWith("Unknown brush 'zzz'. See //help -s brush"));
        check("none of them rebinds the item", BrushFactory.current(actor) instanceof Brushes.SphereBrush
                && BrushFactory.current(actor).radius() == 2.0);
        check("and a bare /brush still shows the brush in hand",
                answer(actor, "/brush").contains("Both clicks: /brush sphere stone 2 (size 2)"));

        check("/none takes it off, as FAWE's global spelling of /tool none does",
                answer(actor, "/none").endsWith("Brush unbound from your current item")
                        && actor.session().binding(AXE) == null);
        String inspect = answer(actor, "/tool inspect minecraft:stick");
        check("/tool inspect binds to the item named after it, as /tool info does (" + inspect + ")",
                inspect.endsWith("Info tool bound to Stick") && actor.session().binding("minecraft:stick") != null
                        && actor.session().binding(AXE) == null);
    }

    private static TestActor actor(String name) {
        TestWorld world = new TestWorld(name);
        world.fillFlat(64);
        return new TestActor(name, world, new BlockVector3(0, 65, 0));
    }

    private static String answer(TestActor actor, String line) {
        actor.clearMessages();
        CommandManager.get().dispatch(actor, line);
        return String.join("\n", actor.messages()).replaceAll("\u00a7.", "");
    }

    private static void eachItemKeepsItsOwnBrush() {
        TestActor actor = actor("BindTwoBrushes");
        actor.setHeldItem(AXE);
        answer(actor, "/brush sphere stone 2");
        actor.setHeldItem(SHOVEL);
        answer(actor, "/brush cylinder dirt 3");
        check("the shovel has the cylinder", BrushFactory.current(actor) instanceof Brushes.CylinderBrush);
        actor.setHeldItem(AXE);
        Brush axe = BrushFactory.current(actor);
        check("and the axe still has its sphere", axe instanceof Brushes.SphereBrush && axe.radius() == 2.0);
        actor.setHeldItem("minecraft:stick");
        check("an item nothing was bound to has no brush", BrushFactory.current(actor) == null);
    }

    private static void aToolAndABrushShareNoItem() {
        TestActor actor = actor("BindToolAndBrush");
        actor.setHeldItem(AXE);
        answer(actor, "/brush sphere stone 2");
        actor.setHeldItem(SHOVEL);
        answer(actor, "/tool farwand");
        check("a tool on one item", Tools.current(actor) != null && Tools.current(actor).name().equals("farwand"));
        actor.setHeldItem(AXE);
        check("leaves the brush of another", BrushFactory.current(actor) != null && Tools.current(actor) == null);
        answer(actor, "/tool farwand");
        check("a tool bound to the item of a brush takes the brush off, as FAWE's item holds one tool",
                BrushFactory.current(actor) == null && Tools.current(actor) != null);
        answer(actor, "/brush sphere stone 2");
        check("and a brush the tool", BrushFactory.current(actor) != null && Tools.current(actor) == null);
    }

    private static void theSettingsAreThoseOfTheBrushInHand() {
        TestActor actor = actor("BindSettings");
        actor.setHeldItem(AXE);
        answer(actor, "/brush sphere stone 2");
        actor.setHeldItem(SHOVEL);
        answer(actor, "/brush sphere dirt 3");
        actor.setHeldItem(AXE);
        answer(actor, "/tool size 5");
        check("/tool size sets the brush in hand", BrushFactory.current(actor).radius() == 5.0);
        actor.setHeldItem(SHOVEL);
        check("and not the one bound last", BrushFactory.current(actor).radius() == 3.0);
        actor.setHeldItem("minecraft:stick");
        check("an item without a brush has none to set",
                answer(actor, "/tool size 4").contains("No brush bound"));
    }

    private static void theLeftClickHasABrushOfItsOwn() {
        TestActor actor = actor("BindSecondary");
        actor.setHeldItem(AXE);
        answer(actor, "/tool primary sphere stone 2");
        answer(actor, "/tool secondary cylinder dirt 3");
        check("the right click keeps its brush", BrushFactory.current(actor) instanceof Brushes.SphereBrush);
        check("and the left click has the other",
                BrushFactory.currentSecondary(actor) instanceof Brushes.CylinderBrush);
        String failed = answer(actor, "/tool secondary sphere notablock 2");
        check("a line that does not parse says so (" + failed + ")", failed.contains("notablock"));
        check("and moves nothing to the left click",
                BrushFactory.currentSecondary(actor) instanceof Brushes.CylinderBrush
                        && BrushFactory.current(actor) instanceof Brushes.SphereBrush);
        actor.setHeldItem(AXE);
        String shown = answer(actor, "/brush");
        check("/brush shows each click by the line that built it (" + shown + ")",
                shown.contains("Right click: /brush sphere stone 2 (size 2)")
                        && shown.contains("Left click: /brush cylinder dirt 3 (size 3)"));
        actor.setHeldItem(SHOVEL);
        check("another item has no left click brush", BrushFactory.currentSecondary(actor) == null);
        check("and /brush says so, with a line that works",
                answer(actor, "/brush").contains("Use /brush sphere stone 5"));
    }

    private static void unbindingTakesOffWhatTheHeldItemHolds() {
        TestActor actor = actor("BindNone");
        actor.setHeldItem(AXE);
        answer(actor, "/brush sphere stone 2");
        actor.setHeldItem(SHOVEL);
        answer(actor, "/brush sphere dirt 3");
        check("/brush none says it took a brush off",
                answer(actor, "/brush none").endsWith("Brush unbound from your current item"));
        check("the shovel has nothing left", actor.session().binding(SHOVEL) == null);
        actor.setHeldItem(AXE);
        check("the axe keeps its brush", BrushFactory.current(actor) != null);
        answer(actor, "/tool secondary cylinder dirt 3");
        answer(actor, "/tool none");
        check("/tool none takes both clicks off", BrushFactory.current(actor) == null
                && BrushFactory.currentSecondary(actor) == null);
        check("an item with nothing on it is said to have its tool unbound",
                answer(actor, "/brush none").endsWith("Tool unbound from your current item"));
    }

    private static void aPresetSavesTheBrushInHand() {
        TestActor actor = actor("BindPreset");
        actor.setHeldItem(AXE);
        answer(actor, "/brush sphere stone 2");
        actor.setHeldItem(SHOVEL);
        answer(actor, "/brush cylinder dirt 3");
        actor.setHeldItem(AXE);
        answer(actor, "/brush savebrush bindingaxe");
        actor.setHeldItem("minecraft:stick");
        check("an item without a brush has none to save",
                answer(actor, "/brush savebrush bindingstick").contains("No brush bound"));
        answer(actor, "/brush loadbrush bindingaxe");
        check("the preset of the axe binds its sphere to the item in hand",
                BrushFactory.current(actor) instanceof Brushes.SphereBrush);
    }

    private static void theBrushToolFiresTheBrushBoundLast() {
        TestActor actor = actor("BindBrushTool");
        TestWorld world = (TestWorld) actor.world();
        actor.setHeldItem(AXE);
        answer(actor, "/brush sphere gold_block 1");
        actor.setHeldItem("minecraft:stick");
        answer(actor, "/tool brush");
        Tool tool = Tools.current(actor);
        check("/tool brush binds the brush tool", tool != null && tool.name().equals("brush"));
        actor.clearMessages();
        tool.onRightClick(new Tool.ToolContext(actor, new BlockVector3(0, 70, 0), null, null));
        checkEquals("it fires the brush bound last", BlockState.registry().defaultState("minecraft:gold_block"),
                world.getBlock(0, 70, 0));
        check("and says nothing, as a brush stroke does", actor.messages().isEmpty());
    }
}
