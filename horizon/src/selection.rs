//! Per-source selection store and all 9 FAWE/WorldEdit region selectors,
//! ported from `region/Selectors.java` and `session/LocalSession.java`.

use std::collections::HashMap;

use crate::math::{BlockVector2, BlockVector3, Vector2, Vector3};
use crate::region::{
    ConvexPolyhedralRegion, CuboidRegion, CylinderRegion, EllipsoidRegion, FuzzyRegion,
    Polygonal2DRegion, PolyhedralRegion, RegionShape,
};

/// Which source owns a selection or session.
#[derive(Clone, Copy, PartialEq, Eq, Hash, Debug)]
pub enum SelectionKey {
    /// A player, keyed by the UUID the host reports.
    Player(u128),
    /// The server console, a command block or a function.
    Console,
}

impl SelectionKey {
    pub const CONSOLE: Self = Self::Console;
}

/// Vertex limits for polygon and polyhedron selectors (`region/SelectorLimits.java`).
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct SelectorLimits {
    pub polygon_vertices: usize,
    pub polyhedron_vertices: usize,
}

impl Default for SelectorLimits {
    fn default() -> Self {
        Self {
            polygon_vertices: 1000,
            polyhedron_vertices: 1000,
        }
    }
}

/// The 9 selector modes of `//sel` (`region/Selectors.java`).
#[derive(Clone, Copy, PartialEq, Eq, Hash, Debug)]
pub enum SelectorKind {
    Cuboid,
    Extend,
    Poly,
    Ellipsoid,
    Sphere,
    Cyl,
    Convex,
    Polyhedral,
    Fuzzy,
}

impl SelectorKind {
    pub const ALL: [Self; 9] = [
        Self::Cuboid,
        Self::Extend,
        Self::Poly,
        Self::Ellipsoid,
        Self::Sphere,
        Self::Cyl,
        Self::Convex,
        Self::Polyhedral,
        Self::Fuzzy,
    ];

    pub fn parse(name: &str) -> Option<Self> {
        match name.trim().to_ascii_lowercase().as_str() {
            "cuboid" => Some(Self::Cuboid),
            "extend" => Some(Self::Extend),
            "poly" | "2d" | "polygon" => Some(Self::Poly),
            "ellipsoid" => Some(Self::Ellipsoid),
            "sphere" => Some(Self::Sphere),
            "cyl" | "cylinder" => Some(Self::Cyl),
            "convex" | "hull" | "polyhedron" => Some(Self::Convex),
            "polyhedral" => Some(Self::Polyhedral),
            "fuzzy" | "magic" => Some(Self::Fuzzy),
            _ => None,
        }
    }

    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Cuboid => "cuboid",
            Self::Extend => "extend",
            Self::Poly => "poly",
            Self::Ellipsoid => "ellipsoid",
            Self::Sphere => "sphere",
            Self::Cyl => "cyl",
            Self::Convex => "convex",
            Self::Polyhedral => "polyhedral",
            Self::Fuzzy => "fuzzy",
        }
    }

    pub const fn description(self) -> &'static str {
        match self {
            Self::Cuboid => "Select two corners of a cuboid",
            Self::Extend => "Fast cuboid selection mode",
            Self::Poly => "Select a 2D polygon with height",
            Self::Ellipsoid => "Select an ellipsoid",
            Self::Sphere => "Select a sphere",
            Self::Cyl => "Select a cylinder",
            Self::Convex => "Select a convex polyhedral",
            Self::Polyhedral => "Select a hollow polyhedral",
            Self::Fuzzy => "Select all connected blocks (magic wand)",
        }
    }

    pub const fn usage(self) -> &'static str {
        match self {
            Self::Cuboid => "Cuboid: left click for point 1, right click for point 2",
            Self::Extend => "Cuboid: left click for a starting point, right click to extend",
            Self::Poly => "2D polygon selector: left click starts a new polygon, right click adds a point",
            Self::Ellipsoid => "Ellipsoid selector: left click for the center, right click to extend",
            Self::Sphere => "Sphere selector: left click for the center, right click to set the radius",
            Self::Cyl => "Cylindrical selector: left click for the center, right click to extend",
            Self::Convex => "Convex polyhedral selector: left click for the first vertex, right click to add more",
            Self::Polyhedral => "Polyhedral selector: left click for the first vertex, right click to add more; the selection is the skin of the shape",
            Self::Fuzzy => "Fuzzy selector: left click selects the connected blocks of one type, right click adds more",
        }
    }
}

