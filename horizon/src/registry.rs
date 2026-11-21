//! Interned block-state, biome, tag, category and item registry bridge,
//! ported from `world/BlockStateRegistry.java` and `FabricBlockStateRegistry.java`.

use std::collections::{BTreeMap, HashMap};
use std::sync::{Arc, Mutex};

use mod_api::prelude::{BlockStatesHost, KeysHost, TagsHost};
use mod_api::HostBlockStates;
use mod_api::{BlockKey, BlockState as HostBlockState, Host};

use crate::transform::StatePropertyAccess;

/// The 16 dye colour shorthands accepted by FAWE (`red` -> `minecraft:red_wool`, etc.).
pub fn expand_colour_shorthand(input: &str) -> String {
    let trimmed = input.trim();
    let (base, suffix) = match trimmed.find('[') {
        Some(idx) => (&trimmed[..idx], &trimmed[idx..]),
        None => (trimmed, ""),
    };
    if base.contains(':') {
        return input.to_string();
    }
    let wool = match base.to_ascii_lowercase().as_str() {
        "white" => Some("minecraft:white_wool"),
        "orange" => Some("minecraft:orange_wool"),
        "magenta" => Some("minecraft:magenta_wool"),
        "light_blue" | "lightblue" => Some("minecraft:light_blue_wool"),
        "yellow" => Some("minecraft:yellow_wool"),
        "lime" => Some("minecraft:lime_wool"),
        "pink" => Some("minecraft:pink_wool"),
        "gray" | "grey" => Some("minecraft:gray_wool"),
        "light_gray" | "light_grey" | "lightgray" | "lightgrey" => {
            Some("minecraft:light_gray_wool")
        }
        "cyan" => Some("minecraft:cyan_wool"),
        "purple" => Some("minecraft:purple_wool"),
        "blue" => Some("minecraft:blue_wool"),
        "brown" => Some("minecraft:brown_wool"),
        "green" => Some("minecraft:green_wool"),
        "red" => Some("minecraft:red_wool"),
        "black" => Some("minecraft:black_wool"),
        _ => None,
    };
    match wool {
        Some(w) => format!("{w}{suffix}"),
        None => input.to_string(),
    }
}

/// FAWE's `##category` definitions mapped to Minecraft block tags.
pub const CATEGORY_TAGS: &[(&str, &[&str])] = &[
    ("wool", &["minecraft:wool"]),
    ("planks", &["minecraft:planks"]),
    ("logs", &["minecraft:logs"]),
    ("leaves", &["minecraft:leaves"]),
    ("saplings", &["minecraft:saplings"]),
    ("flowers", &["minecraft:flowers", "minecraft:small_flowers"]),
    ("crops", &["minecraft:crops"]),
    ("ores", &["minecraft:ores"]),
    (
        "stone",
        &["minecraft:stone_bricks", "minecraft:base_stone_overworld"],
    ),
    ("stairs", &["minecraft:stairs"]),
    ("slabs", &["minecraft:slabs"]),
    ("walls", &["minecraft:walls"]),
    ("fences", &["minecraft:fences"]),
    ("glass", &["minecraft:impermeable"]),
    ("terracotta", &["minecraft:terracotta"]),
    ("concrete", &["minecraft:concrete"]),
    ("sand", &["minecraft:sand"]),
    ("dirt", &["minecraft:dirt"]),
    (
        "plants",
        &["minecraft:replaceable_by_trees", "minecraft:flowers"],
    ),
    ("air", &[]),
];

/// Legacy 1.12 numeric block IDs supported by MCEdit `.schematic` files.
pub const LEGACY_BLOCKS: &[(i32, &str)] = &[
    (0, "minecraft:air"),
    (1, "minecraft:stone"),
    (2, "minecraft:grass_block"),
    (3, "minecraft:dirt"),
    (4, "minecraft:cobblestone"),
    (5, "minecraft:oak_planks"),
    (7, "minecraft:bedrock"),
    (8, "minecraft:water"),
    (10, "minecraft:lava"),
    (12, "minecraft:sand"),
    (13, "minecraft:gravel"),
    (14, "minecraft:gold_ore"),
    (15, "minecraft:iron_ore"),
    (16, "minecraft:coal_ore"),
    (17, "minecraft:oak_log"),
    (18, "minecraft:oak_leaves"),
    (20, "minecraft:glass"),
    (24, "minecraft:sandstone"),
    (45, "minecraft:bricks"),
    (49, "minecraft:obsidian"),
    (54, "minecraft:chest"),
    (56, "minecraft:diamond_ore"),
    (57, "minecraft:diamond_block"),
    (73, "minecraft:redstone_ore"),
    (79, "minecraft:ice"),
    (80, "minecraft:snow_block"),
    (82, "minecraft:clay"),
    (87, "minecraft:netherrack"),
    (89, "minecraft:glowstone"),
    (98, "minecraft:stone_bricks"),
    (110, "minecraft:mycelium"),
    (121, "minecraft:end_stone"),
    (129, "minecraft:emerald_ore"),
    (133, "minecraft:emerald_block"),
];

/// Vanilla `MapColor.id` (0..=61) to `0xRRGGBB`.
pub fn map_color_id_to_rgb(id: u8) -> u32 {
    match id {
        0 => 0x000000,
        1 => 0x7FB238,
        2 => 0xF7E9A3,
        3 => 0xC7C7C7,
        4 => 0xFF0000,
        5 => 0xA0A0FF,
        6 => 0xA7A7A7,
        7 => 0x007C00,
        8 => 0xFFFFFF,
        9 => 0xA4A8B8,
        10 => 0x976D4D,
        11 => 0x707070,
        12 => 0x4040FF,
        13 => 0x8F7748,
        14 => 0xFFFCF5,
        15 => 0xD87F33,
        16 => 0xB24CD8,
        17 => 0x6699D8,
        18 => 0xE5E533,
        19 => 0x7FCC19,
        20 => 0xF27FA5,
        21 => 0x4C4C4C,
        22 => 0x999999,
        23 => 0x4C7F99,
        24 => 0x7F3FB2,
        25 => 0x334CB2,
        26 => 0x664C33,
        27 => 0x667F33,
        28 => 0x993333,
        29 => 0x191919,
        30 => 0xFAEE4D,
        31 => 0x5CDBD5,
        32 => 0x4A80FF,
        33 => 0x00D93A,
        34 => 0x815631,
        35 => 0x700200,
        36 => 0xD1B1A1,
        37 => 0x9F5224,
        38 => 0x95576C,
        39 => 0x706C8A,
        40 => 0xBA8524,
        41 => 0x677535,
        42 => 0xA04D4E,
        43 => 0x392923,
        44 => 0x876B62,
        45 => 0x575C5C,
        46 => 0x7A4958,
        47 => 0x4C3E5C,
        48 => 0x4C3223,
        49 => 0x4C522A,
        50 => 0x8E3C2E,
        51 => 0x251610,
        52 => 0xBD3031,
        53 => 0x943F61,
        54 => 0x5C191D,
        55 => 0x167E86,
        56 => 0x3A8E8C,
        57 => 0x562C3E,
        58 => 0x14B485,
        59 => 0x646464,
        60 => 0xD8AF93,
        61 => 0x7FA796,
        _ => 0x707070,
    }
}

