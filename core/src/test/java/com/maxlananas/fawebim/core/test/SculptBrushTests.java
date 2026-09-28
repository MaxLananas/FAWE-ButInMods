package com.maxlananas.fawebim.core.test;

import com.maxlananas.fawebim.core.brush.Brush;
import com.maxlananas.fawebim.core.brush.BrushFactory;
import com.maxlananas.fawebim.core.brush.Brushes;
import com.maxlananas.fawebim.core.command.CommandManager;
import com.maxlananas.fawebim.core.extent.EditSession;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.world.BlockState;

import java.util.Random;

import static com.maxlananas.fawebim.core.test.SelfTestMain.check;
import static com.maxlananas.fawebim.core.test.SelfTestMain.checkEquals;
import static com.maxlananas.fawebim.core.test.SelfTestMain.section;

/**
 * The terrain sculpting brushes work as FAWE's.
 *
 * <p>The blend ball never chose air, so a block standing out of the ground
 * was turned into the ground's block instead of going; with {@code -a} it
 * compared every block with itself and changed none; its {@code -m} mask only
 * skipped blocks, where FAWE's also counts what it refuses as air; and each
 * block read the blocks the same stroke had changed before it, so the result
 * depended on the order of the scan.</p>
 */
final class SculptBrushTests {

    private SculptBrushTests() {
    }

    static void run() {
        section("sculpt brushes");
        theBlendBallTakesALoneBlockAwayAndLeavesTheFloor();
        theBlendBallFillsAPocketAndLevelsWithAir();
        theBlendBallMaskCountsWhatItRefusesAsAir();
        theBlendBallDecidesOnTheTerrainBeforeTheStroke();
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

    private static String name(TestWorld world, int x, int y, int z) {
        return BlockState.registry().name(world.getBlock(x, y, z));
    }

    /**
     * A block on a flat floor has 17 air neighbours out of 26: air outnumbers
     * its own type and the ball takes it away. Each block of the floor has as
     * many neighbours of the block below as of the air above, a tie that
     * leaves it as it is.
     */
    private static void theBlendBallTakesALoneBlockAwayAndLeavesTheFloor() {
        TestActor actor = actor("BlendLone");
        TestWorld world = (TestWorld) actor.world();
        world.setBlock(0, 64, 0, state("minecraft:stone"));
        answer(actor, "/brush blendball 3");
        int changed = stroke(actor, 0, 64, 0);
        checkEquals("a block standing on the floor goes", "minecraft:air", name(world, 0, 64, 0));
        checkEquals("and nothing else changes", 1, changed);
        boolean floor = true;
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                floor &= name(world, x, 63, z).equals("minecraft:grass_block")
                        && name(world, x, 62, z).equals("minecraft:dirt")
                        && name(world, x, 64, z).equals("minecraft:air");
            }
        }
        check("the floor keeps its grass, its dirt and the air over it", floor);
        String refused = answer(actor, "/brush blendball 5 27");
        check("a frequency difference over 26 is refused as FAWE refuses it (" + refused + ")",
                refused.contains("minFreqDiff not in range 0 <= value <= 26"));
    }

    /**
     * A pocket of air in stone has 26 stone neighbours and fills. With -a
     * only air is weighed: a block standing out goes, and a hole in the floor
     * takes the neighbour FAWE's count settles on, the dirt under it, which
     * reaches the most neighbours first.
     */
    private static void theBlendBallFillsAPocketAndLevelsWithAir() {
        TestActor actor = actor("BlendPocket");
        TestWorld world = (TestWorld) actor.world();
        world.setBlock(0, 50, 0, BlockState.registry().air());
        answer(actor, "/brush blendball 2");
        stroke(actor, 0, 50, 0);
        checkEquals("a pocket of air inside the stone fills", "minecraft:stone", name(world, 0, 50, 0));

        world.setBlock(0, 64, 0, state("minecraft:stone"));
        world.setBlock(6, 63, 0, BlockState.registry().air());
        answer(actor, "/brush blendball 2 1 -a");
        stroke(actor, 0, 64, 0);
        checkEquals("-a takes a block standing out away", "minecraft:air", name(world, 0, 64, 0));
        checkEquals("and leaves the floor under it", "minecraft:grass_block", name(world, 0, 63, 0));
        stroke(actor, 6, 63, 0);
        checkEquals("-a fills a hole in the floor", "minecraft:dirt", name(world, 6, 63, 0));
    }

    /**
     * A stone block set into the grass ties between the dirt under it and the
     * air over it. With -m stone the ball only looks at stone: the grass and
     * the dirt count as air, and the stone goes, while the grass it does not
     * look at stays.
     */
    private static void theBlendBallMaskCountsWhatItRefusesAsAir() {
        TestActor actor = actor("BlendMask");
        TestWorld world = (TestWorld) actor.world();
        world.setBlock(0, 63, 0, state("minecraft:stone"));
        answer(actor, "/brush blendball 2");
        stroke(actor, 0, 63, 0);
        checkEquals("without a mask the stone in the grass stays", "minecraft:stone", name(world, 0, 63, 0));
        answer(actor, "/brush blendball 2 1 -m stone");
        stroke(actor, 0, 63, 0);
        checkEquals("-m counts the blocks it refuses as air", "minecraft:air", name(world, 0, 63, 0));
        checkEquals("and leaves them as they are", "minecraft:grass_block", name(world, 1, 63, 0));
    }

    /**
     * Terrain that is the same on both sides of x = 0 is still so after a
     * stroke at x = 0: every block is decided on the blocks as they were,
     * whatever order they are visited in.
     */
    private static void theBlendBallDecidesOnTheTerrainBeforeTheStroke() {
        TestActor actor = actor("BlendOrder");
        TestWorld world = (TestWorld) actor.world();
        Random random = new Random(7);
        int[] kinds = {state("minecraft:stone"), state("minecraft:dirt"), state("minecraft:cobblestone")};
        int air = BlockState.registry().air();
        for (int x = 0; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                int height = 58 + random.nextInt(8);
                for (int y = 50; y <= 72; y++) {
                    int block = y > height ? air
                            : y == height ? state("minecraft:grass_block")
                            : random.nextInt(5) == 0 ? air : kinds[random.nextInt(kinds.length)];
                    world.setBlock(x, y, z, block);
                    world.setBlock(-x, y, z, block);
                }
            }
        }
        answer(actor, "/brush blendball 6");
        int changed = stroke(actor, 0, 61, 0);
        check("the stroke changed the terrain (" + changed + ")", changed > 50);
        int broken = 0;
        for (int x = 1; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                for (int y = 50; y <= 72; y++) {
                    if (world.getBlock(x, y, z) != world.getBlock(-x, y, z)) {
                        broken++;
                    }
                }
            }
        }
        checkEquals("and left it the same on both sides", 0, broken);
    }
}
