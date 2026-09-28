package com.maxlananas.fawebim.core.test;

import com.maxlananas.fawebim.core.brush.Brush;
import com.maxlananas.fawebim.core.brush.BrushFactory;
import com.maxlananas.fawebim.core.brush.Brushes;
import com.maxlananas.fawebim.core.command.CommandManager;
import com.maxlananas.fawebim.core.extent.EditSession;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.region.Region;
import com.maxlananas.fawebim.core.world.BlockState;

import static com.maxlananas.fawebim.core.test.SelfTestMain.check;
import static com.maxlananas.fawebim.core.test.SelfTestMain.checkEquals;
import static com.maxlananas.fawebim.core.test.SelfTestMain.section;

/**
 * The command and scatter brushes work as FAWE's.
 *
 * <p>The command brush ran its command with the player's own selection and
 * where the player stood, knew only %x% for its placeholders, ran one command
 * and announced it on every click. The scatter command brush ran as many
 * commands as its radius at random points of a cube, whatever its points and
 * distance. The scatter brush put its pattern on top of random columns of a
 * disc whatever -o said, where FAWE's replaces surface blocks, and with -o
 * lays it on them, its points kept apart by the distance.</p>
 */
final class BrushCommandTests {

    private BrushCommandTests() {
    }

    static void run() {
        section("command and scatter brushes");
        theCommandBrushRunsAtTheClick();
        theScatterBrushesPickSurfacePointsApart();
        theSplatterBrushPaintsTheSurface();
        theLineBrushesJoinTwoClicks();
    }

    private static TestActor actor(String name) {
        TestWorld world = new TestWorld(name);
        world.fillFlat(64);
        TestActor actor = new TestActor(name, world, new BlockVector3(0, 70, 0));
        actor.session().setMaxBlocksChanged(1_000_000);
        return actor;
    }

    private static String answer(TestActor actor, String line) {
        actor.clearMessages();
        CommandManager.get().dispatch(actor, line);
        return String.join("\n", actor.messages()).replaceAll("\u00a7.", "");
    }

    private static int stroke(TestActor actor, int x, int y, int z) {
        Brush brush = BrushFactory.current(actor);
        EditSession session = new EditSession(actor.world(), actor.session(), "brush");
        try {
            return Brushes.apply(brush, session, new BlockVector3(x, y, z), actor);
        } finally {
            session.close();
        }
    }

    private static int state(String name) {
        return BlockState.registry().defaultState(name);
    }

    private static void theCommandBrushRunsAtTheClick() {
        TestActor actor = actor("CommandBrush");
        TestWorld world = (TestWorld) actor.world();
        answer(actor, "//pos1 -20,60,-20");
        answer(actor, "//pos2 -18,60,-18");
        answer(actor, "/brush cmd 1 //set gold_block");
        actor.clearMessages();
        stroke(actor, 10, 70, 10);
        checkEquals("//set fills the cube of the radius around the click", state("minecraft:gold_block"),
                world.getBlock(11, 71, 9));
        check("and nothing of the player's own selection", world.getBlock(-19, 60, -19) != state("minecraft:gold_block"));
        Region selection = actor.session().getSelection(world);
        check("the selection is that cube afterwards, as FAWE leaves it", selection != null
                && selection.getMinimumPoint().equals(new BlockVector3(9, 69, 9))
                && selection.getMaximumPoint().equals(new BlockVector3(11, 71, 11)));
        check("the brush does not announce what it runs",
                actor.messages().stream().noneMatch(message -> message.contains("Brush command")));
        answer(actor, "/brush cmd 0 //sphere diamond_block 1");
        stroke(actor, 30, 80, 30);
        checkEquals("a command that builds where the player is builds at the click",
                state("minecraft:diamond_block"), world.getBlock(31, 80, 30));
        answer(actor, "/brush cmd 0 //pos1 {x},{y},{z};//pos2 {x},{y},{z};//set lapis_block -h");
        actor.clearMessages();
        stroke(actor, 40, 75, 40);
        checkEquals("{x} {y} {z} are the click, and ; separates commands", state("minecraft:lapis_block"),
                world.getBlock(40, 75, 40));
        check("-h keeps them quiet", actor.messages().isEmpty());
    }

