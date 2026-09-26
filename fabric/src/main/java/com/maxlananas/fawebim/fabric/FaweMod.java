package com.maxlananas.fawebim.fabric;

import com.maxlananas.fawebim.core.clipboard.Schematics;
import com.maxlananas.fawebim.core.command.CommandManager;
import com.maxlananas.fawebim.core.command.CommandRegistry;
import com.maxlananas.fawebim.core.extent.EditSession;
import com.maxlananas.fawebim.core.history.EditLog;
import com.maxlananas.fawebim.core.history.Snapshots;
import com.maxlananas.fawebim.core.platform.Config;
import com.maxlananas.fawebim.core.session.SessionManager;
import com.maxlananas.fawebim.core.world.BlockState;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

/**
 * The mod entry point.
 *
 * <p>It wires the engine to Minecraft: it installs the block-state registry, the
 * config and the schematics directory on server start, registers the whole
 * command surface ({@code //set}, {@code /brush}, {@code /tool}, ...) and hooks
 * the click callbacks that drive wands, brushes and tools.</p>
 */
public final class FaweMod implements ModInitializer {

    public static final String MOD_ID = "fawebim";
    /** The mod's logger, shared by the adapters so all of it lands in one place. */
    static final Logger LOGGER = LoggerFactory.getLogger("FAWE-BIM");

