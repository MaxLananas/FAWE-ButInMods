package com.fawebutinmods.core.world;

import com.fawebutinmods.core.math.BlockVector2;
import com.fawebutinmods.core.math.BlockVector3;

import java.util.Collection;
import java.util.List;

/**
 * Anything the engine can read blocks from and write blocks to: a real world, a
 * clipboard, a mask-limited edit session or a queued chunk buffer.
 *
 * <p>Blocks travel as registry ids (see {@link BlockStateRegistry}); reads
 * outside the extent return air, mirroring WorldEdit's behaviour.</p>
 */
public interface Extent {

    int getBlock(int x, int y, int z);

    /** @return true when the write changed the extent. */
    boolean setBlock(int x, int y, int z, int stateId);

    default int getBiome(int x, int y, int z) {
        return 0;
    }

    default boolean setBiome(int x, int y, int z, int biomeId) {
        return false;
    }

    default boolean contains(int x, int y, int z) {
        return y >= minY() && y <= maxY();
    }

    /** Lowest buildable Y (inclusive). */
    default int minY() {
        return -64;
    }

    /** Highest buildable Y (inclusive). */
    default int maxY() {
        return 319;
    }

    default boolean isWorld() {
        return false;
    }

    /** Entities intersecting the box; empty for non-world extents. */
    default List<EntityData> getEntities(Region3i box) {
        return List.of();
    }

    default void addEntity(EntityData data) {
    }

    default void removeEntity(EntityData data) {
    }

    /** Called once a bulk operation finished so the extent can flush its queue. */
    default void flushQueue() {
    }

    default void close() {
    }

    /**
     * Minimal immutable box description used to avoid a dependency cycle
     * between {@code world} and {@code region} packages.
     */
    record Region3i(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

        public static Region3i of(BlockVector3 a, BlockVector3 b) {
            return new Region3i(
                    Math.min(a.x(), b.x()), Math.min(a.y(), b.y()), Math.min(a.z(), b.z()),
                    Math.max(a.x(), b.x()), Math.max(a.y(), b.y()), Math.max(a.z(), b.z()));
        }
    }

    /** Convenience: chunk keys touched by a box, used by deferred light updates. */
    static Collection<BlockVector2> chunksOf(BlockVector3 min, BlockVector3 max) {
        java.util.LinkedHashSet<BlockVector2> out = new java.util.LinkedHashSet<>();
        for (int cx = min.x() >> 4; cx <= (max.x() >> 4); cx++) {
            for (int cz = min.z() >> 4; cz <= (max.z() >> 4); cz++) {
                out.add(new BlockVector2(cx, cz));
            }
        }
        return out;
    }
}
