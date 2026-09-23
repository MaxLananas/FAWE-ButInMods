package com.maxlananas.fawebim.core.region;

import com.maxlananas.fawebim.core.math.BlockBox;
import com.maxlananas.fawebim.core.math.BlockVector2;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.math.Vector3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
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
     * Walks every block of the region without building a {@link BlockVector3}
     * for it.
     *
     * <p>Region commands visit a million blocks on a large selection, and an
     * object per block is the largest allocation of such an edit. The default
     * here keeps the iterator contract for the shapes whose traversal is not on
     * a hot path; a region walked constantly (the cuboid) overrides it with
     * plain loops.</p>
     *
     * @return how many visits reported true
     */
    default int forEachPosition(BlockVisitor visitor) {
        int visited = 0;
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

    default List<BlockVector2> getChunks() {
        BlockVector3 min = getMinimumPoint();
        BlockVector3 max = getMaximumPoint();
        LinkedHashSet<BlockVector2> chunks = new LinkedHashSet<>();
        for (int cx = min.x() >> 4; cx <= (max.x() >> 4); cx++) {
            for (int cz = min.z() >> 4; cz <= (max.z() >> 4); cz++) {
                chunks.add(new BlockVector2(cx, cz));
            }
        }
        return new ArrayList<>(chunks);
    }

    default boolean isFlat() {
        return getMinimumY() == getMaximumY();
    }

    // ------------------------------------------------------------ transform ops

    /** Expands the region in place by a signed amount per axis. */
    boolean expand(BlockVector3 amount);

    /** Contracts the region in place by a signed amount per axis. */
    boolean contract(BlockVector3 amount);

    default boolean shift(BlockVector3 amount) {
        boolean changed = !amount.equals(BlockVector3.ZERO);
        if (changed) {
            expand(amount);
            contract(amount.multiply(-1));
        }
        return changed;
    }

    /**
     * Expands/contracts the region vertically to the given bounds, used by
     * {@code //expand vert} and {@code /brush} style helpers.
     */
    default boolean setY(int minY, int maxY) {
        BlockVector3 min = getMinimumPoint();
        BlockVector3 max = getMaximumPoint();
        return expand(new BlockVector3(0, minY - min.y(), 0))
                & expand(new BlockVector3(0, maxY - max.y(), 0));
    }

    /** Short human description used by {@code //size} and the selection wand. */
    String describe();
}
