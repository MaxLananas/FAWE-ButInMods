//! Noise generators behind FAWE's `#simplex`, `#perlin`, `#voronoi` and `#rmf`
//! masks/patterns and the `//generate` brush — a port of `Noise.java` from the
//! Java reference, worked out against the same algorithms so that a seed gives
//! the same field everywhere.
//!
//! Seeded per instance so that reproductions are stable.

/// Java's `java.util.Random`, ported so that a Java seed rebuilds the same
/// permutation tables: a 48-bit LCG (multiplier `0x5DEECE66D`, increment
/// `0xB16`). Only what the noise generators need is implemented.
pub struct JavaRandom {
    seed: u64,
}

impl JavaRandom {
    /// `new Random(seed)` in Java.
    pub const fn new(seed: i64) -> Self {
        Self {
            seed: (seed as u64 ^ 0x5DEE_CE66_D) & ((1 << 48) - 1),
        }
    }

    fn next_raw(&mut self, bits: u32) -> u64 {
        self.seed = self
            .seed
            .wrapping_mul(0x5DEE_CE66_D)
            .wrapping_add(0xB16)
            & ((1 << 48) - 1);
        self.seed >> (48 - bits)
    }

    /// `nextInt()` in Java.
    pub fn next_int(&mut self) -> i32 {
        self.next_raw(32) as u32 as i32
    }

    /// `nextInt(bound)` in Java, including the power-of-two shortcut and the
    /// rejection loop for other bounds.
    pub fn next_int_below(&mut self, bound: i32) -> i32 {
        if bound <= 0 {
            panic!("bound must be positive");
        }
        if bound.count_ones() == 1 {
            return ((bound as i64 * self.next_raw(31) as i64) >> 31) as i32;
        }
        loop {
            let bits = self.next_raw(31) as i32;
            let value = bits % bound;
            // Rejection sampling with Java's exact int arithmetic: the sum
            // wraps into the sign bit exactly when the draw is too large.
            if ((bits as u32)
                .wrapping_sub(value as u32)
                .wrapping_add(bound as u32 - 1) as i32)
                >= 0
            {
                return value;
            }
        }
    }

    /// `nextDouble()` in Java.
    pub fn next_double(&mut self) -> f64 {
        ((self.next_raw(26) << 27) as u64 + self.next_raw(27)) as f64 / (1u64 << 53) as f64
    }
}

/// Java's `Math.signum(double)`: +/-1 for non-zero values, exactly 0 for +/-0.
pub fn java_signum(value: f64) -> f64 {
    if value > 0.0 {
        1.0
    } else if value < 0.0 {
        -1.0
    } else {
        // Java keeps the sign of zero out of the result: signum(-0.0) == 0.0.
        0.0
    }
}

/// Java's `Math.round(double)` as a double: `floor(x + 0.5)` with the Java
/// edge cases (NaN reads as 0, infinities clamp to Java's long range).
pub fn java_round(value: f64) -> f64 {
    if value.is_nan() {
        return 0.0;
    }
    if value >= 9.2233720368547758e18 {
        return 9.2233720368547758e18;
    }
    if value < -9.2233720368547758e18 {
        return -9.2233720368547758e18;
    }
    (value + 0.5).floor()
}

/// Clamps to `[0, 1)` the way the Java reference does, keeping 1 out of the
/// last weighted entry.
pub fn clamp01(value: f64) -> f64 {
    if value < 0.0 {
        0.0
    } else if value >= 1.0 {
        0.9999999999999999
    } else {
        value
    }
}

/// A noise function of three coordinates, the common shape of the Java
/// reference.
pub trait NoiseFn {
    fn noise(&self, x: f64, y: f64, z: f64) -> f64;

    /// The noise as a value in `[0, 1)`, which is the range WorldEdit's
    /// generators work in.
    fn unit(&self, x: f64, y: f64, z: f64) -> f64 {
        clamp01(self.noise(x, y, z) * 0.5 + 0.5)
    }

    /// The two-argument form, read as `(x, 0, z)`.
    fn noise2(&self, x: f64, z: f64) -> f64 {
        self.noise(x, 0.0, z)
    }

    /// The first byte-plane of the noise, as Java's `intNoise` gives it.
    fn int_noise(&self, x: f64, y: f64, z: f64) -> i32 {
        (self.noise(x, y, z) * 0x1_0000_00 as f64).floor() as i32
    }
}

fn fade(t: f64) -> f64 {
    t * t * t * (t * (t * 6.0 - 15.0) + 10.0)
}

