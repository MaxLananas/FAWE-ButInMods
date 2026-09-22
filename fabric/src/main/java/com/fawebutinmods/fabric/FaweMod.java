package com.fawebutinmods.fabric;

import com.fawebutinmods.core.clipboard.Schematics;
import com.fawebutinmods.core.command.CommandManager;
import com.fawebutinmods.core.command.CommandRegistry;
import com.fawebutinmods.core.extent.EditSession;
import com.fawebutinmods.core.platform.Config;
import com.fawebutinmods.core.session.SessionManager;
import com.fawebutinmods.core.world.BlockState;
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

    public static final String MOD_ID = "fawe";
    private static final Logger LOGGER = LoggerFactory.getLogger("FAWE-ButInMods");

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
                CommandManager.get().initialise();
                LOGGER.info("FAWE-ButInMods ready: {} commands registered, {} block states known",
                        CommandManager.get().size(), registry.stateCount());
            } catch (Throwable throwable) {
                LOGGER.error("FAWE-ButInMods failed to start", throwable);
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

        LOGGER.info("FAWE-ButInMods initialised");
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
            builder.requires(source -> source.hasPermission(2) || !source.getServer().isDedicatedServer());
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

    private static CompletableFuture<Suggestions> suggest(CommandRegistry.Entry entry, SuggestionsBuilder builder) {
        for (String suggestion : entry.arguments) {
            if (!suggestion.startsWith("<")) {
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
