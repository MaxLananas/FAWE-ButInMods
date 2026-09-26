package com.maxlananas.fawebim.core.function;

import com.maxlananas.fawebim.core.clipboard.Clipboards;
import com.maxlananas.fawebim.core.extent.EditSession;
import com.maxlananas.fawebim.core.mask.Mask;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.math.Vector3;
import com.maxlananas.fawebim.core.pattern.Pattern;
import com.maxlananas.fawebim.core.region.CuboidRegion;
import com.maxlananas.fawebim.core.region.Region;
import com.maxlananas.fawebim.core.util.NbtCompound;
import com.maxlananas.fawebim.core.world.EntityData;
import com.maxlananas.fawebim.core.world.Extent;
import com.maxlananas.fawebim.core.world.World;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code //stack} and {@code //move}: the contents of a region copied to other
 * places of the same world, as WorldEdit's {@code stackRegionBlockUnits} and
 * {@code moveRegion} copy them.
 *
 * <p>Only the positions of the region are copied - the corners of a sphere's
 * box are not part of it - and of those only the ones the source mask accepts;
 * the others are left alone at the destination. Each copied position brings its
 * block entity, so a stacked chest keeps its items and a moved sign its text,
 * and on request the region's entities and biomes.</p>
 *
 * <p>Nothing is held per block: a stack reads the source a section at a time
 * and writes every copy of that section before the next one, and a move walks
 * the region in the order that reads each position before anything is written
 * over it, so neither needs a buffer the size of the region.</p>
 */
public final class RegionCopies {

    private RegionCopies() {
    }

    /**
     * Repeats the region {@code count} times, each copy {@code step} further
     * than the one before.
     *
     * <p>The copies must not overlap the region - {@code //stack} refuses a
     * step that would - so the source reads what the world held before the
     * command whatever has been written: the region is read once, section by
     * section, and each section is written into every copy while it is at
     * hand.</p>
     *
     * @param sourceMask the positions to copy, tested once on the source;
     *                   {@code null} for all of them
     * @return the number of blocks changed
     */
    public static long stack(EditSession session, Region region, BlockVector3 step, int count, Mask sourceMask,
                             boolean entities, boolean biomes) {
        if (overlaps(region, step)) {
            throw new IllegalArgumentException("a stack step of " + step + " overlaps the region");
        }
        World world = session.getWorld();
        List<EntityData> movable = entities ? sourceEntities(world, region) : List.of();
        BlockVector3 min = region.getMinimumPoint();
        BlockVector3 max = region.getMaximumPoint();
        boolean cuboid = region instanceof CuboidRegion;
        // The global source mask decides what a read sees, so a session that has
        // one reads through the edit, position by position.
        boolean readThroughSession = session.sourceMask() != null;
        int[] section = new int[4096];
        int[] cells = new int[4096];
        int[] states = new int[4096];
        List<long[]> blockEntityCells = new ArrayList<>();
        List<NbtCompound> blockEntities = new ArrayList<>();
        long before = session.getBlocksChanged();
        for (int chunkX = min.x() >> 4; chunkX <= max.x() >> 4; chunkX++) {
            int fromX = Math.max(min.x(), chunkX << 4);
            int toX = Math.min(max.x(), (chunkX << 4) + 15);
            for (int chunkZ = min.z() >> 4; chunkZ <= max.z() >> 4; chunkZ++) {
                int fromZ = Math.max(min.z(), chunkZ << 4);
                int toZ = Math.min(max.z(), (chunkZ << 4) + 15);
                for (int sectionY = min.y() >> 4; sectionY <= max.y() >> 4; sectionY++) {
                    int fromY = Math.max(min.y(), sectionY << 4);
                    int toY = Math.min(max.y(), (sectionY << 4) + 15);
                    session.checkTimeout();
                    boolean read = !readThroughSession && world.readSection(chunkX, sectionY, chunkZ, section,
                            fromX, fromY, fromZ, toX, toY, toZ);
                    int kept = 0;
                    for (int y = fromY; y <= toY; y++) {
                        for (int z = fromZ; z <= toZ; z++) {
                            for (int x = fromX; x <= toX; x++) {
                                if (!cuboid && !region.contains(x, y, z)) {
                                    continue;
                                }
                                if (sourceMask != null && !sourceMask.test(x, y, z)) {
                                    continue;
                                }
                                int cell = (y & 15) << 8 | (z & 15) << 4 | (x & 15);
                                cells[kept] = cell;
                                states[kept] = read ? section[cell]
                                        : readThroughSession ? session.getBlock(x, y, z) : world.getBlock(x, y, z);
                                kept++;
                            }
                        }
                    }
                    if (kept == 0) {
                        continue;
                    }
                    blockEntityCells.clear();
                    blockEntities.clear();
                    collectBlockEntities(world, region, sourceMask, fromX, fromY, fromZ, toX, toY, toZ,
                            blockEntityCells, blockEntities);
                    int baseX = chunkX << 4;
                    int baseY = sectionY << 4;
                    int baseZ = chunkZ << 4;
                    for (int copy = 1; copy <= count; copy++) {
                        int offsetX = baseX + step.x() * copy;
                        int offsetY = baseY + step.y() * copy;
                        int offsetZ = baseZ + step.z() * copy;
                        for (int i = 0; i < kept; i++) {
                            int cell = cells[i];
                            session.setBlock(offsetX + (cell & 15), offsetY + (cell >> 8),
                                    offsetZ + (cell >> 4 & 15), states[i]);
                        }
                        writeBlockEntities(session, blockEntityCells, blockEntities, step.multiply(copy));
                        session.checkTimeout();
                    }
                }
            }
        }
        if (biomes) {
            for (int copy = 1; copy <= count; copy++) {
                copyBiomes(world, session, region, step.multiply(copy));
            }
        }
        if (!movable.isEmpty()) {
            // The blocks go in first, so a painting or an item frame finds its wall.
            session.flushQueue();
            for (int copy = 1; copy <= count; copy++) {
                placeEntities(session, movable, min, step.multiply(copy));
            }
        }
        return session.getBlocksChanged() - before;
    }

