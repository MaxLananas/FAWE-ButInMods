package com.maxlananas.fawebim.core.test;

import com.maxlananas.fawebim.core.command.CommandManager;
import com.maxlananas.fawebim.core.command.Directions;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.math.Vector3;
import com.maxlananas.fawebim.core.region.Region;
import com.maxlananas.fawebim.core.util.InputException;
import com.maxlananas.fawebim.core.util.NbtCompound;
import com.maxlananas.fawebim.core.world.EntityData;

import java.util.List;

import static com.maxlananas.fawebim.core.test.ClipboardSectionTests.actor;
import static com.maxlananas.fawebim.core.test.ClipboardSectionTests.answer;
import static com.maxlananas.fawebim.core.test.ClipboardSectionTests.differences;
import static com.maxlananas.fawebim.core.test.ClipboardSectionTests.fill;
import static com.maxlananas.fawebim.core.test.ClipboardSectionTests.pattern;
import static com.maxlananas.fawebim.core.test.ClipboardSectionTests.plain;
import static com.maxlananas.fawebim.core.test.ClipboardSectionTests.registry;
import static com.maxlananas.fawebim.core.test.ClipboardSectionTests.state;
import static com.maxlananas.fawebim.core.test.SelfTestMain.check;
import static com.maxlananas.fawebim.core.test.SelfTestMain.checkEquals;
import static com.maxlananas.fawebim.core.test.SelfTestMain.section;

/**
 * {@code //stack} and {@code //move}, and the direction words WorldEdit reads.
 *
 * <p>Stack wrote air over the corners of a sphere's box, dropped the data of
 * chests and ignored {@code -e} and {@code -b}; move filled the destination's
 * air with the pattern meant for the place the blocks left; an unknown
 * direction moved the blocks up, and {@code //flip left} was refused.</p>
 */
final class RegionCopyTests {

    private RegionCopyTests() {
    }

    static void run() {
        section("region copies and directions");
        stackRepeatsTheSelectionWhereTheOffsetSays();
        stackCopiesOnlyTheRegionsBlocks();
        stackBringsDataEntitiesAndBiomes();
        stackMasksAndAir();
        stackRefusesWhatWorldEditRefuses();
        moveOverItsOwnSelection();
        moveLeavesThePatternBehind();
        moveCarriesDataAndEntities();
        directionsReadLikeWorldEdit();
        flipAndExpandTakeRelativeDirections();
    }

    private static void run(TestActor actor, String line) {
        CommandManager.get().dispatch(actor, line);
    }

    private static void stackRepeatsTheSelectionWhereTheOffsetSays() {
        TestActor actor = actor("stack-offsets");
        TestWorld world = (TestWorld) actor.world();
        fill(world, 2, 71, 3, 9, 74, 12);
        run(actor, "//pos1 2,71,3");
        run(actor, "//pos2 9,74,12");
        // The actor looks east: the default offset is forward.
        run(actor, "//stack 3");
        long wrong = 0;
        for (int copy = 1; copy <= 3; copy++) {
            wrong += differences(world, 2, 71, 3, 9, 74, 12, 8 * copy, 0, 0);
        }
        checkEquals("//stack 3 repeats the selection three times forward", 0L, wrong);
        check("and no further", plain(world.getBlock(34, 71, 3)) == registry().air()
                || plain(world.getBlock(34, 71, 3)) == plain(pattern(34, 71, 3)));

        run(actor, "//stack 2 up");
        checkEquals("//stack 2 up steps by the height", 0L,
                differences(world, 2, 71, 3, 9, 74, 12, 0, 4, 0) + differences(world, 2, 71, 3, 9, 74, 12, 0, 8, 0));

        run(actor, "//stack back");
        checkEquals("//stack back, without a count, makes one copy behind", 0L,
                differences(world, 2, 71, 3, 9, 74, 12, -8, 0, 0));

        run(actor, "//stack 1 nw");
        checkEquals("a diagonal steps along both axes", 0L, differences(world, 2, 71, 3, 9, 74, 12, -8, 0, -10));

        run(actor, "//stack 2 0,6,0 -r");
        checkEquals("-r steps by the offset itself", 0L,
                differences(world, 2, 71, 3, 9, 74, 12, 0, 6, 0) + differences(world, 2, 71, 3, 9, 74, 12, 0, 12, 0));

        actor.setPitch(80);
        run(actor, "//stack 1");
        actor.setPitch(0);
        checkEquals("looking down stacks downwards", 0L, differences(world, 2, 71, 3, 9, 74, 12, 0, -4, 0));

        run(actor, "//stack 1 south -s");
        BlockVector3 min = actor.session().getSelection(world).getMinimumPoint();
        checkEquals("-s moves the selection onto the last copy", new BlockVector3(2, 71, 13), min);
    }

