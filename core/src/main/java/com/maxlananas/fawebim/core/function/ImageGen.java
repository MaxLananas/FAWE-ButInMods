package com.maxlananas.fawebim.core.function;

import com.maxlananas.fawebim.core.extent.EditSession;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.util.Images;
import com.maxlananas.fawebim.core.world.BlockState;
import com.maxlananas.fawebim.core.world.BlockStateRegistry;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * FAWE's {@code TextureUtil}: it turns an image into blocks by matching every
 * pixel against the block palette.
 *
 * <p>FAWE samples the real block textures of the running game, which a head-less
 * core cannot do, so the palette below is the map colour of every block an image
 * is usually built from. The search is the same: the nearest colour in RGB
 * space, with {@code threshold} as the distance beyond which a pixel is left
 * alone and {@code randomize} spreading the choice over the blocks that are
 * close enough.</p>
 */
public final class ImageGen {

    /** The largest image the command accepts, FAWE's default web image limit. */
    public static final int MAX_IMAGE_SIZE = 500 * 500;

    /** How long an image may take to arrive before the command gives up. */
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    private static final String[] BLOCKS = {
        "minecraft:white_wool", "minecraft:orange_wool", "minecraft:magenta_wool",
        "minecraft:light_blue_wool", "minecraft:yellow_wool", "minecraft:lime_wool",
        "minecraft:pink_wool", "minecraft:gray_wool", "minecraft:light_gray_wool",
        "minecraft:cyan_wool", "minecraft:purple_wool", "minecraft:blue_wool",
        "minecraft:brown_wool", "minecraft:green_wool", "minecraft:red_wool",
        "minecraft:black_wool",
        "minecraft:white_concrete", "minecraft:orange_concrete", "minecraft:magenta_concrete",
        "minecraft:light_blue_concrete", "minecraft:yellow_concrete", "minecraft:lime_concrete",
        "minecraft:pink_concrete", "minecraft:gray_concrete", "minecraft:light_gray_concrete",
        "minecraft:cyan_concrete", "minecraft:purple_concrete", "minecraft:blue_concrete",
        "minecraft:brown_concrete", "minecraft:green_concrete", "minecraft:red_concrete",
        "minecraft:black_concrete",
        "minecraft:terracotta", "minecraft:white_terracotta", "minecraft:orange_terracotta",
        "minecraft:yellow_terracotta", "minecraft:brown_terracotta", "minecraft:red_terracotta",
        "minecraft:stone", "minecraft:cobblestone", "minecraft:andesite", "minecraft:diorite",
        "minecraft:granite", "minecraft:deepslate", "minecraft:blackstone", "minecraft:obsidian",
        "minecraft:dirt", "minecraft:coarse_dirt", "minecraft:grass_block", "minecraft:podzol",
        "minecraft:sand", "minecraft:red_sand", "minecraft:sandstone", "minecraft:gravel",
        "minecraft:oak_planks", "minecraft:spruce_planks", "minecraft:birch_planks",
        "minecraft:dark_oak_planks", "minecraft:oak_log", "minecraft:spruce_log",
        "minecraft:oak_leaves", "minecraft:spruce_leaves", "minecraft:birch_leaves",
        "minecraft:water", "minecraft:lava", "minecraft:ice", "minecraft:snow_block",
        "minecraft:packed_ice", "minecraft:blue_ice", "minecraft:moss_block", "minecraft:clay",
        "minecraft:bricks", "minecraft:netherrack", "minecraft:nether_bricks", "minecraft:soul_sand",
        "minecraft:end_stone", "minecraft:purpur_block", "minecraft:quartz_block",
        "minecraft:iron_block", "minecraft:gold_block", "minecraft:diamond_block",
        "minecraft:emerald_block", "minecraft:redstone_block", "minecraft:lapis_block",
        "minecraft:coal_block", "minecraft:copper_block", "minecraft:amethyst_block",
        "minecraft:glass", "minecraft:bookshelf", "minecraft:hay_block", "minecraft:melon",
        "minecraft:pumpkin", "minecraft:bone_block", "minecraft:sea_lantern", "minecraft:glowstone",
    };

    private static final int[] COLORS = {
        0xE9ECEC, 0xF07613, 0xBD44B3, 0x3AAFD9, 0xF8C627, 0x70B919, 0xED8DAC, 0x3E4447,
        0x8E8E86, 0x158991, 0x792AAC, 0x35399D, 0x724728, 0x546D1B, 0xA12722, 0x141519,
        0xCFD5D6, 0xE06101, 0xA9309F, 0x248FC1, 0xF0AF15, 0x5EA818, 0xD5658E, 0x54585A,
        0x7D7D73, 0x157788, 0x64209C, 0x2D2F8F, 0x603C20, 0x495B24, 0x8E2121, 0x080A0F,
        0x985E43, 0xD2B1A1, 0xA15325, 0xBA8523, 0x4D3223, 0x8E3C2E,
        0x7D7D7D, 0x7A7A7A, 0x8A8A8D, 0xBCBCBC, 0x9A6A4F, 0x646464, 0x2B2926, 0x101019,
        0x976D4D, 0x7F6144, 0x6A7039, 0x5B4C31, 0xDBD3A0, 0xB86A28, 0xD5C98D, 0x847E7C,
        0xB8945F, 0x73553B, 0xC7B584, 0x4A3219, 0x6A5025, 0x4A3A22, 0x4C7B32, 0x3B5B2C,
        0x5E7A45, 0x3F76E4, 0xD45A12, 0x7DADEB, 0xF0FCFC, 0x8EB4E8, 0x74A8F0, 0x59A74A,
        0x9FA3A6, 0x985B45, 0x6B2A2A, 0x2D1717, 0x54402F, 0xDBDEA0, 0xA97BA8, 0xE5E0D8,
        0xD8D8D8, 0xF9EF4E, 0x4AEDD9, 0x2CCB5A, 0xAA0F0F, 0x1C48A0, 0x101010, 0xC0724A,
        0x8A6EC7, 0xFFFFFF, 0x6B4F31, 0xB0A03C, 0x7A9B2E, 0xD18E21, 0xD9D3A1, 0x9BE7E7,
        0xB9A24C,
    };

