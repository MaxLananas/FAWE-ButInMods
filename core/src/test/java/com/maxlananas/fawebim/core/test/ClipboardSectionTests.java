package com.maxlananas.fawebim.core.test;

import com.maxlananas.fawebim.core.clipboard.BlockArrayClipboard;
import com.maxlananas.fawebim.core.clipboard.Clipboards;
import com.maxlananas.fawebim.core.command.CommandManager;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.region.CuboidRegion;
import com.maxlananas.fawebim.core.region.Region;
import com.maxlananas.fawebim.core.world.BlockState;
import com.maxlananas.fawebim.core.world.BlockStateRegistry;

import static com.maxlananas.fawebim.core.test.SelfTestMain.check;
import static com.maxlananas.fawebim.core.test.SelfTestMain.checkEquals;
import static com.maxlananas.fawebim.core.test.SelfTestMain.section;

/**
 * The clipboard keeps the sections a copy reads only part of.
 *
 * <p>A {@code //stack 100} of a selection that reached part way into its
 * sections failed with an index out of bounds, as did the {@code //copy} of
 * it: the runs of a partly read section were counted by the width of the box
 * where there is one per row along z, and read back at the wrong rows; a row
 * that began with air was also stored without its offset, which put its blocks
 * further along x than they were.</p>
 */
final class ClipboardSectionTests {

    private ClipboardSectionTests() {
    }

    static void run() {
        section("clipboard sections");
        partlyReadSectionsKeepEveryBlock();
        theReportedCopyAndStackWork();
    }

    static BlockStateRegistry registry() {
        return BlockState.registry();
    }

    static int state(String name) {
        return registry().defaultState(name);
    }

    /** Air, stone, dirt or gold, with air at the start of many rows. */
    static int pattern(int x, int y, int z) {
        int hash = x * 73856093 ^ y * 19349663 ^ z * 83492791;
        return switch (Math.floorMod(hash, 5)) {
            case 2 -> state("minecraft:stone");
            case 3 -> state("minecraft:dirt");
            case 4 -> state("minecraft:gold_block");
            default -> registry().air();
        };
    }

    static void fill(TestWorld world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    world.setBlock(x, y, z, pattern(x, y, z));
                }
            }
        }
    }

    /** Air whether it is stored as air or as nothing. */
    static int plain(int state) {
        return state <= 0 || registry().isAirLike(state) ? registry().air() : state;
    }

    static TestActor actor(String name) {
        TestWorld world = new TestWorld(name);
        world.fillFlat(70);
        return new TestActor(name, world, new BlockVector3(0, 71, 0));
    }

    static String answer(TestActor actor, String line) {
        actor.clearMessages();
        CommandManager.get().dispatch(actor, line);
        return String.join("\n", actor.messages()).replaceAll("\u00a7.", "");
    }

    /** How many cells of the box differ from the same box {@code (dx, dy, dz)} away. */
    static long differences(TestWorld world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                            int dx, int dy, int dz) {
        long different = 0;
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                for (int x = minX; x <= maxX; x++) {
                    if (plain(world.getBlock(x, y, z)) != plain(world.getBlock(x + dx, y + dy, z + dz))) {
                        different++;
                    }
                }
            }
        }
        return different;
    }

    /**
     * Boxes that reach part way into their sections, narrower along x than
     * along z and the other way round, one block thick along each axis: the
     * clipboard holds each block where it was, and nothing else.
     */
    private static void partlyReadSectionsKeepEveryBlock() {
        TestWorld world = new TestWorld("partial-sections");
        fill(world, -20, 60, -20, 40, 90, 40);
        int[][] boxes = {
                {-3, 64, 5, 12, 79, 40},
                {5, 61, -3, 40, 70, 2},
                {7, 65, 7, 7, 65, 30},
                {1, 62, 1, 30, 62, 1},
                {3, 60, 9, 3, 90, 9},
                {-17, 60, 3, 20, 90, 9},
                {-20, 60, -20, 40, 90, 40},
        };
        for (int[] box : boxes) {
            String name = "the copy of " + box[0] + "," + box[1] + "," + box[2] + " to " + box[3] + ","
                    + box[4] + "," + box[5];
            Region region = new CuboidRegion(new BlockVector3(box[0], box[1], box[2]),
                    new BlockVector3(box[3], box[4], box[5]));
            BlockArrayClipboard clipboard = Clipboards.copy(world, region, null, false);
            long wrong = 0;
            long filled = 0;
            for (int y = box[1]; y <= box[4]; y++) {
                for (int z = box[2]; z <= box[5]; z++) {
                    for (int x = box[0]; x <= box[3]; x++) {
                        int expected = plain(world.getBlock(x, y, z));
                        if (plain(clipboard.getBlock(x, y, z)) != expected) {
                            wrong++;
                        }
                        if (expected != registry().air()) {
                            filled++;
                        }
                    }
                }
            }
            checkEquals(name + " holds every block where it was", 0L, wrong);
            checkEquals(name + " counts the blocks it holds", filled, (long) clipboard.filled(registry()));
            long[] visited = new long[2];
            clipboard.forEachStored(registry().air(), (x, y, z, stored) -> {
                visited[0]++;
                if (!region.contains(x, y, z) || plain(world.getBlock(x, y, z)) != plain(stored)) {
                    visited[1]++;
                }
                return true;
            });
            checkEquals(name + " visits each block once", filled, visited[0]);
            checkEquals(name + " visits them where they are", 0L, visited[1]);
        }
    }

    /**
     * The report: a {@code //copy} and a {@code //stack 100} of a selection
     * that crosses chunk borders and is narrower than its sections along x.
     */
    private static void theReportedCopyAndStackWork() {
        TestActor actor = actor("reported-stack");
        TestWorld world = (TestWorld) actor.world();
        fill(world, -3, 71, 5, 1, 75, 17);
        CommandManager.get().dispatch(actor, "//pos1 -3,71,5");
        CommandManager.get().dispatch(actor, "//pos2 1,75,17");
        // The copy's origin is where the player stands: the corner here.
        actor.setPosition(new BlockVector3(-3, 71, 5));
        String copied = answer(actor, "//copy");
        check("the copy answers without an error: " + copied, !copied.contains("internal"));
        CommandManager.get().dispatch(actor, "//paste 100,71,100");
        checkEquals("and pastes every block", 0L, differences(world, -3, 71, 5, 1, 75, 17, 103, 0, 95));

        String stacked = answer(actor, "//stack 100 north");
        check("the stack of 100 answers without an error: " + stacked, stacked.contains("Stacked"));
        long wrong = 0;
        for (int copy : new int[]{1, 50, 100}) {
            wrong += differences(world, -3, 71, 5, 1, 75, 17, 0, 0, -13 * copy);
        }
        checkEquals("its copies are the selection", 0L, wrong);
    }
}
