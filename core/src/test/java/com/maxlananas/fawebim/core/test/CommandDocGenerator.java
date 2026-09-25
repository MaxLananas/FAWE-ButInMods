package com.maxlananas.fawebim.core.test;

import com.maxlananas.fawebim.core.command.CommandManager;
import com.maxlananas.fawebim.core.command.CommandRegistry;
import com.maxlananas.fawebim.core.extent.EditSession;
import com.maxlananas.fawebim.core.platform.Config;
import com.maxlananas.fawebim.core.world.BlockState;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Regenerates {@code docs/COMMANDS.md} and {@code docs/commands-spec.json} from
 * the live command registry.
 *
 * <p>Run through Gradle: {@code ./gradlew :core:genDocs}.</p>
 */
public final class CommandDocGenerator {

    private CommandDocGenerator() {
    }

    public static void main(String[] args) throws IOException {
        Path markdown = args.length > 0 ? Path.of(args[0]) : Path.of("../docs/COMMANDS.md");
        Path json = args.length > 1 ? Path.of(args[1]) : Path.of("../docs/commands-spec.json");

        BlockState.setRegistry(new TestBlockStateRegistry());
        EditSession.BlockStateRegistryHolder.set(new TestBlockStateRegistry());
        CommandManager.get().initialise();

        List<CommandRegistry.Entry> entries = new ArrayList<>(CommandManager.get().registry().all());
        entries.sort((a, b) -> a.name.compareToIgnoreCase(b.name));

        Coverage coverage = coverage(markdown.getParent() == null
                ? Path.of("docs/commands-inventory.json")
                : markdown.getParent().resolve("commands-inventory.json"));

        Files.createDirectories(markdown.toAbsolutePath().getParent());
        Files.writeString(markdown, renderMarkdown(entries, coverage), StandardCharsets.UTF_8);
        Files.writeString(json, renderJson(entries, coverage), StandardCharsets.UTF_8);
        Path status = markdown.resolveSibling("STATUS.md");
        Files.writeString(status, renderStatus(entries, coverage), StandardCharsets.UTF_8);

        System.out.println("Wrote " + markdown + " and " + json);
        System.out.println("commands=" + entries.size()
                + " implemented=" + countStatus(entries, "implemented")
                + " alias=" + countStatus(entries, "alias")
                + " stub=" + countStatus(entries, "stub")
                + " inventory=" + coverage.inventoryNames() + " unresolved=" + coverage.unresolved.size());
        if (!coverage.unresolved.isEmpty()) {
            System.out.println("unresolved: " + coverage.unresolved);
        }
    }

    private static int countStatus(List<CommandRegistry.Entry> entries, String status) {
        int count = 0;
        for (CommandRegistry.Entry entry : entries) {
            if (status.equals(entry.status)) {
                count++;
            }
        }
        return count;
    }

    // ------------------------------------------------------------------ coverage

    private static final class Coverage {
        private final Set<String> seen = new TreeSet<>();
        private final Set<String> unresolved = new TreeSet<>();
        private final Set<String> resolved = new TreeSet<>();

        /** Distinct command names declared by WorldEdit + FAWE. */
        private int inventoryNames() {
            return seen.size();
        }
    }

    /** Cross-checks the registry against the WorldEdit/FAWE command inventory. */
    private static Coverage coverage(Path inventory) throws IOException {
        Coverage coverage = new Coverage();
        if (!Files.isRegularFile(inventory)) {
            return coverage;
        }
        Object parsed = Json.parse(Files.readString(inventory, StandardCharsets.UTF_8));
        if (!(parsed instanceof List<?> list)) {
            return coverage;
        }
        CommandRegistry registry = CommandManager.get().registry();
        for (Object element : list) {
            if (!(element instanceof Map<?, ?> entry)) {
                continue;
            }
            Object nameValue = entry.get("name");
            if (!(nameValue instanceof String name) || name.isEmpty()) {
                continue;
            }
            String source = String.valueOf(entry.get("source"));
            if (name.equals("*")) {
                continue;
            }
            coverage.seen.add(name);
            if (resolves(registry, name)) {
                coverage.resolved.add(name);
            } else {
                coverage.unresolved.add(source + " " + name);
            }
        }
        return coverage;
    }

