package com.maxlananas.fawebim.core.test;

import com.maxlananas.fawebim.core.brush.Brushes;
import com.maxlananas.fawebim.core.command.CommandManager;
import com.maxlananas.fawebim.core.extent.EditSession;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.math.Vector3;
import com.maxlananas.fawebim.core.util.NbtCompound;
import com.maxlananas.fawebim.core.world.EntityData;

import static com.maxlananas.fawebim.core.test.SelfTestMain.check;
import static com.maxlananas.fawebim.core.test.SelfTestMain.checkEquals;
import static com.maxlananas.fawebim.core.test.SelfTestMain.section;

/**
 * {@code //distr -p} pages the distribution already counted, and
 * {@code //butcher}, {@code /remove} and the butcher brush reach as far as
 * FAWE's cylinder.
 *
 * <p>Every page of {@code //distr} counted the selection again, and
 * {@code -p} with nothing counted before was a new count rather than FAWE's
 * "No previous distribution". {@code //butcher} took a square, killing in its
 * corners half as far again as the radius; it read {@code -1} as no radius,
 * said nothing below it and refused a radius over the maximum instead of
 * keeping to it. The brush took a cube, and missed a mob above it.</p>
 */
final class DistributionAndButcherTests {

    private DistributionAndButcherTests() {
    }

    static void run() {
        section("distribution and butcher");
        distributionPagesTheLastCount();
        butcherTakesACylinder();
        butcherReadsFawesRadius();
        butcherBrushTakesACylinder();
    }

    private static void distributionPagesTheLastCount() {
        TestWorld world = new TestWorld("DistrPages");
        TestActor actor = new TestActor("DistrPages", world, new BlockVector3(0, 70, 0));
        String none = answer(actor, "//distr -p 1");
        check("//distr -p with nothing counted says so (" + none + ")", none.contains("No previous distribution"));
        answer(actor, "//pos1 0,64,0");
        answer(actor, "//pos2 3,65,3");
        answer(actor, "//set stone");
        String counted = answer(actor, "//distr");
        check("//distr counts the selection (" + counted + ")", counted.contains("minecraft:stone: 32 (100.00%)"));
        answer(actor, "//set dirt");
        String paged = answer(actor, "//distr -p 1");
        check("-p pages that count without counting again (" + paged + ")",
                paged.contains("minecraft:stone: 32") && !paged.contains("dirt"));
        check("a new //distr counts again", answer(actor, "//distr").contains("minecraft:dirt: 32"));
    }

    private static void butcherTakesACylinder() {
        TestWorld world = new TestWorld("ButcherCylinder");
        TestActor actor = new TestActor("ButcherCylinder", world, new BlockVector3(0, 70, 0));
        EntityData edge = mob(world, "minecraft:zombie", 0.5, 70, 20.5);
        EntityData corner = mob(world, "minecraft:zombie", 15.5, 70, 15.5);
        EntityData above = mob(world, "minecraft:zombie", 0.5, 250, 0.5);
        EntityData pig = mob(world, "minecraft:pig", 1.5, 70, 1.5);
        String answer = answer(actor, "//butcher");
        check("//butcher kills within the default radius of 20 (" + answer + ")",
                answer.contains("Killed: 2 entities within 20 blocks"));
        check("up to the radius across the ground", !world.getEntities().contains(edge));
        check("at any height", !world.getEntities().contains(above));
        check("but not in the corners of a square around it", world.getEntities().contains(corner));
        check("and only the hostile mobs without a flag", world.getEntities().contains(pig));
    }

    private static void butcherReadsFawesRadius() {
        TestWorld world = new TestWorld("ButcherRadius");
        TestActor actor = new TestActor("ButcherRadius", world, new BlockVector3(0, 70, 0));
        EntityData near = mob(world, "minecraft:zombie", 50.5, 70, 0.5);
        EntityData far = mob(world, "minecraft:zombie", 150.5, 70, 0.5);
        String below = answer(actor, "//butcher -2");
        check("a radius below -1 explains -1 (" + below + ")",
                below.contains("Use -1 to remove all mobs in loaded chunks"));
        String over = answer(actor, "//butcher 500");
        check("a radius over the maximum keeps to the maximum (" + over + ")",
                over.contains("Killed: 1 entity within 100 blocks"));
        check("which reaches the near mob", !world.getEntities().contains(near));
        check("and not the far one", world.getEntities().contains(far));
        String all = answer(actor, "//butcher -1");
        check("-1 is the maximum when there is one (" + all + ")", all.contains("within 100 blocks"));
        com.maxlananas.fawebim.core.platform.Config config = com.maxlananas.fawebim.core.platform.Config.get();
        int maximum = config.butcherMaxRadius;
        config.butcherMaxRadius = -1;
        try {
            String loaded = answer(actor, "//butcher -1");
            check("and every loaded chunk without one (" + loaded + ")",
                    loaded.contains("Killed: 1 entity in the loaded chunks"));
            check("however far the mob is", !world.getEntities().contains(far));
        } finally {
            config.butcherMaxRadius = maximum;
        }
    }

    private static void butcherBrushTakesACylinder() {
        TestWorld world = new TestWorld("ButcherBrush");
        TestActor actor = new TestActor("ButcherBrush", world, new BlockVector3(0, 70, 0));
        EntityData above = mob(world, "minecraft:zombie", 100.5, 110, 100.5);
        EntityData corner = mob(world, "minecraft:zombie", 104.5, 70, 104.5);
        EntityData side = mob(world, "minecraft:zombie", 105.5, 70, 100.5);
        EditSession session = new EditSession(world, actor.session(), "brush");
        int killed = new Brushes.ButcherBrush(5).apply(session, new BlockVector3(100, 70, 100), actor);
        session.flushQueue();
        checkEquals("the butcher brush kills within its radius", 2, killed);
        check("above the block it hit", !world.getEntities().contains(above));
        check("out to its radius", !world.getEntities().contains(side));
        check("and not in the corners of a cube", world.getEntities().contains(corner));
    }

    private static EntityData mob(TestWorld world, String type, double x, double y, double z) {
        EntityData entity = new EntityData(type, new NbtCompound(), new Vector3(x, y, z));
        world.addEntity(entity);
        return entity;
    }

    private static String answer(TestActor actor, String line) {
        actor.clearMessages();
        CommandManager.get().dispatch(actor, line);
        return String.join("\n", actor.messages()).replaceAll("\u00a7.", "");
    }
}
