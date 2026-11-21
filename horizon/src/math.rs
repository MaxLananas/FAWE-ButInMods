//! Vector, box, direction and spline math, ported from `math/`,
//! `world/Direction.java` and `command/Directions.java` of the Java reference.

/// An immutable 2D integer block column coordinate `(x, z)`.
#[derive(Clone, Copy, PartialEq, Eq, Hash, Debug, Default, PartialOrd, Ord)]
pub struct BlockVector2 {
    pub x: i32,
    pub z: i32,
}

impl BlockVector2 {
    pub const ZERO: Self = Self { x: 0, z: 0 };

    pub const fn at(x: i32, z: i32) -> Self {
        Self { x, z }
    }

    pub const fn add(self, dx: i32, dz: i32) -> Self {
        Self {
            x: self.x.saturating_add(dx),
            z: self.z.saturating_add(dz),
        }
    }

    pub const fn subtract(self, other: Self) -> Self {
        Self {
            x: self.x.saturating_sub(other.x),
            z: self.z.saturating_sub(other.z),
        }
    }

    pub fn length_sq(self) -> i64 {
        self.x as i64 * self.x as i64 + self.z as i64 * self.z as i64
    }

    pub const fn to_vector2(self) -> Vector2 {
        Vector2 {
            x: self.x as f64,
            z: self.z as f64,
        }
    }
}

impl std::fmt::Display for BlockVector2 {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "({}, {})", self.x, self.z)
    }
}

/// An immutable integer block position, `(x, y, z)`. See `math/BlockVector3.java`.
#[derive(Clone, Copy, PartialEq, Eq, Hash, Debug, Default, PartialOrd, Ord)]
pub struct BlockVector3 {
    pub x: i32,
    pub y: i32,
    pub z: i32,
}

impl BlockVector3 {
    pub const ZERO: Self = Self { x: 0, y: 0, z: 0 };
    pub const ONE: Self = Self { x: 1, y: 1, z: 1 };

    pub const fn at(x: i32, y: i32, z: i32) -> Self {
        Self { x, y, z }
    }

    pub fn floor(v: Vector3) -> Self {
        Self {
            x: v.x.floor() as i32,
            y: v.y.floor() as i32,
            z: v.z.floor() as i32,
        }
    }

    pub const fn add(self, other: Self) -> Self {
        Self {
            x: self.x.saturating_add(other.x),
            y: self.y.saturating_add(other.y),
            z: self.z.saturating_add(other.z),
        }
    }

    pub const fn add_xyz(self, dx: i32, dy: i32, dz: i32) -> Self {
        Self {
            x: self.x.saturating_add(dx),
            y: self.y.saturating_add(dy),
            z: self.z.saturating_add(dz),
        }
    }

    pub const fn subtract(self, other: Self) -> Self {
        Self {
            x: self.x.saturating_sub(other.x),
            y: self.y.saturating_sub(other.y),
            z: self.z.saturating_sub(other.z),
        }
    }

    pub const fn multiply(self, scale: i32) -> Self {
        Self {
            x: self.x.saturating_mul(scale),
            y: self.y.saturating_mul(scale),
            z: self.z.saturating_mul(scale),
        }
    }

    pub const fn with_x(self, x: i32) -> Self {
        Self { x, ..self }
    }

    pub const fn with_y(self, y: i32) -> Self {
        Self { y, ..self }
    }

    pub const fn with_z(self, z: i32) -> Self {
        Self { z, ..self }
    }

    pub fn min(self, other: Self) -> Self {
        Self {
            x: self.x.min(other.x),
            y: self.y.min(other.y),
            z: self.z.min(other.z),
        }
    }

    pub fn max(self, other: Self) -> Self {
        Self {
            x: self.x.max(other.x),
            y: self.y.max(other.y),
            z: self.z.max(other.z),
        }
    }

    /// The squared length, in 64 bits: a component past 46,341 squares out of an `i32`.
    pub fn length_sq(self) -> i64 {
        self.x as i64 * self.x as i64 + self.y as i64 * self.y as i64 + self.z as i64 * self.z as i64
    }

    pub fn distance_sq(self, other: Self) -> i64 {
        let dx = self.x as i64 - other.x as i64;
        let dy = self.y as i64 - other.y as i64;
        let dz = self.z as i64 - other.z as i64;
        dx * dx + dy * dy + dz * dz
    }