pub fn normalize_id(raw: &str) -> String {
    let t = raw.trim();
    if t.contains(':') {
        t.to_ascii_lowercase()
    } else {
        format!("minecraft:{}", t.to_ascii_lowercase())
    }
}

pub fn parse_state_syntax(input: &str) -> Option<(String, Vec<(String, String)>)> {
    let expanded = expand_colour_shorthand(input);
    let trimmed = expanded.trim();
    if trimmed.is_empty() {
        return None;
    }
    if let Some(bracket) = trimmed.find('[') {
        if !trimmed.ends_with(']') {
            return None;
        }
        let block = normalize_id(&trimmed[..bracket]);
        let props_str = &trimmed[bracket + 1..trimmed.len() - 1];
        let mut props = Vec::new();
        if !props_str.trim().is_empty() {
            for pair in props_str.split(',') {
                let (k, v) = pair.split_once('=')?;
                let k = k.trim();
                let v = v.trim();
                if k.is_empty() || v.is_empty() {
                    return None;
                }
                props.push((k.to_string(), v.to_ascii_lowercase()));
            }
        }
        Some((block, props))
    } else {
        Some((normalize_id(trimmed), Vec::new()))
    }
}

/// Core block-state registry interface (`world/BlockStateRegistry.java`).
pub trait BlockStateRegistry {
    fn air(&self) -> i32;
    fn is_air(&self, state_id: i32) -> bool;
    fn state_count(&self) -> usize;
    fn parse(&self, input: &str) -> i32;
    fn default_state(&self, block_name: &str) -> i32;

    fn states_of(&self, block_name: &str) -> Vec<i32> {
        let base = expand_colour_shorthand(block_name);
        let base = base.split('[').next().unwrap_or(&base);
        let id = self.default_state(base);
        if id < 0 {
            Vec::new()
        } else {
            vec![id]
        }
    }

    fn legacy_state(&self, block_id: i32, metadata: i32) -> i32 {
        let Some((_, name)) = LEGACY_BLOCKS.iter().find(|(id, _)| *id == block_id) else {
            return self.air();
        };
        let state = self.default_state(name);
        if state < 0 {
            return self.air();
        }
        if metadata != 0 && *name == "minecraft:oak_log" {
            let axis = match metadata {
                1 => "x",
                2 => "z",
                _ => "y",
            };
            let rotated = self.with_property(state, "axis", axis);
            if rotated >= 0 {
                return rotated;
            }
        }
        state
    }

    fn legacy_id(&self, state_id: i32) -> i32 {
        let block_name = self.name(state_id);
        LEGACY_BLOCKS
            .iter()
            .find(|(_, n)| *n == block_name)
            .map(|(id, _)| *id)
            .unwrap_or(-1)
    }

    fn legacy_metadata(&self, state_id: i32) -> i32 {
        if self.legacy_id(state_id) != 17 {
            return 0;
        }
        match self.properties(state_id).get("axis").map(String::as_str) {
            Some("x") => 1,
            Some("z") => 2,
            _ => 0,
        }
    }

    fn name(&self, state_id: i32) -> String;
    fn describe(&self, state_id: i32) -> String;
    fn properties(&self, state_id: i32) -> BTreeMap<String, String>;
    fn property_defs(&self, state_id: i32) -> BTreeMap<String, Vec<String>>;
    fn with_property(&self, state_id: i32, property: &str, value: &str) -> i32;
    fn has_tag(&self, state_id: i32, tag: &str) -> bool;

    fn matches_category(&self, state_id: i32, category: &str) -> bool {
        let lower = category.trim().trim_start_matches('#').to_ascii_lowercase();
        if lower == "air" {
            return self.is_air(state_id);
        }
        let Some((_, tags)) = CATEGORY_TAGS.iter().find(|(cat, _)| *cat == lower) else {
            return false;
        };
        tags.iter().any(|tag| self.has_tag(state_id, tag))
    }

    fn categories(&self) -> Vec<String> {
        CATEGORY_TAGS.iter().map(|(c, _)| (*c).to_string()).collect()
    }

    fn is_solid(&self, state_id: i32) -> bool;
    fn is_liquid(&self, state_id: i32) -> bool;
    fn is_full_cube(&self, state_id: i32) -> bool;

    fn is_air_like(&self, state_id: i32) -> bool {
        if self.is_air(state_id) {
            return true;
        }
        matches!(
            self.name(state_id).as_str(),
            "minecraft:air" | "minecraft:cave_air" | "minecraft:void_air"
        )
    }

    fn blocks_light(&self, state_id: i32) -> bool {
        self.is_full_cube(state_id)
    }

    fn light_emission(&self, _state_id: i32) -> i32 {
        0
    }

    fn blast_resistance(&self, _state_id: i32) -> f64 {
        0.0
    }

    fn map_color(&self, _state_id: i32) -> u32 {
        0
    }

    fn block_names(&self) -> Vec<String>;
    fn block_tags(&self) -> Vec<String> {
        Vec::new()
    }
    fn block_from_item(&self, item_name: &str) -> i32;
    fn biome(&self, name: &str) -> i32;
    fn biome_name(&self, biome_id: i32) -> String;
    fn biome_names(&self) -> Vec<String>;
    fn item_names(&self) -> Vec<String>;
    fn item_name(&self, item_id: i32) -> String;
    fn item(&self, name: &str) -> i32;
}

impl<R: BlockStateRegistry + ?Sized> StatePropertyAccess for R {
    fn properties(&self, state: u32) -> BTreeMap<String, String> {
        BlockStateRegistry::properties(self, state as i32)
    }

    fn property_defs(&self, state: u32) -> BTreeMap<String, Vec<String>> {
        BlockStateRegistry::property_defs(self, state as i32)
    }

