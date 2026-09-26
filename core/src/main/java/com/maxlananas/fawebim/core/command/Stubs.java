package com.maxlananas.fawebim.core.command;

import com.maxlananas.fawebim.core.util.Msg;

/**
 * Registers the command names that no command class implements.
 *
 * <p>Two generated tables drive this class: {@link SubCommandTable} routes a
 * spelling to the command that already implements the behaviour ({@code 0} runs
 * {@code /air}, {@code s} runs {@code /brush sphere}), and {@link StubTable}
 * holds the names that still have no implementation so the command surface stays
 * complete while the port continues.</p>
 *
 * <p>Both are produced by {@code scripts/generate_command_tables.py} from the
 * WorldEdit 7.3.17 and FastAsyncWorldEdit command inventories.</p>
 */
final class Stubs {

    /** The routed spellings WorldEdit binds to a player, as a set for lookup. */
    private static final java.util.Set<String> PLAYER_ONLY =
            java.util.Set.of(SubCommandTable.PLAYER_ONLY);

    private Stubs() {
    }

    static void register(CommandRegistry registry) {
        registerRoutes(registry);
        registerMissing(registry);
        registerPickaxeToggle(registry);
    }

    /** Registers every spelling whose behaviour another command already covers. */
    private static void registerRoutes(CommandRegistry registry) {
        for (int i = 0; i + 1 < SubCommandTable.PAIRS.length; i += 2) {
            String name = SubCommandTable.PAIRS[i];
            String target = SubCommandTable.PAIRS[i + 1];
            if (registry.contains(name)) {
                continue;
            }
            CommandRegistry.Entry entry = registry.register(name);
            CommandRegistry.Entry delegate = registry.resolve(target);
            entry.description = delegate == null ? target : delegate.description;
            entry.group = delegate == null ? groupFor(name) : delegate.group;
            entry.status = "alias";
            entry.requiresSelection = delegate != null && delegate.requiresSelection;
            entry.requiresPlayer = PLAYER_ONLY.contains(name)
                    || (delegate != null && delegate.requiresPlayer);
            entry.handler = ctx -> {
                String arguments = ctx.tail();
                registry.dispatch(ctx.actor(), target + (arguments.isEmpty() ? "" : " " + arguments));
            };
        }
    }

    /** Registers the names that are not ported yet, so none of them is missing. */
    private static void registerMissing(CommandRegistry registry) {
        for (int i = 0; i + 1 < StubTable.ENTRIES.length; i += 2) {
            String name = StubTable.ENTRIES[i];
            if (registry.contains(name)) {
                continue;
            }
            CommandRegistry.Entry entry = registry.register(name);
            String description = StubTable.ENTRIES[i + 1];
            entry.description = description.isEmpty() ? "WorldEdit/FAWE command" : description;
            entry.group = groupFor(name);
            entry.status = "stub";
            entry.handler = ctx -> ctx.actor().message(Msg.warn("'" + name
                    + "' is registered but not ported yet."));
        }
    }

    /**
     * WorldEdit registers {@code /} (which the player types as {@code //}) as the
     * super-pickaxe toggle, so it gets a real implementation instead of a stub.
     */
    private static void registerPickaxeToggle(CommandRegistry registry) {
        for (String name : new String[]{"/", "//"}) {
            if (registry.get(name) != null) {
                continue;
            }
            CommandRegistry.Entry entry = registry.register(name);
            entry.description = "Toggle the super pickaxe function";
            entry.group = "tool";
            entry.status = "implemented";
            entry.handler = ctx -> {
                boolean enabled = !ctx.session().isSuperPickaxeEnabled();
                ctx.session().setSuperPickaxeEnabled(enabled);
                ctx.actor().message(Msg.success("Super pickaxe " + (enabled ? "enabled" : "disabled")));
            };
        }
    }

    /** The help section a command belongs to, derived from its name. */
    private static String groupFor(String name) {
        String key = name.toLowerCase(java.util.Locale.ROOT).replaceFirst("^/+", "");
        if (key.startsWith("brush") || key.equals("br") || key.startsWith("br ")) {
            return "brush";
        }
        if (key.startsWith("schem") || key.startsWith("snap")) {
            return "schematic";
        }
        if (key.startsWith("tool") || key.startsWith("superpickaxe") || key.startsWith("sp")) {
            return "tool";
        }
        if (key.startsWith("biome")) {
            return "biome";
        }
        if (key.startsWith("chunk") || key.equals("lazycopy") || key.equals("lazycut")
                || key.startsWith("delchunks") || key.startsWith("trimall")) {
            return "chunk";
        }
        if (key.startsWith("nbt") || key.startsWith("nbtinfo")) {
            return "info";
        }
        if (key.startsWith("height") || key.startsWith("world") || key.startsWith("watchdog")
                || key.startsWith("timeout") || key.startsWith("limit") || key.startsWith("perf")
                || key.startsWith("threads") || key.startsWith("trace") || key.startsWith("debug")
                || key.startsWith("reload") || key.startsWith("version") || key.startsWith("ver")
                || key.startsWith("tz") || key.startsWith("confirm") || key.startsWith("report")) {
            return "utility";
        }
        if (key.startsWith("snapshot") || key.startsWith("restore") || key.startsWith("rollback")
                || key.startsWith("snap") || key.startsWith("use") || key.startsWith("before")
                || key.startsWith("after") || key.startsWith("list")) {
            return "snapshot";
        }
        if (key.startsWith("forest") || key.startsWith("flora") || key.startsWith("generat")
                || key.startsWith("gen") || key.startsWith("caves") || key.startsWith("pumpkin")
                || key.startsWith("ore") || key.startsWith("snow") || key.startsWith("fix")) {
            return "generation";
        }
        if (key.startsWith("copy") || key.startsWith("cut") || key.startsWith("paste")
                || key.startsWith("clipboard") || key.startsWith("clearclip") || key.startsWith("lazy")
                || key.startsWith("paste") || key.startsWith("rotate") || key.startsWith("flip")
                || key.startsWith("schem")) {
            return "clipboard";
        }
        if (key.startsWith("undo") || key.startsWith("redo") || key.startsWith("history")
                || key.startsWith("clearhistory")) {
            return "history";
        }
        if (key.startsWith("jumpto") || key.startsWith("thru") || key.startsWith("unstuck")
                || key.startsWith("ascend") || key.startsWith("descend") || key.startsWith("up")
                || key.startsWith("ceil") || key.startsWith("pos") || key.startsWith("hpos")
                || key.startsWith("farwand") || key.startsWith("navwand") || key.startsWith("target")) {
            return "navigation";
        }
        if (key.startsWith("mask") || key.startsWith("gmask") || key.startsWith("smask")
                || key.startsWith("sourcemask") || key.startsWith("tracemask") || key.startsWith("gmask")) {
            return "mask";
        }
        if (key.startsWith("pattern") || key.startsWith("gtexture") || key.startsWith("gtransform")
                || key.startsWith("transform") || key.startsWith("material") || key.startsWith("rep")) {
            return "pattern";
        }
        if (key.startsWith("distr") || key.startsWith("count") || key.startsWith("search")
                || key.startsWith("info") || key.startsWith("near") || key.startsWith("size")
                || key.startsWith("sel")) {
            return "info";
        }
        if (key.equals("help") || key.equals("we") || key.equals("/we")) {
            return "help";
        }
        return "worldedit";
    }
}
