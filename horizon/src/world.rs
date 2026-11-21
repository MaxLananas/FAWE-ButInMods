//! Packed 16³ block arrays, per-chunk write buffers (`ChunkSet`), `Extent`,
//! `World`, `HorizonWorld` (live Horizon level + bulk `edit_blocks` flush),
//! and `InMemoryWorld` (pure-Rust world for unit tests).

use std::collections::{BTreeMap, HashMap};
use std::sync::{Arc, Mutex};

use mod_api::prelude::{BlockEntitiesHost, EntitiesHost};
use mod_api::{
    BlockEdit, BlockFace, BlockRun, ClipBlock, ClipFluid, Compound, LevelRef, LightLayer,
    ServerHost,
};

use crate::math::{BlockBox, BlockVector3, Direction, Vector3};
use crate::region::block_keys;
use crate::registry::HorizonRegistryCache;

/// Maximum `BlockRun`s sent in a single `edit_blocks` batch.
const EDIT_BATCH: usize = 16_384;

/// A palette + bit-packed array of 4,096 block state IDs (`16x16x16` section),
/// ported from `world/PackedBlockArray.java`.
#[derive(Clone, Debug)]
pub struct PackedBlockArray {
    palette: Vec<i32>,
    palette_lookup: HashMap<i32, usize>,
    bits_per_block: usize,
    values_per_long: usize,
    mask: u64,
    data: Vec<u64>,
    written: [u64; Self::VOLUME / 64],
    last_state: i32,
    last_index: usize,
}

impl PackedBlockArray {
    pub const VOLUME: usize = 4096;

    pub fn new(initial_bits: usize) -> Self {
        let bits = initial_bits.clamp(1, 16);
        let values_per_long = 64 / bits;
        let mask = (1u64 << bits) - 1;
        let long_count = Self::VOLUME.div_ceil(values_per_long);
        Self {
            palette: Vec::with_capacity(1 << bits.min(8)),
            palette_lookup: HashMap::new(),
            bits_per_block: bits,
            values_per_long,
            mask,
            data: vec![0; long_count],
            written: [0; Self::VOLUME / 64],
            last_state: -1,
            last_index: 0,
        }
    }

    #[inline]
    pub const fn cell_index(x: i32, y: i32, z: i32) -> usize {
        (((y & 15) as usize) << 8) | (((z & 15) as usize) << 4) | ((x & 15) as usize)
    }

    #[inline]
    pub fn is_written(&self, index: usize) -> bool {
        (self.written[index >> 6] & (1u64 << (index & 63))) != 0
    }

    pub fn get(&self, index: usize) -> i32 {
        let slot = index / self.values_per_long;
        let offset = (index % self.values_per_long) * self.bits_per_block;
        let pal_idx = ((self.data[slot] >> offset) & self.mask) as usize;
        self.palette.get(pal_idx).copied().unwrap_or(0)
    }

    pub fn set(&mut self, index: usize, state_id: i32) {
        let pal_idx = self.palette_index(state_id);
        let slot = index / self.values_per_long;
        let offset = (index % self.values_per_long) * self.bits_per_block;
        self.data[slot] = (self.data[slot] & !(self.mask << offset)) | ((pal_idx as u64) << offset);
    }

    /// Writes `state_id` at `index`:
    /// - `-1` when already written with `state_id`,
    /// - `0` when previously unwritten,
    /// - `1` when overwriting a different buffered state.
    pub fn put(&mut self, index: usize, state_id: i32) -> i32 {
        let word = index >> 6;
        let bit = 1u64 << (index & 63);
        let was_written = (self.written[word] & bit) != 0;
        let pal_idx = self.palette_index(state_id);
        let slot = index / self.values_per_long;
        let offset = (index % self.values_per_long) * self.bits_per_block;
        if was_written {
            let cur_idx = ((self.data[slot] >> offset) & self.mask) as usize;
            if self.palette[cur_idx] == state_id {
                return -1;
            }
        }
        self.data[slot] = (self.data[slot] & !(self.mask << offset)) | ((pal_idx as u64) << offset);
        if was_written {
            1
        } else {
            self.written[word] |= bit;
            0
        }
    }

