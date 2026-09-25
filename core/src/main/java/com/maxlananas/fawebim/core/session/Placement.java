package com.maxlananas.fawebim.core.session;

import com.maxlananas.fawebim.core.actor.Actor;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.region.Region;
import com.maxlananas.fawebim.core.region.RegionSelector;
import com.maxlananas.fawebim.core.world.World;

/**
 * The placement a session pastes and generates at: the anchor its type names,
 * moved by an offset that {@code /placement} can set.
 */
public record Placement(PlacementType type, BlockVector3 offset) {

    /** What a session starts with: the block the player stands in. */
    public static final Placement DEFAULT = new Placement(PlacementType.PLAYER, BlockVector3.ZERO);

    /**
     * Where this placement points, or {@code null} when its anchor needs a
     * position or a selection the source does not have.
     */
    public BlockVector3 position(World world, Actor actor) {
        if (type == null || actor == null) {
            return null;
        }
        LocalSession session = actor.session();
        Region selection = session == null || world == null ? null : session.getSelection(world);
        RegionSelector selector = session == null || world == null ? null : session.getSelector(world);
        BlockVector3 anchor = type.anchor(actor, selector, selection);
        return anchor == null ? null : anchor.add(offset);
    }

    public boolean canBeUsedBy(Actor actor) {
        return type == null || type.canBeUsedBy(actor);
    }

    /** The line {@code /placement} and {@code /toggleplace} print. */
    public String message() {
        return type.message(offset);
    }
}
