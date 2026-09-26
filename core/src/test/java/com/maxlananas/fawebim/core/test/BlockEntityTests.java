package com.maxlananas.fawebim.core.test;

import com.maxlananas.fawebim.core.command.CommandManager;
import com.maxlananas.fawebim.core.extent.EditSession;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.util.NbtCompound;
import com.maxlananas.fawebim.core.world.BlockState;

import java.util.List;

import static com.maxlananas.fawebim.core.test.SelfTestMain.check;
import static com.maxlananas.fawebim.core.test.SelfTestMain.checkEquals;
import static com.maxlananas.fawebim.core.test.SelfTestMain.section;

/**
 * A chest's items, a sign's text: the data of a block travels with it through
 * a copy and a paste, goes with it when an edit replaces it, and comes back
 * with an undo.
 *
 * <p>The test world keeps block entities the way the game does for a block it
 * sets, which is what the Fabric world now does for the blocks a flush writes.</p>
 */
final class BlockEntityTests {

    private BlockEntityTests() {
    }

    static void run() {
        section("block entities");
        copyAndPasteCarryTheData();
        replacingABlockTakesItsDataAndUndoBringsItBack();
        pastingOverAChestReplacesItsItemsUndoably();
        cutKeepsTheDataInTheClipboard();
        masksKeepTheDataOutAsTheyKeepTheBlocks();
        aCopyAsksTheChunksNotEveryBlock();
        snapshotsNameTheirStatesAndKeepTheData();
    }

    private static int chest() {
        return BlockState.registry().defaultState("minecraft:chest");
    }

    private static NbtCompound items(String name, int count) {
        NbtCompound item = new NbtCompound().putByte("Slot", 0).putString("id", name).putInt("count", count);
        return new NbtCompound().putString("id", "minecraft:chest").putList("Items", List.of(item));
    }

    private static String itemOf(NbtCompound chest) {
        if (chest == null || chest.getCompoundList("Items").isEmpty()) {
            return "empty";
        }
        NbtCompound item = chest.getCompoundList("Items").get(0);
        return item.getString("id", "?") + " x" + item.getInt("count", 0);
    }

    private static TestActor actor(String name) {
        TestWorld world = new TestWorld(name);
        world.fillFlat(70);
        return new TestActor(name, world, new BlockVector3(0, 71, 0));
    }

    private static void run(TestActor actor, String line) {
        CommandManager.get().dispatch(actor, line);
    }

    /** Puts a chest holding an item into the world through an edit, as a paste would. */
    private static void placeChest(TestActor actor, BlockVector3 at, String item, int count) {
        EditSession edit = new EditSession(actor.world(), actor.session(), "place chest", false);
        try {
            edit.setBlock(at.x(), at.y(), at.z(), chest());
            edit.setBlockEntity(at.x(), at.y(), at.z(), items(item, count));
        } finally {
            edit.close();
        }
    }

    /** {@code //copy} kept block entities only with {@code -e}; WorldEdit and FAWE always copy them. */
    private static void copyAndPasteCarryTheData() {
        TestActor actor = actor("BlockEntityCopy");
        TestWorld world = (TestWorld) actor.world();
        placeChest(actor, new BlockVector3(2, 70, 2), "minecraft:diamond", 5);
        run(actor, "//pos1 0,70,0");
        run(actor, "//pos2 4,72,4");
        run(actor, "//copy");
        checkEquals("//copy without -e keeps the chest's items", "minecraft:diamond x5",
                itemOf(actor.session().getClipboard().getClipboard().getBlockEntity(new BlockVector3(2, 70, 2))));
        run(actor, "//paste 20,70,20");
        checkEquals("//paste puts the chest down with its items", "minecraft:diamond x5",
                itemOf(world.getBlockEntity(22, 70, 22)));
        check("and the chest itself", world.getBlock(22, 70, 22) == chest());
        run(actor, "//lazycopy");
        run(actor, "//paste 40,70,40");
        checkEquals("a lazy copy reads the chest's items when it is pasted", "minecraft:diamond x5",
                itemOf(world.getBlockEntity(42, 70, 42)));
    }