    fn palette_index(&mut self, state_id: i32) -> usize {
        if state_id == self.last_state {
            return self.last_index;
        }
        let idx = if let Some(&existing) = self.palette_lookup.get(&state_id) {
            existing
        } else {
            let new_idx = self.palette.len();
            self.palette.push(state_id);
            self.palette_lookup.insert(state_id, new_idx);
            if new_idx >= (1usize << self.bits_per_block) {
                self.grow_bits();
            }
            new_idx
        };
        self.last_state = state_id;
        self.last_index = idx;
        idx
    }

    fn grow_bits(&mut self) {
        let new_bits = (self.bits_per_block * 2).min(16);
        let new_vpl = 64 / new_bits;
        let new_mask = (1u64 << new_bits) - 1;
        let mut new_data = vec![0u64; Self::VOLUME.div_ceil(new_vpl)];
        for index in 0..Self::VOLUME {
            if !self.is_written(index) {
                continue;
            }
            let old_slot = index / self.values_per_long;
            let old_off = (index % self.values_per_long) * self.bits_per_block;
            let val = (self.data[old_slot] >> old_off) & self.mask;
            let new_slot = index / new_vpl;
            let new_off = (index % new_vpl) * new_bits;
            new_data[new_slot] |= val << new_off;
        }
        self.bits_per_block = new_bits;
        self.values_per_long = new_vpl;
        self.mask = new_mask;
        self.data = new_data;
    }

    pub fn for_each_written(&self, mut visit: impl FnMut(usize, i32)) {
        for (word_idx, &bits) in self.written.iter().enumerate() {
            let mut w = bits;
            while w != 0 {
                let tz = w.trailing_zeros() as usize;
                let index = (word_idx << 6) | tz;
                visit(index, self.get(index));
                w &= w - 1;
            }
        }
    }
}

#[derive(Clone, Debug)]
pub struct BlockEntityWrite {
    pub x: i32,
    pub y: i32,
    pub z: i32,
    pub data: Compound,
}

#[derive(Clone, Debug)]
pub struct EntitySnapshot {
    pub uuid: u128,
    pub entity_type: String,
    pub position: Vector3,
    pub nbt: Option<Compound>,
    pub is_player: bool,
}

#[derive(Clone, Copy, Debug)]
pub struct RayTraceHit {
    pub block: BlockVector3,
    pub face: Direction,
    pub location: Vector3,
}

/// Per-chunk bulk write buffer (`world/ChunkSet.java`).
#[derive(Clone, Debug)]
pub struct ChunkSet {
    chunk_x: i32,
    chunk_z: i32,
    min_section: i32,
    sections: Vec<Option<PackedBlockArray>>,
    biomes: Vec<Option<[i32; 64]>>,
    block_entities: Vec<BlockEntityWrite>,
    changed_count: usize,
    dirty: bool,
}

impl ChunkSet {
    pub fn new(chunk_x: i32, chunk_z: i32, min_y: i32, max_y: i32) -> Self {
        let min_section = min_y >> 4;
        let count = (((max_y >> 4) - min_section) + 1).max(1) as usize;
        Self {
            chunk_x,
            chunk_z,
            min_section,
            sections: vec![None; count],
            biomes: vec![None; count],
            block_entities: Vec::new(),
            changed_count: 0,
            dirty: false,
        }
    }

    pub const fn chunk_x(&self) -> i32 {
        self.chunk_x
    }

    pub const fn chunk_z(&self) -> i32 {
        self.chunk_z
    }

    pub const fn min_section(&self) -> i32 {
        self.min_section
    }

    pub fn is_empty(&self) -> bool {
        !self.dirty
            && self.biomes.iter().all(Option::is_none)
            && self.block_entities.is_empty()
    }

    pub const fn len(&self) -> usize {
        self.changed_count
    }

