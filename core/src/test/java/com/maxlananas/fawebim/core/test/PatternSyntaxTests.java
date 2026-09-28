package com.maxlananas.fawebim.core.test;

import com.maxlananas.fawebim.core.command.CommandManager;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.world.BlockState;
import com.maxlananas.fawebim.core.world.BlockStateRegistry;

import static com.maxlananas.fawebim.core.test.SelfTestMain.check;
import static com.maxlananas.fawebim.core.test.SelfTestMain.checkEquals;
import static com.maxlananas.fawebim.core.test.SelfTestMain.section;

/**
 * The patterns are written, and do, as FAWE's.
 *
 * <p>{@code #offset[pattern][x][y][z]} and {@code #spread[pattern][n]} did not
 * parse, and in the form they did parse, {@code #offset[x,y,z]}, they set air
 * whatever the pattern; {@code #surfacespread} was a plain spread and
 * {@code #solidspread} set air where FAWE's keeps the block of the position.
 * {@code #linear}, {@code #l2d} and {@code #l3d} took two blocks and changed
 * from the first to the second every 128 blocks along x+y+z. {@code ##wool}
 * was taken for an unknown {@code #} pattern, and {@code ^oak_log} copied the
 * properties of the block below instead of those of the block it replaces.</p>
 */
final class PatternSyntaxTests {

    private PatternSyntaxTests() {
    }

    static void run() {
        section("pattern syntax");
        offsetsAndSpreads();
        linearPatterns();
        tagPatterns();
        colourAndSwapPatterns();
        clipboardPatterns();
        BlockStateRegistry previous = BlockState.registry();
        BlockState.setRegistry(new PropertyTestRegistry());
        try {
            typeAndStatePatterns();
        } finally {
            BlockState.setRegistry(previous);
        }
    }

    private static TestActor actor(String name) {
        TestWorld world = new TestWorld(name);
        world.fillFlat(64);
        return new TestActor(name, world, new BlockVector3(0, 70, 0));
    }

    private static String answer(TestActor actor, String line) {
        actor.clearMessages();
        CommandManager.get().dispatch(actor, line);
        return String.join("\n", actor.messages()).replaceAll("\u00a7.", "");
    }

    private static int state(String name) {
        return BlockState.registry().defaultState(name);
    }

    private static void offsetsAndSpreads() {
        TestActor actor = actor("PatternSpread");
        TestWorld world = (TestWorld) actor.world();
        answer(actor, "//pos1 0,64,0");
        answer(actor, "//pos2 3,65,3");
        answer(actor, "//set #offset[stone][0][1][0]");
        checkEquals("#offset[pattern][x][y][z] sets its pattern", state("minecraft:stone"), world.getBlock(1, 64, 1));
        answer(actor, "//set #spread[gold_block][1]");
        checkEquals("#spread[pattern][n] too", state("minecraft:gold_block"), world.getBlock(2, 65, 2));
        answer(actor, "//set #solidspread[air][1][1][1]");
        check("#solidspread keeps the block of the position when the offset one is not solid",
                BlockState.registry().isAirLike(world.getBlock(2, 65, 2)));
        check("the old #offset[x,y,z] is refused with the syntax",
                answer(actor, "//set #offset[0,1,0]").contains("#offset[pattern][x][y][z]"));
        check("a negative spread is refused", answer(actor, "//set #spread[stone][-1]").contains("negative"));
        // A column of dirt at x 0 over a floor of stone at 64, walked along its
        // surface: every block the walk ends on is one of the two, never air.
        answer(actor, "//pos2 3,64,3");
        answer(actor, "//set stone");
        answer(actor, "//pos1 0,65,0");
        answer(actor, "//pos2 0,68,0");
        answer(actor, "//set dirt");
        answer(actor, "//pos1 1,65,0");
        answer(actor, "//pos2 3,65,3");
        answer(actor, "//set #surfacespread[#existing][3]");
        boolean onTheSurface = true;
        for (int x = 1; x <= 3; x++) {
            for (int z = 0; z <= 3; z++) {
                int block = world.getBlock(x, 65, z);
                onTheSurface &= block == state("minecraft:stone") || block == state("minecraft:dirt")
                        || block == state("minecraft:grass_block");
            }
        }
        check("#surfacespread[#existing] copies the surface it walks, not the air over it", onTheSurface);
    }