/// Active region selector state (`region/Selectors.java`).
#[derive(Clone, Debug)]
pub enum RegionSelector {
    Cuboid {
        pos1: Option<BlockVector3>,
        pos2: Option<BlockVector3>,
    },
    Extend {
        pos1: Option<BlockVector3>,
        pos2: Option<BlockVector3>,
    },
    Poly {
        points: Vec<BlockVector2>,
        first: Option<BlockVector3>,
        min_y: i32,
        max_y: i32,
    },
    Ellipsoid {
        center: Option<BlockVector3>,
        radii: Vector3,
        radius_set: bool,
        sphere: bool,
        min_y: i32,
        max_y: i32,
    },
    Cyl {
        center: Option<BlockVector3>,
        radii: Vector2,
        radius_set: bool,
        min_y: i32,
        max_y: i32,
    },
    Convex {
        region: ConvexPolyhedralRegion,
        first: Option<BlockVector3>,
    },
    Polyhedral {
        region: PolyhedralRegion,
        first: Option<BlockVector3>,
    },
    Fuzzy {
        region: FuzzyRegion,
        clicks: Vec<BlockVector3>,
    },
}

impl Default for RegionSelector {
    fn default() -> Self {
        Self::Cuboid {
            pos1: None,
            pos2: None,
        }
    }
}

fn polygonize_cylinder(center: BlockVector2, radius: Vector2) -> Vec<BlockVector2> {
    let count = ((std::f64::consts::PI * (radius.x * radius.x + radius.z * radius.z).sqrt()).ceil()
        as usize)
        .max(3);
    let mut points: Vec<BlockVector2> = Vec::with_capacity(count);
    for i in 0..count {
        let angle = i as f64 * (2.0 * std::f64::consts::PI) / count as f64;
        let pt = BlockVector2::at(
            (angle.cos() * radius.x).floor() as i32 + center.x,
            (angle.sin() * radius.z).floor() as i32 + center.z,
        );
        if points.last() != Some(&pt) {
            points.push(pt);
        }
    }
    points
}

impl RegionSelector {
    pub fn kind(&self) -> SelectorKind {
        match self {
            Self::Cuboid { .. } => SelectorKind::Cuboid,
            Self::Extend { .. } => SelectorKind::Extend,
            Self::Poly { .. } => SelectorKind::Poly,
            Self::Ellipsoid { sphere: true, .. } => SelectorKind::Sphere,
            Self::Ellipsoid { sphere: false, .. } => SelectorKind::Ellipsoid,
            Self::Cyl { .. } => SelectorKind::Cyl,
            Self::Convex { .. } => SelectorKind::Convex,
            Self::Polyhedral { .. } => SelectorKind::Polyhedral,
            Self::Fuzzy { .. } => SelectorKind::Fuzzy,
        }
    }

    pub fn is_defined(&self) -> bool {
        match self {
            Self::Cuboid { pos1, pos2 } | Self::Extend { pos1, pos2 } => {
                pos1.is_some() && pos2.is_some()
            }
            Self::Poly { points, .. } => points.len() >= 3,
            Self::Ellipsoid {
                center, radius_set, ..
            } => center.is_some() && *radius_set,
            Self::Cyl {
                center, radius_set, ..
            } => center.is_some() && *radius_set,
            Self::Convex { region, .. } => region.is_defined(),
            Self::Polyhedral { region, .. } => region.is_defined(),
            Self::Fuzzy { region, .. } => region.volume() > 0,
        }
    }

