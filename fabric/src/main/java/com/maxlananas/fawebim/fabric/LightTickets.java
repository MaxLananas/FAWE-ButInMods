package com.maxlananas.fawebim.fabric;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Keeps a chunk loaded while the light engine works on the light an edit
 * changed in it.
 *
 * <p>The light engine reaches the blocks of a chunk and the sky light sources
 * of its columns through the chunks the server has loaded. An edit loads the
 * chunks it writes with the game's own ticket, which lasts a tick, so a chunk
 * no player keeps loaded leaves the loaded set a tick or two after the edit,
 * possibly before the light thread gets to it. The checks the engine then runs
 * find no chunk: the sky light sources of the columns are not moved, the light
 * below a new roof stays at 15, and that is the light the chunk is saved and
 * loaded with. This ticket loads without simulating - nothing ticks in the
 * chunk because of it - and goes once the work queued for the chunk so far is
 * done.</p>
 *
 * <p>Server thread only. The future completes on the light thread, and what
 * follows it runs back on the server thread.</p>
 */
final class LightTickets {

    /** Loads its chunk at the full status, never times out: the light work removes it. */
    static final TicketType TYPE = new TicketType(TicketType.NO_TIMEOUT, TicketType.FLAG_LOADING);

    /** Per level, the last light work queued for each chunk a ticket holds. */
    private static final Map<ServerLevel, Long2ObjectOpenHashMap<CompletableFuture<?>>> PENDING = new HashMap<>();

    private LightTickets() {
    }

    /** At mod load, while the game's registries still take entries. */
    static void register() {
        Registry.register(BuiltInRegistries.TICKET_TYPE,
                ResourceLocation.fromNamespaceAndPath(FaweMod.MOD_ID, "light"), TYPE);
    }

    /**
     * Holds the chunk loaded until {@code done} completes. Work queued for the
     * chunk later takes the hold over, so the ticket stays until the last of it.
     */
    static void hold(ServerLevel level, ChunkPos pos, CompletableFuture<?> done) {
        long key = pos.toLong();
        Long2ObjectOpenHashMap<CompletableFuture<?>> pending =
                PENDING.computeIfAbsent(level, ignored -> new Long2ObjectOpenHashMap<>());
        if (pending.put(key, done) == null) {
            level.getChunkSource().addTicketWithRadius(TYPE, pos, 0);
        }
        done.whenCompleteAsync((ignored, error) -> {
            if (pending.remove(key, done)) {
                level.getChunkSource().removeTicketWithRadius(TYPE, pos, 0);
            }
        }, level.getServer());
    }

    /** When the server stops: its levels and their tickets go with it. */
    static void clear() {
        PENDING.clear();
    }
}
