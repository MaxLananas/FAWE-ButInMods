package com.maxlananas.fawebim.core.test;

import com.maxlananas.fawebim.core.extent.EditSession;
import com.maxlananas.fawebim.core.history.History;
import com.maxlananas.fawebim.core.math.BlockVector2;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.session.LocalSession;
import com.maxlananas.fawebim.core.util.TimeLimiter;
import com.maxlananas.fawebim.core.world.BlockState;
import com.maxlananas.fawebim.core.world.ChunkSet;
import com.maxlananas.fawebim.core.world.EntityData;
import com.maxlananas.fawebim.core.world.PackedBlockArray;
import com.maxlananas.fawebim.core.world.RegenOptions;
import com.maxlananas.fawebim.core.world.World;

import java.util.Collection;
import java.util.Random;

/**
 * Throughput of the engine's write path, printed as a table.
 *
 * <p>Run it with {@code ./gradlew :core:bench}. It exists so that a claim about
 * performance can be re-measured, and so that a change to the write path shows
 * what it cost: the numbers are warm-JIT averages of a few runs, taken on the
 * machine in front of you, and only comparisons within one run mean anything.</p>
 */
public final class BenchMain {

    private static final int SIDE = 256;
    private static final int HEIGHT = 128;
    private static final long BLOCKS = (long) SIDE * SIDE * HEIGHT;

    /** Kept so the JIT cannot delete a loop whose result nobody looks at. */
    private static long benchSink;

    private BenchMain() {
    }

    /** The test world's shape without its map: writes are array stores, nothing else. */
    static final class FastWorld implements World {

        private final int[] blocks = new int[SIDE * SIDE * HEIGHT];

        @Override
        public String name() {
            return "bench";
        }

        @Override
        public int minY() {
            return 0;
        }

        @Override
        public int maxY() {
            return HEIGHT - 1;
        }

        private int index(int x, int y, int z) {
            return ((y << 8) | z) << 8 | x;
        }

        /** Writes the whole world at once, the way a regenerate would. */
        void fill(int state) {
            java.util.Arrays.fill(blocks, state);
        }

        @Override
        public int getBlock(int x, int y, int z) {
            if (x < 0 || x >= SIDE || y < 0 || y >= HEIGHT || z < 0 || z >= SIDE) {
                return BlockState.registry().air();
            }
            return blocks[index(x, y, z)];
        }

        @Override
        public boolean setBlock(int x, int y, int z, int stateId) {
            if (x < 0 || x >= SIDE || y < 0 || y >= HEIGHT || z < 0 || z >= SIDE) {
                return false;
            }
            blocks[index(x, y, z)] = stateId;
            return true;
        }

        @Override
        public void loadChunk(int chunkX, int chunkZ) {
        }

        @Override
        public int applyChunk(ChunkSet set) {
            // The bulk write path, without a game: one array store per cell the
            // buffer holds.
            int applied = 0;
            int baseX = set.chunkX() << 4;
            int baseZ = set.chunkZ() << 4;
            int baseY = set.minSection() << 4;
            PackedBlockArray[] sections = set.sections();
            for (int section = 0; section < sections.length; section++) {
                PackedBlockArray packed = sections[section];
                if (packed == null) {
                    continue;
                }
                int sectionY = baseY + section * 16;
                applied += packed.forEachWritten(local -> blocks[index(baseX + (local & 15),
                        sectionY + ((local >> 8) & 15), baseZ + ((local >> 4) & 15))] = packed.get(local));
            }
            return applied;
        }

        @Override
        public void relight(Collection<BlockVector2> chunks) {
        }

        @Override
        public boolean regenerateChunk(int chunkX, int chunkZ, RegenOptions options) {
            return true;
        }

        @Override
        public boolean generateTree(BlockVector3 pos, String treeType, Random random) {
            return false;
        }

        @Override
        public boolean generateFeature(BlockVector3 pos, String featureType, Random random) {
            return false;
        }

