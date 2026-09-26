package com.maxlananas.fawebim.core.test;

import com.maxlananas.fawebim.core.command.CommandManager;
import com.maxlananas.fawebim.core.extent.EditSession;
import com.maxlananas.fawebim.core.history.EditLog;
import com.maxlananas.fawebim.core.history.History;
import com.maxlananas.fawebim.core.history.Snapshots;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.platform.Config;
import com.maxlananas.fawebim.core.session.LocalSession;
import com.maxlananas.fawebim.core.session.SessionManager;
import com.maxlananas.fawebim.core.util.NbtCompound;
import com.maxlananas.fawebim.core.world.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.maxlananas.fawebim.core.test.SelfTestMain.check;
import static com.maxlananas.fawebim.core.test.SelfTestMain.checkEquals;
import static com.maxlananas.fawebim.core.test.SelfTestMain.section;

/**
 * What an edit leaves behind: the world, the history of it and the log of it
 * have to agree with each other whatever order the writes of the edit came in.
 */
final class HistoryIntegrityTests {

    private HistoryIntegrityTests() {
    }

    static void run() {
        section("history integrity");
        writeBackOverAPendingWrite();
        undoOfACellWrittenTwice();
        snapshotRestoreOfACellWrittenTwice();
        recordsArePublishedOnce();
        sharedHistoryKeepsTheEditor();
        sealedRecordsRefuseWrites();
        cancelIsNotCarriedOver();
        queueFlushesWhatItHolds();
        traceStaysBounded();
    }

    private static int state(String name) {
        return BlockState.registry().defaultState(name);
    }

    /**
     * A write that puts back what the world holds, on a cell the edit already
     * wrote and has not flushed yet, is a real change: it undoes the pending
     * write. Reading the "before" value from the world dropped it as a no-op, so
     * the first write reached the world anyway - an overlapping move left holes.
     */
    private static void writeBackOverAPendingWrite() {
        TestWorld world = new TestWorld("pending-write");
        world.fillFlat(70);
        LocalSession session = new TestActor("Pending", world, new BlockVector3(0, 71, 0)).session();
        int stone = state("minecraft:stone");
        int air = BlockState.registry().air();

        EditSession edit = new EditSession(world, session, "overlap");
        check("the first write is taken", edit.setBlock(1, 72, 1, stone));
        check("writing back what the world holds over a pending write is a change",
                edit.setBlock(1, 72, 1, air));
        edit.flushQueue();
        checkEquals("the cell ends as the last write left it", air, world.getBlock(1, 72, 1));

        EditSession known = new EditSession(world, session, "known");
        known.setBlock(2, 72, 2, stone);
        // The caller read the world before the pending write and hands that
        // value over; the buffer knows better.
        check("a write with a stale known state is still a change",
                known.setBlockKnown(2, 72, 2, air, air, true));
        known.flushQueue();
        checkEquals("a known-state write over a pending write lands", air, world.getBlock(2, 72, 2));
    }

    /**
     * A cell written twice in one edit - on both sides of a mid-edit flush, or
     * twice in the same buffer - is recorded twice. The undo has to walk the rows
     * back to front to end on the state the cell held before the edit.
     */
    private static void undoOfACellWrittenTwice() {
        TestWorld world = new TestWorld("written-twice");
        world.fillFlat(70);
        LocalSession session = new TestActor("Twice", world, new BlockVector3(0, 71, 0)).session();
        int stone = state("minecraft:stone");
        int dirt = state("minecraft:dirt");
        int air = BlockState.registry().air();

        EditSession edit = new EditSession(world, session, "twice");
        edit.setBlock(3, 72, 3, stone);
        edit.flushQueue();
        edit.setBlock(3, 72, 3, dirt);
        edit.setBlock(4, 72, 4, stone);
        edit.setBlock(4, 72, 4, dirt);
        edit.close();
        checkEquals("the second write is in the world", dirt, world.getBlock(3, 72, 3));

        History.Record record = session.getHistory().undo();
        EditSession undo = new EditSession(world, session, "undo", false);
        undo.applyRecord(record, true);
        undo.close();
        checkEquals("undo across a flush ends on the state before the edit", air, world.getBlock(3, 72, 3));
        checkEquals("undo inside one buffer ends on the state before the edit", air, world.getBlock(4, 72, 4));

        record = session.getHistory().redo();
        EditSession redo = new EditSession(world, session, "redo", false);
        redo.applyRecord(record, false);
        redo.close();
        checkEquals("redo ends on the last write", dirt, world.getBlock(3, 72, 3));
        checkEquals("redo inside one buffer ends on the last write", dirt, world.getBlock(4, 72, 4));

        // The same through the commands a player types.
        TestActor player = new TestActor("TwiceCmd", world, new BlockVector3(8, 71, 8));
        CommandManager.get().dispatch(player, "//pos1 8,72,8");
        CommandManager.get().dispatch(player, "//pos2 9,72,9");
        CommandManager.get().dispatch(player, "//set stone");
        CommandManager.get().dispatch(player, "//set dirt");
        CommandManager.get().dispatch(player, "//undo 2");
        checkEquals("//undo 2 puts the air back", air, world.getBlock(8, 72, 8));
        CommandManager.get().dispatch(player, "//redo 2");
        checkEquals("//redo 2 puts the last pattern back", dirt, world.getBlock(9, 72, 9));
    }