    /** A cylinder's copies leave the corners of its box as they were. */
    private static void stackCopiesOnlyTheRegionsBlocks() {
        TestActor actor = actor("stack-shape");
        TestWorld world = (TestWorld) actor.world();
        int stone = state("minecraft:stone");
        int gold = state("minecraft:gold_block");
        for (int y = 71; y <= 74; y++) {
            for (int z = 17; z <= 23; z++) {
                for (int x = 17; x <= 23; x++) {
                    world.setBlock(x, y, z, stone);
                    world.setBlock(x + 7, y, z, gold);
                }
            }
        }
        run(actor, "//sel cyl");
        run(actor, "//pos1 20,71,20");
        run(actor, "//pos2 23,71,20");
        run(actor, "//pos2 20,74,23");
        Region region = actor.session().getSelection(world);
        checkEquals("the cylinder's box is 7 wide", 7, region.getWidth());
        check("its corners are outside it", !region.contains(17, 71, 17));
        run(actor, "//stack 1 east");
        checkEquals("the copy's corner keeps what it held", gold, world.getBlock(24, 71, 17));
        checkEquals("the copy's middle is the cylinder's", stone, world.getBlock(27, 72, 20));
        checkEquals("the copy's rim is the cylinder's", stone, world.getBlock(30, 73, 20));
    }

    private static void stackBringsDataEntitiesAndBiomes() {
        TestActor actor = actor("stack-data");
        TestWorld world = (TestWorld) actor.world();
        int chest = state("minecraft:chest");
        world.setBlock(2, 71, 3, chest);
        NbtCompound item = new NbtCompound().putByte("Slot", 0).putString("id", "minecraft:diamond")
                .putInt("count", 3);
        world.applyBlockEntity(2, 71, 3, new NbtCompound().putList("Items", List.of(item)));
        world.addEntity(new EntityData("minecraft:pig", new NbtCompound(), new Vector3(4.5, 71, 5.5)));
        world.addEntity(new EntityData("minecraft:painting",
                new NbtCompound().putIntArray("block_pos", new int[]{3, 72, 3}), new Vector3(3.5, 72.5, 3.03)));
        for (int x = 0; x < 8; x++) {
            for (int z = 0; z < 12; z++) {
                world.setBiome(x, 72, z, 7);
            }
        }
        run(actor, "//pos1 0,71,0");
        run(actor, "//pos2 7,74,11");
        run(actor, "//stack 2 east -e -b");
        for (int copy = 1; copy <= 2; copy++) {
            NbtCompound data = world.getBlockEntity(2 + 8 * copy, 71, 3);
            check("copy " + copy + " of the chest has its items", data != null
                    && data.getCompoundList("Items").size() == 1
                    && data.getCompoundList("Items").get(0).getString("id", "").equals("minecraft:diamond"));
            double pigX = 4.5 + 8 * copy;
            check("copy " + copy + " of the pig stands where the pig does", world.entityList().stream()
                    .anyMatch(entity -> entity.type().equals("minecraft:pig")
                            && Math.abs(entity.position().x() - pigX) < 1e-9));
            int wallX = 3 + 8 * copy;
            check("copy " + copy + " of the painting hangs on the copied wall", world.entityList().stream()
                    .anyMatch(entity -> entity.type().equals("minecraft:painting")
                            && entity.nbt().get("block_pos") instanceof int[] at && at[0] == wallX));
            checkEquals("copy " + copy + " has the biome", 7, world.getBiome(8 * copy, 72, 4));
        }
        checkEquals("the source keeps its entities", 2L, world.entityList().stream()
                .filter(entity -> entity.position().x() < 8).count());
    }

