package com.maxlananas.fawebim.core.world;

/**
 * Options for {@code //regen}: whether the biomes are regenerated too, and the
 * seed to generate with instead of the world's.
 */
public final class RegenOptions {

    private boolean regenBiomes;
    private boolean useSeed;
    private long seed;

    public boolean shouldRegenBiomes() {
        return regenBiomes;
    }

    public RegenOptions setRegenBiomes(boolean regenBiomes) {
        this.regenBiomes = regenBiomes;
        return this;
    }

    public boolean shouldUseSeed() {
        return useSeed;
    }

    public long getSeed() {
        return seed;
    }

    public RegenOptions setSeed(long seed) {
        this.seed = seed;
        this.useSeed = true;
        return this;
    }
}
