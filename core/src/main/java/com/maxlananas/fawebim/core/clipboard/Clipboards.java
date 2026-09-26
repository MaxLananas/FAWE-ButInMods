package com.maxlananas.fawebim.core.clipboard;

import com.maxlananas.fawebim.core.extent.EditSession;
import com.maxlananas.fawebim.core.math.BlockBox;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.mask.Mask;
import com.maxlananas.fawebim.core.pattern.Pattern;
import com.maxlananas.fawebim.core.platform.Config;
import com.maxlananas.fawebim.core.region.Region;
import com.maxlananas.fawebim.core.transform.Transform;
import com.maxlananas.fawebim.core.util.Msg;
import com.maxlananas.fawebim.core.util.NbtCompound;
import com.maxlananas.fawebim.core.world.EntityData;
import com.maxlananas.fawebim.core.world.World;

import java.util.List;

/** Copy/paste helpers shared by the commands, brushes and tools. */
public final class Clipboards {


    private Clipboards() {
    }

    public static BlockArrayClipboard copy(World world, Region region, EditSession session) {
        return copy(world, region, session, false);
    }

    /** Copies a region into a new clipboard, including entities when requested. */
    public static BlockArrayClipboard copy(World world, Region region, EditSession session, boolean withEntities) {
        return copy(world, region, session, withEntities, false, null, false);
    }

    /**
     * Copies a region into a new clipboard.
     *
     * @param withEntities keep the entities of the region
     * @param withBiomes   keep the biomes of the region ({@code //copy -b})
     * @param include      blocks that fail the mask are stored as air ({@code -m})
     * @param centre       move the clipboard origin to the centre ({@code -c})
     */
    public static BlockArrayClipboard copy(World world, Region region, EditSession session, boolean withEntities,
                                           boolean withBiomes, Mask include, boolean centre) {
        BlockVector3 min = region.getMinimumPoint();
        BlockVector3 max = region.getMaximumPoint();
        BlockArrayClipboard clipboard = new BlockArrayClipboard(min);
        Mask sessionMask = session == null ? null : session.getMask();
        Mask mask = sessionMask == null || sessionMask == include ? include
                : include == null ? sessionMask
                : new com.maxlananas.fawebim.core.mask.Masks.IntersectionMask(List.of(sessionMask, include));
        // A box with no mask to test is copied a section at a time: the world
        // hands a whole section over in one call, which is how a large //copy
        // stays off the per-block path.
        copyBlockEntities(world, region, mask, clipboard);
        if (mask == null && region instanceof com.maxlananas.fawebim.core.region.CuboidRegion) {
            copyBox(world, min, max, clipboard);
            if (withBiomes) {
                copyBiomes(world, region, clipboard);
            }
            if (withEntities) {
                List<EntityData> entities = world.getEntities(
                        com.maxlananas.fawebim.core.world.Extent.Region3i.of(min, max.add(1, 1, 1)));
                for (EntityData entity : entities) {
                    clipboard.addEntity(entity.clone());
                }
            }
            if (centre) {
                clipboard.setOrigin(new BlockVector3((min.x() + max.x()) / 2, (min.y() + max.y()) / 2,
                        (min.z() + max.z()) / 2));
            }
            clipboard.setName("clipboard");
            return clipboard;
        }
        // Every other shape walks itself in section order, which keeps both the
        // world's chunk cache and the clipboard's sections warm for a whole
        // section at a time; asking it about every coordinate of the bounding box
        // was the slow way round for a shape that is not a cuboid.
        region.forEachPosition((x, y, z) -> {
            int state = world.getBlock(x, y, z);
            if (state == BlockStateHolder.air() || (mask != null && !mask.test(x, y, z))) {
                return false;
            }
            clipboard.setBlock(x, y, z, state);
            return true;
        });
        if (withBiomes) {
            copyBiomes(world, region, clipboard);
        }
        if (withEntities) {
            List<EntityData> entities = world.getEntities(
                    com.maxlananas.fawebim.core.world.Extent.Region3i.of(min, max.add(1, 1, 1)));
            for (EntityData entity : entities) {
                clipboard.addEntity(entity.clone());
            }
        }
        if (centre) {
            BlockVector3 middle = new BlockVector3((min.x() + max.x()) / 2, (min.y() + max.y()) / 2,
                    (min.z() + max.z()) / 2);
            clipboard.setOrigin(middle);
        }
        clipboard.setName("clipboard");
        return clipboard;
    }