    fn section_index(&self, y: i32) -> Option<usize> {
        let idx = (y >> 4) - self.min_section;
        if idx < 0 || (idx as usize) >= self.sections.len() {
            None
        } else {
            Some(idx as usize)
        }
    }

    pub fn is_set(&self, x: i32, y: i32, z: i32) -> bool {
        let Some(sec_idx) = self.section_index(y) else {
            return false;
        };
        let Some(ref sec) = self.sections[sec_idx] else {
            return false;
        };
        sec.is_written(PackedBlockArray::cell_index(x, y, z))
    }

    /// Returns buffered state ID, or `-1` when not buffered at `(x, y, z)`.
    pub fn get_block(&self, x: i32, y: i32, z: i32) -> i32 {
        let Some(sec_idx) = self.section_index(y) else {
            return -1;
        };
        let Some(ref sec) = self.sections[sec_idx] else {
            return -1;
        };
        let cell = PackedBlockArray::cell_index(x, y, z);
        if sec.is_written(cell) {
            sec.get(cell)
        } else {
            -1
        }
    }

    pub fn set(&mut self, x: i32, y: i32, z: i32, state_id: i32) -> bool {
        let Some(sec_idx) = self.section_index(y) else {
            return false;
        };
        let sec = self.sections[sec_idx].get_or_insert_with(|| PackedBlockArray::new(4));
        let res = sec.put(PackedBlockArray::cell_index(x, y, z), state_id);
        if res < 0 {
            return false;
        }
        if res == 0 {
            self.changed_count += 1;
        }
        self.dirty = true;
        true
    }

    pub fn set_biome(&mut self, x: i32, y: i32, z: i32, biome_id: i32) {
        let Some(sec_idx) = self.section_index(y) else {
            return;
        };
        let cells = self.biomes[sec_idx].get_or_insert([-1; 64]);
        let idx = ((((y >> 2) & 3) << 4) | (((z >> 2) & 3) << 2) | ((x >> 2) & 3)) as usize;
        cells[idx] = biome_id;
    }

    pub fn get_biome(&self, x: i32, y: i32, z: i32) -> i32 {
        let Some(sec_idx) = self.section_index(y) else {
            return -1;
        };
        let Some(ref cells) = self.biomes[sec_idx] else {
            return -1;
        };
        let idx = ((((y >> 2) & 3) << 4) | (((z >> 2) & 3) << 2) | ((x >> 2) & 3)) as usize;
        cells[idx]
    }

    pub fn set_block_entity(&mut self, x: i32, y: i32, z: i32, data: Compound) {
        if let Some(existing) = self
            .block_entities
            .iter_mut()
            .find(|be| be.x == x && be.y == y && be.z == z)
        {
            existing.data = data;
        } else {
            self.block_entities.push(BlockEntityWrite { x, y, z, data });
        }
        self.dirty = true;
    }

    pub fn block_entities(&self) -> &[BlockEntityWrite] {
        &self.block_entities
    }

    pub fn for_each_changed(&self, mut visit: impl FnMut(i32, i32, i32, i32)) {
        let base_x = self.chunk_x << 4;
        let base_z = self.chunk_z << 4;
        for (sec_idx, sec_opt) in self.sections.iter().enumerate() {
            let Some(sec) = sec_opt else {
                continue;
            };
            let base_y = (self.min_section + sec_idx as i32) << 4;
            sec.for_each_written(|cell, state_id| {
                let x = base_x + ((cell & 15) as i32);
                let y = base_y + (((cell >> 8) & 15) as i32);
                let z = base_z + (((cell >> 4) & 15) as i32);
                visit(x, y, z, state_id);
            });
        }
    }

    pub fn for_each_biome(&self, mut visit: impl FnMut(i32, i32, i32, i32)) {
        let base_x = self.chunk_x << 4;
        let base_z = self.chunk_z << 4;
        for (sec_idx, cells_opt) in self.biomes.iter().enumerate() {
            let Some(cells) = cells_opt else {
                continue;
            };
            let base_y = (self.min_section + sec_idx as i32) << 4;
            for (cell, &biome_id) in cells.iter().enumerate() {
                if biome_id < 0 {
                    continue;
                }
                let x = base_x + (((cell & 3) as i32) << 2);
                let y = base_y + ((((cell >> 4) & 3) as i32) << 2);
                let z = base_z + ((((cell >> 2) & 3) as i32) << 2);
                visit(x, y, z, biome_id);
            }
        }
    }