    private static void replacingABlockTakesItsDataAndUndoBringsItBack() {
        TestActor actor = actor("BlockEntityUndo");
        TestWorld world = (TestWorld) actor.world();
        placeChest(actor, new BlockVector3(1, 70, 1), "minecraft:emerald", 7);
        run(actor, "//pos1 0,70,0");
        run(actor, "//pos2 3,71,3");
        run(actor, "//set minecraft:stone");
        check("replacing the chest takes its block entity away", world.getBlockEntity(1, 70, 1) == null);
        run(actor, "//undo");
        checkEquals("//undo brings the chest back with its items", "minecraft:emerald x7",
                itemOf(world.getBlockEntity(1, 70, 1)));
        run(actor, "//redo");
        check("//redo takes it away again", world.getBlockEntity(1, 70, 1) == null
                && world.getBlock(1, 70, 1) == BlockState.registry().defaultState("minecraft:stone"));
    }

    private static void pastingOverAChestReplacesItsItemsUndoably() {
        TestActor actor = actor("BlockEntityOver");
        TestWorld world = (TestWorld) actor.world();
        placeChest(actor, new BlockVector3(0, 70, 0), "minecraft:gold_ingot", 3);
        placeChest(actor, new BlockVector3(10, 70, 10), "minecraft:iron_ingot", 9);
        run(actor, "//pos1 0,70,0");
        run(actor, "//pos2 0,70,0");
        run(actor, "//copy");
        run(actor, "//paste 10,70,10");
        checkEquals("a chest pasted over the same chest brings its items", "minecraft:gold_ingot x3",
                itemOf(world.getBlockEntity(10, 70, 10)));
        run(actor, "//undo");
        checkEquals("//undo puts back the items the chest had", "minecraft:iron_ingot x9",
                itemOf(world.getBlockEntity(10, 70, 10)));
    }

    private static void cutKeepsTheDataInTheClipboard() {
        TestActor actor = actor("BlockEntityCut");
        TestWorld world = (TestWorld) actor.world();
        placeChest(actor, new BlockVector3(3, 70, 3), "minecraft:apple", 2);
        run(actor, "//pos1 2,70,2");
        run(actor, "//pos2 4,71,4");
        run(actor, "//cut");
        check("//cut clears the chest and its block entity", world.getBlockEntity(3, 70, 3) == null
                && world.getBlock(3, 70, 3) == BlockState.registry().air());
        checkEquals("the clipboard has its items", "minecraft:apple x2",
                itemOf(actor.session().getClipboard().getClipboard().getBlockEntity(new BlockVector3(3, 70, 3))));
        run(actor, "//undo");
        checkEquals("//undo of the cut brings them back", "minecraft:apple x2", itemOf(world.getBlockEntity(3, 70, 3)));
    }

    private static void masksKeepTheDataOutAsTheyKeepTheBlocks() {
        TestActor actor = actor("BlockEntityMask");
        TestWorld world = (TestWorld) actor.world();
        placeChest(actor, new BlockVector3(0, 70, 0), "minecraft:bread", 4);
        placeChest(actor, new BlockVector3(30, 70, 30), "minecraft:coal", 1);
        run(actor, "//pos1 0,70,0");
        run(actor, "//pos2 0,70,0");
        run(actor, "//copy -m minecraft:stone");
        check("a copy whose mask leaves the chest out leaves its data out",
                actor.session().getClipboard().getClipboard().blockEntities().isEmpty());
        run(actor, "//copy");
        run(actor, "//gmask minecraft:stone");
        run(actor, "//paste 30,70,30");
        run(actor, "//gmask");
        checkEquals("a global mask that refuses the block refuses its data", "minecraft:coal x1",
                itemOf(world.getBlockEntity(30, 70, 30)));
    }

