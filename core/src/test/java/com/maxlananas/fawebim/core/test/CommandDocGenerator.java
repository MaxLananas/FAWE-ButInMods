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
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Writes the machine-readable command surface of the live registry to JSON.
 *
 * <p>Used by the audits in {@code scripts/} to compare the port against the
 * upstream WorldEdit and FastAsyncWorldEdit command declarations.</p>
 */
public final class CommandDocGenerator {

    private CommandDocGenerator() {
    }

    public static void main(String[] args) throws IOException {
        Path json = args.length > 0 ? Path.of(args[0]) : Path.of("build/commands-spec.json");
        Path inventory = args.length > 1
                ? Path.of(args[1])
                : Path.of("reference/commands-inventory.json");

        BlockState.setRegistry(new TestBlockStateRegistry());
        EditSession.BlockStateRegistryHolder.set(new TestBlockStateRegistry());
        CommandManager.get().initialise();

        List<CommandRegistry.Entry> entries = new ArrayList<>(CommandManager.get().registry().all());
        entries.sort((a, b) -> a.name.compareToIgnoreCase(b.name));

        Coverage coverage = coverage(inventory);

        Files.createDirectories(json.toAbsolutePath().getParent());
        Files.writeString(json, renderJson(entries, coverage), StandardCharsets.UTF_8);

        System.out.println("Wrote " + json);
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