    pub fn clear(&mut self) {
        match self {
            Self::Cuboid { pos1, pos2 } | Self::Extend { pos1, pos2 } => {
                *pos1 = None;
                *pos2 = None;
            }
            Self::Poly { points, first, .. } => {
                points.clear();
                *first = None;
            }
            Self::Ellipsoid {
                center,
                radii,
                radius_set,
                ..
            } => {
                *center = None;
                *radii = Vector3::ZERO;
                *radius_set = false;
            }
            Self::Cyl {
                center,
                radii,
                radius_set,
                ..
            } => {
                *center = None;
                *radii = Vector2::ZERO;
                *radius_set = false;
            }
            Self::Convex { region, first } => {
                region.clear();
                *first = None;
            }
            Self::Polyhedral { region, first } => {
                region.clear();
                *first = None;
            }
            Self::Fuzzy { region, clicks } => {
                region.clear();
                clicks.clear();
            }
        }
    }

    pub fn primary_position(&self) -> Option<BlockVector3> {
        match self {
            Self::Cuboid { pos1, .. } | Self::Extend { pos1, .. } => *pos1,
            Self::Poly { first, .. }
            | Self::Convex { first, .. }
            | Self::Polyhedral { first, .. } => *first,
            Self::Ellipsoid { center, .. } | Self::Cyl { center, .. } => *center,
            Self::Fuzzy { clicks, .. } => clicks.first().copied(),
        }
    }

    pub fn secondary_position(&self) -> Option<BlockVector3> {
        match self {
            Self::Cuboid { pos2, .. } | Self::Extend { pos2, .. } => *pos2,
            _ => None,
        }
    }

    pub fn region(&self) -> Option<RegionShape> {
        if !self.is_defined() {
            return None;
        }
        match self {
            Self::Cuboid {
                pos1: Some(a),
                pos2: Some(b),
            }
            | Self::Extend {
                pos1: Some(a),
                pos2: Some(b),
            } => Some(RegionShape::Cuboid(CuboidRegion::new(*a, *b))),
            Self::Poly {
                points,
                min_y,
                max_y,
                ..
            } => Polygonal2DRegion::new(points.clone(), *min_y, *max_y)
                .ok()
                .map(RegionShape::Polygon),
            Self::Ellipsoid {
                center: Some(c),
                radii,
                min_y,
                max_y,
                ..
            } => Some(RegionShape::Ellipsoid(EllipsoidRegion::with_bounds(
                c.to_center(),
                *radii,
                *min_y,
                *max_y,
            ))),
            Self::Cyl {
                center: Some(c),
                radii,
                min_y,
                max_y,
                ..
            } => Some(RegionShape::Cylinder(CylinderRegion::new(
                Vector2::new(c.x as f64 + 0.5, c.z as f64 + 0.5),
                radii.x,
                radii.z,
                *min_y,
                *max_y,
            ))),
            Self::Convex { region, .. } => Some(RegionShape::Convex(region.clone())),
            Self::Polyhedral { region, .. } => Some(RegionShape::Polyhedral(region.clone())),
            Self::Fuzzy { region, .. } => Some(RegionShape::Fuzzy(region.clone())),
            _ => None,
        }
    }

    fn polygonize(&self, old: &RegionShape) -> Vec<BlockVector2> {
        match self {
            Self::Poly { points, .. } => points.clone(),
            Self::Cyl {
                center: Some(c),
                radii,
                ..
            } => polygonize_cylinder(c.to_block_vector2(), *radii),
            _ => {
                let min = old.min();
                let max = old.max();
                vec![
                    BlockVector2::at(min.x, min.z),
                    BlockVector2::at(min.x, max.z),
                    BlockVector2::at(max.x, max.z),
                    BlockVector2::at(max.x, min.z),
                ]
            }
        }
    }

