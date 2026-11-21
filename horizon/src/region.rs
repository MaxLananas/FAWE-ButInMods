//! Region math and selection shapes, ported from `region/` of the Java reference:
//! [`CuboidRegion`], [`CylinderRegion`], [`EllipsoidRegion`],
//! [`Polygonal2DRegion`], [`ConvexPolyhedralRegion`], [`PolyhedralRegion`],
//! [`FuzzyRegion`], and [`RegionShape`].

use std::collections::{HashSet, VecDeque};

pub use crate::math::{BlockBox, BlockVector2, BlockVector3, Vector2, Vector3};

/// Packed 64-bit block coordinate keys (`region/BlockKeys.java`):
/// 26 bits each for `x` and `z`, 12 bits for `y`.
pub mod block_keys {
    #[inline]
    pub const fn key(x: i32, y: i32, z: i32) -> i64 {
        (((x as i64) & 0x3FF_FFFF) << 38)
            | (((y as i64) & 0xFFF) << 26)
            | ((z as i64) & 0x3FF_FFFF)
    }

    #[inline]
    pub const fn x(key: i64) -> i32 {
        (key >> 38) as i32
    }

    #[inline]
    pub const fn y(key: i64) -> i32 {
        ((key << 26) >> 52) as i32
    }

    #[inline]
    pub const fn z(key: i64) -> i32 {
        ((key << 38) >> 38) as i32
    }

    /// Sort packed keys in chunk-column order, then Y bottom-up, then Z, then X.
    pub fn chunk_order(positions: &[i64]) -> Vec<i64> {
        let mut sort_keys: Vec<u64> = positions
            .iter()
            .map(|&k| {
                let x = x(k);
                let y = y(k);
                let z = z(k);
                ((((x >> 4) + (1 << 21)) as u64) << 42)
                    | ((((z >> 4) + (1 << 21)) as u64) << 20)
                    | (((y + 2048) as u64 & 0xFFF) << 8)
                    | (((z & 15) as u64) << 4)
                    | ((x & 15) as u64)
            })
            .collect();
        sort_keys.sort_unstable();
        sort_keys
            .into_iter()
            .map(|sk| {
                let chunk_x = ((sk >> 42) as i32) - (1 << 21);
                let chunk_z = (((sk >> 20) & 0x3F_FFFF) as i32) - (1 << 21);
                let y = (((sk >> 8) & 0xFFF) as i32) - 2048;
                let z = (chunk_z << 4) | (((sk >> 4) & 15) as i32);
                let x = (chunk_x << 4) | ((sk & 15) as i32);
                key(x, y, z)
            })
            .collect()
    }
}

/// The classic two-corner selection, `//pos1` / `//pos2`.
/// A point past the opposite corner flips the box, as WorldEdit keeps the two
/// corners and reads the box from them.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct CuboidRegion {
    min: BlockVector3,
    max: BlockVector3,
}

impl CuboidRegion {
    /// The largest edit this port runs without another guard; a region past it
    /// is refused before anything walks it.
    pub const MAX_VOLUME: u64 = 2_000_000;

    /// A cuboid around `a` and `b`, either order.
    pub fn new(a: BlockVector3, b: BlockVector3) -> Self {
        let mut region = Self { min: a, max: b };
        region.normalize();
        region
    }

    pub fn min(&self) -> BlockVector3 {
        self.min
    }

    pub fn max(&self) -> BlockVector3 {
        self.max
    }

    pub fn center(&self) -> Vector3 {
        Vector3::new(
            (self.min.x as f64 + self.max.x as f64) * 0.5,
            (self.min.y as f64 + self.max.y as f64) * 0.5,
            (self.min.z as f64 + self.max.z as f64) * 0.5,
        )
    }

    pub fn set_corners(&mut self, a: BlockVector3, b: BlockVector3) {
        self.min = a;
        self.max = b;
        self.normalize();
    }

    pub fn set_pos1(&mut self, pos: BlockVector3) {
        self.min = pos;
        self.normalize();
    }

    pub fn set_pos2(&mut self, pos: BlockVector3) {
        self.max = pos;
        self.normalize();
    }

    fn normalize(&mut self) {
        if self.min.x > self.max.x {
            std::mem::swap(&mut self.min.x, &mut self.max.x);
        }
        if self.min.y > self.max.y {
            std::mem::swap(&mut self.min.y, &mut self.max.y);
        }
        if self.min.z > self.max.z {
            std::mem::swap(&mut self.min.z, &mut self.max.z);
        }
    }

    pub fn width(&self) -> u64 {
        (self.max.x as i64 - self.min.x as i64) as u64 + 1
    }

    pub fn height(&self) -> u64 {
        (self.max.y as i64 - self.min.y as i64) as u64 + 1
    }

    pub fn length(&self) -> u64 {
        (self.max.z as i64 - self.min.z as i64) as u64 + 1
    }

    pub fn area(&self) -> u64 {
        self.width() * self.length()
    }

    pub fn volume(&self) -> u64 {
        self.width() * self.height() * self.length()
    }

    pub fn chunk_count(&self) -> u64 {
        ((self.max.x >> 4) - (self.min.x >> 4) + 1) as u64
            * ((self.max.z >> 4) - (self.min.z >> 4) + 1) as u64
    }

    pub fn is_flat(&self) -> bool {
        self.min.y == self.max.y
    }

    pub fn contains(&self, x: i32, y: i32, z: i32) -> bool {
        x >= self.min.x
            && x <= self.max.x
            && y >= self.min.y
            && y <= self.max.y
            && z >= self.min.z
            && z <= self.max.z
    }

    pub fn expand(&mut self, x: i32, y: i32, z: i32) -> bool {
        let changed = (x != 0) || (y != 0) || (z != 0);
        self.min.x = self.min.x.saturating_add(x.min(0));
        self.max.x = self.max.x.saturating_add(x.max(0));
        self.min.y = self.min.y.saturating_add(y.min(0));
        self.max.y = self.max.y.saturating_add(y.max(0));
        self.min.z = self.min.z.saturating_add(z.min(0));
        self.max.z = self.max.z.saturating_add(z.max(0));
        self.normalize();
        changed
    }

    pub fn contract(&mut self, x: i32, y: i32, z: i32) -> bool {
        let changed = (x != 0) || (y != 0) || (z != 0);
        self.min.x = self.min.x.saturating_add(x.max(0));
        self.max.x = self.max.x.saturating_add(x.min(0));
        self.min.y = self.min.y.saturating_add(y.max(0));
        self.max.y = self.max.y.saturating_add(y.min(0));
        self.min.z = self.min.z.saturating_add(z.max(0));
        self.max.z = self.max.z.saturating_add(z.min(0));
        self.normalize();
        changed
    }

    pub fn shift(&mut self, x: i32, y: i32, z: i32) -> bool {
        let changed = (x != 0) || (y != 0) || (z != 0);
        self.min.x = self.min.x.saturating_add(x);
        self.max.x = self.max.x.saturating_add(x);
        self.min.y = self.min.y.saturating_add(y);
        self.max.y = self.max.y.saturating_add(y);
        self.min.z = self.min.z.saturating_add(z);
        self.max.z = self.max.z.saturating_add(z);
        changed
    }

    pub fn describe(&self) -> String {
        format!("cuboid: {}x{}x{}", self.width(), self.height(), self.length())
    }

    pub fn for_each_position(&self, mut visit: impl FnMut(i32, i32, i32)) -> u64 {
        let min_x = self.min.x;
        let max_x = self.max.x;
        let min_y = self.min.y;
        let max_y = self.max.y;
        let min_z = self.min.z;
        let max_z = self.max.z;
        let mut visited = 0u64;
        let mut chunk_x = min_x >> 4;
        while chunk_x <= max_x >> 4 {
            let x_start = min_x.max(chunk_x << 4);
            let x_end = max_x.min((chunk_x << 4) + 15);
            let mut chunk_z = min_z >> 4;
            while chunk_z <= max_z >> 4 {
                let z_start = min_z.max(chunk_z << 4);
                let z_end = max_z.min((chunk_z << 4) + 15);
                let mut section_y = min_y >> 4;
                while section_y <= max_y >> 4 {
                    let y_start = min_y.max(section_y << 4);
                    let y_end = max_y.min((section_y << 4) + 15);
                    let mut y = y_start;
                    while y <= y_end {
                        let mut z = z_start;
                        while z <= z_end {
                            let mut x = x_start;
                            while x <= x_end {
                                visit(x, y, z);
                                visited += 1;
                                x += 1;
                            }
                            z += 1;
                        }
                        y += 1;
                    }
                    section_y += 1;
                }
                chunk_z += 1;
            }
            chunk_x += 1;
        }
        visited
    }
}

