package com.maxlananas.fawebim.core.util;

import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.region.Region;

/**
 * The text the selection preview shows while a selection is dragged out.
 *
 * <p>WorldEdit hands the outline to a client mod and lets it decide what to put
 * next to it. This mod draws the outline itself, so it also writes the line: the
 * three dimensions of the box and how many blocks it holds, on the line above
 * the hotbar. The text lives here so the engine tests cover it.</p>
 */
public final class Cui {

    private Cui() {
    }

    /** {@code » Selection 12x70x12 - 10,080 block(s)}. */
    public static Msg size(Region region) {
        BlockVector3 min = region.getMinimumPoint();
        BlockVector3 max = region.getMaximumPoint();
        return Msg.of(Msg.MARKER + "§7Selection "
                + Msg.value(region.getWidth() + "x" + region.getHeight() + "x"
                        + region.getLength()).raw()
                + " §8(" + Msg.value(shortSize(min, max)).raw() + "§8)"
                + " §8- " + Msg.count(volume(region)) + " §7block(s)");
    }

    /**
     * The blocks the box holds. This is not {@code Region.getArea()}, which is
     * the flat area of the selection: what the line promises is the number of
     * blocks an edit of the whole box would touch.
     */
    public static long volume(Region region) {
        return (long) region.getWidth() * region.getHeight() * region.getLength();
    }

    /** The two picked corners, so the line says where the box is as well as how big. */
    private static String shortSize(BlockVector3 min, BlockVector3 max) {
        return min.x() + ", " + min.y() + ", " + min.z() + " → "
                + max.x() + ", " + max.y() + ", " + max.z();
    }
}