    private static void stackMasksAndAir() {
        TestActor actor = actor("stack-masks");
        TestWorld world = (TestWorld) actor.world();
        int gold = state("minecraft:gold_block");
        int stone = state("minecraft:stone");
        int dirt = state("minecraft:dirt");
        // A row of stone, air and dirt, stacked onto a row of gold.
        world.setBlock(0, 80, 0, stone);
        world.setBlock(1, 80, 0, registry().air());
        world.setBlock(2, 80, 0, dirt);
        for (int x = 0; x < 3; x++) {
            world.setBlock(x, 81, 0, gold);
            world.setBlock(x, 82, 0, gold);
            world.setBlock(x, 83, 0, gold);
        }
        run(actor, "//pos1 0,80,0");
        run(actor, "//pos2 2,80,0");
        run(actor, "//stack 1 up -a");
        checkEquals("-a leaves the destination where the source is air", gold, world.getBlock(1, 81, 0));
        checkEquals("and copies the rest", stone, world.getBlock(0, 81, 0));
        run(actor, "//stack 1 0,2,0 -r -m stone");
        checkEquals("-m copies what the mask accepts", stone, world.getBlock(0, 82, 0));
        checkEquals("and leaves the others", gold, world.getBlock(2, 82, 0));
        checkEquals("air the mask refuses is not copied either", gold, world.getBlock(1, 82, 0));
        run(actor, "//stack 1 0,3,0 -r");
        check("without -a the air is copied", registry().isAirLike(world.getBlock(1, 83, 0)));
    }

    private static void stackRefusesWhatWorldEditRefuses() {
        TestActor actor = actor("stack-refusals");
        run(actor, "//pos1 0,71,0");
        run(actor, "//pos2 7,74,7");
        check("a step inside the selection is refused",
                answer(actor, "//stack 1 1,0,0 -r").contains("inside the selection"));
        check("a count below one is refused", answer(actor, "//stack 0").contains("at least 1"));
        check("an unknown direction is refused", answer(actor, "//stack 1 sideways").contains("Unknown direction"));
        check("an offset with two numbers is refused", answer(actor, "//stack 1 1,2").contains("three numbers"));
        check("a zero offset is refused", answer(actor, "//stack 1 0,0,0").contains("inside the selection"));
        check("//move refuses a zero offset", answer(actor, "//move 1 0,0,0").contains("moves nothing"));
    }

    /** A move by less than the selection's size overlaps it: every block still lands. */
    private static void moveOverItsOwnSelection() {
        TestActor actor = actor("move-overlap");
        TestWorld world = (TestWorld) actor.world();
        fill(world, 2, 71, 3, 9, 74, 12);
        int[] before = new int[8 * 4 * 10];
        int at = 0;
        for (int y = 71; y <= 74; y++) {
            for (int z = 3; z <= 12; z++) {
                for (int x = 2; x <= 9; x++) {
                    before[at++] = plain(world.getBlock(x, y, z));
                }
            }
        }
        run(actor, "//pos1 2,71,3");
        run(actor, "//pos2 9,74,12");
        run(actor, "//move 1 east -s");
        long wrong = 0;
        at = 0;
        for (int y = 71; y <= 74; y++) {
            for (int z = 3; z <= 12; z++) {
                for (int x = 2; x <= 9; x++) {
                    if (plain(world.getBlock(x + 1, y, z)) != before[at++]) {
                        wrong++;
                    }
                }
            }
        }
        checkEquals("a move by one keeps every block", 0L, wrong);
        long left = 0;
        for (int y = 71; y <= 74; y++) {
            for (int z = 3; z <= 12; z++) {
                if (!registry().isAirLike(world.getBlock(2, y, z))) {
                    left++;
                }
            }
        }
        checkEquals("and leaves air where nothing moved in", 0L, left);

        run(actor, "//move 3 west");
        at = 0;
        wrong = 0;
        for (int y = 71; y <= 74; y++) {
            for (int z = 3; z <= 12; z++) {
                for (int x = 2; x <= 9; x++) {
                    if (plain(world.getBlock(x - 2, y, z)) != before[at++]) {
                        wrong++;
                    }
                }
            }
        }
        checkEquals("a move back the other way keeps them too", 0L, wrong);
    }