        @Override
        public int getHighestBlockY(int x, int z) {
            for (int y = HEIGHT - 1; y >= 0; y--) {
                if (getBlock(x, y, z) != BlockState.registry().air()) {
                    return y;
                }
            }
            return 0;
        }

        @Override
        public int getBiome(int x, int y, int z) {
            return 1;
        }

        @Override
        public boolean setBiome(int x, int y, int z, int biomeId) {
            return true;
        }

        @Override
        public void removeEntity(EntityData data) {
        }

        @Override
        public boolean isChunkLoaded(int chunkX, int chunkZ) {
            return true;
        }
    }

    public static void main(String[] args) {
        BlockState.setRegistry(new TestBlockStateRegistry());
        EditSession.BlockStateRegistryHolder.set(BlockState.registry());

        FastWorld world = new FastWorld();
        TestActor actor = new TestActor("Bench", world, new BlockVector3(0, 64, 0));
        LocalSession session = actor.session();
        session.setOwnerName("Bench");
        session.setMaxBlocksChanged(-1);

        section("palette");
        measurePalette();

        section("recording");
        measureRecording();

        section("masks");
        measureMasks(world);

        section("engine");
        measureEngine(world, session);

        section("commands");
        measureCommands();

        section("undo");
        measureHistory();

        System.exit(0);
    }

    private static void measurePalette() {
        int stone = BlockState.registry().defaultState("minecraft:stone");
        int dirt = BlockState.registry().defaultState("minecraft:dirt");

        // Two entries need one bit each, which is the width a section starts at.
        PackedBlockArray narrow = new PackedBlockArray(1);
        timed("a section of two states", BLOCKS, () -> {
            for (int i = 0; i < BLOCKS; i++) {
                narrow.set((int) (i & 4095), (i & 1) == 0 ? stone : dirt);
            }
        });

        // A build with many distinct states is what makes the lookup matter.
        PackedBlockArray wide = new PackedBlockArray(4);
        for (int i = 0; i < 4096; i++) {
            wide.set(i, 1000 + i);
        }
        timed("a section of 4096 states", BLOCKS, () -> {
            for (int i = 0; i < BLOCKS; i++) {
                wide.set((int) (i & 4095), 1000 + (int) (i & 4095));
            }
        });
    }

    /**
     * What a mask costs per block.
     *
     * <p>A mask answers for every block of an edit, so its test is as hot as the
     * write itself: the row measures a mask of one name and a mask of a tag, each
     * asked about the same few states a world holds.</p>
     */
    private static void measureMasks(FastWorld world) {
        int stone = BlockState.registry().defaultState("minecraft:stone");
        int dirt = BlockState.registry().defaultState("minecraft:dirt");
        int[] states = {stone, dirt, BlockState.registry().air()};
        long questions = 4_000_000L;

        com.maxlananas.fawebim.core.mask.Mask byName =
                new com.maxlananas.fawebim.core.mask.Masks.BlockMask(world, java.util.List.of("minecraft:dirt"));
        timed("the mask of one name, 4M blocks", questions, () -> {
            long hits = 0;
            for (long i = 0; i < questions; i++) {
                if (byName.test((int) (i & 255), (int) (i >> 8) & 127, (int) (i >> 15) & 255)) {
                    hits++;
                }
            }
            benchSink += hits;
        });

        com.maxlananas.fawebim.core.mask.Mask byTag =
                new com.maxlananas.fawebim.core.mask.Masks.BlockMask(world, java.util.List.of("#minecraft:dirt"));
        timed("the mask of one tag, 4M blocks", questions, () -> {
            long hits = 0;
            for (long i = 0; i < questions; i++) {
                if (byTag.test((int) (i & 255), (int) (i >> 8) & 127, (int) (i >> 15) & 255)) {
                    hits++;
                }
            }
            benchSink += hits;
        });
    }

