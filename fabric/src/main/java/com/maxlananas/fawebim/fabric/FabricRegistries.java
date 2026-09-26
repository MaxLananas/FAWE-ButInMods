package com.maxlananas.fawebim.fabric;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.biome.Biome;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The registries of the running server.
 *
 * <p>Biomes live in a data-pack registry: the game has no built-in table for
 * them and hands them out as holders, while the engine counts biomes with
 * integers. The adapter therefore numbers the sorted names of the registry once
 * per server and keeps the table; everything that leaves the adapter — a
 * schematic, a history file, a chat message — carries the name, so the numbers
 * only have to be consistent while the session runs.</p>
 */
final class FabricRegistries {

    private static volatile RegistryAccess access;
    private static volatile List<String> biomeNames = List.of();
    private static volatile Map<String, Integer> biomeIds = Map.of();

    private FabricRegistries() {
    }

    /** Numbers the registries of a server that has just started. */
    static void install(MinecraftServer server) {
        install(server.registryAccess());
    }

    static void install(RegistryAccess registryAccess) {
        if (registryAccess == null) {
            return;
        }
        List<String> names = registryAccess.lookupOrThrow(Registries.BIOME).keySet().stream()
                .map(ResourceLocation::toString)
                .sorted()
                .toList();
        Map<String, Integer> ids = new HashMap<>(names.size() * 2);
        for (int id = 0; id < names.size(); id++) {
            ids.put(names.get(id), id);
        }
        biomeNames = names;
        biomeIds = ids;
        access = registryAccess;
    }

    static void clear() {
        access = null;
        biomeNames = List.of();
        biomeIds = Map.of();
    }

    static RegistryAccess access() {
        return access;
    }

    static boolean isEmpty() {
        return access == null;
    }

    static List<String> biomeNames() {
        return biomeNames;
    }

    /** The name of a biome id, or {@code null} when the id is not one of ours. */
    static String biomeName(int id) {
        List<String> names = biomeNames;
        return id < 0 || id >= names.size() ? null : names.get(id);
    }

    static int biomeId(String name) {
        Integer id = biomeIds.get(name);
        return id == null ? -1 : id;
    }

    static ResourceKey<Biome> biomeKey(int id) {
        String name = biomeName(id);
        ResourceLocation location = name == null ? null : ResourceLocation.tryParse(name);
        return location == null ? null : ResourceKey.create(Registries.BIOME, location);
    }
}
