package com.maxlananas.fawebim.core.world;

import com.maxlananas.fawebim.core.math.BlockVector2;
import com.maxlananas.fawebim.core.math.BlockVector3;

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

    /**
     * The dimension's {@code region} folder, used by {@code /anvil} to inspect
     * chunks the server has not loaded. Null when the platform has no world files.
     */
    default java.nio.file.Path regionDirectory() {
        return null;
    }

    void loadChunk(int chunkX, int chunkZ);

    /**
     * Applies a fully prepared chunk buffer to the world. This is the bulk write
     * path: implementations must write packed sections directly (never block by
     * block through the vanilla setBlock cascade) and queue relighting.
     *
     * <p>The positions that changed are read from {@link ChunkSet#forEachChanged},
     * which walks the bits of the sections the buffer holds: a flush of a
     * million-block edit used to hand the implementation a list of a million
     * position objects.</p>
     *
     * @param set the prepared chunk data
     * @return the number of blocks that changed
     */
    int applyChunk(ChunkSet set);

    /**
     * Writes the data of a block entity whose block the buffer just put down.
     *
     * <p>A platform takes the matching block entity out of the chunk and loads
     * this data into it; one that keeps block entities as data replaces what it
     * holds. It is called after the blocks of the chunk are written, never
     * before, because the block entity only exists once its block does.</p>
     */
    default void applyBlockEntity(int x, int y, int z, com.maxlananas.fawebim.core.util.NbtCompound nbt) {
    }

    /** Ensures the given chunks get relit after a bulk edit. */
    void relight(Collection<BlockVector2> chunks);

    /** Queues a "every neighbour of this block should update" notification. */
    default void queueBlockUpdate(int x, int y, int z) {
    }

    /** Regenerates a chunk from the world seed, keeping nothing. */
    boolean regenerateChunk(int chunkX, int chunkZ, RegenOptions options);

    /**
     * Whether {@code //regen <seed>} can use the seed it was given. Minecraft's
     * chunk source is built from the level seed, so a platform that cannot build
     * a second one says so and the command tells the player instead of silently
     * regenerating with the world seed.
     */
    default boolean supportsCustomRegenSeed() {
        return false;
    }

    /**
     * The last time a chunk was written to disk, in milliseconds since the epoch,
     * or {@code -1} when the platform cannot tell. {@code //delchunks -o} uses it
     * to leave recently edited chunks alone.
     */
    default long chunkLastModified(int chunkX, int chunkZ) {
        return -1;
    }

    boolean generateTree(BlockVector3 pos, String treeType, Random random);

    boolean generateFeature(BlockVector3 pos, String featureType, Random random);

    /**
     * Generates a worldgen structure (a village, a shipwreck, a stronghold...) at
     * the given position.
     *
     * @return false when the structure id is unknown to the server
     */
    default boolean generateStructure(String structureId, BlockVector3 pos, Random random) {
        return false;
    }

    /**
     * The first non-air block along the actor's view direction, used by
     * {@code //jumpto}, {@code //thru}, {@code //deltree} and the tools.
     * Implementations with a real raytrace should override this.
     */
    default BlockVector3 getTargetBlock(com.maxlananas.fawebim.core.actor.Actor actor, int maxDistance) {
        BlockVector3 origin = actor.position();
        if (origin == null) {
            // The console, a command block and a function look at nothing.
            return null;
        }
        com.maxlananas.fawebim.core.math.Vector3 direction = actor.direction().normalize();
        BlockVector3 last = origin;
        for (double step = 0; step <= maxDistance; step += 0.2) {
            double x = origin.x() + 0.5 + direction.x() * step;
            double y = origin.y() + 1.62 + direction.y() * step;
            double z = origin.z() + 0.5 + direction.z() * step;
            BlockVector3 current = new BlockVector3((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
            if (!current.equals(last)) {
                last = current;
                if (!com.maxlananas.fawebim.core.world.BlockState.registry()
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

    /** Every entity of the loaded chunks, which is a radius of -1 to {@code /remove}. */
    default List<EntityData> getEntities() {
        return getEntities(new Region3i(-30_000_000, minY(), -30_000_000, 30_000_000, maxY(), 30_000_000));
    }

    /** Called before an edit that may insert/remove block entities. */
    default void setBlockEntity(int x, int y, int z, com.maxlananas.fawebim.core.util.NbtCompound nbt) {
    }

    default com.maxlananas.fawebim.core.util.NbtCompound getBlockEntity(int x, int y, int z) {
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
