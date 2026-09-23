package com.maxlananas.fawebim.core.command;

import com.maxlananas.fawebim.core.util.Msg;

/**
 * One page of a command listing.
 *
 * <p>{@code -p <page>} appears on every list WorldEdit and FAWE print, so the
 * arithmetic and the "next page" hint live here instead of in each command.</p>
 */
final class Page {

    /** The page size WorldEdit uses for the command and biome listings. */
    static final int DEFAULT_SIZE = 20;

    private final int number;
    private final int pages;
    private final int from;
    private final int to;

    private Page(int number, int pages, int from, int to) {
        this.number = number;
        this.pages = pages;
        this.from = from;
        this.to = to;
    }

    /** The page the command asked for, clamped to the pages that exist. */
    static Page of(Ctx ctx, int total) {
        return of(ctx, total, DEFAULT_SIZE);
    }

    static Page of(Ctx ctx, int total, int size) {
        int pages = Math.max(1, (total + size - 1) / size);
        int number = Math.max(1, Math.min(pages, ctx.flagInt("p", 1)));
        int from = Math.min(total, (number - 1) * size);
        return new Page(number, pages, from, Math.min(total, from + size));
    }

    int number() {
        return number;
    }

    int pages() {
        return pages;
    }

    int from() {
        return from;
    }

    int to() {
        return to;
    }

    /** The header FAWE prints in front of a paginated listing. */
    String header(String label, int total) {
        return label + " (" + total + ", page " + number + "/" + pages + "):";
    }

    /** Tells the player how to see the rest, when there is a rest. */
    void hint(Ctx ctx, String command) {
        if (pages > 1) {
            ctx.actor().message(Msg.info("Next page: " + command + " -p <page>"));
        }
    }
}
