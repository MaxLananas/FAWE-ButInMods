package com.maxlananas.fawebim.core.actor;

import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.math.Vector3;

/**
 * An actor standing somewhere else: the commands a brush runs where it was
 * used - FAWE's command brushes - build there, as FAWE runs them for a player
 * whose location is the point.
 */
public final class PositionedActor extends DelegatingActor {

    private final BlockVector3 position;

    public PositionedActor(Actor delegate, BlockVector3 position) {
        super(delegate);
        this.position = position;
    }

    @Override
    public BlockVector3 position() {
        return position;
    }

    /** The point the actor was moved to, not where the player behind it stands. */
    @Override
    public Vector3 location() {
        return new Vector3(position.x(), position.y(), position.z());
    }
}
