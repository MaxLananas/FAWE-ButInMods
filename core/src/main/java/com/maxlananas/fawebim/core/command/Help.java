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

    /** Rows one page holds: a header, a group line, eight commands and a footer. */
    private static final int PAGE_SIZE = 8;

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
        ctx.actor().message(header("FAWE-BIM commands", matches.size(), page));
        String group = null;
        for (CommandRegistry.Entry entry : matches.subList(page.from(), page.to())) {
            if (!entry.group.equals(group)) {
                group = entry.group;
                ctx.actor().message(groupTitle(group));
            }
            ctx.actor().suggestLink(row(entry), entry.name,
                    "Put " + entry.name + " in the chat box");
        }
        footer(ctx, "//help", page);
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
                ctx.actor().message(groupTitle(group));
            }
            ctx.actor().suggestLink(row(entry), entry.name,
                    "Put " + entry.name + " in the chat box");
        }
        footer(ctx, "//help " + filter, page);
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
            ctx.actor().suggestLink(row(entry), entry.name,
                    "Put " + entry.name + " in the chat box");
        }
        footer(ctx, "//help -s " + filter, page);
    }

    /**
     * The lines under a page: the way to the pages around it, then the three
     * things a reader of a listing looks for - the search, the settings screen
     * and the Discord server. Every one of them is a click target, so a page can
     * be read without typing a path.
     */
    private static void footer(Ctx ctx, String command, Page page) {
        page.hint(ctx, command);
        ctx.actor().commandLink(Msg.hint("Search " + Msg.value("//help <word>").raw()
                        + " - sub-commands " + Msg.value("//help -s <command>").raw()
                        + " - pages " + Msg.value("-p <page>").raw()).raw(),
                "//help ", "Search the command list");
        ctx.actor().commandLink(Msg.hint("Settings " + Msg.value("/fawebim").raw()
                        + " - Discord " + Msg.value("/fawebim-discord").raw()
                        + " - click a command to put it in the chat box").raw(),
                "/fawebim", "Open the settings screen");
    }

    /** The heading of a page: the title, how many there are, and which page this is. */
    private static Msg header(String label, int total, Page page) {
        return Msg.title(label + " (" + total + " commands, page " + page.number() + "/" + page.pages() + ")");
    }

    /** One command: its name - sub-command included - its arguments, then what it does. */
    private static String row(CommandRegistry.Entry entry) {
        String usage = entry.usage();
        String arguments = usage.startsWith(entry.name) ? usage.substring(entry.name.length()).trim() : "";
        return Msg.usage(entry.name, arguments, entry.description).raw();
    }

    /**
     * The name of a group. Every group reads in the same colour: a page of the
     * listing is a handful of groups, and a colour of their own for each made
     * it a patchwork.
     */
    private static Msg groupTitle(String group) {
        return Msg.section(group == null || group.isEmpty() ? "OTHER" : group.toUpperCase(Locale.ROOT));
    }
}
