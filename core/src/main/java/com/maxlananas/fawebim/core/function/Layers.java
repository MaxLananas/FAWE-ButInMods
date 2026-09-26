package com.maxlananas.fawebim.core.function;

import com.maxlananas.fawebim.core.extent.EditSession;
import com.maxlananas.fawebim.core.mask.Mask;
import com.maxlananas.fawebim.core.mask.Masks;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.pattern.Pattern;
import com.maxlananas.fawebim.core.region.CylinderRegion;
import com.maxlananas.fawebim.core.region.Polygonal2DRegion;
import com.maxlananas.fawebim.core.region.Region;
import com.maxlananas.fawebim.core.world.BlockState;
import com.maxlananas.fawebim.core.world.BlockStateRegistry;

import java.util.List;

/**
 * The commands that work a column at a time from the surface of the terrain -
 * {@code //overlay}, {@code //lay} and {@code //naturalize} - as FAWE runs
 * them.
 *
 * <p>All three walk the footprint of the selection the way FAWE's flat region
 * does: every column of a cuboid, a cylinder or a polygon, and every column of
 * the box around any other shape. The walk goes along X, then to the next Z,
 * which matters to the two that start each column's search where the previous
 * column's surface was.</p>
 */
public final class Layers {

    private Layers() {
    }

    @FunctionalInterface
    private interface ColumnVisitor {

        void visit(int x, int z);
    }

    private static void forEachColumn(Region region, ColumnVisitor visitor) {
        BlockVector3 min = region.getMinimumPoint();
        BlockVector3 max = region.getMaximumPoint();
        boolean flat = region instanceof CylinderRegion || region instanceof Polygonal2DRegion;
        for (int z = min.z(); z <= max.z(); z++) {
            for (int x = min.x(); x <= max.x(); x++) {
                if (!flat || region.contains(x, min.y(), z)) {
                    visitor.visit(x, z);
                }
            }
        }
    }

    /**
     * {@code //overlay}: the pattern on top of the surface of each column. The
     * surface is the one nearest to the previous column's, between the bottom
     * of the selection and one block above its top, found from the top for the
     * first column.
     *
     * <p>A column with no surface in that range - air or solid all the way - is
     * left alone. FAWE's search means to answer "none" there, but its check
     * misses the bottom of the range, so FAWE writes the second layer of an
     * empty column, or two blocks above a buried one.</p>
     *
     * @return the number of blocks changed
     */
    public static int overlay(EditSession session, Region region, Pattern pattern) {
        int minY = region.getMinimumPoint().y();
        int maxY = Math.min(session.maxY(), region.getMaximumPoint().y() + 1);
        int[] lastY = {maxY};
        int[] changed = {0};
        forEachColumn(region, (x, z) -> {
            session.checkTimeout();
            int surface = session.getNearestSurfaceTerrainBlock(x, z, lastY[0], minY, maxY,
                    Integer.MIN_VALUE, Integer.MIN_VALUE, true);
            if (surface == Integer.MIN_VALUE) {
                return;
            }
            lastY[0] = surface;
            if (session.setBlock(x, surface + 1, z, pattern.apply(x, surface + 1, z))) {
                changed[0]++;
            }
        });
        return changed[0];
    }

    /**
     * {@code //lay}: the surface block of each column replaced with the
     * pattern. The search starts at the bottom of the selection for the first
     * column and where the previous surface was for the others; a column with
     * no surface gets the block at the end it searched towards.
     *
     * @return the number of columns, which is what FAWE counts
     */
    public static int lay(EditSession session, Region region, Pattern pattern) {
        int minY = region.getMinimumPoint().y();
        int maxY = region.getMaximumPoint().y();
        int[] y = {minY};
        int[] columns = {0};
        forEachColumn(region, (x, z) -> {
            session.checkTimeout();
            y[0] = session.getNearestSurfaceTerrainBlock(x, z, y[0], minY, maxY);
            session.setBlock(x, y[0], z, pattern.apply(x, y[0], z));
            columns[0]++;
        });
        return columns[0];
    }

    /**
     * {@code //naturalize}: grass, three blocks of dirt, then stone, counted from
     * the first grass, dirt or stone block of each column down. Only those
     * three blocks change - sand, ores or water keep their place and still
     * count as depth - and a column whose block above the selection is one of
     * them is underground and left alone.
     *
     * @return the number of blocks changed
     */
    public static int naturalize(EditSession session, Region region) {
        BlockStateRegistry registry = BlockState.registry();
        int grass = registry.defaultState("minecraft:grass_block");
        int dirt = registry.defaultState("minecraft:dirt");
        int stone = registry.defaultState("minecraft:stone");
        Mask ground = new Masks.BlockMask(session,
                List.of("minecraft:grass_block", "minecraft:dirt", "minecraft:stone"));
        int minY = region.getMinimumPoint().y();
        int maxY = region.getMaximumPoint().y();
        int[] changed = {0};
        forEachColumn(region, (x, z) -> {
            session.checkTimeout();
            if (ground.test(x, maxY + 1, z)) {
                return;
            }
            int top = Integer.MIN_VALUE;
            for (int y = maxY; y >= minY; y--) {
                if (!ground.test(x, y, z)) {
                    continue;
                }
                if (top == Integer.MIN_VALUE) {
                    top = y;
                }
                int depth = top - y;
                int target = depth == 0 ? grass : depth <= 3 ? dirt : stone;
                if (session.setBlock(x, y, z, target)) {
                    changed[0]++;
                }
            }
        });
        return changed[0];
    }
}
