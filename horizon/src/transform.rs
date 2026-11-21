//! Coordinate and block-state transforms, ported from `transform/` of the Java
//! reference (`Axis.java`, `Transform.java`, `Transforms.java`,
//! `BlockStateTransform.java`).

use std::collections::{BTreeMap, HashMap};

use crate::math::{BlockVector3, Vector3};
use crate::noise::JavaRandom;

/// Coordinate axis (`transform/Axis.java`).
#[derive(Clone, Copy, PartialEq, Eq, Hash, Debug)]
pub enum Axis {
    X,
    Y,
    Z,
}

impl Axis {
    pub fn parse(input: &str) -> Result<Self, String> {
        match input.trim().to_ascii_lowercase().as_str() {
            "x" => Ok(Self::X),
            "y" => Ok(Self::Y),
            "z" => Ok(Self::Z),
            _ => Err(format!("Unknown axis '{input}'; expected x, y or z")),
        }
    }

    pub const fn as_str(self) -> &'static str {
        match self {
            Self::X => "x",
            Self::Y => "y",
            Self::Z => "z",
        }
    }
}

/// Exact cosine on 90-degree multiples (`Transforms.cos`).
pub fn cos_deg(degrees: f64) -> f64 {
    let turn = degrees % 360.0;
    if turn % 90.0 == 0.0 {
        let quarter = ((turn / 90.0) as i64).rem_euclid(4);
        return match quarter {
            0 => 1.0,
            2 => -1.0,
            _ => 0.0,
        };
    }
    degrees.to_radians().cos()
}

/// Exact sine on 90-degree multiples (`Transforms.sin`).
pub fn sin_deg(degrees: f64) -> f64 {
    let turn = degrees % 360.0;
    if turn % 90.0 == 0.0 {
        let quarter = ((turn / 90.0) as i64).rem_euclid(4);
        return match quarter {
            1 => 1.0,
            3 => -1.0,
            _ => 0.0,
        };
    }
    degrees.to_radians().sin()
}

/// A single step or chain in FAWE's transform pipeline (`transform/Transform.java`).
#[derive(Clone, Debug)]
pub enum Transform {
    Identity,
    Rotate {
        origin: BlockVector3,
        axis: Axis,
        cos: f64,
        sin: f64,
    },
    Flip {
        origin: BlockVector3,
        axis: Axis,
    },
    Scale {
        origin: BlockVector3,
        x: f64,
        y: f64,
        z: f64,
    },
    Offset {
        dx: f64,
        dy: f64,
        dz: f64,
    },
    RandomOffset {
        dx: i32,
        dy: i32,
        dz: i32,
        seed: u64,
    },
    Chain(Vec<Transform>),
}

impl Default for Transform {
    fn default() -> Self {
        Self::Identity
    }
}

impl Transform {
    pub fn identity() -> Self {
        Self::Identity
    }

    pub fn rotate(origin: BlockVector3, axis: Axis, degrees: f64) -> Self {
        Self::Rotate {
            origin,
            axis,
            cos: cos_deg(degrees),
            sin: sin_deg(degrees),
        }
    }

    pub fn rotate_y(origin: BlockVector3, degrees: f64) -> Self {
        Self::rotate(origin, Axis::Y, degrees)
    }

    pub fn flip(origin: BlockVector3, axis: Axis) -> Self {
        Self::Flip { origin, axis }
    }

    pub fn scale(origin: BlockVector3, x: f64, y: f64, z: f64) -> Self {
        Self::Scale { origin, x, y, z }
    }

    pub fn offset(dx: f64, dy: f64, dz: f64) -> Self {
        Self::Offset { dx, dy, dz }
    }

    pub fn random_offset(dx: i32, dy: i32, dz: i32, seed: u64) -> Self {
        Self::RandomOffset {
            dx: dx.abs(),
            dy: dy.abs(),
            dz: dz.abs(),
            seed,
        }
    }

    pub fn combine(self, next: Self) -> Self {
        match (self, next) {
            (Self::Identity, other) | (other, Self::Identity) => other,
            (Self::Chain(mut a), Self::Chain(b)) => {
                a.extend(b);
                Self::Chain(a)
            }
            (Self::Chain(mut a), b) => {
                a.push(b);
                Self::Chain(a)
            }
            (a, Self::Chain(mut b)) => {
                b.insert(0, a);
                Self::Chain(b)
            }
            (a, b) => Self::Chain(vec![a, b]),
        }
    }

