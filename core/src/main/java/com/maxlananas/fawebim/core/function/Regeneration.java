package com.maxlananas.fawebim.core.function;

import com.maxlananas.fawebim.core.extent.EditSession;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.region.Region;
import com.maxlananas.fawebim.core.util.NbtCompound;
import com.maxlananas.fawebim.core.world.World;

/**
 * {@code //regen}: a region written as freshly generated terrain has it.
 *
 * <p>The platform generates the chunks in a world of their own; the blocks of
 * the region are copied out of it through the edit session like any other
 * edit, so nothing outside the region changes, masks and limits hold, and an
 * undo puts back what was there. That is WorldEdit's way; regenerating the
 * live chunks in place rewrote whole chunks past the selection and could not
 * be undone.</p>
 */
public final class Regeneration {

    private Regeneration() {
    }

    /**
     * Writes the region as the terrain has it: its blocks, the data of their
     * block entities, and with {@code biomes} its biomes, one per 4x4x4 cell.
     *
     * @return how many blocks changed
     */
    public static long copy(World.GeneratedTerrain terrain, Region region, EditSession session, boolean biomes) {
        long changed = region.forEachPosition((x, y, z) -> session.setBlock(x, y, z, terrain.getBlock(x, y, z)));
        BlockVector3 min = region.getMinimumPoint();
        BlockVector3 max = region.getMaximumPoint();
        terrain.forEachBlockEntity(min.x(), min.y(), min.z(), max.x(), max.y(), max.z(), (x, y, z) -> {
            if (region.contains(x, y, z)) {
                NbtCompound nbt = terrain.getBlockEntity(x, y, z);
                if (nbt != null) {
                    session.setBlockEntity(x, y, z, nbt);
                }
            }
        });
        if (biomes) {
            for (int cellY = min.y() >> 2; cellY <= max.y() >> 2; cellY++) {
                for (int cellZ = min.z() >> 2; cellZ <= max.z() >> 2; cellZ++) {
                    for (int cellX = min.x() >> 2; cellX <= max.x() >> 2; cellX++) {
                        // The cell's centre, pulled into the box for a cell the box only reaches.
                        int x = Math.max(min.x(), Math.min(max.x(), (cellX << 2) + 2));
                        int y = Math.max(min.y(), Math.min(max.y(), (cellY << 2) + 2));
                        int z = Math.max(min.z(), Math.min(max.z(), (cellZ << 2) + 2));
                        if (region.contains(x, y, z)) {
                            session.setBiome(x, y, z, terrain.getBiome(x, y, z));
                        }
                    }
                }
            }
        }
        return changed;
    }
}