    pub fn distance(self, other: Self) -> f64 {
        (self.distance_sq(other) as f64).sqrt()
    }

    pub const fn to_vector3(self) -> Vector3 {
        Vector3 {
            x: self.x as f64,
            y: self.y as f64,
            z: self.z as f64,
        }
    }

    /// Block centre `(x + 0.5, y + 0.5, z + 0.5)`.
    pub fn to_center(self) -> Vector3 {
        Vector3 {
            x: self.x as f64 + 0.5,
            y: self.y as f64 + 0.5,
            z: self.z as f64 + 0.5,
        }
    }

    pub const fn to_block_vector2(self) -> BlockVector2 {
        BlockVector2 {
            x: self.x,
            z: self.z,
        }
    }

    pub const fn to_array(self) -> [i32; 3] {
        [self.x, self.y, self.z]
    }
}

impl std::fmt::Display for BlockVector3 {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "({}, {}, {})", self.x, self.y, self.z)
    }
}

/// An immutable double-precision 2D vector `(x, z)`.
#[derive(Clone, Copy, PartialEq, Debug, Default)]
pub struct Vector2 {
    pub x: f64,
    pub z: f64,
}

impl Vector2 {
    pub const ZERO: Self = Self { x: 0.0, z: 0.0 };

    pub const fn new(x: f64, z: f64) -> Self {
        Self { x, z }
    }

    pub fn add(self, other: Self) -> Self {
        Self {
            x: self.x + other.x,
            z: self.z + other.z,
        }
    }

    pub fn subtract(self, other: Self) -> Self {
        Self {
            x: self.x - other.x,
            z: self.z - other.z,
        }
    }

    pub fn length_sq(self) -> f64 {
        self.x * self.x + self.z * self.z
    }

    pub fn length(self) -> f64 {
        self.length_sq().sqrt()
    }

    pub fn to_block_point(self) -> BlockVector2 {
        BlockVector2 {
            x: self.x.floor() as i32,
            z: self.z.floor() as i32,
        }
    }
}

/// An immutable double-precision 3D vector `(x, y, z)`.
#[derive(Clone, Copy, PartialEq, Debug, Default)]
pub struct Vector3 {
    pub x: f64,
    pub y: f64,
    pub z: f64,
}

impl Vector3 {
    pub const ZERO: Self = Self {
        x: 0.0,
        y: 0.0,
        z: 0.0,
    };
    pub const ONE: Self = Self {
        x: 1.0,
        y: 1.0,
        z: 1.0,
    };

    pub const fn new(x: f64, y: f64, z: f64) -> Self {
        Self { x, y, z }
    }

    pub const fn at(x: f64, y: f64, z: f64) -> Self {
        Self { x, y, z }
    }

    pub fn add(self, other: Self) -> Self {
        Self {
            x: self.x + other.x,
            y: self.y + other.y,
            z: self.z + other.z,
        }
    }

    pub fn add_xyz(self, dx: f64, dy: f64, dz: f64) -> Self {
        Self {
            x: self.x + dx,
            y: self.y + dy,
            z: self.z + dz,
        }
    }

    pub fn subtract(self, other: Self) -> Self {
        Self {
            x: self.x - other.x,
            y: self.y - other.y,
            z: self.z - other.z,
        }
    }

    pub fn multiply(self, n: f64) -> Self {
        Self {
            x: self.x * n,
            y: self.y * n,
            z: self.z * n,
        }
    }

    pub fn multiply_vec(self, other: Self) -> Self {
        Self {
            x: self.x * other.x,
            y: self.y * other.y,
            z: self.z * other.z,
        }
    }

    pub fn divide(self, n: f64) -> Self {
        Self {
            x: self.x / n,
            y: self.y / n,
            z: self.z / n,
        }
    }

    pub const fn with_x(self, x: f64) -> Self {
        Self { x, ..self }
    }

    pub const fn with_y(self, y: f64) -> Self {
        Self { y, ..self }
    }

    pub const fn with_z(self, z: f64) -> Self {
        Self { z, ..self }
    }

    pub fn length_sq(self) -> f64 {
        self.x * self.x + self.y * self.y + self.z * self.z
    }

    pub fn length(self) -> f64 {
        self.length_sq().sqrt()
    }

    pub fn distance(self, other: Self) -> f64 {
        self.subtract(other).length()
    }

