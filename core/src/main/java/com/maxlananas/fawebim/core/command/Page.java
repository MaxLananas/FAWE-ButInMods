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

    /** The heading of a paginated listing: the title, the total, the page. */
    Msg header(String label, int total) {
        return Msg.title(label + " (" + Msg.formatNumber(total) + ", page " + number + "/" + pages + ")");
    }

    /**
     * The last line of a listing: which page this was, and the way to the ones
     * around it, so a listing longer than the chat stays readable.
     */
    void hint(Ctx ctx, String command) {
        if (pages <= 1) {
            return;
        }
        boolean forward = number < pages;
        int target = forward ? number + 1 : number - 1;
        StringBuilder line = new StringBuilder("Page ").append(number).append('/').append(pages);
        if (forward) {
            line.append(" - next: ").append(command).append(" -p ").append(target);
        } else {
            line.append(" - back to page ").append(target);
        }
        if (number > 1 && forward) {
            line.append(" - back to page ").append(number - 1);
        }
        // Clicking the line runs the command it prints, which is what makes a
        // listing longer than the chat readable. The tooltip names the page the
        // click really opens.
        ctx.actor().commandLink(Msg.hint(line.toString()).raw(), command + " -p " + target,
                forward ? "Go to page " + target : "Go back to page " + target);
    }
}