    /// Switch selector shape while carrying over the current selection (`Selectors.create`).
    pub fn switch_kind(&self, kind: SelectorKind, world_min_y: i32, world_max_y: i32) -> Self {
        if self.kind() == kind {
            return self.clone();
        }
        let old = self.region();
        match kind {
            SelectorKind::Cuboid => match &old {
                Some(r) => Self::Cuboid {
                    pos1: Some(r.min()),
                    pos2: Some(r.max()),
                },
                None => Self::Cuboid {
                    pos1: None,
                    pos2: None,
                },
            },
            SelectorKind::Extend => match &old {
                Some(r) => Self::Extend {
                    pos1: Some(r.min()),
                    pos2: Some(r.max()),
                },
                None => Self::Extend {
                    pos1: None,
                    pos2: None,
                },
            },
            SelectorKind::Poly => match &old {
                Some(r) => {
                    let pts = self.polygonize(r);
                    let first = pts.first().map(|p| BlockVector3::at(p.x, r.min().y, p.z));
                    Self::Poly {
                        points: pts,
                        first,
                        min_y: r.min().y,
                        max_y: r.max().y,
                    }
                }
                None => Self::Poly {
                    points: Vec::new(),
                    first: None,
                    min_y: world_min_y,
                    max_y: world_min_y,
                },
            },
            SelectorKind::Ellipsoid | SelectorKind::Sphere => {
                let sphere = kind == SelectorKind::Sphere;
                if let Self::Ellipsoid {
                    center,
                    mut radii,
                    radius_set,
                    ..
                } = *self
                {
                    if sphere && radius_set {
                        let r = radii.x.max(radii.y).max(radii.z);
                        radii = Vector3::new(r, r, r);
                    }
                    return Self::Ellipsoid {
                        center,
                        radii,
                        radius_set,
                        sphere,
                        min_y: world_min_y,
                        max_y: world_max_y,
                    };
                }
                match &old {
                    Some(r) => {
                        let min = r.min();
                        let max = r.max();
                        let center = BlockVector3::at(
                            (min.x + max.x).div_euclid(2),
                            (min.y + max.y).div_euclid(2),
                            (min.z + max.z).div_euclid(2),
                        );
                        let mut radii = max.subtract(center).to_vector3();
                        if sphere {
                            let m = radii.x.max(radii.y).max(radii.z);
                            radii = Vector3::new(m, m, m);
                        }
                        Self::Ellipsoid {
                            center: Some(center),
                            radii,
                            radius_set: true,
                            sphere,
                            min_y: world_min_y,
                            max_y: world_max_y,
                        }
                    }
                    None => Self::Ellipsoid {
                        center: None,
                        radii: Vector3::ZERO,
                        radius_set: false,
                        sphere,
                        min_y: world_min_y,
                        max_y: world_max_y,
                    },
                }
            }
            SelectorKind::Cyl => match &old {
                Some(r) => {
                    let min = r.min();
                    let max = r.max();
                    let center = BlockVector3::at(
                        (min.x + max.x).div_euclid(2),
                        (min.y + max.y).div_euclid(2),
                        (min.z + max.z).div_euclid(2),
                    );
                    let radii = Vector2::new((max.x - center.x) as f64, (max.z - center.z) as f64);
                    Self::Cyl {
                        center: Some(center),
                        radii,
                        radius_set: true,
                        min_y: min.y,
                        max_y: max.y,
                    }
                }
                None => Self::Cyl {
                    center: None,
                    radii: Vector2::ZERO,
                    radius_set: false,
                    min_y: world_min_y,
                    max_y: world_max_y,
                },
            },
            SelectorKind::Convex => {
                let mut region = ConvexPolyhedralRegion::new();
                region.set_bounds(world_min_y, world_max_y);
                if let Some(r) = &old {
                    let (low, high) = (r.min().y, r.max().y);
                    for p in self.polygonize(r) {
                        let _ = region.add_vertex(BlockVector3::at(p.x, low, p.z));
                        let _ = region.add_vertex(BlockVector3::at(p.x, high, p.z));
                    }
                }
                let first = region.vertices().first().copied();
                Self::Convex { region, first }
            }
            SelectorKind::Polyhedral => Self::Polyhedral {
                region: PolyhedralRegion::new(world_min_y, world_max_y),
                first: None,
            },
            SelectorKind::Fuzzy => Self::Fuzzy {
                region: FuzzyRegion::new(),
                clicks: Vec::new(),
            },
        }
    }

