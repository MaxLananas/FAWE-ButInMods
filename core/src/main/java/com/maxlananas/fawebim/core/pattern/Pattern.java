package com.maxlananas.fawebim.core.pattern;

import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.world.Extent;

/**
 * Something that produces a block state for a position. Patterns are what
 * {@code //set}, {@code //replace} and every brush accept as their argument.
 */
public interface Pattern {

    /**
     * The primitive form every pattern implements: patterns run once per changed
     * block, so the coordinates are passed as ints and nothing allocates.
     *
     * @return the state id to place at the position
     */
    int apply(int x, int y, int z);

    default int apply(BlockVector3 position) {
        return apply(position.x(), position.y(), position.z());
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

    /** Short human readable form, used by {@code /brush info} and friends. */
    default String describe() {
        return getClass().getSimpleName();
    }
}
