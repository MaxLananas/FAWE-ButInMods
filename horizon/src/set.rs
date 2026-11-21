//! Selection, bulk `//set`, and `//undo` / `//redo` commands backed by
//! `SessionManager`, `HorizonBlockRegistry`, `HorizonWorld`, and `EditSession`.

use std::sync::{Arc, Mutex};

use mod_api::{
    BlockRun, Command, CommandArgumentValue, Flow, RunModCommandEvent, ServerHost, Text,
};

use crate::region::{BlockVector3, CuboidRegion, RegionShape};
use crate::registry::{
    BlockStateRegistry, CachedBlockRegistry, HorizonBlockRegistry, HorizonRegistryCache,
};
use crate::selection::{SelectionKey, SelectorKind};
use crate::session::{replay_history_record, EditSession, SessionManager};
use crate::world::{Extent, HorizonWorld};

pub(crate) fn register(event: &mut mod_api::RegisterCommandsEvent<'_>) {
    event.commands.extend([
        Command::op("fawebim pos1 <x:integer> <y:integer> <z:integer>", 2),
        Command::op("fawebim pos2 <x:integer> <y:integer> <z:integer>", 2),
        Command::op("fawebim sel [mode:text]", 2),
        Command::op("fawebim set <block:text>", 2),
        Command::op("fawebim undo [steps:integer]", 2),
        Command::op("fawebim redo [steps:integer]", 2),
        Command::op("fawebim clearhistory", 2),
    ]);
}

pub(crate) fn run(
    host: &mut dyn ServerHost,
    event: &RunModCommandEvent<'_>,
    sessions: &Mutex<SessionManager>,
    registry_cache: &Arc<Mutex<HorizonRegistryCache>>,
) -> Flow {
    let words = &event.command.literals;
    if words.first().map(String::as_str) != Some("fawebim") {
        return Flow::Continue;
    }
    match words.get(1).map(String::as_str) {
        Some("pos1") => pos(host, event, sessions, Corner::One),
        Some("pos2") => pos(host, event, sessions, Corner::Two),
        Some("sel") => sel(host, sessions, event),
        Some("set") => set(host, event, sessions, registry_cache),
        Some("undo") => history_step(host, event, sessions, registry_cache, true),
        Some("redo") => history_step(host, event, sessions, registry_cache, false),
        Some("clearhistory") => {
            lock_sessions(sessions).get(source_key(event.source)).history.clear();
            feedback(host, "fawebim clearhistory: history cleared");
            Flow::Cancel
        }
        _ => Flow::Continue,
    }
}

fn lock_sessions(sessions: &Mutex<SessionManager>) -> std::sync::MutexGuard<'_, SessionManager> {
    sessions.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
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

fn resolve_dimension(host: &dyn ServerHost, event: &RunModCommandEvent<'_>) -> String {
    event
        .command
        .dimension
        .id(host)
        .unwrap_or_else(|| host.dimension().to_string())
}

fn pos(
    host: &mut dyn ServerHost,
    event: &RunModCommandEvent<'_>,
    sessions: &Mutex<SessionManager>,
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
    let mut mgr = lock_sessions(sessions);
    let selection = &mut mgr.get(key).selection;
    match corner {
        Corner::One => selection.set_pos1(position),
        Corner::Two => selection.set_pos2(position),
    }
    feedback(host, &format!("fawebim {usage}: ({x}, {y}, {z})"));
    Flow::Cancel
}

fn sel(
    host: &mut dyn ServerHost,
    sessions: &Mutex<SessionManager>,
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
            lock_sessions(sessions).get(key).selection.clear();
            feedback(host, "fawebim sel: selection cleared");
            return Flow::Cancel;
        }
        if let Some(kind) = SelectorKind::parse(&lower) {
            let mut mgr = lock_sessions(sessions);
            mgr.get(key).selection.switch_kind(kind, -64, 319);
            feedback(
                host,
                &format!("fawebim sel: {} — {}", kind.as_str(), kind.usage()),
            );
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
    let text = lock_sessions(sessions)
        .peek(key)
        .map(|s| s.selection.describe())
        .unwrap_or_else(|| "no selection (pos1 and pos2 unset)".to_string());
    feedback(host, &format!("fawebim sel: {text}"));
    Flow::Cancel
}