    pub fn normalize(self) -> Self {
        let len = self.length();
        if len == 0.0 {
            Self::ZERO
        } else {
            self.divide(len)
        }
    }

    pub fn dot(self, other: Self) -> f64 {
        self.x * other.x + self.y * other.y + self.z * other.z
    }

    pub fn cross(self, other: Self) -> Self {
        Self {
            x: self.y * other.z - self.z * other.y,
            y: self.z * other.x - self.x * other.z,
            z: self.x * other.y - self.y * other.x,
        }
    }

    pub fn to_block_point(self) -> BlockVector3 {
        BlockVector3::floor(self)
    }
}

impl std::fmt::Display for Vector3 {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "({}, {}, {})", self.x, self.y, self.z)
    }
}

/// An axis-aligned box of block coordinates (`math/BlockBox.java`).
#[derive(Clone, Copy, PartialEq, Eq, Debug, Default)]
pub struct BlockBox {
    pub min_x: i32,
    pub min_y: i32,
    pub min_z: i32,
    pub max_x: i32,
    pub max_y: i32,
    pub max_z: i32,
}

impl BlockBox {
    pub fn new(min_x: i32, min_y: i32, min_z: i32, max_x: i32, max_y: i32, max_z: i32) -> Self {
        Self {
            min_x: min_x.min(max_x),
            min_y: min_y.min(max_y),
            min_z: min_z.min(max_z),
            max_x: min_x.max(max_x),
            max_y: min_y.max(max_y),
            max_z: min_z.max(max_z),
        }
    }

    pub fn between(a: BlockVector3, b: BlockVector3) -> Self {
        Self::new(a.x, a.y, a.z, b.x, b.y, b.z)
    }

    pub const fn point(p: BlockVector3) -> Self {
        Self {
            min_x: p.x,
            min_y: p.y,
            min_z: p.z,
            max_x: p.x,
            max_y: p.y,
            max_z: p.z,
        }
    }

    pub const fn from_chunk(chunk_x: i32, chunk_z: i32, min_y: i32, max_y: i32) -> Self {
        Self {
            min_x: chunk_x << 4,
            min_y,
            min_z: chunk_z << 4,
            max_x: (chunk_x << 4) + 15,
            max_y,
            max_z: (chunk_z << 4) + 15,
        }
    }

    pub const fn min(self) -> BlockVector3 {
        BlockVector3::at(self.min_x, self.min_y, self.min_z)
    }

    pub const fn max(self) -> BlockVector3 {
        BlockVector3::at(self.max_x, self.max_y, self.max_z)
    }

    pub const fn center(self) -> BlockVector3 {
        BlockVector3::at(
            ((self.min_x as i64 + self.max_x as i64) >> 1) as i32,
            ((self.min_y as i64 + self.max_y as i64) >> 1) as i32,
            ((self.min_z as i64 + self.max_z as i64) >> 1) as i32,
        )
    }

    pub fn width(self) -> i32 {
        self.max_x - self.min_x + 1
    }

    pub fn height(self) -> i32 {
        self.max_y - self.min_y + 1
    }

    pub fn length(self) -> i32 {
        self.max_z - self.min_z + 1
    }

    pub fn volume(self) -> u64 {
        (self.max_x as i64 - self.min_x as i64 + 1).max(0) as u64
            * (self.max_y as i64 - self.min_y as i64 + 1).max(0) as u64
            * (self.max_z as i64 - self.min_z as i64 + 1).max(0) as u64
    }

    pub const fn contains(self, x: i32, y: i32, z: i32) -> bool {
        x >= self.min_x
            && x <= self.max_x
            && y >= self.min_y
            && y <= self.max_y
            && z >= self.min_z
            && z <= self.max_z
    }

    pub const fn contains_vec(self, v: BlockVector3) -> bool {
        self.contains(v.x, v.y, v.z)
    }

    pub const fn contains_chunk(self, chunk_x: i32, chunk_z: i32) -> bool {
        chunk_x >= (self.min_x >> 4)
            && chunk_x <= (self.max_x >> 4)
            && chunk_z >= (self.min_z >> 4)
            && chunk_z <= (self.max_z >> 4)
    }

    pub const fn expand(self, dx: i32, dy: i32, dz: i32) -> Self {
        Self {
            min_x: self.min_x.saturating_sub(dx),
            min_y: self.min_y.saturating_sub(dy),
            min_z: self.min_z.saturating_sub(dz),
            max_x: self.max_x.saturating_add(dx),
            max_y: self.max_y.saturating_add(dy),
            max_z: self.max_z.saturating_add(dz),
        }
    }