    pub fn select_primary(&mut self, position: BlockVector3, _limits: SelectorLimits) -> Result<bool, String> {
        match self {
            Self::Cuboid { pos1, .. } => {
                if *pos1 == Some(position) {
                    return Ok(false);
                }
                *pos1 = Some(position);
                Ok(true)
            }
            Self::Extend { pos1, pos2 } => {
                if *pos1 == Some(position) && *pos2 == Some(position) {
                    return Ok(false);
                }
                *pos1 = Some(position);
                *pos2 = Some(position);
                Ok(true)
            }
            Self::Poly {
                points,
                first,
                min_y,
                max_y,
            } => {
                if *first == Some(position) {
                    return Ok(false);
                }
                *first = Some(position);
                points.clear();
                points.push(position.to_block_vector2());
                *min_y = position.y;
                *max_y = position.y;
                Ok(true)
            }
            Self::Ellipsoid {
                center,
                radii,
                radius_set,
                ..
            } => {
                if *center == Some(position) && !*radius_set {
                    return Ok(false);
                }
                *center = Some(position);
                *radii = Vector3::ZERO;
                *radius_set = false;
                Ok(true)
            }
            Self::Cyl {
                center,
                radii,
                radius_set,
                min_y,
                max_y,
            } => {
                if *center == Some(position) && !*radius_set {
                    return Ok(false);
                }
                *center = Some(position);
                *radii = Vector2::ZERO;
                *radius_set = false;
                *min_y = position.y;
                *max_y = position.y;
                Ok(true)
            }
            Self::Convex { region, first } => {
                region.clear();
                *first = Some(position);
                region.add_vertex(position)
            }
            Self::Polyhedral { region, first } => {
                region.clear();
                *first = Some(position);
                region.add_vertex(position)
            }
            Self::Fuzzy { region, clicks } => {
                region.clear();
                clicks.clear();
                clicks.push(position);
                Ok(true)
            }
        }
    }

    pub fn select_secondary(&mut self, position: BlockVector3, limits: SelectorLimits) -> Result<bool, String> {
        match self {
            Self::Cuboid { pos2, .. } => {
                if *pos2 == Some(position) {
                    return Ok(false);
                }
                *pos2 = Some(position);
                Ok(true)
            }
            Self::Extend { pos1, pos2 } => {
                let (Some(p1), Some(p2)) = (*pos1, *pos2) else {
                    *pos1 = Some(position);
                    *pos2 = Some(position);
                    return Ok(true);
                };
                if CuboidRegion::new(p1, p2).contains(position.x, position.y, position.z) {
                    return Ok(false);
                }
                *pos1 = Some(p1.min(position));
                *pos2 = Some(p2.max(position));
                Ok(true)
            }
            Self::Poly {
                points,
                min_y,
                max_y,
                ..
            } => {
                let pt = position.to_block_vector2();
                if !points.is_empty() {
                    if points.last() == Some(&pt) {
                        return Ok(false);
                    }
                    if points.len() > limits.polygon_vertices {
                        return Ok(false);
                    }
                    *min_y = (*min_y).min(position.y);
                    *max_y = (*max_y).max(position.y);
                } else {
                    *min_y = position.y;
                    *max_y = position.y;
                }
                points.push(pt);
                Ok(true)
            }
            Self::Ellipsoid {
                center,
                radii,
                radius_set,
                sphere,
                ..
            } => {
                let Some(c) = *center else {
                    return Ok(false);
                };
                let dx = (position.x as f64 - c.x as f64).abs();
                let dy = (position.y as f64 - c.y as f64).abs();
                let dz = (position.z as f64 - c.z as f64).abs();
                if *sphere {
                    let r = (dx * dx + dy * dy + dz * dz).sqrt().ceil();
                    *radii = Vector3::new(r, r, r);
                } else {
                    *radii = Vector3::new(radii.x.max(dx), radii.y.max(dy), radii.z.max(dz));
                }
                *radius_set = true;
                Ok(true)
            }
            Self::Cyl {
                center,
                radii,
                radius_set,
                min_y,
                max_y,
            } => {
                let Some(c) = *center else {
                    return Ok(false);
                };
                let dx = (position.x as f64 - c.x as f64).abs();
                let dz = (position.z as f64 - c.z as f64).abs();
                *radii = Vector2::new(radii.x.max(dx), radii.z.max(dz));
                *min_y = (*min_y).min(position.y);
                *max_y = (*max_y).max(position.y);
                *radius_set = true;
                Ok(true)
            }
            Self::Convex { region, first } => {
                if region.vertices().len() > limits.polyhedron_vertices {
                    return Ok(false);
                }
                if first.is_none() {
                    *first = Some(position);
                }
                region.add_vertex(position)
            }
            Self::Polyhedral { region, first } => {
                if region.vertices().len() > limits.polyhedron_vertices {
                    return Ok(false);
                }
                if first.is_none() {
                    *first = Some(position);
                }
                region.add_vertex(position)
            }
            Self::Fuzzy { clicks, .. } => {
                clicks.push(position);
                Ok(true)
            }
        }
    }