    /**
     * FAWE's splatter grows splotches over the surface from points on it; it
     * filled random blocks of a sphere, the air over the ground included.
     */
    private static void theSplatterBrushPaintsTheSurface() {
        TestActor actor = actor("SplatterBrush");
        TestWorld world = (TestWorld) actor.world();
        answer(actor, "/brush splatter gold_block 6 3 4");
        int changed = stroke(actor, 0, 63, 0);
        int surface = 0;
        int elsewhere = 0;
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                for (int y = 58; y <= 70; y++) {
                    if (world.getBlock(x, y, z) == state("minecraft:gold_block")) {
                        if (y == 63) {
                            surface++;
                        } else {
                            elsewhere++;
                        }
                    }
                }
            }
        }
        check("the splatter brush paints (" + changed + ")", changed > 0 && surface == changed);
        checkEquals("on the surface only, never in the air or under the ground", 0, elsewhere);
        answer(actor, "//undo");
        answer(actor, "/brush splatter gold_block 6 1 0");
        checkEquals("a splotch of no recursion is its point", 1, stroke(actor, 0, 63, 0));
    }

    /**
     * FAWE's line and catenary brushes join the points of two clicks. The line
     * brush drew from where the player stood on the first click and from there
     * forever after; the catenary one hung a sine from the corner of the
     * selection, and failed without one.
     */
    private static void theLineBrushesJoinTwoClicks() {
        TestActor actor = actor("LineBrush");
        TestWorld world = (TestWorld) actor.world();
        int gold = state("minecraft:gold_block");
        answer(actor, "/brush line gold_block 0");
        checkEquals("the first click of the line brush only marks a point", 0, stroke(actor, 0, 70, 0));
        checkEquals("the second draws the line", 6, stroke(actor, 5, 70, 0));
        check("between the two", world.getBlock(0, 70, 0) == gold && world.getBlock(3, 70, 0) == gold
                && world.getBlock(5, 70, 0) == gold);
        checkEquals("and the next click starts another line", 0, stroke(actor, 5, 75, 0));
        stroke(actor, 5, 78, 0);
        check("from there", world.getBlock(5, 77, 0) == gold && world.getBlock(5, 72, 0) != gold);
        answer(actor, "/brush line diamond_block 0 -s");
        stroke(actor, 20, 70, 0);
        stroke(actor, 20, 70, 4);
        // The shared end is already there: two new blocks.
        checkEquals("with -s the end of a line starts the next", 2, stroke(actor, 22, 70, 4));
        answer(actor, "/brush line emerald_block 1 -f");
        stroke(actor, 30, 70, 0);
        stroke(actor, 34, 70, 0);
        check("-f draws discs at the line's height, not balls", world.getBlock(32, 70, 1) == state("minecraft:emerald_block")
                && world.getBlock(32, 71, 0) != state("minecraft:emerald_block"));

        answer(actor, "/brush catenary gold_block 1.5 0");
        checkEquals("the catenary brush marks its first end too", 0, stroke(actor, 40, 90, 0));
        check("and hangs a wire to the second", stroke(actor, 50, 90, 0) > 0);
        int lowest = Integer.MAX_VALUE;
        for (int y = 60; y <= 90; y++) {
            if (world.getBlock(45, y, 0) == gold) {
                lowest = Math.min(lowest, y);
            }
        }
        check("that sags between them (" + lowest + ")", lowest < 90 && lowest > 70);
        check("from one end to the other", world.getBlock(40, 90, 0) == gold && world.getBlock(50, 90, 0) == gold);
    }

    private static void theScatterBrushesPickSurfacePointsApart() {
        TestActor actor = actor("ScatterBrush");
        TestWorld world = (TestWorld) actor.world();
        answer(actor, "/brush scatter gold_block 6 5 2");
        checkEquals("the scatter brush takes as many points as it is told", 5, stroke(actor, 0, 63, 0));
        int onTheSurface = 0;
        int above = 0;
        java.util.List<BlockVector3> placed = new java.util.ArrayList<>();
        for (int x = -6; x <= 6; x++) {
            for (int z = -6; z <= 6; z++) {
                if (world.getBlock(x, 63, z) == state("minecraft:gold_block")) {
                    onTheSurface++;
                    placed.add(new BlockVector3(x, 63, z));
                }
                if (world.getBlock(x, 64, z) == state("minecraft:gold_block")) {
                    above++;
                }
            }
        }
        checkEquals("in place of surface blocks, as FAWE's", 5, onTheSurface);
        checkEquals("not over them", 0, above);
        boolean apart = true;
        for (int i = 0; i < placed.size(); i++) {
            for (int j = i + 1; j < placed.size(); j++) {
                apart &= Math.max(Math.abs(placed.get(i).x() - placed.get(j).x()),
                        Math.abs(placed.get(i).z() - placed.get(j).z())) > 2;
            }
        }
        check("more than the distance apart", apart);
        answer(actor, "//undo");
        answer(actor, "/brush scatter gold_block 6 5 2 -o");
        stroke(actor, 0, 63, 0);
        int laid = 0;
        for (int x = -6; x <= 6; x++) {
            for (int z = -6; z <= 6; z++) {
                if (world.getBlock(x, 64, z) == state("minecraft:gold_block")) {
                    laid++;
                }
            }
        }
        checkEquals("-o lays the pattern on the surface", 5, laid);

        answer(actor, "/brush scattercommand 6 3 2 //set emerald_block -p");
        actor.clearMessages();
        checkEquals("the scatter command brush runs its command at each of its points", 3,
                stroke(actor, 20, 63, 20));
        check("-p shows what they answer", actor.messages().stream()
                .anyMatch(message -> message.replaceAll("\u00a7.", "").contains("Set:")));
        int emeralds = 0;
        for (int x = 10; x <= 30; x++) {
            for (int y = 60; y <= 66; y++) {
                for (int z = 10; z <= 30; z++) {
                    if (world.getBlock(x, y, z) == state("minecraft:emerald_block")) {
                        emeralds++;
                    }
                }
            }
        }
        // The cubes are as wide as the points are apart, so two may share blocks.
        check("each in the cube of the distance around its point (" + emeralds + ")",
                emeralds > 125 && emeralds <= 3 * 125);
    }
}
