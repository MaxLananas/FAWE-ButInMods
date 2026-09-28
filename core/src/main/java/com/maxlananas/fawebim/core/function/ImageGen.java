package com.maxlananas.fawebim.core.function;

import com.maxlananas.fawebim.core.extent.EditSession;
import com.maxlananas.fawebim.core.math.BlockVector3;
import com.maxlananas.fawebim.core.pattern.MapColors;
import com.maxlananas.fawebim.core.util.Images;
import com.maxlananas.fawebim.core.world.BlockState;
import com.maxlananas.fawebim.core.world.BlockStateRegistry;

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
 * core cannot do; the blocks and colours are {@link MapColors}'s, the ones the
 * colour patterns use too. The search is the same: the nearest colour in RGB
 * space, with {@code threshold} as the distance beyond which a pixel is left
 * alone and {@code randomize} spreading the choice over the blocks that are
 * close enough.</p>
 */
public final class ImageGen {

    /** The largest image the command accepts, FAWE's default web image limit. */
    public static final int MAX_IMAGE_SIZE = 500 * 500;

    /** How long an image may take to arrive before the command gives up. */
    private static final int CONNECT_TIMEOUT_MS = 3_000;
    private static final int READ_TIMEOUT_MS = 3_000;
    /** The whole download, which the server thread waits for. */
    private static final long DOWNLOAD_BUDGET_MS = 5_000;
    private static final int MAX_DOWNLOAD_BYTES = 16 * 1024 * 1024;

    private ImageGen() {
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
            // Only the images directory: a path of the server's disk, absolute or
            // climbing out with "..", is not a picture a player may read.
            Path file = com.maxlananas.fawebim.core.util.SafePaths.inside(imagesDirectory, source,
                    com.maxlananas.fawebim.core.platform.Config.get().allowSymlinks, "image");
            if (!Files.isRegularFile(file)) {
                throw new IOException("No image named '" + source + "' in " + imagesDirectory);
            }
            try (InputStream stream = Files.newInputStream(file)) {
                image = Images.decode(stream, Images.MAX_DECODED_PIXELS);
            }
        }
        if (image == null) {
            throw new IOException("'" + source + "' is not an image this runtime can read");
        }
        if (dimensions != null) {
            // Checked before the scaled copy is allocated, not after.
            if ((long) dimensions[0] * dimensions[1] > MAX_IMAGE_SIZE) {
                throw new IOException("A size of " + dimensions[0] + "x" + dimensions[1] + " is larger than the "
                        + MAX_IMAGE_SIZE + " pixels an image may cover");
            }
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

    /**
     * Downloads an image, within a size and a time budget for the whole
     * transfer. The command waits for it on the server thread, and a read
     * timeout alone is per read: a host sending a byte every few seconds held
     * the server for as long as it liked.
     */
    private static BufferedImage readUrl(String url) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "FAWE-BIM");
            long deadline = System.nanoTime() + DOWNLOAD_BUDGET_MS * 1_000_000L;
            java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
            try (InputStream stream = connection.getInputStream()) {
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = stream.read(buffer)) != -1) {
                    body.write(buffer, 0, read);
                    if (body.size() > MAX_DOWNLOAD_BYTES) {
                        throw new IOException("'" + url + "' is larger than " + MAX_DOWNLOAD_BYTES / (1024 * 1024)
                                + " MiB");
                    }
                    if (System.nanoTime() - deadline > 0) {
                        throw new IOException("'" + url + "' took longer than " + DOWNLOAD_BUDGET_MS / 1000
                                + " seconds to download");
                    }
                }
            }
            BufferedImage image = Images.decode(new java.io.ByteArrayInputStream(body.toByteArray()),
                    Images.MAX_DECODED_PIXELS);
            if (image == null) {
                throw new IOException("'" + url + "' did not answer with an image");
            }
            return image;
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
        MapColors.Palette palette = MapColors.palette(registry);
        int[] states = palette.states();
        int[] colors = palette.colors();
        int bestDistance = Integer.MAX_VALUE;
        int bestState = -1;
        List<Integer> close = randomize ? new ArrayList<>() : null;
        for (int i = 0; i < states.length; i++) {
            int distance = MapColors.distance(colors[i], rgb);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestState = states[i];
            }
            if (close != null && distance <= threshold * threshold) {
                close.add(states[i]);
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
}
