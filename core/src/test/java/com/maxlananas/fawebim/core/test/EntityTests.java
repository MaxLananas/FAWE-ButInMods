package com.maxlananas.fawebim.core.test;

import com.maxlananas.fawebim.core.command.CommandManager;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.math.Vector3;
import com.maxlananas.fawebim.core.transform.Axis;
import com.maxlananas.fawebim.core.transform.EntityTransforms;
import com.maxlananas.fawebim.core.transform.Transforms;
import com.maxlananas.fawebim.core.util.NbtCompound;
import com.maxlananas.fawebim.core.world.EntityData;
import com.maxlananas.fawebim.core.world.Extent;

import java.util.List;

import static com.maxlananas.fawebim.core.test.SelfTestMain.check;
import static com.maxlananas.fawebim.core.test.SelfTestMain.checkEquals;
import static com.maxlananas.fawebim.core.test.SelfTestMain.section;

/**
 * Entities copied with {@code -e} arrive as new entities with their data, a
 * paste of them is undone, and what {@code /butcher}, {@code /remove} and
 * {@code //cut -e} take away comes back with {@code //undo}.
 */
final class EntityTests {

    private EntityTests() {
    }

    static void run() {
        section("entities");
        copiedEntitiesArriveAsNewOnes();
        removalsAreUndone();
        removeAllLeavesTheMobs();
        cutTakesTheEntitiesAlong();
        turnedPastesTurnTheEntities();
        itemFramesTurnWhatTheyHold();
        turnedPastesKeepEntitiesOnTheirBlocks();
        hangingEntitiesMoveWithTheirWall();
        passengersTravelInTheirVehicle();
    }

    private static TestActor actor(String name) {
        TestWorld world = new TestWorld(name);
        world.fillFlat(70);
        return new TestActor(name, world, new BlockVector3(0, 71, 0));
    }

    private static void run(TestActor actor, String line) {
        CommandManager.get().dispatch(actor, line);
    }

    private static EntityData pig(double x, double y, double z, String name) {
        return new EntityData("minecraft:pig", new NbtCompound().putString("CustomName", name)
                .putList("Rotation", List.of(0.0f, 0.0f)), new Vector3(x, y, z));
    }

    private static List<EntityData> near(TestWorld world, int x, int y, int z) {
        return world.getEntities(new Extent.Region3i(x - 3, y - 3, z - 3, x + 3, y + 3, z + 3));
    }

    private static void copiedEntitiesArriveAsNewOnes() {
        TestActor actor = actor("EntityCopy");
        TestWorld world = (TestWorld) actor.world();
        EntityData original = pig(2.5, 71, 2.5, "Wilbur");
        world.addEntity(original);
        run(actor, "//pos1 0,70,0");
        run(actor, "//pos2 4,72,4");
        run(actor, "//copy -e");
        run(actor, "//paste -e 20,70,20");
        List<EntityData> pasted = near(world, 22, 71, 22);
        check("//paste -e puts the pig down at the paste, with its data", pasted.size() == 1
                && "Wilbur".equals(pasted.get(0).nbt().getString("CustomName", null)));
        check("as a new entity: the one copied is still there, under its own identity",
                near(world, 2, 71, 2).size() == 1 && !pasted.isEmpty()
                        && !pasted.get(0).uuid().equals(original.uuid()));
        run(actor, "//undo");
        check("//undo of the paste takes the pasted pig away", near(world, 22, 71, 22).isEmpty()
                && near(world, 2, 71, 2).size() == 1);
        run(actor, "//redo");
        checkEquals("//redo brings it back", 1, near(world, 22, 71, 22).size());
    }

    /** WorldEdit's "all" is every kind /remove names, never a mob: /butcher is for those. */
    private static void removeAllLeavesTheMobs() {
        TestActor actor = actor("EntityRemoveAll");
        TestWorld world = (TestWorld) actor.world();
        world.addEntity(pig(1.5, 71, 1.5, "Pet"));
        world.addEntity(new EntityData("minecraft:item", new NbtCompound(), new Vector3(2.5, 71, 2.5)));
        run(actor, "/remove all 5");
        List<EntityData> left = near(world, 2, 71, 2);
        check("/remove all takes the item and leaves the pig (" + left + ")", left.size() == 1
                && left.get(0).type().equals("minecraft:pig"));
    }

    private static void removalsAreUndone() {
        TestActor actor = actor("EntityRemoval");
        TestWorld world = (TestWorld) actor.world();
        EntityData arrow = new EntityData("minecraft:arrow", new NbtCompound().putByte("pickup", 1),
                new Vector3(1.5, 71, 1.5));
        world.addEntity(arrow);
        String identity = arrow.uuid();
        run(actor, "/remove arrows 5");
        check("/remove takes the arrow away", near(world, 1, 71, 1).isEmpty());
        run(actor, "//undo");
        List<EntityData> back = near(world, 1, 71, 1);
        check("//undo brings it back with its data and its identity", back.size() == 1
                && back.get(0).nbt().getByte("pickup", 0) == 1 && identity.equals(back.get(0).uuid()));
        run(actor, "//redo");
        check("//redo takes it away again", near(world, 1, 71, 1).isEmpty());
    }

