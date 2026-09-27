package com.maxlananas.fawebim.fabric;

import net.minecraft.core.RegistryAccess;
import net.minecraft.data.worldgen.features.AquaticFeatures;
import net.minecraft.data.worldgen.features.CaveFeatures;
import net.minecraft.data.worldgen.features.EndFeatures;
import net.minecraft.data.worldgen.features.NetherFeatures;
import net.minecraft.data.worldgen.features.PileFeatures;
import net.minecraft.data.worldgen.features.TreeFeatures;
import net.minecraft.data.worldgen.features.VegetationFeatures;
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
    private static volatile List<String> featureIds = List.of();
    private static volatile List<String> structureIds = List.of();

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
        java.util.TreeSet<String> features = new java.util.TreeSet<>();
        registryAccess.lookupOrThrow(Registries.CONFIGURED_FEATURE).keySet()
                .forEach(key -> features.add(key.toString()));
        registryAccess.lookupOrThrow(Registries.PLACED_FEATURE).keySet()
                .forEach(key -> features.add(key.toString()));
        featureIds = List.copyOf(features);
        structureIds = registryAccess.lookupOrThrow(Registries.STRUCTURE).keySet().stream()
                .map(ResourceLocation::toString)
                .sorted()
                .toList();
        access = registryAccess;
    }

    static void clear() {
        access = null;
        biomeNames = List.of();
        biomeIds = Map.of();
        featureIds = List.of();
        structureIds = List.of();
    }

    /** The configured and the placed features of the server, sorted. */
    static List<String> featureIds() {
        return featureIds;
    }

    /** The structures of the server, sorted. */
    static List<String> structureIds() {
        return structureIds;
    }

    /**
     * Whether FAWE's feature placer puts a feature against the clicked face:
     * FAWE's list, the game's tree, vegetation, aquatic and pile features and
     * a few that grow in caves, in the End and in the Nether.
     */
    static boolean placesOnFace(String featureId) {
        return FaceFeatures.IDS.contains(featureId);
    }

    /**
     * The features of {@link #placesOnFace}, read once from the keys the game
     * declares for them. The keys are found by their type, which the remapping
     * of the game's names leaves alone, as FAWE finds them.
     */
    private static final class FaceFeatures {

        static final java.util.Set<String> IDS = ids();

        private static java.util.Set<String> ids() {
            java.util.Set<String> ids = new java.util.HashSet<>();
            for (Class<?> holder : new Class<?>[]{AquaticFeatures.class, PileFeatures.class, TreeFeatures.class,
                    VegetationFeatures.class}) {
                for (java.lang.reflect.Field field : holder.getFields()) {
                    int modifiers = field.getModifiers();
                    if (!java.lang.reflect.Modifier.isStatic(modifiers) || field.getType() != ResourceKey.class) {
                        continue;
                    }
                    try {
                        ids.add(((ResourceKey<?>) field.get(null)).location().toString());
                    } catch (IllegalAccessException | RuntimeException skipped) {
                        FaweMod.LOGGER.debug("Feature key {} not read", field, skipped);
                    }
                }
            }
            for (ResourceKey<?> key : List.of(CaveFeatures.DRIPSTONE_CLUSTER, CaveFeatures.LARGE_DRIPSTONE,
                    CaveFeatures.POINTED_DRIPSTONE, CaveFeatures.GLOW_LICHEN, CaveFeatures.CAVE_VINE,
                    CaveFeatures.CAVE_VINE_IN_MOSS, CaveFeatures.MOSS_VEGETATION, CaveFeatures.DRIPLEAF,
                    EndFeatures.CHORUS_PLANT, EndFeatures.END_PLATFORM, NetherFeatures.SMALL_BASALT_COLUMNS,
                    NetherFeatures.LARGE_BASALT_COLUMNS, NetherFeatures.GLOWSTONE_EXTRA)) {
                ids.add(key.location().toString());
            }
            return java.util.Set.copyOf(ids);
        }
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
