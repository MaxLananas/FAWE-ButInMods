package com.maxlananas.fawebim.core.world;

/**
 * Options for {@code //regen}: whether to keep the seed, biomes, entities and
 * the {@code -s} "seed only" behaviour of FAWE.
 */
public final class RegenOptions {

    private boolean regenBiomes;
    private boolean keepEntities;
    private boolean useSeed;
    private long seed;
    private boolean noStructures;

    public boolean shouldRegenBiomes() {
        return regenBiomes;
    }

    public RegenOptions setRegenBiomes(boolean regenBiomes) {
        this.regenBiomes = regenBiomes;
        return this;
    }

    public boolean shouldKeepEntities() {
        return keepEntities;
    }

    public RegenOptions setKeepEntities(boolean keepEntities) {
        this.keepEntities = keepEntities;
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

    public boolean shouldGenerateStructures() {
        return !noStructures;
    }

    public RegenOptions setGenerateStructures(boolean generate) {
        this.noStructures = !generate;
        return this;
    }
}