    private static void cutTakesTheEntitiesAlong() {
        TestActor actor = actor("EntityCut");
        TestWorld world = (TestWorld) actor.world();
        world.addEntity(pig(1.5, 71, 1.5, "Babe"));
        run(actor, "//pos1 0,70,0");
        run(actor, "//pos2 3,72,3");
        run(actor, "//cut -e");
        check("//cut -e takes the pig out of the world", near(world, 1, 71, 1).isEmpty());
        checkEquals("and into the clipboard", 1, actor.session().getClipboard().getClipboard().entities().size());
        run(actor, "//undo");
        checkEquals("//undo of the cut puts it back", 1, near(world, 1, 71, 1).size());
    }

    private static void turnedPastesTurnTheEntities() {
        NbtCompound looking = new NbtCompound().putList("Rotation", List.of(0.0f, 10.0f)).putByte("Facing", 2);
        NbtCompound turned = EntityTransforms.turn(looking, Transforms.rotate(BlockVector3.ZERO, Axis.Y, -90));
        List<?> rotation = (List<?>) turned.get("Rotation");
        check("a mob looking south looks west after a clockwise quarter turn (" + rotation + ")",
                Math.abs((Float) rotation.get(0) - 90f) < 1e-3 && (Float) rotation.get(1) == 10f);
        checkEquals("an item frame on a north wall hangs on an east one", (byte) 5, turned.get("Facing"));
        check("the data the paste started from is left as it was", looking.get("Facing").equals((byte) 2));
    }

    /**
     * The item in a frame is tilted in eighths of a turn, a map in quarters,
     * counted clockwise from the top of the frame as the player facing it sees
     * it: up on a wall, north on a floor, south on a ceiling.
     */
    private static void itemFramesTurnWhatTheyHold() {
        var quarter = Transforms.rotate(BlockVector3.ZERO, Axis.Y, 90);
        var mirror = Transforms.flip(BlockVector3.ZERO, Axis.X);
        checkEquals("a turn keeps the tilt of the item in a frame on a wall", (byte) 3,
                EntityTransforms.turn(frame(2, 3, "minecraft:clock"), quarter).get("ItemRotation"));
        checkEquals("a mirror mirrors it", (byte) 7,
                EntityTransforms.turn(frame(2, 1, "minecraft:clock"), mirror).get("ItemRotation"));
        checkEquals("in quarter turns for a map", (byte) 3,
                EntityTransforms.turn(frame(2, 1, "minecraft:filled_map"), mirror).get("ItemRotation"));
        byte floor = (Byte) EntityTransforms.turn(frame(1, 0, "minecraft:clock"), quarter).get("ItemRotation");
        byte ceiling = (Byte) EntityTransforms.turn(frame(0, 0, "minecraft:clock"), quarter).get("ItemRotation");
        check("a quarter turn tilts an item on a floor a quarter one way (" + floor + ")", floor == 2 || floor == 6);
        checkEquals("and one on a ceiling, seen from below, a quarter the other way", (8 - floor) % 8, (int) ceiling);
        checkEquals("a mirror mirrors the tilt on a ceiling too", (byte) 7,
                EntityTransforms.turn(frame(0, 1, "minecraft:clock"), mirror).get("ItemRotation"));
        checkEquals("an item turned upside down with its frame keeps pointing the same way", (byte) 4,
                EntityTransforms.turn(frame(1, 0, "minecraft:clock"), Transforms.flip(BlockVector3.ZERO, Axis.Y))
                        .get("ItemRotation"));
    }

    private static NbtCompound frame(int facing, int rotation, String item) {
        return new NbtCompound().putByte("Facing", facing).putByte("ItemRotation", rotation)
                .put("Item", new NbtCompound().putString("id", item).putInt("count", 1));
    }

    /**
     * Blocks turn as points around the origin, so an entity turned around the
     * same corner landed a block away from the block it stood on.
     */
    private static void turnedPastesKeepEntitiesOnTheirBlocks() {
        TestActor actor = actor("EntityTurn");
        TestWorld world = (TestWorld) actor.world();
        int gold = com.maxlananas.fawebim.core.world.BlockState.registry().defaultState("minecraft:gold_block");
        world.setBlock(1, 69, 1, gold);
        world.addEntity(pig(1.5, 70, 1.5, "Turn"));
        run(actor, "//pos1 0,69,0");
        run(actor, "//pos2 3,72,3");
        actor.setPosition(new BlockVector3(0, 69, 0));
        run(actor, "//copy -e");
        boolean onTheirBlock = true;
        StringBuilder seen = new StringBuilder();
        for (int turn = 1; turn <= 3; turn++) {
            run(actor, "//rotate 90");
            int base = 40 * turn;
            run(actor, "//paste -e " + base + ",69," + base);
            int goldX = Integer.MIN_VALUE;
            int goldZ = Integer.MIN_VALUE;
            for (int x = base - 6; x <= base + 6; x++) {
                for (int z = base - 6; z <= base + 6; z++) {
                    if (world.getBlock(x, 69, z) == gold) {
                        goldX = x;
                        goldZ = z;
                    }
                }
            }
            List<EntityData> pasted = world.getEntities(new Extent.Region3i(base - 8, 60, base - 8, base + 8, 80,
                    base + 8));
            seen.append(" gold ").append(goldX).append(',').append(goldZ).append(" pig ")
                    .append(pasted.isEmpty() ? "none" : pasted.get(0).position());
            onTheirBlock &= pasted.size() == 1 && Math.floor(pasted.get(0).position().x()) == goldX
                    && Math.floor(pasted.get(0).position().z()) == goldZ;
        }
        check("a pig pasted a quarter, a half and three quarters round still stands on its block" + seen,
                onTheirBlock);
    }