    private static void measureRecording() {
        int stone = BlockState.registry().defaultState("minecraft:stone");
        int dirt = BlockState.registry().defaultState("minecraft:dirt");
        long blocks = 1_000_000L;
        History.Record record = new History.Record("bench");
        timed("a record of 1M changes", blocks, () -> {
            for (int i = 0; i < blocks; i++) {
                record.addChange(i & 255, (i >> 8) & 127, (i >> 15) & 255, stone, dirt);
            }
        });

        ChunkSet chunk = new ChunkSet(0, 0, 0, HEIGHT - 1);
        timed("a chunk buffer of 8.4M", BLOCKS, () -> {
            for (int y = 0; y < HEIGHT; y++) {
                for (int z = 0; z < SIDE; z++) {
                    for (int x = 0; x < SIDE; x++) {
                        chunk.set(x & 15, y, z & 15, (x ^ z) == 0 ? stone : dirt);
                    }
                }
            }
        });

        // A section that ends up holding many states grows as the palette fills,
        // and a growth remaps the whole section: this row is what that costs, and
        // it is the reason the starting width is a trade and not a free win.
        ChunkSet mixed = new ChunkSet(0, 0, 0, HEIGHT - 1);
        timed("a chunk buffer of 32 states", BLOCKS, () -> {
            for (int y = 0; y < HEIGHT; y++) {
                for (int z = 0; z < SIDE; z++) {
                    for (int x = 0; x < SIDE; x++) {
                        mixed.set(x & 15, y, z & 15, 1000 + ((x + z) & 31));
                    }
                }
            }
        });

        TimeLimiter limiter = new TimeLimiter(100_000);
        timed("the timeout check of 8.4M", BLOCKS, () -> {
            for (int i = 0; i < BLOCKS; i++) {
                limiter.count(1);
            }
        });
    }

    private static void measureEngine(FastWorld world, LocalSession session) {
        int stone = BlockState.registry().defaultState("minecraft:stone");
        int dirt = BlockState.registry().defaultState("minecraft:dirt");
        fill(world, stone);

        timed("the world itself (no engine)", BLOCKS, () -> fill(world, dirt));

        // Every row prepares the world with the state the edit is about to write
        // over, so the measured run really changes those blocks: an edit that
        // finds the value already there returns before it does anything, and a
        // benchmark of that measures nothing.
        // Every row runs ten times and each run records a history of its own -
        // about a hundred megabytes for a row of 8.4M changes - so the prepare
        // drops the history of the run before it. A bench that keeps them
        // measures the heap instead of the engine, and needs a heap no machine
        // running it is likely to have.
        Runnable filled = () -> {
            fill(world, stone);
            session.getHistory().clear();
        };

        timed("an edit without history", BLOCKS, filled, () -> {
            EditSession edit = new EditSession(world, session, "bench", false);
            writeAll(edit, dirt);
            edit.flushQueue();
        });

        timed("an edit with history", BLOCKS, filled, () -> {
            EditSession edit = new EditSession(world, session, "bench", true);
            writeAll(edit, dirt);
            edit.flushQueue();
        });

        timed("an edit that changes nothing", BLOCKS, filled, () -> {
            EditSession edit = new EditSession(world, session, "bench", true);
            writeAll(edit, stone);
            edit.flushQueue();
        });
    }

    /** Undo and redo of an edit the size of a large paste. */
    private static void measureHistory() {
        FastWorld world = new FastWorld();
        TestActor actor = new TestActor("Bench", world, new BlockVector3(0, 64, 0));
        actor.session().setOwnerName("Bench");
        actor.session().setMaxBlocksChanged(-1);
        com.maxlananas.fawebim.core.command.CommandManager.get().initialise();
        dispatch(actor, "//pos1 0,0,0", "//pos2 63,63,63");

        long small = 64L * 64 * 64;
        timed("//undo after //set on 64^3", small,
                () -> dispatch(actor, "//set stone"),
                () -> dispatch(actor, "//undo"));
        timed("//redo after //undo of 64^3", small,
                () -> dispatch(actor, "//set stone", "//undo"),
                () -> dispatch(actor, "//redo"));
    }

