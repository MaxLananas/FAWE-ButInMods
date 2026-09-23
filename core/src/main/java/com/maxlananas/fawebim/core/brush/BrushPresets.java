package com.maxlananas.fawebim.core.brush;

import com.maxlananas.fawebim.core.platform.Config;
import com.maxlananas.fawebim.core.session.LocalSession;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Saved brush presets, the {@code /brush savebrush} family.
 *
 * <p>A preset is the {@code /brush ...} command line that created the brush, so
 * loading one rebuilds exactly the brush the player saved, settings included.</p>
 */
public final class BrushPresets {

    private BrushPresets() {
    }

    private static Path directory() {
        return Config.get().resolveDirectory(Config.get().brushPresetDirectory);
    }

    /** Saves the brush currently bound to the player's item. */
    public static Path save(LocalSession session, String name) throws IOException {
        Object line = session.getBindings().get("brush-command");
        if (line == null) {
            return null;
        }
        Path folder = directory();
        Files.createDirectories(folder);
        Path file = folder.resolve(safe(name) + ".txt");
        Files.writeString(file, line + System.lineSeparator());
        return file;
    }

    /** The command line of a saved preset, or null when it does not exist. */
    public static String load(String name) throws IOException {
        Path file = directory().resolve(safe(name) + ".txt");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        return Files.readString(file).trim();
    }

    /** The names of the saved presets, sorted. */
    public static List<String> list() {
        Path folder = directory();
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        try (Stream<Path> files = Files.list(folder)) {
            files.filter(path -> path.getFileName().toString().endsWith(".txt"))
                    .forEach(path -> names.add(path.getFileName().toString().replace(".txt", "")));
        } catch (IOException e) {
            return List.of();
        }
        names.sort(String::compareToIgnoreCase);
        return names;
    }

    private static String safe(String name) {
        String cleaned = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
        if (cleaned.isEmpty()) {
            throw new IllegalArgumentException("Invalid preset name");
        }
        return cleaned;
    }
}