fn lerp(t: f64, a: f64, b: f64) -> f64 {
    a + t * (b - a)
}

/// The value the seed folds down to in Java (`(int) (seed ^ (seed >>> 32))`).
pub fn folded_seed(seed: i64) -> i32 {
    (seed as i32) ^ (((seed as u64) >> 32) as i32)
}

/// Classic Perlin noise, ported from `Noise.Perlin`.
pub struct Perlin {
    perm: [i32; 512],
}

impl Perlin {
    /// `new Perlin(seed)`: the permutation table shuffled by Java's `Random`.
    pub fn new(seed: i64) -> Self {
        let mut random = JavaRandom::new(seed);
        let mut p = [0i32; 256];
        for (i, entry) in p.iter_mut().enumerate() {
            *entry = i as i32;
        }
        for i in (1..256).rev() {
            let j = random.next_int_below(i as i32 + 1);
            p.swap(i, j as usize);
        }
        let mut perm = [0i32; 512];
        for i in 0..512 {
            perm[i] = p[i & 255];
        }
        Self { perm }
    }

    fn grad(hash: i32, x: f64, y: f64, z: f64) -> f64 {
        let h = hash & 15;
        let u = if h < 8 { x } else { y };
        let v = if h < 4 {
            y
        } else if h == 12 || h == 14 {
            x
        } else {
            z
        };
        let u = if (h & 1) == 0 { u } else { -u };
        let v = if (h & 2) == 0 { v } else { -v };
        u + v
    }

    #[cfg(test)]
    fn perm(&self) -> &[i32; 512] {
        &self.perm
    }
}

impl NoiseFn for Perlin {
    fn noise(&self, x: f64, y: f64, z: f64) -> f64 {
        let xi = x.floor() as i32 & 255;
        let yi = y.floor() as i32 & 255;
        let zi = z.floor() as i32 & 255;
        let x = x - x.floor();
        let y = y - y.floor();
        let z = z - z.floor();
        let u = fade(x);
        let v = fade(y);
        let w = fade(z);
        let a = self.perm[xi as usize] + yi;
        let aa = self.perm[a as usize] + zi;
        let ab = self.perm[(a + 1) as usize] + zi;
        let b = self.perm[(xi + 1) as usize] + yi;
        let ba = self.perm[b as usize] + zi;
        let bb = self.perm[(b + 1) as usize] + zi;
        lerp(
            w,
            lerp(
                v,
                lerp(u, Self::grad(self.perm[aa as usize], x, y, z), Self::grad(self.perm[ba as usize], x - 1.0, y, z)),
                lerp(u, Self::grad(self.perm[ab as usize], x, y - 1.0, z), Self::grad(self.perm[bb as usize], x - 1.0, y - 1.0, z)),
            ),
            lerp(
                v,
                lerp(u, Self::grad(self.perm[(aa + 1) as usize], x, y, z - 1.0), Self::grad(self.perm[(ba + 1) as usize], x - 1.0, y, z - 1.0)),
                lerp(u, Self::grad(self.perm[(ab + 1) as usize], x, y - 1.0, z - 1.0), Self::grad(self.perm[(bb + 1) as usize], x - 1.0, y - 1.0, z - 1.0)),
            ),
        )
    }
}

/// Simplex (gradient) noise, ported from `Noise.Simplex`.
pub struct Simplex {
    perm: [i16; 512],
    perm_mod12: [i16; 512],
}

const GRAD3: [[i32; 3]; 12] = [
    [1, 1, 0], [-1, 1, 0], [1, -1, 0], [-1, -1, 0],
    [1, 0, 1], [-1, 0, 1], [1, 0, -1], [-1, 0, -1],
    [0, 1, 1], [0, -1, 1], [0, 1, -1], [0, -1, -1],
];
const F2: f64 = 0.5 * (1.7320508075688772 - 1.0);
const G2: f64 = (3.0 - 1.7320508075688772) / 6.0;
const F3: f64 = 1.0 / 3.0;
const G3: f64 = 1.0 / 6.0;