/// Vertical elliptical cylinder (`region/CylinderRegion.java`).
#[derive(Clone, Copy, PartialEq, Debug)]
pub struct CylinderRegion {
    center: Vector2,
    radius_x: f64,
    radius_z: f64,
    min_y: i32,
    max_y: i32,
}

impl CylinderRegion {
    pub fn new(center: Vector2, radius_x: f64, radius_z: f64, min_y: i32, max_y: i32) -> Self {
        Self {
            center,
            radius_x: radius_x.abs(),
            radius_z: radius_z.abs(),
            min_y: min_y.min(max_y),
            max_y: min_y.max(max_y),
        }
    }

    pub fn center_2d(&self) -> Vector2 {
        self.center
    }

    pub fn set_center(&mut self, center: Vector2) {
        self.center = center;
    }

    pub fn radius_x(&self) -> f64 {
        self.radius_x
    }

    pub fn radius_z(&self) -> f64 {
        self.radius_z
    }

    pub fn set_radius(&mut self, rx: f64, rz: f64) {
        self.radius_x = rx.abs();
        self.radius_z = rz.abs();
    }

    pub fn min_y(&self) -> i32 {
        self.min_y
    }

    pub fn max_y(&self) -> i32 {
        self.max_y
    }

    pub fn set_y_range(&mut self, min_y: i32, max_y: i32) {
        self.min_y = min_y.min(max_y);
        self.max_y = min_y.max(max_y);
    }

    pub fn min(&self) -> BlockVector3 {
        BlockVector3::at(
            (self.center.x - self.radius_x - 1.0).ceil() as i32,
            self.min_y,
            (self.center.z - self.radius_z - 1.0).ceil() as i32,
        )
    }

    pub fn max(&self) -> BlockVector3 {
        BlockVector3::at(
            (self.center.x + self.radius_x).floor() as i32,
            self.max_y,
            (self.center.z + self.radius_z).floor() as i32,
        )
    }

    pub fn height(&self) -> u64 {
        (self.max_y as i64 - self.min_y as i64 + 1).max(0) as u64
    }

    pub fn volume(&self) -> u64 {
        ((self.radius_x + 0.5) * (self.radius_z + 0.5) * std::f64::consts::PI * self.height() as f64)
            .floor()
            .max(0.0) as u64
    }

    #[inline]
    pub fn contains_column(&self, x: i32, z: i32) -> bool {
        let dx = (x as f64 + 0.5 - self.center.x) / (self.radius_x + 0.5);
        let dz = (z as f64 + 0.5 - self.center.z) / (self.radius_z + 0.5);
        dx * dx + dz * dz <= 1.0
    }

    pub fn contains(&self, x: i32, y: i32, z: i32) -> bool {
        y >= self.min_y && y <= self.max_y && self.contains_column(x, z)
    }

    pub fn expand(&mut self, amounts: &[BlockVector3]) -> Result<bool, String> {
        let mut sx = 0i64;
        let mut sz = 0i64;
        let mut grow_x = 0i64;
        let mut grow_z = 0i64;
        let mut changed = false;
        for a in amounts {
            sx += a.x as i64;
            sz += a.z as i64;
            grow_x += (a.x as i64).abs();
            grow_z += (a.z as i64).abs();
            if a.y > 0 {
                self.max_y = self.max_y.saturating_add(a.y);
            } else {
                self.min_y = self.min_y.saturating_add(a.y);
            }
            changed |= *a != BlockVector3::ZERO;
        }
        if (sx & 1) != 0 || (sz & 1) != 0 {
            return Err(
                "A cylinder grows and shrinks by an even amount on each horizontal axis"
                    .to_string(),
            );
        }
        self.center.x += (sx / 2) as f64;
        self.center.z += (sz / 2) as f64;
        self.radius_x += (grow_x / 2) as f64;
        self.radius_z += (grow_z / 2) as f64;
        Ok(changed)
    }

    pub fn contract(&mut self, amounts: &[BlockVector3]) -> Result<bool, String> {
        let mut sx = 0i64;
        let mut sz = 0i64;
        let mut shrink_x = 0i64;
        let mut shrink_z = 0i64;
        let mut changed = false;
        for a in amounts {
            sx += a.x as i64;
            sz += a.z as i64;
            shrink_x += (a.x as i64).abs();
            shrink_z += (a.z as i64).abs();
            let h = self.max_y - self.min_y;
            if a.y > 0 {
                self.min_y += h.min(a.y);
            } else {
                self.max_y += (-h).max(a.y);
            }
            changed |= *a != BlockVector3::ZERO;
        }
        if (sx & 1) != 0 || (sz & 1) != 0 {
            return Err(
                "A cylinder grows and shrinks by an even amount on each horizontal axis"
                    .to_string(),
            );
        }
        self.center.x -= (sx / 2) as f64;
        self.center.z -= (sz / 2) as f64;
        self.radius_x = (self.radius_x - (shrink_x / 2) as f64).max(1.0);
        self.radius_z = (self.radius_z - (shrink_z / 2) as f64).max(1.0);
        Ok(changed)
    }

    pub fn shift(&mut self, dx: i32, dy: i32, dz: i32) -> bool {
        self.center.x += dx as f64;
        self.center.z += dz as f64;
        self.min_y = self.min_y.saturating_add(dy);
        self.max_y = self.max_y.saturating_add(dy);
        dx != 0 || dy != 0 || dz != 0
    }

    pub fn describe(&self) -> String {
        format!(
            "cyl: r={},{} y={}..{}",
            self.radius_x, self.radius_z, self.min_y, self.max_y
        )
    }

    pub fn for_each_position(&self, mut visit: impl FnMut(i32, i32, i32)) -> u64 {
        let min = self.min();
        let max = self.max();
        let mut visited = 0u64;
        for chunk_x in (min.x >> 4)..=(max.x >> 4) {
            let xs = min.x.max(chunk_x << 4);
            let xe = max.x.min((chunk_x << 4) + 15);
            for chunk_z in (min.z >> 4)..=(max.z >> 4) {
                let zs = min.z.max(chunk_z << 4);
                let ze = max.z.min((chunk_z << 4) + 15);
                for y in min.y..=max.y {
                    for z in zs..=ze {
                        for x in xs..=xe {
                            if self.contains_column(x, z) {
                                visit(x, y, z);
                                visited += 1;
                            }
                        }
                    }
                }
            }
        }
        visited
    }
}

/// Sphere / ellipsoid region (`region/EllipsoidRegion.java`).
#[derive(Clone, Copy, PartialEq, Debug)]
pub struct EllipsoidRegion {
    center: Vector3,
    radii: Vector3,
    min_y: i32,
    max_y: i32,
}

impl EllipsoidRegion {
    pub fn new(center: Vector3, radii: Vector3) -> Self {
        Self::with_bounds(center, radii, i32::MIN / 2, i32::MAX / 2)
    }

    pub fn with_bounds(center: Vector3, radii: Vector3, min_y: i32, max_y: i32) -> Self {
        Self {
            center,
            radii: Vector3::new(radii.x.abs(), radii.y.abs(), radii.z.abs()),
            min_y: min_y.min(max_y),
            max_y: min_y.max(max_y),
        }
    }

    pub fn center(&self) -> Vector3 {
        self.center
    }

    pub fn set_center(&mut self, center: Vector3) {
        self.center = center;
    }

    pub fn radii(&self) -> Vector3 {
        self.radii
    }

    pub fn set_radii(&mut self, radii: Vector3) {
        self.radii = Vector3::new(radii.x.abs(), radii.y.abs(), radii.z.abs());
    }

    pub fn min(&self) -> BlockVector3 {
        BlockVector3::at(
            (self.center.x - self.radii.x - 1.0).ceil() as i32,
            self.min_y.max((self.center.y - self.radii.y - 1.0).ceil() as i32),
            (self.center.z - self.radii.z - 1.0).ceil() as i32,
        )
    }

