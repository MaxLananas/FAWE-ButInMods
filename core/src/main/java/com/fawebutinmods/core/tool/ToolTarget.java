package com.fawebutinmods.core.tool;

import com.fawebutinmods.core.actor.Actor;
import com.fawebutinmods.core.brush.BrushSettings;
import com.fawebutinmods.core.mask.Mask;
import com.fawebutinmods.core.math.BlockVector3;
import com.fawebutinmods.core.math.Vector3;
import com.fawebutinmods.core.world.BlockState;
import com.fawebutinmods.core.world.World;

/**
 * Where a brush or tool is applied, which is what {@code /tool target} selects.
 *
 * <p>The four modes are FAWE's: the clicked block (the default), a point along
 * the player's pitch, the ground below the player, and the face the click landed
 * on. The trace ignores everything the trace mask does not match, so a brush can
 * be aimed through water or leaves.</p>
 */
public final class ToolTarget {

    /** The modes of {@code /tool target}, in the order FAWE numbers them. */
    public enum Mode {
        /** The first block along the view direction, the default. */
        TARGET_BLOCK_RANGE,
        /** A point that only depends on the pitch, i.e. always in front of the player. */
        FORWARD_POINT_PITCH,
        /** The ground below the player, plus a fixed height. */
        TARGET_POINT_HEIGHT,
        /** The clicked face's block, at the tool's range. */
        TARGET_FACE_RANGE
    }

    private ToolTarget() {
    }

    public static Mode mode(int index) {
        Mode[] modes = Mode.values();
        int wrapped = Math.floorMod(index, modes.length);
        return modes[wrapped];
    }

    /**
     * The position the brush or tool applies at.
     *
     * @param clicked the block the player clicked, used by the range modes
     */
    public static BlockVector3 resolve(World world, Actor actor, BrushSettings settings, BlockVector3 clicked) {
        BlockVector3 position = switch (mode(settings.getTargetMode())) {
            case FORWARD_POINT_PITCH -> forwardPoint(actor, settings.getRange());
            case TARGET_POINT_HEIGHT -> groundPoint(world, actor, settings);
            case TARGET_FACE_RANGE, TARGET_BLOCK_RANGE -> clicked == null
                    ? trace(world, actor, settings) : clicked;
        };
        return applyOffset(actor, position, settings.getTargetOffset());
    }

    /** The first block along the view direction that the trace mask accepts. */
    public static BlockVector3 trace(World world, Actor actor, BrushSettings settings) {
        return trace(world, actor, settings.getRange(), settings.getTraceMask());
    }

    public static BlockVector3 trace(World world, Actor actor, int range, Mask traceMask) {
        BlockVector3 origin = actor.position();
        if (origin == null) {
            return BlockVector3.ZERO;
        }
        Vector3 direction = actor.direction().normalize();
        double eye = 1.62;
        BlockVector3 last = null;
        for (double step = 0; step <= range; step += 0.2) {
            double x = origin.x() + 0.5 + direction.x() * step;
            double y = origin.y() + eye + direction.y() * step;
            double z = origin.z() + 0.5 + direction.z() * step;
            BlockVector3 current = new BlockVector3((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
            if (current.equals(last)) {
                continue;
            }
            last = current;
            if (!world.contains(current.x(), current.y(), current.z())) {
                continue;
            }
            int state = world.getBlock(current.x(), current.y(), current.z());
            boolean hit = traceMask != null
                    ? traceMask.test(current) : !BlockState.registry().isAirLike(state);
            if (hit) {
                return current;
            }
        }
        return origin.add(direction.multiply(range).toBlockPoint());
    }

    /**
     * FAWE's pitch-only aiming: the pitch is rescaled so that a player looking
     * straight ahead reaches {@code 50} blocks, and the distance is projected on
     * the horizontal view direction.
     */
    private static BlockVector3 forwardPoint(Actor actor, int range) {
        BlockVector3 origin = actor.position();
        Vector3 direction = actor.direction();
        double pitch = 23 - (actor.pitch() / 4.0);
        int distance = (int) (Math.sin(Math.toRadians(pitch)) * 50);
        Vector3 flat = new Vector3(direction.x(), 0, direction.z()).normalize().multiply(distance)
                .add(origin.x(), origin.y(), origin.z());
        return flat.toBlockPoint();
    }

    /** The ground below the player, traced at the distance it was found at. */
    private static BlockVector3 groundPoint(World world, Actor actor, BrushSettings settings) {
        BlockVector3 origin = actor.position();
        int y = origin.y();
        while (y > world.minY() && BlockState.registry().isAirLike(world.getBlock(origin.x(), y, origin.z()))) {
            y--;
        }
        int distance = (origin.y() - y) + 8;
        return trace(world, actor, distance, settings.getTraceMask());
    }

    /** Moves the target towards the player, which is what {@code targetoffset} does. */
    private static BlockVector3 applyOffset(Actor actor, BlockVector3 target, int offset) {
        if (offset == 0 || target == null) {
            return target;
        }
        BlockVector3 origin = actor.position();
        Vector3 delta = new Vector3(target.x() - origin.x(), target.y() - origin.y(), target.z() - origin.z());
        if (delta.lengthSq() == 0) {
            return target;
        }
        Vector3 moved = new Vector3(target.x(), target.y(), target.z())
                .subtract(delta.normalize().multiply(offset));
        return moved.toBlockPoint();
    }
}