    /// Emits vertical `(x, z)` column runs merging contiguous identical states across sections.
    pub fn emit_column_runs(&self, mut visit: impl FnMut(i32, i32, i32, u32, i32)) {
        if !self.dirty {
            return;
        }
        // Find lowest and highest populated section so empty sections are skipped wholesale.
        let Some(first_sec) = self.sections.iter().position(Option::is_some) else {
            return;
        };
        let last_sec = self
            .sections
            .iter()
            .rposition(Option::is_some)
            .unwrap_or(first_sec);

        let base_x = self.chunk_x << 4;
        let base_z = self.chunk_z << 4;

        for lz in 0..16i32 {
            for lx in 0..16i32 {
                let x = base_x + lx;
                let z = base_z + lz;
                let mut cur_run: Option<(i32, u32, i32)> = None; // (start_y, height, state_id)

                for sec_idx in first_sec..=last_sec {
                    let Some(ref sec) = self.sections[sec_idx] else {
                        if let Some((sy, h, st)) = cur_run.take() {
                            visit(x, z, sy, h, st);
                        }
                        continue;
                    };
                    let sec_base_y = (self.min_section + sec_idx as i32) << 4;
                    for ly in 0..16i32 {
                        let cell = ((ly as usize) << 8) | ((lz as usize) << 4) | (lx as usize);
                        if sec.is_written(cell) {
                            let st = sec.get(cell);
                            let y = sec_base_y + ly;
                            match cur_run {
                                Some((sy, h, prev_st))
                                    if prev_st == st && sy + (h as i32) == y =>
                                {
                                    cur_run = Some((sy, h + 1, prev_st));
                                }
                                Some((sy, h, prev_st)) => {
                                    visit(x, z, sy, h, prev_st);
                                    cur_run = Some((y, 1, st));
                                }
                                None => {
                                    cur_run = Some((y, 1, st));
                                }
                            }
                        } else if let Some((sy, h, prev_st)) = cur_run.take() {
                            visit(x, z, sy, h, prev_st);
                        }
                    }
                }
                if let Some((sy, h, prev_st)) = cur_run {
                    visit(x, z, sy, h, prev_st);
                }
            }
        }
    }
}

/// Read/write block & biome extent (`world/Extent.java`).
pub trait Extent {
    fn get_block(&self, x: i32, y: i32, z: i32) -> i32;
    fn set_block(&mut self, x: i32, y: i32, z: i32, state_id: i32) -> bool;

    /// Returns `Some(state_id)` when the live block state is readable, or `None`
    /// when the backing level cannot be read (so `//set air` still flushes).
    fn try_get_block(&self, x: i32, y: i32, z: i32) -> Option<i32> {
        Some(self.get_block(x, y, z))
    }

    fn get_biome(&self, _x: i32, _y: i32, _z: i32) -> i32 {
        0
    }

    fn set_biome(&mut self, _x: i32, _y: i32, _z: i32, _biome_id: i32) -> bool {
        false
    }

    fn get_nbt(&self, _x: i32, _y: i32, _z: i32) -> Option<Compound> {
        None
    }

    fn set_nbt(&mut self, _x: i32, _y: i32, _z: i32, _nbt: Compound) -> bool {
        false
    }

    fn min_y(&self) -> i32 {
        -64
    }

    fn max_y(&self) -> i32 {
        319
    }
}

/// Platform world abstraction (`world/World.java`).
pub trait World: Extent {
    fn name(&self) -> &str;
    fn apply_chunks(&mut self, chunks: &[ChunkSet]) -> Result<u64, String>;

    fn sky_light(&self, _x: i32, _y: i32, _z: i32) -> i32 {
        15
    }

