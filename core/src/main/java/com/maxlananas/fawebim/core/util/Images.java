package com.maxlananas.fawebim.core.util;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

/**
 * Reads the images used by {@code //img} and the image brush.
 *
 * <p>The image brush reads files of the schematics directory; {@code //img}
 * reads the images directory or an address, as FAWE's does. Either way the
 * image is decoded through {@link #decode}, which reads the size in the header
 * first: a small file can declare a picture of a billion pixels, and decoding
 * it outright is the memory of the server. The pixel accessors return
 * {@code 0xAARRGGBB}, which is the layout {@code Patterns.Color} expects.</p>
 */
public final class Images {

    /**
     * The most pixels an image may declare to be decoded at all, before any
     * scaling: 4096 by 4096, 64 MiB of pixels while it is read.
     */
    public static final long MAX_DECODED_PIXELS = 4096L * 4096L;

    private Images() {
    }

    /**
     * Decodes an image, refusing one whose header declares more than
     * {@code maxPixels} pixels before a pixel is allocated.
     *
     * @return the image, or null when no reader of this runtime knows the format
     * @throws IOException when it cannot be read or is too large
     */
    public static BufferedImage decode(java.io.InputStream stream, long maxPixels) throws IOException {
        try (javax.imageio.stream.ImageInputStream input = ImageIO.createImageInputStream(stream)) {
            if (input == null) {
                return null;
            }
            java.util.Iterator<javax.imageio.ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return null;
            }
            javax.imageio.ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                long width = reader.getWidth(0);
                long height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || width * height > maxPixels) {
                    throw new TooLargeException("The image is " + width + "x" + height + ", more than the "
                            + maxPixels + " pixels one may have");
                }
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        }
    }

    /** An image whose header declares more pixels than may be decoded. */
    public static final class TooLargeException extends IOException {

        TooLargeException(String message) {
            super(message);
        }
    }

    /**
     * Loads an image, returning null when it is missing or unreadable.
     *
     * @throws InputException when it declares more pixels than may be decoded
     */
    public static PixelSource load(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return null;
            }
            BufferedImage image;
            try (java.io.InputStream stream = Files.newInputStream(file)) {
                image = decode(stream, MAX_DECODED_PIXELS);
            }
            if (image == null) {
                return null;
            }
            int width = image.getWidth();
            int height = image.getHeight();
            int[] pixels = new int[width * height];
            image.getRGB(0, 0, width, height, pixels, 0, width);
            return new PixelSource(width, height, pixels);
        } catch (TooLargeException e) {
            throw new InputException(e.getMessage());
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Wraps a decoded pixel buffer, {@code 0xRRGGBBAA} per pixel. */
    public static PixelSource of(int width, int height, int[] pixels) {
        return new PixelSource(width, height, pixels);
    }

    /** An indexed pixel buffer with the alpha channel ignored. */
    public static final class PixelSource {

        private final int width;
        private final int height;
        private final int[] pixels;

        PixelSource(int width, int height, int[] pixels) {
            this.width = width;
            this.height = height;
            this.pixels = pixels;
        }

        public int width() {
            return width;
        }

        public int height() {
            return height;
        }

        /** {@code 0xRRGGBB} of a pixel, clamped to the image. */
        public int rgb(int x, int z) {
            int clampedX = Math.max(0, Math.min(width - 1, x));
            int clampedZ = Math.max(0, Math.min(height - 1, z));
            return pixels[clampedZ * width + clampedX] & 0xFFFFFF;
        }

        /** The perceived brightness of a pixel, {@code 0} to {@code 255}. */
        public int luminance(int x, int z) {
            int rgb = rgb(x, z);
            int red = (rgb >> 16) & 0xFF;
            int green = (rgb >> 8) & 0xFF;
            int blue = rgb & 0xFF;
            return (red * 299 + green * 587 + blue * 114) / 1000;
        }

        /** The alpha channel of a pixel, {@code 0} (clear) to {@code 255} (solid). */
        public int opacity(int x, int z) {
            int clampedX = Math.max(0, Math.min(width - 1, x));
            int clampedZ = Math.max(0, Math.min(height - 1, z));
            return pixels[clampedZ * width + clampedX] >>> 24;
        }

        /** True when every pixel is fully transparent, i.e. the tile is empty. */
        public boolean transparent(int x, int z) {
            int clampedX = Math.max(0, Math.min(width - 1, x));
            int clampedZ = Math.max(0, Math.min(height - 1, z));
            return (pixels[clampedZ * width + clampedX] >>> 24) == 0;
        }
    }
}
