package com.fawebutinmods.core.actor;

import com.fawebutinmods.core.math.BlockVector3;
import com.fawebutinmods.core.math.Vector3;
import com.fawebutinmods.core.session.LocalSession;
import com.fawebutinmods.core.util.Msg;
import com.fawebutinmods.core.world.Direction;
import com.fawebutinmods.core.world.World;

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

    /** Extra reach used by {@code /farwand}. */
    default double reachDistance() {
        return 5.0;
    }

    /** Called when the player's selection changed so the client can be notified. */
    default void updateSelectionOutline() {
    }
}
