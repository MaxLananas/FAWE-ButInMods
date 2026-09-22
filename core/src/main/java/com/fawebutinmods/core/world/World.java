package com.fawebutinmods.core.world;

import com.fawebutinmods.core.math.BlockVector2;
import com.fawebutinmods.core.math.BlockVector3;

import java.util.Collection;
import java.util.List;
import java.util.Random;

/**
 * A loaded Minecraft world. Everything the engine needs from the platform lives
 * behind this interface: bulk chunk application, lighting, chunk regeneration,
 * tree/feature generation, entity access and effects.
 */
public interface World extends Extent {

    String name();

    @Override
    default boolean isWorld() {
        return true;
    }

    @Override
    int minY();

    @Override
    int maxY();

    /** Highest non-air block at the column, or {@link #minY()} - 1 when empty. */
    int getHighestBlockY(int x, int z);

    /** True when the chunk is currently resident; the engine loads chunks on demand. */
    boolean isChunkLoaded(int chunkX, int chunkZ);

    void loadChunk(int chunkX, int chunkZ);

    /**
     * Applies a fully prepared chunk buffer to the world. This is the bulk write
     * path: implementations must write packed sections directly (never block by
     * block through the vanilla setBlock cascade) and queue relighting.
     *
     * @param set      the prepared chunk data
     * @param changed  positions that actually changed (used for lighting/updates)
     * @return the number of blocks that changed
     */
    int applyChunk(ChunkSet set, Collection<BlockVector3> changed);

    /** Ensures the given chunks get relit after a bulk edit. */
    void relight(Collection<BlockVector2> chunks);

    /** Queues a "every neighbour of this block should update" notification. */
    default void queueBlockUpdate(int x, int y, int z) {
    }

    /** Regenerates a chunk from the world seed, keeping nothing. */
    boolean regenerateChunk(int chunkX, int chunkZ, RegenOptions options);

    boolean generateTree(BlockVector3 pos, String treeType, Random random);

    boolean generateFeature(BlockVector3 pos, String featureType, Random random);

    /**
     * The first non-air block along the actor's view direction, used by
     * {@code //jumpto}, {@code //thru}, {@code //deltree} and the tools.
     * Implementations with a real raytrace should override this.
     */
    default BlockVector3 getTargetBlock(com.fawebutinmods.core.actor.Actor actor, int maxDistance) {
        BlockVector3 origin = actor.position();
        if (origin == null) {
            return BlockVector3.ZERO;
        }
        com.fawebutinmods.core.math.Vector3 direction = actor.direction().normalize();
        BlockVector3 last = origin;
        for (double step = 0; step <= maxDistance; step += 0.2) {
            double x = origin.x() + 0.5 + direction.x() * step;
            double y = origin.y() + 1.62 + direction.y() * step;
            double z = origin.z() + 0.5 + direction.z() * step;
            BlockVector3 current = new BlockVector3((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
            if (!current.equals(last)) {
                last = current;
                if (!com.fawebutinmods.core.world.BlockState.registry()
                        .isAirLike(getBlock(current.x(), current.y(), current.z()))) {
                    return current;
                }
            }
        }
        return origin.add(direction.multiply(maxDistance).toBlockPoint());
    }

    /** Plays a world effect (used by {@code /remove}, {@code /butcher}, ...). */
    default void playEffect(int x, int y, int z, int effectId) {
    }

    @Override
    default List<EntityData> getEntities(Region3i box) {
        return List.of();
    }

    /** Called before an edit that may insert/remove block entities. */
    default void setBlockEntity(int x, int y, int z, com.fawebutinmods.core.util.NbtCompound nbt) {
    }

    default com.fawebutinmods.core.util.NbtCompound getBlockEntity(int x, int y, int z) {
        return null;
    }

    default void removeBlockEntity(int x, int y, int z) {
    }

    /** Schedules work on the platform's main thread. */
    default void sync(Runnable task) {
        task.run();
    }

    /** Schedules work off-thread (FAWE's async queue). */
    default void async(Runnable task) {
        task.run();
    }

    default java.util.concurrent.ExecutorService executor() {
        return java.util.concurrent.ForkJoinPool.commonPool();
    }
}