    pub fn max(&self) -> BlockVector3 {
        BlockVector3::at(
            (self.center.x + self.radii.x).floor() as i32,
            self.max_y.min((self.center.y + self.radii.y).floor() as i32),
            (self.center.z + self.radii.z).floor() as i32,
        )
    }

    pub fn volume(&self) -> u64 {
        ((4.0 / 3.0)
            * std::f64::consts::PI
            * (self.radii.x + 0.5)
            * (self.radii.y + 0.5)
            * (self.radii.z + 0.5))
            .floor()
            .max(0.0) as u64
    }

    pub fn contains(&self, x: i32, y: i32, z: i32) -> bool {
        if y < self.min_y || y > self.max_y {
            return false;
        }
        let dx = (x as f64 + 0.5 - self.center.x) / (self.radii.x + 0.5);
        let dy = (y as f64 + 0.5 - self.center.y) / (self.radii.y + 0.5);
        let dz = (z as f64 + 0.5 - self.center.z) / (self.radii.z + 0.5);
        dx * dx + dy * dy + dz * dz <= 1.0
    }

    fn half_sums(amounts: &[BlockVector3]) -> Result<(Vector3, Vector3, bool), String> {
        let (mut sx, mut sy, mut sz) = (0i64, 0i64, 0i64);
        let (mut ax, mut ay, mut az) = (0i64, 0i64, 0i64);
        let mut changed = false;
        for a in amounts {
            sx += a.x as i64;
            sy += a.y as i64;
            sz += a.z as i64;
            ax += (a.x as i64).abs();
            ay += (a.y as i64).abs();
            az += (a.z as i64).abs();
            changed |= *a != BlockVector3::ZERO;
        }
        if (sx & 1) != 0 || (sy & 1) != 0 || (sz & 1) != 0 {
            return Err("An ellipsoid grows and shrinks by an even amount on each axis".to_string());
        }
        Ok((
            Vector3::new((sx / 2) as f64, (sy / 2) as f64, (sz / 2) as f64),
            Vector3::new((ax / 2) as f64, (ay / 2) as f64, (az / 2) as f64),
            changed,
        ))
    }

    pub fn expand(&mut self, amounts: &[BlockVector3]) -> Result<bool, String> {
        let (shift, grow, changed) = Self::half_sums(amounts)?;
        self.center = self.center.add(shift);
        self.radii = self.radii.add(grow);
        Ok(changed)
    }

    pub fn contract(&mut self, amounts: &[BlockVector3]) -> Result<bool, String> {
        let (shift, shrink, changed) = Self::half_sums(amounts)?;
        self.center = self.center.subtract(shift);
        self.radii = Vector3::new(
            (self.radii.x - shrink.x).max(1.0),
            (self.radii.y - shrink.y).max(1.0),
            (self.radii.z - shrink.z).max(1.0),
        );
        Ok(changed)
    }

    pub fn shift(&mut self, dx: i32, dy: i32, dz: i32) -> bool {
        self.center = self.center.add_xyz(dx as f64, dy as f64, dz as f64);
        dx != 0 || dy != 0 || dz != 0
    }

    pub fn describe(&self) -> String {
        format!(
            "ellipsoid: {}x{}x{}",
            self.radii.x, self.radii.y, self.radii.z
        )
    }

    pub fn for_each_position(&self, mut visit: impl FnMut(i32, i32, i32)) -> u64 {
        let min = self.min();
        let max = self.max();
        if min.y > max.y {
            return 0;
        }
        let mut visited = 0u64;
        for chunk_x in (min.x >> 4)..=(max.x >> 4) {
            let xs = min.x.max(chunk_x << 4);
            let xe = max.x.min((chunk_x << 4) + 15);
            for chunk_z in (min.z >> 4)..=(max.z >> 4) {
                let zs = min.z.max(chunk_z << 4);
                let ze = max.z.min((chunk_z << 4) + 15);
                for y in min.y..=max.y {
                    for z in zs..=ze {
                        for x in xs..=xe {
                            if self.contains(x, y, z) {
                                visit(x, y, z);
                                visited += 1;
                            }
                        }
                    }
                }
            }
        }
        visited
    }
}

/// 2D polygon extruded over a Y range (`region/Polygonal2DRegion.java`),
/// using exact 64-bit integer edge/scanline arithmetic.
#[derive(Clone, PartialEq, Eq, Debug)]
pub struct Polygonal2DRegion {
    points: Vec<BlockVector2>,
    min_y: i32,
    max_y: i32,
    min_x: i32,
    min_z: i32,
    max_x: i32,
    max_z: i32,
}

impl Polygonal2DRegion {
    const MAX_COORDINATE: i64 = 1 << 30;

    pub fn new(points: Vec<BlockVector2>, min_y: i32, max_y: i32) -> Result<Self, String> {
        let mut r = Self {
            points: Vec::new(),
            min_y: min_y.min(max_y),
            max_y: min_y.max(max_y),
            min_x: 0,
            min_z: 0,
            max_x: 0,
            max_z: 0,
        };
        r.set_points(points)?;
        Ok(r)
    }

    pub fn points(&self) -> &[BlockVector2] {
        &self.points
    }

    pub fn set_points(&mut self, outline: Vec<BlockVector2>) -> Result<(), String> {
        for p in &outline {
            if (p.x as i64).abs() > Self::MAX_COORDINATE || (p.z as i64).abs() > Self::MAX_COORDINATE
            {
                return Err(format!("The polygon point {p} is outside the world"));
            }
        }
        self.points = outline;
        self.recalculate();
        Ok(())
    }

    pub fn add_point(&mut self, p: BlockVector2) -> Result<(), String> {
        if (p.x as i64).abs() > Self::MAX_COORDINATE || (p.z as i64).abs() > Self::MAX_COORDINATE {
            return Err(format!("The polygon point {p} is outside the world"));
        }
        self.points.push(p);
        self.recalculate();
        Ok(())
    }

    pub fn min_y(&self) -> i32 {
        self.min_y
    }

    pub fn max_y(&self) -> i32 {
        self.max_y
    }

    pub fn set_y_range(&mut self, min_y: i32, max_y: i32) {
        self.min_y = min_y.min(max_y);
        self.max_y = min_y.max(max_y);
    }

    pub fn expand_y(&mut self, y: i32) {
        self.min_y = self.min_y.min(y);
        self.max_y = self.max_y.max(y);
    }

    fn recalculate(&mut self) {
        self.min_x = i32::MAX;
        self.min_z = i32::MAX;
        self.max_x = i32::MIN;
        self.max_z = i32::MIN;
        for p in &self.points {
            self.min_x = self.min_x.min(p.x);
            self.min_z = self.min_z.min(p.z);
            self.max_x = self.max_x.max(p.x);
            self.max_z = self.max_z.max(p.z);
        }
    }

    pub fn min(&self) -> BlockVector3 {
        if self.points.is_empty() {
            BlockVector3::ZERO
        } else {
            BlockVector3::at(self.min_x, self.min_y, self.min_z)
        }
    }

    pub fn max(&self) -> BlockVector3 {
        if self.points.is_empty() {
            BlockVector3::ZERO
        } else {
            BlockVector3::at(self.max_x, self.max_y, self.max_z)
        }
    }

    /// Exact integer point-in-polygon column test (`Polygonal2DRegion.containsColumn`).
    pub fn contains_column(&self, x: i32, z: i32) -> bool {
        let n = self.points.len();
        if n < 3 {
            return false;
        }
        let mut inside = false;
        let mut previous = self.points[n - 1];
        for &current in &self.points {
            if current.x == x && current.z == z {
                return true;
            }
            let (x1, z1, x2, z2) = if current.x > previous.x {
                (previous.x, previous.z, current.x, current.z)
            } else {
                (current.x, current.z, previous.x, previous.z)
            };
            if x1 <= x && x <= x2 {
                let cross = (z as i64 - z1 as i64) * (x2 as i64 - x1 as i64)
                    - (z2 as i64 - z1 as i64) * (x as i64 - x1 as i64);
                if cross == 0 {
                    if (z1 <= z) == (z <= z2) {
                        return true;
                    }
                } else if cross < 0 && x1 != x {
                    inside = !inside;
                }
            }
            previous = current;
        }
        inside
    }

    pub fn contains(&self, x: i32, y: i32, z: i32) -> bool {
        y >= self.min_y && y <= self.max_y && self.contains_column(x, z)
    }