    private static void snapshotRestoreOfACellWrittenTwice() {
        TestWorld world = new TestWorld("snapshot-twice");
        world.fillFlat(70);
        LocalSession session = new TestActor("SnapTwice", world, new BlockVector3(0, 71, 0)).session();
        int stone = state("minecraft:stone");
        int dirt = state("minecraft:dirt");
        int air = BlockState.registry().air();

        EditSession edit = new EditSession(world, session, "twice");
        edit.setBlock(5, 72, 5, stone);
        edit.flushQueue();
        edit.setBlock(5, 72, 5, dirt);
        edit.close();
        History.Record record = session.getHistory().getCurrent();
        NbtCompound snapshot = Snapshots.of(record, "SnapTwice");

        EditSession restore = new EditSession(world, session, "restore", false);
        Snapshots.restore(restore, snapshot);
        restore.close();
        checkEquals("a snapshot restore ends on the state before the edit", air, world.getBlock(5, 72, 5));

        History.Record imported = Snapshots.toRecord(snapshot);
        EditSession replay = new EditSession(world, session, "import", false);
        replay.applyRecord(imported, false);
        replay.close();
        checkEquals("an imported record replays to the last write", dirt, world.getBlock(5, 72, 5));
    }

    /**
     * The log hears about every finished edit once, when it finishes: not a
     * second time after an undo moved the history back, and not only once the
     * next edit starts - the last edit of a player who leaves was never logged.
     */
    private static void recordsArePublishedOnce() {
        TestWorld world = new TestWorld("published");
        world.fillFlat(70);
        LocalSession session = new LocalSession();
        List<String> published = new ArrayList<>();
        session.getHistory().setRecordListener(record -> published.add(record.description));
        int stone = state("minecraft:stone");
        int dirt = state("minecraft:dirt");

        EditSession first = new EditSession(world, session, "first");
        first.setBlock(0, 72, 0, stone);
        first.close();
        checkEquals("a finished edit is published when it closes", List.of("first"), published);

        EditSession second = new EditSession(world, session, "second");
        second.setBlock(1, 72, 0, stone);
        second.close();
        session.getHistory().undo();
        EditSession third = new EditSession(world, session, "third");
        third.setBlock(2, 72, 0, dirt);
        third.close();
        checkEquals("every edit is published exactly once", List.of("first", "second", "third"), published);

        // A caller that never closes its session still gets its record out, when
        // the next one starts, and still only once.
        EditSession open = new EditSession(world, session, "never closed");
        open.setBlock(3, 72, 0, dirt);
        open.flushQueue();
        EditSession after = new EditSession(world, session, "after");
        after.setBlock(4, 72, 0, dirt);
        after.close();
        checkEquals("an unclosed edit is published when the next one starts",
                List.of("first", "second", "third", "never closed", "after"), published);

        EditSession empty = new EditSession(world, session, "empty");
        empty.close();
        checkEquals("an edit that changed nothing is not published", 5, published.size());
    }