    /**
     * Moves the region by {@code offset}, leaving {@code leave} where its
     * blocks were.
     *
     * <p>The region and its destination may overlap. The positions are visited
     * in the order that reaches the destination of a position before the
     * position itself - from the far end along each axis the move goes - so
     * every position is read before a moved block lands on it, and the block
     * that lands there is written after what is left behind, as WorldEdit's
     * buffer writes it.</p>
     *
     * @param sourceMask the positions to move, the others staying as they are;
     *                   {@code null} for all of them
     * @param leave      what a moved position is left as
     * @return the number of blocks changed
     */
    public static long move(EditSession session, Region region, BlockVector3 offset, Mask sourceMask, Pattern leave,
                            boolean entities, boolean biomes) {
        World world = session.getWorld();
        List<EntityData> movable = entities ? sourceEntities(world, region) : List.of();
        BlockVector3 min = region.getMinimumPoint();
        BlockVector3 max = region.getMaximumPoint();
        boolean cuboid = region instanceof CuboidRegion;
        // Block entities are read before anything moves: the positions they
        // stand on may be overwritten before the walk reaches them.
        List<long[]> blockEntityCells = new ArrayList<>();
        List<NbtCompound> blockEntities = new ArrayList<>();
        collectBlockEntities(world, region, sourceMask, min.x(), min.y(), min.z(), max.x(), max.y(), max.z(),
                blockEntityCells, blockEntities);
        int stepX = offset.x() > 0 ? -1 : 1;
        int stepY = offset.y() > 0 ? -1 : 1;
        int stepZ = offset.z() > 0 ? -1 : 1;
        int startX = stepX < 0 ? max.x() : min.x();
        int startY = stepY < 0 ? max.y() : min.y();
        int startZ = stepZ < 0 ? max.z() : min.z();
        int width = max.x() - min.x() + 1;
        int height = max.y() - min.y() + 1;
        int length = max.z() - min.z() + 1;
        long before = session.getBlocksChanged();
        for (int dy = 0, y = startY; dy < height; dy++, y += stepY) {
            for (int dz = 0, z = startZ; dz < length; dz++, z += stepZ) {
                session.checkTimeout();
                for (int dx = 0, x = startX; dx < width; dx++, x += stepX) {
                    if (!cuboid && !region.contains(x, y, z)) {
                        continue;
                    }
                    if (sourceMask != null && !sourceMask.test(x, y, z)) {
                        continue;
                    }
                    int state = session.getBlock(x, y, z);
                    session.setBlock(x, y, z, leave.apply(x, y, z));
                    session.setBlock(x + offset.x(), y + offset.y(), z + offset.z(), state);
                }
            }
        }
        writeBlockEntities(session, blockEntityCells, blockEntities, offset);
        if (biomes) {
            copyBiomes(world, session, region, offset);
        }
        if (!movable.isEmpty()) {
            session.flushQueue();
            for (EntityData entity : movable) {
                session.removeEntity(entity);
            }
            placeEntities(session, movable, min, offset);
        }
        return session.getBlocksChanged() - before;
    }

    /**
     * True when a copy {@code step} away would share a position with the
     * region's box, WorldEdit's test for a stack in block units: the step has
     * to clear the box along at least one axis.
     */
    public static boolean overlaps(Region region, BlockVector3 step) {
        BlockVector3 min = region.getMinimumPoint();
        BlockVector3 max = region.getMaximumPoint();
        return Math.abs((long) step.x()) < (long) max.x() - min.x() + 1
                && Math.abs((long) step.y()) < (long) max.y() - min.y() + 1
                && Math.abs((long) step.z()) < (long) max.z() - min.z() + 1;
    }

