package com.maxlananas.fawebim.core.test;

import com.maxlananas.fawebim.core.clipboard.BlockArrayClipboard;
import com.maxlananas.fawebim.core.clipboard.Clipboards;
import com.maxlananas.fawebim.core.extent.EditSession;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.math.Vector3;
import com.maxlananas.fawebim.core.transform.Axis;
import com.maxlananas.fawebim.core.transform.BlockStateTransform;
import com.maxlananas.fawebim.core.transform.Transform;
import com.maxlananas.fawebim.core.transform.Transforms;
import com.maxlananas.fawebim.core.world.BlockState;
import com.maxlananas.fawebim.core.world.BlockStateRegistry;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.maxlananas.fawebim.core.test.SelfTestMain.check;
import static com.maxlananas.fawebim.core.test.SelfTestMain.checkEquals;
import static com.maxlananas.fawebim.core.test.SelfTestMain.section;

/**
 * Rotating and mirroring a clipboard: every block lands on a position of its
 * own, and its state turns with it.
 */
final class TransformTests {

    private TransformTests() {
    }

    static void run() {
        section("transforms");
        quarterTurnsAreExact();
        globalTransformsApplyToEdits();
        BlockStateRegistry previous = BlockState.registry();
        PropertyTestRegistry registry = new PropertyTestRegistry();
        BlockState.setRegistry(registry);
        try {
            statesTurnWithTheBlocks();
            everyTransformIsUndoneByItsInverse(registry);
            composingTransformsComposesTheirStates(registry);
            aRotatedPasteTurnsItsStairs();
        } finally {
            BlockState.setRegistry(previous);
        }
    }

    /**
     * //gtransform and the brush transform were stored and never applied: an
     * edit ignored them. Every block an edit writes now goes through the
     * transform, around the first block it writes, as in FAWE.
     */
    private static void globalTransformsApplyToEdits() {
        TestWorld world = new TestWorld("GlobalTransform");
        world.fillFlat(70);
        TestActor actor = new TestActor("GlobalTransform", world, new BlockVector3(0, 71, 0));
        int stone = BlockState.registry().defaultState("minecraft:stone");
        int air = BlockState.registry().air();
        com.maxlananas.fawebim.core.command.CommandManager manager = com.maxlananas.fawebim.core.command.CommandManager.get();
        manager.dispatch(actor, "//pos1 0,80,0");
        manager.dispatch(actor, "//pos2 2,80,0");
        manager.dispatch(actor, "//gtransform offset 0 5 0");
        manager.dispatch(actor, "//set minecraft:stone");
        check("//gtransform offset moves what //set writes", world.getBlock(1, 85, 0) == stone
                && world.getBlock(1, 80, 0) == air);
        manager.dispatch(actor, "//undo");
        check("and //undo takes it back from where it went", world.getBlock(1, 85, 0) == air);

        manager.dispatch(actor, "//gtransform rotate 90");
        manager.dispatch(actor, "//set minecraft:stone");
        check("//gtransform rotate turns the edit around the first block it writes (a row along x becomes one along z)",
                world.getBlock(0, 80, 0) == stone && world.getBlock(0, 80, -2) == stone
                        && world.getBlock(2, 80, 0) == air);
        manager.dispatch(actor, "//undo");

        manager.dispatch(actor, "//pos1 0,90,0");
        manager.dispatch(actor, "//pos2 0,90,0");
        manager.dispatch(actor, "//gtransform scale 2");
        manager.dispatch(actor, "//set minecraft:stone");
        int filled = 0;
        for (int x = 0; x <= 1; x++) {
            for (int y = 90; y <= 91; y++) {
                for (int z = 0; z <= 1; z++) {
                    filled += world.getBlock(x, y, z) == stone ? 1 : 0;
                }
            }
        }
        checkEquals("//gtransform scale 2 makes one block a 2x2x2 cube, with no gap", 8, filled);
        manager.dispatch(actor, "//gtransform");
        manager.dispatch(actor, "//pos1 5,95,5");
        manager.dispatch(actor, "//pos2 5,95,5");
        manager.dispatch(actor, "//set minecraft:stone");
        check("//gtransform with nothing clears it", world.getBlock(5, 95, 5) == stone);

        // A brush's transform turns around where the brush hits.
        EditSession brush = new EditSession(world, actor.session(), "brush");
        try {
            brush.setTransform(Transforms.offset(0, 3, 0), new BlockVector3(10, 100, 10));
            brush.setBlock(10, 100, 10, stone);
        } finally {
            brush.close();
        }
        check("a transform given an origin applies around it", world.getBlock(10, 103, 10) == stone
                && world.getBlock(10, 100, 10) == air);
    }