    pub const fn shift(self, dx: i32, dy: i32, dz: i32) -> Self {
        Self {
            min_x: self.min_x.saturating_add(dx),
            min_y: self.min_y.saturating_add(dy),
            min_z: self.min_z.saturating_add(dz),
            max_x: self.max_x.saturating_add(dx),
            max_y: self.max_y.saturating_add(dy),
            max_z: self.max_z.saturating_add(dz),
        }
    }

    pub fn union(self, other: Self) -> Self {
        Self {
            min_x: self.min_x.min(other.min_x),
            min_y: self.min_y.min(other.min_y),
            min_z: self.min_z.min(other.min_z),
            max_x: self.max_x.max(other.max_x),
            max_y: self.max_y.max(other.max_y),
            max_z: self.max_z.max(other.max_z),
        }
    }

    pub fn intersects(self, other: Self) -> bool {
        self.max_x >= other.min_x
            && self.min_x <= other.max_x
            && self.max_y >= other.min_y
            && self.min_y <= other.max_y
            && self.max_z >= other.min_z
            && self.min_z <= other.max_z
    }

    pub fn clamp_y(self, min_y: i32, max_y: i32) -> Self {
        Self {
            min_y: self.min_y.max(min_y),
            max_y: self.max_y.min(max_y),
            ..self
        }
    }
}

/// Kochanek-Bartels spline interpolation (`math/KochanekBartels.java`).
/// With `tension = bias = continuity = 0`, produces a Catmull-Rom spline.
#[derive(Clone, Debug)]
pub struct KochanekBartels {
    a: Vec<Vector3>,
    b: Vec<Vector3>,
    c: Vec<Vector3>,
    d: Vec<Vector3>,
    scaling: usize,
}

impl KochanekBartels {
    pub fn new(nodes: &[Vector3], tension: f64, bias: f64, continuity: f64) -> Result<Self, String> {
        if nodes.is_empty() {
            return Err("A spline needs a node".to_string());
        }
        let count = nodes.len();
        let mut a = Vec::with_capacity(count);
        let mut b = Vec::with_capacity(count);
        let mut c = Vec::with_capacity(count);
        let mut d = Vec::with_capacity(count);
        let ta = (1.0 - tension) * (1.0 + bias) * (1.0 + continuity) / 2.0;
        let tb = (1.0 - tension) * (1.0 - bias) * (1.0 - continuity) / 2.0;
        let tc = (1.0 - tension) * (1.0 + bias) * (1.0 - continuity) / 2.0;
        let td = (1.0 - tension) * (1.0 - bias) * (1.0 + continuity) / 2.0;

        let node = |idx: isize| -> Vector3 {
            let clamped = idx.clamp(0, (count - 1) as isize) as usize;
            nodes[clamped]
        };
        let combine = |idx: isize, f1: f64, f2: f64, f3: f64, f4: f64| -> Vector3 {
            node(idx - 1)
                .multiply(f1)
                .add(node(idx).multiply(f2))
                .add(node(idx + 1).multiply(f3))
                .add(node(idx + 2).multiply(f4))
        };

        for i in 0..count {
            let idx = i as isize;
            a.push(combine(idx, -ta, ta - tb - tc + 2.0, tb + tc - td - 2.0, td));
            b.push(combine(
                idx,
                2.0 * ta,
                -2.0 * ta + 2.0 * tb + tc - 3.0,
                -2.0 * tb - tc + td + 3.0,
                -td,
            ));
            c.push(combine(idx, -ta, ta - tb, tb, 0.0));
            d.push(node(idx));
        }
        Ok(Self {
            a,
            b,
            c,
            d,
            scaling: count - 1,
        })
    }

    /// Point at `position` in `[0.0, 1.0]`.
    pub fn position(&self, position: f64) -> Result<Vector3, String> {
        if !(0.0..=1.0).contains(&position) {
            return Err(format!("A spline position runs from 0 to 1, not {position}"));
        }
        let scaled = position * self.scaling as f64;
        let index = (scaled.floor() as usize).min(self.a.len() - 1);
        let remainder = scaled - index as f64;
        Ok(self.a[index]
            .multiply(remainder)
            .add(self.b[index])
            .multiply(remainder)
            .add(self.c[index])
            .multiply(remainder)
            .add(self.d[index]))
    }