    pub fn is_identity(&self) -> bool {
        match self {
            Self::Identity => true,
            Self::Chain(list) => list.iter().all(Self::is_identity),
            _ => false,
        }
    }

    pub fn apply(&self, point: Vector3) -> Vector3 {
        match self {
            Self::Identity => point,
            Self::Rotate {
                origin,
                axis,
                cos,
                sin,
            } => {
                let x = point.x - origin.x as f64;
                let y = point.y - origin.y as f64;
                let z = point.z - origin.z as f64;
                let (nx, ny, nz) = match axis {
                    Axis::X => (x, y * cos - z * sin, y * sin + z * cos),
                    Axis::Y => (x * cos + z * sin, y, -x * sin + z * cos),
                    Axis::Z => (x * cos - y * sin, x * sin + y * cos, z),
                };
                Vector3::new(
                    nx + origin.x as f64,
                    ny + origin.y as f64,
                    nz + origin.z as f64,
                )
            }
            Self::Flip { origin, axis } => {
                let x = point.x - origin.x as f64;
                let y = point.y - origin.y as f64;
                let z = point.z - origin.z as f64;
                match axis {
                    Axis::X => Vector3::new(-x + origin.x as f64, point.y, point.z),
                    Axis::Y => Vector3::new(point.x, -y + origin.y as f64, point.z),
                    Axis::Z => Vector3::new(point.x, point.y, -z + origin.z as f64),
                }
            }
            Self::Scale { origin, x, y, z } => Vector3::new(
                (point.x - origin.x as f64) * x + origin.x as f64,
                (point.y - origin.y as f64) * y + origin.y as f64,
                (point.z - origin.z as f64) * z + origin.z as f64,
            ),
            Self::Offset { dx, dy, dz } => point.add_xyz(*dx, *dy, *dz),
            Self::RandomOffset { dx, dy, dz, seed } => {
                // Position-seeded JavaRandom so repeated evaluation is pure & thread-safe.
                let pos_hash = (point.x.to_bits() as i64)
                    .wrapping_mul(3129871)
                    .wrapping_add((point.y.to_bits() as i64).wrapping_mul(116129781))
                    .wrapping_add(point.z.to_bits() as i64)
                    ^ (*seed as i64);
                let mut rng = JavaRandom::new(pos_hash);
                let ox = if *dx == 0 {
                    0
                } else {
                    rng.next_int_below(*dx * 2 + 1) - *dx
                };
                let oy = if *dy == 0 {
                    0
                } else {
                    rng.next_int_below(*dy * 2 + 1) - *dy
                };
                let oz = if *dz == 0 {
                    0
                } else {
                    rng.next_int_below(*dz * 2 + 1) - *dz
                };
                point.add_xyz(ox as f64, oy as f64, oz as f64)
            }
            Self::Chain(list) => list.iter().fold(point, |acc, step| step.apply(acc)),
        }
    }

    pub fn apply_direction(&self, direction: Vector3) -> Vector3 {
        match self {
            Self::Identity | Self::Offset { .. } | Self::RandomOffset { .. } => direction,
            Self::Chain(list) => list
                .iter()
                .fold(direction, |acc, step| step.apply_direction(acc)),
            _ => self.apply(direction).subtract(self.apply(Vector3::ZERO)),
        }
    }
}

/// Property-level block state access used by [`BlockStateTransform`].
pub trait StatePropertyAccess {
    fn properties(&self, state: u32) -> BTreeMap<String, String>;
    fn property_defs(&self, state: u32) -> BTreeMap<String, Vec<String>>;
    fn with_property(&mut self, state: u32, property: &str, value: &str) -> Option<u32>;
}

const NAMES: [&str; 6] = ["north", "east", "south", "west", "up", "down"];
const VECTORS: [[f64; 3]; 6] = [
    [0.0, 0.0, -1.0],
    [1.0, 0.0, 0.0],
    [0.0, 0.0, 1.0],
    [-1.0, 0.0, 0.0],
    [0.0, 1.0, 0.0],
    [0.0, -1.0, 0.0],
];
const UP_IDX: usize = 4;

