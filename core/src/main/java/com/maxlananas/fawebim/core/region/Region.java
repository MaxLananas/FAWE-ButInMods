package com.maxlananas.fawebim.core.region;

import com.maxlananas.fawebim.core.math.BlockBox;
import com.maxlananas.fawebim.core.math.BlockVector2;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.math.Vector3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * A set of block positions with a bounding box, iterable in chunk-friendly
 * order. This mirrors WorldEdit's {@code Region} contract, including the
 * expand/contract vocabulary used by {@code //expand} and {@code //contract}.
 */
public interface Region extends Iterable<BlockVector3> {

    BlockVector3 getMinimumPoint();

    BlockVector3 getMaximumPoint();

    default BlockBox getBoundingBox() {
        return new BlockBox(getMinimumPoint(), getMaximumPoint());
    }

    default Vector3 getCenter() {
        return getMinimumPoint().toVector3().add(getMaximumPoint().toVector3()).multiply(0.5);
    }

    /** Number of blocks in the region (FAWE uses a {@code long} for this). */
    long getVolume();

    boolean contains(int x, int y, int z);

    default boolean contains(BlockVector3 position) {
        return contains(position.x(), position.y(), position.z());
    }

    @Override
    Iterator<BlockVector3> iterator();

    /**
     * Receives one block of a region; returning true counts it, the way a
     * command counts the blocks it changed.
     */
    @FunctionalInterface
    interface BlockVisitor {

        boolean visit(int x, int y, int z);
    }

    /**
     * Walks every block of the region, each exactly once, without building a
     * {@link BlockVector3} for it.
     *
     * <p>Region commands visit millions of blocks on a large selection, and an
     * object per block is the largest allocation of such an edit. Every shape
     * of this package walks itself a chunk column at a time and a section at a
     * time inside it, which is the order the edit buffer and the world store
     * blocks in; the default is only there for a region written elsewhere.</p>
     *
     * @return how many visits reported true, as a {@code long}: a selection can
     *         hold more blocks than an {@code int} counts
     */
    default long forEachPosition(BlockVisitor visitor) {
        long visited = 0;
        for (BlockVector3 position : this) {
            if (visitor.visit(position.x(), position.y(), position.z())) {
                visited++;
            }
        }
        return visited;
    }

    default int getMinimumY() {
        return getMinimumPoint().y();
    }

    default int getMaximumY() {
        return getMaximumPoint().y();
    }

    default int getWidth() {
        BlockVector3 min = getMinimumPoint();
        BlockVector3 max = getMaximumPoint();
        return max.x() - min.x() + 1;
    }

    default int getHeight() {
        return getMaximumY() - getMinimumY() + 1;
    }

    default int getLength() {
        BlockVector3 min = getMinimumPoint();
        BlockVector3 max = getMaximumPoint();
        return max.z() - min.z() + 1;
    }

    /** {@code width * length} — the XZ footprint. */
    default long getArea() {
        return (long) getWidth() * getLength();
    }

    /** How many chunks the bounding box of the region touches, without listing them. */
    default long getChunkCount() {
        BlockVector3 min = getMinimumPoint();
        BlockVector3 max = getMaximumPoint();
        return ((long) (max.x() >> 4) - (min.x() >> 4) + 1) * ((long) (max.z() >> 4) - (min.z() >> 4) + 1);
    }

    /**
     * The chunks the bounding box of the region touches.
     *
     * <p>A list of every chunk is an object per chunk; a selection across a
     * large part of the world would fill the heap with it before any command
     * ran, so a list of more than {@value #MAX_LISTED_CHUNKS} chunks is refused.
     * {@link #getChunkCount()} answers how many there are without the list.</p>
     */
    default List<BlockVector2> getChunks() {
        long count = getChunkCount();
        if (count > MAX_LISTED_CHUNKS) {
            throw new com.maxlananas.fawebim.core.util.InputException("The selection spans "
                    + count + " chunks; at most " + MAX_LISTED_CHUNKS + " can be worked on chunk by chunk");
        }
        BlockVector3 min = getMinimumPoint();
        BlockVector3 max = getMaximumPoint();
        List<BlockVector2> chunks = new ArrayList<>((int) count);
        for (int cx = min.x() >> 4; cx <= (max.x() >> 4); cx++) {
            for (int cz = min.z() >> 4; cz <= (max.z() >> 4); cz++) {
                chunks.add(new BlockVector2(cx, cz));
            }
        }
        return chunks;
    }

    /** The most chunks {@link #getChunks()} lists. */
    int MAX_LISTED_CHUNKS = 1 << 20;

    default boolean isFlat() {
        return getMinimumY() == getMaximumY();
    }

    // ------------------------------------------------------------ transform ops

    /** Expands the region in place by a signed amount per axis. */
    boolean expand(BlockVector3 amount);

    /** Contracts the region in place by a signed amount per axis. */
    boolean contract(BlockVector3 amount);

    /**
     * Moves the region by an amount, keeping its shape.
     *
     * <p>Every shape moves its own defining points. Moving by an expansion and
     * a contraction of the opposite side was the old default, and for a box it
     * was no move at all: the contraction took back the expansion, so
     * {@code //shift} answered that the selection moved and left it in place.</p>
     *
     * @return whether the amount moved the region at all
     */
    boolean shift(BlockVector3 amount);

    /**
     * Grows the region towards the given vertical bounds, used by
     * {@code //expand vert}: the amounts hand {@link #expand(BlockVector3)} the
     * side of the region to move, so it reaches bounds that lie outside the
     * region and can only move a bound that is already past them further. A
     * region whose height is a value of its own sets that value instead.
     */
    default boolean expandToY(int minY, int maxY) {
        BlockVector3 min = getMinimumPoint();
        BlockVector3 max = getMaximumPoint();
        return expand(new BlockVector3(0, minY - min.y(), 0))
                & expand(new BlockVector3(0, maxY - max.y(), 0));
    }

    /** Short human description used by {@code //size} and the selection wand. */
    String describe();
}