    /**
     * Cuts a region into a clipboard and leaves a pattern behind, in one pass.
     *
     * <p>{@code //cut} used to copy the region and then walk it a second time to
     * do the replacing. Reading a position before writing over it gives the same
     * clipboard and the same result, so the second traversal - the one the
     * player waits on with a large selection - is not needed.</p>
     *
     * @param withEntities keep the entities of the region
     * @param withBiomes   keep the biomes of the region ({@code //cut -b})
     * @param include      blocks that fail the mask stay out of the clipboard ({@code -m})
     * @param leave        the pattern the selection is left as
     */
    public static BlockArrayClipboard cut(World world, Region region, EditSession session, boolean withEntities,
                                          boolean withBiomes, Mask include, Pattern leave) {
        BlockVector3 min = region.getMinimumPoint();
        BlockVector3 max = region.getMaximumPoint();
        BlockArrayClipboard clipboard = new BlockArrayClipboard(min);
        Mask sessionMask = session == null ? null : session.getMask();
        Mask mask = sessionMask == null || sessionMask == include ? include
                : include == null ? sessionMask
                : new com.maxlananas.fawebim.core.mask.Masks.IntersectionMask(List.of(sessionMask, include));
        // A leave pattern that is one fixed block - the default air, and the
        // common case - is resolved once instead of per position, and the write
        // then folds into the same read the copy already did.
        int constant = leave instanceof com.maxlananas.fawebim.core.pattern.Patterns.Single single
                ? single.stateId() : -1;
        // Read while the world still holds them: the cut is about to clear them.
        copyBlockEntities(world, region, mask, clipboard);
        // A cuboid selection - what //cut is used on - is walked a section at a
        // time, so a section the world reports as all air costs one check
        // instead of 4096 reads and 4096 no-op writes. That only holds when the
        // selection is left as air: a leave pattern with blocks in it has
        // something to write even where the region is empty.
        if (session != null && region instanceof com.maxlananas.fawebim.core.region.CuboidRegion
                && constant == BlockStateHolder.air()) {
            return cutBox(world, region, min, max, clipboard, session, mask, withBiomes);
        }
        // Every other shape walks itself: the traversal of a polyhedron is not
        // a box, and the selections it is used on are not the million-block ones.
        region.forEachPosition((x, y, z) -> {
            int state = world.getBlock(x, y, z);
            if (state != BlockStateHolder.air() && (mask == null || mask.test(x, y, z))) {
                clipboard.setBlock(x, y, z, state);
            }
            if (session == null) {
                return true;
            }
            // The session counts the writes it makes; this only consults the
            // clock, so a configured timeout still stops a long cut.
            session.limiter().check(0);
            if (state == constant || (mask != null && state != BlockStateHolder.air()
                    && !mask.test(x, y, z))) {
                return true;
            }
            session.setBlockKnown(x, y, z, state,
                    constant >= 0 ? constant : leave.apply(x, y, z), true);
            return true;
        });
        if (withBiomes) {
            copyBiomes(world, region, clipboard);
        }
        if (withEntities) {
            List<EntityData> entities = world.getEntities(
                    com.maxlananas.fawebim.core.world.Extent.Region3i.of(min, max.add(1, 1, 1)));
            for (EntityData entity : entities) {
                clipboard.addEntity(entity.clone());
            }
        }
        clipboard.setName("clipboard");
        return clipboard;
    }

