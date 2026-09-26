package com.maxlananas.fawebim.core.actor;

import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.math.Vector3;
import com.maxlananas.fawebim.core.extent.EditSession;
import com.maxlananas.fawebim.core.world.BlockState;
import com.maxlananas.fawebim.core.world.World;

/**
 * Player movement for the navigation commands.
 *
 * <p>Ported from WorldEdit's {@code AbstractPlayerActor}: the vertical search
 * heights, the two-block free space rule and the glass platform of {@code /ceil}
 * and {@code /up} are the same, so a command that lands the player somewhere in
 * WorldEdit lands them in the same place here.</p>
 */
public final class Navigation {

    /** WorldEdit's {@code defaultVerticalHeight}: how far the searches look. */
    private static final int SEARCH_HEIGHT = 256;

    private Navigation() {
    }

    /** {@code /ascend}: the next spot that can be stood on, searching upwards. */
    public static boolean ascendLevel(Actor actor) {
        World world = actor.world();
        BlockVector3 pos = actor.position();
        int x = pos.x();
        int z = pos.z();
        int y = Math.max(world.minY(), pos.y() + 1);
        int maxY = Math.min(world.maxY(), y + SEARCH_HEIGHT) + 2;
        while (y <= maxY) {
            if (y > pos.y() && canStand(actor, x, y, z) && actor.teleport(x + 0.5, y, z + 0.5)) {
                return true;
            }
            ++y;
        }
        return false;
    }

    /** {@code /descend}: the next spot that can be stood on, searching downwards. */
    public static boolean descendLevel(Actor actor) {
        World world = actor.world();
        BlockVector3 pos = actor.position();
        int x = pos.x();
        int z = pos.z();
        int y = Math.max(world.minY(), pos.y() - 1);
        int minY = Math.min(world.minY() + 1, y - SEARCH_HEIGHT);
        while (y >= minY) {
            if (y < pos.y() && canStand(actor, x, y, z) && actor.teleport(x + 0.5, y, z + 0.5)) {
                return true;
            }
            --y;
        }
        return false;
    }

    /** {@code /ceil}: climb to just under the ceiling, leaving the clearance asked for. */
    public static boolean ascendToCeiling(Actor actor, int clearance, boolean alwaysGlass) {
        World world = actor.world();
        BlockVector3 pos = actor.position();
        int x = pos.x();
        int z = pos.z();
        int initialY = Math.max(world.minY(), pos.y());
        int y = Math.max(world.minY(), pos.y() + 2);
        if (!isAir(actor, x, y, z)) {
            return false;
        }
        int maxY = Math.min(world.maxY(), y + SEARCH_HEIGHT);
        while (y <= maxY) {
            if (isBlocking(actor, x, y, z)) {
                int platformY = Math.max(initialY, y - 3 - clearance);
                if (platformY <= initialY) {
                    return false;
                }
                floatAt(actor, x, platformY + 1, z, alwaysGlass);
                return true;
            }
            ++y;
        }
        return false;
    }

    /** {@code /up <distance>}: rise by a distance without searching for a floor. */
    public static boolean ascendUpwards(Actor actor, int distance, boolean alwaysGlass) {
        World world = actor.world();
        BlockVector3 pos = actor.position();
        int x = pos.x();
        int z = pos.z();
        int initialY = Math.max(world.minY(), pos.y());
        int y = Math.max(world.minY(), pos.y() + 1);
        int maxY = Math.min(world.maxY() + 1, initialY + distance);
        while (y <= world.maxY() + 2) {
            if (isBlocking(actor, x, y, z) || y > maxY + 1) {
                return false;
            }
            if (y == maxY + 1) {
                floatAt(actor, x, y - 1, z, alwaysGlass);
                return true;
            }
            ++y;
        }
        return false;
    }

    /** {@code /thru}: walk the view ray to the far side of the wall it hits. */
    public static boolean passThroughForwardWall(Actor actor, int range) {
        Vector3 direction = actor.direction();
        Vector3 eye = new Vector3(actor.position().x() + 0.5,
                actor.position().y() + 1.62, actor.position().z() + 0.5);
        double step = 0.2;
        double travelled = 0;
        boolean inWall = false;
        while (travelled <= range) {
            Vector3 point = eye.add(direction.multiply(travelled));
            int x = (int) Math.floor(point.x());
            int y = (int) Math.floor(point.y());
            int z = (int) Math.floor(point.z());
            boolean blocking = isBlocking(actor, x, y, z);
            if (blocking && !inWall) {
                inWall = true;
            } else if (inWall && !blocking) {
                setOnGround(actor, BlockVector3.at(x, y, z));
                return true;
            }
            travelled += step;
        }
        return false;
    }

    /** Drops the player onto the first floor below {@code searchPos}. */
    public static void setOnGround(Actor actor, BlockVector3 searchPos) {
        World world = actor.world();
        int x = searchPos.x();
        int z = searchPos.z();
        int y = Math.max(world.minY(), searchPos.y());
        int minY = Math.min(world.minY(), y - SEARCH_HEIGHT) + 2;
        while (y >= minY) {
            if (isBlocking(actor, x, y, z) && actor.teleport(x + 0.5, y + 1, z + 0.5)) {
                return;
            }
            --y;
        }
    }

    /** {@code /unstuck}: the first column of two free blocks above the player. */
    public static boolean findFreePosition(Actor actor) {
        World world = actor.world();
        BlockVector3 pos = actor.position();
        int x = pos.x();
        int z = pos.z();
        int y = Math.max(world.minY(), pos.y());
        int originalY = y;
        int maxY = Math.min(world.maxY(), y + SEARCH_HEIGHT) + 2;
        int free = 0;
        while (y <= maxY) {
            if (!isBlocking(actor, x, y, z)) {
                ++free;
            } else {
                free = 0;
            }
            if (free == 2) {
                if (y - 1 == originalY || actor.teleport(x + 0.5, y - 1, z + 0.5)) {
                    return true;
                }
            }
            ++y;
        }
        return false;
    }

    /**
     * Puts the player in the air at {@code y}, on glass when they cannot fly:
     * WorldEdit does the same so that a ceiling warp never drops them.
     */
    private static void floatAt(Actor actor, int x, int y, int z, boolean alwaysGlass) {
        if (alwaysGlass || !actor.isFlying()) {
            if (!isBlocking(actor, x, y - 1, z)) {
                EditSession session = new EditSession(actor.world(), actor.session(), "navigation");
                session.setBlock(x, y - 1, z, BlockState.registry().defaultState("minecraft:glass"));
                session.flushQueue();
            }
        } else {
            actor.setFlying(true);
        }
        actor.teleport(x + 0.5, y, z + 0.5);
    }

    /** WorldEdit's {@code isLocationGoodForStanding}: two free blocks on a floor. */
    private static boolean canStand(Actor actor, int x, int y, int z) {
        return !isBlocking(actor, x, y + 1, z) && !isBlocking(actor, x, y, z)
                && isBlocking(actor, x, y - 1, z);
    }

    private static boolean isBlocking(Actor actor, int x, int y, int z) {
        World world = actor.world();
        if (y < world.minY() || y > world.maxY()) {
            return true;
        }
        return BlockState.registry().isSolid(world.getBlock(x, y, z));
    }

    private static boolean isAir(Actor actor, int x, int y, int z) {
        World world = actor.world();
        if (y < world.minY() || y > world.maxY()) {
            return true;
        }
        return BlockState.registry().isAirLike(world.getBlock(x, y, z));
    }
}
