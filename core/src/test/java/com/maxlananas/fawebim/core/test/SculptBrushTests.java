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
 *
 * <p>The erode and morph brushes met the neighbours in another order than
 * FAWE's and WorldEdit's and settled ties their own way, erode counted states
 * where FAWE counts types, took a single open face where FAWE needs a type met
 * twice, and called the cobweb closed.</p>
 *
 * <p>The rock drew the same sphere, wobbled by sines of its offsets, on every
 * click, and refused FAWE's radius of three numbers.</p>
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
        erosionCountsTypesAndNeedsOneTwice();
        erosionFillingKeepsTheLastTypeAndMorphTheFirstToLead();
        theRockTakesARadiusPerAxisAndIsNewEveryClick();
        aFallingSphereDropsOntoTheGroundUnderEachColumn();
    }

    /**
     * FAWE's falling sphere drops each column onto the highest block that
     * stops movement at or under the column's top, keeping its length: over a
     * flat floor the middle column of a sphere of radius 2 is five blocks
     * from the grass up, beside a stone pillar the column is set in the
     * pillar and no higher, and over a pond it falls through the water. It
     * dropped onto the highest block of the whole column, filled beside the
     * pillar up to its top, and landed on the water.
     */
    private static void aFallingSphereDropsOntoTheGroundUnderEachColumn() {
        TestActor actor = actor("FallingSphere");
        TestWorld world = (TestWorld) actor.world();
        int gold = state("minecraft:gold_block");
        int stone = state("minecraft:stone");
        for (int y = 64; y <= 80; y++) {
            world.setBlock(1, y, 0, stone);
        }
        for (int y = 64; y <= 66; y++) {
            world.setBlock(-1, y, 0, state("minecraft:water"));
        }
        answer(actor, "/brush sphere gold_block 2 -f");
        stroke(actor, 0, 72, 0);
        boolean middle = true;
        for (int y = 63; y <= 67; y++) {
            middle &= world.getBlock(0, y, 0) == gold;
        }
        check("the middle column falls onto the grass and keeps its five blocks",
                middle && world.getBlock(0, 68, 0) != gold);
        boolean pillar = world.getBlock(1, 71, 0) == gold && world.getBlock(1, 73, 0) == gold;
        for (int y = 74; y <= 80; y++) {
            pillar &= world.getBlock(1, y, 0) == stone;
        }
        check("beside a pillar the column is set in it, no higher", pillar);
        check("and over a pond it falls through the water", world.getBlock(-1, 64, 0) == gold
                && world.getBlock(-1, 63, 0) == gold && world.getBlock(-1, 66, 0) != gold);
    }

    /**
     * FAWE's rock reads its radius as a vector and draws its noise from a
     * random corner on every click. Without noise it is FAWE's ellipsoid, the
     * axis radii dividing the squared offsets: 6,3,6 reaches 5 blocks out and
     * 4 up, x² + 2y² + z² under 36.
     */
    private static void theRockTakesARadiusPerAxisAndIsNewEveryClick() {
        TestActor actor = actor("Rock");
        TestWorld world = (TestWorld) actor.world();
        String bound = answer(actor, "/brush rock gold_block 3,6,3");
        check("a radius per axis is taken, the largest being the size (" + bound + ")",
                bound.contains("equipped (radius 6)"));
        check("two numbers are no radius", answer(actor, "/brush rock gold_block 6,3").contains(
                "'6,3' is not a radius: give one number, or three like 10,5,10"));
        check("nor is zero", answer(actor, "/brush rock gold_block 6,0,6").contains("Each radius must be a positive number"));

        answer(actor, "/brush rock gold_block 6,3,6 100 30 0");
        stroke(actor, 0, 120, 0);
        int gold = state("minecraft:gold_block");
        int blocks = 0;
        int expected = 0;
        int widest = 0;
        int highest = 0;
        for (int x = -12; x <= 12; x++) {
            for (int y = -12; y <= 12; y++) {
                for (int z = -12; z <= 12; z++) {
                    if (x * x + 2 * y * y + z * z < 36) {
                        expected++;
                    }
                    if (world.getBlock(x, 120 + y, z) == gold) {
                        blocks++;
                        widest = Math.max(widest, Math.abs(x));
                        highest = Math.max(highest, Math.abs(y));
                    }
                }
            }
        }
        checkEquals("without noise the rock is FAWE's ellipsoid", expected, blocks);
        check("wider than it is high (" + widest + ", " + highest + ")", widest == 5 && highest == 4);

        answer(actor, "/brush rock gold_block 6");
        stroke(actor, 40, 120, 0);
        stroke(actor, 80, 120, 0);
        int differ = 0;
        for (int x = -12; x <= 12; x++) {
            for (int y = -12; y <= 12; y++) {
                for (int z = -12; z <= 12; z++) {
                    if ((world.getBlock(40 + x, 120 + y, z) == gold) != (world.getBlock(80 + x, 120 + y, z) == gold)) {
                        differ++;
                    }
                }
            }
        }
        check("two clicks give two rocks (" + differ + " blocks apart)", differ > 0);
    }

    private static int apply(TestActor actor, Brush brush, int x, int y, int z) {
        EditSession session = new EditSession(actor.world(), actor.session(), "brush");
        try {
            return Brushes.apply(brush, session, new BlockVector3(x, y, z), actor);
        } finally {
            session.close();
        }
    }

    private static Brushes.MorphBrush erode(double radius, int erodeFaces, int erodeRec, int fillFaces, int fillRec) {
        return new Brushes.MorphBrush(radius, com.maxlananas.fawebim.core.function.Morphology.Style.ERODE,
                new com.maxlananas.fawebim.core.function.Morphology.Passes(erodeFaces, erodeRec, fillFaces, fillRec),
                null);
    }

    /**
     * FAWE's erosion counts the open neighbours by block type from one up, and
     * a block only takes a type met at least twice: air and cave air beside a
     * block are two types met once, and one air face is not enough even when
     * one is asked for. What does not stop movement is open, the cobweb with
     * it, though the game calls it solid. Radius 1 holds the centre alone.
     */
    private static void erosionCountsTypesAndNeedsOneTwice() {
        TestActor actor = actor("ErodeTypes");
        TestWorld world = (TestWorld) actor.world();
        int air = BlockState.registry().air();
        world.setBlock(0, 40, -1, air);
        world.setBlock(0, 40, 1, state("minecraft:cave_air"));
        apply(actor, erode(1, 2, 1, 5, 0), 0, 40, 0);
        checkEquals("air and cave air are two types met once: the block stays", "minecraft:stone",
                name(world, 0, 40, 0));
        world.setBlock(0, 40, 1, air);
        apply(actor, erode(1, 2, 1, 5, 0), 0, 40, 0);
        checkEquals("two air faces erode it", "minecraft:air", name(world, 0, 40, 0));

        world.setBlock(4, 40, -1, air);
        apply(actor, erode(1, 1, 1, 5, 0), 4, 40, 0);
        checkEquals("one air face is not enough, even with one asked", "minecraft:stone", name(world, 4, 40, 0));

        world.setBlock(8, 40, -1, state("minecraft:cobweb"));
        world.setBlock(8, 40, 1, state("minecraft:cobweb"));
        apply(actor, erode(1, 2, 1, 5, 0), 8, 40, 0);
        checkEquals("a block between two cobwebs, which let movement through, erodes into one",
                "minecraft:cobweb", name(world, 8, 40, 0));
    }

    /**
     * FAWE's filling lets a later type that equals the best take its place;
     * WorldEdit's morph keeps the state that reached the best count first.
     * Both meet the neighbours north, east, south, west, up and down.
     */
    private static void erosionFillingKeepsTheLastTypeAndMorphTheFirstToLead() {
        TestActor actor = actor("ErodeTies");
        TestWorld world = (TestWorld) actor.world();
        int air = BlockState.registry().air();
        for (int[] cell : new int[][] {{0, 0, 0}, {0, 1, 0}, {0, -1, 0}, {-1, 0, 0}, {0, 0, 1}}) {
            world.setBlock(cell[0], 40 + cell[1], cell[2], air);
        }
        world.setBlock(0, 40, -1, state("minecraft:stone"));
        world.setBlock(1, 40, 0, state("minecraft:dirt"));
        apply(actor, erode(1, 6, 0, 2, 1), 0, 40, 0);
        checkEquals("filling between stone to the north and dirt to the east takes the dirt, met last",
                "minecraft:dirt", name(world, 0, 40, 0));

        for (int[] cell : new int[][] {{0, 0, 0}, {0, 1, 0}, {0, -1, 0}}) {
            world.setBlock(6 + cell[0], 40 + cell[1], cell[2], air);
        }
        world.setBlock(6, 40, -1, state("minecraft:stone"));
        world.setBlock(7, 40, 0, state("minecraft:dirt"));
        world.setBlock(6, 40, 1, state("minecraft:dirt"));
        world.setBlock(5, 40, 0, state("minecraft:stone"));
        Brushes.MorphBrush morph = new Brushes.MorphBrush(0.5,
                com.maxlananas.fawebim.core.function.Morphology.Style.MORPH,
                new com.maxlananas.fawebim.core.function.Morphology.Passes(7, 0, 4, 1), null);
        apply(actor, morph, 6, 40, 0);
        checkEquals("morph fills with the dirt, whose second face comes before the stone's", "minecraft:dirt",
                name(world, 6, 40, 0));
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
