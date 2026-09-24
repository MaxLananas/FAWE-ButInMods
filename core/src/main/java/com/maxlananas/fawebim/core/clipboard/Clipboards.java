package com.maxlananas.fawebim.core.clipboard;

import com.maxlananas.fawebim.core.extent.EditSession;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.mask.Mask;
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
        for (int y = min.y(); y <= max.y(); y++) {
            for (int z = min.z(); z <= max.z(); z++) {
                for (int x = min.x(); x <= max.x(); x++) {
                    if (!region.contains(x, y, z)) {
                        continue;
                    }
                    if (withBiomes) {
                        clipboard.setBiome(x, y, z, world.getBiome(x, y, z));
                    }
                    int state = world.getBlock(x, y, z);
                    if (state == BlockStateHolder.air() || (mask != null && !mask.test(x, y, z))) {
                        continue;
                    }
                    clipboard.setBlock(x, y, z, state);
                    // Block entities travel with the clipboard, like FAWE's NBT copy.
                    if (withEntities) {
                        NbtCompound nbt = world.getBlockEntity(x, y, z);
                        if (nbt != null) {
                            clipboard.addBlockEntity(new BlockVector3(x, y, z), nbt);
                        }
                    }
                }
            }
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
