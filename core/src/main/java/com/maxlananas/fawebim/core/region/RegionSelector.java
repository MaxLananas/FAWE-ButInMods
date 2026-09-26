package com.maxlananas.fawebim.core.region;

import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.util.Msg;

import java.util.List;

/**
 * Per-player selection state, WorldEdit's {@code RegionSelector}: what the
 * primary (left) and secondary (right) clicks of the wand and {@code
 * //pos1}/{@code //pos2} do to a shape, and the line each is answered with.
 */
public interface RegionSelector {

    /** @return true when the selection changed. */
    boolean selectPrimary(BlockVector3 position, SelectorLimits limits);

    /** @return true when the selection changed. */
    boolean selectSecondary(BlockVector3 position, SelectorLimits limits);

    /** The answer to a primary click that changed the selection. */
    default Msg explainPrimary(BlockVector3 position) {
        return Msg.result("Position 1", "set to " + Msg.value(position).raw());
    }

    /** The answer to a secondary click that changed the selection. */
    default Msg explainSecondary(BlockVector3 position) {
        return Msg.result("Position 2", "set to " + Msg.value(position).raw());
    }

    /** How the wand works for this shape, as {@code //sel} says when switching to it. */
    default String usage() {
        return "Left click for point 1, right click for point 2";
    }

    Region getRegion();

    /** The first position a player set, which is what placement at pos #1 uses. */
    default BlockVector3 getPrimaryPosition() {
        return getRegion().getMinimumPoint();
    }

    /** The points the primary clicks placed, which the preview marks as position 1. */
    default List<BlockVector3> primaryPoints() {
        return List.of();
    }

    /** The points the secondary clicks placed, which the preview marks as position 2. */
    default List<BlockVector3> secondaryPoints() {
        return List.of();
    }

    /** The selection shape name as used by {@code //sel}. */
    String getTypeName();

    boolean isDefined();

    void clear();

    /** Human readable size/messages for {@code //size}. */
    String describe();

    /**
     * Called after a command changed the region - {@code //expand},
     * {@code //shift}, {@code //move -s} - so the points the selector keeps
     * follow it and the next click starts from the changed region.
     */
    default void learnChanges() {
    }

    RegionSelector copy();

    default int vertexCount() {
        return 0;
    }
}
