//! Compact per-section block & biome change sets and sharded undo/redo history,
//! ported from `history/ChangeSet.java`, `history/BiomeChangeSet.java`,
//! `history/History.java`, and `history/EditLog.java`.

use std::collections::BTreeMap;

use mod_api::Compound;

use crate::math::{BlockBox, BlockVector3};

const SECTION_VOLUME: usize = 4096;
const STRIDE_PACKED: usize = 1;
const STRIDE_PAIR: usize = 2;
const STRIDE_ROW: usize = 3;
const COL_CELL: usize = 0;
const COL_BEFORE: usize = 1;
const COL_AFTER: usize = 2;
const PACKED_LIMIT: u32 = 1 << 20;
const PACKED_CELL_MASK: u32 = (1 << 12) - 1;

/// Default byte budget for retained history records (64 MiB inside wasm guest).
pub const DEFAULT_HISTORY_BUDGET_BYTES: u64 = 64 * 1024 * 1024;
/// Default max history steps per session (`Config.historySize`).
pub const DEFAULT_HISTORY_SIZE: usize = 20;

/// Recorded block changes of one `16x16x16` chunk section (`history/ChangeSet.java`).
#[derive(Clone, Debug)]
pub struct ChangeSet {
    rows: Vec<u32>,
    size: usize,
    stride: usize,
    packed: bool,
    uniform: bool,
    uniform_after: i32,
    chunk_x: i32,
    chunk_z: i32,
    section_y: i32,
}

impl ChangeSet {
    pub fn new(chunk_x: i32, chunk_z: i32, section_y: i32) -> Self {
        Self {
            rows: vec![0; 64],
            size: 0,
            stride: STRIDE_PACKED,
            packed: true,
            uniform: true,
            uniform_after: 0,
            chunk_x,
            chunk_z,
            section_y,
        }
    }

    pub const fn chunk_x(&self) -> i32 {
        self.chunk_x
    }

    pub const fn chunk_z(&self) -> i32 {
        self.chunk_z
    }

    pub const fn section_y(&self) -> i32 {
        self.section_y
    }

    pub const fn len(&self) -> usize {
        self.size
    }

    pub const fn is_empty(&self) -> bool {
        self.size == 0
    }

    pub fn bytes(&self) -> u64 {
        (self.rows.len() * std::mem::size_of::<u32>()) as u64
    }

    pub fn add(&mut self, x: i32, y: i32, z: i32, previous: i32, current: i32) {
        if self.uniform && current != self.uniform_after {
            if self.size == 0 {
                self.uniform_after = current;
            } else {
                self.widen();
            }
        }
        if self.packed && (previous < 0 || (previous as u32) >= PACKED_LIMIT) {
            self.unpack();
        }
        if (self.size + 1) * self.stride > self.rows.len() {
            self.grow();
        }
        let at = self.size * self.stride;
        let cell = (((y & 15) as u32) << 8) | (((z & 15) as u32) << 4) | ((x & 15) as u32);
        if self.packed {
            self.rows[at] = ((previous as u32) << 12) | cell;
        } else {
            self.rows[at + COL_CELL] = cell;
            self.rows[at + COL_BEFORE] = previous as u32;
            if !self.uniform {
                self.rows[at + COL_AFTER] = current as u32;
            }
        }
        self.size += 1;
    }

    fn unpack(&mut self) {
        let mut wide = vec![0u32; self.rows.len() * STRIDE_PAIR];
        for row in (0..self.size).rev() {
            let val = self.rows[row];
            wide[row * STRIDE_PAIR + COL_CELL] = val & PACKED_CELL_MASK;
            wide[row * STRIDE_PAIR + COL_BEFORE] = val >> 12;
        }
        self.rows = wide;
        self.packed = false;
        self.stride = STRIDE_PAIR;
    }

    fn widen(&mut self) {
        if self.packed {
            self.unpack();
        }
        let mut wide = vec![0u32; (self.rows.len() / STRIDE_PAIR) * STRIDE_ROW];
        for row in 0..self.size {
            wide[row * STRIDE_ROW + COL_CELL] = self.rows[row * STRIDE_PAIR + COL_CELL];
            wide[row * STRIDE_ROW + COL_BEFORE] = self.rows[row * STRIDE_PAIR + COL_BEFORE];
            wide[row * STRIDE_ROW + COL_AFTER] = self.uniform_after as u32;
        }
        self.rows = wide;
        self.stride = STRIDE_ROW;
        self.uniform = false;
    }