    /**
     * With {@code history.per-player} off every session shares one history. The
     * log has to name the player who made each edit, not whoever joined last.
     */
    private static void sharedHistoryKeepsTheEditor() {
        Config config = Config.get();
        boolean perPlayer = config.perPlayerHistory;
        config.perPlayerHistory = false;
        try {
            EditLog.clear();
            TestWorld world = new TestWorld("shared");
            world.fillFlat(70);
            LocalSession first = SessionManager.get().of(UUID.randomUUID(), "SharedFirst");
            LocalSession second = SessionManager.get().of(UUID.randomUUID(), "SharedSecond");
            check("the sessions share one history", first.getHistory() == second.getHistory());
            int stone = state("minecraft:stone");

            EditSession byFirst = new EditSession(world, first, "by first");
            byFirst.setBlock(0, 72, 0, stone);
            byFirst.close();
            EditSession bySecond = new EditSession(world, second, "by second");
            bySecond.setBlock(1, 72, 0, stone);
            bySecond.close();

            List<String> owners = new ArrayList<>();
            for (EditLog.Entry entry : EditLog.entries()) {
                owners.add(entry.record.description + "=" + entry.actor);
            }
            check("the log names the player of each shared-history edit (" + owners + ")",
                    owners.contains("by first=SharedFirst") && owners.contains("by second=SharedSecond"));
        } finally {
            config.perPlayerHistory = perPlayer;
            EditLog.clear();
        }
    }

    /** A published record is read on the writer thread; nothing may change it after that. */
    private static void sealedRecordsRefuseWrites() {
        History.Record record = new History.Record("sealed");
        record.addChange(0, 0, 0, 1, 2);
        record.seal();
        boolean refused;
        try {
            record.addChange(1, 0, 0, 1, 2);
            refused = false;
        } catch (IllegalStateException expected) {
            refused = true;
        }
        check("a sealed record refuses a change", refused);
        checkEquals("a sealed record keeps what it had", 1, record.changeCount());
    }

    /**
     * Commands run one after the other on the server thread, so a {@code /cancel}
     * never finds an edit running. It used to leave a flag behind that killed the
     * next edit the player started.
     */
    private static void cancelIsNotCarriedOver() {
        TestWorld world = new TestWorld("cancel");
        world.fillFlat(70);
        TestActor player = new TestActor("Canceller", world, new BlockVector3(0, 71, 0));
        CommandManager.get().dispatch(player, "//pos1 0,67,0");
        CommandManager.get().dispatch(player, "//pos2 3,69,3");
        CommandManager.get().dispatch(player, "//cancel");
        check("the session is not left cancelled", !player.session().isCancelled());
        // //air checks the cancel flag on every block it writes.
        CommandManager.get().dispatch(player, "//air");
        checkEquals("a /cancel with nothing running does not stop the next edit",
                BlockState.registry().air(), world.getBlock(1, 68, 1));
    }

    /**
     * {@code queue.target-size} is how much the queue holds before it writes:
     * once an edit had written that many blocks in total, every block count check
     * after it flushed the whole queue, and a chunk column went to the world a
     * piece at a time - relit and re-sent each time.
     */
    private static void queueFlushesWhatItHolds() {
        Config config = Config.get();
        int target = config.queueTargetSize;
        int wait = config.queueMaxWait;
        config.queueTargetSize = 20_000;
        config.queueMaxWait = 0;
        try {
            TestWorld world = new TestWorld("queue");
            LocalSession session = new LocalSession();
            int stone = state("minecraft:stone");
            EditSession edit = new EditSession(world, session, "column", false);
            for (int y = 0; y < 256; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        edit.setBlock(x, y, z, stone);
                    }
                }
            }
            edit.close();
            checkEquals("a 65,536 block column is written in queue-sized batches", 4,
                    world.applyCount(0, 0));
        } finally {
            config.queueTargetSize = target;
            config.queueMaxWait = wait;
        }
    }

    /** A traced edit keeps what it prints, not a line per block of the edit. */
    private static void traceStaysBounded() {
        TestWorld world = new TestWorld("trace");
        LocalSession session = new LocalSession();
        EditSession edit = new EditSession(world, session, "traced", false);
        edit.setTracing(true);
        int stone = state("minecraft:stone");
        for (int i = 0; i < 10_000; i++) {
            edit.setBlock(i & 15, 64 + (i >> 8), (i >> 4) & 15, stone);
        }
        edit.close();
        check("the trace keeps a bounded number of lines (" + edit.getTraceLog().size() + ")",
                edit.getTraceLog().size() <= 64);
        TestActor reader = new TestActor("Tracer", world, new BlockVector3(0, 64, 0));
        edit.reportTrace(reader);
        check("the trace report counts what it did not keep",
                reader.lastMessage() != null && reader.lastMessage().contains("more"));
    }
}
