package com.fawebutinmods.core.brush;

import com.fawebutinmods.core.actor.Actor;
import com.fawebutinmods.core.extent.EditSession;
import com.fawebutinmods.core.mask.Mask;
import com.fawebutinmods.core.math.BlockVector3;
import com.fawebutinmods.core.pattern.Pattern;

/**
 * A brush: a shape plus a behaviour, applied where the player right-clicks.
 *
 * <p>FAWE's brushes all share this contract; settings such as the mask, the
 * size and the "fill vs shell" flag live in {@link BrushSettings}.</p>
 */
public interface Brush extends Cloneable {

    /** Radius of the brush shape. */
    double radius();

    /** Changes the brush size, as {@code /tool size} does. */
    default void setRadius(double radius) {
    }

    /** Changes the material the brush places, as {@code /tool material} does. */
    default void setFill(Pattern fill) {
    }

    /** The mask limiting which blocks the brush may touch, or null. */
    Mask mask();

    void setMask(Mask mask);

    /** Whether the brush only writes in the shell of its shape. */
    boolean hollow();

    void setHollow(boolean hollow);

    /** Applies the brush at the given position, returning the number of changed blocks. */
    int apply(EditSession session, BlockVector3 position, Actor actor);

    /** Human readable description for {@code /brush} feedback. */
    String describe();

    /** Whether a left-click applies this brush (some brushes do). */
    default boolean leftClick() {
        return false;
    }

    BrushSettings settings();
}
