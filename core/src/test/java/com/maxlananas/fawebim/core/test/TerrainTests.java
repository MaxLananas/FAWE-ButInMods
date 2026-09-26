package com.maxlananas.fawebim.core.test;

import com.maxlananas.fawebim.core.brush.Brushes;
import com.maxlananas.fawebim.core.command.CommandManager;
import com.maxlananas.fawebim.core.extent.EditSession;
import com.maxlananas.fawebim.core.function.Morphology;
import com.maxlananas.fawebim.core.function.Operations;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.region.CuboidRegion;
import com.maxlananas.fawebim.core.util.InputException;
import com.maxlananas.fawebim.core.world.BlockState;

import java.util.Random;
import java.util.function.IntPredicate;

import static com.maxlananas.fawebim.core.test.SelfTestMain.check;
import static com.maxlananas.fawebim.core.test.SelfTestMain.checkEquals;
import static com.maxlananas.fawebim.core.test.SelfTestMain.section;

/**
 * {@code //deform} reads the block at the position the expression leaves in
 * x, y and z, in WorldEdit's coordinates, and the morph brushes erode and fill
 * in passes that read one buffer and write the other.
 *
 * <p>{@code //deform} pushed each block to the position the expression gave,
 * counted from the corner of the selection after clearing it to air; the
 * morph brushes wrote into the blocks they were still reading, ignored their
 * arguments, and {@code /brush pull} moved blocks sideways.</p>
 */
final class TerrainTests {

    private TerrainTests() {
    }

    static void run() {
        section("deform and morph");
        deformReadsTheBlockTheExpressionPointsAt();
        deformWorksInTheUnitCubeByDefault();
        deformReadsEverySourceBeforeWriting();
        deformRefusesAnAreaPastTheMemoryBudget();
        morphologyPassesFollowTheirThresholds();
        morphologyIsTheSameWhateverTheWalkOrder();
        morphologyStopsWhenAPassChangesNothing();
        theStylesDisagreeOnTheSurfaceOfTheBall();
        pullFillsTheOpenFacesAboveTheTerrain();
        morphErodesAFloatingBlock();
    }

    private static TestActor actor(String name) {
        TestWorld world = new TestWorld(name);
        world.fillFlat(70);
        return new TestActor(name, world, new BlockVector3(0, 71, 0));
    }

    private static int state(String name) {
        return BlockState.registry().defaultState(name);
    }

    private static void deformReadsTheBlockTheExpressionPointsAt() {
        TestActor actor = actor("DeformRaw");
        TestWorld world = (TestWorld) actor.world();
        int gold = state("minecraft:gold_block");
        world.setBlock(0, 80, 0, gold);
        CommandManager.get().dispatch(actor, "//pos1 0,80,0");
        CommandManager.get().dispatch(actor, "//pos2 4,80,0");
        CommandManager.get().dispatch(actor, "//deform -r x-=1");
        // Every block takes the one to its west: the gold moves one east.
        check("x-=1 in the game's coordinates moves the blocks one east",
                world.getBlock(1, 80, 0) == gold && world.getBlock(0, 80, 0) == BlockState.registry().air());
        CommandManager.get().dispatch(actor, "//undo");
        check("and undoes", world.getBlock(0, 80, 0) == gold && world.getBlock(1, 80, 0) != gold);
    }

    private static void deformWorksInTheUnitCubeByDefault() {
        TestActor actor = actor("DeformUnit");
        TestWorld world = (TestWorld) actor.world();
        int gold = state("minecraft:gold_block");
        world.setBlock(8, 80, 0, gold);
        CommandManager.get().dispatch(actor, "//pos1 0,80,0");
        CommandManager.get().dispatch(actor, "//pos2 10,80,0");
        // The row spans -1 to 1: x 10 is 1, halved to 0.5, which is x 7.5 and
        // rounds to 8.
        CommandManager.get().dispatch(actor, "//deform x*=0.5");
        check("x*=0.5 stretches the row from its centre", world.getBlock(10, 80, 0) == gold);
        check("the source is read, not moved", world.getBlock(8, 80, 0) != gold);
        checkEquals("the frame of a row", new Operations.DeformFrame(new com.maxlananas.fawebim.core.math.Vector3(5, 80, 0),
                        new com.maxlananas.fawebim.core.math.Vector3(5, 1, 1)),
                Operations.DeformFrame.unitCube(new CuboidRegion(new BlockVector3(0, 80, 0),
                        new BlockVector3(10, 80, 0))));
    }