    /** The eight turns and mirrors about Y a clipboard can take, and their inverses. */
    private record Case(String name, Transform forward, Transform inverse) {
    }

    /** The turns and mirrors that keep up and down on the vertical axis: what a clipboard usually takes. */
    private static List<Case> uprightCases() {
        BlockVector3 o = BlockVector3.ZERO;
        List<Case> cases = new ArrayList<>();
        for (int degrees : new int[]{90, 180, 270, -90}) {
            cases.add(new Case("rotate " + degrees, Transforms.rotate(o, Axis.Y, degrees),
                    Transforms.rotate(o, Axis.Y, -degrees)));
        }
        for (Axis axis : Axis.values()) {
            cases.add(new Case("flip " + axis, Transforms.flip(o, axis), Transforms.flip(o, axis)));
        }
        cases.add(new Case("rotate 90 then flip X",
                Transforms.rotate(o, Axis.Y, 90).combine(Transforms.flip(o, Axis.X)),
                Transforms.flip(o, Axis.X).combine(Transforms.rotate(o, Axis.Y, -90))));
        return cases;
    }

    /** Quarter turns that tip a build over. */
    private static List<Case> tippingCases() {
        BlockVector3 o = BlockVector3.ZERO;
        return List.of(
                new Case("rotate 90 about X", Transforms.rotate(o, Axis.X, 90), Transforms.rotate(o, Axis.X, -90)),
                new Case("rotate 90 about Z", Transforms.rotate(o, Axis.Z, 90), Transforms.rotate(o, Axis.Z, -90)));
    }

    private static List<Case> cases() {
        List<Case> cases = new ArrayList<>(uprightCases());
        cases.addAll(tippingCases());
        return cases;
    }

    /**
     * A quarter turn used cos(90) = 6e-17: a block at a negative offset went
     * to -1e-16 and was floored one block over, so a rotated paste put two
     * blocks in one place and left a hole beside it.
     */
    private static void quarterTurnsAreExact() {
        for (Case turn : cases()) {
            Set<BlockVector3> images = new HashSet<>();
            int inexact = 0;
            int notUndone = 0;
            for (int x = -4; x <= 4; x++) {
                for (int y = -4; y <= 4; y++) {
                    for (int z = -4; z <= 4; z++) {
                        Vector3 image = turn.forward().apply(new Vector3(x, y, z));
                        if (image.x() != Math.rint(image.x()) || image.y() != Math.rint(image.y())
                                || image.z() != Math.rint(image.z())) {
                            inexact++;
                        }
                        images.add(image.toBlockPoint());
                        if (!turn.inverse().apply(image).equals(new Vector3(x, y, z))) {
                            notUndone++;
                        }
                    }
                }
            }
            checkEquals(turn.name() + " maps whole blocks to whole blocks", 0, inexact);
            checkEquals(turn.name() + " puts every block on a position of its own", 729, images.size());
            checkEquals(turn.name() + " is undone by its inverse", 0, notUndone);
        }
    }

    private static int state(String description) {
        int state = BlockState.registry().parse(description);
        if (state < 0) {
            throw new IllegalArgumentException("Unknown state " + description);
        }
        return state;
    }

    private static void expect(String what, Transform transform, String from, String to) {
        BlockStateTransform states = BlockStateTransform.of(transform);
        int turned = states == null ? state(from) : states.apply(state(from));
        checkEquals(what, BlockState.registry().describe(state(to)), BlockState.registry().describe(turned));
    }