    fn with_property(&mut self, state: u32, property: &str, value: &str) -> Option<u32> {
        let result = BlockStateRegistry::with_property(self, state as i32, property, value);
        (result >= 0).then_some(result as u32)
    }
}

#[derive(Clone, Debug)]
struct CachedStateEntry {
    host_state: Option<HostBlockState>,
    block_name: String,
    described: String,
    props: BTreeMap<String, String>,
}

/// Shared interned state/biome/item table persisted across hook invocations.
pub struct HorizonRegistryCache {
    states: Vec<CachedStateEntry>,
    by_host_state: HashMap<HostBlockState, i32>,
    by_described: HashMap<String, i32>,
    parse_cache: HashMap<String, i32>,
    tag_cache: HashMap<(BlockKey, String), bool>,
    biomes: Vec<String>,
    biome_by_name: HashMap<String, i32>,
    items: Vec<String>,
    item_by_name: HashMap<String, i32>,
}

impl Default for HorizonRegistryCache {
    fn default() -> Self {
        let mut cache = Self {
            states: Vec::new(),
            by_host_state: HashMap::new(),
            by_described: HashMap::new(),
            parse_cache: HashMap::new(),
            tag_cache: HashMap::new(),
            biomes: Vec::new(),
            biome_by_name: HashMap::new(),
            items: Vec::new(),
            item_by_name: HashMap::new(),
        };
        // Slot 0 is always `minecraft:air`.
        cache.states.push(CachedStateEntry {
            host_state: None,
            block_name: "minecraft:air".to_string(),
            described: "minecraft:air".to_string(),
            props: BTreeMap::new(),
        });
        cache.by_described.insert("minecraft:air".to_string(), 0);
        cache.parse_cache.insert("minecraft:air".to_string(), 0);
        cache.parse_cache.insert("air".to_string(), 0);
        // Slot 0 biome is `minecraft:plains`; slot 0 item is `minecraft:air`.
        cache.intern_biome("minecraft:plains");
        cache.intern_item("minecraft:air");
        cache
    }
}

impl HorizonRegistryCache {
    pub fn new_shared() -> Arc<Mutex<Self>> {
        Arc::new(Mutex::new(Self::default()))
    }

    pub fn host_state_of(&self, state_id: i32, host: &dyn Host) -> Option<HostBlockState> {
        let entry = self.states.get(state_id as usize)?;
        if let Some(hs) = entry.host_state {
            return Some(hs);
        }
        BlockStatesHost::block_state_id(host, &entry.described)
    }

    pub fn intern_host_state(&mut self, host: &dyn Host, hs: HostBlockState, hint_desc: Option<&str>) -> i32 {
        if let Some(&id) = self.by_host_state.get(&hs) {
            return id;
        }
        let described = hint_desc
            .map(Self::canonicalize_desc)
            .or_else(|| BlockStatesHost::block_state_string(host, hs))
            .unwrap_or_else(|| "minecraft:air".to_string());
        let (block_name, prop_vec) = parse_state_syntax(&described)
            .unwrap_or_else(|| ("minecraft:air".to_string(), Vec::new()));
        if block_name == "minecraft:air" && prop_vec.is_empty() {
            self.states[0].host_state = Some(hs);
            self.by_host_state.insert(hs, 0);
            return 0;
        }
        if let Some(&existing) = self.by_described.get(&described) {
            self.states[existing as usize].host_state = Some(hs);
            self.by_host_state.insert(hs, existing);
            return existing;
        }
        let props: BTreeMap<String, String> = prop_vec.into_iter().collect();
        let id = self.states.len() as i32;
        self.states.push(CachedStateEntry {
            host_state: Some(hs),
            block_name,
            described: described.clone(),
            props,
        });
        self.by_host_state.insert(hs, id);
        self.by_described.insert(described, id);
        id
    }

    fn canonicalize_desc(raw: &str) -> String {
        if let Some((block, mut props)) = parse_state_syntax(raw) {
            if props.is_empty() {
                return block;
            }
            props.sort_by(|a, b| a.0.cmp(&b.0));
            let joined = props
                .into_iter()
                .map(|(k, v)| format!("{k}={v}"))
                .collect::<Vec<_>>()
                .join(",");
            format!("{block}[{joined}]")
        } else {
            normalize_id(raw)
        }
    }

    pub fn intern_biome(&mut self, name: &str) -> i32 {
        let canonical = normalize_id(name);
        if let Some(&id) = self.biome_by_name.get(&canonical) {
            return id;
        }
        let id = self.biomes.len() as i32;
        self.biomes.push(canonical.clone());
        self.biome_by_name.insert(canonical, id);
        id
    }

    pub fn biome_name(&self, biome_id: i32) -> String {
        self.biomes
            .get(biome_id as usize)
            .cloned()
            .unwrap_or_else(|| "minecraft:plains".to_string())
    }

    pub fn intern_item(&mut self, name: &str) -> i32 {
        let canonical = normalize_id(name);
        if let Some(&id) = self.item_by_name.get(&canonical) {
            return id;
        }
        let id = self.items.len() as i32;
        self.items.push(canonical.clone());
        self.item_by_name.insert(canonical, id);
        id
    }

    pub fn item_name(&self, item_id: i32) -> String {
        self.items
            .get(item_id as usize)
            .cloned()
            .unwrap_or_else(|| "minecraft:air".to_string())
    }
}

/// Host-free view over `HorizonRegistryCache` for use while `HorizonWorld` holds `&mut dyn ServerHost`.
#[derive(Clone)]
pub struct CachedBlockRegistry {
    cache: Arc<Mutex<HorizonRegistryCache>>,
}

impl CachedBlockRegistry {
    pub fn new(cache: Arc<Mutex<HorizonRegistryCache>>) -> Self {
        Self { cache }
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, HorizonRegistryCache> {
        self.cache.lock().unwrap_or_else(|p| p.into_inner())
    }
}

impl BlockStateRegistry for CachedBlockRegistry {
    fn air(&self) -> i32 {
        0
    }

    fn is_air(&self, state_id: i32) -> bool {
        if state_id == 0 {
            return true;
        }
        self.lock()
            .states
            .get(state_id as usize)
            .is_some_and(|e| e.block_name == "minecraft:air")
    }

    fn state_count(&self) -> usize {
        self.lock().states.len()
    }

    fn parse(&self, input: &str) -> i32 {
        let expanded = expand_colour_shorthand(input);
        let trimmed = expanded.trim();
        let guard = self.lock();
        if let Some(&id) = guard.parse_cache.get(trimmed) {
            return id;
        }
        let canon = HorizonRegistryCache::canonicalize_desc(trimmed);
        guard.by_described.get(&canon).copied().unwrap_or(-1)
    }