    /// Apply a mutated `RegionShape` back onto the selector's stored points (`learnChanges`).
    pub fn learn_region(&mut self, mutated: RegionShape) {
        match (self, mutated) {
            (
                Self::Cuboid {
                    pos1: Some(p1),
                    pos2: Some(p2),
                },
                RegionShape::Cuboid(r),
            ) => {
                let (min, max) = (r.min(), r.max());
                let first = BlockVector3::at(
                    if p1.x <= p2.x { min.x } else { max.x },
                    if p1.y <= p2.y { min.y } else { max.y },
                    if p1.z <= p2.z { min.z } else { max.z },
                );
                let second = BlockVector3::at(
                    if p1.x <= p2.x { max.x } else { min.x },
                    if p1.y <= p2.y { max.y } else { min.y },
                    if p1.z <= p2.z { max.z } else { min.z },
                );
                *p1 = first;
                *p2 = second;
            }
            (Self::Extend { pos1, pos2 }, RegionShape::Cuboid(r)) => {
                *pos1 = Some(r.min());
                *pos2 = Some(r.max());
            }
            (
                Self::Poly {
                    points,
                    first,
                    min_y,
                    max_y,
                },
                RegionShape::Polygon(r),
            ) => {
                *points = r.points().to_vec();
                *min_y = r.min_y();
                *max_y = r.max_y();
                *first = points.first().map(|p| BlockVector3::at(p.x, *min_y, p.z));
            }
            (
                Self::Ellipsoid {
                    center,
                    radii,
                    radius_set,
                    ..
                },
                RegionShape::Ellipsoid(r),
            ) => {
                let c = r.center();
                *center = Some(BlockVector3::at(
                    c.x.floor() as i32,
                    c.y.floor() as i32,
                    c.z.floor() as i32,
                ));
                *radii = r.radii();
                *radius_set = true;
            }
            (
                Self::Cyl {
                    center,
                    radii,
                    radius_set,
                    min_y,
                    max_y,
                },
                RegionShape::Cylinder(r),
            ) => {
                let c2 = r.center_2d();
                let old_y = center.map_or(r.min_y(), |c| c.y);
                *center = Some(BlockVector3::at(c2.x.floor() as i32, old_y, c2.z.floor() as i32));
                *radii = Vector2::new(r.radius_x(), r.radius_z());
                *min_y = r.min_y();
                *max_y = r.max_y();
                *radius_set = true;
            }
            (Self::Convex { region, first }, RegionShape::Convex(r)) => {
                *first = r.vertices().first().copied();
                *region = r;
            }
            (Self::Polyhedral { region, first }, RegionShape::Polyhedral(r)) => {
                *first = r.vertices().first().copied();
                *region = r;
            }
            (Self::Fuzzy { region, .. }, RegionShape::Fuzzy(r)) => {
                *region = r;
            }
            _ => {}
        }
    }

    pub fn explain_primary(&self, position: BlockVector3) -> String {
        let vol_suffix = self
            .region()
            .map(|r| format!(" ({} blocks)", r.volume()))
            .unwrap_or_default();
        match self.kind() {
            SelectorKind::Cuboid => format!("Position 1: set to {position}{vol_suffix}"),
            SelectorKind::Extend => format!("Selection: started at {position}{vol_suffix}"),
            SelectorKind::Poly => format!("Polygon: started at {position}"),
            SelectorKind::Ellipsoid | SelectorKind::Sphere => {
                format!("Center: set to {position}{vol_suffix}")
            }
            SelectorKind::Cyl => format!("Cylinder: started at {position}"),
            SelectorKind::Convex => format!("Convex: started with the vertex {position}"),
            SelectorKind::Polyhedral => format!("Polyhedral: started with the vertex {position}"),
            SelectorKind::Fuzzy => format!("Fuzzy: selected from {position}{vol_suffix}"),
        }
    }

