package com.maxlananas.fawebim.core.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * Files a player names on a command line, kept inside the folder of the mod
 * they belong to.
 *
 * <p>A macro, an image or a heightmap is looked up by a name the player typed.
 * Resolved as it is, {@code ../../server.properties} or an absolute path reads
 * any file the server can: a macro runs its lines as commands and echoes the
 * ones it cannot run, an image read tells a file that exists from one that
 * does not. The name may go down into sub-folders, never up or out.</p>
 */
public final class SafePaths {

    private SafePaths() {
    }

    /**
     * The file {@code name} names inside {@code folder}.
     *
     * @param allowSymlinks whether a link inside the folder may lead out of it,
     *                      which is {@code files.allow-symbolic-links}
     * @param what          what the name is of, for the error: "macro", "image"
     * @throws InputException when the name is empty, malformed, or leads out
     */
    public static Path inside(Path folder, String name, boolean allowSymlinks, String what) {
        if (name == null || name.isBlank()) {
            throw new InputException("No " + what + " name given");
        }
        Path base = folder.toAbsolutePath().normalize();
        Path path;
        try {
            path = base.resolve(name).normalize();
        } catch (InvalidPathException e) {
            throw new InputException("Invalid " + what + " name '" + name + "'");
        }
        if (!path.startsWith(base) || path.equals(base)) {
            throw new InputException("Invalid " + what + " name '" + name + "': it has to stay inside "
                    + base.getFileName());
        }
        if (!allowSymlinks && Files.exists(path)) {
            try {
                Path real = path.toRealPath();
                Path realBase = base.toRealPath();
                if (!real.startsWith(realBase)) {
                    throw new InputException("Symbolic links are disabled"
                            + " (files.allow-symbolic-links in config/fawebim.yml)");
                }
            } catch (IOException e) {
                throw new InputException("Could not read the " + what + " '" + name + "'");
            }
        }
        return path;
    }
}