    /// WorldEdit's arc-length sum over segments.
    pub fn arc_length(&self) -> f64 {
        let mut length = 0.0;
        for index in 0..self.scaling {
            length += self.segment_length(index);
        }
        length
    }

    fn segment_length(&self, index: usize) -> f64 {
        let a3 = self.a[index].multiply(3.0);
        let b2 = self.b[index].multiply(2.0);
        let c1 = self.c[index];
        let points = 8;
        let mut sum = c1.length() / 2.0;
        for i in 1..(points - 1) {
            let t = i as f64 / points as f64;
            sum += a3.multiply(t).add(b2).multiply(t).add(c1).length();
        }
        sum += a3.add(b2).add(c1).length() / 2.0;
        sum / points as f64
    }
}

/// The six block directions (`world/Direction.java`).
#[derive(Clone, Copy, PartialEq, Eq, Hash, Debug)]
pub enum Direction {
    North,
    East,
    South,
    West,
    Up,
    Down,
}

impl Direction {
    pub const ALL: [Self; 6] = [
        Self::North,
        Self::East,
        Self::South,
        Self::West,
        Self::Up,
        Self::Down,
    ];

    pub const fn to_vector(self) -> BlockVector3 {
        match self {
            Self::North => BlockVector3::at(0, 0, -1),
            Self::East => BlockVector3::at(1, 0, 0),
            Self::South => BlockVector3::at(0, 0, 1),
            Self::West => BlockVector3::at(-1, 0, 0),
            Self::Up => BlockVector3::at(0, 1, 0),
            Self::Down => BlockVector3::at(0, -1, 0),
        }
    }

    pub const fn to_vector3(self) -> Vector3 {
        let v = self.to_vector();
        v.to_vector3()
    }

    pub const fn opposite(self) -> Self {
        match self {
            Self::North => Self::South,
            Self::South => Self::North,
            Self::East => Self::West,
            Self::West => Self::East,
            Self::Up => Self::Down,
            Self::Down => Self::Up,
        }
    }

    pub const fn as_str(self) -> &'static str {
        match self {
            Self::North => "north",
            Self::East => "east",
            Self::South => "south",
            Self::West => "west",
            Self::Up => "up",
            Self::Down => "down",
        }
    }

    pub fn parse(input: &str) -> Result<Self, String> {
        match input.trim().to_ascii_lowercase().as_str() {
            "north" | "n" => Ok(Self::North),
            "south" | "s" => Ok(Self::South),
            "east" | "e" => Ok(Self::East),
            "west" | "w" => Ok(Self::West),
            "up" | "u" => Ok(Self::Up),
            "down" | "d" => Ok(Self::Down),
            _ => Err(format!("Unknown direction: {input}")),
        }
    }
}

/// WorldEdit's direction and offset argument parser (`command/Directions.java`).
pub mod directions {
    use super::{BlockVector3, Vector3};

    pub const NORTH: BlockVector3 = BlockVector3::at(0, 0, -1);
    pub const SOUTH: BlockVector3 = BlockVector3::at(0, 0, 1);
    pub const EAST: BlockVector3 = BlockVector3::at(1, 0, 0);
    pub const WEST: BlockVector3 = BlockVector3::at(-1, 0, 0);
    pub const UP: BlockVector3 = BlockVector3::at(0, 1, 0);
    pub const DOWN: BlockVector3 = BlockVector3::at(0, -1, 0);

    const OCTANTS: [BlockVector3; 8] = [
        SOUTH,
        BlockVector3::at(-1, 0, 1),
        WEST,
        BlockVector3::at(-1, 0, -1),
        NORTH,
        BlockVector3::at(1, 0, -1),
        EAST,
        BlockVector3::at(1, 0, 1),
    ];

    /// Optional player view orientation `(yaw, pitch)` in degrees.
    #[derive(Clone, Copy, Debug)]
    pub struct ViewOrientation {
        pub yaw: f64,
        pub pitch: f64,
    }

    impl ViewOrientation {
        /// Unit look vector in Minecraft coordinates (yaw=0 south, +90 west, pitch>0 down).
        pub fn direction(self) -> Vector3 {
            let yaw_rad = self.yaw.to_radians();
            let pitch_rad = self.pitch.to_radians();
            let flat = pitch_rad.cos();
            Vector3::new(-flat * yaw_rad.sin(), -pitch_rad.sin(), flat * yaw_rad.cos())
        }
    }