    pub fn explain_secondary(&self, position: BlockVector3) -> String {
        let vol_suffix = self
            .region()
            .map(|r| format!(" ({} blocks)", r.volume()))
            .unwrap_or_default();
        match self {
            Self::Cuboid { .. } => format!("Position 2: set to {position}{vol_suffix}"),
            Self::Extend { .. } => format!("Selection: extended to {position}{vol_suffix}"),
            Self::Poly { points, .. } => format!(
                "Polygon: point #{} added at {position}{vol_suffix}",
                points.len()
            ),
            Self::Ellipsoid {
                radii,
                sphere: true,
                ..
            } => format!("Radius: set to {}{vol_suffix}", radii.x),
            Self::Ellipsoid {
                radii,
                sphere: false,
                ..
            } => format!(
                "Radius: set to {}, {}, {}{vol_suffix}",
                radii.x, radii.y, radii.z
            ),
            Self::Cyl {
                center: None, ..
            } => "Select the center with a left click before the radius".to_string(),
            Self::Cyl { radii, .. } => {
                format!("Radius: set to {}/{}{vol_suffix}", radii.x, radii.z)
            }
            Self::Convex { .. } => format!("Convex: vertex {position} added{vol_suffix}"),
            Self::Polyhedral { .. } => format!("Polyhedral: vertex {position} added"),
            Self::Fuzzy { .. } => format!("Fuzzy: added from {position}{vol_suffix}"),
        }
    }

    pub fn describe(&self) -> String {
        match self {
            Self::Cuboid { pos1, pos2 } => match (*pos1, *pos2) {
                (None, None) => "no selection (pos1 and pos2 unset)".to_string(),
                (Some(p1), None) => format!("pos1 = ({}, {}, {}); pos2 unset", p1.x, p1.y, p1.z),
                (None, Some(p2)) => format!("pos1 unset; pos2 = ({}, {}, {})", p2.x, p2.y, p2.z),
                (Some(p1), Some(p2)) => {
                    let cuboid = CuboidRegion::new(p1, p2);
                    format!(
                        "({}, {}, {}) -> ({}, {}, {}) ({})",
                        cuboid.min().x,
                        cuboid.min().y,
                        cuboid.min().z,
                        cuboid.max().x,
                        cuboid.max().y,
                        cuboid.max().z,
                        cuboid.describe()
                    )
                }
            },
            Self::Extend { pos1, pos2 } => match (*pos1, *pos2) {
                (Some(p1), Some(p2)) => {
                    let cuboid = CuboidRegion::new(p1, p2);
                    format!(
                        "({}, {}, {}) -> ({}, {}, {}) (extend: {}x{}x{})",
                        cuboid.min().x,
                        cuboid.min().y,
                        cuboid.min().z,
                        cuboid.max().x,
                        cuboid.max().y,
                        cuboid.max().z,
                        cuboid.width(),
                        cuboid.height(),
                        cuboid.length()
                    )
                }
                _ => "extend: not defined".to_string(),
            },
            _ => match self.region() {
                Some(r) => {
                    let (min, max) = (r.min(), r.max());
                    format!(
                        "({}, {}, {}) -> ({}, {}, {}) ({}, {} blocks)",
                        min.x,
                        min.y,
                        min.z,
                        max.x,
                        max.y,
                        max.z,
                        r.describe(),
                        r.volume()
                    )
                }
                None => format!("{}: not defined", self.kind().as_str()),
            },
        }
    }
}

/// The selection of one source (`//pos1` / `//pos2` / `//sel`).
#[derive(Clone, Debug, Default)]
pub struct Selection {
    selector: RegionSelector,
}

impl Selection {
    pub fn selector(&self) -> &RegionSelector {
        &self.selector
    }

    pub fn selector_mut(&mut self) -> &mut RegionSelector {
        &mut self.selector
    }