fn set(
    host: &mut dyn ServerHost,
    event: &RunModCommandEvent<'_>,
    sessions: &Mutex<SessionManager>,
    registry_cache: &Arc<Mutex<HorizonRegistryCache>>,
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
    let mut region = {
        let mgr = lock_sessions(sessions);
        mgr.peek(key).and_then(|s| s.selection.region())
    };
    let Some(ref mut region) = region else {
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

    let state_id = {
        let reg = HorizonBlockRegistry::new(host, registry_cache.clone());
        // Pre-warm air so undo can resolve slot 0 without extra lookups.
        let _ = reg.air();
        let parsed = reg.parse(&block_name);
        if parsed < 0 {
            feedback(host, &format!("fawebim set: '{block_name}' is not a block"));
            return Flow::Cancel;
        }
        parsed
    };

    let dimension = resolve_dimension(host, event);
    let mut world = HorizonWorld::new(host, dimension, registry_cache.clone());
    let mut cached_reg = CachedBlockRegistry::new(registry_cache.clone());

    let res = {
        let mut mgr = lock_sessions(sessions);
        let mut session = mgr.get(key).clone();
        let mut edit = EditSession::open(
            &mut world,
            &mut cached_reg,
            &mut session,
            format!("//set {block_name}"),
            true,
        );
        region.for_each_position(|x, y, z| {
            edit.set_block(x, y, z, state_id);
        });
        let finish_res = edit.finish(&mut session, Some(&mut mgr.edit_log));
        *mgr.get(key) = session;
        finish_res
    };

    match res {
        Ok(count) => feedback(
            host,
            &format!("fawebim set: {count} block(s) set to {block_name}"),
        ),
        Err(error) => feedback(host, &format!("fawebim set: the edit was refused: {error}")),
    }
    Flow::Cancel
}

fn history_step(
    host: &mut dyn ServerHost,
    event: &RunModCommandEvent<'_>,
    sessions: &Mutex<SessionManager>,
    registry_cache: &Arc<Mutex<HorizonRegistryCache>>,
    undo: bool,
) -> Flow {
    let verb = if undo { "undo" } else { "redo" };
    let past = if undo { "undone" } else { "redone" };
    let steps = integer(event, "steps").unwrap_or(1);
    if steps <= 0 {
        feedback(host, &format!("fawebim {verb}: steps must be >= 1"));
        return Flow::Cancel;
    }
    let steps = (steps as usize).min(100);
    let key = source_key(event.source);

    // Pre-warm air slot in case undo restores `0` (`minecraft:air`).
    {
        let reg = HorizonBlockRegistry::new(host, registry_cache.clone());
        let _ = reg.air();
    }

    let fallback_dim = resolve_dimension(host, event);
    let mut records_to_apply = Vec::new();
    {
        let mut mgr = lock_sessions(sessions);
        let session = mgr.get(key);
        for _ in 0..steps {
            let rec_opt = if undo {
                session.history.undo().cloned()
            } else {
                session.history.redo().cloned()
            };
            let Some(rec) = rec_opt else {
                break;
            };
            records_to_apply.push(rec);
        }
    }

    if records_to_apply.is_empty() {
        feedback(host, &format!("fawebim {verb}: nothing to {verb}"));
        return Flow::Cancel;
    }

    let mut total_blocks = 0u64;
    for rec in &records_to_apply {
        let dim = rec
            .world
            .clone()
            .unwrap_or_else(|| fallback_dim.clone());
        let mut world = HorizonWorld::new(host, dim, registry_cache.clone());
        match replay_history_record(&mut world, rec, undo) {
            Ok(changed) => total_blocks += changed,
            Err(err) => {
                feedback(
                    host,
                    &format!("fawebim {verb}: failed to replay '{}': {err}", rec.description),
                );
                return Flow::Cancel;
            }
        }
    }

    feedback(
        host,
        &format!(
            "fawebim {verb}: {} edit(s) {past} ({total_blocks} block(s))",
            records_to_apply.len()
        ),
    );
    Flow::Cancel
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