    fn grow(&mut self) {
        let new_rows = if self.size < SECTION_VOLUME {
            (self.size * 8).max(64)
        } else {
            self.size * 2
        };
        self.rows.resize(new_rows * self.stride, 0);
    }

    pub fn cell_at(&self, row: usize) -> u32 {
        if self.packed {
            self.rows[row] & PACKED_CELL_MASK
        } else {
            self.rows[row * self.stride + COL_CELL]
        }
    }

    pub fn before_at(&self, row: usize) -> i32 {
        if self.packed {
            (self.rows[row] >> 12) as i32
        } else {
            self.rows[row * self.stride + COL_BEFORE] as i32
        }
    }

    pub fn after_at(&self, row: usize) -> i32 {
        if self.uniform {
            self.uniform_after
        } else {
            self.rows[row * self.stride + COL_AFTER] as i32
        }
    }

    pub fn world_pos_at(&self, row: usize) -> BlockVector3 {
        let cell = self.cell_at(row) as i32;
        BlockVector3::at(
            (self.chunk_x << 4) + (cell & 15),
            (self.section_y << 4) + ((cell >> 8) & 15),
            (self.chunk_z << 4) + ((cell >> 4) & 15),
        )
    }

    pub fn y_range(&self) -> Option<(i32, i32)> {
        if self.size == 0 {
            return None;
        }
        let base_y = self.section_y << 4;
        let mut min_y = i32::MAX;
        let mut max_y = i32::MIN;
        for row in 0..self.size {
            let y = base_y + (((self.cell_at(row) >> 8) & 15) as i32);
            min_y = min_y.min(y);
            max_y = max_y.max(y);
        }
        Some((min_y, max_y))
    }
}

/// Recorded biome changes of one `16x16x16` chunk section (`history/BiomeChangeSet.java`).
#[derive(Clone, Debug)]
pub struct BiomeChangeSet {
    cells: Vec<u8>,
    before: Vec<i32>,
    after: Vec<i32>,
    chunk_x: i32,
    chunk_z: i32,
    section_y: i32,
}

impl BiomeChangeSet {
    pub fn new(chunk_x: i32, chunk_z: i32, section_y: i32) -> Self {
        Self {
            cells: Vec::with_capacity(16),
            before: Vec::with_capacity(16),
            after: Vec::with_capacity(16),
            chunk_x,
            chunk_z,
            section_y,
        }
    }

    pub const fn chunk_x(&self) -> i32 {
        self.chunk_x
    }

    pub const fn chunk_z(&self) -> i32 {
        self.chunk_z
    }

    pub const fn section_y(&self) -> i32 {
        self.section_y
    }

    pub fn len(&self) -> usize {
        self.cells.len()
    }

    pub fn is_empty(&self) -> bool {
        self.cells.is_empty()
    }

    pub fn bytes(&self) -> u64 {
        (self.cells.capacity()
            + (self.before.capacity() + self.after.capacity()) * std::mem::size_of::<i32>())
            as u64
    }

    pub fn add(&mut self, x: i32, y: i32, z: i32, previous: i32, current: i32) {
        let cell = ((((y >> 2) & 3) << 4) | (((z >> 2) & 3) << 2) | ((x >> 2) & 3)) as u8;
        self.cells.push(cell);
        self.before.push(previous);
        self.after.push(current);
    }

    pub fn restore(&mut self, cell: u8, biome_id: i32) {
        self.cells.push(cell);
        self.before.push(biome_id);
        self.after.push(biome_id);
    }

    pub fn before_at(&self, slot: usize) -> i32 {
        self.before[slot]
    }

    pub fn after_at(&self, slot: usize) -> i32 {
        self.after[slot]
    }

    pub fn world_pos_at(&self, slot: usize) -> BlockVector3 {
        let cell = self.cells[slot] as i32;
        BlockVector3::at(
            (self.chunk_x << 4) + ((cell & 3) << 2),
            (self.section_y << 4) + (((cell >> 4) & 3) << 2),
            (self.chunk_z << 4) + (((cell >> 2) & 3) << 2),
        )
    }

    pub fn cells(&self) -> &[u8] {
        &self.cells
    }
}

#[derive(Clone, Debug)]
pub struct BlockEntityChange {
    pub x: i32,
    pub y: i32,
    pub z: i32,
    pub before: Option<Compound>,
    pub after: Option<Compound>,
}