    /**
     * The cut of a box selection, section by section.
     *
     * <p>Each 16x16x16 section of the box is read once, whole, out of the world:
     * the platform hands a section over in one call - a section of solid ground
     * is answered from the chunk's own palette - and a section the world reports
     * as all air costs nothing at all. The copy and the clear then work from
     * that one read, and a section the selection covers completely goes into the
     * clipboard as it is, in one array instead of 4096 writes.</p>
     */
    private static BlockArrayClipboard cutBox(World world, Region region, BlockVector3 min,
                                              BlockVector3 max, BlockArrayClipboard clipboard,
                                              EditSession session, Mask mask, boolean withBiomes) {
        int air = BlockStateHolder.air();
        int[] sectionBlocks = new int[4096];
        for (int chunkX = min.x() >> 4; chunkX <= max.x() >> 4; chunkX++) {
            int fromX = Math.max(min.x(), chunkX << 4);
            int toX = Math.min(max.x(), (chunkX << 4) + 15);
            for (int chunkZ = min.z() >> 4; chunkZ <= max.z() >> 4; chunkZ++) {
                int fromZ = Math.max(min.z(), chunkZ << 4);
                int toZ = Math.min(max.z(), (chunkZ << 4) + 15);
                for (int sectionY = min.y() >> 4; sectionY <= max.y() >> 4; sectionY++) {
                    int fromY = Math.max(min.y(), sectionY << 4);
                    int toY = Math.min(max.y(), (sectionY << 4) + 15);
                    // An empty section has neither a block to copy nor one to
                    // clear, whether the selection covers all of it or part.
                    if (world.isSectionEmpty(chunkX, sectionY, chunkZ)) {
                        continue;
                    }
                    boolean whole = fromX == (chunkX << 4) && toX == (chunkX << 4) + 15
                            && fromY == (sectionY << 4) && toY == (sectionY << 4) + 15
                            && fromZ == (chunkZ << 4) && toZ == (chunkZ << 4) + 15;
                    // The world is asked for the part of the section the
                    // selection covers, so a section it barely reaches costs a
                    // handful of cells rather than all 4096 of them.
                    boolean read = world.readSection(chunkX, sectionY, chunkZ, sectionBlocks,
                            fromX, fromY, fromZ, toX, toY, toZ);
                    if (read && whole && mask == null) {
                        clearSection(session, sectionBlocks, chunkX << 4, sectionY << 4, chunkZ << 4);
                        clipboard.adoptSection(chunkX, sectionY, chunkZ, sectionBlocks);
                        sectionBlocks = new int[4096];
                        continue;
                    }
                    int visited = 0;
                    for (int y = fromY; y <= toY; y++) {
                        for (int z = fromZ; z <= toZ; z++) {
                            for (int x = fromX; x <= toX; x++) {
                                int state = read
                                        ? sectionBlocks[(y & 15) << 8 | (z & 15) << 4 | (x & 15)]
                                        : world.getBlock(x, y, z);
                                if (state != air && (mask == null || mask.test(x, y, z))) {
                                    clipboard.setBlock(x, y, z, state);
                                    // What the mask keeps out of the clipboard
                                    // stays in the world: a masked cut leaves
                                    // the blocks it does not match.
                                    session.setBlockKnown(x, y, z, state, air, true);
                                }
                                // The session counts its own writes; this is the
                                // clock a configured timeout watches.
                                if ((++visited & 0x3FF) == 0) {
                                    session.limiter().check(0);
                                }
                            }
                        }
                    }
                }
            }
        }
        if (withBiomes) {
            copyBiomes(world, region, clipboard);
        }
        return clipboard;
    }