    fn block_light(&self, _x: i32, _y: i32, _z: i32) -> i32 {
        0
    }

    fn fix_lighting(&mut self, _chunks: &[[i32; 2]]) -> usize {
        0
    }

    fn ray_trace(&self, _origin: Vector3, _direction: Vector3, _max_distance: f64) -> Option<RayTraceHit> {
        None
    }

    fn entities_in(&self, _bounds: BlockBox) -> Vec<EntitySnapshot> {
        Vec::new()
    }

    fn remove_entity(&mut self, _uuid: u128) -> bool {
        false
    }
}

/// Live server world backed by Horizon's `ServerHost` and bulk `edit_blocks`.
pub struct HorizonWorld<'a> {
    host: &'a mut dyn ServerHost,
    dimension: String,
    level: Option<LevelRef>,
    min_y: i32,
    max_y: i32,
    cache: Arc<Mutex<HorizonRegistryCache>>,
    read_memo: Mutex<HashMap<i64, i32>>,
}

impl<'a> HorizonWorld<'a> {
    pub fn new(
        host: &'a mut dyn ServerHost,
        dimension: String,
        cache: Arc<Mutex<HorizonRegistryCache>>,
    ) -> Self {
        let level = EntitiesHost::level(host, &dimension);
        let (min_y, max_y) = level
            .and_then(|l| l.height(host))
            .map(|(min, height)| (min, min + height - 1))
            .unwrap_or((-64, 319));
        Self {
            host,
            dimension,
            level,
            min_y,
            max_y,
            cache,
            read_memo: Mutex::new(HashMap::new()),
        }
    }

    /// Constructor for fast write-only paths that do not query `EntitiesHost::level` upfront.
    pub fn unprobed(
        host: &'a mut dyn ServerHost,
        dimension: String,
        cache: Arc<Mutex<HorizonRegistryCache>>,
    ) -> Self {
        Self {
            host,
            dimension,
            level: None,
            min_y: -64,
            max_y: 319,
            cache,
            read_memo: Mutex::new(HashMap::new()),
        }
    }

    pub fn host_mut(&mut self) -> &mut dyn ServerHost {
        self.host
    }

    pub fn host(&self) -> &dyn ServerHost {
        self.host
    }

    pub fn cache(&self) -> &Arc<Mutex<HorizonRegistryCache>> {
        &self.cache
    }

    fn flush_run_batch(
        &mut self,
        palette_states: &[mod_api::BlockState],
        runs: Vec<BlockRun>,
    ) -> Result<u64, String> {
        if runs.is_empty() {
            return Ok(0);
        }
        let edit = BlockEdit {
            palette: palette_states.to_vec(),
            filters: Vec::new(),
            runs,
        };
        self.host.edit_blocks(&self.dimension, &edit)
    }
}

