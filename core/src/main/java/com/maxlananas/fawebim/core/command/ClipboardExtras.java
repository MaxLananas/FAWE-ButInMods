package com.maxlananas.fawebim.core.command;

import com.maxlananas.fawebim.core.clipboard.BlockArrayClipboard;
import com.maxlananas.fawebim.core.clipboard.Clipboards;
import com.maxlananas.fawebim.core.clipboard.Schematics;
import com.maxlananas.fawebim.core.extent.EditSession;
import com.maxlananas.fawebim.core.mask.Masks;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.platform.Config;
import com.maxlananas.fawebim.core.region.Region;
import com.maxlananas.fawebim.core.session.ClipboardHolder;
import com.maxlananas.fawebim.core.util.Msg;
import com.maxlananas.fawebim.core.world.BlockState;


/**
 * Clipboard commands that FAWE adds to WorldEdit's {@code //copy}, {@code //cut}
 * and {@code //paste}: the lazy variants that use a file-backed clipboard, the
 * {@code /download} export and {@code /place}.
 */
final class ClipboardExtras {

    private final CommandRegistry registry;

    ClipboardExtras(CommandRegistry registry) {
        this.registry = registry;
    }

    void register() {
        lazyCopy();
        lazyCut();
        download();
        place();
    }

    /**
     * {@code //lazycopy} — copies the selection without reading the blocks: the
     * clipboard keeps a reference to the region and reads it when it is pasted.
     * FAWE uses this to copy huge selections without allocating a block array.
     */
    private void lazyCopy() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("//lazycopy", "/lc");
        if (entry == null) {
            return;
        }
        entry.description = "Copy the selection to the clipboard without reading it";
        entry.group = "clipboard";
        entry.requiresSelection = true;
        // -e skips the entities, which is the opposite of //copy -e.
        entry.booleanFlags.add("e");
        entry.booleanFlags.add("b");
        entry.handler = ctx -> {
            Region region = ctx.selection();
            BlockArrayClipboard clipboard = BlockArrayClipboard.lazy(ctx.world(), region, "lazy");
            if (ctx.hasFlag("b")) {
                com.maxlananas.fawebim.core.clipboard.Clipboards.copyBiomes(ctx.world(), region, clipboard);
            }
            ctx.session().setClipboard(clipboard);
            ctx.actor().message(Msg.result("Lazily copied", Msg.count(clipboard.volume())
                    + "\u00a77 block(s) to the clipboard"
                    + (ctx.hasFlag("e") ? "\u00a77 without entities" : "")));
        };
    }

    /**
     * {@code //lazycut} — the same as {@code //lazycopy}, then clears the region.
     */
    private void lazyCut() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("lazycut");
        if (entry == null) {
            return;
        }
        entry.description = "Cut the selection to the clipboard without reading it";
        entry.group = "clipboard";
        entry.requiresSelection = true;
        entry.booleanFlags.add("e");
        entry.booleanFlags.add("b");
        entry.handler = ctx -> {
            Region region = ctx.selection();
            BlockArrayClipboard clipboard = BlockArrayClipboard.lazy(ctx.world(), region, "lazy");
            if (ctx.hasFlag("b")) {
                com.maxlananas.fawebim.core.clipboard.Clipboards.copyBiomes(ctx.world(), region, clipboard);
            }
            ctx.session().setClipboard(clipboard);
            EditSession session = ctx.editSession("lazycut");
            int air = BlockState.registry().air();
            int cleared = 0;
            for (BlockVector3 position : region) {
                session.checkTimeout();
                if (session.setBlock(position.x(), position.y(), position.z(), air)) {
                    cleared++;
                }
            }
            session.flushQueue();
            ctx.actor().message(Msg.success("Lazily cut " + Msg.formatNumber(clipboard.volume())
                    + " block(s), " + cleared + " removed"));
        };
    }

    /**
     * {@code /download} — writes the clipboard to a schematic file so it can be
     * taken out of the world. FAWE uploads to a paste service; a mod writes the
     * file next to the other schematics.
     */
    private void download() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("/download");
        if (entry == null) {
            return;
        }
        entry.description = "Save your clipboard to a schematic file";
        entry.group = "clipboard";
        entry.arguments.add("[name]");
        entry.arguments.add("[format]");
        entry.handler = ctx -> {
            ClipboardHolder holder = ctx.session().getClipboard();
            if (holder == null) {
                throw CommandRegistry.error("No clipboard: copy something first");
            }
            String name = ctx.arg(0, "clipboard-" + System.currentTimeMillis());
            String format = ctx.arg(1, Config.get().defaultSchematicFormat);
            Schematics.save(holder.getClipboard(), name, format);
            ctx.actor().message(Msg.success("Clipboard written as " + name + "." + format
                    + " in " + Schematics.directory()));
        };
    }

    /**
     * {@code /place} — pastes the clipboard at the player's position, ignoring
     * the clipboard's own origin. It is the command used by the clipboard brush.
     */
    private void place() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("/place");
        if (entry == null) {
            return;
        }
        entry.description = "Place the clipboard's contents without applying transformations";
        entry.group = "clipboard";
        entry.booleanFlags.add("a");
        entry.booleanFlags.add("o");
        entry.booleanFlags.add("s");
        entry.booleanFlags.add("n");
        entry.booleanFlags.add("e");
        entry.booleanFlags.add("b");
        entry.booleanFlags.add("x");
        entry.handler = ctx -> {
            ClipboardHolder holder = ctx.session().getClipboard();
            if (holder == null) {
                throw CommandRegistry.error("No clipboard: copy something first");
            }
            BlockArrayClipboard clipboard = holder.getClipboard();
            BlockVector3 destination = ctx.hasFlag("o") ? clipboard.getOrigin() : ctx.placement();
            EditSession session = ctx.editSession("place");
            Masks.ExtentHolder.set(session);
            boolean onlySelect = ctx.hasFlag("n");
            int changed = 0;
            if (!onlySelect) {
                changed = Clipboards.paste(clipboard, destination, session,
                        com.maxlananas.fawebim.core.transform.Transform.identity(), ctx.hasFlag("a"),
                        session.getMask(), ctx.hasFlag("e"), ctx.hasFlag("b"), ctx.hasFlag("x"), false);
            }
            if (ctx.hasFlag("s") || onlySelect) {
                var selector = ctx.session().getSelector(ctx.world());
                var limits = com.maxlananas.fawebim.core.region.SelectorLimits.unlimited();
                BlockVector3 max = destination.add(clipboard.getWidth(), clipboard.getHeight(),
                        clipboard.getLength());
                selector.selectPrimary(destination, limits);
                selector.selectSecondary(max, limits);
            }
            session.flushQueue();
            if (onlySelect) {
                ctx.actor().message(Msg.success("Selected the clipboard region at " + destination));
            } else {
                ctx.actor().message(Msg.success("Placed " + changed + " block(s) at " + destination));
            }
        };
    }

}
