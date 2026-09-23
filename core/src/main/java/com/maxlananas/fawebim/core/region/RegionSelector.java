package com.maxlananas.fawebim.core.region;

import com.maxlananas.fawebim.core.math.BlockVector3;

/**
 * Per-player selection state. The {@code explain*} methods produce the chat
 * feedback the selection wand shows, exactly like WorldEdit's
 * {@code RegionSelector} contract.
 */
public interface RegionSelector {

    /** @return true when the first position changed. */
    boolean selectPrimary(BlockVector3 position, SelectorLimits limits);

    /** @return true when the second position changed. */
    boolean selectSecondary(BlockVector3 position, SelectorLimits limits);

    /** Called when the player left-clicks a block with the wand. */
    default void explainPrimarySelection(Object actor, BlockVector3 position, boolean changed) {
    }

    default void explainSecondarySelection(Object actor, BlockVector3 position, boolean changed) {
    }

    /** Called after the selector type changed, to print the usage hint. */
    default void explainRegionAdjust(Object actor) {
    }

    Region getRegion();

    /** The selection shape name as used by {@code //sel}. */
    String getTypeName();

    boolean isDefined();

    void clear();

    /** Human readable size/messages for {@code //size}. */
    String describe();

    /** Called before an operation that modifies the selection. */
    default void learnChanges() {
    }

    RegionSelector copy();

    default int vertexCount() {
        return 0;
    }
}
