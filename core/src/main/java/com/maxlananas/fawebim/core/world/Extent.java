package com.maxlananas.fawebim.core.world;

import com.maxlananas.fawebim.core.math.BlockVector2;
import com.maxlananas.fawebim.core.math.BlockVector3;

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

    /**
     * The surface of the terrain around {@code y}: the nearest block whose
     * solidity differs from the one at the starting height, searched outwards in
     * both directions. A query that starts in the air returns the topmost solid
     * block below it, a query that starts inside a block returns the height of
     * the first free spot above it, which is what makes a cave roof a surface
     * too. FAWE's heightmap lookup, used by the angle masks.
     *
     * @param x         column x
     * @param z         column z
     * @param y         height to start from
     * @param minY      lowest height to consider
     * @param maxY      highest height to consider
     * @param failedMin what to return when no surface was found while looking for
     *                  a solid block
     * @param failedMax what to return when no surface was found while looking for
     *                  a free spot
     * @param ignoreAir whether a match at the {@code failedMin} bound counts
     * @return the height of the nearest surface
     */
    default int getNearestSurfaceTerrainBlock(int x, int z, int y, int minY, int maxY,
                                              int failedMin, int failedMax, boolean ignoreAir) {
        int lowest = Math.max(minY(), minY);
        int highest = Math.min(maxY(), maxY);
        int start = Math.max(lowest, Math.min(highest, y));
        BlockStateRegistry registry = BlockState.registry();
        boolean lookingForSolid = !registry.isSolid(getBlock(x, start, z));
        int offset = lookingForSolid ? 0 : 1;
        int clearance = Math.min(highest - start, start - lowest);
        for (int distance = 0; distance <= clearance; distance++) {
            int above = start + distance;
            if (isSurface(getBlock(x, above, z), lookingForSolid)) {
                return above - offset;
            }
            int below = start - distance;
            if (isSurface(getBlock(x, below, z), lookingForSolid)) {
                return below + offset;
            }
        }
        if (highest - start != start - lowest) {
            if (highest - start < start - lowest) {
                for (int layer = start - clearance - 1; layer >= lowest; layer--) {
                    if (isSurface(getBlock(x, layer, z), lookingForSolid)) {
                        return layer + offset;
                    }
                }
            } else {
                for (int layer = start + clearance + 1; layer <= highest; layer++) {
                    if (isSurface(getBlock(x, layer, z), lookingForSolid)) {
                        return layer - offset;
                    }
                }
            }
        }
        int result = lookingForSolid ? failedMin : failedMax;
        if (result > lowest && !ignoreAir) {
            return registry.isAirLike(getBlock(x, result, z)) ? -1 : result;
        }
        return result;
    }

    /**
     * The nearest surface around {@code y}, telling the caller the column bounds
     * when nothing matches.
     */
    default int getNearestSurfaceTerrainBlock(int x, int z, int y, int minY, int maxY) {
        return getNearestSurfaceTerrainBlock(x, z, y, minY, maxY, minY, maxY, true);
    }

    private static boolean isSurface(int state, boolean lookingForSolid) {
        return BlockState.registry().isSolid(state) == lookingForSolid;
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