    private static void deformReadsEverySourceBeforeWriting() {
        TestActor actor = actor("DeformOrder");
        TestWorld world = (TestWorld) actor.world();
        String[] column = {"minecraft:gold_block", "minecraft:iron_block", "minecraft:diamond_block",
            "minecraft:emerald_block", "minecraft:lapis_block"};
        for (int i = 0; i < column.length; i++) {
            world.setBlock(0, 80 + i, 0, state(column[i]));
        }
        CommandManager.get().dispatch(actor, "//pos1 0,80,0");
        CommandManager.get().dispatch(actor, "//pos2 0,84,0");
        CommandManager.get().dispatch(actor, "//deform -r y+=1");
        boolean shifted = true;
        for (int i = 0; i < column.length - 1; i++) {
            shifted &= world.getBlock(0, 80 + i, 0) == state(column[i + 1]);
        }
        check("each block takes the one above as it was, not as the walk left it",
                shifted && world.getBlock(0, 84, 0) == BlockState.registry().air());
    }

    private static void deformRefusesAnAreaPastTheMemoryBudget() {
        TestActor actor = actor("DeformHuge");
        TestWorld world = (TestWorld) actor.world();
        EditSession edit = new EditSession(world, actor.session(), "deform");
        boolean refused = false;
        try {
            Operations.deform(world, edit, new CuboidRegion(new BlockVector3(-2_000_000, -64, -2_000_000),
                    new BlockVector3(2_000_000, 319, 2_000_000)), "y-=1", Operations.DeformFrame.RAW);
        } catch (InputException e) {
            refused = e.getMessage().contains("more than");
        } finally {
            edit.close();
        }
        check("a deform of trillions of blocks is refused before anything is read", refused);
        checkEquals("and nothing changed", 0L, edit.getBlocksChanged());
    }

    /** Open is 0, every other value is a block. */
    private static final IntPredicate AIR = state -> state == 0;

    private static int[] cube(int side) {
        return new int[side * side * side];
    }

    private static int at(int side, int x, int y, int z) {
        return (y * side + z) * side + x;
    }

    private static void morphologyPassesFollowTheirThresholds() {
        int side = 7;
        int c = side / 2;
        int[] lone = cube(side);
        lone[at(side, c, c, c)] = 1;
        int[] eroded = Morphology.run(lone, side, 2, Morphology.Style.MORPH, new Morphology.Passes(6, 1, 7, 0), AIR);
        checkEquals("a block with six open faces erodes at six", 0, eroded[at(side, c, c, c)]);
        lone = cube(side);
        lone[at(side, c, c, c)] = 1;
        int[] kept = Morphology.run(lone, side, 2, Morphology.Style.MORPH, new Morphology.Passes(7, 1, 7, 0), AIR);
        checkEquals("and stays when seven are asked", 1, kept[at(side, c, c, c)]);

        // An open cell under three blocks of 2 and two of 3: filled with the most common.
        int[] hole = cube(side);
        hole[at(side, c, c, c - 1)] = 2;
        hole[at(side, c, c, c + 1)] = 3;
        hole[at(side, c + 1, c, c)] = 2;
        hole[at(side, c - 1, c, c)] = 3;
        hole[at(side, c, c + 1, c)] = 2;
        int[] filled = Morphology.run(hole, side, 2, Morphology.Style.MORPH, new Morphology.Passes(7, 0, 5, 1), AIR);
        checkEquals("an open cell with five closed faces is filled with the most common", 2,
                filled[at(side, c, c, c)]);
        int[] tie = cube(side);
        tie[at(side, c, c, c - 1)] = 3;
        tie[at(side, c, c, c + 1)] = 2;
        int[] tied = Morphology.run(tie, side, 2, Morphology.Style.MORPH, new Morphology.Passes(7, 0, 2, 1), AIR);
        checkEquals("a tie goes to the face met first, north", 3, tied[at(side, c, c, c)]);
    }

