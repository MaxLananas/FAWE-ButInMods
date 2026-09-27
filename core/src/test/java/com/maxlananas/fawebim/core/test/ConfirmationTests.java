package com.maxlananas.fawebim.core.test;

import com.maxlananas.fawebim.core.command.CommandManager;
import com.maxlananas.fawebim.core.command.Confirmation;
import com.maxlananas.fawebim.core.extent.EditSession;
import com.maxlananas.fawebim.core.function.Operations;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.pattern.Pattern;
import com.maxlananas.fawebim.core.platform.Config;
import com.maxlananas.fawebim.core.region.CuboidRegion;
import com.maxlananas.fawebim.core.session.LocalSession;
import com.maxlananas.fawebim.core.world.BlockState;

import java.util.HashSet;
import java.util.Set;

import static com.maxlananas.fawebim.core.test.SelfTestMain.check;
import static com.maxlananas.fawebim.core.test.SelfTestMain.checkEquals;
import static com.maxlananas.fawebim.core.test.SelfTestMain.section;

/**
 * FAWE's {@code @Confirm}, and the walls and faces of a cuboid written once per
 * cell.
 *
 * <p>{@code //confirm} existed and answered whatever ran before it with
 * "Nothing to confirm": no command asked, so a {@code //set} over a whole map
 * ran from a slip of the wand where FAWE stops and asks. {@code //walls} wrote
 * the corner columns of a cuboid twice, which took a pattern twice there, and
 * {@code //faces} walked the whole inside of a cuboid to find its surface.</p>
 */
final class ConfirmationTests {

    /** 725 x 725 columns: 525,625, over FAWE's 524,288. */
    private static final String LARGE_FROM = "0,80,0";
    private static final String LARGE_TO = "724,80,724";

    private ConfirmationTests() {
    }

    static void run() {
        section("confirm");
        aLargeSelectionWaitsForConfirm();
        confirmRunsTheParkedLineOnce();
        aParkedLineWaitsFifteenSeconds();
        aSelectionUnderTheLimitRunsAtOnce();
        theSettingTurnsTheRegionCheckOff();
        aLineWithoutItsArgumentsIsNotParked();
        stackWeighsTheSelectionByItsCopies();
        undoPastFiftyStepsAsks();
        rollbackAsksEveryTime();
        wallsTakeThePatternOncePerCell();
        facesOfACuboidAreItsSurfaceOnce();
    }

    private static TestActor actor(String name) {
        return new TestActor(name, new TestWorld(name), new BlockVector3(0, 81, 0));
    }

    private static int stone() {
        return BlockState.registry().defaultState("minecraft:stone");
    }

    private static void select(TestActor actor, String from, String to) {
        CommandManager.get().dispatch(actor, "//pos1 " + from);
        CommandManager.get().dispatch(actor, "//pos2 " + to);
    }

    private static String last(TestActor actor) {
        return SelfTestMain.plain(actor.lastMessage());
    }

    private static void aLargeSelectionWaitsForConfirm() {
        TestActor actor = actor("ConfirmLarge");
        TestWorld world = (TestWorld) actor.world();
        select(actor, LARGE_FROM, LARGE_TO);
        CommandManager.get().dispatch(actor, "//walls minecraft:stone");
        checkEquals("a selection over 524,288 columns asks with FAWE's line",
                "FAWE » Your selection is large ((0, 80, 0) -> (724, 80, 724), containing 525,625 blocks)."
                        + " Use //confirm to execute //walls minecraft:stone", last(actor));
        check("a command waiting for //confirm writes nothing", world.getBlock(0, 80, 0) != stone());
        check("a command waiting for //confirm records no edit", actor.session().getHistory().getCurrent() == null);
    }

    private static void confirmRunsTheParkedLineOnce() {
        TestActor actor = actor("ConfirmRuns");
        TestWorld world = (TestWorld) actor.world();
        select(actor, LARGE_FROM, LARGE_TO);
        CommandManager.get().dispatch(actor, "//walls minecraft:stone");
        actor.clearMessages();
        CommandManager.get().dispatch(actor, "//confirm");
        check("//confirm runs the command that asked", world.getBlock(0, 80, 0) == stone()
                && world.getBlock(724, 80, 724) == stone() && world.getBlock(1, 80, 1) != stone());
        check("the confirmed command answers for itself", last(actor).startsWith("FAWE » Walls: 2,896 blocks"));
        checkEquals("//confirm answers with its own line and nothing else", 1, actor.messages().size());
        CommandManager.get().dispatch(actor, "//confirm");
        checkEquals("a second //confirm finds nothing left to run",
                "FAWE » You have no actions pending confirmation", last(actor));
    }

    private static void aParkedLineWaitsFifteenSeconds() {
        LocalSession session = actor("ConfirmWait").session();
        session.setPendingCommand("//set stone", 0);
        checkEquals("a parked line is there for fifteen seconds", "//set stone",
                session.takePendingCommand(Confirmation.WAIT_NANOS, Confirmation.WAIT_NANOS));
        checkEquals("taking a parked line clears it", null,
                session.takePendingCommand(1, Confirmation.WAIT_NANOS));
        session.setPendingCommand("//set stone", 0);
        checkEquals("a parked line is gone after fifteen seconds", null,
                session.takePendingCommand(Confirmation.WAIT_NANOS + 1, Confirmation.WAIT_NANOS));
    }

    private static void aSelectionUnderTheLimitRunsAtOnce() {
        TestActor actor = actor("ConfirmSmall");
        TestWorld world = (TestWorld) actor.world();
        // 724 x 724 columns: 524,176.
        select(actor, "0,80,0", "723,80,723");
        CommandManager.get().dispatch(actor, "//walls minecraft:stone");
        check("a selection under the limit runs without asking", world.getBlock(723, 80, 723) == stone()
                && last(actor).startsWith("FAWE » Walls:"));
    }

