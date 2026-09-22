package com.fawebutinmods.core.pattern;

import com.fawebutinmods.core.math.BlockVector3;
import com.fawebutinmods.core.world.Extent;

/**
 * Something that produces a block state for a position. Patterns are what
 * {@code //set}, {@code //replace} and every brush accept as their argument.
 */
public interface Pattern {

    /** @return the state id to place at the position. */
    int apply(BlockVector3 position);

    default int apply(int x, int y, int z) {
        return apply(new BlockVector3(x, y, z));
    }

    /** The extent the pattern reads from (clipboard patterns, biome patterns...). */
    default Extent extent() {
        return null;
    }

    /** Offset applied to the pattern's own coordinates (relative patterns). */
    default BlockVector3 offset() {
        return BlockVector3.ZERO;
    }

    /** Used by {@code //set -n} style optimisations to know whether the pattern is random. */
    default boolean isDeterministic() {
        return true;
    }
}