    /**
     * With one kind of block and one kind of air the answer of a pass is unique,
     * so mirroring the input mirrors the output unless a pass reads what it
     * wrote, which depends on the direction of the walk.
     */
    private static void morphologyIsTheSameWhateverTheWalkOrder() {
        Random random = new Random(2024);
        int side = 11;
        boolean equivariant = true;
        for (int round = 0; round < 40 && equivariant; round++) {
            int[] grid = cube(side);
            for (int i = 0; i < grid.length; i++) {
                grid[i] = random.nextInt(100) < 45 ? 1 : 0;
            }
            Morphology.Passes passes = new Morphology.Passes(1 + random.nextInt(5), 1 + random.nextInt(3),
                    1 + random.nextInt(5), 1 + random.nextInt(3));
            Morphology.Style style = random.nextBoolean() ? Morphology.Style.MORPH : Morphology.Style.ERODE;
            int[] mirrored = mirrorX(grid, side);
            int[] direct = Morphology.run(grid.clone(), side, 4, style, passes, AIR);
            int[] throughMirror = mirrorX(Morphology.run(mirrored, side, 4, style, passes, AIR), side);
            equivariant = java.util.Arrays.equals(direct, throughMirror);
        }
        check("the passes commute with a mirror, so no pass reads its own writes", equivariant);
    }

    private static int[] mirrorX(int[] grid, int side) {
        int[] mirrored = new int[grid.length];
        for (int y = 0; y < side; y++) {
            for (int z = 0; z < side; z++) {
                for (int x = 0; x < side; x++) {
                    mirrored[at(side, side - 1 - x, y, z)] = grid[at(side, x, y, z)];
                }
            }
        }
        return mirrored;
    }

    private static void morphologyStopsWhenAPassChangesNothing() {
        int side = 2 * 20 + 3;
        int[] grid = cube(side);
        long started = System.nanoTime();
        Morphology.run(grid, side, 20, Morphology.Style.MORPH,
                new Morphology.Passes(1, Integer.MAX_VALUE, 1, Integer.MAX_VALUE), AIR);
        check("two billion passes over air end at the first that changes nothing",
                System.nanoTime() - started < 5_000_000_000L);
    }

    private static void theStylesDisagreeOnTheSurfaceOfTheBall() {
        int side = 7;
        int c = side / 2;
        // A lone block two cells east of the centre of a ball of radius 2.
        int[] grid = cube(side);
        grid[at(side, c + 2, c, c)] = 1;
        int[] morph = Morphology.run(grid.clone(), side, 2, Morphology.Style.MORPH,
                new Morphology.Passes(1, 1, 7, 0), AIR);
        int[] erode = Morphology.run(grid.clone(), side, 2, Morphology.Style.ERODE,
                new Morphology.Passes(1, 1, 7, 0), AIR);
        check("WorldEdit's ball holds the cells at the radius",  morph[at(side, c + 2, c, c)] == 0);
        check("FAWE's stops short of them", erode[at(side, c + 2, c, c)] == 1);
    }

    private static void pullFillsTheOpenFacesAboveTheTerrain() {
        TestActor actor = actor("Pull");
        TestWorld world = (TestWorld) actor.world();
        Brushes.MorphBrush pull = new Brushes.MorphBrush(3, Morphology.Style.ERODE,
                new Morphology.Passes(6, 0, 1, 1), null);
        EditSession edit = new EditSession(world, actor.session(), "brush");
        int changed;
        try {
            changed = pull.apply(edit, new BlockVector3(0, 69, 0), actor);
        } finally {
            edit.close();
        }
        // The layer above the grass inside the ball: dx² + dz² < 9 - 1.
        checkEquals("pull fills the open cells touching the terrain, one layer", 21, changed);
        check("with the block they touch", world.getBlock(0, 70, 0) == state("minecraft:grass_block")
                && world.getBlock(0, 71, 0) == BlockState.registry().air());
    }

    private static void morphErodesAFloatingBlock() {
        TestActor actor = actor("Morph");
        TestWorld world = (TestWorld) actor.world();
        world.setBlock(0, 100, 0, state("minecraft:stone"));
        Brushes.MorphBrush morph = new Brushes.MorphBrush(5, Morphology.Style.MORPH,
                new Morphology.Passes(3, 1, 3, 1), null);
        EditSession edit = new EditSession(world, actor.session(), "brush");
        int changed;
        try {
            changed = morph.apply(edit, new BlockVector3(0, 100, 0), actor);
        } finally {
            edit.close();
        }
        checkEquals("morph erodes a block floating in the air", 1, changed);
        check("to air", world.getBlock(0, 100, 0) == BlockState.registry().air());
    }
}