    /**
     * Copies a box, a section at a time.
     *
     * <p>A section the box covers completely goes into the clipboard as the
     * world hands it over - one array instead of 4096 writes - and one the box
     * only reaches part way is read whole and picked out cell by cell, so the
     * chunk behind it is looked up once rather than once per block.</p>
     */
    private static void copyBox(World world, BlockVector3 min, BlockVector3 max,
                                BlockArrayClipboard clipboard) {
        int air = BlockStateHolder.air();
        int[] sectionBlocks = new int[4096];
        for (int chunkX = min.x() >> 4; chunkX <= max.x() >> 4; chunkX++) {
            int fromX = Math.max(min.x(), chunkX << 4);
            int toX = Math.min(max.x(), (chunkX << 4) + 15);
            for (int chunkZ = min.z() >> 4; chunkZ <= max.z() >> 4; chunkZ++) {
                int fromZ = Math.max(min.z(), chunkZ << 4);
                int toZ = Math.min(max.z(), (chunkZ << 4) + 15);
                for (int sectionY = min.y() >> 4; sectionY <= max.y() >> 4; sectionY++) {
                    int fromY = Math.max(min.y(), sectionY << 4);
                    int toY = Math.min(max.y(), (sectionY << 4) + 15);
                    if (world.isSectionEmpty(chunkX, sectionY, chunkZ)) {
                        continue;
                    }
                    boolean whole = fromX == (chunkX << 4) && toX == (chunkX << 4) + 15
                            && fromY == (sectionY << 4) && toY == (sectionY << 4) + 15
                            && fromZ == (chunkZ << 4) && toZ == (chunkZ << 4) + 15;
                    if (whole && world.readSection(chunkX, sectionY, chunkZ,
                            sectionBlocks, fromX, fromY, fromZ, toX, toY, toZ)) {
                        clipboard.adoptSection(chunkX, sectionY, chunkZ, sectionBlocks);
                        sectionBlocks = new int[4096];
                        continue;
                    }
                    // A section the box only reaches part way is read whole only
                    // when it is worth it: under a quarter the cells outside the
                    // box would outnumber the ones inside it, so those are read
                    // from the world one at a time instead.
                    long covered = (long) (toX - fromX + 1) * (toY - fromY + 1) * (toZ - fromZ + 1);
                    boolean read = covered >= SECTION_READ_MINIMUM
                            && world.readSection(chunkX, sectionY, chunkZ, sectionBlocks,
                            fromX, fromY, fromZ, toX, toY, toZ);
                    if (!read) {
                        for (int y = fromY; y <= toY; y++) {
                            for (int z = fromZ; z <= toZ; z++) {
                                for (int x = fromX; x <= toX; x++) {
                                    copyCell(world, clipboard, x, y, z, air);
                                }
                            }
                        }
                        continue;
                    }
                    if (whole) {
                        clipboard.adoptSection(chunkX, sectionY, chunkZ, sectionBlocks);
                        sectionBlocks = new int[4096];
                        continue;
                    }
                    // The array holds the box at the section's own indices, so
                    // the stride of a row is the width of the box and each row
                    // starts where the read put it.
                    clipboard.adoptBox(chunkX, sectionY, chunkZ, sectionBlocks,
                            fromX, fromY, fromZ, toX, toY, toZ);
                    sectionBlocks = new int[4096];
                }
            }
        }
    }

    /**
     * How much of a 16x16x16 section a box must cover before reading the whole
     * section beats reading the cells the box reaches one at a time. A quarter
     * is where the two meet: about as many cells are looked at either way.
     */
    private static final int SECTION_READ_MINIMUM = 1024;

    /**
     * The paste of a clipboard that needs no per-cell decision.
     *
     * <p>Every cell the clipboard holds a block in is written at the offset the
     * destination asks for, which is what the general walk does for it: the
     * difference is that the air of the clipboard - the bulk of it, for the box
     * a player copies - is never visited, and neither the mask, the transform nor
     * the block entities are asked about anything.</p>
     */
    private static int pasteStored(BlockArrayClipboard clipboard, BlockVector3 destination,
                                   EditSession session) {
        BlockVector3 origin = clipboard.getOrigin();
        int offsetX = destination.x() - origin.x();
        int offsetY = destination.y() - origin.y();
        int offsetZ = destination.z() - origin.z();
        int air = BlockStateHolder.air();
        return clipboard.forEachStored(air, (x, y, z, state) -> {
            int targetX = x + offsetX;
            int targetY = y + offsetY;
            int targetZ = z + offsetZ;
            // The queued changes count as the previous state: a cell written
            // twice by one paste keeps its history straight.
            int previous = session.getBlock(targetX, targetY, targetZ);
            if (previous == state) {
                return false;
            }
            return session.setBlockKnown(targetX, targetY, targetZ, previous, state, true);
        });
    }