#[derive(Clone, Debug)]
pub struct EntityChange {
    pub entity_type: String,
    pub nbt: Option<Compound>,
    pub x: f64,
    pub y: f64,
    pub z: f64,
    pub removed: bool,
    pub uuid: Option<u128>,
}

pub const fn chunk_key(chunk_x: i32, chunk_z: i32) -> i64 {
    ((chunk_x as i64) << 32) | ((chunk_z as u32) as i64)
}

/// One atomic edit step (`History.Record`).
#[derive(Clone, Debug)]
pub struct HistoryRecord {
    pub description: String,
    pub world: Option<String>,
    pub owner: String,
    pub timestamp_ms: u64,
    sealed: bool,
    changes: BTreeMap<i64, Vec<ChangeSet>>,
    biomes: BTreeMap<i64, Vec<BiomeChangeSet>>,
    entities: Vec<EntityChange>,
    block_entities: Vec<BlockEntityChange>,
    change_count: usize,
    biome_change_count: usize,
    last_section: Option<(i64, usize, i32)>,
    last_biome_section: Option<(i64, usize, i32)>,
}

impl HistoryRecord {
    pub fn new(description: impl Into<String>, world: Option<String>, owner: impl Into<String>) -> Self {
        Self {
            description: description.into(),
            world,
            owner: owner.into(),
            timestamp_ms: 0,
            sealed: false,
            changes: BTreeMap::new(),
            biomes: BTreeMap::new(),
            entities: Vec::new(),
            block_entities: Vec::new(),
            change_count: 0,
            biome_change_count: 0,
            last_section: None,
            last_biome_section: None,
        }
    }

    pub fn seal(&mut self) {
        self.sealed = true;
        self.last_section = None;
        self.last_biome_section = None;
    }

    pub const fn is_sealed(&self) -> bool {
        self.sealed
    }

    pub fn add_change(&mut self, x: i32, y: i32, z: i32, previous: i32, current: i32) {
        assert!(
            !self.sealed,
            "History record '{}' is sealed",
            self.description
        );
        let cx = x >> 4;
        let cz = z >> 4;
        let sy = y >> 4;
        let key = chunk_key(cx, cz);

        if let Some((cached_key, cached_idx, cached_sy)) = self.last_section {
            if cached_key == key && cached_sy == sy {
                if let Some(sets) = self.changes.get_mut(&key) {
                    if let Some(set) = sets.get_mut(cached_idx) {
                        set.add(x, y, z, previous, current);
                        self.change_count += 1;
                        return;
                    }
                }
            }
        }

        let sets = self.changes.entry(key).or_default();
        let idx = match sets.iter().position(|s| s.section_y() == sy) {
            Some(i) => i,
            None => {
                sets.push(ChangeSet::new(cx, cz, sy));
                sets.len() - 1
            }
        };
        sets[idx].add(x, y, z, previous, current);
        self.last_section = Some((key, idx, sy));
        self.change_count += 1;
    }

    pub fn add_biome(&mut self, x: i32, y: i32, z: i32, previous: i32, current: i32) {
        assert!(
            !self.sealed,
            "History record '{}' is sealed",
            self.description
        );
        if previous == current {
            return;
        }
        let cx = x >> 4;
        let cz = z >> 4;
        let sy = y >> 4;
        let key = chunk_key(cx, cz);

        if let Some((cached_key, cached_idx, cached_sy)) = self.last_biome_section {
            if cached_key == key && cached_sy == sy {
                if let Some(sets) = self.biomes.get_mut(&key) {
                    if let Some(set) = sets.get_mut(cached_idx) {
                        set.add(x, y, z, previous, current);
                        self.biome_change_count += 1;
                        return;
                    }
                }
            }
        }

        let sets = self.biomes.entry(key).or_default();
        let idx = match sets.iter().position(|s| s.section_y() == sy) {
            Some(i) => i,
            None => {
                sets.push(BiomeChangeSet::new(cx, cz, sy));
                sets.len() - 1
            }
        };
        sets[idx].add(x, y, z, previous, current);
        self.last_biome_section = Some((key, idx, sy));
        self.biome_change_count += 1;
    }

