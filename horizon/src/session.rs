//! Per-actor `LocalSession`, `SessionManager`, `SideEffectSet`, `Placement`,
//! and buffered `EditSession` (with `//undo` / `//redo` replay), ported from
//! `session/*.java` and `extent/EditSession.java`.

use std::collections::{BTreeMap, HashMap};

use mod_api::Compound;

use crate::history::{chunk_key, EditLog, History, HistoryRecord};
use crate::math::{BlockVector3, Direction, Vector3};
use crate::region::RegionShape;
use crate::registry::BlockStateRegistry;
use crate::selection::{RegionSelector, Selection, SelectionKey, SelectorKind};
use crate::transform::{BlockStateTransform, Transform};
use crate::world::{ChunkSet, Extent, World};

/// Side-effect categories controlled by `/perf` and `//fast` (`session/SideEffect.java`).
#[derive(Clone, Copy, PartialEq, Eq, Hash, Debug)]
#[repr(u8)]
pub enum SideEffect {
    History = 0,
    Lighting = 1,
    Neighbors = 2,
    Update = 3,
    EntityEvents = 4,
    Network = 5,
}

#[derive(Clone, Copy, PartialEq, Eq, Hash, Debug)]
#[repr(u8)]
pub enum SideEffectState {
    Off = 0,
    On = 1,
    Delayed = 2,
}

impl SideEffectState {
    pub fn parse(word: &str) -> Option<Self> {
        match word.trim().to_ascii_lowercase().as_str() {
            "off" => Some(Self::Off),
            "on" => Some(Self::On),
            "delayed" => Some(Self::Delayed),
            _ => None,
        }
    }

    pub const fn as_str(self) -> &'static str {
        match self {
            Self::Off => "off",
            Self::On => "on",
            Self::Delayed => "delayed",
        }
    }
}

impl SideEffect {
    pub const ALL: [Self; 6] = [
        Self::History,
        Self::Lighting,
        Self::Neighbors,
        Self::Update,
        Self::EntityEvents,
        Self::Network,
    ];

    pub const fn default_state(self) -> SideEffectState {
        match self {
            Self::Neighbors => SideEffectState::Off,
            _ => SideEffectState::On,
        }
    }

    pub const fn as_str(self) -> &'static str {
        match self {
            Self::History => "history",
            Self::Lighting => "lighting",
            Self::Neighbors => "neighbors",
            Self::Update => "update",
            Self::EntityEvents => "entity_events",
            Self::Network => "network",
        }
    }

    pub fn parse(word: &str) -> Option<Self> {
        match word.trim().to_ascii_lowercase().as_str() {
            "history" => Some(Self::History),
            "lighting" => Some(Self::Lighting),
            "neighbors" | "neighbours" => Some(Self::Neighbors),
            "update" => Some(Self::Update),
            "entity_events" | "entities" => Some(Self::EntityEvents),
            "network" => Some(Self::Network),
            _ => None,
        }
    }
}

/// Packed 2-bit-per-effect configuration (`session/SideEffectSet.java`).
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct SideEffectSet {
    bits: u32,
}

impl Default for SideEffectSet {
    fn default() -> Self {
        Self::defaults()
    }
}

impl SideEffectSet {
    pub const fn none() -> Self {
        Self { bits: 0 }
    }

    pub fn defaults() -> Self {
        let mut s = Self::none();
        for eff in SideEffect::ALL {
            s = s.with(eff, eff.default_state());
        }
        s
    }

    pub const fn with(self, effect: SideEffect, state: SideEffectState) -> Self {
        let shift = (effect as u32) * 2;
        Self {
            bits: (self.bits & !(3 << shift)) | ((state as u32) << shift),
        }
    }

    pub const fn state(self, effect: SideEffect) -> SideEffectState {
        match (self.bits >> ((effect as u32) * 2)) & 3 {
            1 => SideEffectState::On,
            2 => SideEffectState::Delayed,
            _ => SideEffectState::Off,
        }
    }

    pub const fn should_apply(self, effect: SideEffect) -> bool {
        !matches!(self.state(effect), SideEffectState::Off)
    }

