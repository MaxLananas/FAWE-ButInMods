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
        BlockVector3 min = region.getMinimumPoint();
        BlockVector3 max = region.getMaximumPoint();
        BlockArrayClipboard clipboard = new BlockArrayClipboard(min);
        Mask mask = session == null ? null : session.getMask();
        for (int y = min.y(); y <= max.y(); y++) {
            for (int z = min.z(); z <= max.z(); z++) {
                for (int x = min.x(); x <= max.x(); x++) {
                    if (!region.contains(x, y, z)) {
                        continue;
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
        int changed = 0;
        Mask mask = selective ? session.getMask() : null;
        for (BlockVector3 position : clipboard.positions()) {
            int state = clipboard.getBlock(position);
            if (state == BlockStateHolder.air() && ignoreAir) {
                continue;
            }
            var target = transform.apply(position.toVector3());
            int x = (int) Math.floor(target.x() - clipboard.getOrigin().x() + destination.x());
            int y = (int) Math.floor(target.y() - clipboard.getOrigin().y() + destination.y());
            int z = (int) Math.floor(target.z() - clipboard.getOrigin().z() + destination.z());
            if (mask != null && !mask.test(x, y, z)) {
                continue;
            }
            if (session.setBlock(x, y, z, state)) {
                changed++;
            }
            NbtCompound nbt = clipboard.getBlockEntity(position);
            if (nbt != null) {
                session.setBlockEntity(x, y, z, nbt);
            }
        }
        // Entities are re-created (never duplicated).
        if (Config.get().allowNonPlayerEntities) {
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
}