    private static void theSettingTurnsTheRegionCheckOff() {
        TestActor actor = actor("ConfirmOff");
        TestWorld world = (TestWorld) actor.world();
        select(actor, LARGE_FROM, LARGE_TO);
        Config.get().confirmLarge = false;
        try {
            CommandManager.get().dispatch(actor, "//walls minecraft:stone");
        } finally {
            Config.get().confirmLarge = true;
        }
        check("confirm-large false lets a large selection through", world.getBlock(724, 80, 724) == stone());
    }

    private static void aLineWithoutItsArgumentsIsNotParked() {
        TestActor actor = actor("ConfirmUsage");
        select(actor, LARGE_FROM, LARGE_TO);
        CommandManager.get().dispatch(actor, "//walls");
        check("a line without its pattern gets its usage, not a confirmation",
                !last(actor).contains("//confirm"));
        CommandManager.get().dispatch(actor, "//confirm");
        checkEquals("and nothing is parked for it", "FAWE » You have no actions pending confirmation",
                last(actor));
    }

    private static void stackWeighsTheSelectionByItsCopies() {
        TestActor actor = actor("ConfirmStack");
        // 100 x 100 columns: 52 copies are 520,000, 53 are 530,000.
        select(actor, "0,80,0", "99,80,99");
        CommandManager.get().dispatch(actor, "//stack 53 up");
        check("//stack asks when the copies times the columns pass the limit",
                last(actor).endsWith("Use //confirm to execute //stack 53 up"));
        CommandManager.get().dispatch(actor, "//stack 52 up");
        check("//stack under the limit runs", last(actor).startsWith("FAWE » Stacked:"));
    }

    private static void undoPastFiftyStepsAsks() {
        TestActor actor = actor("ConfirmUndo");
        CommandManager.get().dispatch(actor, "//undo 51");
        checkEquals("//undo past fifty steps asks with FAWE's line",
                "FAWE » You're exceeding your limit for this action (51 > 50). Use //confirm to execute //undo 51",
                last(actor));
        CommandManager.get().dispatch(actor, "//confirm");
        checkEquals("the confirmed //undo runs", "FAWE » Nothing to undo", last(actor));
        CommandManager.get().dispatch(actor, "//undo 50");
        checkEquals("//undo of fifty steps runs at once", "FAWE » Nothing to undo", last(actor));
    }

    private static void rollbackAsksEveryTime() {
        TestActor actor = actor("ConfirmRollback");
        CommandManager.get().dispatch(actor, "//history rollback -u nobody");
        checkEquals("a history rollback always asks first",
                "FAWE » Use //confirm to execute //history rollback -u nobody", last(actor));
        CommandManager.get().dispatch(actor, "//confirm");
        checkEquals("and runs once confirmed", "FAWE » No edit matches those filters", last(actor));
    }

    /** A pattern that notes every cell it is asked for, and how many times. */
    private static final class Recording implements Pattern {
        final Set<BlockVector3> cells = new HashSet<>();
        int calls;

        @Override
        public int apply(int x, int y, int z) {
            calls++;
            cells.add(new BlockVector3(x, y, z));
            return stone();
        }
    }

    private static int walls(TestActor actor, BlockVector3 min, BlockVector3 max, Recording pattern) {
        EditSession session = new EditSession(actor.world(), actor.session(), "walls");
        try {
            return Operations.walls(session, new CuboidRegion(min, max), pattern);
        } finally {
            session.close();
        }
    }

    private static void wallsTakeThePatternOncePerCell() {
        TestActor actor = actor("WallsOnce");
        Recording box = new Recording();
        walls(actor, new BlockVector3(0, 80, 0), new BlockVector3(4, 81, 4), box);
        checkEquals("the walls of a 5x2x5 box are 32 cells", 32, box.cells.size());
        checkEquals("each of them takes the pattern once", 32, box.calls);
        Recording thin = new Recording();
        walls(actor, new BlockVector3(10, 80, 0), new BlockVector3(10, 80, 4), thin);
        checkEquals("a wall one block thick is one row, taken once", 5, thin.calls);
        Recording narrow = new Recording();
        walls(actor, new BlockVector3(20, 80, 0), new BlockVector3(21, 80, 3), narrow);
        check("a box two blocks wide is all wall, each cell once",
                narrow.calls == 8 && narrow.cells.size() == 8);
    }

    private static void facesOfACuboidAreItsSurfaceOnce() {
        TestActor actor = actor("FacesOnce");
        Recording faces = new Recording();
        EditSession session = new EditSession(actor.world(), actor.session(), "faces");
        try {
            Operations.faces(session, new CuboidRegion(new BlockVector3(0, 80, 0), new BlockVector3(4, 83, 4)),
                    faces);
        } finally {
            session.close();
        }
        // 5x4x5 is 100 cells around a 3x2x3 inside.
        checkEquals("the faces of a 5x4x5 box are 82 cells", 82, faces.cells.size());
        checkEquals("each of them takes the pattern once", 82, faces.calls);
        check("the inside is left alone", !faces.cells.contains(new BlockVector3(2, 81, 2))
                && !faces.cells.contains(new BlockVector3(1, 82, 3)));
        check("the edges and the caps are there", faces.cells.contains(new BlockVector3(0, 81, 0))
                && faces.cells.contains(new BlockVector3(2, 80, 2))
                && faces.cells.contains(new BlockVector3(2, 83, 2))
                && faces.cells.contains(new BlockVector3(4, 82, 2)));
    }
}
