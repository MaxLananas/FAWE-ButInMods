package com.maxlananas.fawebim.fabric;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.lighting.LightEngine;

import java.util.Arrays;

/**
 * The light checks of a bulk write, handed to the light engine a batch at a
 * time.
 *
 * <p>{@code LevelChunk#setBlockState} asks the light engine to check every
 * block whose light properties changed, and the threaded engine turns each of
 * those calls into a task of its own: a position, a lambda, a named wrapper,
 * a queue entry and a dispatcher submission per block. A flush changes up to
 * 98304 blocks of a chunk, so the positions of a section are gathered here and
 * queued as tasks of up to {@link #BATCH} positions, which make on the light
 * thread the calls vanilla's task makes there: {@code checkBlock} on the block
 * engine and on the sky engine.</p>
 *
 * <p>Server thread only. A submitted batch is a copy that nothing but its task
 * reads afterwards, on the light thread.</p>
 */
final class LightChecks {

    /**
     * Positions per task, one layer of a section. The engine runs up to 1000
     * tasks before it propagates light, so this bounds one round of propagation
     * - and the set of positions the engine holds for it - to 256k positions.
     */
    private static final int BATCH = 256;

    /** The cells gathered for the current section, by their index in a {@code PackedBlockArray}. */
    private final short[] pending = new short[4096];
    private int count;

    /** Gathers a cell of the current section: {@code x | z << 4 | y << 8}. */
    void add(int local) {
        pending[count++] = (short) local;
    }

    /** Queues the cells gathered for a section, then starts the next section empty. */
    void submit(ThreadedLevelLightEngine engine, int chunkX, int sectionY, int chunkZ) {
        int baseX = chunkX << 4;
        int baseY = sectionY << 4;
        int baseZ = chunkZ << 4;
        if (replaced(engine)) {
            for (int i = 0; i < count; i++) {
                int local = pending[i];
                engine.checkBlock(new BlockPos(baseX + (local & 15), baseY + (local >> 8), baseZ + ((local >> 4) & 15)));
            }
        } else {
            for (int from = 0; from < count; from += BATCH) {
                short[] batch = Arrays.copyOfRange(pending, from, Math.min(count, from + BATCH));
                engine.addTask(chunkX, chunkZ, ThreadedLevelLightEngine.TaskType.PRE_UPDATE,
                        () -> check(engine, baseX, baseY, baseZ, batch));
            }
        }
        count = 0;
    }

    /** Queues every cell of a section, a layer per task, with no position to copy. */
    static void submitSection(ThreadedLevelLightEngine engine, int chunkX, int sectionY, int chunkZ) {
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        for (int y = sectionY << 4; y < (sectionY + 1) << 4; y++) {
            if (replaced(engine)) {
                for (int local = 0; local < 256; local++) {
                    engine.checkBlock(new BlockPos(baseX + (local & 15), y, baseZ + (local >> 4)));
                }
                continue;
            }
            int layer = y;
            engine.addTask(chunkX, chunkZ, ThreadedLevelLightEngine.TaskType.PRE_UPDATE,
                    () -> checkLayer(engine, baseX, layer, baseZ));
        }
    }

    /**
     * Whether a mod replaced the light engine: Starlight and its forks drop
     * vanilla's two engines and take the checks through the public call, with
     * positions of their own, since what they keep of one is their choice.
     */
    private static boolean replaced(LevelLightEngine engine) {
        return engine.blockEngine == null && engine.skyEngine == null;
    }

    /** Light thread: what {@code LevelLightEngine#checkBlock} does for each position of a batch. */
    private static void check(LevelLightEngine engine, int baseX, int baseY, int baseZ, short[] batch) {
        LightEngine<?, ?> block = engine.blockEngine;
        LightEngine<?, ?> sky = engine.skyEngine;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (short local : batch) {
            pos.set(baseX + (local & 15), baseY + (local >> 8), baseZ + ((local >> 4) & 15));
            checkBlock(block, sky, pos);
        }
    }

    /** Light thread: the same for the 256 positions of a layer. */
    private static void checkLayer(LevelLightEngine engine, int baseX, int y, int baseZ) {
        LightEngine<?, ?> block = engine.blockEngine;
        LightEngine<?, ?> sky = engine.skyEngine;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int local = 0; local < 256; local++) {
            checkBlock(block, sky, pos.set(baseX + (local & 15), y, baseZ + (local >> 4)));
        }
    }

    private static void checkBlock(LightEngine<?, ?> block, LightEngine<?, ?> sky, BlockPos pos) {
        if (block != null) {
            block.checkBlock(pos);
        }
        if (sky != null) {
            sky.checkBlock(pos);
        }
    }
}
