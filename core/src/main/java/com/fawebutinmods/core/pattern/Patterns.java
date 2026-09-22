package com.fawebutinmods.core.pattern;

import com.fawebutinmods.core.expression.Expression;
import com.fawebutinmods.core.math.BlockVector3;
import com.fawebutinmods.core.util.RandomCollection;
import com.fawebutinmods.core.util.noise.Noise;
import com.fawebutinmods.core.world.BlockState;
import com.fawebutinmods.core.world.BlockStateRegistry;
import com.fawebutinmods.core.world.Extent;

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
        public int apply(BlockVector3 position) {
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
        public int apply(BlockVector3 position) {
            Pattern chosen = children.next(random);
            return chosen == null ? 0 : chosen.apply(position);
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

        public ClipboardPattern(Extent clipboard, BlockVector3 origin, boolean fullCopy, boolean randomRotation) {
            this.clipboard = clipboard;
            this.origin = origin;
            this.fullCopy = fullCopy;
            this.randomRotation = randomRotation;
        }

        @Override
        public int apply(BlockVector3 position) {
            int x = position.x() - origin.x();
            int y = position.y() - origin.y();
            int z = position.z() - origin.z();
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
            return clipboard instanceof com.fawebutinmods.core.clipboard.BlockArrayClipboard c ? c.getWidth() : 1;
        }

        private int clipboardHeight() {
            return clipboard instanceof com.fawebutinmods.core.clipboard.BlockArrayClipboard c ? c.getHeight() : 1;
        }

        private int clipboardLength() {
            return clipboard instanceof com.fawebutinmods.core.clipboard.BlockArrayClipboard c ? c.getLength() : 1;
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
        public int apply(BlockVector3 position) {
            Extent ext = extent != null ? extent : com.fawebutinmods.core.mask.Masks.ExtentHolder.get();
            return ext == null ? 0 : ext.getBlock(position.x(), position.y(), position.z());
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
        public int apply(BlockVector3 position) {
            Extent ext = extent != null ? extent : com.fawebutinmods.core.mask.Masks.ExtentHolder.get();
            if (ext == null) {
                return 0;
            }
            // Approximate FAWE: biome surface is grass on dirt on stone; pick by depth.
            for (int dy = 1; dy <= 5; dy++) {
                if (!BlockState.registry().isAirLike(ext.getBlock(position.x(), position.y() + dy, position.z()))) {
                    return BlockState.registry().defaultState("minecraft:grass_block");
                }
            }
            return BlockState.registry().defaultState("minecraft:stone");
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
        public int apply(BlockVector3 position) {
            return delegate.apply(position.add(offset));
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
        public int apply(BlockVector3 position) {
            int ox = dx == 0 ? 0 : random.nextInt(dx * 2 + 1) - dx;
            int oy = dy == 0 ? 0 : random.nextInt(dy * 2 + 1) - dy;
            int oz = dz == 0 ? 0 : random.nextInt(dz * 2 + 1) - dz;
            return delegate.apply(position.add(ox, oy, oz));
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
        public int apply(BlockVector3 position) {
            return delegate.apply(position.add(origin));
        }
    }

    /** {@code #swaptype} — swaps block types, keeping properties. */
    public static final class TypeSwap implements Pattern {

        @Override
        public int apply(BlockVector3 position) {
            Extent extent = com.fawebutinmods.core.mask.Masks.ExtentHolder.get();
            if (extent == null) {
                return 0;
            }
            int id = extent.getBlock(position.x(), position.y(), position.z());
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
        public int apply(BlockVector3 position) {
            double t = Math.abs((position.x() * (alongX ? 1 : 0)
                    + position.y() * (alongY ? 1 : 0)
                    + position.z() * (alongZ ? 1 : 0)) % 256) / 255.0;
            return t < 0.5 ? from.apply(position) : to.apply(position);
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
        public int apply(BlockVector3 position) {
            Expression.Variables vars = new Expression.Variables();
            vars.set("x", position.x()).set("y", position.y()).set("z", position.z());
            int value = (int) Math.floor(expression.evaluate(vars));
            return value;
        }
    }

    /** {@code ##tag} — pattern that follows the surface of the terrain. */
    public static final class Surface implements Pattern {

        @Override
        public int apply(BlockVector3 position) {
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
        public int apply(BlockVector3 position) {
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
        public int apply(BlockVector3 position) {
            Extent extent = com.fawebutinmods.core.mask.Masks.ExtentHolder.get();
            int base = delegate.apply(position);
            if (extent == null) {
                return base;
            }
            BlockStateRegistry registry = BlockState.registry();
            // Copy matching properties from the block below (stairs' facing...).
            int below = extent.getBlock(position.x(), position.y() - 1, position.z());
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
        private final Extent extent;
        private final List<Integer> palette = new ArrayList<>();

        public Color(int argb, Extent extent, List<Integer> palette) {
            this.argb = argb;
            this.extent = extent;
            this.palette.addAll(palette);
        }

        @Override
        public int apply(BlockVector3 position) {
            return closest();
        }

        public int closest() {
            Extent ext = extent != null ? extent : com.fawebutinmods.core.mask.Masks.ExtentHolder.get();
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
        public int apply(BlockVector3 position) {
            Extent ext = extent != null ? extent : com.fawebutinmods.core.mask.Masks.ExtentHolder.get();
            if (ext == null) {
                return 0;
            }
            int current = ext.getBlock(position.x(), position.y(), position.z());
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
            return new Color(rgb, ext, MapColors.palette(registry)).closest();
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
        public int apply(BlockVector3 position) {
            double value = noise.noise(position.x() * scale, position.y() * scale, position.z() * scale);
            return value > 0 ? high.apply(position) : low.apply(position);
        }
    }
}