    pub const fn is_delayed(self, effect: SideEffect) -> bool {
        matches!(self.state(effect), SideEffectState::Delayed)
    }
}

impl std::fmt::Display for SideEffectSet {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        for (i, eff) in SideEffect::ALL.iter().enumerate() {
            if i > 0 {
                write!(f, ", ")?;
            }
            write!(f, "{}={}", eff.as_str(), self.state(*eff).as_str())?;
        }
        Ok(())
    }
}

/// Anchor mode for `/placement` and `/toggleplace` (`session/PlacementType.java`).
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum PlacementType {
    World,
    Player,
    Here,
    Pos1,
    Min,
    Max,
}

impl PlacementType {
    pub fn parse(word: &str) -> Option<Self> {
        match word.trim().to_ascii_lowercase().as_str() {
            "world" => Some(Self::World),
            "player" => Some(Self::Player),
            "here" => Some(Self::Here),
            "pos1" => Some(Self::Pos1),
            "min" => Some(Self::Min),
            "max" => Some(Self::Max),
            _ => None,
        }
    }

    pub fn anchor(
        self,
        actor_pos: Option<BlockVector3>,
        selector: &RegionSelector,
        region: Option<&RegionShape>,
    ) -> Option<BlockVector3> {
        match self {
            Self::World => Some(BlockVector3::ZERO),
            Self::Player | Self::Here => actor_pos,
            Self::Pos1 => {
                if selector.is_defined() {
                    selector.primary_position()
                } else {
                    None
                }
            }
            Self::Min => region.map(RegionShape::min),
            Self::Max => region.map(RegionShape::max),
        }
    }

    pub fn message(self, offset: BlockVector3) -> String {
        if offset == BlockVector3::ZERO {
            match self {
                Self::World => "Now placing at world origin.".to_string(),
                Self::Player | Self::Here => "Now placing at the block you stand in.".to_string(),
                Self::Pos1 => "Now placing at pos #1.".to_string(),
                Self::Min => "Now placing at the minimum of the current selection.".to_string(),
                Self::Max => "Now placing at the maximum of the current selection.".to_string(),
            }
        } else {
            let (x, y, z) = (offset.x, offset.y, offset.z);
            match self {
                Self::World => format!("Now placing at ({x}, {y}, {z})."),
                Self::Player | Self::Here => {
                    format!("Now placing at an offset of ({x}, {y}, {z}) from the block you stand in.")
                }
                Self::Pos1 => format!("Now placing at an offset of ({x}, {y}, {z}) from pos #1."),
                Self::Min => format!(
                    "Now placing at an offset of ({x}, {y}, {z}) from the minimum of the current selection."
                ),
                Self::Max => format!(
                    "Now placing at an offset of ({x}, {y}, {z}) from the maximum of the current selection."
                ),
            }
        }
    }
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct Placement {
    pub kind: PlacementType,
    pub offset: BlockVector3,
}

impl Default for Placement {
    fn default() -> Self {
        Self {
            kind: PlacementType::Player,
            offset: BlockVector3::ZERO,
        }
    }
}

impl Placement {
    pub fn resolve(
        self,
        actor_pos: Option<BlockVector3>,
        selector: &RegionSelector,
        region: Option<&RegionShape>,
    ) -> Option<BlockVector3> {
        self.kind
            .anchor(actor_pos, selector, region)
            .map(|a| a.add(self.offset))
    }

