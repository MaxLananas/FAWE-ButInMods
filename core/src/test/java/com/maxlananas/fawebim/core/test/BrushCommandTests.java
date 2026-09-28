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
        theShatterBrushDrawsCracks();
        theSurfaceBrushPaintsAnySurface();
        theImageBrushPaintsItsImage();
        theCopyPasteBrushCopiesThenPastesAboutItsOrigin();
        thePopulateBrushSetsItsSchematicsOnTheSurfaceWithoutTheirAir();
        theExtinguishBrushPutsOutFireAlone();
    }

    /**
     * WorldEdit's and FAWE's extinguisher is a sphere brush of air masked to
     * the fire block: WorldEdit's sphere, half a block wider than the radius,
     * and nothing but fire in it. It took lava, campfires and anything named
     * with "fire" too, in a smaller ball.
     */
    private static void theExtinguishBrushPutsOutFireAlone() {
        TestActor actor = actor("Extinguish");
        TestWorld world = (TestWorld) actor.world();
        int fire = state("minecraft:fire");
        world.setBlock(0, 70, 0, fire);
        world.setBlock(2, 71, 1, fire);
        world.setBlock(1, 70, 0, state("minecraft:soul_fire"));
        world.setBlock(-1, 70, 0, state("minecraft:lava"));
        world.setBlock(0, 70, 1, state("minecraft:campfire"));
        world.setBlock(0, 70, -1, state("minecraft:magma_block"));
        answer(actor, "/brush extinguish 2");
        stroke(actor, 0, 70, 0);
        check("the fire goes, up to WorldEdit's sphere", world.getBlock(0, 70, 0) == BlockState.registry().air()
                && world.getBlock(2, 71, 1) == BlockState.registry().air());
        check("and nothing else: soul fire, lava, a campfire and magma stay",
                world.getBlock(1, 70, 0) == state("minecraft:soul_fire")
                        && world.getBlock(-1, 70, 0) == state("minecraft:lava")
                        && world.getBlock(0, 70, 1) == state("minecraft:campfire")
                        && world.getBlock(0, 70, -1) == state("minecraft:magma_block"));
    }

    /**
     * FAWE's populate brush pastes each schematic one block above the surface
     * block, without the schematic's air, and reads the schematics once, when
     * it is bound: a file, a list, or a folder whose every schematic is drawn
     * from. It pasted the air of the schematic's box into the ground, stood
     * the schematic in the surface block, and read the file on every copy.
     */
    private static void thePopulateBrushSetsItsSchematicsOnTheSurfaceWithoutTheirAir() {
        int gold = state("minecraft:gold_block");
        int diamond = state("minecraft:diamond_block");
        com.maxlananas.fawebim.core.clipboard.BlockArrayClipboard stamp =
                new com.maxlananas.fawebim.core.clipboard.BlockArrayClipboard(new BlockVector3(0, 0, 0));
        for (int x = -1; x <= 1; x++) {
            for (int y = 0; y <= 2; y++) {
                for (int z = -1; z <= 1; z++) {
                    stamp.setBlock(x, y, z, x == 0 && y == 0 && z == 0 ? gold : BlockState.registry().air());
                }
            }
        }
        com.maxlananas.fawebim.core.clipboard.Schematics.save(stamp, "populate-stamp", "sponge.3");
        TestActor actor = actor("Populate");
        TestWorld world = (TestWorld) actor.world();
        String refused = answer(actor, "/brush populateschematic populate-nothing grass_block 5 100");
        check("a schematic that is not there is refused when the brush is bound (" + refused + ")",
                refused.contains("populate-nothing") && BrushFactory.current(actor) == null);
        answer(actor, "/brush populateschematic populate-stamp grass_block 6 100");
        com.maxlananas.fawebim.core.clipboard.Schematics.delete("populate-stamp.schem");
        int groundBefore = 0;
        for (int x = -24; x < 24; x++) {
            for (int z = -24; z < 24; z++) {
                groundBefore += world.getBlock(x, 63, z) == state("minecraft:grass_block") ? 1 : 0;
            }
        }
        stroke(actor, 0, 64, 0);
        int stamps = 0;
        int ground = 0;
        for (int x = -24; x < 24; x++) {
            for (int z = -24; z < 24; z++) {
                stamps += world.getBlock(x, 64, z) == gold ? 1 : 0;
                ground += world.getBlock(x, 63, z) == state("minecraft:grass_block") ? 1 : 0;
            }
        }
        check("the schematics read when the brush was bound are set on the grass, one block up (" + stamps + ")",
                stamps > 0);
        checkEquals("and their air leaves the ground as it was", groundBefore, ground);

        com.maxlananas.fawebim.core.clipboard.BlockArrayClipboard other =
                new com.maxlananas.fawebim.core.clipboard.BlockArrayClipboard(new BlockVector3(0, 0, 0));
        other.setBlock(0, 0, 0, diamond);
        com.maxlananas.fawebim.core.clipboard.Schematics.save(stamp, "populate-folder/gold", "sponge.3");
        com.maxlananas.fawebim.core.clipboard.Schematics.save(other, "populate-folder/diamond", "sponge.3");
        checkEquals("a folder gives every schematic in it", 2,
                Brushes.PopulateSchematicBrush.load("populate-folder").size());
    }

    /**
     * FAWE's copy paste brush empties the clipboard when it is bound, so its
     * first click copies: the blocks joined to the clicked one, no lower,
     * within the radius counted in steps - FAWE's walk reaches one step past
     * it. The next clicks paste one block above the click, turned about the
     * origin of the copy. The paste was turned about the click, which threw
     * the object as far away as the click was from the copy, and a clipboard
     * of before was pasted by the first click.
     */
    private static void theCopyPasteBrushCopiesThenPastesAboutItsOrigin() {
        TestActor actor = actor("CopyPaste");
        TestWorld world = (TestWorld) actor.world();
        int gold = state("minecraft:gold_block");
        int diamond = state("minecraft:diamond_block");
        for (int y = 70; y < 80; y++) {
            world.setBlock(0, y, 0, gold);
        }
        answer(actor, "//pos1 0,70,0");
        answer(actor, "//pos2 0,70,0");
        answer(actor, "//copy");
        String bound = answer(actor, "/brush copypaste 3");
        check("binding it empties the clipboard, so the first click copies",
                !actor.session().hasClipboard());
        check("and says how to use it (" + bound + ")", bound.contains("Left click the base of an object to copy"));
        stroke(actor, 0, 70, 0);
        check("the first click copies the pillar up to one step past the radius",
                actor.session().hasClipboard() && actor.session().getClipboard().getClipboard().volume() == 5);

        world.setBlock(20, 70, 0, gold);
        world.setBlock(21, 70, 0, diamond);
        TestActor turner = actor("CopyPasteTurn");
        TestWorld turned = (TestWorld) turner.world();
        turned.setBlock(20, 70, 0, gold);
        turned.setBlock(21, 70, 0, diamond);
        answer(turner, "/brush copypaste 3 -a");
        stroke(turner, 20, 70, 0);
        turner.setYaw(90);
        turner.setPitch(90);
        stroke(turner, 40, 80, 40);
        check("a turned paste stands on the click", turned.getBlock(40, 81, 40) == gold);
        check("turned a quarter about the origin of the copy", turned.getBlock(40, 81, 41) == diamond
                && turned.getBlock(41, 81, 40) != diamond);

        TestActor masked = actor("CopyPasteMask");
        TestWorld maskedWorld = (TestWorld) masked.world();
        maskedWorld.setBlock(20, 70, 0, gold);
        maskedWorld.setBlock(21, 70, 0, diamond);
        answer(masked, "/brush copypaste 3");
        answer(masked, "/tool mask gold_block");
        stroke(masked, 20, 70, 0);
        checkEquals("the brush's mask decides what is copied", 1L,
                masked.session().getClipboard().getClipboard().volume());
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
        // A splotch grows up to the radius from its point, and its point is up
        // to the radius from the click.
        for (int x = -13; x <= 13; x++) {
            for (int z = -13; z <= 13; z++) {
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

    /**
     * FAWE's shatter draws its pattern along the cracks between patches grown
     * over the surface from its points; it set random blocks of a sphere to
     * air whatever its pattern.
     */
    private static void theShatterBrushDrawsCracks() {
        TestActor actor = actor("ShatterBrush");
        TestWorld world = (TestWorld) actor.world();
        int gold = state("minecraft:gold_block");
        answer(actor, "/brush shatter gold_block 6 5");
        int changed = stroke(actor, 0, 63, 0);
        int cracks = 0;
        int elsewhere = 0;
        int air = 0;
        for (int x = -10; x <= 10; x++) {
            for (int z = -10; z <= 10; z++) {
                for (int y = 58; y <= 70; y++) {
                    int block = world.getBlock(x, y, z);
                    if (block == gold) {
                        if (y == 63 && x * x + z * z <= 36) {
                            cracks++;
                        } else {
                            elsewhere++;
                        }
                    } else if (y <= 63 && BlockState.registry().isAirLike(block)) {
                        air++;
                    }
                }
            }
        }
        check("the shatter brush draws cracks of its pattern (" + changed + ")", changed > 0 && cracks == changed);
        checkEquals("on the surface within its radius only", 0, elsewhere);
        checkEquals("and takes no block away", 0, air);
    }

    /**
     * FAWE's surface brush paints the surface blocks around the click, a wall
     * as well as the ground; it laid its pattern on top of the highest block of
     * each column, and could not reach a wall.
     */
    private static void theSurfaceBrushPaintsAnySurface() {
        TestActor actor = actor("SurfaceBrush");
        TestWorld world = (TestWorld) actor.world();
        int gold = state("minecraft:gold_block");
        answer(actor, "/brush surface gold_block 2");
        stroke(actor, 0, 63, 0);
        checkEquals("the surface brush paints the ground itself", gold, world.getBlock(1, 63, 0));
        check("rather than laying a block on it", world.getBlock(1, 64, 0) != gold);
        check("within its radius", world.getBlock(3, 63, 0) != gold);
        // A wall of stone standing on the ground at x 10, painted from its side.
        for (int y = 64; y <= 70; y++) {
            for (int z = -3; z <= 3; z++) {
                world.setBlock(10, y, z, state("minecraft:stone"));
            }
        }
        stroke(actor, 10, 67, 0);
        check("and a wall", world.getBlock(10, 68, 1) == gold && world.getBlock(10, 66, -1) == gold);
    }

    /**
     * FAWE's image brush lays its image over the surface; it was a sphere of a
     * pattern it had not, so of air.
     */
    private static void theImageBrushPaintsItsImage() {
        TestActor actor = actor("ImageBrush");
        TestWorld world = (TestWorld) actor.world();
        java.nio.file.Path folder = com.maxlananas.fawebim.core.clipboard.Schematics.directory();
        java.nio.file.Path file = folder.resolve("fawebim-test-image.png");
        try {
            java.nio.file.Files.createDirectories(folder);
            java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(4, 4,
                    java.awt.image.BufferedImage.TYPE_INT_ARGB);
            for (int x = 0; x < 4; x++) {
                for (int z = 0; z < 4; z++) {
                    image.setRGB(x, z, x < 2 ? 0xFFFF0000 : 0xFF0000FF);
                }
            }
            javax.imageio.ImageIO.write(image, "png", file.toFile());
        } catch (java.io.IOException e) {
            check("the test image is written: " + e, false);
            return;
        }
        // Looking straight down, facing south: the image lies on the ground,
        // its x along the world's x.
        actor.setPitch(90);
        actor.setYaw(0);
        try {
            check("an image that is not there is refused",
                    answer(actor, "/brush image nothing.png 2").contains("not found"));
            answer(actor, "/brush image fawebim-test-image.png 2");
            int changed = stroke(actor, 0, 63, 0);
            check("the image brush paints the ground (" + changed + ")", changed > 0);
            int west = com.maxlananas.fawebim.core.pattern.MapColors.colorOf(BlockState.registry(),
                    world.getBlock(-1, 63, 0));
            int east = com.maxlananas.fawebim.core.pattern.MapColors.colorOf(BlockState.registry(),
                    world.getBlock(1, 63, 0));
            check("red where the image is red", ((west >> 16) & 0xFF) > (west & 0xFF));
            check("blue where it is blue", (east & 0xFF) > ((east >> 16) & 0xFF));
            check("and no air carved", !BlockState.registry().isAirLike(world.getBlock(0, 62, 0))
                    && !BlockState.registry().isAirLike(world.getBlock(0, 63, 0)));
        } finally {
            try {
                java.nio.file.Files.deleteIfExists(file);
            } catch (java.io.IOException ignored) {
                // Left in the git-ignored folder.
            }
        }
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