/// Turns block states (`facing`, `axis`, `rotation`, `shape`, `orientation`,
/// `hanging`, `half`, `hinge`, side connections) to match a [`Transform`].
/// Port of `transform/BlockStateTransform.java`.
#[derive(Clone, Debug)]
pub struct BlockStateTransform {
    transform: Transform,
    turned: [[f64; 3]; 6],
    becomes: [isize; 6],
    mirrored: bool,
    upside_down: bool,
    cache: HashMap<u32, u32>,
}

impl BlockStateTransform {
    /// Returns `None` when `transform` turns no direction and so changes no state.
    pub fn of(transform: &Transform) -> Option<Self> {
        if transform.is_identity() {
            return None;
        }
        let mut turned = [[0.0; 3]; 6];
        let mut becomes = [-1isize; 6];
        for i in 0..6 {
            let v = transform.apply_direction(Vector3::new(
                VECTORS[i][0],
                VECTORS[i][1],
                VECTORS[i][2],
            ));
            turned[i] = [v.x, v.y, v.z];
            becomes[i] = exact_direction(turned[i]);
        }
        if (0..6).all(|i| becomes[i] == i as isize) {
            return None;
        }
        let east = turned[1];
        let south = turned[2];
        let mirrored = east[0] * south[2] - east[2] * south[0] < 0.0;
        let upside_down = turned[UP_IDX][1] < 0.0;
        Some(Self {
            transform: transform.clone(),
            turned,
            becomes,
            mirrored,
            upside_down,
            cache: HashMap::new(),
        })
    }

    pub fn apply(&mut self, registry: &mut dyn StatePropertyAccess, state: u32) -> u32 {
        if let Some(&cached) = self.cache.get(&state) {
            return cached;
        }
        let computed = self.compute(registry, state);
        self.cache.insert(state, computed);
        computed
    }

    fn compute(&self, registry: &mut dyn StatePropertyAccess, state: u32) -> u32 {
        let properties = registry.properties(state);
        if properties.is_empty() {
            return state;
        }
        let definitions = registry.property_defs(state);
        let mut next: BTreeMap<String, String> = BTreeMap::new();
        for (name, value) in &properties {
            if index_of_dir(name).is_some() {
                continue;
            }
            let empty = Vec::new();
            let allowed = definitions.get(name).unwrap_or(&empty);
            if let Some(turned_val) = self.turn(name, value, allowed)
                && &turned_val != value
                && allowed.contains(&turned_val)
            {
                next.insert(name.clone(), turned_val);
            }
        }
        for i in 0..6 {
            let target = self.becomes[i];
            if target >= 0
                && target as usize != i
                && let Some(val) = properties.get(NAMES[i])
                && properties.contains_key(NAMES[target as usize])
            {
                next.insert(NAMES[target as usize].to_string(), val.clone());
            }
        }
        let mut result = state;
        for (key, val) in next {
            if registry.properties(result).get(&key) == Some(&val) {
                continue;
            }
            if let Some(changed) = registry.with_property(result, &key, &val) {
                result = changed;
            }
        }
        result
    }

