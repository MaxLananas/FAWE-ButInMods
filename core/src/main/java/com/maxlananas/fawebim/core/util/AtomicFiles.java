package com.maxlananas.fawebim.core.util;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Writes a file so that it is either the old one or the new one, never half of
 * the new one.
 *
 * <p>A schematic, a history file or the configuration written straight over
 * its target is cut short by a crash, a full disk or a server killed mid-write,
 * and the next read finds a truncated file - the old content is gone as well.
 * The bytes go to a temporary file next to the target first, which is then
 * moved over it in one step.</p>
 */
public final class AtomicFiles {

    /** Writes the content of a file into the stream it is handed. */
    @FunctionalInterface
    public interface Content {

        void writeTo(OutputStream out) throws IOException;
    }

    private AtomicFiles() {
    }

    public static Path write(Path target, byte[] data) throws IOException {
        return write(target, out -> out.write(data));
    }

    public static Path writeString(Path target, String text) throws IOException {
        return write(target, text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public static Path write(Path target, Content content) throws IOException {
        Path directory = target.toAbsolutePath().getParent();
        if (directory != null) {
            Files.createDirectories(directory);
        }
        Path temporary = Files.createTempFile(directory, "." + target.getFileName(), ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(temporary)) {
                content.writeTo(out);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                // A file system without an atomic rename still gets the complete
                // file in one move rather than a write over the old one.
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return target;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
