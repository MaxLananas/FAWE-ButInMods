package com.maxlananas.fawebim.core.test;

import com.maxlananas.fawebim.core.command.CommandManager;
import com.maxlananas.fawebim.core.command.Suggestions;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.world.BlockState;
import com.maxlananas.fawebim.core.world.BlockStateRegistry;

import static com.maxlananas.fawebim.core.test.SelfTestMain.check;
import static com.maxlananas.fawebim.core.test.SelfTestMain.section;

/**
 * The masks are written as WorldEdit and FAWE write them.
 *
 * <p>{@code >stone} and {@code <stone}, WorldEdit's masks of the block over and
 * under a mask, {@code $plains}, its biome mask, and {@code ^[axis=y]}, its
 * block state mask, were not masks: the first ones were unknown blocks and the
 * last one was read as a slope. {@code #offset[x][y][z][mask]} failed to parse
 * and tested the global mask instead of its own. {@code #existing} matched
 * every block, air included, so {@code !#existing} matched none and
 * {@code #wall} took air for a wall; {@code ##fences} and every block tag that
 * is not one of the mod's categories matched nothing, silently.</p>
 */
final class MaskSyntaxTests {

    private MaskSyntaxTests() {
    }

    static void run() {
        section("mask syntax");
        // Ground of stone, dirt and grass up to 63 over an 8 by 8 by 7 box from 60.
        TestActor actor = actor("MaskSyntax");
        theBlockOverAndUnderAMask(actor);
        existingIsWhatIsNotAir(actor);
        biomesAreMasks(actor);
        anOffsetMaskTestsItsOwnMask(actor);
        blockTagsAreMasks(actor);
        theHotbarIsAMask(actor);
        masksComplete();
        BlockStateRegistry previous = BlockState.registry();
        BlockState.setRegistry(new PropertyTestRegistry());
        try {
            blockStatesAreMasks();
        } finally {
            BlockState.setRegistry(previous);
        }
    }

    private static TestActor actor(String name) {
        TestWorld world = new TestWorld(name);
        world.fillFlat(64);
        TestActor actor = new TestActor(name, world, new BlockVector3(0, 70, 0));
        answer(actor, "//pos1 0,60,0");
        answer(actor, "//pos2 7,66,7");
        return actor;
    }

    private static String answer(TestActor actor, String line) {
        actor.clearMessages();
        CommandManager.get().dispatch(actor, line);
        return String.join("\n", actor.messages()).replaceAll("\u00a7.", "");
    }

    private static boolean counts(TestActor actor, String mask, int expected) {
        return answer(actor, "//count " + mask).contains("Counted: " + expected);
    }

    private static void theBlockOverAndUnderAMask(TestActor actor) {
        check(">grass_block is the air over the grass", counts(actor, ">grass_block", 64));
        check("<stone is what is under stone", counts(actor, "<stone", 64));
        check("> alone is anything over a block", counts(actor, ">", 320));
        check("< alone is anything under one", counts(actor, "<", 192));
        check("they take any mask, a negation included", counts(actor, ">!air", 320));
    }

    private static void existingIsWhatIsNotAir(TestActor actor) {
        check("#existing is the ground and not the air", counts(actor, "#existing", 256));
        check("!#existing is the air", counts(actor, "!#existing", 192));
        check("#wall is no air", counts(actor, "#wall&air", 0));
    }

    private static void biomesAreMasks(TestActor actor) {
        TestWorld world = (TestWorld) actor.world();
        int desert = BlockState.registry().biome("desert");
        for (int z = 0; z < 8; z++) {
            world.setBiome(0, 60, z, desert);
        }
        check("$desert is a biome mask", counts(actor, "$desert", 56));
        check("$[desert,ocean] takes several", counts(actor, "$[desert,ocean]", 448));
        check("an unknown biome is refused", answer(actor, "//count $nowhere").contains("Unknown biome 'nowhere'"));
    }

    private static void anOffsetMaskTestsItsOwnMask(TestActor actor) {
        check("#offset[0][-1][0][grass_block] is what stands on grass",
                counts(actor, "#offset[0][-1][0][grass_block]", 64));
        check("#offset[0][1][0][air] is what has air over it", counts(actor, "#offset[0][1][0][air]", 256));
        check("a word for a number is refused with the syntax",
                answer(actor, "//count #offset[a][0][0][air]").contains("whole number"));
    }

    private static void theHotbarIsAMask(TestActor actor) {
        // The test player's hotbar holds stone; the ground under the grass is stone.
        check("#hotbar is the blocks of the hotbar", counts(actor, "#hotbar", 128));
    }

    private static void blockTagsAreMasks(TestActor actor) {
        check("##minecraft:logs is the tag", counts(actor, "##minecraft:logs", 0));
        check("a tag the mod has no category for is a mask",
                !answer(actor, "//count ##mineable/pickaxe").contains("unknown"));
        check("an unknown tag is refused", answer(actor, "//count ##nope").contains("unknown block tag 'nope'"));
    }

    private static void masksComplete() {
        check("the signs of the masks are offered", Suggestions.masks("").containsAll(
                java.util.List.of(">", "<", "$", "^[", "#hotbar")));
        check("what follows > completes as a mask", Suggestions.masks(">gra").contains(">grass_block"));
        check("and what follows a negation", Suggestions.masks("!sto").contains("!stone"));
        check("$ completes the biomes", Suggestions.masks("$des").contains("$desert"));
    }

    private static void blockStatesAreMasks() {
        TestWorld world = new TestWorld("MaskStates");
        TestActor actor = new TestActor("MaskStates", world, new BlockVector3(0, 70, 0));
        BlockStateRegistry registry = BlockState.registry();
        world.setBlock(0, 60, 0, registry.parse("minecraft:oak_log[axis=y]"));
        world.setBlock(1, 60, 0, registry.parse("minecraft:oak_log[axis=x]"));
        world.setBlock(2, 60, 0, registry.parse("minecraft:stone"));
        answer(actor, "//pos1 0,60,0");
        answer(actor, "//pos2 2,60,0");
        check("^[axis=y] takes the upright log and the blocks without an axis",
                counts(actor, "^[axis=y]", 2));
        check("^=[axis=y] wants the axis there", counts(actor, "^=[axis=y]", 1));
        check("^[axis=x] is the other log", counts(actor, "^=[axis=x]", 1));
        check("it is not a slope any more", !answer(actor, "//count ^[axis=y]").contains("angle"));
        check("a state without a value is refused",
                answer(actor, "//count ^[axis]").contains("Expected property=value"));
    }
}
