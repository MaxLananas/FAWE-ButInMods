package com.maxlananas.fawebim.fabric;

import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.region.Region;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * Draws the selection outline with particles.
 *
 * <p>FAWE/WorldEdit normally ask a client mod (CUI) to draw the selection. In
 * singleplayer there is no client mod, so the selection is drawn as an outline
 * of end-rod particles around the selected region — the same visual result,
 * without requiring anything on the client.</p>
 */
public final class SelectionPreview {

    /** How many particles to spread over the box; kept modest on purpose. */
    private static final int MAX_PARTICLES_PER_EDGE = 64;

    private SelectionPreview() {
    }

    public static void refresh(FabricActor actor) {
        if (!actor.isPlayer()) {
            return;
        }
        ServerPlayer player = actor.player();
        var session = actor.session();
        Region region = session.isSelectionDefined(actor.world()) ? session.getSelection(actor.world()) : null;
        if (region == null) {
            return;
        }
        if (!session.isDrawSelection()) {
            return;
        }
        draw(player, region);
    }

    /** Sends the outline to the player. */
    public static void draw(ServerPlayer player, Region region) {
        ServerLevel level = (ServerLevel) player.level();
        BlockVector3 min = region.getMinimumPoint();
        BlockVector3 max = region.getMaximumPoint().add(1, 1, 1);

        // The 12 edges of the box, sampled so that long walls stay readable.
        for (int axis = 0; axis < 3; axis++) {
            List<int[]> edges = switch (axis) {
                case 0 -> List.of(new int[]{1, 0, 0}, new int[]{1, 0, 1}, new int[]{1, 1, 0}, new int[]{1, 1, 1});
                case 1 -> List.of(new int[]{0, 1, 0}, new int[]{0, 1, 1}, new int[]{1, 1, 0}, new int[]{1, 1, 1});
                default -> List.of(new int[]{0, 0, 1}, new int[]{0, 1, 1}, new int[]{1, 0, 1}, new int[]{1, 1, 1});
            };
            for (int[] edge : edges) {
                int length = axis == 0 ? max.x() - min.x() : axis == 1 ? max.y() - min.y() : max.z() - min.z();
                int steps = Math.max(1, Math.min(MAX_PARTICLES_PER_EDGE, length));
                for (int i = 0; i <= steps; i++) {
                    double t = (double) i / steps;
                    double x = edge[0] == 0 ? min.x() : min.x() + (max.x() - min.x()) * t;
                    double y = edge[1] == 0 ? min.y() : min.y() + (max.y() - min.y()) * t;
                    double z = edge[2] == 0 ? min.z() : min.z() + (max.z() - min.z()) * t;
                    if (axis == 0) {
                        x = min.x() + (max.x() - min.x()) * t;
                    } else if (axis == 1) {
                        y = min.y() + (max.y() - min.y()) * t;
                    } else {
                        z = min.z() + (max.z() - min.z()) * t;
                    }
                    level.sendParticles(player, ParticleTypes.END_ROD, true, x, y, z, 1, 0, 0, 0, 0);
                }
            }
        }
    }
}
