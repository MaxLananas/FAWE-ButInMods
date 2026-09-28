package com.maxlananas.fawebim.core.test;

import com.maxlananas.fawebim.core.command.CommandManager;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.world.BlockState;

import static com.maxlananas.fawebim.core.test.SelfTestMain.check;
import static com.maxlananas.fawebim.core.test.SelfTestMain.checkEquals;
import static com.maxlananas.fawebim.core.test.SelfTestMain.section;

/**
 * {@code //removeabove}, {@code //removebelow}, {@code //removenear},
 * {@code //extinguish} and {@code //replacenear} measure what they clear as
 * FAWE does.
 *
 * <p>The sizes were half-widths where FAWE takes an apothem - a size of 1 is
 * the player's own column - and the height of {@code //removeabove} and
 * {@code //removebelow} was a level of the world rather than a count of
 * levels from the player's: {@code //removeabove 2 3}, standing at y 64,
 * cleared a square five blocks wide from y 3 to the top of the world.</p>
 */
final class RemovalTests {

    private RemovalTests() {
    }

    static void run() {
        section("removals");
        removeAboveCountsItsLevelsFromThePlayer();
        removeBelowCountsItsLevelsDownFromThePlayer();
        removeNearTakesAnApothem();
        extinguishTakesAnApothem();
        replaceNearTakesAtLeastOneBlockEachWay();
    }

    /** A block of stone from y 56 to y 75 around the origin. */
    private static TestWorld stone(String name) {
        TestWorld world = new TestWorld(name);
        box(world, -4, 56, -4, 4, 75, 4, state("minecraft:stone"));
        return world;
    }

    private static void removeAboveCountsItsLevelsFromThePlayer() {
        TestWorld world = stone("RemoveAbove");
        int stone = state("minecraft:stone");
        int air = BlockState.registry().air();
        TestActor actor = new TestActor("RemoveAbove", world, new BlockVector3(0, 64, 0));
        String answer = answer(actor, "//removeabove 2 3");
        check("//removeabove 2 3 clears a square three blocks wide, four levels high (" + answer + ")",
                answer.contains("Removed: 36 blocks"));
        check("from the player's level", world.getBlock(-1, 64, 1) == air && world.getBlock(1, 67, -1) == air);
        checkEquals("up to the height given", stone, world.getBlock(0, 68, 0));
        checkEquals("and not below the player", stone, world.getBlock(0, 63, 0));
        checkEquals("and not past the apothem", stone, world.getBlock(2, 64, 0));

        TestWorld column = stone("RemoveAboveColumn");
        TestActor alone = new TestActor("RemoveAboveColumn", column, new BlockVector3(0, 64, 0));
        answer(alone, "//removeabove");
        check("with no argument, the player's column up to the top of the world",
                column.getBlock(0, 64, 0) == air && column.getBlock(0, 75, 0) == air);
        checkEquals("and only that column", stone, column.getBlock(1, 64, 0));
        check("a negative height is refused",
                answer(alone, "//removeabove 1 -5").contains("The height must be at least 0"));
    }

    private static void removeBelowCountsItsLevelsDownFromThePlayer() {
        TestWorld world = stone("RemoveBelow");
        int stone = state("minecraft:stone");
        int air = BlockState.registry().air();
        TestActor actor = new TestActor("RemoveBelow", world, new BlockVector3(0, 70, 0));
        String answer = answer(actor, "//removebelow 2 3");
        check("//removebelow 2 3 clears four levels down from the player's (" + answer + ")",
                answer.contains("Removed: 36 blocks"));
        check("from the player's level down", world.getBlock(1, 70, 1) == air && world.getBlock(-1, 67, 0) == air);
        checkEquals("down to the height given", stone, world.getBlock(0, 66, 0));
        checkEquals("and not above the player", stone, world.getBlock(0, 71, 0));
    }

    /** The default of fifty reaches 49 blocks away, and a size of 2 one block. */
    private static void removeNearTakesAnApothem() {
        TestWorld world = new TestWorld("RemoveNear");
        int diamond = state("minecraft:diamond_block");
        int air = BlockState.registry().air();
        world.setBlock(49, 64, -49, diamond);
        world.setBlock(50, 64, 0, diamond);
        world.setBlock(1, 65, 1, diamond);
        world.setBlock(0, 64, 2, diamond);
        TestActor actor = new TestActor("RemoveNear", world, new BlockVector3(0, 64, 0));
        answer(actor, "//removenear minecraft:diamond_block 2");
        checkEquals("//removenear of size 2 takes the block one away", air, world.getBlock(1, 65, 1));
        checkEquals("and leaves the one two away", diamond, world.getBlock(0, 64, 2));
        answer(actor, "//removenear minecraft:diamond_block");
        checkEquals("its default reaches 49 blocks away", air, world.getBlock(49, 64, -49));
        checkEquals("and not 50", diamond, world.getBlock(50, 64, 0));
    }

    private static void extinguishTakesAnApothem() {
        TestWorld world = new TestWorld("Extinguish");
        int fire = state("minecraft:fire");
        int air = BlockState.registry().air();
        world.setBlock(1, 64, -1, fire);
        world.setBlock(2, 64, 0, fire);
        TestActor actor = new TestActor("Extinguish", world, new BlockVector3(0, 64, 0));
        answer(actor, "//extinguish 2");
        checkEquals("//extinguish 2 puts out the fire one block away", air, world.getBlock(1, 64, -1));
        checkEquals("and not the fire two blocks away", fire, world.getBlock(2, 64, 0));
    }

    private static void replaceNearTakesAtLeastOneBlockEachWay() {
        TestWorld world = stone("ReplaceNear");
        int stone = state("minecraft:stone");
        int gold = state("minecraft:gold_block");
        TestActor actor = new TestActor("ReplaceNear", world, new BlockVector3(0, 64, 0));
        answer(actor, "//replacenear 0 minecraft:stone minecraft:gold_block");
        check("//replacenear 0 is a size of one, a block each way",
                world.getBlock(1, 65, -1) == gold && world.getBlock(0, 64, 0) == gold);
        checkEquals("and no further", stone, world.getBlock(2, 64, 0));
        check("its size is required", answer(actor, "//replacenear").contains("Missing argument 1"));
    }

    private static void box(TestWorld world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, int state) {
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    world.setBlock(x, y, z, state);
                }
            }
        }
    }

    private static int state(String name) {
        return BlockState.registry().defaultState(name);
    }

    private static String answer(TestActor actor, String line) {
        actor.clearMessages();
        CommandManager.get().dispatch(actor, line);
        return String.join("\n", actor.messages()).replaceAll("\u00a7.", "");
    }
}
