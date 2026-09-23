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
    private static final Logger LOGGER = LoggerFactory.getLogger("FAWE-BIM");

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

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            try {
                Config.get().load(server.getServerDirectory());
                registry = new FabricBlockStateRegistry();
                BlockState.setRegistry(registry);
                EditSession.BlockStateRegistryHolder.set(registry);
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
                CommandManager.get().initialise();
                LOGGER.info("FAWE-BIM ready: {} commands registered, {} block states known",
                        CommandManager.get().size(), registry.stateCount());
            } catch (Throwable throwable) {
                LOGGER.error("FAWE-BIM failed to start", throwable);
            }
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            Config.get().save();
            SessionManager.get().clear();
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
            // A player with operator rights may always run them; a command block
            // or a function only when the configuration allows it, which is what
            // WorldEdit's command-block-support decides.
            builder.requires(source -> (source.hasPermission(2) || !source.getServer().isDedicatedServer())
                    && (source.getEntity() instanceof ServerPlayer || Config.get().commandBlockSupport));
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
        builder.suggests((context, suggestions) -> suggest(node.entry, suggestions));
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
     * The tab completions of a command: the argument names its signature declares,
     * plus whatever the command computes from the text typed so far — the setting
     * keys of {@code /fawebim} come from there.
     */
    private static CompletableFuture<Suggestions> suggest(CommandRegistry.Entry entry, SuggestionsBuilder builder) {
        for (String suggestion : entry.arguments) {
            if (!suggestion.startsWith("<")) {
                builder.suggest(suggestion);
            }
        }
        if (entry.suggestions != null) {
            for (String suggestion : entry.suggestions.apply(builder.getRemaining())) {
                builder.suggest(suggestion);
            }
        }
        return builder.buildFuture();
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