    fn default_state(&self, block_name: &str) -> i32 {
        let expanded = expand_colour_shorthand(block_name);
        let base = normalize_id(expanded.split('[').next().unwrap_or(&expanded));
        self.lock().by_described.get(&base).copied().unwrap_or(-1)
    }

    fn name(&self, state_id: i32) -> String {
        self.lock()
            .states
            .get(state_id as usize)
            .map(|e| e.block_name.clone())
            .unwrap_or_else(|| "minecraft:air".to_string())
    }

    fn describe(&self, state_id: i32) -> String {
        self.lock()
            .states
            .get(state_id as usize)
            .map(|e| e.described.clone())
            .unwrap_or_else(|| "minecraft:air".to_string())
    }

    fn properties(&self, state_id: i32) -> BTreeMap<String, String> {
        self.lock()
            .states
            .get(state_id as usize)
            .map(|e| e.props.clone())
            .unwrap_or_default()
    }

    fn property_defs(&self, _state_id: i32) -> BTreeMap<String, Vec<String>> {
        BTreeMap::new()
    }

    fn with_property(&self, state_id: i32, property: &str, value: &str) -> i32 {
        let guard = self.lock();
        let Some(entry) = guard.states.get(state_id as usize) else {
            return -1;
        };
        let mut props = entry.props.clone();
        props.insert(property.to_string(), value.to_ascii_lowercase());
        let body = props
            .iter()
            .map(|(k, v)| format!("{k}={v}"))
            .collect::<Vec<_>>()
            .join(",");
        let desc = format!("{}[{body}]", entry.block_name);
        guard.by_described.get(&desc).copied().unwrap_or(-1)
    }

    fn has_tag(&self, state_id: i32, tag: &str) -> bool {
        let clean = normalize_id(tag.trim().trim_start_matches('#'));
        let guard = self.lock();
        let Some(entry) = guard.states.get(state_id as usize) else {
            return false;
        };
        let Some(hs) = entry.host_state else {
            return false;
        };
        guard
            .tag_cache
            .get(&(hs.block(), clean))
            .copied()
            .unwrap_or(false)
    }

    fn is_solid(&self, state_id: i32) -> bool {
        !self.is_air_like(state_id) && !self.is_liquid(state_id)
    }

    fn is_liquid(&self, state_id: i32) -> bool {
        matches!(
            self.name(state_id).as_str(),
            "minecraft:water" | "minecraft:lava"
        )
    }

    fn is_full_cube(&self, state_id: i32) -> bool {
        self.is_solid(state_id)
    }

    fn block_names(&self) -> Vec<String> {
        let mut out: Vec<String> = self
            .lock()
            .states
            .iter()
            .map(|e| e.block_name.clone())
            .collect();
        out.sort();
        out.dedup();
        out
    }

    fn block_from_item(&self, item_name: &str) -> i32 {
        self.default_state(item_name)
    }

    fn biome(&self, name: &str) -> i32 {
        self.lock().intern_biome(name)
    }

    fn biome_name(&self, biome_id: i32) -> String {
        self.lock().biome_name(biome_id)
    }

    fn biome_names(&self) -> Vec<String> {
        self.lock().biomes.clone()
    }

    fn item_names(&self) -> Vec<String> {
        self.lock().items.clone()
    }

    fn item_name(&self, item_id: i32) -> String {
        self.lock().item_name(item_id)
    }

    fn item(&self, name: &str) -> i32 {
        self.lock().intern_item(name)
    }
}

/// Live `BlockStateRegistry` backed by Horizon's `Host` and a shared `HorizonRegistryCache`.
pub struct HorizonBlockRegistry<'a> {
    host: &'a dyn Host,
    cache: Arc<Mutex<HorizonRegistryCache>>,
}

impl<'a> HorizonBlockRegistry<'a> {
    pub fn new(host: &'a dyn Host, cache: Arc<Mutex<HorizonRegistryCache>>) -> Self {
        Self { host, cache }
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, HorizonRegistryCache> {
        self.cache.lock().unwrap_or_else(|p| p.into_inner())
    }

    pub fn cache(&self) -> &Arc<Mutex<HorizonRegistryCache>> {
        &self.cache
    }

    pub fn host_state(&self, state_id: i32) -> Option<HostBlockState> {
        self.lock().host_state_of(state_id, self.host)
    }

    pub fn intern_host_state(&self, hs: HostBlockState) -> i32 {
        self.lock().intern_host_state(self.host, hs, None)
    }
}

impl BlockStateRegistry for HorizonBlockRegistry<'_> {
    fn air(&self) -> i32 {
        let mut guard = self.lock();
        if guard.states[0].host_state.is_none() {
            if let Some(hs) = BlockStatesHost::block_state_id(self.host, "minecraft:air") {
                guard.states[0].host_state = Some(hs);
                guard.by_host_state.insert(hs, 0);
            }
        }
        0
    }

    fn is_air(&self, state_id: i32) -> bool {
        if state_id == 0 {
            return true;
        }
        let guard = self.lock();
        guard
            .states
            .get(state_id as usize)
            .is_some_and(|e| e.block_name == "minecraft:air")
    }

    fn state_count(&self) -> usize {
        self.lock().states.len()
    }

    fn parse(&self, input: &str) -> i32 {
        let expanded = expand_colour_shorthand(input);
        let trimmed = expanded.trim();
        if trimmed.is_empty() {
            return -1;
        }
        if let Some(&cached) = self.lock().parse_cache.get(trimmed) {
            return cached;
        }
        let Some(hs) = BlockStatesHost::block_state_id(self.host, trimmed) else {
            return -1;
        };
        let mut guard = self.lock();
        let id = guard.intern_host_state(self.host, hs, Some(trimmed));
        guard.parse_cache.insert(trimmed.to_string(), id);
        id
    }

    fn default_state(&self, block_name: &str) -> i32 {
        let expanded = expand_colour_shorthand(block_name);
        let base = expanded.split('[').next().unwrap_or(&expanded).trim();
        if base.is_empty() {
            return -1;
        }
        self.parse(&normalize_id(base))
    }