    /**
     * The game places a painting or an item frame by the block it hangs from,
     * which a paste has to move with the blocks; older data names that block
     * with TileX, TileY and TileZ.
     */
    private static void hangingEntitiesMoveWithTheirWall() {
        NbtCompound frame = new NbtCompound().putIntArray("block_pos", new int[]{1, 71, 0}).putByte("Facing", 3);
        NbtCompound moved = com.maxlananas.fawebim.core.clipboard.Clipboards.attachedTo(frame, null,
                new BlockVector3(0, 70, 0), new BlockVector3(20, 70, 20));
        check("a paste moves the block an item frame hangs from",
                java.util.Arrays.equals(new int[]{21, 71, 20}, moved.getIntArray("block_pos")));
        check("without touching the clipboard's copy", java.util.Arrays.equals(new int[]{1, 71, 0},
                frame.getIntArray("block_pos")));
        NbtCompound turned = com.maxlananas.fawebim.core.clipboard.Clipboards.attachedTo(frame,
                Transforms.rotate(BlockVector3.ZERO, Axis.Y, 90), BlockVector3.ZERO, BlockVector3.ZERO);
        BlockVector3 expected = Transforms.rotate(BlockVector3.ZERO, Axis.Y, 90).apply(new Vector3(1, 71, 0))
                .toBlockPoint();
        check("and turns it as it turns the blocks", java.util.Arrays.equals(
                new int[]{expected.x(), expected.y(), expected.z()}, turned.getIntArray("block_pos")));
        NbtCompound legacy = new NbtCompound().putInt("TileX", 5).putInt("TileY", 64).putInt("TileZ", -3);
        NbtCompound upgraded = com.maxlananas.fawebim.core.clipboard.Clipboards.attachedTo(legacy, null,
                BlockVector3.ZERO, new BlockVector3(1, 0, 0));
        check("TileX, TileY and TileZ become the block_pos the game reads", java.util.Arrays.equals(
                new int[]{6, 64, -3}, upgraded.getIntArray("block_pos")) && !upgraded.contains("TileX"));
        NbtCompound pig = new NbtCompound().putString("CustomName", "Babe");
        check("an entity that hangs from nothing keeps its data", com.maxlananas.fawebim.core.clipboard
                .Clipboards.attachedTo(pig, null, BlockVector3.ZERO, BlockVector3.ONE) == pig);
        NbtCompound tied = new NbtCompound().putIntArray("leash", new int[]{2, 71, 1});
        NbtCompound retied = com.maxlananas.fawebim.core.clipboard.Clipboards.attachedTo(tied, null,
                new BlockVector3(0, 70, 0), new BlockVector3(20, 70, 20));
        check("an animal tied to a fence is tied to the pasted fence", java.util.Arrays.equals(
                new int[]{22, 71, 21}, retied.getIntArray("leash")));
        NbtCompound held = new NbtCompound().put("leash", new NbtCompound().putIntArray("UUID", new int[]{1, 2, 3, 4}));
        check("one held by another entity keeps its data", com.maxlananas.fawebim.core.clipboard
                .Clipboards.attachedTo(held, null, BlockVector3.ZERO, BlockVector3.ONE) == held);
    }

    /** A passenger is saved in its vehicle's data, so a copy of both takes the vehicle. */
    private static void passengersTravelInTheirVehicle() {
        TestActor actor = actor("EntityPassenger");
        TestWorld world = (TestWorld) actor.world();
        EntityData rider = new EntityData("minecraft:zombie", new NbtCompound(), new Vector3(1.5, 71, 1.5));
        rider.setPassenger(true);
        world.addEntity(rider);
        EntityData vehicle = pig(1.5, 71, 1.5, "Ride");
        vehicle.nbt().putList("Passengers", List.of(new NbtCompound().putString("id", "minecraft:zombie")));
        world.addEntity(vehicle);
        run(actor, "//pos1 0,70,0");
        run(actor, "//pos2 3,72,3");
        run(actor, "//copy -e");
        checkEquals("a copy keeps the vehicle and leaves the passenger to its data", 1,
                actor.session().getClipboard().getClipboard().entities().size());
        run(actor, "/butcher -a -t 5");
        run(actor, "//undo");
        List<EntityData> back = near(world, 1, 71, 1);
        check("an undo of both puts each back once, the vehicle without its passenger in its data (" + back + ")",
                back.size() == 2 && back.stream().noneMatch(entity -> entity.nbt().contains("Passengers")));
    }
}