    /// Exact rational Z-spans inside column `x` (`Polygonal2DRegion.spans`).
    pub fn column_spans(&self, x: i32) -> Vec<(i32, i32)> {
        let n = self.points.len();
        if n < 3 || x < self.min_x || x > self.max_x {
            return Vec::new();
        }
        let mut crossings: Vec<(i64, i64, i64)> = Vec::new();
        let mut runs: Vec<(i64, i64)> = Vec::new();
        let mut previous = self.points[n - 1];
        for &current in &self.points {
            let (x1, z1, x2, z2) = if current.x > previous.x {
                (previous.x, previous.z, current.x, current.z)
            } else {
                (current.x, current.z, previous.x, previous.z)
            };
            if x1 == x2 {
                if x1 == x {
                    runs.push((z1.min(z2) as i64, z1.max(z2) as i64));
                }
            } else if x1 < x && x <= x2 {
                let den = x2 as i64 - x1 as i64;
                let num = z1 as i64 * den + (z2 as i64 - z1 as i64) * (x as i64 - x1 as i64);
                let whole = num.div_euclid(den);
                let rem = num.rem_euclid(den);
                crossings.push((whole, rem, den));
            }
            if current.x == x {
                runs.push((current.z as i64, current.z as i64));
            }
            previous = current;
        }
        crossings.sort_by(|a, b| {
            a.0.cmp(&b.0)
                .then_with(|| (a.1 * b.2).cmp(&(b.1 * a.2)))
        });
        let mut i = 0;
        while i + 1 < crossings.len() {
            let low = crossings[i];
            let high = crossings[i + 1];
            let start = low.0 + i64::from(low.1 != 0);
            let end = high.0;
            if start <= end {
                runs.push((start, end));
            }
            i += 2;
        }
        runs.sort_by_key(|r| r.0);
        let mut merged: Vec<(i32, i32)> = Vec::new();
        for (from, to) in runs {
            if let Some(last) = merged.last_mut()
                && from <= last.1 as i64 + 1
            {
                last.1 = last.1.max(to as i32);
            } else {
                merged.push((from as i32, to as i32));
            }
        }
        merged
    }

    pub fn volume(&self) -> u64 {
        if self.points.len() < 3 {
            return 0;
        }
        let mut columns = 0u64;
        for x in self.min_x..=self.max_x {
            for (from, to) in self.column_spans(x) {
                columns += (to as i64 - from as i64 + 1) as u64;
            }
        }
        columns * (self.max_y as i64 - self.min_y as i64 + 1).max(0) as u64
    }

    pub fn expand(&mut self, dx: i32, dy: i32, dz: i32) -> Result<bool, String> {
        if dx != 0 || dz != 0 {
            return Err("A polygon selection can only be expanded vertically".to_string());
        }
        if dy == 0 {
            return Ok(false);
        }
        if dy > 0 {
            self.max_y = self.max_y.saturating_add(dy);
        } else {
            self.min_y = self.min_y.saturating_add(dy);
        }
        Ok(true)
    }

    pub fn contract(&mut self, dx: i32, dy: i32, dz: i32) -> Result<bool, String> {
        if dx != 0 || dz != 0 {
            return Err("A polygon selection can only be contracted vertically".to_string());
        }
        if dy == 0 {
            return Ok(false);
        }
        let h = self.max_y - self.min_y;
        if dy > 0 {
            self.min_y += h.min(dy);
        } else {
            self.max_y += (-h).max(dy);
        }
        Ok(true)
    }

    pub fn shift(&mut self, dx: i32, dy: i32, dz: i32) -> bool {
        if dx == 0 && dy == 0 && dz == 0 {
            return false;
        }
        for p in &mut self.points {
            *p = p.add(dx, dz);
        }
        self.min_y = self.min_y.saturating_add(dy);
        self.max_y = self.max_y.saturating_add(dy);
        self.recalculate();
        true
    }

    pub fn describe(&self) -> String {
        format!(
            "poly: {} points, y {}..{}",
            self.points.len(),
            self.min_y,
            self.max_y
        )
    }

    pub fn for_each_position(&self, mut visit: impl FnMut(i32, i32, i32)) -> u64 {
        if self.points.len() < 3 {
            return 0;
        }
        let mut visited = 0u64;
        for x in self.min_x..=self.max_x {
            for (from_z, to_z) in self.column_spans(x) {
                for y in self.min_y..=self.max_y {
                    for z in from_z..=to_z {
                        visit(x, y, z);
                        visited += 1;
                    }
                }
            }
        }
        visited
    }
}

/// Incremental 3D convex hull (`region/ConvexPolyhedralRegion.java`).
#[derive(Clone, PartialEq, Eq, Debug)]
pub struct ConvexPolyhedralRegion {
    vertices: Vec<BlockVector3>,
    backlog: Vec<BlockVector3>,
    faces: Vec<HullFace>,
    origin: Option<BlockVector3>,
    minimum: Option<BlockVector3>,
    maximum: Option<BlockVector3>,
    min_y: i32,
    max_y: i32,
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
struct HullFace {
    a: BlockVector3,
    b: BlockVector3,
    c: BlockVector3,
    normal_x: i64,
    normal_y: i64,
    normal_z: i64,
    limit: i64,
}

impl HullFace {
    fn new(a: BlockVector3, b: BlockVector3, c: BlockVector3, origin: BlockVector3) -> Self {
        let abx = b.x as i64 - a.x as i64;
        let aby = b.y as i64 - a.y as i64;
        let abz = b.z as i64 - a.z as i64;
        let acx = c.x as i64 - a.x as i64;
        let acy = c.y as i64 - a.y as i64;
        let acz = c.z as i64 - a.z as i64;
        let normal_x = aby * acz - abz * acy;
        let normal_y = abz * acx - abx * acz;
        let normal_z = abx * acy - aby * acx;
        let limit = normal_x * (a.x as i64 - origin.x as i64)
            + normal_y * (a.y as i64 - origin.y as i64)
            + normal_z * (a.z as i64 - origin.z as i64);
        Self {
            a,
            b,
            c,
            normal_x,
            normal_y,
            normal_z,
            limit,
        }
    }

    #[inline]
    fn above(&self, x: i64, y: i64, z: i64) -> bool {
        self.normal_x * x + self.normal_y * y + self.normal_z * z > self.limit
    }

    fn edges(&self) -> [(BlockVector3, BlockVector3); 3] {
        [(self.a, self.b), (self.b, self.c), (self.c, self.a)]
    }

    fn shift(&self, amount: BlockVector3) -> Self {
        Self {
            a: self.a.add(amount),
            b: self.b.add(amount),
            c: self.c.add(amount),
            ..*self
        }
    }
}

fn same_undirected_edge(e1: (BlockVector3, BlockVector3), e2: (BlockVector3, BlockVector3)) -> bool {
    (e1.0 == e2.0 && e1.1 == e2.1) || (e1.0 == e2.1 && e1.1 == e2.0)
}

impl Default for ConvexPolyhedralRegion {
    fn default() -> Self {
        Self::new()
    }
}

impl ConvexPolyhedralRegion {
    pub const MAX_SPAN: i64 = 500_000;

    pub fn new() -> Self {
        Self {
            vertices: Vec::new(),
            backlog: Vec::new(),
            faces: Vec::new(),
            origin: None,
            minimum: None,
            maximum: None,
            min_y: i32::MIN / 2,
            max_y: i32::MAX / 2,
        }
    }

    pub fn set_bounds(&mut self, min_y: i32, max_y: i32) {
        self.min_y = min_y.min(max_y);
        self.max_y = min_y.max(max_y);
    }

    pub fn min_y(&self) -> i32 {
        self.min_y
    }

    pub fn max_y(&self) -> i32 {
        self.max_y
    }

    pub fn vertices(&self) -> Vec<BlockVector3> {
        let mut all = self.vertices.clone();
        all.extend_from_slice(&self.backlog);
        all
    }

    pub fn triangles(&self) -> Vec<[BlockVector3; 3]> {
        self.faces.iter().map(|f| [f.a, f.b, f.c]).collect()
    }

    pub fn is_defined(&self) -> bool {
        !self.faces.is_empty()
    }

    pub fn clear(&mut self) {
        self.vertices.clear();
        self.backlog.clear();
        self.faces.clear();
        self.origin = None;
        self.minimum = None;
        self.maximum = None;
    }

