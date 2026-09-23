package com.maxlananas.fawebim.core.command;

import com.maxlananas.fawebim.core.extent.EditSession;
import com.maxlananas.fawebim.core.history.Snapshots;
import com.maxlananas.fawebim.core.math.BlockBox;
import com.maxlananas.fawebim.core.util.Msg;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

/**
 * {@code /snapshot} — the file backed undo points, FAWE's equivalent of the
 * {@code snapshots} folder: {@code list}, {@code use}, {@code before},
 * {@code after}, {@code sel} and {@code restore}.
 *
 * <p>Every edit made while {@code history.snapshots.enabled} is set writes its
 * "before" state to disk, so an edit can be reverted after a restart, which the
 * in-memory history cannot do.</p>
 */
final class SnapshotCommands {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);

    private final CommandRegistry registry;

    SnapshotCommands(CommandRegistry registry) {
        this.registry = registry;
    }

    void register() {
        list();
        use();
        before();
        after();
        select();
        restore();
    }

    /** {@code /snapshot list} — the snapshots of this player, newest first. */
    private void list() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("/snapshot list");
        if (entry == null) {
            return;
        }
        entry.description = "List your snapshots";
        entry.group = "snapshot";
        entry.valueFlags.add("p");
        entry.arguments.add("[-p <page>]");
        entry.handler = ctx -> {
            List<Path> snapshots = Snapshots.list(ctx.actor().name());
            if (snapshots.isEmpty()) {
                ctx.actor().message(Msg.info("No snapshots yet"));
                return;
            }
            Page page = Page.of(ctx, snapshots.size());
            ctx.actor().message(Msg.info(page.header("Snapshots", snapshots.size())));
            for (Path path : snapshots.subList(page.from(), page.to())) {
                long time = Snapshots.timestampOf(path);
                ctx.actor().message(Msg.of("§7 - §f" + path.getFileName() + "§7 "
                        + (time < 0 ? "?" : ZonedDateTime.ofInstant(Instant.ofEpochMilli(time),
                        ctx.session().getTimezone()).format(DATE))));
            }
            page.hint(ctx, "/snapshot list");
        };
    }

    /** {@code /snapshot use} — picks the snapshot the other sub-commands act on. */
    private void use() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("/snapshot use");
        if (entry == null) {
            return;
        }
        entry.description = "Choose the snapshot to work with";
        entry.group = "snapshot";
        entry.arguments.add("name");
        entry.handler = ctx -> {
            Path path = Snapshots.byName(ctx.actor().name(), ctx.arg(0));
            if (path == null) {
                throw CommandRegistry.error("No snapshot named '" + ctx.arg(0) + "'");
            }
            ctx.session().setActiveSnapshot(path);
            ctx.actor().message(Msg.success("Using snapshot " + path.getFileName()));
        };
    }

    /** {@code /snapshot before} — the newest snapshot older than a date. */
    private void before() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("/snapshot before");
        if (entry == null) {
            return;
        }
        entry.description = "Choose the nearest snapshot before a date";
        entry.group = "snapshot";
        entry.arguments.add("[date]");
        entry.handler = ctx -> pick(ctx, true);
    }

    /** {@code /snapshot after} — the oldest snapshot newer than a date. */
    private void after() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("/snapshot after");
        if (entry == null) {
            return;
        }
        entry.description = "Choose the nearest snapshot after a date";
        entry.group = "snapshot";
        entry.arguments.add("[date]");
        entry.handler = ctx -> pick(ctx, false);
    }

    private void pick(Ctx ctx, boolean before) {
        long time = ctx.args().isEmpty() ? System.currentTimeMillis() : parseDate(ctx.arg(0));
        Path path = before ? Snapshots.before(ctx.actor().name(), time) : Snapshots.after(ctx.actor().name(), time);
        if (path == null) {
            throw CommandRegistry.error("No snapshot " + (before ? "before " : "after ") + ctx.arg(0, "now"));
        }
        ctx.session().setActiveSnapshot(path);
        ctx.actor().message(Msg.success("Using snapshot " + path.getFileName() + " (" + DATE.format(
                ZonedDateTime.ofInstant(Instant.ofEpochMilli(Snapshots.timestampOf(path)),
                        ctx.session().getTimezone())) + ")"));
    }

    /** Parses {@code yyyy-MM-dd}, {@code yyyy-MM-dd HH:mm:ss} or a raw timestamp. */
    private static long parseDate(String input) {
        try {
            return Long.parseLong(input);
        } catch (NumberFormatException ignored) {
            // Not a raw timestamp; fall through to the date formats.
        }
        try {
            return Instant.parse(input).toEpochMilli();
        } catch (DateTimeParseException ignored) {
            // Not an ISO instant either.
        }
        for (String pattern : List.of("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd")) {
            try {
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern(pattern, Locale.ROOT);
                if (pattern.equals("yyyy-MM-dd")) {
                    return java.time.LocalDate.parse(input, formatter)
                            .atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
                }
                return java.time.LocalDateTime.parse(input, formatter)
                        .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
            } catch (DateTimeParseException ignored) {
                // Try the next pattern.
            }
        }
        throw CommandRegistry.error("Expected a date like 2026-09-22 or 2026-09-22 14:30:00, got '" + input + "'");
    }

    /** {@code /snapshot sel} — selects the area a snapshot covers. */
    private void select() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("/snapshot sel");
        if (entry == null) {
            return;
        }
        entry.description = "Select the region of the chosen snapshot";
        entry.group = "snapshot";
        entry.handler = ctx -> {
            Path path = requireSnapshot(ctx);
            BlockBox box = Snapshots.bounds(read(ctx, path));
            if (box == null) {
                throw CommandRegistry.error("Snapshot " + path.getFileName() + " contains no blocks");
            }
            var world = ctx.world();
            var selector = ctx.session().getSelector(world);
            selector.selectPrimary(new com.maxlananas.fawebim.core.math.BlockVector3(box.minX(), box.minY(), box.minZ()),
                    com.maxlananas.fawebim.core.region.SelectorLimits.unlimited());
            selector.selectSecondary(new com.maxlananas.fawebim.core.math.BlockVector3(box.maxX(), box.maxY(), box.maxZ()),
                    com.maxlananas.fawebim.core.region.SelectorLimits.unlimited());
            ctx.actor().updateSelectionOutline();
            ctx.actor().message(Msg.success("Selected the area of " + path.getFileName()));
        };
    }

    /** {@code /snapshot restore} — puts the snapshot back into the world. */
    private void restore() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("/snapshot restore", "/restore");
        if (entry == null) {
            return;
        }
        entry.description = "Restore a snapshot";
        entry.group = "snapshot";
        // -b also puts the recorded biomes back, -e the recorded entities.
        entry.booleanFlags.add("b");
        entry.booleanFlags.add("e");
        entry.arguments.add("[name]");
        entry.handler = ctx -> {
            Path path = ctx.args().isEmpty() ? requireSnapshot(ctx) : resolve(ctx);
            EditSession session = ctx.editSession("snapshot restore");
            var snapshot = read(ctx, path);
            int restored = Snapshots.restore(session, snapshot);
            int biomes = ctx.hasFlag("b") ? Snapshots.restoreBiomes(session, snapshot) : 0;
            int entities = ctx.hasFlag("e") ? Snapshots.restoreEntities(session, snapshot) : 0;
            session.flushQueue();
            ctx.actor().message(Msg.success("Restored " + restored + " block(s) from " + path.getFileName()
                    + (ctx.hasFlag("b") ? ", " + biomes + " biome cell(s)" : "")
                    + (ctx.hasFlag("e") ? ", " + entities + " entit(ies)" : "")));
        };
    }

    private Path resolve(Ctx ctx) {
        Path path = Snapshots.byName(ctx.actor().name(), ctx.arg(0));
        if (path == null) {
            path = Snapshots.byName(ctx.actor().name(), ctx.joined(0));
        }
        if (path == null) {
            throw CommandRegistry.error("No snapshot named '" + ctx.joined(0) + "'");
        }
        return path;
    }

    private static Path requireSnapshot(Ctx ctx) {
        Path path = ctx.session().getActiveSnapshot();
        if (path == null) {
            throw CommandRegistry.error("No snapshot chosen: use /snapshot use or /snapshot before <date> first");
        }
        return path;
    }

    private static com.maxlananas.fawebim.core.util.NbtCompound read(Ctx ctx, Path path) {
        try {
            return Snapshots.read(path);
        } catch (IOException e) {
            throw CommandRegistry.error("Could not read " + path.getFileName() + ": " + e.getMessage());
        }
    }

}