    private static void statesTurnWithTheBlocks() {
        BlockVector3 o = BlockVector3.ZERO;
        // What //rotate 90 builds: a clockwise quarter turn seen from above.
        Transform clockwise = Transforms.rotate(o, Axis.Y, -90);
        expect("stairs facing north face east after a clockwise turn", clockwise,
                "oak_stairs[facing=north,half=bottom,shape=inner_left,waterlogged=false]",
                "oak_stairs[facing=east,half=bottom,shape=inner_left,waterlogged=false]");
        expect("a log along x lies along z", clockwise, "oak_log[axis=x]", "oak_log[axis=z]");
        expect("a standing log stays standing", clockwise, "oak_log[axis=y]", "oak_log[axis=y]");
        expect("a fence connected north connects east", clockwise,
                "oak_fence[north=true,east=false,south=false,west=true]",
                "oak_fence[north=true,east=true,south=false,west=false]");
        expect("a wall keeps each side's height on the side it turned to", clockwise,
                "cobblestone_wall[up=true,north=tall,east=none,south=low,west=none]",
                "cobblestone_wall[up=true,north=none,east=tall,south=none,west=low]");
        expect("a sign facing south faces west", clockwise, "oak_sign[rotation=0,waterlogged=false]",
                "oak_sign[rotation=4,waterlogged=false]");
        expect("a sign on the diagonal stays on one", clockwise, "oak_sign[rotation=2,waterlogged=false]",
                "oak_sign[rotation=6,waterlogged=false]");
        expect("a straight rail turns", clockwise, "rail[shape=north_south]", "rail[shape=east_west]");
        expect("a rail curve turns", clockwise, "rail[shape=south_east]", "rail[shape=south_west]");
        expect("a rail slope turns", clockwise, "rail[shape=ascending_north]", "rail[shape=ascending_east]");
        expect("a jigsaw's front and top turn", clockwise, "jigsaw[orientation=north_up]", "jigsaw[orientation=east_up]");

        Transform mirrorX = Transforms.flip(o, Axis.X);
        expect("a mirror turns stairs round and swaps the side of their corner", mirrorX,
                "oak_stairs[facing=east,half=bottom,shape=outer_left,waterlogged=false]",
                "oak_stairs[facing=west,half=bottom,shape=outer_right,waterlogged=false]");
        expect("a mirror swaps a door's hinge", mirrorX, "oak_door[facing=north,half=lower,hinge=left,open=false]",
                "oak_door[facing=north,half=lower,hinge=right,open=false]");
        expect("a mirror swaps the halves of a double chest", mirrorX, "chest[facing=north,type=left]",
                "chest[facing=north,type=right]");
        expect("a mirror swaps a curve's side", mirrorX, "rail[shape=south_east]", "rail[shape=south_west]");
        expect("a mirrored sign faces the other way", mirrorX, "oak_sign[rotation=4,waterlogged=false]",
                "oak_sign[rotation=12,waterlogged=false]");

        Transform upsideDown = Transforms.flip(o, Axis.Y);
        expect("upside down, stairs hang from the top", upsideDown,
                "oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]",
                "oak_stairs[facing=north,half=top,shape=straight,waterlogged=false]");
        expect("upside down, a bottom slab is a top slab", upsideDown, "oak_slab[type=bottom,waterlogged=false]",
                "oak_slab[type=top,waterlogged=false]");
        expect("upside down, a lantern hangs", upsideDown, "lantern[hanging=false,waterlogged=false]",
                "lantern[hanging=true,waterlogged=false]");
        expect("upside down, a floor button is on the ceiling", upsideDown,
                "stone_button[face=floor,facing=east,powered=false]",
                "stone_button[face=ceiling,facing=east,powered=false]");
        expect("upside down, an observer facing up faces down", upsideDown, "observer[facing=up,powered=false]",
                "observer[facing=down,powered=false]");
        expect("a hopper cannot face up, so it keeps facing down", upsideDown, "hopper[facing=down,enabled=true]",
                "hopper[facing=down,enabled=true]");
        expect("upside down, a door's halves swap", upsideDown,
                "oak_door[facing=north,half=upper,hinge=left,open=false]",
                "oak_door[facing=north,half=lower,hinge=left,open=false]");
        expect("upside down, a mushroom block's faces swap", upsideDown,
                "brown_mushroom_block[down=false,east=false,north=false,south=false,up=true,west=false]",
                "brown_mushroom_block[down=true,east=false,north=false,south=false,up=false,west=false]");
        expect("a piston head's type is not a side", upsideDown, "piston_head[facing=up,type=sticky]",
                "piston_head[facing=down,type=sticky]");
        check("a transform that turns nothing changes no state",
                BlockStateTransform.of(Transforms.offset(3, 4, 5)) == null
                        && BlockStateTransform.of(Transforms.randomOffset(2, 2, 2)) == null
                        && BlockStateTransform.of(Transforms.rotate(o, Axis.Y, 360)) == null);
    }

