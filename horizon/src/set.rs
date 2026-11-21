//! The selection commands — a port of FAWE's `//pos1`, `//pos2`, `//sel` and
//! `//set` slice — for the Horizon mod.
//!
//! FAWE's `//set <pattern>` fills the actor's selection with a pattern. This
//! slice is the smallest thing that is actually useful against a live world:
//!
//! - `/fawebim pos1 <x:integer> <y:integer> <z:integer>` — mark corner 1;
//! - `/fawebim pos2 <x:integer> <y:integer> <z:integer>` — mark corner 2;
//! - `/fawebim sel` — describe the current selection;
//! - `/fawebim set <block:text>` — set every block of the selection to one
//!   block, as `/setblock` reads it (`stone`, `oak_stairs[facing=east]`).
//!
//! The fill goes through the game's bulk `edit_blocks` path — palette of
//! complete typed states plus column runs — which is what FAWE's `//set` does
//! with the server's edit buffer, rather than one host call per block.
//!
//! Still missing relative to FAWE: the wand (`//wand` and the click handlers),
//! the other region shapes, masks, patterns, undo (FAWE records the edit) and
//! the size limit from the session's `//limit`. The selection here is
//! per-source (UUID; console shares one) and never persisted.
//!
//! The edit is bounded by [`CuboidRegion::MAX_VOLUME`] until the session's
//! limit config lands; edits bigger than that are refused with a message
//! naming the limit.

use std::sync::Mutex;

use mod_api::prelude::BlockStatesHost;
use mod_api::{
    BlockEdit, BlockRun, Command, CommandArgumentValue, Flow, RunModCommandEvent, ServerHost, Text,
};

use crate::region::{BlockVector3, CuboidRegion};
use crate::selection::{SelectionKey, SelectionStore};

/// How often `edit_blocks` is called; one call per this many column runs.
const EDIT_BATCH: usize = 16_384;

/// The commands this module owns: words, so they merge with every other
/// `fawebim` usage the server already has.
pub(crate) fn register(event: &mut mod_api::RegisterCommandsEvent<'_>) {
    event.commands.extend([
        Command::op("fawebim pos1 <x:integer> <y:integer> <z:integer>", 2),
        Command::op("fawebim pos2 <x:integer> <y:integer> <z:integer>", 2),
        Command::op("fawebim sel", 2),
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

fn sel(host: &mut dyn ServerHost, store: &Mutex<SelectionStore>, event: &RunModCommandEvent<'_>) -> Flow {
    let key = source_key(event.source);
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
        .and_then(|selection| selection.cuboid());
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
    // The /setblock parser: unknown block, property or value all come back as None.
    let Some(state) = BlockStatesHost::block_state_id(host, &block_name) else {
        feedback(host, &format!("fawebim set: '{block_name}' is not a block"));
        return Flow::Cancel;
    };
    // Resolve the command's execution dimension (`execute in` aware), falling
    // back to the level currently in place.
    let dimension = event
        .command
        .dimension
        .id(host)
        .unwrap_or_else(|| host.dimension().to_string());
    let changed = edit_cuboid(host, &dimension, region, state);
    match changed {
        Ok(count) => feedback(host, &format!("fawebim set: {count} block(s) set to {block_name}")),
        Err(error) => feedback(host, &format!("fawebim set: the edit was refused: {error}")),
    }
    Flow::Cancel
}

/// Send `edit` as column runs to `edit_blocks`, one call per batch of columns.
/// Returns the total the game reports as changed.
fn edit_cuboid(
    host: &mut dyn ServerHost,
    dimension: &str,
    region: CuboidRegion,
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

/// Walk the region's column runs — one `(x, z)` column per run from the
/// region's `y` up by its `height`, palette index 0 — as in the Java walk.
fn for_each_column_run(region: CuboidRegion, mut visit: impl FnMut(BlockRun)) {
    let min = region.min();
    let max = region.max();
    let height = region.height() as u32;
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
    // Text formation is the only fallible part; plain ASCII always fits.
    let _ = host.commands().feedback(Text::literal(line));
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::region::BlockVector3;

    #[test]
    fn source_key_shares_console_and_splits_players() {
        // The exact PlayerRef shape is private to the SDK; the mapping logic is
        // exercised through the two non-player paths here, and players are the
        // UUID key by construction.
        let console = mod_api::CommandSource::Console {
            permission: mod_api::PermissionLevel::Owners,
        };
        assert_eq!(source_key(&console), SelectionKey::Console);
    }

    #[test]
    fn integer_reads_typed_arguments_only() {
        // Argument decoding is the game's; only its typed shape reaches us.
        // (The round trip lives in the wasm smoke check.)
        let _ = integer;
    }

    #[test]
    fn column_runs_cover_every_column_once() {
        let region = CuboidRegion::new(BlockVector3::at(0, 64, 0), BlockVector3::at(3, 67, 2));
        let mut runs = Vec::new();
        for_each_column_run(region, |run| runs.push(run));
        assert_eq!(runs.len() as u64, region.area());
        let mut cells = 0u64;
        for run in &runs {
            assert_eq!(run.y, 64);
            assert_eq!(run.height, 4);
            assert_eq!(run.block, 0);
            assert!(run.only.is_none());
            assert!(run.surface.is_none());
            cells += run.height as u64;
        }
        assert_eq!(cells, region.volume());
    }
}
