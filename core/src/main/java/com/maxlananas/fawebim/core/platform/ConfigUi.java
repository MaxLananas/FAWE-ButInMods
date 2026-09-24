package com.maxlananas.fawebim.core.platform;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The model behind the in-game configuration screen.
 *
 * <p>What settings exist, how they group, which of them a search matches and what
 * a value has to look like is the same on every platform, so it lives here: the
 * platform side only draws this and hands back what a player typed. The chat
 * command {@code /fawebim} and the screen therefore edit the same table through
 * the same methods, and a change made in either shows up in the other.</p>
 */
public final class ConfigUi {

    /** One group of settings, as the sidebar of the screen lists them. */
    public record Group(String name, List<Setting<?>> settings) {
    }

    /** The name of the group that holds settings no rule claims. */
    private static final String GENERAL = "General";

    /** The order the sidebar uses; a group with no settings is left out. */
    private static final List<String> ORDER =
            List.of("Editing", "Brushes", "Tools", "History", "Performance", "Files", GENERAL);

    private final Config config;

    public ConfigUi(Config config) {
        this.config = config;
    }

    /** The settings of the configuration, grouped for the sidebar. */
    public List<Group> groups() {
        Map<String, List<Setting<?>>> byName = new LinkedHashMap<>();
        for (String name : ORDER) {
            byName.put(name, new ArrayList<>());
        }
        for (Setting<?> setting : config.settings()) {
            byName.computeIfAbsent(groupOf(setting), name -> new ArrayList<>()).add(setting);
        }
        List<Group> groups = new ArrayList<>();
        for (Map.Entry<String, List<Setting<?>>> entry : byName.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                groups.add(new Group(entry.getKey(), List.copyOf(entry.getValue())));
            }
        }
        return groups;
    }

    /**
     * The settings of one group, narrowed to the ones a search text matches.
     *
     * @param group the group to show, {@code null} or {@code ""} for all of them
     * @param query the text typed in the search box, matched against the key and
     *              the file path the way {@code /fawebim settings <filter>} does
     */
    public List<Setting<?>> settings(String group, String query) {
        String filter = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        String wanted = group == null ? "" : group.trim();
        List<Setting<?>> matches = new ArrayList<>();
        for (Group entry : groups()) {
            if (!wanted.isEmpty() && !entry.name().equalsIgnoreCase(wanted)) {
                continue;
            }
            for (Setting<?> setting : entry.settings()) {
                if (filter.isEmpty()
                        || setting.key().toLowerCase(Locale.ROOT).contains(filter)
                        || setting.path().toLowerCase(Locale.ROOT).contains(filter)
                        || setting.description().toLowerCase(Locale.ROOT).contains(filter)) {
                    matches.add(setting);
                }
            }
        }
        return matches;
    }

    /** The group a setting is listed under. */
    private static String groupOf(Setting<?> setting) {
        String path = setting.path();
        if (path.startsWith("limits.max-brush") || path.startsWith("brushes.")) {
            return "Brushes";
        }
        if (path.startsWith("wand-item") || path.startsWith("navigation-wand.")
                || path.startsWith("super-pickaxe.")) {
            return "Tools";
        }
        if (path.startsWith("history.") || path.startsWith("snapshots.")) {
            return "History";
        }
        if (path.startsWith("queue.") || path.equals("threads") || path.startsWith("calculation.")
                || path.equals("debug")) {
            return "Performance";
        }
        if (path.startsWith("saving.") || path.startsWith("macros.") || path.startsWith("scripting.")
                || path.startsWith("files.") || path.equals("limits.max-schematic-size")) {
            return "Files";
        }
        if (path.startsWith("limits.") || path.startsWith("regen.") || path.equals("command-block-support")
                || path.equals("allow-ancient-blocks") || path.equals("allow-non-player-entities")) {
            return "Editing";
        }
        return GENERAL;
    }

    /**
     * Writes a value typed in the screen or on the command line, and saves the
     * file straight away so the value survives a restart.
     *
     * @return an error message when the value does not fit the setting, or
     *         {@code null} when it was applied and stored
     */
    public String set(String key, String rawValue) {
        Setting<?> setting = find(key);
        if (setting == null) {
            return "Unknown setting '" + key + "'";
        }
        String error = setting.apply(rawValue);
        if (error != null) {
            return setting.key() + " expects " + expectedOf(setting);
        }
        config.save();
        return null;
    }

    /** Puts one setting back to the value the mod ships with, and saves. */
    public boolean reset(String key) {
        Setting<?> setting = find(key);
        if (setting == null) {
            return false;
        }
        setting.reset();
        config.save();
        return true;
    }

    /** Re-reads {@code config/fawebim.yml}, as the button of the screen does. */
    public void reloadFromDisk() {
        config.reload();
    }

    /** Writes the file without changing anything. */
    public void saveToDisk() {
        config.save();
    }

    /** The setting a key or a file path names, or null when nothing matches. */
    /**
     * A setting from what a user typed: its key, the path it has in the file, or
     * the end of that path. {@code max-blocks-changed.default} and
     * {@code limits.max-blocks-changed.default} name the same setting, and so
     * does {@code default-change-limit}.
     */
    public Setting<?> resolve(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String wanted = name.trim();
        Setting<?> exact = find(wanted);
        if (exact != null) {
            return exact;
        }
        Setting<?> match = null;
        for (Setting<?> setting : config.settings()) {
            String path = setting.path();
            if (!path.equalsIgnoreCase(wanted) && !path.toLowerCase(java.util.Locale.ROOT)
                    .endsWith("." + wanted.toLowerCase(java.util.Locale.ROOT))) {
                continue;
            }
            if (match != null) {
                return null;
            }
            match = setting;
        }
        return match;
    }

    public Setting<?> find(String key) {
        return key == null ? null : config.find(key);
    }

    /** The value of a setting, as the screen prints it, or an empty text. */
    public String value(String key) {
        Setting<?> setting = find(key);
        return setting == null ? "" : setting.value();
    }

    /** How many settings the configuration holds. */
    public int size() {
        return config.settings().size();
    }

    /** The file the values are stored in, for the line at the bottom of the screen. */
    public String path() {
        java.nio.file.Path file = config.configFile();
        return file == null ? "config/fawebim.yml" : file.toString();
    }

    /** What a value of this setting has to look like, in a player's words. */
    public static String expectedOf(Setting<?> setting) {
        return switch (setting.kind()) {
            case BOOLEAN -> "true or false";
            case INTEGER -> "a whole number";
            case TEXT -> "text";
        };
    }
}