    fn turn(&self, name: &str, value: &str, allowed: &[String]) -> Option<String> {
        if all_directions(allowed) {
            let dir = index_of_dir(value)?;
            return nearest_slice(self.turned[dir], allowed);
        }
        match name {
            "axis" => {
                let axis_vec = match value {
                    "x" => VECTORS[1],
                    "y" => VECTORS[UP_IDX],
                    "z" => VECTORS[2],
                    _ => return None,
                };
                let v = self.transform_dir(axis_vec);
                let (ax, ay, az) = (v[0].abs(), v[1].abs(), v[2].abs());
                Some(
                    if ax >= ay && ax >= az {
                        "x"
                    } else if ay >= az {
                        "y"
                    } else {
                        "z"
                    }
                    .to_string(),
                )
            }
            "rotation" => self.turn_rotation(value),
            "shape" => {
                if value.contains("left") || value.contains("right") {
                    self.mirror_word(value)
                } else {
                    self.rail_shape(value, allowed)
                }
            }
            "orientation" => {
                let (front_s, top_s) = value.split_once('_')?;
                let front = index_of_dir(front_s)?;
                let top = index_of_dir(top_s)?;
                let front_name = nearest_strs(self.turned[front], &NAMES)?;
                let top_name = nearest_strs(self.turned[top], &NAMES)?;
                let candidate = format!("{front_name}_{top_name}");
                if allowed.contains(&candidate) {
                    return Some(candidate);
                }
                let prefix = format!("{front_name}_");
                allowed.iter().find(|c| c.starts_with(&prefix)).cloned()
            }
            "hanging" => {
                if !self.upside_down {
                    return None;
                }
                let parsed: bool = value.parse().ok()?;
                Some((!parsed).to_string())
            }
            _ => {
                if value == "left" || value == "right" {
                    return self.mirror_word(value);
                }
                if !self.upside_down {
                    return None;
                }
                match value {
                    "top" => Some("bottom".to_string()),
                    "bottom" => Some("top".to_string()),
                    "upper" => Some("lower".to_string()),
                    "lower" => Some("upper".to_string()),
                    "floor" => Some("ceiling".to_string()),
                    "ceiling" => Some("floor".to_string()),
                    _ => None,
                }
            }
        }
    }

    fn mirror_word(&self, value: &str) -> Option<String> {
        if !self.mirrored {
            return None;
        }
        if value.contains("left") {
            Some(value.replace("left", "right"))
        } else {
            Some(value.replace("right", "left"))
        }
    }

    fn turn_rotation(&self, value: &str) -> Option<String> {
        let step: i32 = value.parse().ok()?;
        let angle = step as f64 * std::f64::consts::PI / 8.0;
        let v = self.transform_dir([-angle.sin(), 0.0, angle.cos()]);
        if v[0].abs() < 1e-9 && v[2].abs() < 1e-9 {
            return None;
        }
        let turned_step = ((-v[0]).atan2(v[2]) / (std::f64::consts::PI / 8.0)).round() as i64;
        Some(turned_step.rem_euclid(16).to_string())
    }

    fn rail_shape(&self, value: &str, allowed: &[String]) -> Option<String> {
        let horizontal = ["north", "east", "south", "west"];
        if let Some(dir_part) = value.strip_prefix("ascending_") {
            let d = index_of_dir(dir_part).filter(|&i| i < UP_IDX)?;
            let turned_name = nearest_strs(self.turned[d], &horizontal)?;
            return Some(format!("ascending_{turned_name}"));
        }
        let parts: Vec<&str> = value.split('_').collect();
        if parts.len() != 2 {
            return None;
        }
        let mut dirs = [0usize; 2];
        for (idx, p) in parts.iter().enumerate() {
            let d = index_of_dir(p).filter(|&i| i < UP_IDX)?;
            let turned_name = nearest_strs(self.turned[d], &horizontal)?;
            dirs[idx] = index_of_dir(turned_name)?;
        }
        let first_ns = dirs[0] % 2 == 0;
        let second_ns = dirs[1] % 2 == 0;
        if first_ns && second_ns {
            return Some("north_south".to_string());
        }
        if !first_ns && !second_ns {
            return Some("east_west".to_string());
        }
        let ns = NAMES[if first_ns { dirs[0] } else { dirs[1] }];
        let ew = NAMES[if first_ns { dirs[1] } else { dirs[0] }];
        let shape = format!("{ns}_{ew}");
        allowed.contains(&shape).then_some(shape)
    }

    fn transform_dir(&self, d: [f64; 3]) -> [f64; 3] {
        let v = self.transform.apply_direction(Vector3::new(d[0], d[1], d[2]));
        [v.x, v.y, v.z]
    }
}

fn nearest_slice(vector: [f64; 3], allowed: &[String]) -> Option<String> {
    let mut best: Option<&str> = None;
    let mut best_dot = 1e-9;
    for candidate in allowed {
        let Some(dir) = index_of_dir(candidate) else {
            continue;
        };
        let axis = VECTORS[dir];
        let dot = vector[0] * axis[0] + vector[1] * axis[1] + vector[2] * axis[2];
        if dot > best_dot {
            best_dot = dot;
            best = Some(candidate.as_str());
        }
    }
    best.map(ToOwned::to_owned)
}