    pub fn add_block_entity(
        &mut self,
        x: i32,
        y: i32,
        z: i32,
        before: Option<Compound>,
        after: Option<Compound>,
    ) {
        assert!(
            !self.sealed,
            "History record '{}' is sealed",
            self.description
        );
        self.block_entities.push(BlockEntityChange {
            x,
            y,
            z,
            before,
            after,
        });
    }

    pub fn add_entity(&mut self, change: EntityChange) {
        assert!(
            !self.sealed,
            "History record '{}' is sealed",
            self.description
        );
        self.entities.push(change);
    }

    pub fn changes(&self) -> &BTreeMap<i64, Vec<ChangeSet>> {
        &self.changes
    }

    pub fn biome_changes(&self) -> &BTreeMap<i64, Vec<BiomeChangeSet>> {
        &self.biomes
    }

    pub fn block_entities(&self) -> &[BlockEntityChange] {
        &self.block_entities
    }

    pub fn entities(&self) -> &[EntityChange] {
        &self.entities
    }

    pub const fn change_count(&self) -> usize {
        self.change_count
    }

    pub const fn biome_change_count(&self) -> usize {
        self.biome_change_count
    }

    pub fn is_empty(&self) -> bool {
        self.change_count == 0
            && self.biome_change_count == 0
            && self.entities.is_empty()
            && self.block_entities.is_empty()
    }

    pub fn estimated_bytes(&self) -> u64 {
        let mut total = 0u64;
        for sets in self.changes.values() {
            for set in sets {
                total += set.bytes();
            }
        }
        for sets in self.biomes.values() {
            for set in sets {
                total += set.bytes();
            }
        }
        total
    }

    pub fn bounds(&self) -> Option<BlockBox> {
        let mut min: Option<BlockVector3> = None;
        let mut max: Option<BlockVector3> = None;
        for sets in self.changes.values() {
            for set in sets {
                let Some((min_y, max_y)) = set.y_range() else {
                    continue;
                };
                let min_x = set.chunk_x() << 4;
                let min_z = set.chunk_z() << 4;
                let max_x = min_x + 15;
                let max_z = min_z + 15;
                let cmin = BlockVector3::at(min_x, min_y, min_z);
                let cmax = BlockVector3::at(max_x, max_y, max_z);
                min = Some(min.map_or(cmin, |m| m.min(cmin)));
                max = Some(max.map_or(cmax, |m| m.max(cmax)));
            }
        }
        { let a = min?; let b = max?; Some(BlockBox::new(a.x, a.y, a.z, b.x, b.y, b.z)) }
    }
}

/// Per-actor undo/redo history stack (`history/History.java`).
#[derive(Clone, Debug)]
pub struct History {
    max_records: usize,
    budget_bytes: u64,
    records: Vec<HistoryRecord>,
    current_index: isize,
}

impl Default for History {
    fn default() -> Self {
        Self::new(DEFAULT_HISTORY_SIZE)
    }
}

impl History {
    pub fn new(max_records: usize) -> Self {
        Self {
            max_records: max_records.max(1),
            budget_bytes: DEFAULT_HISTORY_BUDGET_BYTES,
            records: Vec::new(),
            current_index: -1,
        }
    }

    pub const fn max_records(&self) -> usize {
        self.max_records
    }

    pub fn set_max_records(&mut self, max_records: usize) {
        self.max_records = max_records.max(1);
        while self.records.len() > self.max_records {
            self.records.remove(0);
            self.current_index -= 1;
        }
        if self.current_index < -1 {
            self.current_index = -1;
        }
    }

    pub fn push_record(&mut self, mut record: HistoryRecord) {
        if record.is_empty() {
            return;
        }
        record.seal();
        let keep_len = (self.current_index + 1).max(0) as usize;
        self.records.truncate(keep_len);
        self.records.push(record);
        self.current_index = self.records.len() as isize - 1;
        self.trim();
    }

    fn trim(&mut self) {
        while self.records.len() > self.max_records {
            self.records.remove(0);
            self.current_index -= 1;
        }
        if self.current_index < 0 {
            self.current_index = -1;
        }
        let mut used: u64 = self.records.iter().map(HistoryRecord::estimated_bytes).sum();
        let mut dropped = 0usize;
        while self.records.len() - dropped > 1 && used > self.budget_bytes {
            used = used.saturating_sub(self.records[dropped].estimated_bytes());
            dropped += 1;
        }
        if dropped > 0 {
            self.records.drain(0..dropped);
            self.current_index -= dropped as isize;
            if self.current_index < 0 {
                self.current_index = -1;
            }
        }
    }