impl Extent for HorizonWorld<'_> {
    fn get_block(&self, x: i32, y: i32, z: i32) -> i32 {
        if y < self.min_y || y > self.max_y {
            return 0;
        }
        let key = block_keys::key(x, y, z);
        if let Some(&cached) = self
            .read_memo
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .get(&key)
        {
            return cached;
        }
        let Some(level) = self.level else {
            return 0;
        };
        let state_id = match level.block_state(self.host, [x, y, z]) {
            Some(hs) => self
                .cache
                .lock()
                .unwrap_or_else(|p| p.into_inner())
                .intern_host_state(self.host, hs, None),
            None => 0,
        };
        self.read_memo
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .insert(key, state_id);
        state_id
    }

    fn try_get_block(&self, x: i32, y: i32, z: i32) -> Option<i32> {
        if y < self.min_y || y > self.max_y {
            return Some(0);
        }
        let key = block_keys::key(x, y, z);
        if let Some(&cached) = self
            .read_memo
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .get(&key)
        {
            return Some(cached);
        }
        let level = self.level?;
        let state_id = match level.block_state(self.host, [x, y, z]) {
            Some(hs) => self
                .cache
                .lock()
                .unwrap_or_else(|p| p.into_inner())
                .intern_host_state(self.host, hs, None),
            None => return None,
        };
        self.read_memo
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .insert(key, state_id);
        Some(state_id)
    }

    fn set_block(&mut self, x: i32, y: i32, z: i32, state_id: i32) -> bool {
        let mut chunk = ChunkSet::new(x >> 4, z >> 4, self.min_y, self.max_y);
        chunk.set(x, y, z, state_id);
        self.apply_chunks(&[chunk]).is_ok_and(|c| c > 0)
    }

    fn get_biome(&self, x: i32, y: i32, z: i32) -> i32 {
        let Some(level) = self.level else {
            return 0;
        };
        match level.biome_at(self.host, [x, y, z]) {
            Some(name) => self
                .cache
                .lock()
                .unwrap_or_else(|p| p.into_inner())
                .intern_biome(&name),
            None => 0,
        }
    }

    fn set_biome(&mut self, x: i32, y: i32, z: i32, biome_id: i32) -> bool {
        let Some(level) = self.level else {
            return false;
        };
        let name = self
            .cache
            .lock()
            .unwrap_or_else(|p| p.into_inner())
            .biome_name(biome_id);
        level.set_biome(self.host, [x, y, z], &name)
    }

    fn get_nbt(&self, x: i32, y: i32, z: i32) -> Option<Compound> {
        let level = self.level?;
        let be = BlockEntitiesHost::block_entity(self.host, level, [x, y, z])?;
        be.data(self.host)
    }

    fn set_nbt(&mut self, x: i32, y: i32, z: i32, nbt: Compound) -> bool {
        let Some(level) = self.level else {
            return false;
        };
        let Some(be) = BlockEntitiesHost::block_entity(self.host, level, [x, y, z]) else {
            return false;
        };
        be.replace_data(self.host, nbt).unwrap_or(false)
    }

    fn min_y(&self) -> i32 {
        self.min_y
    }

    fn max_y(&self) -> i32 {
        self.max_y
    }
}