    private static void moveLeavesThePatternBehind() {
        TestActor actor = actor("move-leave");
        TestWorld world = (TestWorld) actor.world();
        fill(world, 2, 71, 3, 5, 74, 6);
        long filled = 0;
        for (int y = 71; y <= 74; y++) {
            for (int z = 3; z <= 6; z++) {
                for (int x = 2; x <= 5; x++) {
                    world.setBlock(x, y + 10, z, state("minecraft:oak_planks"));
                    if (!registry().isAirLike(world.getBlock(x, y, z))) {
                        filled++;
                    }
                }
            }
        }
        check("the pattern has blocks and air", filled > 0 && filled < 64);
        run(actor, "//pos1 2,71,3");
        run(actor, "//pos2 5,74,6");
        run(actor, "//move 5 up glass");
        int glass = state("minecraft:glass");
        long notGlass = 0;
        for (int y = 71; y <= 74; y++) {
            for (int z = 3; z <= 6; z++) {
                for (int x = 2; x <= 5; x++) {
                    if (world.getBlock(x, y, z) != glass) {
                        notGlass++;
                    }
                }
            }
        }
        checkEquals("the place the blocks left is the pattern", 0L, notGlass);
        long wrong = 0;
        for (int y = 71; y <= 74; y++) {
            for (int z = 3; z <= 6; z++) {
                for (int x = 2; x <= 5; x++) {
                    if (plain(world.getBlock(x, y + 5, z)) != plain(pattern(x, y, z))) {
                        wrong++;
                    }
                }
            }
        }
        checkEquals("the moved blocks are the selection's, its air included", 0L, wrong);

        // A row of stone, air and dirt moved up into a row of gold.
        int gold = state("minecraft:gold_block");
        world.setBlock(0, 90, 0, state("minecraft:stone"));
        world.setBlock(1, 90, 0, registry().air());
        world.setBlock(2, 90, 0, state("minecraft:dirt"));
        for (int x = 0; x < 3; x++) {
            world.setBlock(x, 91, 0, gold);
        }
        run(actor, "//pos1 0,90,0");
        run(actor, "//pos2 2,90,0");
        run(actor, "//move up -a");
        checkEquals("-a keeps the destination where the source is air", gold, world.getBlock(1, 91, 0));
        checkEquals("and moves the blocks", state("minecraft:dirt"), world.getBlock(2, 91, 0));
        check("which leave air", registry().isAirLike(world.getBlock(2, 90, 0)));
        world.setBlock(0, 95, 0, state("minecraft:stone"));
        world.setBlock(1, 95, 0, registry().air());
        for (int x = 0; x < 2; x++) {
            world.setBlock(x, 96, 0, gold);
        }
        run(actor, "//pos1 0,95,0");
        run(actor, "//pos2 1,95,0");
        run(actor, "//move up");
        check("without -a the air moves too", registry().isAirLike(world.getBlock(1, 96, 0)));
    }

    private static void moveCarriesDataAndEntities() {
        TestActor actor = actor("move-data");
        TestWorld world = (TestWorld) actor.world();
        world.setBlock(2, 71, 3, state("minecraft:chest"));
        NbtCompound item = new NbtCompound().putByte("Slot", 0).putString("id", "minecraft:emerald")
                .putInt("count", 5);
        world.applyBlockEntity(2, 71, 3, new NbtCompound().putList("Items", List.of(item)));
        world.addEntity(new EntityData("minecraft:cow", new NbtCompound(), new Vector3(3.5, 71, 4.5)));
        run(actor, "//pos1 2,71,3");
        run(actor, "//pos2 5,73,6");
        run(actor, "//move 2 south -e -s");
        NbtCompound moved = world.getBlockEntity(2, 71, 5);
        check("the moved chest keeps its items", moved != null && moved.getCompoundList("Items").size() == 1);
        check("the place it left is air", registry().isAirLike(world.getBlock(2, 71, 3)));
        check("the cow moved with the blocks", world.entityList().stream()
                .anyMatch(entity -> entity.type().equals("minecraft:cow")
                        && Math.abs(entity.position().z() - 6.5) < 1e-9));
        checkEquals("and only one cow is left", 1L, world.entityList().stream()
                .filter(entity -> entity.type().equals("minecraft:cow")).count());
        checkEquals("-s moved the selection", new BlockVector3(2, 71, 5),
                actor.session().getSelection(world).getMinimumPoint());
    }

