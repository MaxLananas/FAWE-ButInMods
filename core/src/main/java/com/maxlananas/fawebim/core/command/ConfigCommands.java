package com.maxlananas.fawebim.core.command;

import com.maxlananas.fawebim.core.platform.Config;
import com.maxlananas.fawebim.core.platform.Setting;
import com.maxlananas.fawebim.core.util.Msg;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The in-game configuration surface.
 *
 * <p>Every value of {@code config/fawebim.yml} is reachable from the chat, so a
 * player never has to leave the game to tune the engine: {@code /fawebim
 * settings} lists the keys with their current values, {@code /fawebim set} edits
 * one and writes the file straight away, and {@code /fawebim reload} picks up a
 * file edited by hand. Each setting is described in one line by its declaration
 * in {@link Config}, which is also what the file writes.</p>
 */
final class ConfigCommands {

    private final CommandRegistry registry;

    ConfigCommands(CommandRegistry registry) {
        this.registry = registry;
    }

    void register() {
        root();
    }

    private void root() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("/fawebim", "//fawebim", "/fbm");
        if (entry == null) {
            return;
        }
        entry.description = "Open the configuration screen, or read and set the values in chat";
        entry.group = "utility";
        // p pages the listing, s narrows it to one type of setting.
        entry.valueFlags.add("p");
        entry.valueFlags.add("s");
        entry.arguments.add("[gui|settings|set|reset|reload|save|path]");
        // Typing /fawebim <tab> suggests the actions, /fawebim set <tab> the keys.
        entry.suggestions = typed -> {
            java.util.List<String> completions = new ArrayList<>();
            String remaining = typed.stripLeading();
            String[] words = remaining.isEmpty() ? new String[0] : remaining.split("\\s+", -1);
            String last = lastWord(remaining);
            if (words.length <= 1) {
                completions.addAll(List.of("gui", "settings", "set", "reset", "reload", "save", "path"));
                keys(completions, last);
                return completions;
            }
            switch (words[0].toLowerCase(Locale.ROOT)) {
                case "set" -> {
                    // Past the key, the useful completion is a value the key takes.
                    if (words.length <= 2) {
                        keys(completions, last);
                    } else {
                        values(completions, words[1], last);
                    }
                }
                case "reset" -> keys(completions, last);
                default -> {
                }
            }
            return completions;
        };
        entry.handler = ctx -> {
            String action = ctx.arg(0, "").toLowerCase(Locale.ROOT);
            switch (action) {
                // Bare /fawebim is the shortest way to the settings: a client that
                // can draw the screen gets it, everything else gets the listing.
                case "" -> {
                    if (!screen(ctx)) {
                        settings(ctx);
                    }
                }
                case "gui", "screen" -> {
                    if (!screen(ctx)) {
                        ctx.actor().message(Msg.warn("There is no configuration screen here;"
                                + " use /fawebim settings to read and set the values in chat"));
                    }
                }
                case "settings", "list", "show" -> settings(ctx);
                case "set" -> set(ctx);
                case "reset" -> reset(ctx);
                case "reload" -> reload(ctx);
                case "save" -> save(ctx);
                case "path" -> path(ctx);
                default -> usage(ctx);
            }
        };
    }

    /** The engine's configuration surface, shared with the screen. */
    private static com.maxlananas.fawebim.core.platform.ConfigUi ui() {
        return new com.maxlananas.fawebim.core.platform.ConfigUi(Config.get());
    }

    /** Opens the graphical settings screen when the actor has one. */
    private static boolean screen(Ctx ctx) {
        return ctx.actor().openConfigurationScreen();
    }

    /** {@code /fawebim settings [filter] [-p <page>] [-s <type>]} — the listing. */
    private void settings(Ctx ctx) {
        Config config = Config.get();
        String filter = ctx.arg(1, "").toLowerCase(Locale.ROOT);
        Setting.Kind kind = kindOf(ctx.flagValue("s", ""));
        List<Setting<?>> matches = new ArrayList<>();
        for (Setting<?> setting : config.settings()) {
            if (kind != null && setting.kind() != kind) {
                continue;
            }
            if (!filter.isEmpty()
                    && !setting.key().contains(filter) && !setting.path().toLowerCase(Locale.ROOT).contains(filter)) {
                continue;
            }
            matches.add(setting);
        }
        if (matches.isEmpty()) {
            ctx.actor().message(Msg.warn("No setting matches '" + filter + "'"));
            return;
        }
        if (!filter.isEmpty() && matches.size() == 1) {
            describe(ctx, matches.get(0));
            return;
        }
        Page page = Page.of(ctx, matches.size());
        ctx.actor().message(Msg.info(Msg.title("Settings") + "\u00a77 (" + matches.size() + ", page " + page.number() + "/"
                + page.pages() + "):"));
        for (Setting<?> setting : matches.subList(page.from(), page.to())) {
            ctx.actor().message(Msg.of("\u00a77 - \u00a7f" + setting.key() + " \u00a77= \u00a7a" + setting.value()
                    + " \u00a78(" + setting.path() + ")"));
        }
        page.hint(ctx, "/fawebim settings" + (filter.isEmpty() ? "" : " " + filter));
    }

    /** One setting, with what it does and how it is spelled in the file. */
    private void describe(Ctx ctx, Setting<?> setting) {
        ctx.actor().message(Msg.keyValue(setting.key(), setting.value()));
        ctx.actor().message(Msg.of("\u00a77  " + setting.description()));
        ctx.actor().message(Msg.of("\u00a77  File path: \u00a7f" + setting.path()
                + " \u00a77| Default: \u00a7f" + setting.defaultValue()));
    }

    /** {@code /fawebim set <key> <value>} — edits a value and writes the file. */
    private void set(Ctx ctx) {
        if (ctx.args().size() < 2) {
            throw CommandRegistry.error("Usage: /fawebim set <key> <value>, for example"
                    + " /fawebim set max-brush-radius 50");
        }
        String name = ctx.arg(1);
        Setting<?> setting = ui().resolve(name);
        if (setting == null) {
            throw CommandRegistry.error("Unknown setting '" + name
                    + "'. Use /fawebim settings to list them");
        }
        if (ctx.args().size() < 3) {
            throw CommandRegistry.error(setting.key() + " holds " + setting.value()
                    + " and expects " + setting.expected() + ". /fawebim set " + setting.key() + " <value>");
        }
        String value = ctx.joined(2);
        String before = setting.value();
        // Config holds the message a rejected value deserves: what it expected and
        // what the setting holds now.
        String error = Config.get().set(setting.key(), value);
        if (error != null) {
            throw CommandRegistry.error(error);
        }
        ctx.actor().message(Msg.success(setting.key() + ": " + before + " -> " + setting.value()
                + " (saved to config/fawebim.yml)"));
    }

    /** {@code /fawebim reset <key>} — puts one value back to the shipped default. */
    private void reset(Ctx ctx) {
        String name = ctx.arg(1);
        Setting<?> setting = ui().resolve(name);
        if (setting == null) {
            throw CommandRegistry.error("Unknown setting '" + name + "'. Use /fawebim settings to list them");
        }
        String before = setting.value();
        setting.reset();
        Config.get().save();
        ctx.actor().message(Msg.success(setting.key() + ": " + before + " -> " + setting.value()
                + " (default)"));
    }

    private void reload(Ctx ctx) {
        Config.get().reload();
        ctx.actor().message(Msg.success("Configuration reloaded from config/fawebim.yml"));
    }

    private void save(Ctx ctx) {
        Config.get().save();
        ctx.actor().message(Msg.success("Configuration written to config/fawebim.yml"));
    }

    private void path(Ctx ctx) {
        java.nio.file.Path file = Config.get().configFile();
        ctx.actor().message(Msg.keyValue("Configuration file",
                file == null ? "config/fawebim.yml" : file.toString()));
        ctx.actor().message(Msg.of("\u00a77  " + Config.get().settings().size()
                + " settings, editable in /fawebim gui, here, or in the file"));
    }

    private void usage(Ctx ctx) {
        ctx.actor().message(Msg.info("Usage: /fawebim gui | settings [filter] | set <key> <value>"
                + " | reset <key> | reload | save | path"));
    }

    /** Every setting key, narrowed to the ones starting with what was typed. */
    private static void keys(List<String> completions, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        for (Setting<?> setting : Config.get().settings()) {
            if (setting.key().toLowerCase(Locale.ROOT).startsWith(lower)) {
                completions.add(setting.key());
            }
        }
    }

    /**
     * The values a setting accepts, narrowed to what was typed: a switch offers
     * its two spellings, anything else offers what it holds now, which is what a
     * player usually wants to change to something else.
     */
    private static void values(List<String> completions, String key, String prefix) {
        // The same three spellings the set command accepts: the value completion
        // follows the name that was typed.
        Setting<?> setting = ui().resolve(key);
        if (setting == null) {
            return;
        }
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> candidates = setting.kind() == Setting.Kind.BOOLEAN
                ? List.of("true", "false")
                : List.of(setting.value());
        for (String candidate : candidates) {
            if (candidate.toLowerCase(Locale.ROOT).startsWith(lower)) {
                completions.add(candidate);
            }
        }
    }

    /** The word being typed, which is what a completion has to match. */
    private static String lastWord(String typed) {
        String trimmed = typed.endsWith(" ") ? "" : typed;
        int space = trimmed.lastIndexOf(' ');
        return space < 0 ? trimmed : trimmed.substring(space + 1);
    }

    /** The type flag of a listing, or null when every type is shown. */
    private static Setting.Kind kindOf(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "b", "boolean", "bool", "switch" -> Setting.Kind.BOOLEAN;
            case "i", "int", "integer", "number" -> Setting.Kind.INTEGER;
            case "t", "text", "string" -> Setting.Kind.TEXT;
            default -> null;
        };
    }
}
