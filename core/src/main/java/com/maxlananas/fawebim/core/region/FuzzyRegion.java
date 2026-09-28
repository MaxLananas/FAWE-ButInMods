package com.maxlananas.fawebim.core.region;

import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.util.Buffers;
import com.maxlananas.fawebim.core.util.InputException;
import com.maxlananas.fawebim.core.util.LongQueue;
import com.maxlananas.fawebim.core.util.LongSet;
import com.maxlananas.fawebim.core.util.Msg;
import com.maxlananas.fawebim.core.world.BlockState;
import com.maxlananas.fawebim.core.world.World;

import java.util.Collections;
import java.util.Iterator;

/**
 * FAWE's fuzzy region: a set of blocks gathered by a magic wand rather than a
 * shape. {@link #select} adds the blocks joined to a start by faces that are
 * of its type, up to {@value #MAX_DEPTH} steps away, the reach FAWE's search
 * has.
 *
 * <p>The set can only grow by another selection: a set of blocks has no sides
 * to move, so it is neither expanded, contracted nor shifted, as in FAWE.</p>
 */
public final class FuzzyRegion implements Region {

    /** How many steps from the start a selection reaches. */
    public static final int MAX_DEPTH = 256;

    /** What a block of the set costs in memory, with the set's free slots. */
    private static final int BYTES_PER_BLOCK = 32;

    private LongSet blocks = new LongSet();
    private int minX = Integer.MAX_VALUE;
    private int minY = Integer.MAX_VALUE;
    private int minZ = Integer.MAX_VALUE;
    private int maxX = Integer.MIN_VALUE;
    private int maxY = Integer.MIN_VALUE;
    private int maxZ = Integer.MIN_VALUE;
    /** The blocks in walking order, or {@code null} once the set changed. */
    private long[] ordered;

    /**
     * Adds the blocks joined to {@code start} by faces that are of its type,
     * breadth first up to {@value #MAX_DEPTH} steps. The search stays between
     * the given heights and inside the chunks the world has loaded: following
     * a vein into the unloaded world would generate it.
     *
     * @return whether a block was added
     * @throws InputException when the joined blocks are more than an edit may
     *                        hold in memory; the set is then left as it was
     */
    public boolean select(World world, BlockVector3 start, int lowestY, int highestY) {
        if (start.y() < lowestY || start.y() > highestY || !world.isChunkLoaded(start.x() >> 4, start.z() >> 4)) {
            return false;
        }
        String type = BlockState.registry().name(world.getBlock(start.x(), start.y(), start.z()));
        long limit = Buffers.budget() / BYTES_PER_BLOCK;
        LongSet seen = new LongSet();
        LongQueue queue = new LongQueue();
        long first = BlockKeys.key(start.x(), start.y(), start.z());
        seen.add(first);
        queue.add(first);
        for (int depth = 0; depth <= MAX_DEPTH && !queue.isEmpty(); depth++) {
            // One layer of the search per pass, so the depth is the number of steps.
            for (int remaining = queue.size(); remaining > 0; remaining--) {
                long key = queue.poll();
                int x = BlockKeys.x(key);
                int y = BlockKeys.y(key);
                int z = BlockKeys.z(key);
                if (depth == MAX_DEPTH) {
                    continue;
                }
                for (int face = 0; face < 6; face++) {
                    int nx = x + (face == 0 ? 1 : face == 1 ? -1 : 0);
                    int ny = y + (face == 2 ? 1 : face == 3 ? -1 : 0);
                    int nz = z + (face == 4 ? 1 : face == 5 ? -1 : 0);
                    if (ny < lowestY || ny > highestY) {
                        continue;
                    }
                    long next = BlockKeys.key(nx, ny, nz);
                    if (seen.contains(next) || blocks.contains(next)
                            || !world.isChunkLoaded(nx >> 4, nz >> 4)
                            || !type.equals(BlockState.registry().name(world.getBlock(nx, ny, nz)))) {
                        continue;
                    }
                    if (seen.size() + (long) blocks.size() >= limit) {
                        throw new InputException("More than " + Msg.formatNumber(limit)
                                + " connected blocks: that is more than an edit may hold in memory");
                    }
                    seen.add(next);
                    queue.add(next);
                }
            }
        }
        boolean added = false;
        for (long key : seen.toArray()) {
            if (blocks.add(key)) {
                grow(BlockKeys.x(key), BlockKeys.y(key), BlockKeys.z(key));
                added = true;
            }
        }
        if (added) {
            ordered = null;
        }
        return added;
    }

    /** Adds the blocks of another fuzzy region. */
    void addAll(FuzzyRegion other) {
        for (long key : other.blocks.toArray()) {
            if (blocks.add(key)) {
                grow(BlockKeys.x(key), BlockKeys.y(key), BlockKeys.z(key));
            }
        }
        ordered = null;
    }

    private void grow(int x, int y, int z) {
        minX = Math.min(minX, x);
        minY = Math.min(minY, y);
        minZ = Math.min(minZ, z);
        maxX = Math.max(maxX, x);
        maxY = Math.max(maxY, y);
        maxZ = Math.max(maxZ, z);
    }

    /** Empties the set. */
    public void clear() {
        blocks = new LongSet();
        minX = Integer.MAX_VALUE;
        minY = Integer.MAX_VALUE;
        minZ = Integer.MAX_VALUE;
        maxX = Integer.MIN_VALUE;
        maxY = Integer.MIN_VALUE;
        maxZ = Integer.MIN_VALUE;
        ordered = null;
    }

    @Override
    public BlockVector3 getMinimumPoint() {
        return blocks.size() == 0 ? BlockVector3.ZERO : new BlockVector3(minX, minY, minZ);
    }

    @Override
    public BlockVector3 getMaximumPoint() {
        return blocks.size() == 0 ? BlockVector3.ZERO : new BlockVector3(maxX, maxY, maxZ);
    }

    @Override
    public long getVolume() {
        return blocks.size();
    }

    @Override
    public boolean contains(int x, int y, int z) {
        return blocks.contains(BlockKeys.key(x, y, z));
    }

    private long[] ordered() {
        if (ordered == null) {
            ordered = BlockKeys.chunkOrder(blocks.toArray());
        }
        return ordered;
    }

    @Override
    public long forEachPosition(BlockVisitor visitor) {
        return BlockKeys.forEach(ordered(), visitor);
    }

    @Override
    public Iterator<BlockVector3> iterator() {
        return blocks.size() == 0 ? Collections.emptyIterator() : BlockKeys.iterator(ordered());
    }

    @Override
    public boolean expand(BlockVector3 amount) {
        throw new InputException("A fuzzy selection cannot be expanded");
    }

    @Override
    public boolean contract(BlockVector3 amount) {
        throw new InputException("A fuzzy selection cannot be contracted");
    }

    @Override
    public boolean shift(BlockVector3 amount) {
        throw new InputException("A fuzzy selection cannot be shifted");
    }

    @Override
    public FuzzyRegion copy() {
        FuzzyRegion copy = new FuzzyRegion();
        for (long key : blocks.toArray()) {
            copy.blocks.add(key);
        }
        copy.minX = minX;
        copy.minY = minY;
        copy.minZ = minZ;
        copy.maxX = maxX;
        copy.maxY = maxY;
        copy.maxZ = maxZ;
        return copy;
    }

    @Override
    public String describe() {
        return "fuzzy (" + blocks.size() + (blocks.size() == 1 ? " block)" : " blocks)");
    }
}