fn nearest_strs<'a>(vector: [f64; 3], allowed: &[&'a str]) -> Option<&'a str> {
    let mut best: Option<&'a str> = None;
    let mut best_dot = 1e-9;
    for &candidate in allowed {
        let Some(dir) = index_of_dir(candidate) else {
            continue;
        };
        let axis = VECTORS[dir];
        let dot = vector[0] * axis[0] + vector[1] * axis[1] + vector[2] * axis[2];
        if dot > best_dot {
            best_dot = dot;
            best = Some(candidate);
        }
    }
    best
}

fn exact_direction(vector: [f64; 3]) -> isize {
    let len = (vector[0] * vector[0] + vector[1] * vector[1] + vector[2] * vector[2]).sqrt();
    if len < 1e-9 {
        return -1;
    }
    for (i, axis) in VECTORS.iter().enumerate() {
        let dot = (vector[0] * axis[0] + vector[1] * axis[1] + vector[2] * axis[2]) / len;
        if dot > 1.0 - 1e-6 {
            return i as isize;
        }
    }
    -1
}

fn all_directions(values: &[String]) -> bool {
    !values.is_empty() && values.iter().all(|v| index_of_dir(v).is_some())
}

fn index_of_dir(name: &str) -> Option<usize> {
    NAMES.iter().position(|&n| n == name)
}

#[cfg(test)]
mod tests {
    use super::*;

    struct MockStates {
        states: Vec<BTreeMap<String, String>>,
        defs: BTreeMap<String, Vec<String>>,
    }

    impl StatePropertyAccess for MockStates {
        fn properties(&self, state: u32) -> BTreeMap<String, String> {
            self.states[state as usize].clone()
        }

        fn property_defs(&self, _state: u32) -> BTreeMap<String, Vec<String>> {
            self.defs.clone()
        }

        fn with_property(&mut self, state: u32, property: &str, value: &str) -> Option<u32> {
            let mut next = self.states[state as usize].clone();
            next.insert(property.to_string(), value.to_string());
            if let Some(pos) = self.states.iter().position(|s| s == &next) {
                return Some(pos as u32);
            }
            self.states.push(next);
            Some((self.states.len() - 1) as u32)
        }
    }

    #[test]
    fn quarter_turn_is_exact_and_rotates_facing_and_shape() {
        let rot = Transform::rotate_y(BlockVector3::ZERO, 90.0);
        // (0, 0, -1) north rotated 90° around Y: nx = 0*0 + (-1)*1 = -1 -> west?
        // Wait: Transforms.java: nx = x*cos + z*sin, nz = -x*sin + z*cos.
        // For north (0, 0, -1): nx = -1 (west), nz = 0!
        //Wait: let's check east (1, 0, 0): nx = 0, nz = -1 (north), i.e. 90° CCW in math / 90° CW or CCW in MC!
        let p = rot.apply(Vector3::new(1.0, 0.0, 0.0));
        assert_eq!(p, Vector3::new(0.0, 0.0, -1.0));

        let mut mock = MockStates {
            states: vec![BTreeMap::from([
                ("facing".to_string(), "east".to_string()),
                ("half".to_string(), "bottom".to_string()),
            ])],
            defs: BTreeMap::from([
                (
                    "facing".to_string(),
                    vec!["north", "east", "south", "west"]
                        .into_iter()
                        .map(String::from)
                        .collect(),
                ),
                (
                    "half".to_string(),
                    vec!["top", "bottom"].into_iter().map(String::from).collect(),
                ),
            ]),
        };
        let mut bst = BlockStateTransform::of(&rot).expect("rotates directions");
        let turned = bst.apply(&mut mock, 0);
        assert_eq!(mock.properties(turned)["facing"], "north");

        let flip_x = Transform::flip(BlockVector3::ZERO, Axis::X);
        let mut bst_flip = BlockStateTransform::of(&flip_x).expect("flips X");
        let flipped = bst_flip.apply(&mut mock, 0);
        assert_eq!(mock.properties(flipped)["facing"], "west");
    }
}