    fn collinear(a: BlockVector3, b: BlockVector3, c: BlockVector3) -> bool {
        let abx = b.x as i64 - a.x as i64;
        let aby = b.y as i64 - a.y as i64;
        let abz = b.z as i64 - a.z as i64;
        let acx = c.x as i64 - a.x as i64;
        let acy = c.y as i64 - a.y as i64;
        let acz = c.z as i64 - a.z as i64;
        aby * acz - abz * acy == 0 && abz * acx - abx * acz == 0 && abx * acy - aby * acx == 0
    }

    fn inside_hull(&self, vertex: BlockVector3) -> bool {
        let Some(origin) = self.origin else {
            return false;
        };
        let x = vertex.x as i64 - origin.x as i64;
        let y = vertex.y as i64 - origin.y as i64;
        let z = vertex.z as i64 - origin.z as i64;
        !self.faces.iter().any(|f| f.above(x, y, z))
    }

    pub fn add_vertex(&mut self, vertex: BlockVector3) -> Result<bool, String> {
        if self.vertices.contains(&vertex) {
            return Ok(false);
        }
        if let Some(origin) = self.origin
            && ((vertex.x as i64 - origin.x as i64).abs() > Self::MAX_SPAN
                || (vertex.y as i64 - origin.y as i64).abs() > Self::MAX_SPAN
                || (vertex.z as i64 - origin.z as i64).abs() > Self::MAX_SPAN)
        {
            return Err(format!(
                "A convex selection spans at most {} blocks from its first point",
                Self::MAX_SPAN
            ));
        }
        if self.vertices.len() == 3 {
            if self.backlog.contains(&vertex) {
                return Ok(false);
            }
            if self.inside_hull(vertex) {
                self.backlog.push(vertex);
                return Ok(true);
            }
        } else if self.vertices.len() == 2
            && Self::collinear(self.vertices[0], self.vertices[1], vertex)
        {
            if self.backlog.contains(&vertex) {
                return Ok(false);
            }
            self.backlog.push(vertex);
            return Ok(true);
        }

        let origin = *self.origin.get_or_insert(vertex);
        self.vertices.push(vertex);
        self.minimum = Some(self.minimum.map_or(vertex, |m| m.min(vertex)));
        self.maximum = Some(self.maximum.map_or(vertex, |m| m.max(vertex)));

        if self.vertices.len() < 3 {
            return Ok(true);
        }
        if self.vertices.len() == 3 {
            let (a, b, c) = (self.vertices[0], self.vertices[1], self.vertices[2]);
            self.faces.push(HullFace::new(a, b, c, origin));
            self.faces.push(HullFace::new(a, c, b, origin));
            return Ok(true);
        }

        let vx = vertex.x as i64 - origin.x as i64;
        let vy = vertex.y as i64 - origin.y as i64;
        let vz = vertex.z as i64 - origin.z as i64;
        let mut rim: Vec<(BlockVector3, BlockVector3)> = Vec::new();
        let mut kept: Vec<HullFace> = Vec::with_capacity(self.faces.len());
        for face in self.faces.drain(..) {
            if !face.above(vx, vy, vz) {
                kept.push(face);
                continue;
            }
            for edge in face.edges() {
                if let Some(pos) = rim.iter().position(|&e| same_undirected_edge(e, edge)) {
                    rim.remove(pos);
                } else {
                    rim.push(edge);
                }
            }
        }
        self.faces = kept;
        for (start, end) in rim {
            self.faces.push(HullFace::new(start, end, vertex, origin));
        }

        if !self.backlog.is_empty() {
            self.vertices.pop();
            let waiting = std::mem::take(&mut self.backlog);
            for each in waiting {
                let _ = self.add_vertex(each)?;
            }
            self.vertices.push(vertex);
        }
        Ok(true)
    }

    pub fn min(&self) -> BlockVector3 {
        match self.minimum {
            None => BlockVector3::ZERO,
            Some(m) => m.with_y(m.y.max(self.min_y)),
        }
    }

    pub fn max(&self) -> BlockVector3 {
        match self.maximum {
            None => BlockVector3::ZERO,
            Some(m) => m.with_y(m.y.min(self.max_y)),
        }
    }

    fn column_range(&self, x: i32, z: i32, low_y: i32, high_y: i32) -> Option<(i32, i32)> {
        let origin = self.origin?;
        let px = x as i64 - origin.x as i64;
        let pz = z as i64 - origin.z as i64;
        let mut low = low_y as i64 - origin.y as i64;
        let mut high = high_y as i64 - origin.y as i64;
        for face in &self.faces {
            let rest = face.limit - face.normal_x * px - face.normal_z * pz;
            if face.normal_y > 0 {
                high = high.min(rest.div_euclid(face.normal_y));
            } else if face.normal_y < 0 {
                low = low.max(-rest.div_euclid(-face.normal_y));
            } else if rest < 0 {
                return None;
            }
            if low > high {
                return None;
            }
        }
        Some(((low + origin.y as i64) as i32, (high + origin.y as i64) as i32))
    }

    pub fn volume(&self) -> u64 {
        if !self.is_defined() {
            return 0;
        }
        let min = self.min();
        let max = self.max();
        let mut total = 0u64;
        for x in min.x..=max.x {
            for z in min.z..=max.z {
                if let Some((y0, y1)) = self.column_range(x, z, min.y, max.y) {
                    total += (y1 as i64 - y0 as i64 + 1) as u64;
                }
            }
        }
        total
    }

    pub fn contains(&self, x: i32, y: i32, z: i32) -> bool {
        let (Some(origin), Some(minimum), Some(maximum)) =
            (self.origin, self.minimum, self.maximum)
        else {
            return false;
        };
        if !self.is_defined()
            || y < self.min_y
            || y > self.max_y
            || x < minimum.x
            || x > maximum.x
            || y < minimum.y
            || y > maximum.y
            || z < minimum.z
            || z > maximum.z
        {
            return false;
        }
        let px = x as i64 - origin.x as i64;
        let py = y as i64 - origin.y as i64;
        let pz = z as i64 - origin.z as i64;
        !self.faces.iter().any(|f| f.above(px, py, pz))
    }

    pub fn shift(&mut self, dx: i32, dy: i32, dz: i32) -> bool {
        let amount = BlockVector3::at(dx, dy, dz);
        if amount == BlockVector3::ZERO || self.origin.is_none() {
            return false;
        }
        for v in &mut self.vertices {
            *v = v.add(amount);
        }
        for v in &mut self.backlog {
            *v = v.add(amount);
        }
        for f in &mut self.faces {
            *f = f.shift(amount);
        }
        self.origin = self.origin.map(|o| o.add(amount));
        self.minimum = self.minimum.map(|m| m.add(amount));
        self.maximum = self.maximum.map(|m| m.add(amount));
        true
    }

    pub fn describe(&self) -> String {
        format!(
            "convex ({} vertices, {} faces)",
            self.vertices().len(),
            self.faces.len()
        )
    }

    pub fn for_each_position(&self, mut visit: impl FnMut(i32, i32, i32)) -> u64 {
        if !self.is_defined() {
            return 0;
        }
        let min = self.min();
        let max = self.max();
        let mut visited = 0u64;
        for x in min.x..=max.x {
            for z in min.z..=max.z {
                if let Some((y0, y1)) = self.column_range(x, z, min.y, max.y) {
                    for y in y0..=y1 {
                        visit(x, y, z);
                        visited += 1;
                    }
                }
            }
        }
        visited
    }
}

/// FAWE's hollow polyhedral skin region (`region/PolyhedralRegion.java`).
#[derive(Clone, Debug)]
pub struct PolyhedralRegion {
    hull: ConvexPolyhedralRegion,
    skin: Option<HashSet<i64>>,
    ordered: Option<Vec<i64>>,
}

impl PolyhedralRegion {
    pub fn new(min_y: i32, max_y: i32) -> Self {
        let mut hull = ConvexPolyhedralRegion::new();
        hull.set_bounds(min_y, max_y);
        Self {
            hull,
            skin: None,
            ordered: None,
        }
    }

    pub fn add_vertex(&mut self, vertex: BlockVector3) -> Result<bool, String> {
        let changed = self.hull.add_vertex(vertex)?;
        if changed {
            self.skin = None;
            self.ordered = None;
        }
        Ok(changed)
    }