    /** Copies one position, the way a shape that is not a box is copied. */
    private static void copyCell(World world, BlockArrayClipboard clipboard, int x, int y, int z, int air) {
        int state = world.getBlock(x, y, z);
        if (state != air) {
            clipboard.setBlock(x, y, z, state);
        }
    }

    /**
     * Copies the block entities of every position a clipboard holds a block
     * at, for a copy that walked something other than a region.
     */
    public static void copyBlockEntities(World world, BlockArrayClipboard clipboard) {
        BlockBox box = clipboard.getBox();
        world.forEachBlockEntity(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ(),
                (x, y, z) -> {
                    if (clipboard.getBlock(x, y, z) != 0) {
                        NbtCompound nbt = world.getBlockEntity(x, y, z);
                        if (nbt != null) {
                            clipboard.addBlockEntity(new BlockVector3(x, y, z), nbt);
                        }
                    }
                });
    }

    /**
     * Copies the block entities of a region - a chest's items, a sign's text -
     * which WorldEdit and FAWE copy with every block, whatever the flags. The
     * chunks are asked for the few they hold, rather than every position of the
     * region for one.
     */
    private static void copyBlockEntities(World world, Region region, Mask mask, BlockArrayClipboard clipboard) {
        BlockVector3 min = region.getMinimumPoint();
        BlockVector3 max = region.getMaximumPoint();
        world.forEachBlockEntity(min.x(), min.y(), min.z(), max.x(), max.y(), max.z(), (x, y, z) -> {
            if (region.contains(x, y, z) && (mask == null || mask.test(x, y, z))) {
                NbtCompound nbt = world.getBlockEntity(x, y, z);
                if (nbt != null) {
                    clipboard.addBlockEntity(new BlockVector3(x, y, z), nbt);
                }
            }
        });
    }

    /** Empties a whole section the caller has already read out of the world. */
    private static void clearSection(EditSession session, int[] sectionBlocks,
                                     int baseX, int baseY, int baseZ) {
        int air = BlockStateHolder.air();
        for (int cell = 0; cell < 4096; cell++) {
            int state = sectionBlocks[cell];
            if (state == air) {
                continue;
            }
            session.setBlockKnown(baseX + (cell & 15), baseY + (cell >> 8),
                    baseZ + (cell >> 4 & 15), state, air, true);
            if ((cell & 0x3FF) == 0) {
                session.limiter().check(0);
            }
        }
    }

    /**
     * Pastes a clipboard.
     *
     * @param ignoreAir    keep existing blocks where the clipboard is air ({@code -a} inverted)
     * @param selectPasted select the pasted area
     */
    public static int paste(BlockArrayClipboard clipboard, BlockVector3 destination, EditSession session,
                            Transform transform, boolean ignoreAir, boolean selective, boolean selectPasted) {
        return paste(clipboard, destination, session, transform, ignoreAir,
                selective ? session.getMask() : null, Config.get().allowNonPlayerEntities, false, false, false);
    }

    /**
     * Copies the biomes of a region into a clipboard.
     *
     * <p>Minecraft stores a biome per 4x4x4 cell, so one sample per cell is all
     * there is to read: asking for the biome of every block of a selection read
     * the same value sixty-four times and filled the clipboard with sixty-four
     * entries for it.</p>
     */
    public static void copyBiomes(World world, Region region, BlockArrayClipboard clipboard) {
        BlockVector3 min = region.getMinimumPoint();
        BlockVector3 max = region.getMaximumPoint();
        for (int y = min.y(); y <= max.y(); y += 4) {
            for (int z = min.z(); z <= max.z(); z += 4) {
                for (int x = min.x(); x <= max.x(); x += 4) {
                    if (!region.contains(x, y, z)) {
                        continue;
                    }
                    clipboard.setBiome(x, y, z, world.getBiome(x, y, z));
                }
            }
        }
    }