    private static void directionsReadLikeWorldEdit() {
        TestActor actor = actor("directions");
        for (String word : List.of("n", "no", "nor", "nort", "north")) {
            checkEquals("'" + word + "' is north", Directions.NORTH, Directions.parse(actor, word, false));
        }
        checkEquals("'u' is up", Directions.UP, Directions.parse(actor, "u", false));
        checkEquals("'dow' is down", Directions.DOWN, Directions.parse(actor, "dow", false));
        checkEquals("'ne' is north-east", new BlockVector3(1, 0, -1), Directions.parse(actor, "ne", true));
        checkEquals("'southw' is south-west", new BlockVector3(-1, 0, 1), Directions.parse(actor, "southw", true));
        check("a diagonal is refused where the command takes none", refused(() ->
                Directions.parse(actor, "ne", false), "diagonal"));
        check("a word that is no direction is refused", refused(() ->
                Directions.parse(actor, "sideways", true), "Unknown direction"));

        checkEquals("yaw 0 looks south", Directions.SOUTH, Directions.looking(0, 0, 0));
        checkEquals("yaw 90 looks west", Directions.WEST, Directions.looking(90, 0, 0));
        checkEquals("yaw 180 looks north", Directions.NORTH, Directions.looking(180, 0, 0));
        checkEquals("yaw -90 looks east", Directions.EAST, Directions.looking(-90, 0, 0));
        checkEquals("yaw 45 looks south-west", new BlockVector3(-1, 0, 1), Directions.looking(45, 0, 0));
        checkEquals("yaw 350 still looks south", Directions.SOUTH, Directions.looking(350, 0, 0));
        checkEquals("a pitch past 67.5 looks down", Directions.DOWN, Directions.looking(0, 70, 0));
        checkEquals("a pitch below -67.5 looks up", Directions.UP, Directions.looking(0, -70, 0));

        actor.setYaw(0);
        actor.setPitch(0);
        checkEquals("me is where the player looks", Directions.SOUTH, Directions.parse(actor, "me", false));
        checkEquals("forward is too", Directions.SOUTH, Directions.parse(actor, "f", false));
        checkEquals("left of south is east", Directions.EAST, Directions.parse(actor, "left", false));
        checkEquals("right of south is west", Directions.WEST, Directions.parse(actor, "r", false));
        checkEquals("back of south is north", Directions.NORTH, Directions.parse(actor, "back", false));
        actor.setPitch(80);
        checkEquals("looking down, me is down", Directions.DOWN, Directions.parse(actor, "me", false));
        checkEquals("looking down, back is up", Directions.UP, Directions.parse(actor, "b", false));
        actor.setPitch(0);
        actor.setYaw(45);
        check("looking diagonally is refused where the command takes no diagonal",
                refused(() -> Directions.parse(actor, "me", false), "diagonal"));
        checkEquals("and taken where it does", new BlockVector3(-1, 0, 1), Directions.parse(actor, "me", true));
        actor.setYaw(0);

        checkEquals("an offset can be x,y,z", new BlockVector3(3, -1, 2), Directions.offset(actor, "3,-1,2"));
        checkEquals("^1,0,0 is forward", new BlockVector3(0, 0, 1), Directions.offset(actor, "^1,0,0"));
        checkEquals("^0,1,0 is up the view", new BlockVector3(0, 1, 0), Directions.offset(actor, "^0,1,0"));
        checkEquals("^0,0,1 is to the side", new BlockVector3(1, 0, 0), Directions.offset(actor, "^0,0,1"));
        check("an offset of letters is refused", refused(() -> Directions.offset(actor, "a,b,c"), "whole number"));

        TestActor console = TestActor.positionlessConsole("DirectionsConsole", actor.world());
        check("the console cannot use a relative word", refused(() ->
                Directions.parse(console, "me", false), "Only a player"));
        checkEquals("but can name one", Directions.WEST, Directions.parse(console, "w", false));
    }

    private static boolean refused(Runnable action, String reason) {
        try {
            action.run();
            return false;
        } catch (InputException e) {
            return e.getMessage().contains(reason);
        }
    }

    private static void flipAndExpandTakeRelativeDirections() {
        TestActor actor = actor("flip-left");
        run(actor, "//pos1 0,71,0");
        run(actor, "//pos2 3,72,1");
        run(actor, "//copy");
        actor.setYaw(0);
        check("//flip left flips across the axis to the left",
                answer(actor, "//flip left").contains("flipped on X"));
        actor.setPitch(80);
        check("//flip looking down flips upside down", answer(actor, "//flip").contains("flipped on Y"));
        actor.setPitch(0);
        check("//flip forward flips along the view", answer(actor, "//flip forward").contains("flipped on Z"));
        check("//flip refuses a diagonal", answer(actor, "//flip ne").contains("diagonal"));
        check("//flip still takes an axis letter", answer(actor, "//flip y").contains("flipped on Y"));

        run(actor, "//expand 3 left");
        checkEquals("//expand 3 left grows towards the left", 6,
                actor.session().getSelection(actor.world()).getMaximumPoint().x());
        check("//expand refuses what is no direction", answer(actor, "//expand 2 sideways").contains("Unknown direction"));
    }
}