    pub fn can_undo(&self) -> bool {
        self.current_index >= 0
            && (self.current_index as usize) < self.records.len()
            && !self.records[self.current_index as usize].is_empty()
    }

    pub fn can_redo(&self) -> bool {
        !self.records.is_empty() && ((self.current_index + 1) as usize) < self.records.len()
    }

    pub fn undo(&mut self) -> Option<&HistoryRecord> {
        while self.current_index >= 0 && self.records[self.current_index as usize].is_empty() {
            let idx = self.current_index as usize;
            self.records.remove(idx);
            self.current_index -= 1;
        }
        if self.current_index < 0 {
            return None;
        }
        let idx = self.current_index as usize;
        self.current_index -= 1;
        self.records.get(idx)
    }

    pub fn redo(&mut self) -> Option<&HistoryRecord> {
        let next = (self.current_index + 1) as usize;
        if next >= self.records.len() {
            return None;
        }
        self.current_index = next as isize;
        self.records.get(next)
    }

    pub fn clear(&mut self) {
        self.records.clear();
        self.current_index = -1;
    }

    pub fn len(&self) -> usize {
        self.records.len()
    }

    pub fn is_empty(&self) -> bool {
        self.records.is_empty()
    }

    pub const fn current_index(&self) -> isize {
        self.current_index
    }

    pub fn records(&self) -> &[HistoryRecord] {
        &self.records
    }

    pub fn total_changes(&self) -> usize {
        self.records.iter().map(HistoryRecord::change_count).sum()
    }
}

/// Shared edit log for `/history find`, `/frb` (`rollback`), and `restore` (`history/EditLog.java`).
#[derive(Clone, Debug, Default)]
pub struct EditLog {
    entries: Vec<HistoryRecord>,
    max_entries: usize,
}

impl EditLog {
    pub fn new(max_entries: usize) -> Self {
        Self {
            entries: Vec::new(),
            max_entries: max_entries.max(16),
        }
    }

    pub fn record(&mut self, record: HistoryRecord) {
        if record.is_empty() {
            return;
        }
        if self.entries.len() >= self.max_entries {
            self.entries.remove(0);
        }
        self.entries.push(record);
    }

    pub fn entries(&self) -> &[HistoryRecord] {
        &self.entries
    }

    pub fn clear(&mut self) {
        self.entries.clear();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn change_set_packs_uniform_and_widens_on_mixed_states() {
        let mut cs = ChangeSet::new(0, 0, 4);
        cs.add(1, 64, 2, 5, 10);
        cs.add(3, 65, 4, 6, 10);
        // Uniform single-int packed mode:
        assert_eq!(cs.len(), 2);
        assert_eq!(cs.before_at(0), 5);
        assert_eq!(cs.after_at(0), 10);
        assert_eq!(cs.before_at(1), 6);
        assert_eq!(cs.after_at(1), 10);
        assert_eq!(cs.world_pos_at(1), BlockVector3::at(3, 65, 4));

        // Add a row with a different after-state -> widens to 3-int stride!
        cs.add(5, 66, 6, 7, 99);
        assert_eq!(cs.len(), 3);
        assert_eq!(cs.after_at(0), 10);
        assert_eq!(cs.after_at(1), 10);
        assert_eq!(cs.before_at(2), 7);
        assert_eq!(cs.after_at(2), 99);
    }

    #[test]
    fn history_undo_redo_and_truncation_match_fawe() {
        let mut hist = History::new(5);
        let mut r1 = HistoryRecord::new("//set stone", Some("minecraft:overworld".into()), "p1");
        r1.add_change(0, 64, 0, 0, 1);
        hist.push_record(r1);

        let mut r2 = HistoryRecord::new("//set dirt", Some("minecraft:overworld".into()), "p1");
        r2.add_change(0, 64, 0, 1, 2);
        hist.push_record(r2);

        assert!(hist.can_undo());
        assert!(!hist.can_redo());
        assert_eq!(hist.undo().unwrap().description, "//set dirt");
        assert!(hist.can_redo());

        // New edit after undo drops redo branch:
        let mut r3 = HistoryRecord::new("//set sand", Some("minecraft:overworld".into()), "p1");
        r3.add_change(0, 64, 0, 1, 3);
        hist.push_record(r3);
        assert!(!hist.can_redo());
        assert_eq!(hist.len(), 2);
    }
}