impl World for HorizonWorld<'_> {
    fn name(&self) -> &str {
        &self.dimension
    }

    fn apply_chunks(&mut self, chunks: &[ChunkSet]) -> Result<u64, String> {
        let mut changed_total = 0u64;
        let mut palette: Vec<mod_api::BlockState> = Vec::new();
        let mut pal_map: HashMap<i32, u16> = HashMap::new();
        let mut runs: Vec<BlockRun> = Vec::new();

        for chunk in chunks {
            let mut batch_err: Option<String> = None;
            chunk.emit_column_runs(|x, z, y, height, state_id| {
                if batch_err.is_some() {
                    return;
                }
                let pal_idx = if let Some(&idx) = pal_map.get(&state_id) {
                    idx
                } else {
                    let hs_opt = self
                        .cache
                        .lock()
                        .unwrap_or_else(|p| p.into_inner())
                        .host_state_of(state_id, self.host);
                    let Some(hs) = hs_opt else {
                        batch_err = Some(format!("unresolvable block state id {state_id}"));
                        return;
                    };
                    let idx = palette.len() as u16;
                    palette.push(hs);
                    pal_map.insert(state_id, idx);
                    idx
                };
                // Update read memo so post-flush reads observe the new state.
                {
                    let mut memo = self.read_memo.lock().unwrap_or_else(|p| p.into_inner());
                    for ry in y..(y + height as i32) {
                        memo.insert(block_keys::key(x, ry, z), state_id);
                    }
                }
                runs.push(BlockRun {
                    x,
                    z,
                    y,
                    height,
                    surface: None,
                    block: pal_idx as u32,
                    only: None,
                });
                if runs.len() >= EDIT_BATCH {
                    let batch = std::mem::take(&mut runs);
                    match self.flush_run_batch(&palette, batch) {
                        Ok(c) => changed_total += c,
                        Err(e) => batch_err = Some(e),
                    }
                    palette.clear();
                    pal_map.clear();
                }
            });
            if let Some(err) = batch_err {
                return Err(err);
            }
        }

        if !runs.is_empty() {
            changed_total += self.flush_run_batch(&palette, runs)?;
        }

        // Apply buffered biomes & block entities when a live LevelRef is present.
        if let Some(level) = self.level {
            for chunk in chunks {
                chunk.for_each_biome(|x, y, z, biome_id| {
                    let name = self
                        .cache
                        .lock()
                        .unwrap_or_else(|p| p.into_inner())
                        .biome_name(biome_id);
                    let _ = level.set_biome(self.host, [x, y, z], &name);
                });
                for be_write in chunk.block_entities() {
                    if let Some(be) = BlockEntitiesHost::block_entity(
                        self.host,
                        level,
                        [be_write.x, be_write.y, be_write.z],
                    ) {
                        let _ = be.replace_data(self.host, be_write.data.clone());
                    }
                }
            }
        }

        Ok(changed_total)
    }

    fn sky_light(&self, x: i32, y: i32, z: i32) -> i32 {
        self.level
            .and_then(|l| l.brightness(self.host, LightLayer::Sky, [x, y, z]))
            .unwrap_or(15)
    }

    fn block_light(&self, x: i32, y: i32, z: i32) -> i32 {
        self.level
            .and_then(|l| l.brightness(self.host, LightLayer::Block, [x, y, z]))
            .unwrap_or(0)
    }

    fn fix_lighting(&mut self, chunks: &[[i32; 2]]) -> usize {
        let Some(level) = self.level else {
            return 0;
        };
        level
            .relight_chunks(self.host, chunks)
            .map(|c| c as usize)
            .unwrap_or(0)
    }

    fn ray_trace(&self, origin: Vector3, direction: Vector3, max_distance: f64) -> Option<RayTraceHit> {
        let level = self.level?;
        let dir = direction.normalize();
        let end = origin.add(dir.multiply(max_distance));
        let hit = level.clip(
            self.host,
            [origin.x, origin.y, origin.z],
            [end.x, end.y, end.z],
            ClipBlock::Outline,
            ClipFluid::None,
        )?;
        if hit.miss {
            return None;
        }
        let face = match hit.face {
            BlockFace::Down => Direction::Down,
            BlockFace::Up => Direction::Up,
            BlockFace::North => Direction::North,
            BlockFace::South => Direction::South,
            BlockFace::West => Direction::West,
            BlockFace::East => Direction::East,
            _ => Direction::Up,
        };
        Some(RayTraceHit {
            block: BlockVector3::at(hit.block[0], hit.block[1], hit.block[2]),
            face,
            location: Vector3::new(hit.location[0], hit.location[1], hit.location[2]),
        })
    }
}

/// Pure-Rust in-memory `World` for deterministic unit tests.
#[derive(Clone, Debug)]
pub struct InMemoryWorld {
    name: String,
    min_y: i32,
    max_y: i32,
    blocks: HashMap<i64, i32>,
    biomes: HashMap<i64, i32>,
    nbts: BTreeMap<i64, Compound>,
}

impl Default for InMemoryWorld {
    fn default() -> Self {
        Self::new("minecraft:overworld", -64, 319)
    }
}

impl InMemoryWorld {
    pub fn new(name: impl Into<String>, min_y: i32, max_y: i32) -> Self {
        Self {
            name: name.into(),
            min_y,
            max_y,
            blocks: HashMap::new(),
            biomes: HashMap::new(),
            nbts: BTreeMap::new(),
        }
    }

    fn biome_cell_key(x: i32, y: i32, z: i32) -> i64 {
        block_keys::key(x >> 2, y >> 2, z >> 2)
    }
}

impl Extent for InMemoryWorld {
    fn get_block(&self, x: i32, y: i32, z: i32) -> i32 {
        if y < self.min_y || y > self.max_y {
            return 0;
        }
        self.blocks
            .get(&block_keys::key(x, y, z))
            .copied()
            .unwrap_or(0)
    }