    pub const fn is_diagonal(step: BlockVector3) -> bool {
        step.x != 0 && step.z != 0
    }

    pub fn name(step: BlockVector3) -> &'static str {
        match (step.x.signum(), step.y.signum(), step.z.signum()) {
            (0, 0, -1) => "north",
            (0, 0, 1) => "south",
            (1, 0, 0) => "east",
            (-1, 0, 0) => "west",
            (0, 1, 0) => "up",
            (0, -1, 0) => "down",
            (1, 0, -1) => "north-east",
            (-1, 0, -1) => "north-west",
            (1, 0, 1) => "south-east",
            (-1, 0, 1) => "south-west",
            _ => "nowhere",
        }
    }

    /// Where a player looks, as WorldEdit's `getCardinalDirection` computes it.
    pub fn looking(yaw: f64, pitch: f64, yaw_offset: i32) -> BlockVector3 {
        if pitch > 67.5 {
            return DOWN;
        }
        if pitch < -67.5 {
            return UP;
        }
        let mut rotation = (yaw + yaw_offset as f64) % 360.0;
        if rotation < 0.0 {
            rotation += 360.0;
        }
        OCTANTS[(((rotation + 22.5) / 45.0).floor() as isize).rem_euclid(8) as usize]
    }

    fn named_direction(word: &str) -> Option<BlockVector3> {
        let words = ["north", "south", "east", "west", "up", "down"];
        let steps = [NORTH, SOUTH, EAST, WEST, UP, DOWN];
        for (i, full) in words.iter().enumerate() {
            if !word.is_empty() && full.starts_with(word) {
                return Some(steps[i]);
            }
        }
        // Diagonals: any prefix of north/south + any prefix of east/west
        for v in 0..2 {
            for s in 2..4 {
                for first in 1..=words[v].len() {
                    if let Some(rest) = word.strip_prefix(&words[v][..first])
                        && !rest.is_empty()
                        && words[s].starts_with(rest)
                    {
                        return Some(steps[v].add(steps[s]));
                    }
                }
            }
        }
        None
    }

    /// Parses a direction word (`n`, `north`, `ne`, `me`, `back`, `left`, `right`, ...).
    pub fn parse(
        view: Option<ViewOrientation>,
        input: &str,
        diagonals: bool,
    ) -> Result<BlockVector3, String> {
        let trimmed = input.trim();
        let word = trimmed.to_ascii_lowercase();
        let step = if let Some(step) = named_direction(&word) {
            step
        } else {
            let relative = |offset: i32| -> Result<BlockVector3, String> {
                let Some(v) = view else {
                    return Err(format!(
                        "Only a player can use '{trimmed}': name a direction such as north or up instead"
                    ));
                };
                Ok(looking(v.yaw, v.pitch, offset))
            };
            match word.as_str() {
                "m" | "me" | "f" | "forward" => relative(0)?,
                "b" | "back" => {
                    let behind = relative(180)?;
                    if behind.y != 0 {
                        behind.multiply(-1)
                    } else {
                        behind
                    }
                }
                "l" | "left" => relative(-90)?,
                "r" | "right" => relative(90)?,
                _ => {
                    return Err(format!(
                        "Unknown direction '{trimmed}'; use north, south, east, west, up, down, me, back, left or right"
                    ));
                }
            }
        };
        if !diagonals && is_diagonal(step) {
            return Err(format!(
                "'{trimmed}' is {}, a diagonal: this command takes north, south, east, west, up or down",
                name(step)
            ));
        }
        Ok(step)
    }

    /// Parses WorldEdit's offset (`north`, `x,y,z`, or `^x,y,z` view-local).
    pub fn offset(view: Option<ViewOrientation>, input: &str) -> Result<BlockVector3, String> {
        let text = input.trim();
        if let Some(rest) = text.strip_prefix('^') {
            let Some(v) = view else {
                return Err("Only a player can use an offset relative to where they look".to_string());
            };
            let rel = parse_xyz_vector(rest, input)?;
            return Ok(relative_offset(v.yaw, v.pitch, rel));
        }
        if text.contains(',') {
            return parse_xyz_vector(text, input);
        }
        parse(view, text, true)
    }

    fn parse_xyz_vector(text: &str, input: &str) -> Result<BlockVector3, String> {
        let parts: Vec<&str> = text.split(',').collect();
        if parts.len() != 3 {
            return Err(format!(
                "'{}' is not an offset: it needs exactly three numbers, x,y,z",
                input.trim()
            ));
        }
        let mut values = [0i32; 3];
        for (i, part) in parts.iter().enumerate() {
            let p = part.trim();
            values[i] = p.parse::<i32>().map_err(|_| {
                format!("'{p}' in '{}' is not a whole number", input.trim())
            })?;
        }
        Ok(BlockVector3::at(values[0], values[1], values[2]))
    }

    pub fn relative_offset(yaw: f64, pitch: f64, relative: BlockVector3) -> BlockVector3 {
        let cos_yaw = cos_degrees(yaw + 90.0);
        let sin_yaw = sin_degrees(yaw + 90.0);
        let cos_pitch = cos_degrees(-pitch + 90.0);
        let sin_pitch = sin_degrees(-pitch + 90.0);
        let flat = pitch.to_radians().cos();
        let yaw_rad = yaw.to_radians();
        let pitch_rad = pitch.to_radians();
        let forward = Vector3::new(-flat * yaw_rad.sin(), -pitch_rad.sin(), flat * yaw_rad.cos());
        let top = Vector3::new(cos_yaw * cos_pitch, sin_pitch, sin_yaw * cos_pitch);
        let side = forward.cross(top).multiply(-1.0);
        let turned = forward
            .multiply(relative.x as f64)
            .add(top.multiply(relative.y as f64))
            .add(side.multiply(relative.z as f64));
        BlockVector3::at(
            turned.x.round() as i32,
            turned.y.round() as i32,
            turned.z.round() as i32,
        )
    }

    pub fn cos_degrees(degrees: f64) -> f64 {
        let whole = degrees as i64;
        if degrees == whole as f64 && whole % 90 == 0 {
            return match whole.rem_euclid(360) {
                0 => 1.0,
                180 => -1.0,
                _ => 0.0,
            };
        }
        degrees.to_radians().cos()
    }

    pub fn sin_degrees(degrees: f64) -> f64 {
        let whole = degrees as i64;
        if degrees == whole as f64 && whole % 90 == 0 {
            return match whole.rem_euclid(360) {
                90 => 1.0,
                270 => -1.0,
                _ => 0.0,
            };
        }
        degrees.to_radians().sin()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn spline_hits_endpoints_and_computes_arc_length() {
        let nodes = [
            Vector3::new(0.0, 0.0, 0.0),
            Vector3::new(5.0, 0.0, 0.0),
            Vector3::new(10.0, 0.0, 0.0),
        ];
        let spline = KochanekBartels::new(&nodes, 0.0, 0.0, 0.0).unwrap();
        let p0 = spline.position(0.0).unwrap();
        let pm = spline.position(0.5).unwrap();
        let p1 = spline.position(1.0).unwrap();
        assert!((p0.x - 0.0).abs() < 1e-9);
        assert!((pm.x - 5.0).abs() < 1e-9);
        assert!((p1.x - 10.0).abs() < 1e-9);
        // WorldEdit's 7-of-8 point quadrature on the clamped Catmull-Rom polynomial.
        assert!((spline.arc_length() - 8.818359375).abs() < 1e-9);
    }

    #[test]
    fn directions_prefixes_diagonals_and_relative_work() {
        assert_eq!(
            directions::parse(None, "nor", false).unwrap(),
            directions::NORTH
        );
        assert!(directions::parse(None, "ne", false).is_err());
        assert_eq!(
            directions::parse(None, "ne", true).unwrap(),
            BlockVector3::at(1, 0, -1)
        );
        // yaw = 0 faces south (+Z); left is east (+X), back is north (-Z).
        let south_view = Some(directions::ViewOrientation {
            yaw: 0.0,
            pitch: 0.0,
        });
        assert_eq!(
            directions::parse(south_view, "me", false).unwrap(),
            directions::SOUTH
        );
        assert_eq!(
            directions::parse(south_view, "left", false).unwrap(),
            directions::EAST
        );
        assert_eq!(
            directions::parse(south_view, "back", false).unwrap(),
            directions::NORTH
        );
        assert_eq!(
            directions::offset(south_view, "^3,2,1").unwrap(),
            BlockVector3::at(1, 2, 3)
        );
    }
}