    fn states_of(&self, block_name: &str) -> Vec<i32> {
        let base = expand_colour_shorthand(block_name);
        let base = normalize_id(base.split('[').next().unwrap_or(&base));
        if let Some(key) = KeysHost::block_key(self.host, &base) {
            if let Some(range) = self.host.block_states().states_of(key) {
                let mut out = Vec::new();
                for hs in range.iter() {
                    out.push(self.lock().intern_host_state(self.host, hs, None));
                }
                if !out.is_empty() {
                    return out;
                }
            }
        }
        let def = self.default_state(&base);
        if def < 0 {
            Vec::new()
        } else {
            vec![def]
        }
    }

    fn name(&self, state_id: i32) -> String {
        self.lock()
            .states
            .get(state_id as usize)
            .map(|e| e.block_name.clone())
            .unwrap_or_else(|| "minecraft:air".to_string())
    }

    fn describe(&self, state_id: i32) -> String {
        self.lock()
            .states
            .get(state_id as usize)
            .map(|e| e.described.clone())
            .unwrap_or_else(|| "minecraft:air".to_string())
    }

    fn properties(&self, state_id: i32) -> BTreeMap<String, String> {
        self.lock()
            .states
            .get(state_id as usize)
            .map(|e| e.props.clone())
            .unwrap_or_default()
    }

    fn property_defs(&self, state_id: i32) -> BTreeMap<String, Vec<String>> {
        let hs = self.lock().host_state_of(state_id, self.host);
        let Some(hs) = hs else {
            return BTreeMap::new();
        };
        let Some(defs) = self.host.block_states().definition(hs.block()) else {
            return BTreeMap::new();
        };
        defs.into_iter().map(|p| (p.name, p.values)).collect()
    }

    fn with_property(&self, state_id: i32, property: &str, value: &str) -> i32 {
        let (hs, block_name, mut props) = {
            let guard = self.lock();
            let Some(entry) = guard.states.get(state_id as usize) else {
                return -1;
            };
            (
                entry.host_state,
                entry.block_name.clone(),
                entry.props.clone(),
            )
        };
        if let Some(hs) = hs {
            if let Ok(updated) = hs.with(&self.host.block_states(), property, value) {
                return self.lock().intern_host_state(self.host, updated, None);
            }
        }
        props.insert(property.to_string(), value.to_ascii_lowercase());
        let prop_str = props
            .iter()
            .map(|(k, v)| format!("{k}={v}"))
            .collect::<Vec<_>>()
            .join(",");
        self.parse(&format!("{block_name}[{prop_str}]"))
    }

    fn has_tag(&self, state_id: i32, tag: &str) -> bool {
        let clean_tag = normalize_id(tag.trim().trim_start_matches('#'));
        let Some(hs) = self.host_state(state_id) else {
            return false;
        };
        let key = (hs.block(), clean_tag.clone());
        if let Some(&cached) = self.lock().tag_cache.get(&key) {
            return cached;
        }
        let ans = TagsHost::block_in_tag(self.host, hs.block(), &clean_tag).unwrap_or(false);
        self.lock().tag_cache.insert(key, ans);
        ans
    }

    fn is_solid(&self, state_id: i32) -> bool {
        if self.is_air_like(state_id) {
            return false;
        }
        if let Some(hs) = self.host_state(state_id) {
            if let Some(rep) = self.host.block_states().replaceable(hs) {
                if rep {
                    return false;
                }
            }
            if let Some(boxes) = TagsHost::state_outline(self.host, hs) {
                return !boxes.is_empty();
            }
        }
        !self.is_liquid(state_id)
    }

    fn is_liquid(&self, state_id: i32) -> bool {
        let name = self.name(state_id);
        if matches!(name.as_str(), "minecraft:water" | "minecraft:lava") {
            return true;
        }
        if let Some(hs) = self.host_state(state_id) {
            if let Some(fs) = TagsHost::state_fluid_state(self.host, hs) {
                return !fs.is_empty();
            }
        }
        false
    }

    fn is_full_cube(&self, state_id: i32) -> bool {
        if self.is_air_like(state_id) {
            return false;
        }
        if let Some(hs) = self.host_state(state_id) {
            if let Some(boxes) = TagsHost::state_outline(self.host, hs) {
                return boxes.len() == 1 && boxes[0] == [0.0, 0.0, 0.0, 1.0, 1.0, 1.0];
            }
        }
        self.is_solid(state_id)
    }

    fn blast_resistance(&self, state_id: i32) -> f64 {
        self.host_state(state_id)
            .and_then(|hs| self.host.block_states().explosion_resistance(hs))
            .map(|v| v as f64)
            .unwrap_or(0.0)
    }

    fn map_color(&self, state_id: i32) -> u32 {
        self.host_state(state_id)
            .and_then(|hs| self.host.block_states().map_color(hs))
            .map(map_color_id_to_rgb)
            .unwrap_or(0)
    }

    fn block_names(&self) -> Vec<String> {
        let keys = KeysHost::block_keys(self.host);
        if !keys.is_empty() {
            let bs = self.host.block_states();
            let mut out: Vec<String> = keys
                .into_iter()
                .filter_map(|k| mod_api::BlockMetadata::name(&bs, k).ok().map(str::to_string))
                .collect();
            out.sort();
            return out;
        }
        let mut names: Vec<String> = self
            .lock()
            .states
            .iter()
            .map(|e| e.block_name.clone())
            .collect();
        names.sort();
        names.dedup();
        names
    }

    fn block_from_item(&self, item_name: &str) -> i32 {
        let norm = normalize_id(item_name);
        if norm == "minecraft:air" {
            return -1;
        }
        let def = self.default_state(&norm);
        if def > 0 {
            return def;
        }
        -1
    }

    fn biome(&self, name: &str) -> i32 {
        let norm = normalize_id(name);
        if KeysHost::biome_key(self.host, &norm).is_some()
            || self.lock().biome_by_name.contains_key(&norm)
        {
            return self.lock().intern_biome(&norm);
        }
        // Accept standard biome names when world registry isn't open yet.
        self.lock().intern_biome(&norm)
    }

    fn biome_name(&self, biome_id: i32) -> String {
        self.lock().biome_name(biome_id)
    }

    fn biome_names(&self) -> Vec<String> {
        if let Some(entries) = KeysHost::registry_entries(self.host, "minecraft:worldgen/biome") {
            let mut out: Vec<String> = entries.into_iter().map(|id| id.to_string()).collect();
            out.sort();
            return out;
        }
        let mut out = self.lock().biomes.clone();
        out.sort();
        out
    }

    fn item_names(&self) -> Vec<String> {
        if let Some(entries) = KeysHost::registry_entries(self.host, "minecraft:item") {
            let mut out: Vec<String> = entries.into_iter().map(|id| id.to_string()).collect();
            out.sort();
            return out;
        }
        let mut out = self.lock().items.clone();
        out.sort();
        out
    }