/// Ken Perlin's reference permutation, the one the shared `Simplex::classic`
/// is built on.
const REFERENCE: [i16; 256] = [
    151, 160, 137, 91, 90, 15, 131, 13, 201, 95, 96, 53, 194, 233, 7, 225,
    140, 36, 103, 30, 69, 142, 8, 99, 37, 240, 21, 10, 23, 190, 6, 148,
    247, 120, 234, 75, 0, 26, 197, 62, 94, 252, 219, 203, 117, 35, 11, 32,
    57, 177, 33, 88, 237, 149, 56, 87, 174, 20, 125, 136, 171, 168, 68, 175,
    74, 165, 71, 134, 139, 48, 27, 166, 77, 146, 158, 231, 83, 111, 229, 122,
    60, 211, 133, 230, 220, 105, 92, 41, 55, 46, 245, 40, 244, 102, 143, 54,
    65, 25, 63, 161, 1, 216, 80, 73, 209, 76, 132, 187, 208, 89, 18, 169,
    200, 196, 135, 130, 116, 188, 159, 86, 164, 100, 109, 198, 173, 186, 3, 64,
    52, 217, 226, 250, 124, 123, 5, 202, 38, 147, 118, 126, 255, 82, 85, 212,
    207, 206, 59, 227, 47, 16, 58, 17, 182, 189, 28, 42, 223, 183, 170, 213,
    119, 248, 152, 2, 44, 154, 163, 70, 221, 153, 101, 155, 167, 43, 172, 9,
    129, 22, 39, 253, 19, 98, 108, 110, 79, 113, 224, 232, 178, 185, 112, 104,
    218, 246, 97, 228, 251, 34, 242, 193, 238, 210, 144, 12, 191, 179, 162, 241,
    81, 51, 145, 235, 249, 14, 239, 107, 49, 192, 214, 31, 181, 199, 106, 157,
    184, 84, 204, 176, 115, 121, 50, 45, 127, 4, 150, 254, 138, 236, 205, 93,
    222, 114, 67, 29, 24, 72, 243, 141, 128, 195, 78, 66, 215, 61, 156, 180,
];

impl Simplex {
    /// A simplex noise of `seed`, the permutation table shuffled by Java's
    /// `Random`.
    pub fn new(seed: i64) -> Self {
        let mut random = JavaRandom::new(seed);
        let mut p = [0i16; 256];
        for (i, entry) in p.iter_mut().enumerate() {
            *entry = i as i16;
        }
        for i in (1..256).rev() {
            let j = random.next_int_below(i as i32 + 1) as usize;
            p.swap(i, j);
        }
        Self::from_perm(&p)
    }

    /// The noise of FAWE's `SimplexNoise`, on the reference permutation: the
    /// field its rock brush draws from, the same for every caller.
    pub fn classic() -> Self {
        Self::from_perm(&REFERENCE)
    }

    fn from_perm(p: &[i16; 256]) -> Self {
        let mut perm = [0i16; 512];
        let mut perm_mod12 = [0i16; 512];
        for i in 0..512 {
            perm[i] = p[i & 255];
            perm_mod12[i] = perm[i] % 12;
        }
        Self { perm, perm_mod12 }
    }

    fn contribution(gi: usize, x: f64, y: f64, z: f64) -> f64 {
        let t = 0.6 - x * x - y * y - z * z;
        if t < 0.0 {
            return 0.0;
        }
        let t2 = t * t;
        t2 * t2
            * (GRAD3[gi][0] as f64 * x + GRAD3[gi][1] as f64 * y + GRAD3[gi][2] as f64 * z)
    }

    fn contribution2(gi: usize, x: f64, y: f64) -> f64 {
        let t = 0.5 - x * x - y * y;
        if t < 0.0 {
            return 0.0;
        }
        let t2 = t * t;
        t2 * t2 * (GRAD3[gi][0] as f64 * x + GRAD3[gi][1] as f64 * y)
    }
}

fn fast_floor(x: f64) -> i32 {
    let xi = x as i32;
    if x < xi as f64 {
        xi - 1
    } else {
        xi
    }
}