    pub fn set_selector(&mut self, selector: RegionSelector) {
        self.selector = selector;
    }

    pub fn switch_kind(&mut self, kind: SelectorKind, min_y: i32, max_y: i32) {
        self.selector = self.selector.switch_kind(kind, min_y, max_y);
    }

    pub fn set_pos1(&mut self, pos: BlockVector3) {
        let _ = self.selector.select_primary(pos, SelectorLimits::default());
    }

    pub fn set_pos2(&mut self, pos: BlockVector3) {
        let _ = self.selector.select_secondary(pos, SelectorLimits::default());
    }

    pub fn is_complete(&self) -> bool {
        self.selector.is_defined()
    }

    pub fn region(&self) -> Option<RegionShape> {
        self.selector.region()
    }

    pub fn cuboid(&self) -> Option<CuboidRegion> {
        let r = self.selector.region()?;
        match r {
            RegionShape::Cuboid(c) => Some(c),
            other => Some(CuboidRegion::new(other.min(), other.max())),
        }
    }

    pub fn clear(&mut self) {
        self.selector.clear();
    }

    pub fn describe(&self) -> String {
        self.selector.describe()
    }
}

/// The selections of every source, in one map.
#[derive(Default)]
pub struct SelectionStore {
    selections: HashMap<SelectionKey, Selection>,
}

impl SelectionStore {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn get(&mut self, key: SelectionKey) -> &mut Selection {
        self.selections.entry(key).or_default()
    }

    pub fn peek(&self, key: SelectionKey) -> Option<&Selection> {
        self.selections.get(&key)
    }

    pub fn clear(&mut self, key: SelectionKey) {
        self.selections.remove(&key);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn cuboid_needs_both_corners() {
        let mut store = SelectionStore::new();
        let selection = store.get(SelectionKey::Console);
        assert!(selection.cuboid().is_none());
        selection.set_pos1(BlockVector3::at(0, 0, 0));
        assert!(selection.cuboid().is_none());
        selection.set_pos2(BlockVector3::at(3, 3, 3));
        let cuboid = selection.cuboid().unwrap();
        assert_eq!(cuboid.volume(), 64);
    }

    #[test]
    fn describe_reports_progress() {
        let mut store = SelectionStore::new();
        assert_eq!(store.peek(SelectionKey::Console).map(Selection::describe), None);
        let selection = store.get(SelectionKey::Console);
        assert!(selection.describe().contains("no selection"));
        selection.set_pos1(BlockVector3::at(1, 2, 3));
        assert!(selection.describe().contains("pos2 unset"));
        selection.set_pos2(BlockVector3::at(4, 5, 6));
        let text = selection.describe();
        assert!(text.contains("(1, 2, 3) -> (4, 5, 6)"), "{text}");
        assert!(text.contains("cuboid: 4x4x4"), "{text}");
    }

    #[test]
    fn clear_forgets_the_selection() {
        let mut store = SelectionStore::new();
        store
            .get(SelectionKey::Player(42))
            .set_pos1(BlockVector3::at(1, 1, 1));
        assert!(store.peek(SelectionKey::Player(42)).is_some());
        store.clear(SelectionKey::Player(42));
        assert!(store.peek(SelectionKey::Player(42)).is_none());
    }

    #[test]
    fn switching_selector_shapes_converts_bounds_and_clicks_work() {
        let mut sel = Selection::default();
        sel.set_pos1(BlockVector3::at(0, 64, 0));
        sel.set_pos2(BlockVector3::at(4, 68, 4));
        sel.switch_kind(SelectorKind::Poly, -64, 319);
        assert_eq!(sel.selector().kind(), SelectorKind::Poly);
        assert!(sel.is_complete());
        assert_eq!(sel.region().unwrap().volume(), 5 * 5 * 5);

        sel.switch_kind(SelectorKind::Sphere, -64, 319);
        sel.set_pos1(BlockVector3::at(10, 70, 10));
        assert!(!sel.is_complete());
        sel.set_pos2(BlockVector3::at(13, 70, 10));
        assert!(sel.is_complete());
        assert!(sel.region().unwrap().contains(13, 70, 10));
    }
}
