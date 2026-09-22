package com.fawebutinmods.core.tool;

import com.fawebutinmods.core.actor.Actor;
import com.fawebutinmods.core.util.Msg;
import com.fawebutinmods.core.world.Direction;

/**
 * A tool bound to an item: the WorldEdit tools ({@code /tool tree},
 * {@code /tool repl}, ...) and FAWE's extra ones ({@code /tool stacker},
 * {@code /tool farwand}, {@code /tool lrbuild}, {@code /tool floodfill}).
 */
public interface Tool {

    /** The name used by {@code /tool <name>}. */
    String name();

    /** Called when the player left-clicks a block with the bound item. */
    default boolean onLeftClick(ToolContext context) {
        return false;
    }

    /** Called when the player right-clicks a block with the bound item. */
    default boolean onRightClick(ToolContext context) {
        return false;
    }

    /** Called when the player swings the item. */
    default boolean onSwing(ToolContext context) {
        return false;
    }

    String describe();

    /** Everything a tool needs to act. */
    final class ToolContext {

        public final Actor actor;
        public final com.fawebutinmods.core.math.BlockVector3 position;
        public final Direction face;
        public final com.fawebutinmods.core.extent.EditSession session;

        public ToolContext(Actor actor, com.fawebutinmods.core.math.BlockVector3 position, Direction face,
                           com.fawebutinmods.core.extent.EditSession session) {
            this.actor = actor;
            this.position = position;
            this.face = face;
            this.session = session;
        }

        public void message(Msg message) {
            actor.message(message);
        }

        public boolean hasSession() {
            return session != null;
        }
    }
}