impl NoiseFn for Simplex {
    fn noise(&self, xin: f64, yin: f64, zin: f64) -> f64 {
        let s = (xin + yin + zin) * F3;
        let i = fast_floor(xin + s);
        let j = fast_floor(yin + s);
        let k = fast_floor(zin + s);
        let t = (i + j + k) as f64 * G3;
        let x0 = xin - (i as f64 - t);
        let y0 = yin - (j as f64 - t);
        let z0 = zin - (k as f64 - t);
        let (i1, j1, k1, i2, j2, k2) = if x0 >= y0 {
            if y0 >= z0 {
                (1, 0, 0, 1, 1, 0)
            } else if x0 >= z0 {
                (1, 0, 0, 1, 0, 1)
            } else {
                (0, 0, 1, 1, 0, 1)
            }
        } else if y0 < z0 {
            (0, 0, 1, 0, 1, 1)
        } else if x0 < z0 {
            (0, 1, 0, 0, 1, 1)
        } else {
            (0, 1, 0, 1, 1, 0)
        };
        let x1 = x0 - i1 as f64 + G3;
        let y1 = y0 - j1 as f64 + G3;
        let z1 = z0 - k1 as f64 + G3;
        let x2 = x0 - i2 as f64 + 2.0 * G3;
        let y2 = y0 - j2 as f64 + 2.0 * G3;
        let z2 = z0 - k2 as f64 + 2.0 * G3;
        let x3 = x0 - 1.0 + 3.0 * G3;
        let y3 = y0 - 1.0 + 3.0 * G3;
        let z3 = z0 - 1.0 + 3.0 * G3;
        let ii = (i & 255) as usize;
        let jj = (j & 255) as usize;
        let kk = (k & 255) as usize;
        let perm = &self.perm;
        let p12 = &self.perm_mod12;
        let index = |a: usize, b: usize, c: usize| -> usize {
            p12[a + perm[b + perm[c] as usize] as usize].rem_euclid(12) as usize
        };
        let n0 = Self::contribution(index(ii, jj, kk), x0, y0, z0);
        let n1 = Self::contribution(index(ii + i1 as usize, jj + j1 as usize, kk + k1 as usize), x1, y1, z1);
        let n2 = Self::contribution(index(ii + i2 as usize, jj + j2 as usize, kk + k2 as usize), x2, y2, z2);
        let n3 = Self::contribution(index(ii + 1, jj + 1, kk + 1), x3, y3, z3);
        32.0 * (n0 + n1 + n2 + n3)
    }
}

impl Simplex {
    /// The 2D form, read as `(x, 0, z)` by `noise2` in the Java reference it
    /// matches; the reference also has a true 2D simplex, which the rock brush
    /// never reached. Kept for parity with the Java call sites.
    pub fn noise2d(&self, xin: f64, yin: f64) -> f64 {
        let s = (xin + yin) * F2;
        let i = fast_floor(xin + s);
        let j = fast_floor(yin + s);
        let t = (i + j) as f64 * G2;
        let x0 = xin - (i as f64 - t);
        let y0 = yin - (j as f64 - t);
        let (i1, j1) = if x0 > y0 { (1, 0) } else { (0, 1) };
        let x1 = x0 - i1 as f64 + G2;
        let y1 = y0 - j1 as f64 + G2;
        let x2 = x0 - 1.0 + 2.0 * G2;
        let y2 = y0 - 1.0 + 2.0 * G2;
        let ii = (i & 255) as usize;
        let jj = (j & 255) as usize;
        let g = |a: usize, b: usize| self.perm_mod12[a + self.perm[b] as usize].rem_euclid(12) as usize;
        let n0 = Self::contribution2(g(ii, jj), x0, y0);
        let n1 = Self::contribution2(g(ii + i1 as usize, jj + j1 as usize), x1, y1);
        let n2 = Self::contribution2(g(ii + 1, jj + 1), x2, y2);
        70.0 * (n0 + n1 + n2)
    }
}

/// Worley/Voronoi noise, ported from `Noise.Voronoi`.
#[derive(Clone)]
pub struct Voronoi {
    pub(crate) seed: i32,
}

impl Voronoi {
    /// `new Voronoi(seed)`.
    pub fn new(seed: i64) -> Self {
        Self {
            seed: folded_seed(seed),
        }
    }

    fn hash(&self, x: i32, y: i32, z: i32) -> f64 {
        let mut h = self.seed ^ x.wrapping_mul(374761393) ^ y.wrapping_mul(668265263) ^ z.wrapping_mul(2147483647);
        h = (h ^ (h >> 13)).wrapping_mul(1274126177);
        ((h ^ (h >> 16)) & 0x00FF_FFFF) as f64 / 0x00FF_FFFF as f64
    }

    fn nearest_cell(&self, x: f64, y: f64, z: f64) -> (f64, i32, i32, i32) {
        let xi = x.floor() as i32;
        let yi = y.floor() as i32;
        let zi = z.floor() as i32;
        let mut min = f64::MAX;
        let mut nearest = (xi, yi, zi);
        for dx in -1..=1 {
            for dy in -1..=1 {
                for dz in -1..=1 {
                    let nx = xi + dx;
                    let ny = yi + dy;
                    let nz = zi + dz;
                    let cx = nx as f64 + self.hash(nx, ny, nz);
                    let cy = ny as f64 + self.hash(nx + 31, ny + 17, nz + 7);
                    let cz = nz as f64 + self.hash(nx + 13, ny + 5, nz + 29);
                    let d = (cx - x) * (cx - x) + (cy - y) * (cy - y) + (cz - z) * (cz - z);
                    if d < min {
                        min = d;
                        nearest = (nx, ny, nz);
                    }
                }
            }
        }
        (min, nearest.0, nearest.1, nearest.2)
    }