    pub fn message(self) -> String {
        self.kind.message(self.offset)
    }
}

/// Per-actor WorldEdit session (`session/LocalSession.java`).
#[derive(Clone, Debug)]
pub struct LocalSession {
    pub selection: Selection,
    pub default_selector: SelectorKind,
    pub history: History,
    pub transform: Transform,
    pub placement: Placement,
    pub side_effects: SideEffectSet,
    pub fast_mode: bool,
    pub super_pickaxe_enabled: bool,
    pub super_pickaxe_mode: u8,
    pub super_pickaxe_range: f64,
    pub max_blocks_changed: i32,
    pub timeout_secs: i32,
    pub max_brush_radius: i64,
    pub max_brush_range: f64,
    pub wand_enabled: bool,
    pub include_air: bool,
    pub tracing: bool,
    pub draw_selection: bool,
    pub tips: bool,
    pub watchdog: bool,
    pub owner_name: String,
    pub last_world_name: Option<String>,
    pub global_mask_expr: Option<String>,
    pub source_mask_expr: Option<String>,
    pub global_pattern_expr: Option<String>,
    pub pending_command: Option<(String, u64)>,
    pub last_clicked_position: Option<BlockVector3>,
    pub last_clicked_face: Direction,
}

impl Default for LocalSession {
    fn default() -> Self {
        Self {
            selection: Selection::default(),
            default_selector: SelectorKind::Cuboid,
            history: History::default(),
            transform: Transform::Identity,
            placement: Placement::default(),
            side_effects: SideEffectSet::defaults(),
            fast_mode: false,
            super_pickaxe_enabled: false,
            super_pickaxe_mode: 0,
            super_pickaxe_range: 1.0,
            max_blocks_changed: -1,
            timeout_secs: -1,
            max_brush_radius: -1,
            max_brush_range: -1.0,
            wand_enabled: true,
            include_air: false,
            tracing: false,
            draw_selection: false,
            tips: false,
            watchdog: true,
            owner_name: "console".to_string(),
            last_world_name: None,
            global_mask_expr: None,
            source_mask_expr: None,
            global_pattern_expr: None,
            pending_command: None,
            last_clicked_position: None,
            last_clicked_face: Direction::North,
        }
    }
}

impl LocalSession {
    pub fn effective_side_effects(&self) -> SideEffectSet {
        if self.fast_mode {
            self.side_effects
                .with(SideEffect::Lighting, SideEffectState::Delayed)
                .with(SideEffect::Neighbors, SideEffectState::Off)
                .with(SideEffect::Update, SideEffectState::Off)
        } else {
            self.side_effects
        }
    }
}

/// Manages one `LocalSession` per player UUID plus the console session (`session/SessionManager.java`).
#[derive(Default)]
pub struct SessionManager {
    players: HashMap<u128, LocalSession>,
    console: LocalSession,
    pub edit_log: EditLog,
}

impl SessionManager {
    pub fn new() -> Self {
        Self {
            players: HashMap::new(),
            console: LocalSession::default(),
            edit_log: EditLog::new(256),
        }
    }

    pub fn get(&mut self, key: SelectionKey) -> &mut LocalSession {
        match key {
            SelectionKey::Console => &mut self.console,
            SelectionKey::Player(uuid) => self.players.entry(uuid).or_insert_with(|| {
                let mut s = LocalSession::default();
                s.owner_name = format!("{uuid:032x}");
                s
            }),
        }
    }

    pub fn get_named(&mut self, key: SelectionKey, owner_name: Option<&str>) -> &mut LocalSession {
        let session = self.get(key);
        if let Some(name) = owner_name {
            if !name.trim().is_empty() {
                session.owner_name = name.to_string();
            }
        }
        session
    }

    pub fn peek(&self, key: SelectionKey) -> Option<&LocalSession> {
        match key {
            SelectionKey::Console => Some(&self.console),
            SelectionKey::Player(uuid) => self.players.get(&uuid),
        }
    }

    pub fn by_name_mut(&mut self, name: &str) -> Option<&mut LocalSession> {
        if self.console.owner_name.eq_ignore_ascii_case(name) {
            return Some(&mut self.console);
        }
        self.players
            .values_mut()
            .find(|s| s.owner_name.eq_ignore_ascii_case(name))
    }

    pub fn remove_player(&mut self, uuid: u128) {
        self.players.remove(&uuid);
    }