    fn item_name(&self, item_id: i32) -> String {
        self.lock().item_name(item_id)
    }

    fn item(&self, name: &str) -> i32 {
        self.lock().intern_item(name)
    }
}

#[derive(Clone, Debug)]
struct TestState {
    block: String,
    props: BTreeMap<String, String>,
    described: String,
}

#[derive(Clone, Debug)]
struct TestBlockDef {
    prop_defs: BTreeMap<String, Vec<String>>,
    defaults: BTreeMap<String, String>,
    tags: Vec<String>,
    solid: bool,
    liquid: bool,
    full_cube: bool,
    light: i32,
    blast: f64,
    rgb: u32,
}

/// Pure-Rust `BlockStateRegistry` preloaded with standard Minecraft blocks,
/// properties, tags, wool colours, biomes and items for fast unit testing.
pub struct InMemoryBlockRegistry {
    blocks: BTreeMap<String, TestBlockDef>,
    states: Vec<TestState>,
    by_desc: HashMap<String, i32>,
    biomes: Vec<String>,
    items: Vec<String>,
}

impl Default for InMemoryBlockRegistry {
    fn default() -> Self {
        Self::with_vanilla_subset()
    }
}

impl InMemoryBlockRegistry {
    pub fn with_vanilla_subset() -> Self {
        let mut reg = Self {
            blocks: BTreeMap::new(),
            states: Vec::new(),
            by_desc: HashMap::new(),
            biomes: vec![
                "minecraft:plains".to_string(),
                "minecraft:forest".to_string(),
                "minecraft:desert".to_string(),
                "minecraft:taiga".to_string(),
                "minecraft:savanna".to_string(),
                "minecraft:jungle".to_string(),
                "minecraft:ocean".to_string(),
                "minecraft:nether_wastes".to_string(),
                "minecraft:the_end".to_string(),
            ],
            items: vec![
                "minecraft:air".to_string(),
                "minecraft:wooden_axe".to_string(),
                "minecraft:wooden_shovel".to_string(),
                "minecraft:compass".to_string(),
                "minecraft:stick".to_string(),
                "minecraft:feather".to_string(),
                "minecraft:blaze_rod".to_string(),
                "minecraft:diamond_pickaxe".to_string(),
            ],
        };
        reg.register_simple("minecraft:air", &[], false, false, false, 0, 0.0, 0x000000);
        reg.register_simple("minecraft:cave_air", &[], false, false, false, 0, 0.0, 0x000000);
        reg.register_simple("minecraft:void_air", &[], false, false, false, 0, 0.0, 0x000000);
        reg.register_simple(
            "minecraft:stone",
            &["minecraft:base_stone_overworld"],
            true,
            false,
            true,
            0,
            6.0,
            0x707070,
        );
        reg.register_simple(
            "minecraft:granite",
            &["minecraft:base_stone_overworld"],
            true,
            false,
            true,
            0,
            6.0,
            0x976D4D,
        );
        reg.register_simple(
            "minecraft:diorite",
            &["minecraft:base_stone_overworld"],
            true,
            false,
            true,
            0,
            6.0,
            0xFFFCF5,
        );
        reg.register_simple(
            "minecraft:andesite",
            &["minecraft:base_stone_overworld"],
            true,
            false,
            true,
            0,
            6.0,
            0x707070,
        );
        reg.register_simple(
            "minecraft:cobblestone",
            &[],
            true,
            false,
            true,
            0,
            6.0,
            0x707070,
        );
        reg.register_simple(
            "minecraft:dirt",
            &["minecraft:dirt"],
            true,
            false,
            true,
            0,
            0.5,
            0x976D4D,
        );
        reg.register_simple(
            "minecraft:grass_block",
            &["minecraft:dirt"],
            true,
            false,
            true,
            0,
            0.6,
            0x7FB238,
        );
        reg.register_simple(
            "minecraft:sand",
            &["minecraft:sand"],
            true,
            false,
            true,
            0,
            0.5,
            0xF7E9A3,
        );
        reg.register_simple("minecraft:gravel", &[], true, false, true, 0, 0.6, 0x707070);
        reg.register_simple("minecraft:bedrock", &[], true, false, true, 0, 3_600_000.0, 0x707070);
        reg.register_simple("minecraft:water", &[], false, true, false, 0, 100.0, 0x4040FF);
        reg.register_simple("minecraft:lava", &[], false, true, false, 15, 100.0, 0xFF0000);
        reg.register_simple(
            "minecraft:glass",
            &["minecraft:impermeable"],
            true,
            false,
            false,
            0,
            0.3,
            0xFFFFFF,
        );
        reg.register_simple("minecraft:obsidian", &[], true, false, true, 0, 1200.0, 0x191919);
        reg.register_simple("minecraft:glowstone", &[], true, false, true, 15, 0.3, 0xF7E9A3);
        reg.register_simple(
            "minecraft:stone_bricks",
            &["minecraft:stone_bricks"],
            true,
            false,
            true,
            0,
            6.0,
            0x707070,
        );
        reg.register_simple(
            "minecraft:oak_planks",
            &["minecraft:planks"],
            true,
            false,
            true,
            0,
            3.0,
            0x8F7748,
        );
        reg.register_simple(
            "minecraft:oak_leaves",
            &["minecraft:leaves"],
            true,
            false,
            false,
            0,
            0.2,
            0x007C00,
        );
        reg.register_simple(
            "minecraft:diamond_ore",
            &["minecraft:ores"],
            true,
            false,
            true,
            0,
            3.0,
            0x707070,
        );
        reg.register_simple("minecraft:diamond_block", &[], true, false, true, 0, 6.0, 0x5CDBD5);
        reg.register_simple("minecraft:sandstone", &[], true, false, true, 0, 0.8, 0xF7E9A3);
        reg.register_simple("minecraft:bricks", &[], true, false, true, 0, 6.0, 0x993333);
        reg.register_simple("minecraft:snow_block", &[], true, false, true, 0, 0.2, 0xFFFFFF);
        reg.register_simple("minecraft:ice", &[], true, false, true, 0, 0.5, 0xA0A0FF);
        reg.register_simple("minecraft:clay", &[], true, false, true, 0, 0.6, 0xA4A8B8);
        reg.register_simple("minecraft:netherrack", &[], true, false, true, 0, 0.4, 0x700200);
        reg.register_simple("minecraft:end_stone", &[], true, false, true, 0, 9.0, 0xF7E9A3);

        for (color, rgb) in [
            ("white", 0xFFFFFF),
            ("orange", 0xD87F33),
            ("magenta", 0xB24CD8),
            ("light_blue", 0x6699D8),
            ("yellow", 0xE5E533),
            ("lime", 0x7FCC19),
            ("pink", 0xF27FA5),
            ("gray", 0x4C4C4C),
            ("light_gray", 0x999999),
            ("cyan", 0x4C7F99),
            ("purple", 0x7F3FB2),
            ("blue", 0x334CB2),
            ("brown", 0x664C33),
            ("green", 0x667F33),
            ("red", 0x993333),
            ("black", 0x191919),
        ] {
            reg.register_simple(
                &format!("minecraft:{color}_wool"),
                &["minecraft:wool"],
                true,
                false,
                true,
                0,
                0.8,
                rgb,
            );
            reg.register_simple(
                &format!("minecraft:{color}_concrete"),
                &["minecraft:concrete"],
                true,
                false,
                true,
                0,
                1.8,
                rgb,
            );
        }

        // Blocks with state properties (`oak_log`, `oak_stairs`, `oak_slab`, `chest`).
        reg.register_with_props(
            "minecraft:oak_log",
            &[("axis", &["x", "y", "z"], "y")],
            &["minecraft:logs"],
            true,
            true,
            0x8F7748,
        );
        reg.register_with_props(
            "minecraft:oak_stairs",
            &[
                ("facing", &["north", "east", "south", "west"], "north"),
                ("half", &["bottom", "top"], "bottom"),
                (
                    "shape",
                    &[
                        "straight",
                        "inner_left",
                        "inner_right",
                        "outer_left",
                        "outer_right",
                    ],
                    "straight",
                ),
            ],
            &["minecraft:stairs"],
            true,
            false,
            0x8F7748,
        );
        reg.register_with_props(
            "minecraft:oak_slab",
            &[("type", &["bottom", "top", "double"], "bottom")],
            &["minecraft:slabs"],
            true,
            false,
            0x8F7748,
        );
        reg.register_with_props(
            "minecraft:chest",
            &[
                ("facing", &["north", "east", "south", "west"], "north"),
                ("type", &["single", "left", "right"], "single"),
            ],
            &[],
            true,
            false,
            0x8F7748,
        );
        reg
    }

