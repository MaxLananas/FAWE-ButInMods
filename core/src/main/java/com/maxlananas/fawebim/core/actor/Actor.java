package com.maxlananas.fawebim.core.actor;

import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.math.Vector3;
import com.maxlananas.fawebim.core.session.LocalSession;
import com.maxlananas.fawebim.core.util.Msg;
import com.maxlananas.fawebim.core.world.Direction;
import com.maxlananas.fawebim.core.world.World;

import java.util.UUID;

/**
 * Whoever runs a command: a player (with a position, direction, hotbar and
 * wand item) or the console.
 */
public interface Actor {

    String name();

    UUID uuid();

    boolean isPlayer();

    LocalSession session();

    World world();

    /** Null for the console. */
    default BlockVector3 position() {
        return null;
    }

    default Vector3 direction() {
        return new Vector3(0, 0, 1);
    }

    default Direction facing() {
        return Direction.NORTH;
    }

    default double pitch() {
        return 0;
    }

    default double yaw() {
        return 0;
    }

    void message(Msg message);

    /**
     * Sends a line whose text opens a URL when the client can do that, and prints
     * it as plain text where nothing can be clicked (the console, a test).
     */
    default void link(String text, String url) {
        message(Msg.of(text));
    }

    /**
     * Sends a line the client can click to run a command. The fallback reads the
     * line as it is, so the text has to name the command it would run.
     */
    default void commandLink(String text, String commandRun, String hover) {
        message(Msg.of(text));
    }

    default void message(String text) {
        message(Msg.of(text));
    }

    default void error(String text) {
        message(Msg.error(text));
    }

    default void info(String text) {
        message(Msg.info(text));
    }

    default boolean hasPermission(String permission) {
        return true;
    }

    /** Item id ("minecraft:wooden_axe") currently held, or null for the console. */
    default String heldItem() {
        return null;
    }

    /** Hotbar block states, used by the hotbar mask and {@code /tool} defaults. */
    default java.util.List<Integer> hotbarBlocks() {
        return java.util.List.of();
    }

    /** Gives the configured wand item to the player. */
    default boolean giveWand(String item) {
        return false;
    }

    /** Teleports the player (navigation commands). */
    default boolean teleport(double x, double y, double z) {
        return false;
    }

    /** Whether the player is flying; {@code /ceil} and {@code /up} keep them there. */
    default boolean isFlying() {
        return false;
    }

    /** Enables flight, used when a ceiling warp cannot place a platform. */
    default boolean setFlying(boolean flying) {
        return false;
    }

    /** Extra reach used by {@code /farwand}. */
    default double reachDistance() {
        return 5.0;
    }

    /**
     * Opens the configuration screen, when this actor has one.
     *
     * <p>A client that can draw the screen returns true; anything else (the
     * console, a test, a platform without a client) returns false and the caller
     * falls back to printing the same values in chat.</p>
     */
    default boolean openConfigurationScreen() {
        return false;
    }

    /** Called when the player's selection changed so the client can be notified. */
    default void updateSelectionOutline() {
    }
}
