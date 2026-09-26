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