    #[allow(clippy::too_many_arguments)]
    pub fn register_simple(
        &mut self,
        name: &str,
        tags: &[&str],
        solid: bool,
        liquid: bool,
        full_cube: bool,
        light: i32,
        blast: f64,
        rgb: u32,
    ) {
        let block = normalize_id(name);
        self.blocks.insert(
            block.clone(),
            TestBlockDef {
                prop_defs: BTreeMap::new(),
                defaults: BTreeMap::new(),
                tags: tags.iter().map(|s| normalize_id(s)).collect(),
                solid,
                liquid,
                full_cube,
                light,
                blast,
                rgb,
            },
        );
        self.intern_state(&block, BTreeMap::new());
        if !self.items.contains(&block) {
            self.items.push(block);
        }
    }

    pub fn register_with_props(
        &mut self,
        name: &str,
        props: &[(&str, &[&str], &str)],
        tags: &[&str],
        solid: bool,
        full_cube: bool,
        rgb: u32,
    ) {
        let block = normalize_id(name);
        let mut prop_defs = BTreeMap::new();
        let mut defaults = BTreeMap::new();
        for (k, vals, def) in props {
            prop_defs.insert(
                (*k).to_string(),
                vals.iter().map(|v| (*v).to_string()).collect(),
            );
            defaults.insert((*k).to_string(), (*def).to_string());
        }
        self.blocks.insert(
            block.clone(),
            TestBlockDef {
                prop_defs: prop_defs.clone(),
                defaults: defaults.clone(),
                tags: tags.iter().map(|s| normalize_id(s)).collect(),
                solid,
                liquid: false,
                full_cube,
                light: 0,
                blast: 3.0,
                rgb,
            },
        );
        // Intern default first so default_state returns the base state, then all combinations.
        self.intern_state(&block, defaults);
        let keys: Vec<(String, Vec<String>)> = prop_defs.into_iter().collect();
        let mut current = BTreeMap::new();
        self.enumerate_states(&block, &keys, 0, &mut current);
        if !self.items.contains(&block) {
            self.items.push(block);
        }
    }

    fn enumerate_states(
        &mut self,
        block: &str,
        keys: &[(String, Vec<String>)],
        idx: usize,
        current: &mut BTreeMap<String, String>,
    ) {
        if idx == keys.len() {
            self.intern_state(block, current.clone());
            return;
        }
        let (ref name, ref values) = keys[idx];
        for val in values {
            current.insert(name.clone(), val.clone());
            self.enumerate_states(block, keys, idx + 1, current);
        }
        current.remove(name);
    }

    fn format_desc(block: &str, props: &BTreeMap<String, String>) -> String {
        if props.is_empty() {
            block.to_string()
        } else {
            let body = props
                .iter()
                .map(|(k, v)| format!("{k}={v}"))
                .collect::<Vec<_>>()
                .join(",");
            format!("{block}[{body}]")
        }
    }

    fn intern_state(&mut self, block: &str, props: BTreeMap<String, String>) -> i32 {
        let desc = Self::format_desc(block, &props);
        if let Some(&id) = self.by_desc.get(&desc) {
            return id;
        }
        let id = self.states.len() as i32;
        self.states.push(TestState {
            block: block.to_string(),
            props,
            described: desc.clone(),
        });
        self.by_desc.insert(desc, id);
        id
    }
}

impl BlockStateRegistry for InMemoryBlockRegistry {
    fn air(&self) -> i32 {
        0
    }

    fn is_air(&self, state_id: i32) -> bool {
        self.states
            .get(state_id as usize)
            .is_some_and(|s| s.block == "minecraft:air")
    }

    fn state_count(&self) -> usize {
        self.states.len()
    }

    fn parse(&self, input: &str) -> i32 {
        let Some((block, pairs)) = parse_state_syntax(input) else {
            return -1;
        };
        let Some(def) = self.blocks.get(&block) else {
            return -1;
        };
        let mut props = def.defaults.clone();
        for (k, v) in pairs {
            let Some(allowed) = def.prop_defs.get(&k) else {
                return -1;
            };
            if !allowed.iter().any(|a| a.eq_ignore_ascii_case(&v)) {
                return -1;
            }
            props.insert(k, v.to_ascii_lowercase());
        }
        let desc = Self::format_desc(&block, &props);
        self.by_desc.get(&desc).copied().unwrap_or(-1)
    }