    pub fn clear(&mut self) {
        self.players.clear();
        self.console = LocalSession::default();
    }
}

fn scales_up_along_axes(transform: &Transform) -> bool {
    let x = transform.apply_direction(Vector3::new(1.0, 0.0, 0.0));
    let y = transform.apply_direction(Vector3::new(0.0, 1.0, 0.0));
    let z = transform.apply_direction(Vector3::new(0.0, 0.0, 1.0));
    let diagonal = x.y.abs() + x.z.abs() < 1e-9
        && y.x.abs() + y.z.abs() < 1e-9
        && z.x.abs() + z.y.abs() < 1e-9
        && x.x > 0.0
        && y.y > 0.0
        && z.z > 0.0;
    diagonal && (x.x > 1.0 || y.y > 1.0 || z.z > 1.0)
}

/// Buffered editing extent (`extent/EditSession.java`).
///
/// Collects changes per chunk in `ChunkSet`s, applies session limits and
/// geometric/block-state transforms, records undo/redo `HistoryRecord`s, and
/// flushes everything in bulk column runs via `World::apply_chunks`.
pub struct EditSession<'w, 'r, W: World + ?Sized, R: BlockStateRegistry> {
    world: &'w mut W,
    registry: &'r mut R,
    record: Option<HistoryRecord>,
    chunks: BTreeMap<i64, ChunkSet>,
    min_y: i32,
    max_y: i32,
    change_limit: i32,
    blocks_changed: u64,
    limit_reached: bool,
    side_effects: SideEffectSet,
    transform: Transform,
    transforming: bool,
    transform_origin: Option<BlockVector3>,
    state_transform: Option<BlockStateTransform>,
    fills_cells: bool,
}

impl<'w, 'r, W: World + ?Sized, R: BlockStateRegistry> EditSession<'w, 'r, W, R> {
    pub fn open(
        world: &'w mut W,
        registry: &'r mut R,
        session: &mut LocalSession,
        description: impl Into<String>,
        record_history: bool,
    ) -> Self {
        let min_y = world.min_y();
        let max_y = world.max_y();
        let world_name = world.name().to_string();
        session.last_world_name = Some(world_name.clone());
        let side_effects = session.effective_side_effects();
        let should_record = record_history && side_effects.should_apply(SideEffect::History);
        let record = if should_record {
            Some(HistoryRecord::new(
                description,
                Some(world_name),
                session.owner_name.clone(),
            ))
        } else {
            None
        };
        let transform = if record_history {
            session.transform.clone()
        } else {
            Transform::Identity
        };
        let transforming = !transform.is_identity();
        let state_transform = BlockStateTransform::of(&transform);
        let fills_cells = transforming && scales_up_along_axes(&transform);
        Self {
            world,
            registry,
            record,
            chunks: BTreeMap::new(),
            min_y,
            max_y,
            change_limit: session.max_blocks_changed,
            blocks_changed: 0,
            limit_reached: false,
            side_effects,
            transform,
            transforming,
            transform_origin: None,
            state_transform,
            fills_cells,
        }
    }

    pub fn set_transform(&mut self, transform: Transform, origin: Option<BlockVector3>) {
        self.transforming = !transform.is_identity();
        self.state_transform = BlockStateTransform::of(&transform);
        self.fills_cells = self.transforming && scales_up_along_axes(&transform);
        self.transform = transform;
        self.transform_origin = origin;
    }

    pub const fn blocks_changed(&self) -> u64 {
        self.blocks_changed
    }

    pub const fn limit_reached(&self) -> bool {
        self.limit_reached
    }

    pub fn registry(&self) -> &R {
        self.registry
    }

    pub fn world(&self) -> &W {
        self.world
    }

    fn set_raw(&mut self, x: i32, y: i32, z: i32, state_id: i32, record_change: bool) -> bool {
        if state_id < 0 || y < self.min_y || y > self.max_y {
            return false;
        }
        if self.change_limit >= 0 && self.blocks_changed >= self.change_limit as u64 {
            self.limit_reached = true;
            return false;
        }
        let ckey = chunk_key(x >> 4, z >> 4);
        let previous_buffered = self.chunks.get(&ckey).map_or(-1, |c| c.get_block(x, y, z));
        if previous_buffered >= 0 && previous_buffered == state_id {
            return false;
        }
        let live_previous = if previous_buffered >= 0 {
            Some(previous_buffered)
        } else if record_change && self.record.is_some() {
            self.world.try_get_block(x, y, z)
        } else {
            None
        };
        if previous_buffered < 0 && live_previous == Some(state_id) {
            return false;
        }
        let (min_y, max_y) = (self.min_y, self.max_y);
        let chunk = self
            .chunks
            .entry(ckey)
            .or_insert_with(|| ChunkSet::new(x >> 4, z >> 4, min_y, max_y));
        if !chunk.set(x, y, z, state_id) {
            return false;
        }
        if record_change {
            if let Some(ref mut rec) = self.record {
                rec.add_change(x, y, z, live_previous.unwrap_or(0).max(0), state_id);
            }
        }
        self.blocks_changed += 1;
        true
    }

