package com.maxlananas.fawebim.core.command;

import com.maxlananas.fawebim.core.anvil.ChunkData;
import com.maxlananas.fawebim.core.anvil.RegionFiles;
import com.maxlananas.fawebim.core.clipboard.BlockArrayClipboard;
import com.maxlananas.fawebim.core.clipboard.Clipboards;
import com.maxlananas.fawebim.core.extent.EditSession;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.mask.Mask;
import com.maxlananas.fawebim.core.pattern.Pattern;
import com.maxlananas.fawebim.core.region.Region;
import com.maxlananas.fawebim.core.util.Msg;
import com.maxlananas.fawebim.core.world.BlockState;
import com.maxlananas.fawebim.core.world.BlockStateRegistry;
import com.maxlananas.fawebim.core.world.World;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * {@code /anvil} — FAWE's chunk tools.
 *
 * <p>Upstream keeps these names but its implementations were deleted ("used on
 * versions prior to 1.13 ... has not been implemented in any modern version"), so
 * the mod implements them for the Minecraft it targets: the region files are read
 * to decide which chunks qualify, and the chunks themselves are changed in game
 * through {@link World}, because a running server owns the region files and
 * rewriting them behind its back is what made the old commands unsafe.</p>
 *
 * <p>Because there is no PlotSquared, WorldGuard or GriefPrevention inside a mod,
 * "unclaimed" is taken as "never inhabited": a chunk no player ever built in is
 * exactly the chunk those plugins would leave unprotected.</p>
 */
final class AnvilCommands {

    /** Chunks one command run may touch; the change limit usually kicks in first. */
    private static final int MAX_CHUNKS = 256;

    private final CommandRegistry registry;

    AnvilCommands(CommandRegistry registry) {
        this.registry = registry;
    }

    void register() {
        container();
        clear();
        copy();
        paste();
        count();
        countAll();
        distr();
        replace();
        replaceAll();
        replacePattern();
        set();
        removeLayers();
        trimAllAir();
        trimAllPlots();
        deleteBiomeChunks();
        deleteAllUnvisited();
        deleteAllUnclaimed();
        deleteUnclaimed();
        deleteAllOldRegions();
        remapAll();
        debugFixRoads();
    }

    private void container() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("/anvil", "//anvil");
        if (entry == null) {
            return;
        }
        entry.description = "Chunk tools that work on whole chunks of the world";
        entry.requiresPlayer = true;
        entry.group = "anvil";
        entry.arguments.add("clear|unset|copy|paste|count|countall|distr|replace|r|replaceall|rea|repall"
                + "|replacepattern|preplace|rp|set|removelayers|trimallair|trimallplots|deletebiomechunks"
                + "|deleteallunvisited|delunvisited|deleteallunclaimed|delallunclaimed|deleteunclaimed"
                + "|deletealloldregions|deloldreg|remapall|debugfixroads");
        entry.handler = ctx -> {
            ctx.actor().message(Msg.info("Chunk tools:"));
            for (String line : List.of(
                    "clear|unset — empty the chunks of the selection",
                    "copy, paste [-c] — move chunks between positions",
                    "count <mask> [-d], countall [-d], distr [-d] — block statistics",
                    "replace|replaceall <from> <to>, replacepattern <from> <pattern>, set <pattern>",
                    "removelayers <block> — strip one block from the selected layers",
                    "trimallair [-u], trimallplots [-v] — drop chunks that hold nothing",
                    "deletebiomechunks <biome> [-u] — drop chunks of one biome",
                    "deleteallunvisited <ticks> [fileMillis] — drop chunks nobody has been in",
                    "deleteunclaimed, deleteallunclaimed — the same age test, without a claim provider",
                    "deletealloldregions <time> — drop region files untouched for that long",
                    "remapall, debugfixroads — legacy maintenance")) {
                ctx.actor().message(Msg.item(line));
            }
        };
    }

    /** {@code /anvil clear} — every block of the selection's chunks goes away. */
    private void clear() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("/anvil clear", "/anvil unset");
        if (entry == null) {
            return;
        }
        entry.description = "Clear the chunks in a selection (delete without defrag)";
        entry.requiresPlayer = true;
        entry.group = "anvil";
        entry.requiresSelection = true;
        entry.handler = ctx -> {
            EditSession session = ctx.editSession("anvil clear");
            List<int[]> chunks = selectionChunks(ctx);
            int cleared = clearChunks(session, ctx.world(), chunks);
            flush(ctx, session, "Cleared", Msg.count(cleared) + " of "
                    + Msg.count(chunks.size()) + " chunk(s)");
        };
    }

    /** {@code /anvil copy} — the selection becomes the anvil clipboard. */
    private void copy() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("/anvil copy");
        if (entry == null) {
            return;
        }
        entry.description = "Lazily copy chunks to your anvil clipboard";
        entry.requiresPlayer = true;
        entry.group = "anvil";
        entry.requiresSelection = true;
        entry.handler = ctx -> {
            Region region = ctx.selection();
            BlockVector3 min = region.getMinimumPoint();
            BlockVector3 max = region.getMaximumPoint();
            // Chunk aligned, like FAWE's MCAClipboard: whole chunks travel together.
            BlockVector3 origin = BlockVector3.at(min.x() & ~15, min.y(), min.z() & ~15);
            BlockVector3 corner = BlockVector3.at(max.x() | 15, max.y(), max.z() | 15);
            EditSession session = ctx.editSession("anvil copy");
            BlockArrayClipboard clipboard = Clipboards.copy(ctx.world(),
                    new com.maxlananas.fawebim.core.region.CuboidRegion(origin, corner), session, false);
            clipboard.setName("anvil");
            ctx.session().setAnvilClipboard(clipboard);
            ctx.actor().message(Msg.success("Copied " + ((corner.x() - origin.x() + 1) >> 4) + "x"
                    + ((corner.z() - origin.z() + 1) >> 4) + " chunk(s) to the anvil clipboard"));
        };
    }

    /** {@code /anvil paste [-c]} — places the anvil clipboard at the player. */
    private void paste() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("/anvil paste");
        if (entry == null) {
            return;
        }
        entry.description = "Paste chunks from your anvil clipboard";
        entry.requiresPlayer = true;
        entry.group = "anvil";
        entry.booleanFlags.add("c");
        entry.handler = ctx -> {
            BlockArrayClipboard clipboard = ctx.session().getAnvilClipboard();
            if (clipboard == null) {
                throw CommandRegistry.error("You must first use /anvil copy");
            }
            BlockVector3 destination = ctx.args().isEmpty() ? ctx.placement() : ctx.blockVector(0);
            if (ctx.hasFlag("c")) {
                destination = BlockVector3.at(destination.x() & ~15, destination.y(), destination.z() & ~15);
            }
            EditSession session = ctx.editSession("anvil paste");
            int changed = Clipboards.paste(clipboard, destination, session,
                    com.maxlananas.fawebim.core.transform.Transform.identity(), false, false, false);
            flush(ctx, session, "Pasted", Msg.count(changed) + " block(s) at "
                    + Msg.value(destination).raw());
        };
    }

    /**
     * {@code /anvil count <mask> [-d]} — how many blocks match, or the full
     * histogram. {@code -d} counts by block state rather than by name, which is
     * the data flag upstream declares for it.
     */
    private void count() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("/anvil count");
        if (entry == null) {
            return;
        }
        entry.description = "Count blocks in a selection";
        entry.requiresPlayer = true;
        entry.group = "anvil";
        entry.requiresSelection = true;
        entry.booleanFlags.add("d");
        entry.arguments.add("mask");
        entry.handler = ctx -> {
            Mask mask = ctx.mask(0);
            Map<String, Long> counts = countSelection(ctx, mask, ctx.hasFlag("d"));
            long total = counts.values().stream().mapToLong(Long::longValue).sum();
            if (ctx.hasFlag("d")) {
                distribution(ctx, counts);
                return;
            }
            ctx.actor().message(Msg.result("Matched", Msg.count(total) + " block(s)"));
        };
    }

    /** {@code /anvil countall [-d]} — the same for every chunk of the world folder. */
    private void countAll() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("/anvil countall");
        if (entry == null) {
            return;
        }
        entry.description = "Count all blocks in a world";
        entry.requiresPlayer = true;
        entry.group = "anvil";
        entry.booleanFlags.add("d");
        entry.handler = ctx -> {
            List<Path> files = regionFiles(ctx.world());
            Map<String, Long> counts = new LinkedHashMap<>();
            int chunks = RegionFiles.forEach(files, chunk -> {
                ChunkData.count(chunk.data(), counts);
                return true;
            });
            if (chunks == 0) {
                throw CommandRegistry.error("No stored chunk found for this world");
            }
            if (ctx.hasFlag("d")) {
                distribution(ctx, counts);
                return;
            }
            long total = counts.values().stream().mapToLong(Long::longValue).sum();
            ctx.actor().message(Msg.result("Counted", Msg.count(chunks) + " chunk(s), "
                    + Msg.count(total) + " block(s)"));
        };
    }

    /** {@code /anvil distr [-d]} — the selection's block histogram. */
    private void distr() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("/anvil distr");
        if (entry == null) {
            return;
        }
        entry.description = "Show the block distribution of a selection";
        entry.requiresPlayer = true;
        entry.group = "anvil";
        entry.requiresSelection = true;
        entry.booleanFlags.add("d");
        entry.handler = ctx -> distribution(ctx, countSelection(ctx, null, ctx.hasFlag("d")));
    }

    /** {@code /anvil replace <from> <to> [-d]}. */
    private void replace() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("/anvil replace", "/anvil r");
        if (entry == null) {
            return;
        }
        entry.description = "Replace all blocks in the selection with another";
        entry.requiresPlayer = true;
        entry.group = "anvil";
        entry.requiresSelection = true;
        entry.booleanFlags.add("d");
        entry.arguments.add("from");
        entry.arguments.add("to");
        entry.handler = ctx -> replaceInSelection(ctx, ctx.mask(0), ctx.pattern(1));
    }

    /** {@code /anvil replaceall <from> <to> [-d]} — the same, with wildcards. */
    private void replaceAll() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("/anvil replaceall",
                "/anvil rea", "/anvil repall", "/anvil replaceallpattern", "/anvil reap", "/anvil repallpat");
        if (entry == null) {
            return;
        }
        entry.description = "Replace all blocks in the selection with another";
        entry.requiresPlayer = true;
        entry.group = "anvil";
        entry.requiresSelection = true;
        entry.booleanFlags.add("d");
        // -m reads <from> as a comma separated map of sources and pairs each of
        // them with the matching entry of <to>.
        entry.booleanFlags.add("m");
        entry.arguments.add("from");
        entry.arguments.add("to");
        entry.handler = ctx -> replaceInSelection(ctx, ctx.mask(0), ctx.pattern(1));
    }

    /** {@code /anvil replacepattern <from> <pattern> [-d]}. */
    private void replacePattern() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("/anvil replacepattern",
                "/anvil preplace", "/anvil rp");
        if (entry == null) {
            return;
        }
        entry.description = "Replace all blocks in the selection with a pattern";
        entry.requiresPlayer = true;
        entry.group = "anvil";
        entry.requiresSelection = true;
        entry.booleanFlags.add("d");
        entry.booleanFlags.add("m");
        entry.arguments.add("from");
        entry.arguments.add("pattern");
        entry.handler = ctx -> replaceInSelection(ctx, ctx.mask(0), ctx.pattern(1));
    }

    /** {@code /anvil set <pattern>}. */
    private void set() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("/anvil set");
        if (entry == null) {
            return;
        }
        entry.description = "Set all blocks in the selection with a pattern";
        entry.requiresPlayer = true;
        entry.group = "anvil";
        entry.requiresSelection = true;
        entry.arguments.add("pattern");
        entry.handler = ctx -> {
            Pattern pattern = ctx.pattern(0);
            EditSession session = ctx.editSession("anvil set");
            int changed = 0;
            for (BlockVector3 position : ctx.selection()) {
                changed += session.setBlock(position.x(), position.y(), position.z(),
                        pattern.apply(position)) ? 1 : 0;
            }
            flush(ctx, session, "Changed", Msg.count(changed) + " block(s)");
        };
    }

    /** {@code /anvil removelayers <block>} — strips one block from a chunk layer. */
    private void removeLayers() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("/anvil removelayers");
        if (entry == null) {
            return;
        }
        entry.description = "Removes matching chunk layers, only in chunks holding the block";
        entry.requiresPlayer = true;
        entry.group = "anvil";
        entry.requiresSelection = true;
        entry.arguments.add("block");
        entry.handler = ctx -> {
            int target = BlockState.registry().parse(ctx.arg(0));
            if (target < 0) {
                target = BlockState.registry().defaultState(ctx.arg(0).contains(":")
                        ? ctx.arg(0) : "minecraft:" + ctx.arg(0));
            }
            if (target < 0) {
                throw CommandRegistry.error("Unknown block '" + ctx.arg(0) + "'");
            }
            Region region = ctx.selection();
            BlockStateRegistry registry = BlockState.registry();
            EditSession session = ctx.editSession("anvil removelayers");
            int changed = 0;
            for (int x = region.getMinimumPoint().x(); x <= region.getMaximumPoint().x(); x++) {
                for (int z = region.getMinimumPoint().z(); z <= region.getMaximumPoint().z(); z++) {
                    for (int y = region.getMinimumPoint().y(); y <= region.getMaximumPoint().y(); y++) {
                        if (!region.contains(x, y, z)) {
                            continue;
                        }
                        if (registry.name(session.getBlock(x, y, z)).equals(registry.name(target))
                                && session.setBlock(x, y, z, registry.air())) {
                            changed++;
                        }
                    }
                }
            }
            flush(ctx, session, "Removed", Msg.count(changed) + " block(s)");
        };
    }

    /** {@code /anvil trimallair [-u]} — chunks that never held anything. */
    private void trimAllAir() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("/anvil trimallair");
        if (entry == null) {
            return;
        }
        entry.description = "Trim all air in the world";
        entry.requiresPlayer = true;
        entry.group = "anvil";
        entry.booleanFlags.add("u");
        entry.handler = ctx -> {
            List<int[]> chunks = matchingChunks(ctx, chunk -> ChunkData.isAirOnly(chunk.data()));
            ctx.actor().message(Msg.info(chunks.size() + " chunk(s) hold air only"));
            if (chunks.isEmpty()) {
                return;
            }
            EditSession session = ctx.editSession("anvil trimallair");
            int cleared = clearChunks(session, ctx.world(), chunks);
            flush(ctx, session, "Trimmed", Msg.count(cleared) + " empty chunk(s)");
        };
    }

    /** {@code /anvil trimallplots [-v]} — chunks no player ever touched. */
    private void trimAllPlots() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("/anvil trimallplots");
        if (entry == null) {
            return;
        }
        entry.description = "Trim chunks in a plot world";
        entry.requiresPlayer = true;
        entry.group = "anvil";
        entry.booleanFlags.add("v");
        // FAWE deletes unclaimed and unmodified plot chunks. Without a plot
        // manager the claim half cannot be answered, so -v drops the chunks that
        // were never occupied and the default drops the unmodified (empty) ones.
        entry.handler = ctx -> {
            if (ctx.hasFlag("v")) {
                deleteUnvisited(ctx, matchingChunks(ctx, unvisitedFilter(ctx)), "never occupied");
                return;
            }
            deleteChunks(ctx, matchingChunks(ctx, chunk -> ChunkData.isAirOnly(chunk.data())),
                    "unmodified");
        };
    }

    /** {@code /anvil deletebiomechunks <biome> [-u]}. */
    private void deleteBiomeChunks() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("/anvil deletebiomechunks");
        if (entry == null) {
            return;
        }
        entry.description = "Delete chunks matching a specific biome";
        entry.requiresPlayer = true;
        entry.group = "anvil";
        entry.booleanFlags.add("u");
        entry.arguments.add("biome");
        entry.handler = ctx -> {
            String wanted = ctx.arg(0).toLowerCase(Locale.ROOT);
            if (!wanted.contains(":")) {
                wanted = "minecraft:" + wanted;
            }
            String biome = wanted;
            List<int[]> chunks = matchingChunks(ctx, chunk ->
                    ChunkData.biomes(chunk.data()).contains(biome));
            deleteChunks(ctx, chunks, "biome " + biome);
        };
    }

    /** {@code /anvil deleteallunvisited <ticks> [fileMillis]}. */
    private void deleteAllUnvisited() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("/anvil deleteallunvisited",
                "/anvil delunvisited");
        if (entry == null) {
            return;
        }
        entry.description = "Delete all chunks which haven't been occupied";
        entry.requiresPlayer = true;
        entry.group = "anvil";
        entry.arguments.add("inhabitedTicks");
        entry.arguments.add("[fileDurationMillis]");
        entry.handler = ctx -> deleteUnvisited(ctx, matchingChunks(ctx, unvisitedFilter(ctx)));
    }

    /** {@code /anvil deleteallunclaimed <ticks> [fileMillis] [-d]}. */
    private void deleteAllUnclaimed() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("/anvil deleteallunclaimed",
                "/anvil delallunclaimed");
        if (entry == null) {
            return;
        }
        entry.description = "Delete every chunk that was never occupied";
        entry.requiresPlayer = true;
        entry.group = "anvil";
        entry.booleanFlags.add("d");
        entry.arguments.add("inhabitedTicks");
        entry.arguments.add("[fileDurationMillis]");
        // FAWE asks a claim provider (WorldGuard, PlotSquared, GriefPrevention)
        // whether the chunk is claimed and then applies the same age test. A mod
        // has no claim provider to ask, so the age test decides on its own and
        // the report says so.
        entry.handler = ctx -> deleteUnvisited(ctx, matchingChunks(ctx, unvisitedFilter(ctx)),
                "never occupied, no claim provider to check");
    }

    /** {@code /anvil deleteunclaimed <ticks> [fileMillis] [-d]} — inside the selection. */
    private void deleteUnclaimed() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("/anvil deleteunclaimed");
        if (entry == null) {
            return;
        }
        entry.description = "Delete every chunk of the selection that was never occupied";
        entry.requiresPlayer = true;
        entry.group = "anvil";
        entry.requiresSelection = true;
        entry.booleanFlags.add("d");
        entry.arguments.add("inhabitedTicks");
        entry.arguments.add("[fileDurationMillis]");
        entry.handler = ctx -> deleteUnvisited(ctx, matchedInSelection(ctx, unvisitedFilter(ctx)),
                "never occupied, no claim provider to check");
    }

    /** {@code /anvil deletealloldregions <time>} — e.g. {@code 8h5m12s}. */
    private void deleteAllOldRegions() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("/anvil deletealloldregions",
                "/anvil deloldreg");
        if (entry == null) {
            return;
        }
        entry.description = "Delete regions which haven't been accessed in a certain amount of time";
        entry.requiresPlayer = true;
        entry.group = "anvil";
        entry.arguments.add("time");
        entry.handler = ctx -> {
            long millis = com.maxlananas.fawebim.core.util.Str.parseDuration(ctx.arg(0));
            long cutoff = (System.currentTimeMillis() - millis) / 1000L;
            List<int[]> chunks = matchingChunks(ctx, chunk -> chunk.modifiedSeconds() > 0
                    && chunk.modifiedSeconds() < cutoff);
            deleteChunks(ctx, chunks, "older than " + ctx.arg(0));
        };
    }

    /**
     * {@code /anvil remapall} — normalises stored palettes.
     *
     * <p>A world saved by an older version can hold palette entries this version
     * no longer knows. Reading and writing the chunk through the registry drops
     * them, which is the modern equivalent of FAWE's MCPE/PC id remap.</p>
     */
    private void remapAll() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("/anvil remapall");
        if (entry == null) {
            return;
        }
        entry.description = "Remap the world between MCPE/PC values";
        entry.requiresPlayer = true;
        entry.group = "anvil";
        entry.handler = ctx -> {
            List<int[]> chunks = matchingChunks(ctx, chunk -> holdsUnknownBlocks(chunk.data()));
            if (chunks.isEmpty()) {
                ctx.actor().message(Msg.info("Every stored chunk uses known blocks, nothing to remap"));
                return;
            }
            // Loading the chunk is what remaps it: the server resolves the palette
            // against the current block registry and saves only what it kept.
            int loaded = 0;
            for (int[] chunk : chunks) {
                ctx.world().loadChunk(chunk[0], chunk[1]);
                loaded++;
            }
            ctx.actor().message(Msg.success("Loaded " + loaded + " chunk(s) holding unknown blocks; "
                    + "the server rewrites them without the removed ids on its next save"));
        };
    }

    /** {@code /anvil debugfixroads} — FAWE's diagnostic command, a no-op there too. */
    private void debugFixRoads() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("/anvil debugfixroads");
        if (entry == null) {
            return;
        }
        entry.description = "debug - do not use";
        entry.requiresPlayer = true;
        entry.group = "anvil";
        entry.handler = ctx -> ctx.actor().message(Msg.info(
                "debugfixroads is a diagnostic command of the old PlotSquared road format; nothing to do"));
    }

    // ---------------------------------------------------------------- helpers

    private void replaceInSelection(Ctx ctx, Mask mask, Pattern pattern) {
        EditSession session = ctx.editSession("anvil replace");
        Map<String, Pattern> mapped = ctx.hasFlag("m") ? mapOf(ctx, mask) : null;
        BlockStateRegistry registry = BlockState.registry();
        int changed = 0;
        for (BlockVector3 position : ctx.selection()) {
            if (!mask.test(position)) {
                continue;
            }
            Pattern target = pattern;
            if (mapped != null) {
                target = mapped.get(registry.name(session.getBlock(position.x(), position.y(), position.z())));
                if (target == null) {
                    continue;
                }
            }
            changed += session.setBlock(position.x(), position.y(), position.z(),
                    target.apply(position)) ? 1 : 0;
        }
        flush(ctx, session, "Replaced", Msg.count(changed) + " block(s)");
    }

    /**
     * Reads {@code -m}: the two arguments are comma separated lists, each source
     * of the first list is replaced by the pattern at the same position of the
     * second one. A single source may map to several patterns, which then act as
     * a random pattern.
     */
    private Map<String, Pattern> mapOf(Ctx ctx, Mask mask) {
        List<String> sources = com.maxlananas.fawebim.core.util.Str.splitTopLevel(ctx.arg(0), ',');
        List<String> targets = com.maxlananas.fawebim.core.util.Str.splitTopLevel(ctx.arg(1), ',');
        if (sources.isEmpty() || targets.isEmpty()) {
            throw CommandRegistry.error("The map needs at least one source and one target");
        }
        BlockStateRegistry registry = BlockState.registry();
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (int index = 0; index < sources.size(); index++) {
            String target = targets.get(index < targets.size() ? index : targets.size() - 1).trim();
            String source = sources.get(index < sources.size() ? index : sources.size() - 1).trim();
            String name = registry.name(registry.parse(source) < 0 ? registry.defaultState(source)
                    : registry.parse(source));
            grouped.computeIfAbsent(name, key -> new ArrayList<>()).add(target);
        }
        Map<String, Pattern> mapped = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : grouped.entrySet()) {
            mapped.put(entry.getKey(), Parsers.pattern(String.join(",", entry.getValue()), ctx));
        }
        return mapped;
    }

    private Map<String, Long> countSelection(Ctx ctx, Mask mask, boolean byState) {
        World world = ctx.world();
        Region region = ctx.selection();
        BlockStateRegistry registry = BlockState.registry();
        Map<String, Long> counts = new LinkedHashMap<>();
        for (BlockVector3 position : region) {
            if (mask != null && !mask.test(position)) {
                continue;
            }
            int state = world.getBlock(position.x(), position.y(), position.z());
            counts.merge(byState ? registry.describe(state) : registry.name(state), 1L, Long::sum);
        }
        return counts;
    }

    private void distribution(Ctx ctx, Map<String, Long> counts) {
        if (counts.isEmpty()) {
            throw CommandRegistry.error("No block found");
        }
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        ctx.actor().message(Msg.title("Distribution (" + Msg.formatNumber(total) + " blocks)"));
        List<Map.Entry<String, Long>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort(Comparator.comparingLong((Map.Entry<String, Long> entry) -> entry.getValue()).reversed());
        for (Map.Entry<String, Long> entry : sorted) {
            double share = 100.0 * entry.getValue() / total;
            ctx.actor().message(Msg.item(entry.getKey(), Msg.formatNumber(entry.getValue()) + " ("
                    + String.format(Locale.ROOT, "%.2f", share) + "%)"));
        }
    }

    private Predicate<RegionFiles.Chunk> unvisitedFilter(Ctx ctx) {
        long ticks = ctx.intArg(0);
        String fileArgument = ctx.arg(1, "60000");
        long fileMillis = fileArgument.matches("\\d+") ? Long.parseLong(fileArgument) : 60_000L;
        long cutoff = (System.currentTimeMillis() - fileMillis) / 1000L;
        return chunk -> ChunkData.inhabitedTicks(chunk.data()) < ticks
                && (chunk.modifiedSeconds() == 0 || chunk.modifiedSeconds() < cutoff);
    }

    private void deleteUnvisited(Ctx ctx, List<int[]> chunks) {
        deleteUnvisited(ctx, chunks, "unvisited");
    }

    private void deleteUnvisited(Ctx ctx, List<int[]> chunks, String reason) {
        if (ctx.hasFlag("d")) {
            ctx.actor().message(Msg.info(chunks.size() + " chunk(s) qualify (" + reason + ")"));
        }
        deleteChunks(ctx, chunks, reason);
    }

    /** Deletes (empties) the given chunks and reports the result. */
    private void deleteChunks(Ctx ctx, List<int[]> chunks, String reason) {
        if (chunks.isEmpty()) {
            ctx.actor().message(Msg.info("No chunk matches: " + reason));
            return;
        }
        EditSession session = ctx.editSession("anvil delete");
        int cleared = clearChunks(session, ctx.world(), chunks);
        flush(ctx, session, "Deleted", Msg.count(cleared) + " chunk(s) (" + reason + ")");
    }

    /**
     * Empties chunks: every stored block goes away, which is what deleting the
     * chunk means for the running world. The chunks stay in the file until the
     * server saves them, exactly like a {@code //set air} over their volume.
     */
    private int clearChunks(EditSession session, World world, List<int[]> chunks) {
        int cleared = 0;
        for (int[] chunk : chunks) {
            session.limiter().check(1);
            if (clearChunk(session, world, chunk[0], chunk[1])) {
                cleared++;
            }
            // Keep the buffered chunk data small on long runs.
            session.flushQueue();
        }
        return cleared;
    }

    private boolean clearChunk(EditSession session, World world, int chunkX, int chunkZ) {
        BlockStateRegistry registry = BlockState.registry();
        int changed = 0;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                int worldX = (chunkX << 4) + x;
                int worldZ = (chunkZ << 4) + z;
                int highest = world.getHighestBlockY(worldX, worldZ);
                for (int y = world.minY(); y <= highest; y++) {
                    changed += session.setBlock(worldX, y, worldZ, registry.air()) ? 1 : 0;
                }
            }
        }
        return changed > 0;
    }

    private boolean holdsUnknownBlocks(com.maxlananas.fawebim.core.util.NbtCompound chunk) {
        if (chunk == null) {
            return false;
        }
        for (com.maxlananas.fawebim.core.util.NbtCompound section : chunk.getCompoundList("sections")) {
            com.maxlananas.fawebim.core.util.NbtCompound states = section.getCompoundOrNull("block_states");
            if (states == null) {
                continue;
            }
            for (Object entry : states.getList("palette")) {
                if (entry instanceof com.maxlananas.fawebim.core.util.NbtCompound compound) {
                    String name = compound.getString("Name", "minecraft:air");
                    if (BlockState.registry().defaultState(name) < 0) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private List<int[]> matchingChunks(Ctx ctx, Predicate<RegionFiles.Chunk> predicate) {
        List<int[]> chunks = new ArrayList<>();
        RegionFiles.forEach(regionFiles(ctx.world()), chunk -> {
            if (predicate.test(chunk)) {
                chunks.add(new int[]{chunk.chunkX(), chunk.chunkZ()});
                if (chunks.size() >= MAX_CHUNKS) {
                    ctx.actor().message(Msg.info("Stopped at " + MAX_CHUNKS + " chunks, run the command again"));
                    return false;
                }
            }
            return true;
        });
        return chunks;
    }

    private List<int[]> matchedInSelection(Ctx ctx, Predicate<RegionFiles.Chunk> predicate) {
        Region region = ctx.selection();
        List<int[]> chunks = new ArrayList<>();
        for (int[] chunk : matchingChunks(ctx, predicate)) {
            int minX = chunk[0] << 4;
            int minZ = chunk[1] << 4;
            if (minX + 15 >= region.getMinimumPoint().x() && minX <= region.getMaximumPoint().x()
                    && minZ + 15 >= region.getMinimumPoint().z() && minZ <= region.getMaximumPoint().z()) {
                chunks.add(chunk);
            }
        }
        return chunks;
    }

    private List<int[]> selectionChunks(Ctx ctx) {
        Region region = ctx.selection();
        List<int[]> chunks = new ArrayList<>();
        for (int chunkX = region.getMinimumPoint().x() >> 4; chunkX <= region.getMaximumPoint().x() >> 4;
             chunkX++) {
            for (int chunkZ = region.getMinimumPoint().z() >> 4;
                 chunkZ <= region.getMaximumPoint().z() >> 4; chunkZ++) {
                chunks.add(new int[]{chunkX, chunkZ});
            }
        }
        return chunks;
    }

    private List<Path> regionFiles(World world) {
        List<Path> files = RegionFiles.files(world.regionDirectory());
        if (files.isEmpty()) {
            throw CommandRegistry.error("This world's region files are not available");
        }
        return files;
    }

    /** Flushes the queue and answers with the one result line the mod uses. */
    private void flush(Ctx ctx, EditSession session, String label, String detail) {
        session.flushQueue();
        ctx.actor().message(Msg.result(label, detail));
    }
}
