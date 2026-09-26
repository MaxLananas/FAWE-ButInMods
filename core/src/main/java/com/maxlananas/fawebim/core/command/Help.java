package com.maxlananas.fawebim.core.command;

import com.maxlananas.fawebim.core.util.Msg;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The listing behind {@code //help}.
 *
 * <p>A listing that runs off the top of the chat is a listing nobody reads, so
 * the commands are cut into pages of ten, grouped by what they do, and each page
 * ends with the way to the next one. {@code -s} narrows the listing to the
 * sub-commands of one command, and a word searches both the names and the
 * descriptions.</p>
 */
final class Help {

    /** Rows one page holds: a header, a group line, ten commands and a footer. */
    private static final int PAGE_SIZE = 10;

    /**
     * One colour pair per group, so the same part of the command surface always
     * reads in the same colour. A group is a handful of rows on a page, and the
     * shift between two of them is what makes the shape of the page readable.
     */
    private static final int[][] GROUP_COLOURS = {
            {0x8FE3FF, 0x6C9BFF},
            {0xFFD98E, 0xFF9E6C},
            {0xA8F5A0, 0x5FD9A0},
            {0xF7A8E0, 0xB47BFF},
            {0x9FE8FF, 0x74C6FF},
            {0xFFE9A0, 0xE4C05F},
    };

    private Help() {
    }

    static void list(Ctx ctx, CommandRegistry registry) {
        List<CommandRegistry.Entry> matches = new ArrayList<>();
        for (CommandRegistry.Entry entry : registry.all()) {
            if (entry.status.equals("stub")) {
                continue;
            }
            matches.add(entry);
        }
        matches.sort(Comparator.<CommandRegistry.Entry, String>comparing(entry -> entry.group)
                .thenComparing(entry -> entry.name));
        if (matches.isEmpty()) {
            ctx.actor().message(Msg.error("No command is registered"));
            return;
        }
        Page page = Page.of(ctx, matches.size(), PAGE_SIZE);
        ctx.actor().message(header("Commands", matches.size(), page));
        String group = null;
        for (CommandRegistry.Entry entry : matches.subList(page.from(), page.to())) {
            if (!entry.group.equals(group)) {
                group = entry.group;
                ctx.actor().message(Msg.of("  " + groupTitle(group)));
            }
            ctx.actor().message(row(entry));
        }
        page.hint(ctx, "//help");
        ctx.actor().message(Msg.of("§8» §7Settings screen: " + Msg.value("/fawebim").raw()
                + " §8- §7Discord: " + Msg.value("/fawebim-discord").raw()));
    }

    /** {@code //help <word>}: everything whose name or description holds the word. */
    static void search(Ctx ctx, CommandRegistry registry, String filter) {
        List<CommandRegistry.Entry> matches = new ArrayList<>();
        for (CommandRegistry.Entry entry : registry.all()) {
            if (entry.status.equals("stub")) {
                continue;
            }
            if (entry.name.toLowerCase(Locale.ROOT).contains(filter)
                    || entry.description.toLowerCase(Locale.ROOT).contains(filter)) {
                matches.add(entry);
            }
        }
        if (matches.isEmpty()) {
            ctx.actor().message(Msg.error("No command matches '" + filter + "'"));
            return;
        }
        matches.sort(Comparator.<CommandRegistry.Entry, String>comparing(entry -> entry.group)
                .thenComparing(entry -> entry.name));
        Page page = Page.of(ctx, matches.size(), PAGE_SIZE);
        ctx.actor().message(header("Commands matching '" + filter + "'", matches.size(), page));
        String group = null;
        for (CommandRegistry.Entry entry : matches.subList(page.from(), page.to())) {
            if (!entry.group.equals(group)) {
                group = entry.group;
                ctx.actor().message(Msg.of("  " + groupTitle(group)));
            }
            ctx.actor().message(row(entry));
        }
        page.hint(ctx, "//help " + filter);
    }

    /** {@code //help -s <command>}: the sub-commands registered under one name. */
    static void subCommands(Ctx ctx, CommandRegistry registry, String filter) {
        List<CommandRegistry.Entry> matches = new ArrayList<>();
        for (CommandRegistry.Entry entry : registry.all()) {
            String name = entry.name.toLowerCase(Locale.ROOT);
            if (name.startsWith("/" + filter + " ") || name.startsWith("//" + filter + " ")
                    || name.equals("/" + filter) || name.equals("//" + filter)) {
                matches.add(entry);
            }
        }
        if (matches.isEmpty()) {
            ctx.actor().message(Msg.error("No sub-command found for '" + filter + "'"));
            return;
        }
        matches.sort(Comparator.comparing(entry -> entry.name));
        Page page = Page.of(ctx, matches.size(), PAGE_SIZE);
        ctx.actor().message(header("Sub-commands of " + filter, matches.size(), page));
        for (CommandRegistry.Entry entry : matches.subList(page.from(), page.to())) {
            ctx.actor().message(row(entry));
        }
        page.hint(ctx, "//help -s " + filter);
    }

    /** The heading of a page: the title, how many there are, and which page this is. */
    private static Msg header(String label, int total, Page page) {
        return Msg.of(Msg.title(label).raw() + " §8(§b" + total
                + "§7, page §b" + page.number() + "§8/§b" + page.pages() + "§8)");
    }

    /** One command: its usage in the command colour, its description under it. */
    private static Msg row(CommandRegistry.Entry entry) {
        return Msg.of("§8 - §b" + entry.usage() + " §8- §7" + entry.description);
    }

    /** The name of a group, in that group's own colour. */
    private static String groupTitle(String group) {
        if (group == null || group.isEmpty()) {
            return "§8» §7Other";
        }
        int[] colours = GROUP_COLOURS[Math.floorMod(group.hashCode(), GROUP_COLOURS.length)];
        return "§8» §l" + Msg.gradient(group.toUpperCase(Locale.ROOT), colours[0], colours[1]);
    }
}
