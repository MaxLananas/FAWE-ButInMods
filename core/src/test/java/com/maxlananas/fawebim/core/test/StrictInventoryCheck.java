package com.maxlananas.fawebim.core.test;

import com.maxlananas.fawebim.core.actor.Actor;
import com.maxlananas.fawebim.core.command.CommandManager;
import com.maxlananas.fawebim.core.extent.EditSession;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.world.BlockState;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs every WorldEdit and FAWE command name through the dispatcher.
 *
 * <p>The documentation rule accepts a name when a registered command ends with
 * it, which is how a sub-command of {@code /brush} counts as covered. That is
 * too generous to prove an implementation exists: this check types the name the
 * way a player would and fails when the answer is "unknown command" or "not
 * implemented", so a name that no handler really answers shows up here.</p>
 */
public final class StrictInventoryCheck {

    /** Declaring file to the container a sub-command is reached through. */
    private static final Map<String, String> CONTAINERS = Map.ofEntries(
            Map.entry("BrushCommands.java", "brush"),
            Map.entry("PaintBrushCommands.java", "brush"),
            Map.entry("ApplyBrushCommands.java", "brush"),
            Map.entry("ToolCommands.java", "tool"),
            Map.entry("ToolUtilCommands.java", "tool"),
            Map.entry("SuperPickaxeCommands.java", "superpickaxe"),
            Map.entry("SchematicCommands.java", "schem"),
            Map.entry("HistorySubCommands.java", "history"),
            Map.entry("SnapshotCommands.java", "snapshot"),
            Map.entry("SnapshotUtilCommands.java", "snapshot"),
            Map.entry("AnvilCommands.java", "anvil"),
            Map.entry("CFICommands.java", "cfi"),
            Map.entry("WorldEditCommands.java", "we"),
            Map.entry("ListFilters.java", "list"));

    public static void main(String[] args) throws Exception {
        Path inventory = Path.of(args.length > 0 ? args[0] : "docs/commands-inventory.json");
        BlockState.setRegistry(new TestBlockStateRegistry());
        EditSession.BlockStateRegistryHolder.set(new TestBlockStateRegistry());
        CommandManager manager = CommandManager.get();
        manager.initialise();

        TestWorld world = new TestWorld("inventory");
        world.fillFlat(70);
        TestActor actor = new TestActor("Audit", world, new BlockVector3(0, 71, 0));

        Map<String, String> declared = new LinkedHashMap<>();
        Object parsed = Json.parse(Files.readString(inventory, StandardCharsets.UTF_8));
        collect(parsed, declared);

        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, String> entry : declared.entrySet()) {
            List<String> lines = commandLines(entry.getKey(), entry.getValue());
            if (lines.isEmpty()) {
                continue;
            }
            String container = CONTAINERS.get(entry.getValue());
            boolean answered = false;
            for (String line : lines) {
                boolean ok = container != null && line.startsWith("/" + container + " ")
                        ? handles(manager, actor, line, container)
                        : answers(manager, actor, line);
                if (ok) {
                    answered = true;
                    break;
                }
            }
            if (!answered) {
                missing.add(lines.get(0) + "    (" + entry.getKey() + ")");
            }
        }
        System.out.println("inventory names: " + declared.size()
                + ", not answered by the dispatcher: " + missing.size());
        for (String line : missing) {
            System.out.println("  " + line);
        }
        if (!missing.isEmpty()) {
            System.exit(1);
        }
    }

    /**
     * The lines a player could type for a declared name. A name declared in a
     * container is reachable through that container, and some of those names are
     * declared as top-level commands as well ({@code /s} for {@code /brush s}),
     * so both spellings are tried.
     */
    private static List<String> commandLines(String name, String file) {
        String bare = name.startsWith("/") ? name.replaceFirst("^/+", "") : name;
        if (bare.isEmpty() || bare.equals("*") || bare.startsWith("-") || bare.startsWith(".")) {
            return List.of();
        }
        if (!bare.matches("[a-z0-9_.+-]+")) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        String container = CONTAINERS.get(file);
        if (container != null) {
            lines.add("/" + container + " " + bare);
        }
        lines.add("/" + bare);
        return lines;
    }

    /** True when the dispatcher recognised the command and did not call it a stub. */
    private static boolean answers(CommandManager manager, Actor actor, String line) {
        List<String> answer = answer(manager, actor, line);
        if (answer.isEmpty()) {
            return false;
        }
        for (String message : answer) {
            if (message.contains("Unknown command") || message.contains("not implemented")) {
                return false;
            }
        }
        return true;
    }

    /** True when the sub-command does something of its own, not just the container help. */
    private static boolean handles(CommandManager manager, Actor actor, String line, String container) {
        List<String> answer = answer(manager, actor, line);
        if (answer.isEmpty()) {
            return false;
        }
        for (String message : answer) {
            if (message.contains("Unknown command") || message.contains("not implemented")) {
                return false;
            }
        }
        return !answer.equals(answer(manager, actor, "/" + container + " zzznotasubcommand"));
    }

    /** What the command printed, in order. */
    private static List<String> answer(CommandManager manager, Actor actor, String line) {
        TestActor probe = (TestActor) actor;
        probe.messages().clear();
        manager.dispatch(actor, line);
        return new ArrayList<>(probe.messages());
    }

    private static void collect(Object node, Map<String, String> declared) {
        if (node instanceof Map<?, ?> map) {
            for (Object value : map.values()) {
                collect(value, declared);
            }
            return;
        }
        if (!(node instanceof List<?> list)) {
            return;
        }
        for (Object element : list) {
            if (!(element instanceof Map<?, ?> row)) {
                continue;
            }
            if (!(row.get("name") instanceof String name) || !row.containsKey("file")) {
                collect(element, declared);
                continue;
            }
            String file = String.valueOf(row.get("file"));
            file = file.substring(file.lastIndexOf('/') + 1);
            declared.putIfAbsent(name, file);
            if (row.get("aliases") instanceof List<?> aliases) {
                for (Object alias : aliases) {
                    if (alias instanceof String text && !text.isEmpty()) {
                        declared.putIfAbsent(text, file);
                    }
                }
            }
        }
    }

    private StrictInventoryCheck() {
    }
}