    /**
     * Whole commands, through the same dispatcher the game uses. The world is the
     * array one, so what is measured is the command path and not the test world's
     * boxed block map.
     */
    private static void measureCommands() {
        FastWorld world = new FastWorld();
        world.fill(BlockState.registry().defaultState("minecraft:stone"));
        TestActor actor = new TestActor("Bench", world, new BlockVector3(0, 64, 0));
        actor.session().setOwnerName("Bench");
        actor.session().setMaxBlocksChanged(-1);
        com.maxlananas.fawebim.core.command.CommandManager.get().initialise();

        long small = 64L * 64 * 64;
        // Each command is measured from the state it expects to find, so the
        // untimed prepare puts the region back and the command really does the
        // work: measuring a no-op edit would only report how fast nothing is.
        Runnable clearSmall = () -> {
            dispatch(actor, "//pos1 0,0,0", "//pos2 63,63,63", "//set air");
            actor.session().getHistory().clear();
        };
        Runnable stoneSmall = () -> {
            dispatch(actor, "//pos1 0,0,0", "//pos2 63,63,63", "//set stone");
            actor.session().getHistory().clear();
        };
        dispatch(actor, "//pos1 0,0,0", "//pos2 63,63,63");
        timed("//set stone on 64^3", small, clearSmall, () -> dispatch(actor, "//set stone"));
        timed("//replace stone dirt on 64^3", small, stoneSmall,
                () -> dispatch(actor, "//replace stone dirt"));
        timed("//copy 64^3", small, stoneSmall, () -> dispatch(actor, "//copy"));
        timed("//paste over 64^3 of air", small, clearSmall, () -> dispatch(actor, "//paste -o"));
        timed("//walls sand around 64^3", 64L * 64 * 4, clearSmall,
                () -> dispatch(actor, "//walls sand"));
        Runnable clearSphere = () -> {
            dispatch(actor, "//pos1 96,32,96", "//pos2 160,96,160", "//set air");
            actor.session().getHistory().clear();
        };
        timed("//sphere stone 40", 268_000L, clearSphere, () -> {
            dispatch(actor, "//center 128,64,128", "//sphere stone 40");
        });
    }

    private static void writeAll(EditSession edit, int state) {
        for (int y = 0; y < HEIGHT; y++) {
            for (int z = 0; z < SIDE; z++) {
                for (int x = 0; x < SIDE; x++) {
                    edit.setBlock(x, y, z, state);
                }
            }
        }
    }

    private static void fill(FastWorld world, int state) {
        for (int y = 0; y < HEIGHT; y++) {
            for (int z = 0; z < SIDE; z++) {
                for (int x = 0; x < SIDE; x++) {
                    world.setBlock(x, y, z, state);
                }
            }
        }
    }

    private static void dispatch(TestActor actor, String... commands) {
        for (String command : commands) {
            try {
                com.maxlananas.fawebim.core.command.CommandManager.get().dispatch(actor, command);
            } catch (RuntimeException e) {
                System.out.println("   " + command + " failed: " + e.getMessage());
            }
        }
    }

    /** Warm-up runs, then a measured average; {@code blocks} is what the rate is over. */
    private static void timed(String label, long blocks, Runnable action) {
        timed(label, blocks, () -> {
        }, action);
    }

    /**
     * {@code prepare} runs before every measured run and outside the measurement,
     * which is how a command that only does work once gets a fair number.
     */
    private static void timed(String label, long blocks, Runnable prepare, Runnable action) {
        for (int i = 0; i < 3; i++) {
            prepare.run();
            action.run();
        }
        // The fastest run, not the average: this runs on a shared machine and an
        // average only reports how busy the neighbours were.
        int runs = 7;
        long nanos = Long.MAX_VALUE;
        for (int i = 0; i < runs; i++) {
            prepare.run();
            long start = System.nanoTime();
            action.run();
            nanos = Math.min(nanos, System.nanoTime() - start);
        }
        double milliseconds = nanos / 1e6;
        System.out.printf("  %-32s %9.2f ms   %8.2f M/s   %6.1f ns/block%n", label, milliseconds,
                blocks / (nanos / 1e9) / 1e6, nanos / (double) blocks);
    }

    private static void section(String name) {
        System.out.println();
        System.out.println(name);
    }
}
