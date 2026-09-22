package com.fawebutinmods.core.world;

import java.util.List;
import java.util.Map;

/**
 * An interned block state: a thin, allocation-cheap wrapper around a registry
 * id. The static {@link #registry()} indirection is what lets the whole engine
 * stay free of platform classes while still talking about real Minecraft
 * blocks.
 */
public final class BlockState {

    private static volatile BlockStateRegistry registry;

    private final int id;

    private BlockState(int id) {
        this.id = id;
    }

    public static void setRegistry(BlockStateRegistry newRegistry) {
        registry = newRegistry;
    }

    public static BlockStateRegistry registry() {
        BlockStateRegistry reg = registry;
        if (reg == null) {
            throw new IllegalStateException("No BlockStateRegistry installed yet");
        }
        return reg;
    }

    public static BlockState of(int id) {
        return new BlockState(id);
    }

    public static BlockState parse(String input) {
        int id = registry().parse(input);
        if (id < 0) {
            throw new IllegalArgumentException("Unknown block: " + input);
        }
        return new BlockState(id);
    }

    public static BlockState air() {
        return new BlockState(registry().air());
    }

    public int id() {
        return id;
    }

    public boolean isAir() {
        return registry().isAir(id);
    }

    public boolean isAirLike() {
        return registry().isAirLike(id);
    }

    public boolean isSolid() {
        return registry().isSolid(id);
    }

    public boolean isLiquid() {
        return registry().isLiquid(id);
    }

    public boolean isFullCube() {
        return registry().isFullCube(id);
    }

    public String name() {
        return registry().name(id);
    }

    public String describe() {
        return registry().describe(id);
    }

    public Map<String, String> properties() {
        return registry().properties(id);
    }

    public Map<String, List<String>> propertyDefs() {
        return registry().propertyDefs(id);
    }

    public String property(String name) {
        return properties().get(name);
    }

    public boolean hasTag(String tag) {
        return registry().hasTag(id, tag);
    }

    public boolean matchesCategory(String category) {
        return registry().matchesCategory(id, category);
    }

    public BlockState withProperty(String property, String value) {
        int newId = registry().withProperty(id, property, value);
        return newId < 0 ? this : new BlockState(newId);
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof BlockState other && other.id == id;
    }

    @Override
    public int hashCode() {
        return id;
    }

    @Override
    public String toString() {
        return describe();
    }
}