    private static final List<int[]> PALETTE = new ArrayList<>();

    private ImageGen() {
    }

    private static synchronized List<int[]> palette() {
        if (PALETTE.isEmpty()) {
            BlockStateRegistry registry = BlockState.registry();
            for (int i = 0; i < BLOCKS.length; i++) {
                int state = registry.defaultState(BLOCKS[i]);
                if (state >= 0) {
                    PALETTE.add(new int[]{COLORS[i], state});
                }
            }
        }
        return PALETTE;
    }

    /**
     * Reads an image from a URL or from a file below the images directory, scaled
     * to {@code dimensions} when one was given.
     *
     * @throws IOException when the image cannot be read, with a message that fits
     *                     on a command line
     */
    public static Images.PixelSource load(String source, int[] dimensions, Path imagesDirectory)
            throws IOException {
        BufferedImage image;
        if (source.startsWith("http://") || source.startsWith("https://")) {
            image = readUrl(source);
        } else {
            Path file = Path.of(source);
            if (!Files.isRegularFile(file)) {
                file = imagesDirectory.resolve(source);
            }
            if (!Files.isRegularFile(file)) {
                throw new IOException("No image named '" + source + "' in " + imagesDirectory);
            }
            image = ImageIO.read(file.toFile());
        }
        if (image == null) {
            throw new IOException("'" + source + "' is not an image this runtime can read");
        }
        if (dimensions != null) {
            image = scale(image, dimensions[0], dimensions[1]);
        }
        int width = image.getWidth();
        int height = image.getHeight();
        if ((long) width * height > MAX_IMAGE_SIZE) {
            throw new IOException("Image is " + width + "x" + height + ", larger than the "
                    + MAX_IMAGE_SIZE + " pixels an image may cover; pass a smaller size");
        }
        int[] pixels = new int[width * height];
        image.getRGB(0, 0, width, height, pixels, 0, width);
        return Images.of(width, height, pixels);
    }

    private static BufferedImage readUrl(String url) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "FAWE-BIM");
            try (InputStream stream = connection.getInputStream()) {
                BufferedImage image = ImageIO.read(stream);
                if (image == null) {
                    throw new IOException("'" + url + "' did not answer with an image");
                }
                return image;
            }
        } catch (IllegalArgumentException e) {
            throw new IOException("'" + url + "' is not a valid image address");
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** Bilinear scaling, the interpolation FAWE asks ImageIO for. */
    private static BufferedImage scale(BufferedImage image, int width, int height) {
        if (width <= 0 || height <= 0) {
            return image;
        }
        if (image.getWidth() == width && image.getHeight() == height) {
            return image;
        }
        BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = scaled.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(image, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return scaled;
    }

    /**
     * Writes the image into the world, one column per pixel, from the placement
     * towards {@code +x} and {@code +z}.
     *
     * @param threshold the colour distance a block may be off by, {@code 0} for
     *                  the nearest block whatever its colour
     * @param randomize spread the choice over the blocks that are close enough
     */
    public static int place(EditSession session, Images.PixelSource image,
                            BlockVector3 origin, int threshold, boolean randomize) {
        BlockStateRegistry registry = BlockState.registry();
        Map<Integer, Integer> cache = new HashMap<>();
        Random random = new Random();
        int changed = 0;
        for (int x = 0; x < image.width(); x++) {
            session.checkTimeout();
            for (int z = 0; z < image.height(); z++) {
                if (image.transparent(x, z)) {
                    continue;
                }
                int rgb = image.rgb(x, z);
                int state = cache.computeIfAbsent(rgb,
                        color -> nearest(registry, color, threshold, randomize, random));
                if (state < 0) {
                    continue;
                }
                if (session.setBlock(origin.x() + x, origin.y(), origin.z() + z, state)) {
                    changed++;
                }
            }
        }
        return changed;
    }

    private static int nearest(BlockStateRegistry registry, int rgb, int threshold,
                               boolean randomize, Random random) {
        List<int[]> palette = palette();
        int bestDistance = Integer.MAX_VALUE;
        int bestState = -1;
        List<Integer> close = randomize ? new ArrayList<>() : null;
        for (int[] entry : palette) {
            int distance = distance(entry[0], rgb);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestState = entry[1];
            }
            if (close != null && distance <= threshold * threshold) {
                close.add(entry[1]);
            }
        }
        if (threshold > 0 && bestDistance > threshold * threshold) {
            return -1;
        }
        if (close != null && !close.isEmpty()) {
            return close.get(random.nextInt(close.size()));
        }
        return bestState;
    }

    /** Squared RGB distance, which is enough to pick the nearest block. */
    private static int distance(int first, int second) {
        int red = ((first >> 16) & 0xFF) - ((second >> 16) & 0xFF);
        int green = ((first >> 8) & 0xFF) - ((second >> 8) & 0xFF);
        int blue = (first & 0xFF) - (second & 0xFF);
        return red * red + green * green + blue * blue;
    }
}
