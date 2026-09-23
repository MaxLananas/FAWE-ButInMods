package com.fawebutinmods.core.command;

import com.fawebutinmods.core.clipboard.BlockArrayClipboard;
import com.fawebutinmods.core.clipboard.Clipboards;
import com.fawebutinmods.core.clipboard.Schematics;
import com.fawebutinmods.core.extent.EditSession;
import com.fawebutinmods.core.math.BlockVector3;
import com.fawebutinmods.core.platform.Config;
import com.fawebutinmods.core.region.Region;
import com.fawebutinmods.core.session.ClipboardHolder;
import com.fawebutinmods.core.util.Msg;
import com.fawebutinmods.core.world.BlockState;


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
        CommandRegistry.Entry entry = registry.registerUnlessPresent("lazycopy", "/lc");
        if (entry == null) {
            return;
        }
        entry.description = "Copy the selection to the clipboard without reading it";
        entry.group = "clipboard";
        entry.requiresSelection = true;
        entry.booleanFlags.add("e");
        entry.handler = ctx -> {
            Region region = ctx.selection();
            if (ctx.hasFlag("e")) {
                throw CommandRegistry.error("Entities cannot be captured by a lazy copy; use //copy -e");
            }
            BlockArrayClipboard clipboard = BlockArrayClipboard.lazy(ctx.world(), region, "lazy");
            ctx.session().setClipboard(clipboard);
            ctx.actor().message(Msg.success("Lazily copied " + Msg.formatNumber(clipboard.volume())
                    + " block(s) to the clipboard"));
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
        entry.handler = ctx -> {
            Region region = ctx.selection();
            if (ctx.hasFlag("e")) {
                throw CommandRegistry.error("Entities cannot be captured by a lazy cut; use //cut -e");
            }
            BlockArrayClipboard clipboard = BlockArrayClipboard.lazy(ctx.world(), region, "lazy");
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
        entry.description = "Place your clipboard at your position";
        entry.group = "clipboard";
        entry.requiresPlayer = true;
        entry.booleanFlags.add("a");
        entry.handler = ctx -> {
            ClipboardHolder holder = ctx.session().getClipboard();
            if (holder == null) {
                throw CommandRegistry.error("No clipboard: copy something first");
            }
            BlockArrayClipboard clipboard = holder.getClipboard();
            BlockVector3 destination = ctx.actor().position();
            EditSession session = ctx.editSession("place");
            int changed = Clipboards.paste(clipboard, destination, session, holder.getTransform(),
                    !ctx.hasFlag("a"), true, false);
            session.flushQueue();
            ctx.actor().message(Msg.success("Placed " + changed + " block(s) at " + destination));
        };
    }

}
