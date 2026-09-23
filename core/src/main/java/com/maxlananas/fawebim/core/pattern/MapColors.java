package com.maxlananas.fawebim.core.pattern;

import com.maxlananas.fawebim.core.world.BlockStateRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Block colours used by {@code #color}, {@code #averagecolor} and the image
 * tools. The platform supplies the real map colours; this keeps a cache and a
 * sane fallback palette for registries that cannot provide colours (tests).
 */
public final class MapColors {

    private static final Map<Integer, Integer> CACHE = new ConcurrentHashMap<>();
    private static volatile java.util.function.IntUnaryOperator provider;
    private static volatile List<Integer> cachedPalette;

    private MapColors() {
    }

    /** Installed by the platform adapter so real Minecraft map colours are used. */
    public static void setProvider(java.util.function.IntUnaryOperator colorProvider) {
        provider = colorProvider;
        CACHE.clear();
        cachedPalette = null;
    }

    public static int colorOf(BlockStateRegistry registry, int stateId) {
        java.util.function.IntUnaryOperator op = provider;
        if (op == null) {
            return fallbackColor(registry.name(stateId));
        }
        return CACHE.computeIfAbsent(stateId, op::applyAsInt);
    }

    /** Every block state that has a distinct colour, used for nearest-colour lookups. */
    public static List<Integer> palette(BlockStateRegistry registry) {
        List<Integer> palette = cachedPalette;
        if (palette != null) {
            return palette;
        }
        palette = new ArrayList<>();
        int count = registry.stateCount();
        for (int id = 0; id < count; id++) {
            if (registry.isAirLike(id)) {
                continue;
            }
            if (registry.isFullCube(id)) {
                palette.add(id);
            }
        }
        cachedPalette = palette;
        return palette;
    }

    private static int fallbackColor(String name) {
        int hash = name.hashCode();
        return ((hash & 0xFF) << 16) | ((hash >> 8 & 0xFF) << 8) | ((hash >> 16) & 0xFF);
    }
}
