package com.maxlananas.fawebim.fabric;

import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.region.Region;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * Draws the selection outline with particles.
 *
 * <p>WorldEdit asks a client mod to draw the selection; in single player there
 * is no client mod, so this mod draws it in the world instead. The box is one
 * packet per edge - the count and the spread of a particle packet paint a whole
 * line - so a hundred-block selection costs the same as a small one, and the
 * two corners the player picked are marked in their own colour, the way a CUI
 * client shows them: red for position 1, blue for position 2.</p>
 */
public final class SelectionPreview {

    /**
     * Particles one edge is worth. Two per block makes a line that reads as a
     * line rather than as dots, and the cap keeps a thousand-block edge from
     * putting a thousand particles in one packet.
     */
    private static final int SAMPLES_PER_BLOCK = 2;
    private static final int MIN_SAMPLES = 6;
    private static final int MAX_SAMPLES = 64;

    /** The colour of the outline, its corners, and the two picked positions. */
    private static final int EDGE_COLOUR = 0x6CC6FF;
    private static final int VERTICAL_COLOUR = 0x3F8CFF;
    private static final int CORNER_COLOUR = 0xB6EAFF;
    private static final int POSITION_1_COLOUR = 0xFF5555;
    private static final int POSITION_2_COLOUR = 0x5599FF;
    private static final float MARK_SIZE = 0.9F;
    private static final float CORNER_SIZE = 0.35F;

    private SelectionPreview() {
    }

    /** Redraws the outline of the actor's selection, when it has one and wants it. */
    public static void refresh(FabricActor actor) {
        if (!actor.isPlayer()) {
            return;
        }
        ServerPlayer player = actor.player();
        var session = actor.session();
        if (!session.isDrawSelection()) {
            return;
        }
        Region region = session.isSelectionDefined(actor.world()) ? session.getSelection(actor.world()) : null;
        if (region == null) {
            return;
        }
        draw(player, region);
    }

    /** Sends the outline of a region to one player. */
    public static void draw(ServerPlayer player, Region region) {
        BlockVector3 min = region.getMinimumPoint();
        // The far corner of the last block, so the line runs along the outside of
        // the selection rather than through its middle.
        BlockVector3 max = region.getMaximumPoint().add(1, 1, 1);
        ParticleOptions edge = dust(EDGE_COLOUR);
        ParticleOptions vertical = dust(VERTICAL_COLOUR);

        for (int axis = 0; axis < 3; axis++) {
            for (int[] corner : corners(axis)) {
                // The edge starts at the corner and runs along one axis.
                double x = corner[0] == 0 ? min.x() : max.x();
                double y = corner[1] == 0 ? min.y() : max.y();
                double z = corner[2] == 0 ? min.z() : max.z();
                int length = axis == 0 ? max.x() - min.x() : axis == 1 ? max.y() - min.y() : max.z() - min.z();
                if (length <= 0) {
                    continue;
                }
                double dx = axis == 0 ? max.x() - min.x() : 0;
                double dy = axis == 1 ? max.y() - min.y() : 0;
                double dz = axis == 2 ? max.z() - min.z() : 0;
                int samples = Math.max(MIN_SAMPLES,
                        Math.min(MAX_SAMPLES, length * SAMPLES_PER_BLOCK));
                // The four uprights run a tone deeper, which is what makes the box
                // read as a box rather than as a square seen from an angle.
                level(player).sendParticles(player, axis == 1 ? vertical : edge, true, false,
                        x + dx / 2, y + dy / 2, z + dz / 2, samples, dx, dy, dz, 0);
            }
        }
        for (int corner = 0; corner < 8; corner++) {
            mark(player,
                    new BlockVector3((corner & 1) == 0 ? min.x() : max.x(),
                            (corner & 2) == 0 ? min.y() : max.y(),
                            (corner & 4) == 0 ? min.z() : max.z()),
                    CORNER_COLOUR);
        }
        mark(player, min, POSITION_1_COLOUR);
        mark(player, region.getMaximumPoint(), POSITION_2_COLOUR);
    }

    /**
     * The size of the selection, on the line above the hotbar.
     *
     * <p>This is what the client half of the CUI shows while a selection is
     * dragged out: the three dimensions and how many blocks they hold. The engine
     * writes it, so the same line is covered by the engine tests.</p>
     */
    public static void size(FabricActor actor) {
        if (!actor.isPlayer()) {
            return;
        }
        var session = actor.session();
        Region region = session.isSelectionDefined(actor.world()) ? session.getSelection(actor.world()) : null;
        if (region == null) {
            return;
        }
        actor.status(com.maxlananas.fawebim.core.util.Cui.size(region));
    }

    /** The four corners an edge of the given axis can start from. */
    private static List<int[]> corners(int axis) {
        return switch (axis) {
            case 0 -> List.of(new int[]{0, 0, 0}, new int[]{0, 0, 1}, new int[]{0, 1, 0}, new int[]{0, 1, 1});
            case 1 -> List.of(new int[]{0, 0, 0}, new int[]{0, 0, 1}, new int[]{1, 0, 0}, new int[]{1, 0, 1});
            default -> List.of(new int[]{0, 0, 0}, new int[]{0, 1, 0}, new int[]{1, 0, 0}, new int[]{1, 1, 0});
        };
    }

    /** A small cloud of the corner's own colour, in one packet. */
    private static void mark(ServerPlayer player, BlockVector3 corner, int colour) {
        level(player).sendParticles(player, dust(colour), true, false,
                corner.x() + 0.5, corner.y() + 0.5, corner.z() + 0.5, 8,
                MARK_SIZE, MARK_SIZE, MARK_SIZE, 0);
    }

    private static ParticleOptions dust(int colour) {
        return new DustParticleOptions(colour, 1.0F);
    }

    private static net.minecraft.server.level.ServerLevel level(ServerPlayer player) {
        return (net.minecraft.server.level.ServerLevel) player.level();
    }
}