    pub fn vertices(&self) -> Vec<BlockVector3> {
        self.hull.vertices()
    }

    pub fn is_defined(&self) -> bool {
        self.hull.is_defined()
    }

    pub fn clear(&mut self) {
        self.hull.clear();
        self.skin = None;
        self.ordered = None;
    }

    pub fn min(&self) -> BlockVector3 {
        self.hull.min()
    }

    pub fn max(&self) -> BlockVector3 {
        self.hull.max()
    }

    fn build_skin(&self) -> HashSet<i64> {
        let mut found = HashSet::new();
        if !self.hull.is_defined() {
            return found;
        }
        let min = self.min();
        let max = self.max();
        for [a, b, c] in self.hull.triangles() {
            Self::sweep(a, b, c, min, max, &mut found);
        }
        found
    }

    fn ensure_skin(&mut self) {
        if self.skin.is_none() {
            let s = self.build_skin();
            let raw: Vec<i64> = s.iter().copied().collect();
            self.ordered = Some(block_keys::chunk_order(&raw));
            self.skin = Some(s);
        }
    }

    pub fn volume(&self) -> u64 {
        if !self.is_defined() {
            return 0;
        }
        match &self.skin {
            Some(s) => s.len() as u64,
            None => self.build_skin().len() as u64,
        }
    }

    pub fn contains(&self, x: i32, y: i32, z: i32) -> bool {
        if !self.is_defined() {
            return false;
        }
        let min = self.min();
        let max = self.max();
        if x < min.x || y < min.y || z < min.z || x > max.x || y > max.y || z > max.z {
            return false;
        }
        let k = block_keys::key(x, y, z);
        match &self.skin {
            Some(s) => s.contains(&k),
            None => self.build_skin().contains(&k),
        }
    }

    fn sweep(
        a: BlockVector3,
        b: BlockVector3,
        c: BlockVector3,
        min: BlockVector3,
        max: BlockVector3,
        out: &mut HashSet<i64>,
    ) {
        let v0 = [a.x as f64, a.y as f64, a.z as f64];
        let v1 = [b.x as f64, b.y as f64, b.z as f64];
        let v2 = [c.x as f64, c.y as f64, c.z as f64];
        let e1 = [v1[0] - v0[0], v1[1] - v0[1], v1[2] - v0[2]];
        let e2 = [v2[0] - v0[0], v2[1] - v0[1], v2[2] - v0[2]];
        let normal = [
            e1[1] * e2[2] - e1[2] * e2[1],
            e1[2] * e2[0] - e1[0] * e2[2],
            e1[0] * e2[1] - e1[1] * e2[0],
        ];
        if normal[0] == 0.0 && normal[1] == 0.0 && normal[2] == 0.0 {
            return;
        }
        let axis = if normal[0].abs() >= normal[1].abs() && normal[0].abs() >= normal[2].abs() {
            0
        } else if normal[1].abs() >= normal[2].abs() {
            1
        } else {
            2
        };
        let first = usize::from(axis == 0);
        let second = if axis == 2 { 1 } else { 2 };
        let low = [min.x, min.y, min.z];
        let high = [max.x, max.y, max.z];
        let mut from = [0i32; 3];
        let mut to = [0i32; 3];
        for i in 0..3 {
            from[i] = low[i].max((v0[i].min(v1[i]).min(v2[i]).floor() as i32) - 1);
            to[i] = high[i].min(v0[i].max(v1[i]).max(v2[i]).floor() as i32);
        }
        let limit = normal[0] * v0[0] + normal[1] * v0[1] + normal[2] * v0[2];
        let mut cell = [0i32; 3];
        for u in from[first]..=to[first] {
            for v in from[second]..=to[second] {
                let mut lowest = f64::INFINITY;
                let mut highest = f64::NEG_INFINITY;
                for corner in 0..4 {
                    let cu = (u + (corner & 1)) as f64;
                    let cv = (v + (corner >> 1)) as f64;
                    let along = (limit - normal[first] * cu - normal[second] * cv) / normal[axis];
                    lowest = lowest.min(along);
                    highest = highest.max(along);
                }
                let start = from[axis].max((lowest.ceil() as i32) - 1);
                let end = to[axis].min(highest.floor() as i32);
                cell[first] = u;
                cell[second] = v;
                for w in start..=end {
                    cell[axis] = w;
                    if Self::touches(cell[0], cell[1], cell[2], v0, v1, v2) {
                        out.insert(block_keys::key(cell[0], cell[1], cell[2]));
                    }
                }
            }
        }
    }

    fn touches(x: i32, y: i32, z: i32, a: [f64; 3], b: [f64; 3], c: [f64; 3]) -> bool {
        const HALF: f64 = 0.5;
        let (cx, cy, cz) = (x as f64 + 0.5, y as f64 + 0.5, z as f64 + 0.5);
        let (ax, ay, az) = (a[0] - cx, a[1] - cy, a[2] - cz);
        let (bx, by, bz) = (b[0] - cx, b[1] - cy, b[2] - cz);
        let (qx, qy, qz) = (c[0] - cx, c[1] - cy, c[2] - cz);
        let sep_edge = |ex: f64, ey: f64, ez: f64| -> bool {
            let sep = |dx: f64, dy: f64, dz: f64| -> bool {
                let reach = HALF * (dx.abs() + dy.abs() + dz.abs());
                let pa = dx * ax + dy * ay + dz * az;
                let pb = dx * bx + dy * by + dz * bz;
                let pc = dx * qx + dy * qy + dz * qz;
                pa.min(pb).min(pc) > reach || pa.max(pb).max(pc) < -reach
            };
            sep(0.0, -ez, ey) || sep(ez, 0.0, -ex) || sep(-ey, ex, 0.0)
        };
        if sep_edge(bx - ax, by - ay, bz - az)
            || sep_edge(qx - bx, qy - by, qz - bz)
            || sep_edge(ax - qx, ay - qy, az - qz)
        {
            return false;
        }
        if ax.min(bx).min(qx) > HALF
            || ax.max(bx).max(qx) < -HALF
            || ay.min(by).min(qy) > HALF
            || ay.max(by).max(qy) < -HALF
            || az.min(bz).min(qz) > HALF
            || az.max(bz).max(qz) < -HALF
        {
            return false;
        }
        let (ex, ey, ez) = (bx - ax, by - ay, bz - az);
        let (fx, fy, fz) = (qx - bx, qy - by, qz - bz);
        let nx = ey * fz - ez * fy;
        let ny = ez * fx - ex * fz;
        let nz = ex * fy - ey * fx;
        let reach = HALF * (nx.abs() + ny.abs() + nz.abs());
        (nx * ax + ny * ay + nz * az).abs() <= reach
    }

    pub fn shift(&mut self, dx: i32, dy: i32, dz: i32) -> bool {
        let moved = self.hull.shift(dx, dy, dz);
        if moved {
            self.skin = None;
            self.ordered = None;
        }
        moved
    }

    pub fn describe(&self) -> String {
        format!("polyhedral ({} vertices)", self.vertices().len())
    }

    pub fn for_each_position(&mut self, mut visit: impl FnMut(i32, i32, i32)) -> u64 {
        if !self.is_defined() {
            return 0;
        }
        self.ensure_skin();
        let ordered = self.ordered.as_deref().unwrap_or(&[]);
        let mut count = 0u64;
        for &k in ordered {
            visit(block_keys::x(k), block_keys::y(k), block_keys::z(k));
            count += 1;
        }
        count
    }
}

/// FAWE's connected-block magic-wand selection (`region/FuzzyRegion.java`).
#[derive(Clone, Debug, Default)]
pub struct FuzzyRegion {
    blocks: HashSet<i64>,
    min_x: i32,
    min_y: i32,
    min_z: i32,
    max_x: i32,
    max_y: i32,
    max_z: i32,
}

impl FuzzyRegion {
    pub const MAX_DEPTH: usize = 256;
    pub const MAX_BLOCKS: usize = 2_000_000;

    pub fn new() -> Self {
        let mut r = Self::default();
        r.clear();
        r
    }

    pub fn clear(&mut self) {
        self.blocks.clear();
        self.min_x = i32::MAX;
        self.min_y = i32::MAX;
        self.min_z = i32::MAX;
        self.max_x = i32::MIN;
        self.max_y = i32::MIN;
        self.max_z = i32::MIN;
    }

