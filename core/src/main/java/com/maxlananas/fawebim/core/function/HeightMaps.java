package com.maxlananas.fawebim.core.function;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.maxlananas.fawebim.core.extent.EditSession;
import com.maxlananas.fawebim.core.mask.Mask;
import com.maxlananas.fawebim.core.region.Region;
import com.maxlananas.fawebim.core.world.BlockState;
import com.maxlananas.fawebim.core.world.BlockStateRegistry;
import com.maxlananas.fawebim.core.world.World;

/**
 * The height map convolution WorldEdit uses to smooth terrain, ported from
 * {@code HeightMap} and {@code SnowHeightMap} together with their filter.
 *
 * <p>Both variants build a map of the surface height of every column, blur it
 * with a Gaussian kernel, then stretch or shrink each column between the floor
 * of the region and its new height so the layers of a hill stay in proportion.
 * The snow variant tracks the snow layer on top of each column as a fraction of
 * a block, which is what lets it smooth drifted snow instead of flattening it.
 */
public final class HeightMaps {

    private static final Pattern SNOW_LAYERS = Pattern.compile("layers=(\\d+)");

    /** The blur radius {@code //snowsmooth} uses; the brush form blurs wider. */
    private static final int SNOW_KERNEL_RADIUS = 5;

    private HeightMaps() {
    }

    /**
     * Smooths the terrain of a region.
     *
     * @param mask which blocks count as terrain, or {@code null} for any solid one
     * @return the number of blocks that changed
     */
    public static int smooth(World world, EditSession session, Region region, int iterations, Mask mask) {
        int width = region.getWidth();
        int length = region.getLength();
        int minY = region.getMinimumPoint().y();
        int maxY = region.getMaximumPoint().y();

        int[] heights = new int[width * length];
        for (int z = 0; z < length; z++) {
            for (int x = 0; x < width; x++) {
                heights[z * width + x] = highestTerrain(world, mask, region.getMinimumPoint().x() + x,
                        region.getMinimumPoint().z() + z, minY, maxY);
            }
        }

        float[] smoothed = toFloats(heights);
        float[] kernel = gaussianKernel(5, 1.0);
        for (int iteration = 0; iteration < iterations; iteration++) {
            smoothed = filter(smoothed, width, length, kernel, 0.5f);
        }
        return apply(world, session, region, heights, smoothed);
    }

    /**
     * Smooths the snow of a region, keeping the snow layer on top of every column
     * proportional to its neighbours.
     *
     * @param layerBlocks how many full snow blocks to place under the layer
     * @param mask        which blocks count as terrain, or {@code null} for any solid one
     * @return the number of blocks that changed
     */
    public static int snowSmooth(World world, EditSession session, Region region, int iterations, int layerBlocks,
                                 Mask mask) {
        return snowSmooth(world, session, region, iterations, layerBlocks, mask, SNOW_KERNEL_RADIUS);
    }

    /**
     * @param kernelRadius the diameter of the blur; FAWE blurs the snow of a brush
     *                     wider than the snow of a selection to smooth a whole
     *                     drift in one click
     */
    public static int snowSmooth(World world, EditSession session, Region region, int iterations, int layerBlocks,
                                 Mask mask, int kernelRadius) {
        BlockStateRegistry registry = BlockState.registry();
        int width = region.getWidth();
        int length = region.getLength();
        int minY = region.getMinimumPoint().y();
        int maxY = region.getMaximumPoint().y();
        int minX = region.getMinimumPoint().x();
        int minZ = region.getMinimumPoint().z();

        float[] heights = new float[width * length];
        for (int z = 0; z < length; z++) {
            for (int x = 0; x < width; x++) {
                int top = highestTerrain(world, mask, minX + x, minZ + z, minY, maxY);
                int above = world.getBlock(minX + x, top + 1, minZ + z);
                int layers = snowLayers(registry, above);
                if (layers > 0) {
                    // A snow layer counts as a fraction of a block, one eighth each.
                    heights[z * width + x] = top + 1 + (layers - 1) / 8f;
                } else if (registry.isAirLike(world.getBlock(minX + x, top, minZ + z))) {
                    heights[z * width + x] = top;
                } else {
                    heights[z * width + x] = top + 1;
                }
            }
        }

        float[] smoothed = heights.clone();
        float[] kernel = gaussianKernel(kernelRadius, 1.0);
        for (int iteration = 0; iteration < iterations; iteration++) {
            // The half layer offset keeps the layer count of a flat field stable.
            smoothed = filter(smoothed, width, length, kernel, 0.0625f);
        }
        return applySnow(world, session, region, heights, smoothed, layerBlocks);
    }

