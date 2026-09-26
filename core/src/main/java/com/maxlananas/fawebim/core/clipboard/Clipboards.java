package com.maxlananas.fawebim.core.clipboard;

import com.maxlananas.fawebim.core.extent.EditSession;
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

    /**
     * How much of a 16x16x16 section a box must cover before the section is
     * worth reading whole. Under a quarter the walk reads fewer cells from the
     * world one at a time than the section-wide read would touch.
     */
    private static final int SECTION_READ_MINIMUM = 1024;

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
        if (mask == null && region instanceof com.maxlananas.fawebim.core.region.CuboidRegion) {
            copyBox(world, min, max, clipboard, withEntities);
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
            // Block entities travel with the clipboard, like FAWE's NBT copy.
            if (withEntities) {
                NbtCompound nbt = world.getBlockEntity(x, y, z);
                if (nbt != null) {
                    clipboard.addBlockEntity(new BlockVector3(x, y, z), nbt);
                }
            }
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
        // A cuboid selection - what //cut is used on - is walked a section at a
        // time, so a section the world reports as all air costs one check
        // instead of 4096 reads and 4096 no-op writes. That only holds when the
        // selection is left as air: a leave pattern with blocks in it has
        // something to write even where the region is empty.
        if (session != null && region instanceof com.maxlananas.fawebim.core.region.CuboidRegion
                && constant == BlockStateHolder.air()) {
            return cutBox(world, region, min, max, clipboard, session, mask, withEntities, withBiomes);
        }
        // Every other shape walks itself: the traversal of a polyhedron is not
        // a box, and the selections it is used on are not the million-block ones.
        region.forEachPosition((x, y, z) -> {
            int state = world.getBlock(x, y, z);
            if (state != BlockStateHolder.air() && (mask == null || mask.test(x, y, z))) {
                clipboard.setBlock(x, y, z, state);
                // Block entities travel with the clipboard, like FAWE's NBT copy.
                if (withEntities) {
                    NbtCompound nbt = world.getBlockEntity(x, y, z);
                    if (nbt != null) {
                        clipboard.addBlockEntity(new BlockVector3(x, y, z), nbt);
                    }
                }
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
                                              EditSession session, Mask mask, boolean withEntities,
                                              boolean withBiomes) {
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
                    // A section the selection barely reaches is read cell by
                    // cell: reading it whole would touch 4096 cells of the
                    // palette to pick out a handful of them.
                    long covered = (long) (toX - fromX + 1) * (toY - fromY + 1) * (toZ - fromZ + 1);
                    boolean read = covered >= SECTION_READ_MINIMUM
                            && world.readSection(chunkX, sectionY, chunkZ, sectionBlocks);
                    if (read && whole && mask == null && !withEntities) {
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
                                    if (withEntities) {
                                        NbtCompound nbt = world.getBlockEntity(x, y, z);
                                        if (nbt != null) {
                                            clipboard.addBlockEntity(new BlockVector3(x, y, z), nbt);
                                        }
                                    }
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
                                BlockArrayClipboard clipboard, boolean withEntities) {
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
                    long covered = (long) (toX - fromX + 1) * (toY - fromY + 1) * (toZ - fromZ + 1);
                    if (covered < SECTION_READ_MINIMUM
                            || !world.readSection(chunkX, sectionY, chunkZ, sectionBlocks)) {
                        for (int y = fromY; y <= toY; y++) {
                            for (int z = fromZ; z <= toZ; z++) {
                                for (int x = fromX; x <= toX; x++) {
                                    copyCell(world, clipboard, x, y, z, withEntities, air);
                                }
                            }
                        }
                        continue;
                    }
                    if (whole && !withEntities) {
                        clipboard.adoptSection(chunkX, sectionY, chunkZ, sectionBlocks);
                        sectionBlocks = new int[4096];
                        continue;
                    }
                    for (int y = fromY; y <= toY; y++) {
                        for (int z = fromZ; z <= toZ; z++) {
                            for (int x = fromX; x <= toX; x++) {
                                int state = sectionBlocks[(y & 15) << 8 | (z & 15) << 4 | (x & 15)];
                                if (state == air) {
                                    continue;
                                }
                                clipboard.setBlock(x, y, z, state);
                                if (withEntities) {
                                    NbtCompound nbt = world.getBlockEntity(x, y, z);
                                    if (nbt != null) {
                                        clipboard.addBlockEntity(new BlockVector3(x, y, z), nbt);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** Copies one position, the way a shape that is not a box is copied. */
    private static void copyCell(World world, BlockArrayClipboard clipboard, int x, int y, int z,
                                 boolean withEntities, int air) {
        int state = world.getBlock(x, y, z);
        if (state == air) {
            return;
        }
        clipboard.setBlock(x, y, z, state);
        if (withEntities) {
            NbtCompound nbt = world.getBlockEntity(x, y, z);
            if (nbt != null) {
                clipboard.addBlockEntity(new BlockVector3(x, y, z), nbt);
            }
        }
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
        boolean hasBlockEntities = !clipboard.blockEntities().isEmpty();
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
            boolean applied = session.setBlock(targetX, targetY, targetZ, state);
            if (hasBlockEntities) {
                NbtCompound nbt = clipboard.getBlockEntity(new BlockVector3(x, y, z));
                if (nbt != null) {
                    session.setBlockEntity(targetX, targetY, targetZ, nbt);
                }
            }
            return applied;
        });
        if (pasteBiomes && clipboard.hasBiomes()) {
            for (java.util.Map.Entry<Long, Integer> biome : clipboard.biomeEntries()) {
                long key = biome.getKey();
                int x = (int) Math.floor(BlockArrayClipboard.keyX(key) - originX + destination.x());
                int y = BlockArrayClipboard.keyY(key) - originY + destination.y();
                int z = (int) Math.floor(BlockArrayClipboard.keyZ(key) - originZ + destination.z());
                session.setBiome(x, y, z, biome.getValue());
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