    fn set_transformed(&mut self, x: i32, y: i32, z: i32, state_id: i32) -> bool {
        let origin = *self
            .transform_origin
            .get_or_insert_with(|| BlockVector3::at(x, y, z));
        let image = self.transform.apply(Vector3::new(
            (x - origin.x) as f64,
            (y - origin.y) as f64,
            (z - origin.z) as f64,
        ));
        let from_x = image.x.floor() as i32 + origin.x;
        let from_y = image.y.floor() as i32 + origin.y;
        let from_z = image.z.floor() as i32 + origin.z;
        let (mut to_x, mut to_y, mut to_z) = (from_x, from_y, from_z);
        if self.fills_cells {
            let far = self.transform.apply(Vector3::new(
                (x - origin.x + 1) as f64,
                (y - origin.y + 1) as f64,
                (z - origin.z + 1) as f64,
            ));
            to_x = from_x.max(far.x.ceil() as i32 + origin.x - 1);
            to_y = from_y.max(far.y.ceil() as i32 + origin.y - 1);
            to_z = from_z.max(far.z.ceil() as i32 + origin.z - 1);
        }
        let turned_state = self.state_transform
            .as_mut()
            .map_or(state_id, |transform| transform.apply(self.registry, state_id as u32) as i32);
        let mut any = false;
        self.transforming = false;
        for ty in from_y..=to_y {
            for tz in from_z..=to_z {
                for tx in from_x..=to_x {
                    any |= self.set_raw(tx, ty, tz, turned_state, true);
                }
            }
        }
        self.transforming = true;
        any
    }

    /// Flushes all buffered chunks to the underlying `World` and seals the history record.
    pub fn finish(
        mut self,
        session: &mut LocalSession,
        edit_log: Option<&mut EditLog>,
    ) -> Result<u64, String> {
        let chunk_vec: Vec<ChunkSet> = self
            .chunks
            .into_values()
            .filter(|c| !c.is_empty())
            .collect();
        let touched_coords: Vec<[i32; 2]> = chunk_vec
            .iter()
            .map(|c| [c.chunk_x(), c.chunk_z()])
            .collect();

        let applied = if chunk_vec.is_empty() {
            0
        } else {
            self.world.apply_chunks(&chunk_vec)?
        };

        if self.side_effects.is_delayed(SideEffect::Lighting) && !touched_coords.is_empty() {
            self.world.fix_lighting(&touched_coords);
        }

        if let Some(mut rec) = self.record.take() {
            if !rec.is_empty() {
                rec.seal();
                if let Some(log) = edit_log {
                    log.record(rec.clone());
                }
                session.history.push_record(rec);
            }
        }

        Ok(if applied > 0 {
            applied
        } else {
            self.blocks_changed
        })
    }
}

impl<W: World + ?Sized, R: BlockStateRegistry> Extent for EditSession<'_, '_, W, R> {
    fn get_block(&self, x: i32, y: i32, z: i32) -> i32 {
        if y < self.min_y || y > self.max_y {
            return 0;
        }
        let ckey = chunk_key(x >> 4, z >> 4);
        if let Some(chunk) = self.chunks.get(&ckey) {
            let buffered = chunk.get_block(x, y, z);
            if buffered >= 0 {
                return buffered;
            }
        }
        self.world.get_block(x, y, z)
    }

    fn set_block(&mut self, x: i32, y: i32, z: i32, state_id: i32) -> bool {
        if self.transforming {
            self.set_transformed(x, y, z, state_id)
        } else {
            self.set_raw(x, y, z, state_id, true)
        }
    }

    fn get_biome(&self, x: i32, y: i32, z: i32) -> i32 {
        let ckey = chunk_key(x >> 4, z >> 4);
        if let Some(chunk) = self.chunks.get(&ckey) {
            let b = chunk.get_biome(x, y, z);
            if b >= 0 {
                return b;
            }
        }
        self.world.get_biome(x, y, z)
    }

