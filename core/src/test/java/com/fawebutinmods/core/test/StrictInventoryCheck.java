package com.fawebutinmods.core.test;

import com.fawebutinmods.core.command.CommandManager;
import com.fawebutinmods.core.command.CommandRegistry;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Checks every WorldEdit and FAWE command name against the registry with the
 * same lookup the dispatcher uses, so a name that only the fuzzy documentation
 * rule accepts shows up here.
 */
public final class StrictInventoryCheck {

    public static void main(String[] args) throws Exception {
        Path inventory = Path.of(args.length > 0 ? args[0] : "docs/commands-inventory.json");
        CommandManager manager = CommandManager.get();
        manager.initialise();
        CommandRegistry registry = manager.registry();

        Set<String> names = new LinkedHashSet<>();
        Object parsed = Json.parse(Files.readString(inventory, StandardCharsets.UTF_8));
        collect(parsed, names);

        List<String> unresolved = new ArrayList<>();
        for (String name : names) {
            if (!resolves(registry, name)) {
                unresolved.add(name);
            }
        }
        System.out.println("inventory names: " + names.size()
                + ", unresolved by the dispatcher: " + unresolved.size());
        for (String name : unresolved) {
            System.out.println("  " + name);
        }
        if (!unresolved.isEmpty()) {
            System.exit(1);
        }
    }

    /** The containers whose implementation reads the sub-name from its arguments. */
    private static final String[] CONTAINERS = {
        "brush", "br", "tool", "schem", "snapshot", "snap", "anvil", "history", "list", "superpickaxe", "sp",
    };

    private static boolean resolves(CommandRegistry registry, String name) {
        String plain = name.startsWith("/") ? name.replaceFirst("^/+", "") : name;
        if (registry.resolve(plain) != null) {
            return true;
        }
        for (String container : CONTAINERS) {
            if (registry.resolve(container + " " + plain) != null) {
                return true;
            }
        }
        return false;
    }

    private static void collect(Object node, Set<String> names) {
        if (node instanceof java.util.Map<?, ?> map) {
            for (Object value : map.values()) {
                collect(value, names);
            }
            return;
        }
        if (!(node instanceof List<?> list)) {
            return;
        }
        for (Object element : list) {
            if (!(element instanceof java.util.Map<?, ?> row)) {
                continue;
            }
            if (!(row.get("name") instanceof String name) || !row.containsKey("file")) {
                collect(element, names);
                continue;
            }
            names.add(name);
            if (row.get("aliases") instanceof List<?> aliases) {
                for (Object alias : aliases) {
                    if (alias instanceof String text && !text.isEmpty()) {
                        names.add(text);
                    }
                }
            }
        }
    }

    private StrictInventoryCheck() {
    }
}
