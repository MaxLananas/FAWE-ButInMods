package com.maxlananas.fawebim.core.actor;

import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.math.Vector3;
import com.maxlananas.fawebim.core.session.LocalSession;
import com.maxlananas.fawebim.core.util.Msg;
import com.maxlananas.fawebim.core.world.Direction;
import com.maxlananas.fawebim.core.world.World;

import java.util.UUID;

/**
 * An actor that is another one in everything, for the wrappers that change
 * one thing about it: {@link SilentActor} what it hears, {@link PositionedActor}
 * where it stands.
 */
public abstract class DelegatingActor implements Actor {

    protected final Actor delegate;

    protected DelegatingActor(Actor delegate) {
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
        delegate.message(message);
    }

    @Override
    public void link(String text, String url) {
        delegate.link(text, url);
    }

    @Override
    public void commandLink(String text, String commandRun, String hover) {
        delegate.commandLink(text, commandRun, hover);
    }

    @Override
    public void suggestLink(String text, String command, String hover) {
        delegate.suggestLink(text, command, hover);
    }

    @Override
    public void status(Msg message) {
        delegate.status(message);
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
    public boolean openConfigurationScreen() {
        return delegate.openConfigurationScreen();
    }

    @Override
    public void updateSelectionOutline() {
        delegate.updateSelectionOutline();
    }
}