    /** The block entities of a box of the region that the mask accepts, as positions and data. */
    private static void collectBlockEntities(World world, Region region, Mask sourceMask, int fromX, int fromY,
                                             int fromZ, int toX, int toY, int toZ, List<long[]> cells,
                                             List<NbtCompound> data) {
        world.forEachBlockEntity(fromX, fromY, fromZ, toX, toY, toZ, (x, y, z) -> {
            if (!region.contains(x, y, z) || sourceMask != null && !sourceMask.test(x, y, z)) {
                return;
            }
            NbtCompound nbt = world.getBlockEntity(x, y, z);
            if (nbt != null) {
                cells.add(new long[]{x, y, z});
                data.add(nbt);
            }
        });
    }

    /** Queues each block entity at its place in a copy, after the blocks of that copy. */
    private static void writeBlockEntities(EditSession session, List<long[]> cells, List<NbtCompound> data,
                                           BlockVector3 offset) {
        for (int i = 0; i < cells.size(); i++) {
            long[] at = cells.get(i);
            session.setBlockEntity((int) at[0] + offset.x(), (int) at[1] + offset.y(), (int) at[2] + offset.z(),
                    data.get(i).clone());
        }
    }

    /**
     * The entities standing in a region that can be copied: a passenger travels
     * in its vehicle's data, as in WorldEdit, and an entity the game does not
     * save on its own - a player - is not copied.
     */
    private static List<EntityData> sourceEntities(World world, Region region) {
        BlockVector3 min = region.getMinimumPoint();
        BlockVector3 max = region.getMaximumPoint();
        List<EntityData> found = new ArrayList<>();
        for (EntityData entity : world.getEntities(Extent.Region3i.of(min, max.add(1, 1, 1)))) {
            BlockVector3 at = entity.position().toBlockPoint();
            if (region.contains(at.x(), at.y(), at.z()) && !entity.isPassenger() && entity.nbt() != null) {
                found.add(entity);
            }
        }
        return found;
    }

    /** A new entity for each one copied, {@code offset} away, hanging from its moved block. */
    private static void placeEntities(EditSession session, List<EntityData> entities, BlockVector3 origin,
                                      BlockVector3 offset) {
        BlockVector3 destination = origin.add(offset);
        for (EntityData entity : entities) {
            Vector3 at = entity.position();
            session.addEntity(new EntityData(entity.type(),
                    Clipboards.attachedTo(entity.nbt(), null, origin, destination),
                    new Vector3(at.x() + offset.x(), at.y() + offset.y(), at.z() + offset.z())));
        }
    }

    /**
     * Gives each biome cell a copy covers the biome of the region under it.
     *
     * <p>The game keeps one biome per 4x4x4 cell. WorldEdit copies a biome per
     * block, so the cell a copy lands in takes the biome of a position of the
     * region that lands in it; an offset that is not a multiple of four puts
     * parts of several cells into one, and the cell then takes the biome of the
     * position nearest its lowest corner.</p>
     */
    private static void copyBiomes(World world, EditSession session, Region region, BlockVector3 offset) {
        BlockVector3 min = region.getMinimumPoint().add(offset);
        BlockVector3 max = region.getMaximumPoint().add(offset);
        for (int cellY = min.y() >> 2; cellY <= max.y() >> 2; cellY++) {
            for (int cellZ = min.z() >> 2; cellZ <= max.z() >> 2; cellZ++) {
                for (int cellX = min.x() >> 2; cellX <= max.x() >> 2; cellX++) {
                    BlockVector3 source = sourceOfCell(region, offset, cellX, cellY, cellZ, min, max);
                    if (source != null) {
                        session.setBiome(cellX << 2, cellY << 2, cellZ << 2,
                                world.getBiome(source.x(), source.y(), source.z()));
                    }
                }
            }
        }
    }

    /**
     * A position of the region that a copy puts inside the biome cell, the
     * first one from the cell's lowest corner, or {@code null} when the copy
     * puts none there.
     */
    private static BlockVector3 sourceOfCell(Region region, BlockVector3 offset, int cellX, int cellY, int cellZ,
                                             BlockVector3 min, BlockVector3 max) {
        return region.firstInside(Math.max(cellX << 2, min.x()) - offset.x(),
                Math.max(cellY << 2, min.y()) - offset.y(), Math.max(cellZ << 2, min.z()) - offset.z(),
                Math.min((cellX << 2) + 3, max.x()) - offset.x(), Math.min((cellY << 2) + 3, max.y()) - offset.y(),
                Math.min((cellZ << 2) + 3, max.z()) - offset.z());
    }
}