    /**
     * A snapshot outlives the server that wrote it, and the numbers of block
     * states do not: they change with the game and with its mods. A snapshot
     * names its states and biomes, and keeps the data of the block entities
     * its edit replaced.
     */
    private static void snapshotsNameTheirStatesAndKeepTheData() {
        TestActor actor = actor("SnapshotPalette");
        TestWorld world = (TestWorld) actor.world();
        placeChest(actor, new BlockVector3(4, 70, 4), "minecraft:flint", 6);
        run(actor, "//pos1 4,70,4");
        run(actor, "//pos2 4,70,4");
        run(actor, "//set minecraft:stone");
        var record = actor.session().getHistory().getCurrent();
        NbtCompound snapshot = com.maxlananas.fawebim.core.history.Snapshots.of(record, "SnapshotPalette");
        List<Object> palette = snapshot.get("palette") instanceof List<?> list ? List.copyOf(list) : List.of();
        check("a snapshot names the states it holds (" + palette + ")",
                palette.contains("minecraft:chest") && palette.contains("minecraft:stone"));
        NbtCompound section = snapshot.getCompoundList("sections").get(0);
        check("its rows hold palette indices", section.getIntArray("b")[0] < palette.size());

        EditSession restore = new EditSession(world, actor.session(), "restore", false);
        try {
            com.maxlananas.fawebim.core.history.Snapshots.restore(restore, snapshot);
        } finally {
            restore.close();
        }
        checkEquals("restoring the snapshot brings the chest back with its items", "minecraft:flint x6",
                itemOf(world.getBlockEntity(4, 70, 4)));

        // The same rows under another numbering: the palette decides what comes back.
        int renumbered = palette.indexOf("minecraft:chest") == 0 ? 1 : 0;
        List<Object> swapped = new java.util.ArrayList<>(palette);
        java.util.Collections.swap(swapped, 0, 1);
        NbtCompound moved = snapshot.clone();
        moved.putList("palette", swapped);
        moved.getCompoundList("sections").get(0).putIntArray("b", new int[]{renumbered});
        EditSession again = new EditSession(world, actor.session(), "restore", false);
        try {
            com.maxlananas.fawebim.core.history.Snapshots.restore(again, moved);
        } finally {
            again.close();
        }
        check("a snapshot restores the state its palette names, whatever the number",
                world.getBlock(4, 70, 4) == chest());

        NbtCompound legacy = new NbtCompound().putList("sections", List.of(new NbtCompound().putInt("x", 0)
                .putInt("z", 0).putInt("y", 70 >> 4).putIntArray("i", new int[]{(70 & 15) << 8})
                .putIntArray("b", new int[]{BlockState.registry().defaultState("minecraft:dirt")})));
        EditSession old = new EditSession(world, actor.session(), "restore", false);
        try {
            com.maxlananas.fawebim.core.history.Snapshots.restore(old, legacy);
        } finally {
            old.close();
        }
        check("a snapshot written before the palette still restores this server's numbers",
                world.getBlock(0, 70, 0) == BlockState.registry().defaultState("minecraft:dirt"));
    }

    /**
     * A copy asked the world about a block entity at every position of the
     * selection; it asks the chunks for the ones they hold.
     */
    private static void aCopyAsksTheChunksNotEveryBlock() {
        TestActor actor = actor("BlockEntityReads");
        TestWorld world = (TestWorld) actor.world();
        placeChest(actor, new BlockVector3(5, 70, 5), "minecraft:stick", 1);
        run(actor, "//pos1 0,60,0");
        run(actor, "//pos2 31,75,31");
        int before = world.blockEntityReads();
        run(actor, "//copy");
        checkEquals("a copy of 16384 blocks reads the one block entity there is", 1,
                world.blockEntityReads() - before);
    }
}