    private static void linearPatterns() {
        TestActor actor = actor("PatternLinear");
        TestWorld world = (TestWorld) actor.world();
        answer(actor, "//pos1 0,64,0");
        answer(actor, "//pos2 5,64,0");
        answer(actor, "//set #linear[stone,dirt,sand]");
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (int x = 0; x <= 5; x++) {
            seen.add(world.getBlock(x, 64, 0));
        }
        checkEquals("#linear goes through every entry of its list", 3, seen.size());
        answer(actor, "//set #l2d[stone,dirt]");
        checkEquals("#l2d is stone where x+z is even", state("minecraft:stone"), world.getBlock(0, 64, 0));
        checkEquals("and dirt where it is odd", state("minecraft:dirt"), world.getBlock(1, 64, 0));
        checkEquals("in bands", state("minecraft:stone"), world.getBlock(2, 64, 0));
        answer(actor, "//set #l3d[stone,dirt][2][1][1]");
        checkEquals("#l3d divides x by its scale", state("minecraft:stone"), world.getBlock(1, 64, 0));
        checkEquals("so its bands are two wide", state("minecraft:dirt"), world.getBlock(2, 64, 0));
        answer(actor, "//set #l2d[25%stone,75%dirt]");
        checkEquals("weights make the bands as wide as they are", state("minecraft:stone"),
                world.getBlock(0, 64, 0));
        checkEquals("three of dirt for one of stone", state("minecraft:dirt"), world.getBlock(3, 64, 0));
        checkEquals("then stone again", state("minecraft:stone"), world.getBlock(4, 64, 0));
        check("a single block is itself", answer(actor, "//set #linear[stone]").contains("Set:"));
        check("anything else is refused",
                answer(actor, "//set #linear[#existing]").contains("Only a list of blocks"));
    }

    private static void tagPatterns() {
        TestActor actor = actor("PatternTags");
        TestWorld world = (TestWorld) actor.world();
        answer(actor, "//pos1 0,64,0");
        answer(actor, "//pos2 3,64,3");
        answer(actor, "//set ##wool");
        check("##wool is a wool block", BlockState.registry().name(world.getBlock(1, 64, 1)).endsWith("_wool"));
        answer(actor, "//set ##logs");
        check("a category is a block of it", BlockState.registry().name(world.getBlock(1, 64, 1)).endsWith("_log"));
        check("an unknown tag is refused", answer(actor, "//set ##nope").contains("Unknown block tag 'nope'"));
    }

    /**
     * FAWE's colour patterns take a colour as [r][g][b][a], which gave white;
     * #averagecolor and #anglecolor were that same fixed colour; #desaturate
     * took its percent for a fraction; #typeswap swapped a fixed list of blocks
     * and took no argument.
     */
    private static void colourAndSwapPatterns() {
        TestActor actor = actor("PatternColours");
        TestWorld world = (TestWorld) actor.world();
        answer(actor, "//pos1 0,63,0");
        answer(actor, "//pos2 1,63,0");
        answer(actor, "//set #color[255][0][0][255]");
        checkEquals("#color[r][g][b][a] is the block nearest the colour", state("minecraft:redstone_block"),
                world.getBlock(0, 63, 0));
        answer(actor, "//set #color[0,0,255]");
        checkEquals("so is the older #color[r,g,b]", state("minecraft:lapis_block"), world.getBlock(0, 63, 0));
        check("a colour by name is refused", answer(actor, "//set #color[red]").contains("#color[r][g][b][a]"));
        answer(actor, "//set white_wool");
        answer(actor, "//set #averagecolor[0][0][0][255]");
        checkEquals("#averagecolor averages the block's colour with its own, white and black being grey",
                state("minecraft:cobblestone"), world.getBlock(0, 63, 0));
        answer(actor, "//set white_wool");
        answer(actor, "//set #saturate[255][0][0][255]");
        checkEquals("#saturate multiplies it, white by red being red", state("minecraft:redstone_block"),
                world.getBlock(0, 63, 0));
        answer(actor, "//set red_wool");
        answer(actor, "//set #desaturate[100]");
        checkEquals("#desaturate[100] is the grey of the colour", state("minecraft:gray_wool"),
                world.getBlock(0, 63, 0));
        answer(actor, "//set stone");
        check("#anglecolor on flat ground changes nothing", answer(actor, "//set #anglecolor[2]").contains("Set: 0"));
        world.setBlock(0, 63, 0, state("minecraft:spruce_log"));
        world.setBlock(1, 63, 0, state("minecraft:stone"));
        answer(actor, "//set #typeswap[spruce][oak]");
        checkEquals("#typeswap[spruce][oak] makes the spruce oak", state("minecraft:oak_log"),
                world.getBlock(0, 63, 0));
        checkEquals("and leaves what has no spruce in its name", state("minecraft:stone"), world.getBlock(1, 63, 0));
        check("it needs its input and its output",
                answer(actor, "//set #ts[spruce]").contains("#ts[input][output]"));
    }