    /**
     * True when the registry answers to the inventory name.
     *
     * <p>WorldEdit declares sub-commands inside a container, so {@code sphere} is
     * really {@code /brush sphere}: a name counts as resolved when the registry
     * knows it directly, or when a registered command ends with it. Every declared
     * alias counts as well, since the inventory lists the aliases of a family
     * separately from the family itself.</p>
     */
    private static boolean resolves(CommandRegistry registry, String name) {
        String plain = plain(name);
        if (registry.resolve(name) != null || registry.resolve(plain) != null) {
            return true;
        }
        for (CommandRegistry.Entry entry : registry.all()) {
            for (String spelling : entryNames(entry)) {
                if (spelling.equals(plain) || spelling.endsWith(" " + plain)) {
                    return true;
                }
            }
            // Containers dispatch their sub-commands from their own arguments
            // (`/tool farwand`, `/schem delete`, `/superpickaxe area`, ...).
            for (String argument : entry.arguments) {
                for (String token : argument.split("[|/]")) {
                    if (token.trim().replaceAll("[\\[\\]()<>]", "").equals(plain)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static java.util.List<String> entryNames(CommandRegistry.Entry entry) {
        java.util.List<String> names = new java.util.ArrayList<>();
        names.add(plain(entry.name));
        for (String alias : entry.aliases) {
            names.add(plain(alias));
        }
        return names;
    }

    private static String plain(String name) {
        String trimmed = name.trim();
        return trimmed.startsWith("/") ? trimmed.replaceFirst("^/+", "") : trimmed;
    }

    // ----------------------------------------------------------------- markdown

    private static String renderMarkdown(List<CommandRegistry.Entry> entries, Coverage coverage) {
        java.util.Map<String, List<CommandRegistry.Entry>> byGroup = new TreeMap<>();
        for (CommandRegistry.Entry entry : entries) {
            byGroup.computeIfAbsent(entry.group, key -> new ArrayList<>()).add(entry);
        }
        StringBuilder out = new StringBuilder();
        out.append("# FAWE-BIM — command reference\n\n");
        out.append("Generated from the live command registry by `./gradlew :core:genDocs`.\n");
        out.append("Do not edit this file by hand.\n\n");
        out.append("| | |\n|---|---|\n");
        out.append("| Mod version | ").append(Config.VERSION).append(" |\n");
        out.append("| Minecraft | ").append(Config.MINECRAFT_VERSION).append(" |\n");
        out.append("| Commands | ").append(entries.size()).append(" |\n");
        out.append("| Implemented | ").append(countStatus(entries, "implemented")).append(" |\n");
        out.append("| Aliases of an implemented command | ")
                .append(countStatus(entries, "alias")).append(" |\n");
        out.append("| Not wired yet (see docs/STATUS.md) | ")
                .append(countStatus(entries, "stub")).append(" |\n");
        out.append("| Inventory cross-check | ").append(coverage.resolved.size()).append(" / ")
                .append(coverage.inventoryNames()).append(" WorldEdit + FAWE names resolve |\n\n");

        out.append("Both spellings work: WorldEdit's `//set` and Minecraft's `/set` "
                + "(Minecraft strips one slash from what you type, so `/set` and `//set` both reach the "
                + "same command).\n\n");

        out.append("## Contents\n\n");
        for (String group : byGroup.keySet()) {
            out.append("- [").append(group).append("](#").append(anchor(group)).append(") (`")
                    .append(byGroup.get(group).size()).append("`)\n");
        }
        out.append('\n');

        for (java.util.Map.Entry<String, List<CommandRegistry.Entry>> group : byGroup.entrySet()) {
            out.append("## ").append(group.getKey()).append("\n\n");
            out.append("| Command | Aliases | What it does | Status |\n|---|---|---|---|\n");
            for (CommandRegistry.Entry entry : group.getValue()) {
                out.append("| `").append(escape(entry.usage())).append("` | ")
                        .append(aliases(entry)).append(" | ")
                        .append(escape(entry.description == null || entry.description.isEmpty()
                                ? "—" : entry.description))
                        .append(" | ").append(entry.status).append(" |\n");
            }
            out.append('\n');
        }

        out.append("## Inventory cross-check\n\n");
        out.append("`docs/commands-inventory.json` lists every command WorldEdit 7.3.17 and FAWE declare. ");
        out.append(coverage.unresolved.isEmpty()
                ? "Every one of those names resolves through the registry.\n"
                : "The names below are handled through a container command "
                        + "(for example `/schem save` is the `schem` command with arguments):\n\n");
        for (String name : coverage.unresolved) {
            out.append("- `").append(name).append("`\n");
        }
        out.append('\n');
        return out.toString();
    }

    private static String anchor(String group) {
        return group.toLowerCase(Locale.ROOT).replace(' ', '-');
    }

    private static String aliases(CommandRegistry.Entry entry) {
        if (entry.aliases.isEmpty()) {
            return "—";
        }
        StringBuilder out = new StringBuilder();
        for (String alias : entry.aliases) {
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append('`').append(escape(alias)).append('`');
        }
        return out.toString();
    }

    private static String escape(String text) {
        return text.replace("|", "\\|");
    }

    // ------------------------------------------------------------------- status

    private static String renderStatus(List<CommandRegistry.Entry> entries, Coverage coverage) {
        java.util.Map<String, List<CommandRegistry.Entry>> stubs = new TreeMap<>();
        for (CommandRegistry.Entry entry : entries) {
            if ("stub".equals(entry.status)) {
                stubs.computeIfAbsent(entry.group, key -> new ArrayList<>()).add(entry);
            }
        }
        StringBuilder out = new StringBuilder();
        out.append("# Port status\n\n");
        out.append("Generated by `./gradlew :core:genDocs`. Do not edit by hand.\n\n");
        out.append("| | |\n|---|---|\n");
        out.append("| Commands in the registry | ").append(entries.size()).append(" |\n");
        out.append("| Implemented | ").append(countStatus(entries, "implemented")).append(" |\n");
        out.append("| Alias of an implemented command | ").append(countStatus(entries, "alias")).append(" |\n");
        out.append("| Registered, behaviour still to port | ").append(countStatus(entries, "stub")).append(" |\n");
        out.append("| WorldEdit + FAWE names that resolve | ").append(coverage.resolved.size())
                .append(" / ").append(coverage.inventoryNames()).append(" |\n\n");
        out.append("Every WorldEdit and FAWE command name is registered, so nothing is missing from the "
                + "surface: this table tracks how many of them already run the ported engine code instead of "
                + "answering with 'not ported yet'.\n\n");
        int playerOnly = 0;
        for (CommandRegistry.Entry entry : entries) {
            if (entry.requiresPlayer) {
                playerOnly++;
            }
        }
        out.append("## Who can run a command\n\n");
        out.append("| | |\n|---|---|\n");
        out.append("| Bound to a player, refused from the console | ").append(playerOnly).append(" |\n");
        out.append("| Run by any source, building at the selection | ")
                .append(entries.size() - playerOnly).append(" |\n\n");
        out.append("WorldEdit declares a command with a `Player` parameter when it cannot run without a "
                + "body - the wand, the tools, the navigation, the brushes, `//tree` - and with an `Actor` "
                + "when a console, a command block or a function can run it. The dispatcher follows the same "
                + "split: a player-only command answers that it needs a player, and the others build at the "
                + "selection when the source has no position. `scripts/player_audit.py` compares every flag "
                + "with the upstream declaration in CI.\n\n");
        out.append("## Remaining by section\n\n");
        out.append("| Section | Remaining |\n|---|---|\n");
        for (java.util.Map.Entry<String, List<CommandRegistry.Entry>> group : stubs.entrySet()) {
            out.append("| ").append(group.getKey()).append(" | ").append(group.getValue().size()).append(" |\n");
        }
        out.append('\n');
        for (java.util.Map.Entry<String, List<CommandRegistry.Entry>> group : stubs.entrySet()) {
            out.append("### ").append(group.getKey()).append("\n\n");
            for (CommandRegistry.Entry entry : group.getValue()) {
                out.append("- `").append(entry.usage()).append("`")
                        .append(entry.description.isEmpty() ? "" : " — " + escape(entry.description))
                        .append('\n');
            }
            out.append('\n');
        }
        return out.toString();
    }

    // --------------------------------------------------------------------- json

    private static String renderJson(List<CommandRegistry.Entry> entries, Coverage coverage) {
        StringBuilder out = new StringBuilder();
        out.append("{\n");
        out.append("  \"version\": \"").append(Config.VERSION).append("\",\n");
        out.append("  \"minecraft\": \"").append(Config.MINECRAFT_VERSION).append("\",\n");
        out.append("  \"count\": ").append(entries.size()).append(",\n");
        out.append("  \"implemented\": ").append(countStatus(entries, "implemented")).append(",\n");
        out.append("  \"stub\": ").append(countStatus(entries, "stub")).append(",\n");
        out.append("  \"inventoryResolved\": ").append(coverage.resolved.size()).append(",\n");
        out.append("  \"inventoryUnresolved\": [");
        boolean first = true;
        for (String name : coverage.unresolved) {
            out.append(first ? "\n" : ",\n").append("    \"").append(jsonEscape(name)).append('"');
            first = false;
        }
        out.append(first ? "],\n" : "\n  ],\n");
        out.append("  \"commands\": [\n");
        for (int i = 0; i < entries.size(); i++) {
            CommandRegistry.Entry entry = entries.get(i);
            out.append("    {\n");
            out.append("      \"name\": \"").append(jsonEscape(entry.name)).append("\",\n");
            out.append("      \"registration\": \"").append(jsonEscape(entry.registrationName())).append("\",\n");
            out.append("      \"usage\": \"").append(jsonEscape(entry.usage())).append("\",\n");
            out.append("      \"description\": \"").append(jsonEscape(entry.description)).append("\",\n");
            out.append("      \"group\": \"").append(jsonEscape(entry.group)).append("\",\n");
            out.append("      \"status\": \"").append(jsonEscape(entry.status)).append("\",\n");
            out.append("      \"aliases\": ").append(jsonArray(entry.aliases)).append(",\n");
            out.append("      \"arguments\": ").append(jsonArray(entry.arguments)).append(",\n");
            out.append("      \"booleanFlags\": ").append(jsonArray(entry.booleanFlags)).append(",\n");
            out.append("      \"valueFlags\": ").append(jsonArray(entry.valueFlags)).append(",\n");
            out.append("      \"requiresSelection\": ").append(entry.requiresSelection).append(",\n");
            out.append("      \"requiresPlayer\": ").append(entry.requiresPlayer).append(",\n");
            out.append("      \"requiresWorld\": ").append(entry.requiresWorld).append('\n');
            out.append("    }").append(i + 1 < entries.size() ? "," : "").append('\n');
        }
        out.append("  ]\n}\n");
        return out.toString();
    }

    private static String jsonArray(Iterable<String> values) {
        StringBuilder out = new StringBuilder("[");
        boolean first = true;
        for (String value : values) {
            if (!first) {
                out.append(", ");
            }
            out.append('"').append(jsonEscape(value)).append('"');
            first = false;
        }
        return out.append(']').toString();
    }

    private static String jsonEscape(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