    /// The value of the cell whose point is nearest, from -1 to 1: one number
    /// over the whole cell, which is what libnoise's Voronoi gives with
    /// distances off.
    pub fn cell_value(&self, x: f64, y: f64, z: f64) -> f64 {
        let (_, nx, ny, nz) = self.nearest_cell(x, y, z);
        self.hash(nx + 101, ny + 211, nz + 307) * 2.0 - 1.0
    }
}

impl NoiseFn for Voronoi {
    fn noise(&self, x: f64, y: f64, z: f64) -> f64 {
        let (min, _, _, _) = self.nearest_cell(x, y, z);
        min.sqrt()
    }

    /// Voronoi measures distances, so it is already a value in `[0, 1)`.
    fn unit(&self, x: f64, y: f64, z: f64) -> f64 {
        clamp01(NoiseFn::noise(self, x, y, z))
    }
}

/// Ridged multifractal noise, ported from `Noise.RidgedMultiFractal`.
pub struct RidgedMultiFractal {
    base: Simplex,
    octaves: i32,
    lacunarity: f64,
    gain: f64,
}

impl RidgedMultiFractal {
    /// `new RidgedMultiFractal(seed, octaves, lacunarity, gain)`.
    pub fn new(seed: i64, octaves: i32, lacunarity: f64, gain: f64) -> Self {
        Self {
            base: Simplex::new(seed),
            octaves,
            lacunarity,
            gain,
        }
    }
}

impl NoiseFn for RidgedMultiFractal {
    fn noise(&self, x: f64, y: f64, z: f64) -> f64 {
        let mut sum = 0.0;
        let mut frequency = 1.0;
        let mut amplitude = 1.0;
        let mut weight = 1.0;
        for _ in 0..self.octaves {
            let mut signal = self.base.noise(x * frequency, y * frequency, z * frequency);
            signal = -signal.abs() + 1.0;
            signal *= signal * weight;
            weight = (signal * 2.0).clamp(0.0, 1.0);
            sum += signal * amplitude;
            frequency *= self.lacunarity;
            amplitude *= self.gain;
        }
        sum
    }

    /// The ridged sum is positive by construction, so it is clamped only.
    fn unit(&self, x: f64, y: f64, z: f64) -> f64 {
        clamp01(NoiseFn::noise(self, x, y, z))
    }
}

/// White noise: a value in `[0, 1)` that depends only on the position, which
/// is what `%50` masks and the `#noise` patterns read. Being a hash of the
/// coordinates it repeats exactly, so a mask answers the same way every time a
/// block is tested.
pub struct RandomNoise {
    seed: i64,
}

impl RandomNoise {
    /// `new RandomNoise(seed)`.
    pub fn new(seed: i64) -> Self {
        Self { seed }
    }
}

impl NoiseFn for RandomNoise {
    fn noise(&self, x: f64, y: f64, z: f64) -> f64 {
        let mut hash = 0x9E3779B97F4A7C15u64 ^ (self.seed.wrapping_mul(0x2545F4914F6CDD1D) as u64);
        hash ^= (x.floor() as i64 as u64).wrapping_mul(0xBF58476D1CE4E5B9);
        hash ^= (y.floor() as i64 as u64).wrapping_mul(0x94D049BB133111EB);
        hash ^= (z.floor() as i64 as u64).wrapping_mul(0x2545F4914F6CDD1D);
        hash ^= hash >> 31;
        hash = hash.wrapping_mul(0x7FB5D329728EA185);
        hash ^= hash >> 27;
        hash = hash.wrapping_mul(0x81DAE7C9F3B1D1A7);
        hash ^= hash >> 33;
        (hash >> 11) as f64 * 1.1102230246251565e-16 // 2^-53
    }