    /** The highest block of a column the mask accepts, or the floor of the window. */
    public static int highestTerrain(World world, Mask mask, int x, int z, int minY, int maxY) {
        BlockStateRegistry registry = BlockState.registry();
        for (int y = maxY; y >= minY; y--) {
            int state = world.getBlock(x, y, z);
            if (mask == null ? !registry.isAirLike(state) && !registry.isLiquid(state) : mask.test(x, y, z)) {
                return y;
            }
        }
        return minY;
    }

    /** The number of layers of a snow block, or zero when the block is not snow. */
    public static int snowLayers(BlockStateRegistry registry, int state) {
        String description = registry.describe(state);
        if (!description.startsWith("minecraft:snow[")) {
            return 0;
        }
        Matcher matcher = SNOW_LAYERS.matcher(description);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 1;
    }

    /** A normalised 2D bell curve, the kernel WorldEdit blurs height maps with. */
    public static float[] gaussianKernel(int radius, double sigma) {
        int diameter = radius * 2 + 1;
        float[] data = new float[diameter * diameter];
        double twoSigmaSquared = 2 * sigma * sigma;
        double constant = Math.PI * twoSigmaSquared;
        float sum = 0;
        for (int y = -radius; y <= radius; y++) {
            for (int x = -radius; x <= radius; x++) {
                float value = (float) (Math.exp(-(x * x + y * y) / twoSigmaSquared) / constant);
                data[(y + radius) * diameter + x + radius] = value;
                sum += value;
            }
        }
        for (int i = 0; i < data.length; i++) {
            data[i] /= sum;
        }
        return data;
    }