    /**
     * Disk writes of the mod. One thread, so two edits of the same millisecond
     * cannot race for the same file, and off the server thread, so a large edit
     * is not held up by its own history file.
     */
    private static final java.util.concurrent.ExecutorService WRITER =
            java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "FAWE-BIM history");
                thread.setDaemon(true);
                return thread;
            });

    private static FabricBlockStateRegistry registry;

    public static FabricBlockStateRegistry registry() {
        return registry;
    }

    /**
     * Installs the block registry and builds the command registry.
     *
     * <p>Idempotent, and called both at mod load and once a server is starting:
     * the first call is what makes the commands exist at all, the second picks up
     * the blocks the data packs and the other mods added.</p>
     */
    private static void prepareEngine() {
        registry = new FabricBlockStateRegistry();
        BlockState.setRegistry(registry);
        EditSession.BlockStateRegistryHolder.set(registry);
        CommandManager.get().initialise();
    }

    @Override
    public void onInitialize() {
        // The game builds the command dispatcher while its server object is being
        // constructed, which happens before the starting event reaches the mod.
        // Everything the commands need to exist has to be ready by then, so the
        // engine is prepared here, at mod load.
        prepareEngine();

        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            try {
                Config.get().load(server.getServerDirectory());
                FabricRegistries.install(server);
                // Blocks a mod registers after us are in the registry by now, so it
                // is read again; the state ids do not change, which is what the
                // commands built at load time hold on to.
                prepareEngine();
                Schematics.setDirectory(server.getServerDirectory()
                        .resolve(Config.get().schematicSaveDirectory));
                // The snapshots and the disk history live next to the world, which
                // is where a player looks for them.
                Snapshots.setDirectory(server.getServerDirectory()
                        .resolve(Config.get().snapshotDirectory));
                EditLog.setDirectory(server.getServerDirectory()
                        .resolve(Config.get().historyDirectory));
                // Writing a history file or a snapshot serialises the whole edit,
                // so both run on the writer instead of on the server thread.
                EditLog.setWriter(WRITER);
                Snapshots.setWriter(WRITER);
                if (Config.get().enableDiskHistory) {
                    int restored = EditLog.load();
                    if (restored > 0) {
                        LOGGER.info("Read {} history entries back from disk", restored);
                    }
                }
                LOGGER.info("FAWE-BIM ready: {} commands registered, {} block states known",
                        CommandManager.get().size(), registry.stateCount());
            } catch (Throwable throwable) {
                LOGGER.error("FAWE-BIM failed to start", throwable);
            }
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            Config.get().save();
            SessionManager.get().clear();
            FabricRegistries.clear();
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                registerCommands(dispatcher));

        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
            if (hand != InteractionHand.MAIN_HAND || !(player instanceof ServerPlayer serverPlayer)) {
                return net.minecraft.world.InteractionResult.PASS;
            }
            return FabricInteractions.onAttackBlock(serverPlayer, pos, direction);
        });

        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            if (hand != InteractionHand.MAIN_HAND || !(player instanceof ServerPlayer serverPlayer)) {
                return net.minecraft.world.InteractionResult.PASS;
            }
            return FabricInteractions.onUseBlock(serverPlayer, hitResult.getBlockPos(), hitResult.getDirection());
        });

        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (hand != InteractionHand.MAIN_HAND || !(player instanceof ServerPlayer serverPlayer)) {
                return net.minecraft.world.InteractionResult.PASS;
            }
            return FabricInteractions.onUseItem(serverPlayer, player.getItemInHand(hand));
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (server.getTickCount() % 5 != 0) {
                // The selection preview only needs a few updates per second.
                return;
            }
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                FabricInteractions.tick(player);
            }
        });

        // A player landing in a world with the mod in it is told what it is and
        // where the commands are. The banner is one config switch away for anyone
        // who does not want it.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (!Config.get().welcomeMessage) {
                return;
            }
            FabricActor actor = new FabricActor(handler.getPlayer());
            for (com.maxlananas.fawebim.core.platform.Welcome.Line line
                    : com.maxlananas.fawebim.core.platform.Welcome.lines()) {
                if (line.openUrl() != null) {
                    actor.link(line.text().raw(), line.openUrl());
                } else if (line.runCommand() != null) {
                    actor.commandLink(line.text().raw(), line.runCommand(), line.hover());
                } else {
                    actor.message(line.text());
                }
            }

        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            SessionManager.get().remove(handler.getPlayer().getUUID());
            FabricInteractions.forget(handler.getPlayer().getUUID());
        });

        LOGGER.info("FAWE-BIM initialised");
    }

    /**
     * Registers the whole command surface.
     *
     * <p>Minecraft strips exactly one leading slash from what the player typed,
     * so WorldEdit's {@code //set} reaches a literal named {@code /set} — that
     * is how WorldEdit's own Fabric adapter registers its commands. Both forms
     * are registered ({@code /set} and {@code set}) so that {@code //set} and
     * {@code /set} both work, and a name already taken by a vanilla command
     * ({"{@code /fill}, {@code /clear}") is left alone.</p>
     */
    private void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        // Nothing to register without the command registry: if this ever runs
        // before the mod is initialised, the engine is built here.
        prepareEngine();
        Node root = new Node(null);
        for (CommandRegistry.Entry entry : CommandManager.get().registry().all()) {
            Set<String> names = new LinkedHashSet<>();
            names.add(entry.registrationName());
            for (String alias : entry.aliases) {
                names.add(literalName(alias));
            }
            for (String name : names) {
                insert(root, name, entry, entry.registrationName().equals(name));
            }
        }
        for (Node child : root.children.values()) {
            if (dispatcher.getRoot().getChild(child.name) != null) {
                LOGGER.warn("Command '/{}' is already taken, leaving it to the other owner", child.name);
                continue;
            }
            LiteralArgumentBuilder<CommandSourceStack> builder = build(child);
            // An operator, and anyone on a single-player world, may run them: the
            // per-command permissions are checked by the engine when a command
            // actually runs. A command block and a function are automated sources
            // and are the ones command-block-support governs - they are the
            // sources that refuse the success messages a console wants.
            builder.requires(source -> (source.hasPermission(2) || !source.getServer().isDedicatedServer())
                    && (source.getEntity() != null || source.source.acceptsSuccess()
                        || Config.get().commandBlockSupport));
            dispatcher.register(builder);
        }
    }

    /**
     * Adds one command name to the tree, in both its {@code //name} and
     * {@code /name} spellings.
     */
    private void insert(Node root, String name, CommandRegistry.Entry entry, boolean primary) {
        String literal = literalName(name);
        if (!insertPath(root, literal, entry, primary)) {
            return;
        }
        if (literal.startsWith("/")) {
            insertPath(root, literal.substring(1), entry, primary);
        }
    }

    /** Adds a literal path such as {@code /brush sphere} to the tree. */
    private boolean insertPath(Node root, String path, CommandRegistry.Entry entry, boolean primary) {
        String[] parts = path.split("\\s+");
        for (String part : parts) {
            if (part.startsWith("-") || !part.matches("[a-z0-9_.+/-]+")) {
                return false;
            }
        }
        Node node = root;
        for (String part : parts) {
            node = node.children.computeIfAbsent(part, Node::new);
        }
        if (node.entry == null || primary) {
            node.entry = entry;
        }
        node.primary |= primary;
        return true;
    }

    /**
     * Turns one literal of the tree into a Brigadier builder, children included.
     *
     * <p>A command line is free-form text — {@code //set 50%stone,50%dirt -a} is
     * not something Brigadier parses — so every literal accepts a greedy tail and
     * hands the whole line to the engine's own argument parser.</p>
     */
    private LiteralArgumentBuilder<CommandSourceStack> build(Node node) {
        LiteralArgumentBuilder<CommandSourceStack> builder = literal(node.name);
        for (Node child : node.children.values()) {
            builder.then(build(child));
        }
        if (node.entry == null) {
            return builder;
        }
        builder.executes(context -> run(context.getSource(), node.entry.name));
        builder.then(argument("arguments", StringArgumentType.greedyString())
                .suggests((context, suggestions) -> suggest(node.entry, suggestions))
                .executes(context -> run(context.getSource(),
                        node.entry.name + " " + context.getArgument("arguments", String.class))));
        return builder;
    }

    /**
     * The literal Minecraft must register for a WorldEdit name.
     *
     * <p>WorldEdit names are written with one leading slash ({@code /set},
     * {@code /brush sphere}); Minecraft strips one slash from what the player
     * types, so typing {@code //set} reaches the literal {@code /set}. Names
     * written with two slashes ({@code //p1}) lose one, which is exactly what
     * {@link CommandRegistry.Entry#registrationName()} does.</p>
     */
    private static String literalName(String name) {
        String trimmed = name.trim();
        return trimmed.startsWith("//") ? trimmed.substring(1) : trimmed;
    }

    /** One literal of the command tree: a name, an optional command, children. */
    private static final class Node {

        private final String name;
        private final Map<String, Node> children = new java.util.TreeMap<>();
        private CommandRegistry.Entry entry;
        private boolean primary;

        private Node(String name) {
            this.name = name;
        }
    }

    /**
     * The tab completions of a command: for the argument being typed, what its
     * signature says may be written there — the blocks of a pattern, the masks of
     * a filter, the biomes of a biome argument — plus whatever the command
     * computes from the text typed so far, which is where the setting keys of
     * {@code /fawebim} come from.
     */
    private static CompletableFuture<Suggestions> suggest(CommandRegistry.Entry entry, SuggestionsBuilder builder) {
        // Depending on where the cursor is Brigadier hands back the text from
        // the argument's start, which may still carry the separating space.
        String remaining = builder.getRemaining();
        if (remaining.startsWith(" ")) {
            remaining = remaining.substring(1);
        }
        String[] tokens = remaining.split(" ", -1);
        String typed = tokens[tokens.length - 1];
        int index = tokens.length - 1;
        String argument = index < entry.arguments.size() ? entry.arguments.get(index) : "";
        boolean found = false;
        for (String suggestion : com.maxlananas.fawebim.core.command.Suggestions.forArgument(argument, typed)) {
            builder.suggest(suggestion);
            found = true;
        }
        // An argument the engine cannot fill in - a radius, a count - still has
        // the words its signature offers, e.g. the "list|info|distr" of
        // //history, and those are worth completing.
        if (!found) {
            for (String suggestion : literalChoices(argument, typed)) {
                builder.suggest(suggestion);
            }
        }
        if (entry.suggestions != null) {
            for (String suggestion : entry.suggestions.apply(remaining)) {
                builder.suggest(suggestion);
            }
        }
        return builder.buildFuture();
    }

    /** The {@code a|b|c} alternatives of an argument, filtered by what is typed. */
    private static List<String> literalChoices(String argument, String typed) {
        String plain = argument.replace("[", "").replace("]", "").replace("<", "").replace(">", "");
        if (plain.indexOf('|') < 0) {
            return List.of();
        }
        String prefix = typed.toLowerCase(java.util.Locale.ROOT);
        List<String> choices = new java.util.ArrayList<>();
        for (String choice : plain.split("\\|")) {
            String word = choice.trim();
            if (!word.isEmpty() && word.toLowerCase(java.util.Locale.ROOT).startsWith(prefix)) {
                choices.add(word);
            }
        }
        return choices;
    }

    private int run(CommandSourceStack source, String line) {
        try {
            var entity = source.getEntity();
            if (entity instanceof ServerPlayer player) {
                CommandManager.get().dispatch(new FabricActor(player), line);
            } else {
                CommandManager.get().dispatch(new FabricActor(source, source.getLevel()), line);
            }
            return 1;
        } catch (Throwable throwable) {
            LOGGER.error("Command '{}' failed", line, throwable);
            source.sendFailure(net.minecraft.network.chat.Component.literal(
                    "FAWE error: " + throwable.getMessage()));
            return 0;
        }
    }

}