    /**
     * Pastes a clipboard with the whole flag set of {@code //paste}.
     *
     * @param ignoreAir       keep the existing blocks where the clipboard is air ({@code -a})
     * @param mask            only blocks matching this mask are pasted ({@code -m})
     * @param pasteEntities   spawn the clipboard's entities ({@code -e})
     * @param pasteBiomes     apply the clipboard's biomes ({@code -b})
     * @param removeEntities  clear the entities of the pasted area first ({@code -x})
     * @param keepStructureVoid keep the target blocks where the clipboard has a
     *                        structure void block ({@code -v})
     */
    public static int paste(BlockArrayClipboard clipboard, BlockVector3 destination, EditSession session,
                            Transform transform, boolean ignoreAir, Mask mask, boolean pasteEntities,
                            boolean pasteBiomes, boolean removeEntities, boolean keepStructureVoid) {
        int air = BlockStateHolder.air();
        int voidState = keepStructureVoid ? structureVoid() : -1;
        int originX = clipboard.getOrigin().x();
        int originY = clipboard.getOrigin().y();
        int originZ = clipboard.getOrigin().z();
        // //paste is the command players run the most, on clipboards of millions
        // of cells. The walk hands the state of each cell over instead of a
        // position object, and the transform is only asked when there is one:
        // without /transform the destination is an integer offset.
        boolean identity = transform.isIdentity();
        // A rotated or mirrored paste turns the states with the positions:
        // stairs, logs, doors and rails face the way the build now does.
        com.maxlananas.fawebim.core.transform.BlockStateTransform states =
                identity ? null : com.maxlananas.fawebim.core.transform.BlockStateTransform.of(transform);
        java.util.Map<BlockVector3, NbtCompound> stored = clipboard.readBlockEntities();
        boolean hasBlockEntities = !stored.isEmpty();
        // A paste that skips air - {@code //paste -a}, and the tool that stacks a
        // clipboard - only writes the cells the clipboard holds a block in, so it
        // never has to visit the air. The walk below stays for the flags that
        // need to see those cells: pasting the clipboard's air is what carves the
        // box a player pasted.
        if (ignoreAir && identity && mask == null && !keepStructureVoid && !hasBlockEntities
                && !pasteEntities && !removeEntities && clipboard.biomeEntries().isEmpty()) {
            return pasteStored(clipboard, destination, session);
        }
        // The block entities are looked up by their cell of the clipboard's box,
        // so the walk asks a primitive map instead of building a position per block.
        BlockBox box = clipboard.getBox();
        com.maxlananas.fawebim.core.util.LongObjectMap<NbtCompound> blockEntities =
                new com.maxlananas.fawebim.core.util.LongObjectMap<>(stored.size());
        for (java.util.Map.Entry<BlockVector3, NbtCompound> entry : stored.entrySet()) {
            BlockVector3 at = entry.getKey();
            if (box.contains(at)) {
                blockEntities.put(cellOf(box, at.x(), at.y(), at.z()), entry.getValue());
            }
        }
        int changed = clipboard.forEachPosition((x, y, z, state) -> {
            if (state == air && ignoreAir) {
                return false;
            }
            if (keepStructureVoid && state == voidState) {
                return false;
            }
            int targetX;
            int targetY;
            int targetZ;
            if (identity) {
                targetX = x - originX + destination.x();
                targetY = y - originY + destination.y();
                targetZ = z - originZ + destination.z();
            } else {
                var target = transform.apply(new com.maxlananas.fawebim.core.math.Vector3(x, y, z));
                targetX = (int) Math.floor(target.x() - originX + destination.x());
                targetY = (int) Math.floor(target.y() - originY + destination.y());
                targetZ = (int) Math.floor(target.z() - originZ + destination.z());
            }
            if (mask != null && !mask.test(targetX, targetY, targetZ)) {
                return false;
            }
            boolean applied = session.setBlock(targetX, targetY, targetZ, states == null ? state : states.apply(state));
            if (hasBlockEntities) {
                // Even onto the same block: a chest pasted over a chest brings its items.
                NbtCompound nbt = blockEntities.get(cellOf(box, x, y, z));
                if (nbt != null) {
                    session.setBlockEntity(targetX, targetY, targetZ, nbt);
                }
            }
            return applied;
        });
        if (pasteBiomes && clipboard.hasBiomes()) {
            for (java.util.Map.Entry<Long, Integer> biome : clipboard.biomeEntries()) {
                long key = biome.getKey();
                double x = BlockArrayClipboard.keyX(key);
                double y = BlockArrayClipboard.keyY(key);
                double z = BlockArrayClipboard.keyZ(key);
                if (!identity) {
                    // The biomes turn with the blocks they are under.
                    var target = transform.apply(new com.maxlananas.fawebim.core.math.Vector3(x, y, z));
                    x = target.x();
                    y = target.y();
                    z = target.z();
                }
                session.setBiome((int) Math.floor(x - originX + destination.x()),
                        (int) Math.floor(y - originY + destination.y()),
                        (int) Math.floor(z - originZ + destination.z()), biome.getValue());
            }
        }
        if (removeEntities) {
            int width = clipboard.getWidth();
            int height = clipboard.getHeight();
            int length = clipboard.getLength();
            session.getWorld().getEntities(com.maxlananas.fawebim.core.world.Extent.Region3i.of(
                            destination, destination.add(width, height, length)))
                    .forEach(session.getWorld()::removeEntity);
        }
        // Entities are re-created (never duplicated).
        if (pasteEntities) {
            for (EntityData entity : clipboard.getEntitiesCopy()) {
                var target = transform.apply(entity.position());
                entity.setPosition(new com.maxlananas.fawebim.core.math.Vector3(
                        target.x() - clipboard.getOrigin().x() + destination.x(),
                        target.y() - clipboard.getOrigin().y() + destination.y(),
                        target.z() - clipboard.getOrigin().z() + destination.z()));
                entity.setSpawnable(true);
                session.addEntity(entity);
            }
        }
        session.flushQueue();
        return changed;
    }