    fn default_state(&self, block_name: &str) -> i32 {
        let expanded = expand_colour_shorthand(block_name);
        let base = normalize_id(expanded.split('[').next().unwrap_or(&expanded));
        let Some(def) = self.blocks.get(&base) else {
            return -1;
        };
        let desc = Self::format_desc(&base, &def.defaults);
        self.by_desc.get(&desc).copied().unwrap_or(-1)
    }

    fn states_of(&self, block_name: &str) -> Vec<i32> {
        let expanded = expand_colour_shorthand(block_name);
        let base = normalize_id(expanded.split('[').next().unwrap_or(&expanded));
        self.states
            .iter()
            .enumerate()
            .filter(|(_, s)| s.block == base)
            .map(|(i, _)| i as i32)
            .collect()
    }

    fn name(&self, state_id: i32) -> String {
        self.states
            .get(state_id as usize)
            .map(|s| s.block.clone())
            .unwrap_or_else(|| "minecraft:air".to_string())
    }

    fn describe(&self, state_id: i32) -> String {
        self.states
            .get(state_id as usize)
            .map(|s| s.described.clone())
            .unwrap_or_else(|| "minecraft:air".to_string())
    }

    fn properties(&self, state_id: i32) -> BTreeMap<String, String> {
        self.states
            .get(state_id as usize)
            .map(|s| s.props.clone())
            .unwrap_or_default()
    }

    fn property_defs(&self, state_id: i32) -> BTreeMap<String, Vec<String>> {
        let Some(state) = self.states.get(state_id as usize) else {
            return BTreeMap::new();
        };
        self.blocks
            .get(&state.block)
            .map(|d| d.prop_defs.clone())
            .unwrap_or_default()
    }

    fn with_property(&self, state_id: i32, property: &str, value: &str) -> i32 {
        let Some(state) = self.states.get(state_id as usize) else {
            return -1;
        };
        let Some(def) = self.blocks.get(&state.block) else {
            return -1;
        };
        let Some(allowed) = def.prop_defs.get(property) else {
            return -1;
        };
        let val_lower = value.to_ascii_lowercase();
        if !allowed.iter().any(|a| a == &val_lower) {
            return -1;
        }
        let mut props = state.props.clone();
        props.insert(property.to_string(), val_lower);
        let desc = Self::format_desc(&state.block, &props);
        self.by_desc.get(&desc).copied().unwrap_or(-1)
    }

    fn has_tag(&self, state_id: i32, tag: &str) -> bool {
        let clean = normalize_id(tag.trim().trim_start_matches('#'));
        let Some(state) = self.states.get(state_id as usize) else {
            return false;
        };
        self.blocks
            .get(&state.block)
            .is_some_and(|d| d.tags.contains(&clean))
    }

    fn is_solid(&self, state_id: i32) -> bool {
        self.states
            .get(state_id as usize)
            .and_then(|s| self.blocks.get(&s.block))
            .is_some_and(|d| d.solid)
    }

    fn is_liquid(&self, state_id: i32) -> bool {
        self.states
            .get(state_id as usize)
            .and_then(|s| self.blocks.get(&s.block))
            .is_some_and(|d| d.liquid)
    }

    fn is_full_cube(&self, state_id: i32) -> bool {
        self.states
            .get(state_id as usize)
            .and_then(|s| self.blocks.get(&s.block))
            .is_some_and(|d| d.full_cube)
    }

    fn light_emission(&self, state_id: i32) -> i32 {
        self.states
            .get(state_id as usize)
            .and_then(|s| self.blocks.get(&s.block))
            .map_or(0, |d| d.light)
    }

    fn blast_resistance(&self, state_id: i32) -> f64 {
        self.states
            .get(state_id as usize)
            .and_then(|s| self.blocks.get(&s.block))
            .map_or(0.0, |d| d.blast)
    }

    fn map_color(&self, state_id: i32) -> u32 {
        self.states
            .get(state_id as usize)
            .and_then(|s| self.blocks.get(&s.block))
            .map_or(0, |d| d.rgb)
    }

    fn block_names(&self) -> Vec<String> {
        self.blocks.keys().cloned().collect()
    }

    fn block_from_item(&self, item_name: &str) -> i32 {
        let norm = normalize_id(item_name);
        if norm == "minecraft:air" {
            return -1;
        }
        self.default_state(&norm)
    }

    fn biome(&self, name: &str) -> i32 {
        let norm = normalize_id(name);
        self.biomes
            .iter()
            .position(|b| b == &norm)
            .map(|i| i as i32)
            .unwrap_or(-1)
    }

    fn biome_name(&self, biome_id: i32) -> String {
        self.biomes
            .get(biome_id as usize)
            .cloned()
            .unwrap_or_else(|| "minecraft:plains".to_string())
    }

    fn biome_names(&self) -> Vec<String> {
        self.biomes.clone()
    }

    fn item_names(&self) -> Vec<String> {
        self.items.clone()
    }

    fn item_name(&self, item_id: i32) -> String {
        self.items
            .get(item_id as usize)
            .cloned()
            .unwrap_or_else(|| "minecraft:air".to_string())
    }

    fn item(&self, name: &str) -> i32 {
        let norm = normalize_id(name);
        self.items
            .iter()
            .position(|i| i == &norm)
            .map(|i| i as i32)
            .unwrap_or(-1)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::math::BlockVector3;
    use crate::transform::{BlockStateTransform, Transform};

    #[test]
    fn colour_shorthands_categories_legacy_and_state_transforms_work() {
        let mut reg = InMemoryBlockRegistry::with_vanilla_subset();
        let orange = reg.parse("orange");
        assert!(orange > 0);
        assert_eq!(reg.name(orange), "minecraft:orange_wool");
        assert!(reg.matches_category(orange, "wool"));

        let log_x = reg.legacy_state(17, 1);
        assert_eq!(reg.describe(log_x), "minecraft:oak_log[axis=x]");
        assert_eq!(reg.legacy_id(log_x), 17);
        assert_eq!(reg.legacy_metadata(log_x), 1);

        let stairs_n = reg.parse("oak_stairs[facing=north,half=bottom]");
        let mut rot90 = BlockStateTransform::of(&Transform::rotate_y(BlockVector3::ZERO, 90.0)).unwrap();
        let stairs_w = rot90.apply(&mut reg, stairs_n as u32) as i32;
        assert_eq!(BlockStateRegistry::properties(&reg, stairs_w).get("facing").unwrap(), "west");
    }
}
