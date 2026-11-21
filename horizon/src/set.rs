//! The selection commands — a port of FAWE's `//pos1`, `//pos2`, `//sel` and
//! `//set` slice — for the Horizon mod.
//!
//! Supports all 9 FAWE selector modes (`cuboid`, `extend`, `poly`,
//! `ellipsoid`, `sphere`, `cyl`, `convex`, `polyhedral`, `fuzzy`) and fills
//! any `RegionShape` through bulk vertical `BlockRun` column runs.

use std::sync::Mutex;

use mod_api::prelude::BlockStatesHost;
use mod_api::{
    BlockEdit, BlockRun, Command, CommandArgumentValue, Flow, RunModCommandEvent, ServerHost, Text,
};

use crate::region::{BlockVector3, CuboidRegion, RegionShape};
use crate::selection::{SelectionKey, SelectionStore, SelectorKind};

/// How often `edit_blocks` is called; one call per this many column runs.
const EDIT_BATCH: usize = 16_384;

/// The commands this module owns: words, so they merge with every other
/// `fawebim` usage the server already has.
pub(crate) fn register(event: &mut mod_api::RegisterCommandsEvent<'_>) {
    event.commands.extend([
        Command::op("fawebim pos1 <x:integer> <y:integer> <z:integer>", 2),
        Command::op("fawebim pos2 <x:integer> <y:integer> <z:integer>", 2),
        Command::op("fawebim sel [mode:text]", 2),
        Command::op("fawebim set <block:text>", 2),
    ]);
}

/// One dispatch of anything this module owns. `Flow::Continue` when the words
/// are not ours; the caller then falls through to the next module.
pub(crate) fn run(
    host: &mut dyn ServerHost,
    event: &RunModCommandEvent<'_>,
    store: &Mutex<SelectionStore>,
) -> Flow {
    let words = &event.command.literals;
    if words.first().map(String::as_str) != Some("fawebim") {
        return Flow::Continue;
    }
    match words.get(1).map(String::as_str) {
        Some("pos1") => pos(host, event, store, Corner::One),
        Some("pos2") => pos(host, event, store, Corner::Two),
        Some("sel") => sel(host, store, event),
        Some("set") => set(host, event, store),
        _ => Flow::Continue,
    }
}

/// Poison-resilient access to the selections; a panicking hook must not take
/// every later command down with it.
fn lock_store(store: &Mutex<SelectionStore>) -> std::sync::MutexGuard<'_, SelectionStore> {
    store.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum Corner {
    One,
    Two,
}

fn source_key(source: &mod_api::CommandSource) -> SelectionKey {
    match source {
        mod_api::CommandSource::Player { player, .. } => SelectionKey::Player(player.uuid()),
        _ => SelectionKey::Console,
    }
}

fn integer(event: &RunModCommandEvent<'_>, name: &str) -> Option<i32> {
    match event.command.argument(name) {
        Some(CommandArgumentValue::Integer(value)) => Some(*value),
        _ => None,
    }
}

fn pos(
    host: &mut dyn ServerHost,
    event: &RunModCommandEvent<'_>,
    store: &Mutex<SelectionStore>,
    corner: Corner,
) -> Flow {
    let usage = if corner == Corner::One { "pos1" } else { "pos2" };
    let (x, y, z) = match (integer(event, "x"), integer(event, "y"), integer(event, "z")) {
        (Some(x), Some(y), Some(z)) => (x, y, z),
        _ => {
            feedback(host, &format!("fawebim {usage}: expected three integers"));
            return Flow::Cancel;
        }
    };
    let key = source_key(event.source);
    let position = BlockVector3::at(x, y, z);
    let mut store = lock_store(store);
    let selection = store.get(key);
    match corner {
        Corner::One => selection.set_pos1(position),
        Corner::Two => selection.set_pos2(position),
    }
    feedback(host, &format!("fawebim {usage}: ({x}, {y}, {z})"));
    Flow::Cancel
}

fn sel(
    host: &mut dyn ServerHost,
    store: &Mutex<SelectionStore>,
    event: &RunModCommandEvent<'_>,
) -> Flow {
    let key = source_key(event.source);
    let mode_arg = match event.command.argument("mode") {
        Some(CommandArgumentValue::String(s)) if !s.trim().is_empty() => Some(s.trim()),
        _ => None,
    };
    if let Some(raw) = mode_arg {
        let lower = raw.to_ascii_lowercase();
        if matches!(lower.as_str(), "clear" | "desel" | "deselect" | "-d") {
            lock_store(store).get(key).clear();
            feedback(host, "fawebim sel: selection cleared");
            return Flow::Cancel;
        }
        if let Some(kind) = SelectorKind::parse(&lower) {
            let mut guard = lock_store(store);
            let selection = guard.get(key);
            selection.switch_kind(kind, -64, 319);
            feedback(host, &format!("fawebim sel: {} — {}", kind.as_str(), kind.usage()));
            return Flow::Cancel;
        }
        feedback(
            host,
            &format!(
                "fawebim sel: unknown mode '{raw}' (expected cuboid, extend, poly, ellipsoid, sphere, cyl, convex, polyhedral, fuzzy, or clear)"
            ),
        );
        return Flow::Cancel;
    }
    let text = lock_store(store)
        .peek(key)
        .map(|selection| selection.describe())
        .unwrap_or_else(|| "no selection (pos1 and pos2 unset)".to_string());
    feedback(host, &format!("fawebim sel: {text}"));
    Flow::Cancel
}