    fn set_biome(&mut self, x: i32, y: i32, z: i32, biome_id: i32) -> bool {
        if biome_id < 0 || y < self.min_y || y > self.max_y {
            return false;
        }
        let prev = self.get_biome(x, y, z);
        if prev == biome_id {
            return false;
        }
        let ckey = chunk_key(x >> 4, z >> 4);
        let (min_y, max_y) = (self.min_y, self.max_y);
        let chunk = self
            .chunks
            .entry(ckey)
            .or_insert_with(|| ChunkSet::new(x >> 4, z >> 4, min_y, max_y));
        chunk.set_biome(x, y, z, biome_id);
        if let Some(ref mut rec) = self.record {
            rec.add_biome(x, y, z, prev, biome_id);
        }
        true
    }

    fn get_nbt(&self, x: i32, y: i32, z: i32) -> Option<Compound> {
        let ckey = chunk_key(x >> 4, z >> 4);
        if let Some(chunk) = self.chunks.get(&ckey) {
            if let Some(be) = chunk
                .block_entities()
                .iter()
                .find(|be| be.x == x && be.y == y && be.z == z)
            {
                return Some(be.data.clone());
            }
        }
        self.world.get_nbt(x, y, z)
    }

    fn set_nbt(&mut self, x: i32, y: i32, z: i32, nbt: Compound) -> bool {
        if y < self.min_y || y > self.max_y {
            return false;
        }
        let before = if self.record.is_some() {
            self.world.get_nbt(x, y, z)
        } else {
            None
        };
        let ckey = chunk_key(x >> 4, z >> 4);
        let (min_y, max_y) = (self.min_y, self.max_y);
        let chunk = self
            .chunks
            .entry(ckey)
            .or_insert_with(|| ChunkSet::new(x >> 4, z >> 4, min_y, max_y));
        chunk.set_block_entity(x, y, z, nbt.clone());
        if let Some(ref mut rec) = self.record {
            rec.add_block_entity(x, y, z, before, Some(nbt));
        }
        true
    }

    fn min_y(&self) -> i32 {
        self.min_y
    }

