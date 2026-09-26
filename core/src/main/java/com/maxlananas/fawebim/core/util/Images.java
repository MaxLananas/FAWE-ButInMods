package com.maxlananas.fawebim.core.util;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

/**
 * Reads the images used by {@code //img} and the image brush.
 *
 * <p>WorldEdit accepts a file or an URL; the mod only reads files, from the
 * schematics directory, because a server-side mod has no business fetching
 * arbitrary URLs. The pixel accessors return {@code 0xAARRGGBB}, which is the
 * layout {@code Patterns.Color} expects.</p>
 */
public final class Images {

    private Images() {
    }

    /** Loads an image, returning null when it is missing or unreadable. */
    public static PixelSource load(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return null;
            }
            BufferedImage image = ImageIO.read(file.toFile());
            if (image == null) {
                return null;
            }
            int width = image.getWidth();
            int height = image.getHeight();
            int[] pixels = new int[width * height];
            image.getRGB(0, 0, width, height, pixels, 0, width);
            return new PixelSource(width, height, pixels);
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