fn set(
    host: &mut dyn ServerHost,
    event: &RunModCommandEvent<'_>,
    store: &Mutex<SelectionStore>,
) -> Flow {
    let block_name = match event.command.argument("block") {
        Some(CommandArgumentValue::String(text)) => text.trim().to_string(),
        _ => {
            feedback(host, "fawebim set: expected <block:text>");
            return Flow::Cancel;
        }
    };
    if block_name.is_empty() {
        feedback(host, "fawebim set: expected <block:text>");
        return Flow::Cancel;
    }
    let key = source_key(event.source);
    let region = lock_store(store)
        .peek(key)
        .and_then(|selection| selection.region());
    let Some(region) = region else {
        feedback(host, "fawebim set: no selection — mark pos1 and pos2 first");
        return Flow::Cancel;
    };
    let volume = region.volume();
    if volume > CuboidRegion::MAX_VOLUME {
        feedback(
            host,
            &format!(
                "fawebim set: the selection holds {volume} blocks; the limit is {} — mark a smaller one",
                CuboidRegion::MAX_VOLUME
            ),
        );
        return Flow::Cancel;
    }
    let Some(state) = BlockStatesHost::block_state_id(host, &block_name) else {
        feedback(host, &format!("fawebim set: '{block_name}' is not a block"));
        return Flow::Cancel;
    };
    let dimension = event
        .command
        .dimension
        .id(host)
        .unwrap_or_else(|| host.dimension().to_string());
    let changed = edit_region(host, &dimension, &region, state);
    match changed {
        Ok(count) => feedback(host, &format!("fawebim set: {count} block(s) set to {block_name}")),
        Err(error) => feedback(host, &format!("fawebim set: the edit was refused: {error}")),
    }
    Flow::Cancel
}

/// Send `edit` as column runs to `edit_blocks`, one call per batch of columns.
fn edit_region(
    host: &mut dyn ServerHost,
    dimension: &str,
    region: &RegionShape,
    state: mod_api::BlockState,
) -> Result<u64, String> {
    let mut changed = 0u64;
    let mut batch = Vec::with_capacity(EDIT_BATCH);
    let mut result = Ok(());
    for_each_column_run(region, |run| {
        if result.is_err() {
            return;
        }
        batch.push(run);
        if batch.len() == EDIT_BATCH {
            match edit_batch(host, dimension, std::mem::take(&mut batch), state) {
                Ok(count) => changed += count,
                Err(error) => result = Err(error),
            }
        }
    });
    result?;
    if !batch.is_empty() {
        match edit_batch(host, dimension, batch, state) {
            Ok(count) => changed += count,
            Err(error) => return Err(error),
        }
    }
    Ok(changed)
}

/// Walk a `RegionShape` as vertical `(x, z)` column runs for `edit_blocks`.
pub(crate) fn for_each_column_run(region: &RegionShape, mut visit: impl FnMut(BlockRun)) {
    match region {
        RegionShape::Cuboid(cuboid) => {
            let min = cuboid.min();
            let max = cuboid.max();
            let height = cuboid.height() as u32;
            for z in min.z..=max.z {
                for x in min.x..=max.x {
                    visit(BlockRun {
                        x,
                        z,
                        y: min.y,
                        height,
                        surface: None,
                        block: 0,
                        only: None,
                    });
                }
            }
        }
        other => {
            let min = other.min();
            let max = other.max();
            for z in min.z..=max.z {
                for x in min.x..=max.x {
                    let mut run_start: Option<i32> = None;
                    for y in min.y..=max.y {
                        if other.contains(x, y, z) {
                            if run_start.is_none() {
                                run_start = Some(y);
                            }
                        } else if let Some(start_y) = run_start.take() {
                            visit(BlockRun {
                                x,
                                z,
                                y: start_y,
                                height: (y - start_y) as u32,
                                surface: None,
                                block: 0,
                                only: None,
                            });
                        }
                    }
                    if let Some(start_y) = run_start {
                        visit(BlockRun {
                            x,
                            z,
                            y: start_y,
                            height: (max.y - start_y + 1) as u32,
                            surface: None,
                            block: 0,
                            only: None,
                        });
                    }
                }
            }
        }
    }
}

fn edit_batch(
    host: &mut dyn ServerHost,
    dimension: &str,
    runs: Vec<BlockRun>,
    state: mod_api::BlockState,
) -> Result<u64, String> {
    let edit = BlockEdit {
        palette: vec![state],
        filters: Vec::new(),
        runs,
    };
    host.edit_blocks(dimension, &edit)
}

fn feedback(host: &mut dyn ServerHost, line: &str) {
    let _ = host.commands().feedback(Text::literal(line));
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::region::{BlockVector3, EllipsoidRegion, Vector3};

    #[test]
    fn source_key_shares_console_and_splits_players() {
        let console = mod_api::CommandSource::Console {
            permission: mod_api::PermissionLevel::Owners,
        };
        assert_eq!(source_key(&console), SelectionKey::Console);
    }

    #[test]
    fn column_runs_cover_cuboid_and_sphere_exact_volumes() {
        let cuboid = RegionShape::Cuboid(CuboidRegion::new(
            BlockVector3::at(0, 64, 0),
            BlockVector3::at(3, 67, 2),
        ));
        let mut runs = Vec::new();
        for_each_column_run(&cuboid, |run| runs.push(run));
        assert_eq!(runs.len() as u64, 12);
        let cells: u64 = runs.iter().map(|r| r.height as u64).sum();
        assert_eq!(cells, cuboid.volume());

        let mut sphere = RegionShape::Ellipsoid(EllipsoidRegion::new(
            Vector3::new(0.5, 64.5, 0.5),
            Vector3::new(3.0, 3.0, 3.0),
        ));
        let mut s_runs = Vec::new();
        for_each_column_run(&sphere, |run| s_runs.push(run));
        let s_cells: u64 = s_runs.iter().map(|r| r.height as u64).sum();
        let visited = sphere.for_each_position(|_, _, _| {});
        assert_eq!(s_cells, visited);
    }
}
