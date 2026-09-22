package com.fawebutinmods.core.command;

import com.fawebutinmods.core.actor.Actor;

/**
 * Entry point used when a command has to be run from inside another operation:
 * {@code /brush command}, the {@code /tool} bindings, {@code //paste -c} and the
 * schematic macros.
 */
public final class BrushCommands {

    private static CommandDispatcher dispatcher;

    private BrushCommands() {
    }

    /** Installed by the platform so brushes can execute command lines. */
    public static void setDispatcher(CommandDispatcher value) {
        dispatcher = value;
    }

    public static boolean run(Actor actor, String line) {
        if (dispatcher == null) {
            actor.message(com.fawebutinmods.core.util.Msg.error("Command dispatch is not available yet"));
            return false;
        }
        return dispatcher.dispatch(actor, line);
    }

    public interface CommandDispatcher {
        boolean dispatch(Actor actor, String line);
    }
}
