package com.maxlananas.fawebim.core.actor;

import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.math.Vector3;
import com.maxlananas.fawebim.core.session.LocalSession;
import com.maxlananas.fawebim.core.util.Msg;
import com.maxlananas.fawebim.core.world.Direction;
import com.maxlananas.fawebim.core.world.World;

import java.util.UUID;

/**
 * An actor that swallows everything printed to it.
 *
 * <p>FAWE's {@code -h} switches ("hide any printed output") and the quiet form of
 * the command brushes keep a command that runs on every brush click from
 * flooding the chat. Everything else — position, selection, session, world — is
 * forwarded, so the wrapped command still does its work.</p>
 */
public final class SilentActor implements Actor {

    private final Actor delegate;

    public SilentActor(Actor delegate) {
        this.delegate = delegate;
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public UUID uuid() {
        return delegate.uuid();
    }

    @Override
    public boolean isPlayer() {
        return delegate.isPlayer();
    }

    @Override
    public LocalSession session() {
        return delegate.session();
    }

    @Override
    public World world() {
        return delegate.world();
    }

    @Override
    public BlockVector3 position() {
        return delegate.position();
    }

    @Override
    public Vector3 direction() {
        return delegate.direction();
    }

    @Override
    public Direction facing() {
        return delegate.facing();
    }

    @Override
    public double pitch() {
        return delegate.pitch();
    }

    @Override
    public double yaw() {
        return delegate.yaw();
    }

    @Override
    public void message(Msg message) {
        // Deliberately dropped.
    }

    @Override
    public boolean hasPermission(String permission) {
        return delegate.hasPermission(permission);
    }

    @Override
    public String heldItem() {
        return delegate.heldItem();
    }

    @Override
    public java.util.List<Integer> hotbarBlocks() {
        return delegate.hotbarBlocks();
    }

    @Override
    public boolean giveWand(String item) {
        return delegate.giveWand(item);
    }

    @Override
    public boolean teleport(double x, double y, double z) {
        return delegate.teleport(x, y, z);
    }

    @Override
    public boolean isFlying() {
        return delegate.isFlying();
    }

    @Override
    public boolean setFlying(boolean flying) {
        return delegate.setFlying(flying);
    }

    @Override
    public double reachDistance() {
        return delegate.reachDistance();
    }

    @Override
    public void updateSelectionOutline() {
        delegate.updateSelectionOutline();
    }
}
