//! Region math, a port of the Java `region` package and `math/BlockVector3.java`
//! of the reference.
//!
//! This slice: [`BlockVector3`] and [`CuboidRegion`], the two-corner selection
//! (`//pos1` / `//pos2`) and everything every edit command walks. Other
//! shapes (cylinder, ellipsoid, polygons) follow the same `Region` contract
//! and are not ported yet.
//!
//! Positions are `i32`, as in the Java reference; sizes and volumes are `u64`,
//! because a cuboid can hold more blocks than an `i32` counts — and a
//! selection a player marks out cannot: [`CuboidRegion::MAX_VOLUME`] refuses
//! the edit before anything walks it.

/// An immutable integer block position, `(x, y, z)`. See `math/BlockVector3.java`.
#[derive(Clone, Copy, PartialEq, Eq, Hash, Debug, PartialOrd, Ord)]
pub struct BlockVector3 {
    pub x: i32,
    pub y: i32,
    pub z: i32,
}

impl BlockVector3 {
    pub const ZERO: Self = Self { x: 0, y: 0, z: 0 };

    pub const fn at(x: i32, y: i32, z: i32) -> Self {
        Self { x, y, z }
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
    /// is refused before anything walks it. FAWE delegates this to the
    /// session's `changeLimit` (unlimited by default); the port puts its own
    /// bound on the console's sake until the config lands.
    pub const MAX_VOLUME: u64 = 2_000_000;

    /// A cuboid around `a` and `b`, either order.
    pub fn new(a: BlockVector3, b: BlockVector3) -> Self {
        let mut region = Self { min: a, max: b };
        region.normalize();
        region
    }

    /// The lower and upper corners, in WorldEdit's order.
    pub fn min(&self) -> BlockVector3 {
        self.min
    }

    pub fn max(&self) -> BlockVector3 {
        self.max
    }

    /// Sets both corners at once, in either order.
    pub fn set_corners(&mut self, a: BlockVector3, b: BlockVector3) {
        self.min = a;
        self.max = b;
        self.normalize();
    }

    /// Sets the lower corner; a corner past the upper one flips the box on that axis.
    pub fn set_pos1(&mut self, pos: BlockVector3) {
        self.min = pos;
        self.normalize();
    }

    /// Sets the upper corner; a corner past the lower one flips the box on that axis.
    pub fn set_pos2(&mut self, pos: BlockVector3) {
        self.max = pos;
        self.normalize();
    }

    /// Keeps `min <= max` on every axis, swapping the corners that are out of order.
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
        (self.max.x - self.min.x) as u64 + 1
    }

    pub fn height(&self) -> u64 {
        (self.max.y - self.min.y) as u64 + 1
    }

    pub fn length(&self) -> u64 {
        (self.max.z - self.min.z) as u64 + 1
    }

    /// `width * length` — the XZ footprint.
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
        x >= self.min.x && x <= self.max.x
            && y >= self.min.y && y <= self.max.y
            && z >= self.min.z && z <= self.max.z
    }

    /// Expands the region in place by a signed amount per axis; a negative
    /// component moves the minimum, a positive one the maximum. `true` when any
    /// axis moved. Saturates at the world's edge rather than wrapping.
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

    /// The reverse of [`expand`](Self::expand): a positive component moves the
    /// minimum up, a negative one moves the maximum down. Overshoots flip and
    /// are normalized, as in the reference.
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

    /// Moves the region by an amount, keeping its shape.
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

    /// The short human description `//size` and the wand use: `cuboid: WxHxL`.
    pub fn describe(&self) -> String {
        format!("cuboid: {}x{}x{}", self.width(), self.height(), self.length())
    }

    /// Walks the region one chunk section at a time, in section index order;
    /// inside a section the walk is x first, then z, then y, the order the
    /// world stores its cells in, as in the Java reference.
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
        // A contraction past the opposite side flips the box, as in the reference.
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
        assert_eq!(sum, (0..18i64).sum::<i64>() * 18 * 2 * 2 + 18 * 18 * (0..2i64).sum::<i64>());
    }
}