    fn max_y(&self) -> i32 {
        self.max_y
    }
}

/// Replay a `HistoryRecord` onto `world` in bulk (`undo = true` writes `before`, `false` writes `after`).
pub fn replay_history_record<W: World + ?Sized>(
    world: &mut W,
    record: &HistoryRecord,
    undo: bool,
) -> Result<u64, String> {
    let min_y = world.min_y();
    let max_y = world.max_y();
    let mut chunks: BTreeMap<i64, ChunkSet> = BTreeMap::new();

    for (&ckey, sets) in record.changes() {
        let cx = (ckey >> 32) as i32;
        let cz = ckey as i32;
        let chunk = chunks
            .entry(ckey)
            .or_insert_with(|| ChunkSet::new(cx, cz, min_y, max_y));
        if undo {
            for set in sets.iter().rev() {
                for row in (0..set.len()).rev() {
                    let pos = set.world_pos_at(row);
                    chunk.set(pos.x, pos.y, pos.z, set.before_at(row));
                }
            }
        } else {
            for set in sets {
                for row in 0..set.len() {
                    let pos = set.world_pos_at(row);
                    chunk.set(pos.x, pos.y, pos.z, set.after_at(row));
                }
            }
        }
    }

    for (&ckey, sets) in record.biome_changes() {
        let cx = (ckey >> 32) as i32;
        let cz = ckey as i32;
        let chunk = chunks
            .entry(ckey)
            .or_insert_with(|| ChunkSet::new(cx, cz, min_y, max_y));
        if undo {
            for set in sets.iter().rev() {
                for slot in (0..set.len()).rev() {
                    let pos = set.world_pos_at(slot);
                    chunk.set_biome(pos.x, pos.y, pos.z, set.before_at(slot));
                }
            }
        } else {
            for set in sets {
                for slot in 0..set.len() {
                    let pos = set.world_pos_at(slot);
                    chunk.set_biome(pos.x, pos.y, pos.z, set.after_at(slot));
                }
            }
        }
    }

    if undo {
        for be in record.block_entities().iter().rev() {
            if let Some(ref data) = be.before {
                let ckey = chunk_key(be.x >> 4, be.z >> 4);
                let chunk = chunks
                    .entry(ckey)
                    .or_insert_with(|| ChunkSet::new(be.x >> 4, be.z >> 4, min_y, max_y));
                chunk.set_block_entity(be.x, be.y, be.z, data.clone());
            }
        }
    } else {
        for be in record.block_entities() {
            if let Some(ref data) = be.after {
                let ckey = chunk_key(be.x >> 4, be.z >> 4);
                let chunk = chunks
                    .entry(ckey)
                    .or_insert_with(|| ChunkSet::new(be.x >> 4, be.z >> 4, min_y, max_y));
                chunk.set_block_entity(be.x, be.y, be.z, data.clone());
            }
        }
    }

    let chunk_vec: Vec<ChunkSet> = chunks.into_values().filter(|c| !c.is_empty()).collect();
    if chunk_vec.is_empty() {
        Ok(0)
    } else {
        world.apply_chunks(&chunk_vec)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::registry::InMemoryBlockRegistry;
    use crate::world::InMemoryWorld;

    #[test]
    fn edit_session_buffers_records_history_and_replays_undo_redo() {
        let mut reg = InMemoryBlockRegistry::with_vanilla_subset();
        let mut world = InMemoryWorld::default();
        let mut session = LocalSession::default();
        let stone = reg.parse("stone");
        let dirt = reg.parse("dirt");

        {
            let mut edit = EditSession::open(&mut world, &mut reg, &mut session, "//set stone", true);
            for x in 0..4 {
                for z in 0..4 {
                    edit.set_block(x, 64, z, stone);
                }
            }
            let changed = edit.finish(&mut session, None).unwrap();
            assert_eq!(changed, 16);
        }
        assert_eq!(world.get_block(2, 64, 2), stone);

        // Overwrite a 2x2 corner with dirt:
        {
            let mut edit = EditSession::open(&mut world, &mut reg, &mut session, "//set dirt", true);
            edit.set_block(0, 64, 0, dirt);
            edit.set_block(1, 64, 0, dirt);
            assert_eq!(edit.finish(&mut session, None).unwrap(), 2);
        }
        assert_eq!(world.get_block(0, 64, 0), dirt);

        // Undo #1 restores stone at (0, 64, 0):
        let rec = session.history.undo().unwrap().clone();
        replay_history_record(&mut world, &rec, true).unwrap();
        assert_eq!(world.get_block(0, 64, 0), stone);

        // Undo #2 restores air everywhere:
        let rec2 = session.history.undo().unwrap().clone();
        replay_history_record(&mut world, &rec2, true).unwrap();
        assert_eq!(world.get_block(2, 64, 2), 0);

        // Redo #1 puts stone back:
        let redo1 = session.history.redo().unwrap().clone();
        replay_history_record(&mut world, &redo1, false).unwrap();
        assert_eq!(world.get_block(2, 64, 2), stone);
    }

    #[test]
    fn edit_session_applies_transform_and_limit() {
        let mut reg = InMemoryBlockRegistry::with_vanilla_subset();
        let mut world = InMemoryWorld::default();
        let mut session = LocalSession::default();
        session.max_blocks_changed = 3;
        session.transform = Transform::rotate_y(BlockVector3::ZERO, 90.0);
        let stairs_n = reg.parse("oak_stairs[facing=north]");

        {
            let transform = session.transform.clone();
            let mut edit = EditSession::open(&mut world, &mut reg, &mut session, "//set stairs", true);
            edit.set_transform(transform, Some(BlockVector3::at(0, 64, 0)));
            for x in 0..5 {
                edit.set_block(x, 64, 0, stairs_n);
            }
            assert!(edit.limit_reached());
            assert_eq!(edit.blocks_changed(), 3);
            edit.finish(&mut session, None).unwrap();
        }
        // Rotated +90 around Y: (2, 64, 0) -> (0, 64, -2) with facing=west!
        let placed = world.get_block(0, 64, -2);
        assert_eq!(BlockStateRegistry::properties(&reg, placed).get("facing").unwrap(), "west");
    }
}
