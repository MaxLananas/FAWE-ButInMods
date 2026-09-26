package com.maxlananas.fawebim.fabric;

import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.region.RegionSelector;
import com.maxlananas.fawebim.core.util.Cui;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * Draws the selection with particles.
 *
 * <p>WorldEdit asks a client mod to draw the selection; a vanilla client has
 * none, so the outline is drawn in the world instead: the engine works out the
 * edges of the shape ({@link Cui#outline}) and each one is dotted with
 * particles, one exact point per particle. The points the player clicked are
 * marked in their own colour, the way a CUI client shows them: red for the
 * first, blue for the others.</p>
 *
 * <p>A particle is a packet, so the dots of one drawing are spread over the
 * whole outline and capped: a large selection gets wider gaps rather than more
 * packets.</p>
 *
 * <p>Server thread only: it reads the player's session and sends packets.</p>
 */
public final class SelectionPreview {

    /** The most particles one drawing of the outline sends, marks excluded. */
    private static final int PARTICLE_BUDGET = 320;
    /** The closest two dots of an edge may be. */
    private static final double MIN_SPACING = 0.5;

    private static final int EDGE_COLOUR = 0x6CC6FF;
    private static final int VERTICAL_COLOUR = 0x3F8CFF;
    private static final int POSITION_1_COLOUR = 0xFF5555;
    private static final int POSITION_2_COLOUR = 0x5599FF;
    private static final float MARK_SIZE = 0.3F;

    private static final ParticleOptions EDGE = new DustParticleOptions(EDGE_COLOUR, 1.0F);
    private static final ParticleOptions VERTICAL = new DustParticleOptions(VERTICAL_COLOUR, 1.0F);
    private static final ParticleOptions POSITION_1 = new DustParticleOptions(POSITION_1_COLOUR, 1.5F);
    private static final ParticleOptions POSITION_2 = new DustParticleOptions(POSITION_2_COLOUR, 1.5F);

    private SelectionPreview() {
    }

    /** Redraws the actor's selection, when the preview is on. */
    public static void refresh(FabricActor actor) {
        if (!actor.isPlayer() || !actor.session().isDrawSelection()) {
            return;
        }
        draw(actor.player(), actor.session().getSelector(actor.world()));
    }

    /** Sends the outline of a selector's shape to one player. */
    public static void draw(ServerPlayer player, RegionSelector selector) {
        Cui.Outline outline = Cui.outline(selector);
        if (outline.isEmpty()) {
            return;
        }
        ServerLevel level = (ServerLevel) player.level();
        double spacing = Math.max(MIN_SPACING, outline.length() / PARTICLE_BUDGET);
        double[] segments = outline.segments();
        for (int i = 0; i < segments.length; i += 6) {
            double x0 = segments[i];
            double y0 = segments[i + 1];
            double z0 = segments[i + 2];
            double dx = segments[i + 3] - x0;
            double dy = segments[i + 4] - y0;
            double dz = segments[i + 5] - z0;
            double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
            // The uprights of a box or a polygon run a tone deeper, which is
            // what makes the shape read as a volume rather than a flat outline.
            ParticleOptions colour = dx == 0 && dz == 0 && dy != 0 ? VERTICAL : EDGE;
            int dots = Math.max(1, (int) Math.round(length / spacing));
            for (int dot = 0; dot <= dots; dot++) {
                double t = dot / (double) dots;
                level.sendParticles(player, colour, true, false, x0 + dx * t, y0 + dy * t, z0 + dz * t,
                        1, 0, 0, 0, 0);
            }
        }
        mark(level, player, outline.primary(), POSITION_1);
        mark(level, player, outline.secondary(), POSITION_2);
    }

    /** A small cloud in the middle of each clicked block, in one packet each. */
    private static void mark(ServerLevel level, ServerPlayer player, List<BlockVector3> points,
                             ParticleOptions colour) {
        for (BlockVector3 point : points) {
            level.sendParticles(player, colour, true, false, point.x() + 0.5, point.y() + 0.5, point.z() + 0.5,
                    4, MARK_SIZE, MARK_SIZE, MARK_SIZE, 0);
        }
    }

    /**
     * The shape and size of the selection, on the line above the hotbar.
     *
     * <p>This is what the client half of the CUI shows while a selection is
     * dragged out. The engine writes the line, so the engine tests cover it.</p>
     */
    public static void size(FabricActor actor) {
        if (!actor.isPlayer()) {
            return;
        }
        RegionSelector selector = actor.session().getSelector(actor.world());
        if (selector.isDefined()) {
            actor.status(Cui.size(selector));
        }
    }
}