    /// Breadth-first connected-block flood up to `MAX_DEPTH` steps.
    pub fn select<F>(&mut self, start: BlockVector3, lowest_y: i32, highest_y: i32, mut block_type: F) -> Result<bool, String>
    where
        F: FnMut(i32, i32, i32) -> Option<u32>,
    {
        if start.y < lowest_y || start.y > highest_y {
            return Ok(false);
        }
        let Some(target) = block_type(start.x, start.y, start.z) else {
            return Ok(false);
        };
        let mut seen: HashSet<i64> = HashSet::new();
        let mut queue: VecDeque<i64> = VecDeque::new();
        let first = block_keys::key(start.x, start.y, start.z);
        seen.insert(first);
        queue.push_back(first);

        const FACES: [(i32, i32, i32); 6] = [
            (1, 0, 0),
            (-1, 0, 0),
            (0, 1, 0),
            (0, -1, 0),
            (0, 0, 1),
            (0, 0, -1),
        ];

        for depth in 0..=Self::MAX_DEPTH {
            if queue.is_empty() {
                break;
            }
            let layer_len = queue.len();
            for _ in 0..layer_len {
                let k = queue.pop_front().expect("nonempty");
                if depth == Self::MAX_DEPTH {
                    continue;
                }
                let (x, y, z) = (block_keys::x(k), block_keys::y(k), block_keys::z(k));
                for (dx, dy, dz) in FACES {
                    let (nx, ny, nz) = (x + dx, y + dy, z + dz);
                    if ny < lowest_y || ny > highest_y {
                        continue;
                    }
                    let next = block_keys::key(nx, ny, nz);
                    if seen.contains(&next) || self.blocks.contains(&next) {
                        continue;
                    }
                    if block_type(nx, ny, nz) != Some(target) {
                        continue;
                    }
                    if seen.len() + self.blocks.len() >= Self::MAX_BLOCKS {
                        return Err(format!(
                            "More than {} connected blocks: that is more than an edit may hold in memory",
                            Self::MAX_BLOCKS
                        ));
                    }
                    seen.insert(next);
                    queue.push_back(next);
                }
            }
        }
        let mut added = false;
        for k in seen {
            if self.blocks.insert(k) {
                let (x, y, z) = (block_keys::x(k), block_keys::y(k), block_keys::z(k));
                self.min_x = self.min_x.min(x);
                self.min_y = self.min_y.min(y);
                self.min_z = self.min_z.min(z);
                self.max_x = self.max_x.max(x);
                self.max_y = self.max_y.max(y);
                self.max_z = self.max_z.max(z);
                added = true;
            }
        }
        Ok(added)
    }

    pub fn min(&self) -> BlockVector3 {
        if self.blocks.is_empty() {
            BlockVector3::ZERO
        } else {
            BlockVector3::at(self.min_x, self.min_y, self.min_z)
        }
    }

    pub fn max(&self) -> BlockVector3 {
        if self.blocks.is_empty() {
            BlockVector3::ZERO
        } else {
            BlockVector3::at(self.max_x, self.max_y, self.max_z)
        }
    }

    pub fn volume(&self) -> u64 {
        self.blocks.len() as u64
    }

    pub fn contains(&self, x: i32, y: i32, z: i32) -> bool {
        self.blocks.contains(&block_keys::key(x, y, z))
    }

    pub fn describe(&self) -> String {
        let v = self.volume();
        format!("fuzzy ({v} {})", if v == 1 { "block" } else { "blocks" })
    }

    pub fn for_each_position(&self, mut visit: impl FnMut(i32, i32, i32)) -> u64 {
        let raw: Vec<i64> = self.blocks.iter().copied().collect();
        let ordered = block_keys::chunk_order(&raw);
        for &k in &ordered {
            visit(block_keys::x(k), block_keys::y(k), block_keys::z(k));
        }
        ordered.len() as u64
    }
}

/// Unified enum over every region shape in FAWE-BIM (`region/Region.java`).
#[derive(Clone, Debug)]
pub enum RegionShape {
    Cuboid(CuboidRegion),
    Cylinder(CylinderRegion),
    Ellipsoid(EllipsoidRegion),
    Polygon(Polygonal2DRegion),
    Convex(ConvexPolyhedralRegion),
    Polyhedral(PolyhedralRegion),
    Fuzzy(FuzzyRegion),
}

impl RegionShape {
    pub fn min(&self) -> BlockVector3 {
        match self {
            Self::Cuboid(r) => r.min(),
            Self::Cylinder(r) => r.min(),
            Self::Ellipsoid(r) => r.min(),
            Self::Polygon(r) => r.min(),
            Self::Convex(r) => r.min(),
            Self::Polyhedral(r) => r.min(),
            Self::Fuzzy(r) => r.min(),
        }
    }

    pub fn max(&self) -> BlockVector3 {
        match self {
            Self::Cuboid(r) => r.max(),
            Self::Cylinder(r) => r.max(),
            Self::Ellipsoid(r) => r.max(),
            Self::Polygon(r) => r.max(),
            Self::Convex(r) => r.max(),
            Self::Polyhedral(r) => r.max(),
            Self::Fuzzy(r) => r.max(),
        }
    }

    pub fn center(&self) -> Vector3 {
        let min = self.min();
        let max = self.max();
        Vector3::new(
            (min.x as f64 + max.x as f64) * 0.5,
            (min.y as f64 + max.y as f64) * 0.5,
            (min.z as f64 + max.z as f64) * 0.5,
        )
    }

    pub fn width(&self) -> u64 {
        let (min, max) = (self.min(), self.max());
        (max.x as i64 - min.x as i64 + 1).max(0) as u64
    }

    pub fn height(&self) -> u64 {
        let (min, max) = (self.min(), self.max());
        (max.y as i64 - min.y as i64 + 1).max(0) as u64
    }

    pub fn length(&self) -> u64 {
        let (min, max) = (self.min(), self.max());
        (max.z as i64 - min.z as i64 + 1).max(0) as u64
    }

    pub fn volume(&self) -> u64 {
        match self {
            Self::Cuboid(r) => r.volume(),
            Self::Cylinder(r) => r.volume(),
            Self::Ellipsoid(r) => r.volume(),
            Self::Polygon(r) => r.volume(),
            Self::Convex(r) => r.volume(),
            Self::Polyhedral(r) => r.volume(),
            Self::Fuzzy(r) => r.volume(),
        }
    }

    pub fn contains(&self, x: i32, y: i32, z: i32) -> bool {
        match self {
            Self::Cuboid(r) => r.contains(x, y, z),
            Self::Cylinder(r) => r.contains(x, y, z),
            Self::Ellipsoid(r) => r.contains(x, y, z),
            Self::Polygon(r) => r.contains(x, y, z),
            Self::Convex(r) => r.contains(x, y, z),
            Self::Polyhedral(r) => r.contains(x, y, z),
            Self::Fuzzy(r) => r.contains(x, y, z),
        }
    }

    pub fn expand(&mut self, amounts: &[BlockVector3]) -> Result<bool, String> {
        match self {
            Self::Cuboid(r) => {
                let mut changed = false;
                for a in amounts {
                    changed |= r.expand(a.x, a.y, a.z);
                }
                Ok(changed)
            }
            Self::Cylinder(r) => r.expand(amounts),
            Self::Ellipsoid(r) => r.expand(amounts),
            Self::Polygon(r) => {
                let mut changed = false;
                for a in amounts {
                    changed |= r.expand(a.x, a.y, a.z)?;
                }
                Ok(changed)
            }
            Self::Convex(_) => Err("A convex selection cannot be expanded".to_string()),
            Self::Polyhedral(_) => Err("A polyhedral selection cannot be expanded".to_string()),
            Self::Fuzzy(_) => Err("A fuzzy selection cannot be expanded".to_string()),
        }
    }

    pub fn contract(&mut self, amounts: &[BlockVector3]) -> Result<bool, String> {
        match self {
            Self::Cuboid(r) => {
                let mut changed = false;
                for a in amounts {
                    changed |= r.contract(a.x, a.y, a.z);
                }
                Ok(changed)
            }
            Self::Cylinder(r) => r.contract(amounts),
            Self::Ellipsoid(r) => r.contract(amounts),
            Self::Polygon(r) => {
                let mut changed = false;
                for a in amounts {
                    changed |= r.contract(a.x, a.y, a.z)?;
                }
                Ok(changed)
            }
            Self::Convex(_) => Err("A convex selection cannot be contracted".to_string()),
            Self::Polyhedral(_) => Err("A polyhedral selection cannot be contracted".to_string()),
            Self::Fuzzy(_) => Err("A fuzzy selection cannot be contracted".to_string()),
        }
    }