    /**
     * Property-based: every state of every block goes through every upright
     * case and back. Tipping a build over cannot be undone for every block -
     * a sign standing on the ground has no state lying on its side - so those
     * cases go through the blocks that can point every way.
     */
    private static void everyTransformIsUndoneByItsInverse(PropertyTestRegistry registry) {
        roundTrips(registry, uprightCases(), registry.names());
        roundTrips(registry, tippingCases(), List.of("minecraft:observer", "minecraft:oak_log",
                "minecraft:piston_head", "minecraft:brown_mushroom_block", "minecraft:stone"));
    }

    private static void roundTrips(PropertyTestRegistry registry, List<Case> cases, List<String> blocks) {
        for (Case turn : cases) {
            BlockStateTransform forward = BlockStateTransform.of(turn.forward());
            BlockStateTransform inverse = BlockStateTransform.of(turn.inverse());
            List<String> broken = new ArrayList<>();
            int states = 0;
            for (String name : blocks) {
                for (int state : registry.statesOfBlock(name)) {
                    states++;
                    int back = inverse.apply(forward.apply(state));
                    if (back != state && broken.size() < 3) {
                        broken.add(registry.describe(state) + " -> " + registry.describe(forward.apply(state))
                                + " -> " + registry.describe(back));
                    }
                }
            }
            check(turn.name() + " then its inverse gives back each of the " + states + " states "
                    + (broken.isEmpty() ? "" : broken), broken.isEmpty());
        }
    }

    private static void composingTransformsComposesTheirStates(PropertyTestRegistry registry) {
        List<Case> cases = uprightCases();
        int mismatches = 0;
        String first = null;
        for (Case a : cases) {
            for (Case b : cases) {
                BlockStateTransform one = BlockStateTransform.of(a.forward());
                BlockStateTransform two = BlockStateTransform.of(b.forward());
                BlockStateTransform both = BlockStateTransform.of(a.forward().combine(b.forward()));
                for (String name : registry.names()) {
                    for (int state : registry.statesOfBlock(name)) {
                        int stepwise = two == null ? (one == null ? state : one.apply(state))
                                : two.apply(one == null ? state : one.apply(state));
                        int together = both == null ? state : both.apply(state);
                        if (stepwise != together) {
                            mismatches++;
                            if (first == null) {
                                first = a.name() + " + " + b.name() + " on " + registry.describe(state);
                            }
                        }
                    }
                }
            }
        }
        check("turning a state twice is turning it by the two transforms at once"
                + (first == null ? "" : " (" + first + ")"), mismatches == 0);
    }

    private static void aRotatedPasteTurnsItsStairs() {
        TestWorld world = new TestWorld("RotatedPaste");
        TestActor actor = new TestActor("RotatedPaste", world, new BlockVector3(0, 100, 0));
        BlockArrayClipboard clipboard = new BlockArrayClipboard(BlockVector3.ZERO);
        int stone = state("stone");
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                clipboard.setBlock(x, 0, z, stone);
            }
        }
        clipboard.setBlock(0, 1, -2, state("oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]"));
        clipboard.setOrigin(BlockVector3.ZERO);
        EditSession session = new EditSession(world, actor.session(), "paste");
        try {
            Clipboards.paste(clipboard, new BlockVector3(50, 64, 50), session,
                    Transforms.rotate(BlockVector3.ZERO, Axis.Y, -90), false, null, false, false, false, false);
        } finally {
            session.close();
        }
        int placed = 0;
        for (int x = 48; x <= 52; x++) {
            for (int z = 48; z <= 52; z++) {
                if (world.getBlock(x, 64, z) == stone) {
                    placed++;
                }
            }
        }
        checkEquals("a rotated 5x5 floor lands whole, with no hole and no block twice", 25, placed);
        checkEquals("the stairs at the north edge are at the east edge, facing east",
                "minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]",
                BlockState.registry().describe(world.getBlock(52, 65, 50)));

        // //paste -s selected the destination plus the size, one block too far
        // on each axis and blind to the origin and the rotation.
        clipboard.setOrigin(new BlockVector3(-2, 0, -2));
        BlockVector3[] bounds = Clipboards.pastedBounds(clipboard, new BlockVector3(10, 64, 10),
                Transforms.rotate(clipboard.getOrigin(), Axis.Y, -90));
        checkEquals("the pasted box starts where the rotated corner lands", new BlockVector3(6, 64, 10), bounds[0]);
        checkEquals("and holds the clipboard's size, not one more", new BlockVector3(10, 65, 14), bounds[1]);
    }
}