    /**
     * Blurs a height map with a kernel. Coordinates outside the map are clamped to
     * the border row or column, which is how WorldEdit treats the edges.
     */
    public static float[] filter(float[] input, int width, int height, float[] kernel, float offset) {
        int radius = (int) Math.sqrt(kernel.length) / 2;
        int diameter = radius * 2 + 1;
        float[] output = new float[input.length];
        int index = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float total = 0;
                for (int ky = -radius; ky <= radius; ky++) {
                    int offsetY = y + ky;
                    if (offsetY < 0 || offsetY >= height) {
                        offsetY = y;
                    }
                    for (int kx = -radius; kx <= radius; kx++) {
                        float weight = kernel[(ky + radius) * diameter + kx + radius];
                        if (weight == 0) {
                            continue;
                        }
                        int offsetX = x + kx;
                        if (offsetX < 0 || offsetX >= width) {
                            offsetX = x;
                        }
                        total += weight * input[offsetY * width + offsetX];
                    }
                }
                output[index++] = total + offset;
            }
        }
        return output;
    }

    /** Stretches or shrinks every column of a region to its new height. */
    private static int apply(World world, EditSession session, Region region, int[] original, float[] smoothed) {
        BlockStateRegistry registry = BlockState.registry();
        int width = region.getWidth();
        int length = region.getLength();
        int minX = region.getMinimumPoint().x();
        int minY = region.getMinimumPoint().y();
        int minZ = region.getMinimumPoint().z();
        int maxY = region.getMaximumPoint().y();
        int air = registry.air();
        int changed = 0;

        for (int z = 0; z < length; z++) {
            for (int x = 0; x < width; x++) {
                int index = z * width + x;
                int currentHeight = original[index];
                int newHeight = Math.min(maxY, (int) smoothed[index]);
                if (newHeight == currentHeight) {
                    continue;
                }
                int xr = minX + x;
                int zr = minZ + z;
                double scale = (double) (currentHeight - minY) / (double) (newHeight - minY);

                if (newHeight > currentHeight) {
                    int top = session.getBlock(xr, currentHeight, zr);
                    if (registry.isLiquid(top)) {
                        // Water and lava are left alone, as upstream leaves them.
                        continue;
                    }
                    if (session.setBlock(xr, newHeight, zr, top)) {
                        changed++;
                    }
                    for (int y = newHeight - 1 - minY; y >= 0; y--) {
                        int copyFrom = (int) Math.floor(y * scale);
                        if (session.setBlock(xr, minY + y, zr, session.getBlock(xr, minY + copyFrom, zr))) {
                            changed++;
                        }
                    }
                } else {
                    for (int y = 0; y < newHeight - minY; y++) {
                        int copyFrom = (int) Math.floor(y * scale);
                        if (session.setBlock(xr, minY + y, zr, session.getBlock(xr, minY + copyFrom, zr))) {
                            changed++;
                        }
                    }
                    if (session.setBlock(xr, newHeight, zr, session.getBlock(xr, currentHeight, zr))) {
                        changed++;
                    }
                    for (int y = newHeight + 1; y <= currentHeight; y++) {
                        if (session.setBlock(xr, y, zr, air)) {
                            changed++;
                        }
                    }
                }
            }
        }
        return changed;
    }

    /** {@link #apply} for the snow variant, which lays snow layers instead of blocks. */
    private static int applySnow(World world, EditSession session, Region region, float[] original, float[] smoothed,
                                 int layerBlocks) {
        BlockStateRegistry registry = BlockState.registry();
        int width = region.getWidth();
        int length = region.getLength();
        int minX = region.getMinimumPoint().x();
        int minY = region.getMinimumPoint().y();
        int minZ = region.getMinimumPoint().z();
        int maxY = region.getMaximumPoint().y();
        int air = registry.air();
        int snowBlock = registry.defaultState("minecraft:snow_block");
        int changed = 0;

        for (int z = 0; z < length; z++) {
            for (int x = 0; x < width; x++) {
                int index = z * width + x;
                float currentHeight = original[index];
                if (currentHeight == minY) {
                    continue;
                }
                float newHeight = Math.min(maxY, smoothed[index]);
                int xr = minX + x;
                int zr = minZ + z;
                double scale = (double) (currentHeight - minY) / (double) (newHeight - minY);

                if (newHeight >= currentHeight) {
                    int floorHeight = (int) Math.floor(currentHeight);
                    if (registry.isLiquid(session.getBlock(xr, floorHeight, zr))) {
                        continue;
                    }
                    if (session.setBlock(xr, (int) Math.floor(newHeight), zr, snowState(registry, newHeight))) {
                        changed++;
                    }
                    for (int y = (int) Math.floor(newHeight - 1 - minY); y >= 0; y--) {
                        int state = y >= (int) Math.floor(newHeight - 1 - minY - layerBlocks)
                                ? snowBlock
                                : session.getBlock(xr, minY + (int) Math.floor(y * scale), zr);
                        if (session.setBlock(xr, minY + y, zr, state)) {
                            changed++;
                        }
                    }
                } else {
                    for (int y = 0; y < (int) Math.floor(newHeight - minY); y++) {
                        int state = y >= (int) Math.floor(newHeight - minY - layerBlocks)
                                ? snowBlock
                                : session.getBlock(xr, minY + (int) Math.floor(y * scale), zr);
                        if (session.setBlock(xr, minY + y, zr, state)) {
                            changed++;
                        }
                    }
                    if (session.setBlock(xr, (int) Math.floor(newHeight), zr, snowState(registry, newHeight))) {
                        changed++;
                    }
                    for (int y = (int) Math.floor(newHeight + 1); y <= (int) Math.floor(currentHeight); y++) {
                        if (session.setBlock(xr, y, zr, air)) {
                            changed++;
                        }
                    }
                }
            }
        }
        return changed;
    }

    /** A snow block holding the layer count a fractional height calls for. */
    private static int snowState(BlockStateRegistry registry, float height) {
        int layers = (int) ((height - (int) Math.floor(height)) * 8) + 1;
        return registry.parse("minecraft:snow[layers=" + layers + "]");
    }

    private static float[] toFloats(int[] values) {
        float[] floats = new float[values.length];
        for (int i = 0; i < values.length; i++) {
            floats[i] = values[i];
        }
        return floats;
    }
}
