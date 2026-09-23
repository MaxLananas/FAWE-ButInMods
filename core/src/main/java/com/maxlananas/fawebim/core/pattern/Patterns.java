package com.maxlananas.fawebim.core.pattern;

import com.maxlananas.fawebim.core.expression.Expression;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.util.RandomCollection;
import com.maxlananas.fawebim.core.util.noise.Noise;
import com.maxlananas.fawebim.core.world.BlockState;
import com.maxlananas.fawebim.core.world.BlockStateRegistry;
import com.maxlananas.fawebim.core.world.Extent;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** All pattern implementations FAWE exposes ({@code //set <pattern>}). */
public final class Patterns {

    private Patterns() {
    }

    /** A single fixed state. */
    public static final class Single implements Pattern {

        private final int stateId;

        public Single(int stateId) {
            this.stateId = stateId;
        }

        public int stateId() {
            return stateId;
        }

        @Override
        public int apply(int x, int y, int z) {
            return stateId;
        }
    }

    /** {@code 25%stone,75%dirt} — weighted random choice. */
    public static final class Weighted implements Pattern {

        private final RandomCollection<Pattern> children = new RandomCollection<>();
        private final Random random = new Random();

        public void add(double weight, Pattern pattern) {
            children.add(weight, pattern);
        }

        public boolean isEmpty() {
            return children.isEmpty();
        }

        @Override
        public int apply(int x, int y, int z) {
            Pattern chosen = children.next(random);
            return chosen == null ? 0 : chosen.apply(x, y, z);
        }

        @Override
        public boolean isDeterministic() {
            return false;
        }
    }

    /** {@code #clipboard} — places blocks from the session clipboard. */
    public static final class ClipboardPattern implements Pattern {

        private final Extent clipboard;
        private final BlockVector3 origin;
        private final boolean fullCopy;
        private final boolean randomRotation;
        private final Random random = new Random();
        /** Cached per-apply rotation, refreshed for every rotation step. */
        private int rotation;

        public ClipboardPattern(Extent clipboard, BlockVector3 origin, boolean fullCopy, boolean randomRotation) {
            this.clipboard = clipboard;
            this.origin = origin;
            this.fullCopy = fullCopy;
            this.randomRotation = randomRotation;
        }

        @Override
        public int apply(int x, int y, int z) {
            int px = x - origin.x();
            int pz = z - origin.z();
            if (randomRotation && (px == 0 && pz == 0)) {
                // The rotation is picked once per block column, so a tower of
                // blocks coming from one clipboard cell stays coherent.
                rotation = random.nextInt(4);
            }
            if (randomRotation && rotation != 0) {
                int w = Math.max(1, clipboardWidth());
                int l = Math.max(1, clipboardLength());
                int wrappedX = Math.floorMod(x, w);
                int wrappedZ = Math.floorMod(z, l);
                switch (rotation) {
                    case 1 -> {
                        x = origin.x() + wrappedZ;
                        z = origin.z() + (w - 1 - wrappedX);
                    }
                    case 2 -> {
                        x = origin.x() + (w - 1 - wrappedX);
                        z = origin.z() + (l - 1 - wrappedZ);
                    }
                    case 3 -> {
                        x = origin.x() + (l - 1 - wrappedZ);
                        z = origin.z() + wrappedX;
                    }
                    default -> {
                        x = origin.x() + wrappedX;
                        z = origin.z() + wrappedZ;
                    }
                }
                x -= origin.x();
                z -= origin.z();
            }
            if (fullCopy) {
                // Wrap into the clipboard bounds, FAWE's "#fullcopy" behaviour.
                int w = Math.max(1, clipboardWidth());
                int h = Math.max(1, clipboardHeight());
                int l = Math.max(1, clipboardLength());
                x = Math.floorMod(x, w);
                y = Math.floorMod(y, h);
                z = Math.floorMod(z, l);
            }
            return clipboard.getBlock(origin.x() + x, origin.y() + y, origin.z() + z);
        }

        private int clipboardWidth() {
            return clipboard instanceof com.maxlananas.fawebim.core.clipboard.BlockArrayClipboard c ? c.getWidth() : 1;
        }

        private int clipboardHeight() {
            return clipboard instanceof com.maxlananas.fawebim.core.clipboard.BlockArrayClipboard c ? c.getHeight() : 1;
        }

        private int clipboardLength() {
            return clipboard instanceof com.maxlananas.fawebim.core.clipboard.BlockArrayClipboard c ? c.getLength() : 1;
        }

        @Override
        public Extent extent() {
            return clipboard;
        }

        @Override
        public boolean isDeterministic() {
            return !randomRotation;
        }
    }

    /** {@code #existing} — keeps whatever block is already there. */
    public static final class Existing implements Pattern {

        private final Extent extent;

        public Existing(Extent extent) {
            this.extent = extent;
        }

        @Override
        public int apply(int x, int y, int z) {
            Extent ext = extent != null ? extent : com.maxlananas.fawebim.core.mask.Masks.ExtentHolder.get();
            return ext == null ? 0 : ext.getBlock(x, y, z);
        }

        @Override
        public Extent extent() {
            return extent;
        }
    }

    /** {@code #biome[<biome>]} — places the biome's surface pattern. */
    public static final class Biome implements Pattern {

        private final int biomeId;
        private final Extent extent;

        public Biome(int biomeId, Extent extent) {
            this.biomeId = biomeId;
            this.extent = extent;
        }

        @Override
        public int apply(int x, int y, int z) {
            Extent ext = extent != null ? extent : com.maxlananas.fawebim.core.mask.Masks.ExtentHolder.get();
            if (ext == null) {
                return 0;
            }
            // FAWE paints the biome's own surface: grass-like biomes get grass,
            // dry ones sand, cold ones snow, everything else keeps the terrain.
            String biome = BlockState.registry().biomeName(biomeId);
            boolean exposed = BlockState.registry().isAirLike(
                    ext.getBlock(x, y + 1, z));
            if (!exposed) {
                return BlockState.registry().defaultState("minecraft:stone");
            }
            if (biome == null) {
                return BlockState.registry().defaultState("minecraft:grass_block");
            }
            if (biome.contains("desert") || biome.contains("beach") || biome.contains("badlands")) {
                return BlockState.registry().defaultState("minecraft:sand");
            }
            if (biome.contains("snow") || biome.contains("frozen") || biome.contains("ice")) {
                return BlockState.registry().defaultState("minecraft:snow_block");
            }
            return BlockState.registry().defaultState("minecraft:grass_block");
        }
    }

    /** {@code #offset[x][y][z]} — shifts the pattern's own coordinates. */
    public static final class Offset implements Pattern {

        private final Pattern delegate;
        private final BlockVector3 offset;

        public Offset(Pattern delegate, BlockVector3 offset) {
            this.delegate = delegate;
            this.offset = offset;
        }

        @Override
        public int apply(int x, int y, int z) {
            return delegate.apply(x + offset.x(), y + offset.y(), z + offset.z());
        }

        @Override
        public BlockVector3 offset() {
            return offset;
        }
    }

    /** {@code #spread} — random horizontal offset per block. */
    public static final class RandomOffset implements Pattern {

        private final Pattern delegate;
        private final int dx;
        private final int dy;
        private final int dz;
        private final boolean solid;
        private final Random random = new Random();

        public RandomOffset(Pattern delegate, int dx, int dy, int dz, boolean solid) {
            this.delegate = delegate;
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
            this.solid = solid;
        }

        @Override
        public int apply(int x, int y, int z) {
            int ox = dx == 0 ? 0 : random.nextInt(dx * 2 + 1) - dx;
            int oy = dy == 0 ? 0 : random.nextInt(dy * 2 + 1) - dy;
            int oz = dz == 0 ? 0 : random.nextInt(dz * 2 + 1) - dz;
            BlockVector3 target = new BlockVector3(x + ox, y + oy, z + oz);
            if (solid) {
                // "#spread" with the solid flag: never carve into the terrain,
                // only fill where the offset landed on an existing block.
                Extent ext = delegate.extent();
                if (ext != null && BlockState.registry().isAirLike(
                        ext.getBlock(target.x(), target.y(), target.z()))) {
                    return BlockState.registry().air();
                }
            }
            return delegate.apply(target);
        }

        @Override
        public boolean isDeterministic() {
            return false;
        }
    }

    /** {@code #rel} — coordinates relative to the player/click position. */
    public static final class Relative implements Pattern {

        private final Pattern delegate;
        private final BlockVector3 origin;

        public Relative(Pattern delegate, BlockVector3 origin) {
            this.delegate = delegate;
            this.origin = origin;
        }

        @Override
        public int apply(int x, int y, int z) {
            return delegate.apply(x + origin.x(), y + origin.y(), z + origin.z());
        }
    }

    /** {@code #swaptype} — swaps block types, keeping properties. */
    public static final class TypeSwap implements Pattern {

        @Override
        public int apply(int x, int y, int z) {
            Extent extent = com.maxlananas.fawebim.core.mask.Masks.ExtentHolder.get();
            if (extent == null) {
                return 0;
            }
            int id = extent.getBlock(x, y, z);
            BlockStateRegistry registry = BlockState.registry();
            String name = registry.name(id);
            // Mud <-> dirt, grass <-> mycelium, stone family, wood families.
            String swapped = switch (name) {
                case "minecraft:grass_block" -> "minecraft:mycelium";
                case "minecraft:mycelium" -> "minecraft:grass_block";
                case "minecraft:dirt" -> "minecraft:coarse_dirt";
                case "minecraft:stone" -> "minecraft:cobblestone";
                case "minecraft:cobblestone" -> "minecraft:stone";
                default -> name.replace("_stairs", "").replace("_slab", "");
            };
            int target = registry.defaultState(swapped);
            if (target < 0) {
                return id;
            }
            // Keep matching properties across the swap.
            int result = target;
            for (var entry : registry.properties(id).entrySet()) {
                int updated = registry.withProperty(result, entry.getKey(), entry.getValue());
                if (updated >= 0) {
                    result = updated;
                }
            }
            return result;
        }
    }

    /** {@code #l3d} / {@code #linear} — gradient between two patterns. */
    public static final class Linear implements Pattern {

        private final Pattern from;
        private final Pattern to;
        private final boolean alongX;
        private final boolean alongY;
        private final boolean alongZ;

        public Linear(Pattern from, Pattern to, boolean alongX, boolean alongY, boolean alongZ) {
            this.from = from;
            this.to = to;
            this.alongX = alongX;
            this.alongY = alongY;
            this.alongZ = alongZ;
        }

        @Override
        public int apply(int x, int y, int z) {
            double t = Math.abs((x * (alongX ? 1 : 0)
                    + y * (alongY ? 1 : 0)
                    + z * (alongZ ? 1 : 0)) % 256) / 255.0;
            return t < 0.5 ? from.apply(x, y, z) : to.apply(x, y, z);
        }
    }

    /** {@code =expr} — expression driven pattern. */
    public static final class ExpressionPattern implements Pattern {

        private final Expression expression;
        private final String input;

        public ExpressionPattern(String input) {
            this.input = input;
            this.expression = Expression.compile(input);
        }

        @Override
        public int apply(int x, int y, int z) {
            Expression.Variables vars = new Expression.Variables();
            vars.set("x", x).set("y", y).set("z", z);
            return (int) Math.floor(expression.evaluate(vars));
        }

        @Override
        public String describe() {
            return "=" + input;
        }
    }

    /** {@code ##tag} — pattern that follows the surface of the terrain. */
    public static final class Surface implements Pattern {

        @Override
        public int apply(int x, int y, int z) {
            return 0;
        }
    }

    /** {@code 33%stone,67%dirt} with per-block randomness but stable seeds. */
    public static final class RandomState implements Pattern {

        private final Random random = new Random();
        private final int[] states;

        public RandomState(int[] states) {
            this.states = states;
        }

        public RandomState(List<Integer> states) {
            this(states.stream().mapToInt(Integer::intValue).toArray());
        }

        @Override
        public int apply(int x, int y, int z) {
            return states[random.nextInt(states.length)];
        }

        @Override
        public boolean isDeterministic() {
            return false;
        }
    }

    /** {@code ^} — applies the properties of the neighbouring block. */
    public static final class TypeOrStateApplying implements Pattern {

        private final Pattern delegate;

        public TypeOrStateApplying(Pattern delegate) {
            this.delegate = delegate;
        }

        @Override
        public int apply(int x, int y, int z) {
            Extent extent = com.maxlananas.fawebim.core.mask.Masks.ExtentHolder.get();
            int base = delegate.apply(x, y, z);
            if (extent == null) {
                return base;
            }
            BlockStateRegistry registry = BlockState.registry();
            // Copy matching properties from the block below (stairs' facing...).
            int below = extent.getBlock(x, y - 1, z);
            int result = base;
            for (var entry : registry.properties(below).entrySet()) {
                int updated = registry.withProperty(result, entry.getKey(), entry.getValue());
                if (updated >= 0) {
                    result = updated;
                }
            }
            return result;
        }
    }

    /** {@code #color} style patterns: pick the closest block to a map colour. */
    public static final class Color implements Pattern {

        private final int argb;
        private final List<Integer> palette = new ArrayList<>();

        public Color(int argb, List<Integer> palette) {
            this.argb = argb;
            this.palette.addAll(palette);
        }

        @Override
        public int apply(int x, int y, int z) {
            return closest();
        }

        public int closest() {
            BlockStateRegistry registry = BlockState.registry();
            int best = 0;
            double bestDistance = Double.MAX_VALUE;
            for (int state : palette) {
                double distance = colorDistance(MapColors.colorOf(registry, state), argb);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = state;
                }
            }
            return best;
        }

        private static double colorDistance(int a, int b) {
            double dr = ((a >> 16) & 0xFF) - ((b >> 16) & 0xFF);
            double dg = ((a >> 8) & 0xFF) - ((b >> 8) & 0xFF);
            double db = (a & 0xFF) - (b & 0xFF);
            return dr * dr + dg * dg + db * db;
        }
    }

    /** Blends a base colour with the block below ({@code #darken}, {@code #lighten}, ...). */
    public static final class ColorAdjust implements Pattern {

        public enum Mode { LIGHTEN, DARKEN, SATURATE, DESATURATE }

        private final Extent extent;
        private final Mode mode;
        private final double amount;

        public ColorAdjust(Extent extent, Mode mode, double amount) {
            this.extent = extent;
            this.mode = mode;
            this.amount = amount;
        }

        @Override
        public int apply(int x, int y, int z) {
            Extent ext = extent != null ? extent : com.maxlananas.fawebim.core.mask.Masks.ExtentHolder.get();
            if (ext == null) {
                return 0;
            }
            int current = ext.getBlock(x, y, z);
            BlockStateRegistry registry = BlockState.registry();
            float[] hsb = java.awt.Color.RGBtoHSB(
                    (MapColors.colorOf(registry, current) >> 16) & 0xFF,
                    (MapColors.colorOf(registry, current) >> 8) & 0xFF,
                    MapColors.colorOf(registry, current) & 0xFF, null);
            switch (mode) {
                case LIGHTEN -> hsb[2] = (float) Math.min(1, hsb[2] + amount);
                case DARKEN -> hsb[2] = (float) Math.max(0, hsb[2] - amount);
                case SATURATE -> hsb[1] = (float) Math.min(1, hsb[1] + amount);
                case DESATURATE -> hsb[1] = (float) Math.max(0, hsb[1] - amount);
                default -> {
                }
            }
            int rgb = java.awt.Color.HSBtoRGB(hsb[0], hsb[1], hsb[2]) & 0xFFFFFF;
            return new Color(rgb, MapColors.palette(registry)).closest();
        }
    }

    /** Noise driven pattern used by {@code #simplex}/{@code #perlin}. */
    public static final class NoisePattern implements Pattern {

        private final Noise noise;
        private final double scale;
        private final Pattern low;
        private final Pattern high;

        public NoisePattern(Noise noise, double scale, Pattern low, Pattern high) {
            this.noise = noise;
            this.scale = scale;
            this.low = low;
            this.high = high;
        }

        @Override
        public int apply(int x, int y, int z) {
            double value = noise.noise(x * scale, y * scale, z * scale);
            return value > 0 ? high.apply(x, y, z) : low.apply(x, y, z);
        }
    }
}