    pub fn shift(&mut self, dx: i32, dy: i32, dz: i32) -> Result<bool, String> {
        match self {
            Self::Cuboid(r) => Ok(r.shift(dx, dy, dz)),
            Self::Cylinder(r) => Ok(r.shift(dx, dy, dz)),
            Self::Ellipsoid(r) => Ok(r.shift(dx, dy, dz)),
            Self::Polygon(r) => Ok(r.shift(dx, dy, dz)),
            Self::Convex(r) => Ok(r.shift(dx, dy, dz)),
            Self::Polyhedral(r) => Ok(r.shift(dx, dy, dz)),
            Self::Fuzzy(_) => Err("A fuzzy selection cannot be shifted".to_string()),
        }
    }

    pub fn describe(&self) -> String {
        match self {
            Self::Cuboid(r) => r.describe(),
            Self::Cylinder(r) => r.describe(),
            Self::Ellipsoid(r) => r.describe(),
            Self::Polygon(r) => r.describe(),
            Self::Convex(r) => r.describe(),
            Self::Polyhedral(r) => r.describe(),
            Self::Fuzzy(r) => r.describe(),
        }
    }

    pub fn for_each_position(&mut self, visit: impl FnMut(i32, i32, i32)) -> u64 {
        match self {
            Self::Cuboid(r) => r.for_each_position(visit),
            Self::Cylinder(r) => r.for_each_position(visit),
            Self::Ellipsoid(r) => r.for_each_position(visit),
            Self::Polygon(r) => r.for_each_position(visit),
            Self::Convex(r) => r.for_each_position(visit),
            Self::Polyhedral(r) => r.for_each_position(visit),
            Self::Fuzzy(r) => r.for_each_position(visit),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn cuboid() -> CuboidRegion {
        CuboidRegion::new(BlockVector3::at(1, 2, 3), BlockVector3::at(4, 6, 8))
    }

    #[test]
    fn corners_are_normalized_either_order() {
        let a = BlockVector3::at(4, 6, 8);
        let b = BlockVector3::at(1, 2, 3);
        let region = CuboidRegion::new(a, b);
        assert_eq!(region.min(), BlockVector3::at(1, 2, 3));
        assert_eq!(region.max(), BlockVector3::at(4, 6, 8));

        let mut flipped = region;
        flipped.set_pos1(BlockVector3::at(10, 10, 10));
        flipped.set_pos2(BlockVector3::at(0, 0, 0));
        assert_eq!(flipped.min(), BlockVector3::ZERO);
        assert_eq!(flipped.max(), BlockVector3::at(4, 6, 8));
    }

    #[test]
    fn sizes_volume_area_and_chunks() {
        let region = cuboid();
        assert_eq!(region.width(), 4);
        assert_eq!(region.height(), 5);
        assert_eq!(region.length(), 6);
        assert_eq!(region.area(), 24);
        assert_eq!(region.volume(), 120);
        assert_eq!(region.chunk_count(), 1);
        assert!(!region.is_flat());
        assert_eq!(region.describe(), "cuboid: 4x5x6");
    }

    #[test]
    fn expand_contract_shift_move_the_bounds() {
        let mut region = cuboid();
        assert!(region.expand(2, -1, 1));
        assert_eq!(region.min(), BlockVector3::at(1, 1, 3));
        assert_eq!(region.max(), BlockVector3::at(6, 6, 9));
        assert!(region.contract(1, 1, 1));
        assert_eq!(region.min(), BlockVector3::at(2, 2, 4));
        assert_eq!(region.max(), BlockVector3::at(6, 6, 9));
        assert!(region.shift(10, 0, -10));
        assert_eq!(region.min(), BlockVector3::at(12, 2, -6));
        assert_eq!(region.max(), BlockVector3::at(16, 6, -1));
        let mut small = CuboidRegion::new(BlockVector3::at(0, 0, 0), BlockVector3::at(1, 1, 1));
        assert!(small.contract(3, 0, 0));
        assert_eq!(small.min(), BlockVector3::at(1, 0, 0));
        assert_eq!(small.max(), BlockVector3::at(3, 1, 1));
    }

    #[test]
    fn contains_is_inclusive_on_both_corners() {
        let region = cuboid();
        assert!(region.contains(1, 2, 3));
        assert!(region.contains(4, 6, 8));
        assert!(!region.contains(0, 2, 3));
        assert!(!region.contains(4, 7, 8));
    }

    #[test]
    fn for_each_position_visits_each_block_once() {
        let region = CuboidRegion::new(BlockVector3::at(0, 0, 0), BlockVector3::at(17, 1, 17));
        let mut sum = 0i64;
        let mut count = 0u64;
        region.for_each_position(|x, y, z| {
            sum += x as i64 + y as i64 + z as i64;
            count += 1;
        });
        assert_eq!(count, region.volume());
        assert_eq!(
            sum,
            (0..18i64).sum::<i64>() * 18 * 2 * 2 + 18 * 18 * (0..2i64).sum::<i64>()
        );
    }

    #[test]
    fn cylinder_and_ellipsoid_include_plus_half_shell() {
        let cyl = CylinderRegion::new(Vector2::new(0.5, 0.5), 3.0, 3.0, 10, 12);
        assert!(cyl.contains(3, 10, 0));
        assert!(!cyl.contains(4, 10, 0));
        assert!(!cyl.contains(0, 9, 0));

        let sph = EllipsoidRegion::new(Vector3::new(0.5, 0.5, 0.5), Vector3::new(3.0, 3.0, 3.0));
        assert!(sph.contains(3, 0, 0));
        assert!(!sph.contains(4, 0, 0));
    }

    #[test]
    fn polygon_exact_edges_and_scanline_match_contains() {
        let square = Polygonal2DRegion::new(
            vec![
                BlockVector2::at(0, 0),
                BlockVector2::at(0, 10),
                BlockVector2::at(10, 10),
                BlockVector2::at(10, 0),
            ],
            64,
            65,
        )
        .unwrap();
        assert_eq!(square.volume(), 11 * 11 * 2);
        let mut walked = 0u64;
        square.for_each_position(|x, y, z| {
            assert!(square.contains(x, y, z));
            walked += 1;
        });
        assert_eq!(walked, square.volume());
    }

    #[test]
    fn convex_and_polyhedral_tetrahedron_work() {
        let mut convex = ConvexPolyhedralRegion::new();
        convex.add_vertex(BlockVector3::at(0, 0, 0)).unwrap();
        convex.add_vertex(BlockVector3::at(6, 0, 0)).unwrap();
        convex.add_vertex(BlockVector3::at(0, 6, 0)).unwrap();
        convex.add_vertex(BlockVector3::at(0, 0, 6)).unwrap();
        assert!(convex.is_defined());
        assert!(convex.contains(1, 1, 1));
        assert!(!convex.contains(5, 5, 5));

        let mut poly = PolyhedralRegion::new(-64, 319);
        poly.add_vertex(BlockVector3::at(0, 0, 0)).unwrap();
        poly.add_vertex(BlockVector3::at(6, 0, 0)).unwrap();
        poly.add_vertex(BlockVector3::at(0, 6, 0)).unwrap();
        poly.add_vertex(BlockVector3::at(0, 0, 6)).unwrap();
        // Skin volume strictly smaller than solid convex hull for large enough tetrahedron,
        // or at least all vertices belong to skin.
        assert!(poly.contains(0, 0, 0));
        assert!(poly.volume() > 0);
    }

    #[test]
    fn fuzzy_flood_fills_connected_same_type() {
        let mut fuzzy = FuzzyRegion::new();
        fuzzy
            .select(BlockVector3::at(0, 0, 0), -10, 10, |x, y, z| {
                if (0..=2).contains(&x) && y == 0 && z == 0 {
                    Some(7)
                } else {
                    Some(1)
                }
            })
            .unwrap();
        assert_eq!(fuzzy.volume(), 3);
        assert!(fuzzy.contains(2, 0, 0));
        assert!(!fuzzy.contains(3, 0, 0));
    }
}
