package com.maxlananas.fawebim.core.command;

import com.maxlananas.fawebim.core.platform.Config;
import com.maxlananas.fawebim.core.util.Msg;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The administration side of the {@code /we} container: version, reload, trace,
 * timezone, thread dump and the diagnostics report. These are the commands that
 * tell a player or an administrator what the mod is doing.
 */
final class WorldCommands {

    private final CommandRegistry registry;

    WorldCommands(CommandRegistry registry) {
        this.registry = registry;
    }

    void register() {
        version();
        reload();
        threads();
        trace();
        timezone();
        report();
        debugPaste();
        cui();
    }

    /** {@code /we version} — the mod version, the Minecraft version and the target. */
    private void version() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("/we version", "/we ver", "/version", "/ver");
        if (entry == null) {
            return;
        }
        entry.description = "Get the FAWE-BIM version";
        entry.group = "worldedit";
        entry.handler = ctx -> {
            ctx.actor().message(Msg.of("§8» §6FAWE-BIM §e" + Config.VERSION + "§r (FastAsyncWorldEdit, but in mods)"));
            ctx.actor().message(Msg.of("§8» §7Minecraft §f" + Config.MINECRAFT_VERSION
                    + "§7, Fabric §f" + loaderVersion()));
            ctx.actor().message(Msg.of("§8» §7Author §fMaxLananas§7, based on WorldEdit 7.3.17 and FastAsyncWorldEdit"));
        };
    }

    private static String loaderVersion() {
        String version = WorldCommands.class.getPackage().getImplementationVersion();
        return version == null ? "0.17.x" : version;
    }

    /** {@code /we reload} — reloads the config file from disk. */
    private void reload() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("/we reload");
        if (entry == null) {
            return;
        }
        entry.description = "Reload the FAWE-BIM configuration";
        entry.group = "worldedit";
        entry.handler = ctx -> {
            Config.get().reload();
            ctx.actor().message(Msg.success("Configuration reloaded"));
        };
    }

    /** {@code /we threads} — dumps every live thread, FAWE's {@code /threads}. */
    private void threads() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("/we threads", "/threads");
        if (entry == null) {
            return;
        }
        entry.description = "Print all thread stacks";
        entry.group = "worldedit";
        entry.handler = ctx -> {
            Thread[] threads = new Thread[Thread.activeCount() + 8];
            int count = Thread.enumerate(threads);
            int alive = 0;
            for (int i = 0; i < count; i++) {
                Thread thread = threads[i];
                if (thread == null || !thread.isAlive()) {
                    continue;
                }
                alive++;
                ctx.actor().message(Msg.of("§7" + thread.getName() + " §8[" + thread.getState() + "]"));
            }
            ctx.actor().message(Msg.info(alive + " live thread(s); "
                    + ManagementFactory.getThreadMXBean().getThreadCount() + " total"));
        };
    }

    /** {@code /we trace} — toggles the session trace hook used to debug edits. */
    private void trace() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("/we trace", "/trace");
        if (entry == null) {
            return;
        }
        entry.description = "Toggle the trace hook for your edits";
        entry.group = "worldedit";
        entry.handler = ctx -> {
            boolean tracing = !ctx.session().isTracing();
            ctx.session().setTracing(tracing);
            ctx.actor().message(Msg.success("Trace hook " + (tracing ? "enabled" : "disabled")
                    + (tracing ? "; the next edit prints its steps" : "")));
        };
    }

    /** {@code /we tz} — the timezone used when snapshots are listed by date. */
    private void timezone() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("/we tz", "/tz");
        if (entry == null) {
            return;
        }
        entry.description = "Set your timezone for snapshots";
        entry.group = "worldedit";
        entry.arguments.add("[timezone]");
        entry.handler = ctx -> {
            if (ctx.args().isEmpty()) {
                ctx.session().setTimezone(ZoneId.systemDefault());
                ctx.actor().message(Msg.info("Timezone reset to " + ZoneId.systemDefault()));
                return;
            }
            ZoneId zone;
            try {
                zone = ZoneId.of(ctx.arg(0));
            } catch (RuntimeException e) {
                throw CommandRegistry.error("Unknown timezone '" + ctx.arg(0) + "'; try UTC or America/New_York");
            }
            ctx.session().setTimezone(zone);
            ctx.actor().message(Msg.success("Timezone set to " + zone));
        };
    }

    /** {@code /we report} — writes a diagnostics report to a file. */
    private void report() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("/we report", "/report");
        if (entry == null) {
            return;
        }
        entry.description = "Write a report about this installation";
        entry.group = "worldedit";
        // -p adds the registered command list, one page per report entry.
        entry.valueFlags.add("p");
        entry.arguments.add("[-p <page>]");
        entry.handler = ctx -> {
            Path file = Config.get().resolveDirectory("fawe-reports")
                    .resolve("report-" + System.currentTimeMillis() + ".txt");
            List<String> lines = new ArrayList<>(List.of(
                    "FAWE-BIM " + Config.VERSION + " (Minecraft " + Config.MINECRAFT_VERSION + ")",
                    "Author: MaxLananas",
                    "Based on WorldEdit 7.3.17 and FastAsyncWorldEdit",
                    "Java: " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")",
                    "OS: " + System.getProperty("os.name") + " " + System.getProperty("os.arch"),
                    "Available processors: " + Runtime.getRuntime().availableProcessors(),
                    "Memory: " + (Runtime.getRuntime().totalMemory() >> 20) + " MiB total, "
                            + (Runtime.getRuntime().freeMemory() >> 20) + " MiB free",
                    "Threads: " + ManagementFactory.getThreadMXBean().getThreadCount(),
                    "Registered commands: " + registry.all().size(),
                    "World: " + ctx.world().name() + " (" + ctx.world().minY() + ".." + ctx.world().maxY() + ")",
                    "Configured threads: " + Config.get().threads));
            // -p appends the command list of one page, which is what FAWE's
            // report does once the summary is written.
            int page = ctx.flagInt("p", 0);
            if (page > 0) {
                lines.add("");
                lines.add("Registered commands (page " + page + "):");
                List<CommandRegistry.Entry> entries = new ArrayList<>(registry.all());
                entries.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
                int pageSize = 50;
                int from = Math.min(entries.size(), (page - 1) * pageSize);
                int to = Math.min(entries.size(), from + pageSize);
                for (CommandRegistry.Entry registered : entries.subList(from, to)) {
                    lines.add("  " + registered.usage() + " - " + registered.description);
                }
            }
            try {
                Files.createDirectories(file.getParent());
                Files.write(file, lines);
            } catch (IOException e) {
                throw CommandRegistry.error("Could not write the report: " + e.getMessage());
            }
            ctx.actor().message(Msg.success("Report written to " + file));
        };
    }

    /**
     * {@code /we debugpaste} — the online paste service WorldEdit uses cannot be
     * reached from a mod, so the same information is printed to the chat and
     * written next to the world.
     */
    private void debugPaste() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("/we debugpaste", "/debugpaste");
        if (entry == null) {
            return;
        }
        entry.description = "Write the debug information a paste would contain";
        entry.group = "worldedit";
        entry.handler = ctx -> {
            Map<String, String> info = new java.util.LinkedHashMap<>();
            info.put("version", Config.VERSION + " for Minecraft " + Config.MINECRAFT_VERSION);
            info.put("java", System.getProperty("java.version"));
            info.put("players-world", ctx.world().name() + " @ " + ctx.actor().position());
            info.put("commands", String.valueOf(registry.all().size()));
            ctx.actor().message(Msg.info("Debug information (also written to ./fawe-report.txt):"));
            for (Map.Entry<String, String> line : info.entrySet()) {
                ctx.actor().message(Msg.of("§7" + line.getKey() + "§r: §f" + line.getValue()));
            }
            Path file = Config.get().resolveDirectory(".").resolve("fawe-report.txt");
            try {
                StringBuilder builder = new StringBuilder();
                for (Map.Entry<String, String> line : info.entrySet()) {
                    builder.append(line.getKey()).append(": ").append(line.getValue()).append(System.lineSeparator());
                }
                Files.writeString(file, builder.toString());
            } catch (IOException e) {
                ctx.actor().message(Msg.warn("Could not write " + file + ": " + e.getMessage()));
            }
        };
    }

    /**
     * {@code /we cui} — the CUI handshake used by the client side mod. A vanilla
     * client cannot receive it, so the handshake is answered locally and the
     * selection preview is drawn by the mod itself.
     */
    private void cui() {
        CommandRegistry.Entry entry = registry.registerUnlessPresent("/we cui", "/cui");
        if (entry == null) {
            return;
        }
        entry.description = "Complete the CUI handshake";
        entry.group = "worldedit";
        entry.handler = ctx -> {
            ctx.session().setCuiEnabled(true);
            ctx.actor().message(Msg.success("Selection outline enabled; the preview is drawn by FAWE-BIM"));
        };
    }
}
