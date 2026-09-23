package com.maxlananas.fawebim.core.mask;

import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.world.Extent;

/**
 * A block filter. Masks are compiled from user input ({@code //gmask '#solid'},
 * {@code //replace '#existing'}...) and evaluated both by the visitors and by
 * the edit session's global mask.
 */
public interface Mask {

    boolean test(BlockVector3 position);

    default boolean test(int x, int y, int z) {
        return test(new BlockVector3(x, y, z));
    }

    /** 2D variant used by the surface/heightmap operations. */
    default boolean test2D(int x, int z) {
        return true;
    }

    /** Masks that are pure region tests never read block data. */
    default boolean isRegion() {
        return false;
    }

    /** Extent the mask reads from (may be null for region-only masks). */
    default Extent extent() {
        return null;
    }

    default java.util.Set<BlockVector3> affectedPositions() {
        return java.util.Set.of();
    }
}