    fn set_block(&mut self, x: i32, y: i32, z: i32, state_id: i32) -> bool {
        if y < self.min_y || y > self.max_y {
            return false;
        }
        let key = block_keys::key(x, y, z);
        let prev = self.blocks.get(&key).copied().unwrap_or(0);
        if prev == state_id {
            return false;
        }
        if state_id == 0 {
            self.blocks.remove(&key);
            self.nbts.remove(&key);
        } else {
            self.blocks.insert(key, state_id);
        }
        true
    }

    fn get_biome(&self, x: i32, y: i32, z: i32) -> i32 {
        self.biomes
            .get(&Self::biome_cell_key(x, y, z))
            .copied()
            .unwrap_or(0)
    }

    fn set_biome(&mut self, x: i32, y: i32, z: i32, biome_id: i32) -> bool {
        let key = Self::biome_cell_key(x, y, z);
        let prev = self.biomes.insert(key, biome_id).unwrap_or(0);
        prev != biome_id
    }

    fn get_nbt(&self, x: i32, y: i32, z: i32) -> Option<Compound> {
        self.nbts.get(&block_keys::key(x, y, z)).cloned()
    }

    fn set_nbt(&mut self, x: i32, y: i32, z: i32, nbt: Compound) -> bool {
        self.nbts.insert(block_keys::key(x, y, z), nbt);
        true
    }

    fn min_y(&self) -> i32 {
        self.min_y
    }

    fn max_y(&self) -> i32 {
        self.max_y
    }
}

impl World for InMemoryWorld {
    fn name(&self) -> &str {
        &self.name
    }

    fn apply_chunks(&mut self, chunks: &[ChunkSet]) -> Result<u64, String> {
        let mut changed = 0u64;
        for chunk in chunks {
            chunk.for_each_changed(|x, y, z, state_id| {
                if self.set_block(x, y, z, state_id) {
                    changed += 1;
                }
            });
            chunk.for_each_biome(|x, y, z, biome_id| {
                self.set_biome(x, y, z, biome_id);
            });
            for be in chunk.block_entities() {
                self.set_nbt(be.x, be.y, be.z, be.data.clone());
            }
        }
        Ok(changed)
    }

    fn ray_trace(&self, origin: Vector3, direction: Vector3, max_distance: f64) -> Option<RayTraceHit> {
        let dir = direction.normalize();
        if dir.length_sq() < 1e-12 {
            return None;
        }
        let steps = (max_distance * 4.0).ceil() as usize;
        let mut prev_block = origin.to_block_point();
        for s in 1..=steps {
            let t = (s as f64) * 0.25;
            if t > max_distance {
                break;
            }
            let pos = origin.add(dir.multiply(t));
            let b = pos.to_block_point();
            if self.get_block(b.x, b.y, b.z) != 0 {
                let delta = prev_block.subtract(b);
                let face = Direction::ALL
                    .into_iter()
                    .find(|d| d.to_vector() == delta)
                    .unwrap_or(Direction::Up);
                return Some(RayTraceHit {
                    block: b,
                    face,
                    location: pos,
                });
            }
            prev_block = b;
        }
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn packed_block_array_grows_palette_and_tracks_written_bits() {
        let mut arr = PackedBlockArray::new(1);
        for i in 0..50 {
            assert_eq!(arr.put(i, 100 + i as i32), 0);
        }
        assert_eq!(arr.put(10, 110), -1);
        assert_eq!(arr.put(10, 999), 1);
        assert_eq!(arr.get(10), 999);
        assert_eq!(arr.get(49), 149);
        assert!(!arr.is_written(50));
    }

    #[test]
    fn chunk_set_merges_vertical_runs_across_section_boundaries() {
        let mut cs = ChunkSet::new(0, 0, -64, 319);
        for y in 60..=68 {
            cs.set(2, y, 5, 7);
        }
        let mut runs = Vec::new();
        cs.emit_column_runs(|x, z, y, h, st| runs.push((x, z, y, h, st)));
        assert_eq!(runs, vec![(2, 5, 60, 9, 7)]);
    }
}