    /**
     * The box a paste covers, as {@code {min, max}}: the clipboard's box put
     * where the paste puts its origin, turned by the transform. What
     * {@code //paste -s} selects.
     */
    public static BlockVector3[] pastedBounds(BlockArrayClipboard clipboard, BlockVector3 destination,
                                              Transform transform) {
        BlockBox box = clipboard.getBox();
        BlockVector3 origin = clipboard.getOrigin();
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (int corner = 0; corner < 8; corner++) {
            com.maxlananas.fawebim.core.math.Vector3 point = new com.maxlananas.fawebim.core.math.Vector3(
                    (corner & 1) == 0 ? box.minX() : box.maxX(),
                    (corner & 2) == 0 ? box.minY() : box.maxY(),
                    (corner & 4) == 0 ? box.minZ() : box.maxZ());
            com.maxlananas.fawebim.core.math.Vector3 target = transform.apply(point);
            int x = (int) Math.floor(target.x() - origin.x() + destination.x());
            int y = (int) Math.floor(target.y() - origin.y() + destination.y());
            int z = (int) Math.floor(target.z() - origin.z() + destination.z());
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
        }
        return new BlockVector3[]{new BlockVector3(minX, minY, minZ), new BlockVector3(maxX, maxY, maxZ)};
    }

    /** The index of a position of a box, counted x first, then z, then y. */
    private static long cellOf(BlockBox box, int x, int y, int z) {
        return ((long) (y - box.minY()) * box.length() + (z - box.minZ())) * box.width() + (x - box.minX());
    }

    /** Summary line used by the copy/paste feedback. */
    public static Msg describe(BlockArrayClipboard clipboard) {
        return Msg.info(Msg.formatNumber(clipboard.volume()) + " blocks ("
                + clipboard.getWidth() + "x" + clipboard.getHeight() + "x" + clipboard.getLength() + ")");
    }

    /** Small helper so the engine never needs the platform registry to test air. */
    static final class BlockStateHolder {

        static int air() {
            return com.maxlananas.fawebim.core.world.BlockState.registry().air();
        }
    }

    /** The structure void block, which {@code //paste -v} keeps the target for. */
    private static int structureVoid() {
        return com.maxlananas.fawebim.core.world.BlockState.registry()
                .defaultState("minecraft:structure_void");
    }
}