    /**
     * WorldEdit's #clipboard repeats the clipboard across the world; it read
     * the clipboard at the coordinates of the world, air far from its middle.
     * #relative is the pattern from where the edit starts; it added the
     * placement position to every position.
     */
    private static void clipboardPatterns() {
        TestActor actor = actor("PatternClipboard");
        TestWorld world = (TestWorld) actor.world();
        world.setBlock(0, 70, 0, state("minecraft:stone"));
        world.setBlock(1, 70, 0, state("minecraft:dirt"));
        answer(actor, "//pos1 0,70,0");
        answer(actor, "//pos2 1,70,0");
        answer(actor, "//copy");
        answer(actor, "//pos1 101,80,40");
        answer(actor, "//pos2 102,80,40");
        answer(actor, "//set #clipboard");
        checkEquals("#clipboard repeats the clipboard, far from where it was copied too", state("minecraft:dirt"),
                world.getBlock(101, 80, 40));
        checkEquals("block for block", state("minecraft:stone"), world.getBlock(102, 80, 40));
        answer(actor, "//set #copy@[1,0,0]");
        checkEquals("@[x,y,z] shifts it", state("minecraft:stone"), world.getBlock(101, 80, 40));
        check("an offset of two numbers is refused",
                answer(actor, "//set #clipboard@[1,0]").contains("#clipboard@[x,y,z]"));
        answer(actor, "//set #relative[#clipboard]");
        checkEquals("#relative starts the pattern at the first block of the edit", state("minecraft:stone"),
                world.getBlock(101, 80, 40));
        checkEquals("and goes on from there", state("minecraft:dirt"), world.getBlock(102, 80, 40));
    }

    private static void typeAndStatePatterns() {
        TestWorld world = new TestWorld("PatternStates");
        TestActor actor = new TestActor("PatternStates", world, new BlockVector3(0, 70, 0));
        BlockStateRegistry registry = BlockState.registry();
        world.setBlock(0, 60, 0, registry.parse("minecraft:oak_log[axis=x]"));
        world.setBlock(1, 60, 0, registry.parse("minecraft:oak_stairs[facing=east,half=bottom]"));
        answer(actor, "//pos1 0,60,0");
        answer(actor, "//pos2 0,60,0");
        answer(actor, "//set ^[axis=z]");
        checkEquals("^[axis=z] turns the log it keeps", registry.parse("minecraft:oak_log[axis=z]"),
                world.getBlock(0, 60, 0));
        answer(actor, "//pos1 1,60,0");
        answer(actor, "//pos2 1,60,0");
        answer(actor, "//set ^[half=top]");
        checkEquals("and keeps the properties it does not name",
                registry.parse("minecraft:oak_stairs[facing=east,half=top]"), world.getBlock(1, 60, 0));
        world.setBlock(2, 60, 0, registry.parse("minecraft:oak_log[axis=x]"));
        world.setBlock(2, 59, 0, registry.parse("minecraft:oak_log[axis=y]"));
        answer(actor, "//pos1 2,60,0");
        answer(actor, "//pos2 2,60,0");
        answer(actor, "//set ^oak_log");
        checkEquals("^oak_log keeps the axis of the block it replaces, not the one below",
                registry.parse("minecraft:oak_log[axis=x]"), world.getBlock(2, 60, 0));
        world.setBlock(3, 60, 0, registry.parse("minecraft:oak_stairs[facing=west,half=bottom]"));
        answer(actor, "//pos1 3,60,0");
        answer(actor, "//pos2 3,60,0");
        answer(actor, "//set ^oak_log[axis=y]");
        checkEquals("^type[states] applies both", registry.parse("minecraft:oak_log[axis=y]"),
                world.getBlock(3, 60, 0));
        check("a state without a value is refused",
                answer(actor, "//set ^[axis]").contains("Expected property=value"));
    }
}