    fn unit(&self, x: f64, y: f64, z: f64) -> f64 {
        NoiseFn::noise(self, x, y, z)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn java_random_matches_the_reference_sequence() {
        let mut random = JavaRandom::new(0);
        assert_eq!(random.next_int(), -1155484576);
        assert_eq!(random.next_int(), 363729741);
        assert_eq!(random.next_int(), -258970078);
        assert_eq!(random.next_int(), 15051088);

        let mut random = JavaRandom::new(0);
        let bounded: Vec<i32> = (0..8).map(|_| random.next_int_below(100)).collect();
        assert_eq!(bounded, [60, 70, 9, 44, 79, 39, 59, 74]);

        let mut random = JavaRandom::new(0);
        assert!((random.next_double() - 0.7309677762491618).abs() < 1e-18);
        assert!((random.next_double() - 0.9397038221881442).abs() < 1e-18);
        assert!((random.next_double() - 0.34019528003063626).abs() < 1e-18);

        let mut random = JavaRandom::new(42);
        assert_eq!(random.next_int(), -1170105035);
        assert_eq!(random.next_int(), 1322470667);
    }

    #[test]
    fn perlin_permutation_matches_the_java_shuffle() {
        let perlin = Perlin::new(0);
        assert_eq!(
            &perlin.perm()[..16],
            &[85, 234, 18, 77, 1, 133, 162, 197, 37, 25, 101, 164, 83, 221, 4, 33]
        );
    }

    #[test]
    fn simplex_noise_is_deterministic_and_finite() {
        let a = Simplex::new(0);
        let b = Simplex::new(0);
        let classic = Simplex::classic();
        for (x, y, z) in [(0.0, 0.0, 0.0), (1.5, -2.0, 3.25), (100.0, 7.5, -0.125)] {
            assert_eq!(a.noise(x, y, z), b.noise(x, y, z));
            assert!(a.noise(x, y, z).is_finite());
            assert!(classic.noise(x, y, z).is_finite());
        }
        // A different seed gives a field the same shape but not the same values.
        // (Exactly at a lattice point simplex noise is zero for every seed.)
        let other = Simplex::new(1);
        assert_ne!(a.noise(3.25, 4.5, 5.75), other.noise(3.25, 4.5, 5.75));
        assert_eq!(a.noise(3.0, 4.0, 5.0), 0.0);
    }

    #[test]
    fn voronoi_cell_value_and_distance_are_in_range() {
        let voronoi = Voronoi::new(0);
        for (x, y, z) in [(0.25, 0.5, 0.75), (-3.0, 8.0, 2.5), (1000.0, 1001.0, 1002.0)] {
            let distance = voronoi.noise(x, y, z);
            assert!((0.0..=3.5).contains(&distance));
            let unit = voronoi.unit(x, y, z);
            assert!((0.0..1.0).contains(&unit));
            let cell = voronoi.cell_value(x, y, z);
            assert!((-1.0..=1.0).contains(&cell));
        }
    }

    #[test]
    fn ridged_multi_is_positive_and_bounded() {
        let ridged = RidgedMultiFractal::new(0, 3, 2.0, 0.5);
        for (x, y, z) in [(0.0, 0.0, 0.0), (5.0, 10.0, 15.0), (-40.0, 2.0, 7.0)] {
            let value = ridged.noise(x, y, z);
            assert!(value >= 0.0);
            assert!(ridged.unit(x, y, z) < 1.0);
        }
    }

    #[test]
    fn java_signum_and_round_match_the_reference() {
        assert_eq!(java_signum(5.0), 1.0);
        assert_eq!(java_signum(-5.0), -1.0);
        assert_eq!(java_signum(0.0), 0.0);
        assert_eq!(java_signum(-0.0), 0.0);
        assert_eq!(java_round(0.0), 0.0);
        assert_eq!(java_round(0.5), 1.0);
        assert_eq!(java_round(1.5), 2.0);
        assert_eq!(java_round(2.5), 3.0);
        assert_eq!(java_round(-1.5), -1.0);
        assert_eq!(java_round(f64::NAN), 0.0);
        assert_eq!(java_round(-7.5), -7.0);
    }

    #[test]
    fn folded_seed_matches_the_java_reference() {
        assert_eq!(folded_seed(0), 0);
        // 0x1_0000_0001 >>> 32 == 1; 0x0000_0001 ^ 1 == 0.
        assert_eq!(folded_seed(0x1_0000_0001), 0);
        // Java's unsigned 64-bit shift zero-extends the high half.
        let seed: i64 = 0x7FFF_FFFF_1234_5678;
        assert_eq!(
            folded_seed(seed) as u32,
            (seed as u32) ^ ((seed as u64 >> 32) as u32)
        );
        // A negative seed: all-ones XOR the zero-extended low half leaves zero.
        assert_eq!(folded_seed(-1), 0);
    }
}
