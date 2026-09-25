package com.maxlananas.fawebim.core.command;

/**
 * The spellings WorldEdit gives a tool or a wand at the top level.
 *
 * <p>FAWE declares them as absolute aliases of a {@code /tool} sub-command -
 * {@code /mask} for {@code /tool mask}, {@code /selwand} for
 * {@code /tool selwand} - so a player reaches one without going through the
 * container. Each spelling here runs the command that already implements the
 * behaviour.</p>
 */
final class Aliases {

    /**
     * {@code spelling, command} pairs, taken from the WorldEdit and FAWE
     * {@code ToolCommands} and {@code ToolUtilCommands} declarations.
     */
    private static final String[] ROUTES = {
            "//mask", "/tool mask",
            "//material", "/tool material",
            "//range", "/tool range",
            "//primary", "/tool primary",
            "//secondary", "/tool secondary",
            "//target", "/tool target",
            "//transform", "/tool transform",
            "//smask", "/tool smask",
            "//selwand", "/tool selwand",
            "//navwand", "/tool navwand",
            "//warwand", "/tool farwand",
            "//info", "/tool info",
            "//inspect", "/tool inspect",
            "//tree", "/tool tree",
            "//featureplacer", "/tool featureplacer",
            "//structureplacer", "/tool structureplacer",
            "//repl", "/tool repl",
            "//cycler", "/tool cycler",
            "//flood", "/tool floodfill",
            "//deltree", "/tool deltree",
            "//lrbuild", "/tool lrbuild",
            "//unbind", "/tool none",
            "//listbrush", "//brushes",
            "/tool ,", "/superpickaxe",
    };

    /** Extra spellings WorldEdit registers beside the main one. */
    private static final String[][] EXTRA = {
            {"//mask", "/mask"},
            {"//material", "/material"},
            {"//range", "/range"},
            {"//primary", "/primary"},
            {"//secondary", "/secondary"},
            {"//target", "/target", "//tar", "/tar"},
            {"//transform", "/transform"},
            {"//smask", "/smask", "//sourcemask", "/sourcemask"},
            {"//selwand", "/selwand"},
            {"//navwand", "/navwand"},
            {"//warwand", "/warwand", "//farwand", "/farwand"},
            {"//info", "/info"},
            {"//inspect", "/inspect"},
            {"//tree", "/tree"},
            {"//featureplacer", "/featureplacer", "//featuretool", "/featuretool"},
            {"//structureplacer", "/structureplacer", "//structuretool", "/structuretool"},
            {"//repl", "/repl"},
            {"//cycler", "/cycler"},
            {"//flood", "/flood", "//floodfill", "/floodfill"},
            {"//deltree", "/deltree"},
            {"//lrbuild", "/lrbuild"},
            {"//unbind", "/unbind"},
            {"//listbrush", "/listbrush"},
            {"//gsmask", "/gsmask", "//globalsourcemask", "/globalsourcemask"},
            {"//pos1", "//1", "/1"},
            {"//pos2", "//2", "/2"},
            {"//nbtinfo", "//nbt", "/nbt"},
            {"//setblocklight", "//setlight", "/setlight"},
    };

    /** The container spellings WorldEdit declares beside a sub-command. */
    private static final String[] CONTAINER_ROUTES = {
            "/tool unbind", "/tool none",
            "/brush unbind", "/brush none",
            "/tool sourcemask", "/tool smask",
            "/tool targetmask", "/tool tracemask",
            "/tool tarmask", "/tool tracemask",
            "/tool tm", "/tool tracemask",
            "/brush listbrush", "//brushes",
    };

    private Aliases() {
    }

    static void register(CommandRegistry registry) {
        for (int i = 0; i + 1 < ROUTES.length; i += 2) {
            registerRoute(registry, ROUTES[i], ROUTES[i + 1]);
        }
        for (int i = 0; i + 1 < CONTAINER_ROUTES.length; i += 2) {
            registerRoute(registry, CONTAINER_ROUTES[i], CONTAINER_ROUTES[i + 1]);
        }
        // The plain spellings are added last: several of them belong to a
        // command the routes above have just created.
        for (String[] pair : EXTRA) {
            for (int i = 1; i < pair.length; i++) {
                registerAlias(registry, pair[0], pair[i]);
            }
        }
    }

    /** Adds a spelling to the command it already answers to, or makes one that routes. */
    private static void registerAlias(CommandRegistry registry, String target, String spelling) {
        CommandRegistry.Entry entry = registry.get(target);
        if (entry == null || registry.contains(spelling)) {
            return;
        }
        registry.alias(spelling, entry);
    }

    private static void registerRoute(CommandRegistry registry, String spelling, String target) {
        if (registry.contains(spelling)) {
            return;
        }
        CommandRegistry.Entry delegate = registry.resolve(target);
        CommandRegistry.Entry entry = registry.register(spelling);
        entry.description = delegate == null ? target : delegate.description;
        entry.group = delegate == null ? "general" : delegate.group;
        entry.status = "alias";
        entry.requiresSelection = delegate != null && delegate.requiresSelection;
        entry.requiresPlayer = delegate != null && delegate.requiresPlayer;
        entry.handler = ctx -> {
            String arguments = ctx.tail();
            registry.dispatch(ctx.actor(), target + (arguments.isEmpty() ? "" : " " + arguments));
        };
    }
}
