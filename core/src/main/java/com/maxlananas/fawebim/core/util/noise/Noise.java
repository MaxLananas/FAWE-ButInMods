package com.maxlananas.fawebim.core.util.noise;

import java.util.Random;

/**
 * Noise generators behind FAWE's {@code #simplex}, {@code #perlin},
 * {@code #voronoi} and {@code #rmf} masks/patterns and {@code /generate}.
 * Seeded per-instance so that reproductions are stable.
 */
public abstract class Noise {

    protected final int seed;

    protected Noise(long seed) {
        this.seed = (int) (seed ^ (seed >>> 32));
    }

    public abstract double noise(double x, double y, double z);

    public double noise(double x, double z) {
        return noise(x, 0, z);
    }

    public int intNoise(double x, double y, double z) {
        double value = noise(x, y, z);
        return (int) Math.floor(value * 0x1000000);
    }

    /** Classic Perlin noise. */
    public static final class Perlin extends Noise {

        private final int[] perm = new int[512];

        public Perlin(long seed) {
            super(seed);
            Random random = new Random(seed);
            int[] p = new int[256];
            for (int i = 0; i < 256; i++) {
                p[i] = i;
            }
            for (int i = 255; i > 0; i--) {
                int j = random.nextInt(i + 1);
                int tmp = p[i];
                p[i] = p[j];
                p[j] = tmp;
            }
            for (int i = 0; i < 512; i++) {
                perm[i] = p[i & 255];
            }
        }

        private static double fade(double t) {
            return t * t * t * (t * (t * 6 - 15) + 10);
        }

        private static double lerp(double t, double a, double b) {
            return a + t * (b - a);
        }

        private double grad(int hash, double x, double y, double z) {
            int h = hash & 15;
            double u = h < 8 ? x : y;
            double v = h < 4 ? y : (h == 12 || h == 14 ? x : z);
            return ((h & 1) == 0 ? u : -u) + ((h & 2) == 0 ? v : -v);
        }

        @Override
        public double noise(double x, double y, double z) {
            int xi = (int) Math.floor(x) & 255;
            int yi = (int) Math.floor(y) & 255;
            int zi = (int) Math.floor(z) & 255;
            x -= Math.floor(x);
            y -= Math.floor(y);
            z -= Math.floor(z);
            double u = fade(x);
            double v = fade(y);
            double w = fade(z);
            int a = perm[xi] + yi;
            int aa = perm[a] + zi;
            int ab = perm[a + 1] + zi;
            int b = perm[xi + 1] + yi;
            int ba = perm[b] + zi;
            int bb = perm[b + 1] + zi;
            return lerp(w,
                    lerp(v, lerp(u, grad(perm[aa], x, y, z), grad(perm[ba], x - 1, y, z)),
                            lerp(u, grad(perm[ab], x, y - 1, z), grad(perm[bb], x - 1, y - 1, z))),
                    lerp(v, lerp(u, grad(perm[aa + 1], x, y, z - 1), grad(perm[ba + 1], x - 1, y, z - 1)),
                            lerp(u, grad(perm[ab + 1], x, y - 1, z - 1), grad(perm[bb + 1], x - 1, y - 1, z - 1))));
        }
    }

    /** Simplex noise (2D/3D), the default for FAWE's terrain generators. */
    public static final class Simplex extends Noise {

        private static final int[][] GRAD3 = {
                {1, 1, 0}, {-1, 1, 0}, {1, -1, 0}, {-1, -1, 0},
                {1, 0, 1}, {-1, 0, 1}, {1, 0, -1}, {-1, 0, -1},
                {0, 1, 1}, {0, -1, 1}, {0, 1, -1}, {0, -1, -1}};
        private static final double F2 = 0.5 * (Math.sqrt(3.0) - 1.0);
        private static final double G2 = (3.0 - Math.sqrt(3.0)) / 6.0;
        private static final double F3 = 1.0 / 3.0;
        private static final double G3 = 1.0 / 6.0;

        private final short[] perm = new short[512];
        private final short[] permMod12 = new short[512];

        public Simplex(long seed) {
            super(seed);
            Random random = new Random(seed);
            short[] p = new short[256];
            for (short i = 0; i < 256; i++) {
                p[i] = i;
            }
            for (int i = 255; i > 0; i--) {
                int j = random.nextInt(i + 1);
                short tmp = p[i];
                p[i] = p[j];
                p[j] = tmp;
            }
            for (int i = 0; i < 512; i++) {
                perm[i] = p[i & 255];
                permMod12[i] = (short) (perm[i] % 12);
            }
        }

        private static int fastFloor(double x) {
            int xi = (int) x;
            return x < xi ? xi - 1 : xi;
        }

