package com.maxlananas.fawebim.core.util;

/**
 * Refused input: an argument that cannot be used, a file that is not a
 * schematic, a name that is not allowed. It is something the player can fix,
 * so the message is written for them and shown as it is; any other exception
 * out of a command is treated as a bug and logged.
 *
 * <p>The engine's lower layers throw this rather than the command layer's own
 * exception, so a schematic reader does not depend on the command package.</p>
 */
public class InputException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InputException(String message) {
        super(message);
    }
}