        @Override
        public double noise(double xin, double yin, double zin) {
            double s = (xin + yin + zin) * F3;
            int i = fastFloor(xin + s);
            int j = fastFloor(yin + s);
            int k = fastFloor(zin + s);
            double t = (i + j + k) * G3;
            double x0 = xin - (i - t);
            double y0 = yin - (j - t);
            double z0 = zin - (k - t);
            int i1;
            int j1;
            int k1;
            int i2;
            int j2;
            int k2;
            if (x0 >= y0) {
                if (y0 >= z0) {
                    i1 = 1; j1 = 0; k1 = 0; i2 = 1; j2 = 1; k2 = 0;
                } else if (x0 >= z0) {
                    i1 = 1; j1 = 0; k1 = 0; i2 = 1; j2 = 0; k2 = 1;
                } else {
                    i1 = 0; j1 = 0; k1 = 1; i2 = 1; j2 = 0; k2 = 1;
                }
            } else {
                if (y0 < z0) {
                    i1 = 0; j1 = 0; k1 = 1; i2 = 0; j2 = 1; k2 = 1;
                } else if (x0 < z0) {
                    i1 = 0; j1 = 1; k1 = 0; i2 = 0; j2 = 1; k2 = 1;
                } else {
                    i1 = 0; j1 = 1; k1 = 0; i2 = 1; j2 = 1; k2 = 0;
                }
            }
            double x1 = x0 - i1 + G3;
            double y1 = y0 - j1 + G3;
            double z1 = z0 - k1 + G3;
            double x2 = x0 - i2 + 2 * G3;
            double y2 = y0 - j2 + 2 * G3;
            double z2 = z0 - k2 + 2 * G3;
            double x3 = x0 - 1 + 3 * G3;
            double y3 = y0 - 1 + 3 * G3;
            double z3 = z0 - 1 + 3 * G3;
            int ii = i & 255;
            int jj = j & 255;
            int kk = k & 255;
            double n0 = contribution(permMod12[ii + perm[jj + perm[kk]]], x0, y0, z0);
            double n1 = contribution(permMod12[ii + i1 + perm[jj + j1 + perm[kk + k1]]], x1, y1, z1);
            double n2 = contribution(permMod12[ii + i2 + perm[jj + j2 + perm[kk + k2]]], x2, y2, z2);
            double n3 = contribution(permMod12[ii + 1 + perm[jj + 1 + perm[kk + 1]]], x3, y3, z3);
            return 32.0 * (n0 + n1 + n2 + n3);
        }

        private static double contribution(int gi, double x, double y, double z) {
            double t = 0.6 - x * x - y * y - z * z;
            if (t < 0) {
                return 0.0;
            }
            t *= t;
            return t * t * (GRAD3[gi][0] * x + GRAD3[gi][1] * y + GRAD3[gi][2] * z);
        }

        @Override
        public double noise(double xin, double yin) {
            double s = (xin + yin) * F2;
            int i = fastFloor(xin + s);
            int j = fastFloor(yin + s);
            double t = (i + j) * G2;
            double x0 = xin - (i - t);
            double y0 = yin - (j - t);
            int i1;
            int j1;
            if (x0 > y0) {
                i1 = 1;
                j1 = 0;
            } else {
                i1 = 0;
                j1 = 1;
            }
            double x1 = x0 - i1 + G2;
            double y1 = y0 - j1 + G2;
            double x2 = x0 - 1 + 2 * G2;
            double y2 = y0 - 1 + 2 * G2;
            int ii = i & 255;
            int jj = j & 255;
            double n0 = contribution2(permMod12[ii + perm[jj]], x0, y0);
            double n1 = contribution2(permMod12[ii + i1 + perm[jj + j1]], x1, y1);
            double n2 = contribution2(permMod12[ii + 1 + perm[jj + 1]], x2, y2);
            return 70.0 * (n0 + n1 + n2);
        }

        private static double contribution2(int gi, double x, double y) {
            double t = 0.5 - x * x - y * y;
            if (t < 0) {
                return 0.0;
            }
            t *= t;
            return t * t * (GRAD3[gi][0] * x + GRAD3[gi][1] * y);
        }
    }

    /** Worley/voronoi noise, used by {@code #voronoi} and rock/flora generation. */
    public static final class Voronoi extends Noise {

        public Voronoi(long seed) {
            super(seed);
        }

        @Override
        public double noise(double x, double y, double z) {
            int xi = (int) Math.floor(x);
            int yi = (int) Math.floor(y);
            int zi = (int) Math.floor(z);
            double min = Double.MAX_VALUE;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        double cx = xi + dx + hash(xi + dx, yi + dy, zi + dz);
                        double cy = yi + dy + hash(xi + dx + 31, yi + dy + 17, zi + dz + 7);
                        double cz = zi + dz + hash(xi + dx + 13, yi + dy + 5, zi + dz + 29);
                        double d = (cx - x) * (cx - x) + (cy - y) * (cy - y) + (cz - z) * (cz - z);
                        min = Math.min(min, d);
                    }
                }
            }
            return Math.sqrt(min);
        }

        private double hash(int x, int y, int z) {
            int h = seed ^ (x * 374761393) ^ (y * 668265263) ^ (z * 2147483647);
            h = (h ^ (h >>> 13)) * 1274126177;
            return ((h ^ (h >>> 16)) & 0xFFFFFF) / (double) 0xFFFFFF;
        }
    }

    /** Ridged multi-fractal noise ({@code #rmf}). */
    public static final class RidgedMultiFractal extends Noise {

        private final Simplex base;
        private final int octaves;
        private final double lacunarity;
        private final double gain;

        public RidgedMultiFractal(long seed, int octaves, double lacunarity, double gain) {
            super(seed);
            this.base = new Simplex(seed);
            this.octaves = octaves;
            this.lacunarity = lacunarity;
            this.gain = gain;
        }

        @Override
        public double noise(double x, double y, double z) {
            double sum = 0;
            double frequency = 1;
            double amplitude = 1;
            double weight = 1;
            for (int i = 0; i < octaves; i++) {
                double signal = base.noise(x * frequency, y * frequency, z * frequency);
                signal = Math.abs(signal);
                signal = 1.0 - signal;
                signal *= signal * weight;
                weight = Math.min(1, Math.max(0, signal * 2));
                sum += signal * amplitude;
                frequency *= lacunarity;
                amplitude *= gain;
            }
            return sum;
        }
    }

    /** Random noise (white noise), the default for {@code #noise} style masks. */
    public static final class RandomNoise extends Noise {

        private final Random random = new Random();

        public RandomNoise(long seed) {
            super(seed);
            random.setSeed(seed);
        }

        @Override
        public double noise(double x, double y, double z) {
            return random.nextDouble();
        }
    }
}
