//! Selection, bulk `//set`, and `//undo` / `//redo` commands backed by
//! `SessionManager`, `HorizonBlockRegistry`, `HorizonWorld`, and `EditSession`.

use std::collections::{HashMap, HashSet, VecDeque};
use std::sync::{Arc, Mutex};

use mod_api::{
    BlockRun, Command, CommandArgumentValue, ConfigPath, Flow, RunModCommandEvent, ServerHost, Text,
};

use crate::brush::{self, BrushManager};
use crate::clipboard::{Clipboard, ClipboardManager};
use crate::expression::{Expression, Variables};
use crate::mask::Mask;
use crate::morphology;
use crate::operations;
use crate::pattern::Pattern;
use crate::region::{BlockVector3, CuboidRegion, RegionShape};
use crate::registry::{
    BlockStateRegistry, CachedBlockRegistry, HorizonBlockRegistry, HorizonRegistryCache,
};
use crate::schematic;
use crate::selection::{SelectionKey, SelectorKind};
use crate::session::{replay_history_record, EditSession, SessionManager};
use crate::transform::{Axis, Transform};
use crate::world::{Extent, HorizonWorld};

pub(crate) fn register(event: &mut mod_api::RegisterCommandsEvent<'_>) {
    event.commands.extend([
        Command::op("fawebim pos1 <x:integer> <y:integer> <z:integer>", 2),
        Command::op("fawebim pos2 <x:integer> <y:integer> <z:integer>", 2),
        Command::op("fawebim sel [mode:text]", 2),
        Command::op("fawebim set <block:text>", 2),
        Command::op("fawebim replace <from:text> <to:text>", 2),
        Command::op("fawebim copy", 2),
        Command::op("fawebim cut [pattern:text]", 2),
        Command::op("fawebim paste", 2),
        Command::op("fawebim rotate <degrees:integer>", 2),
        Command::op("fawebim flip <axis:text>", 2),
        Command::op("fawebim clearclipboard", 2),
        Command::op("fawebim schemsave <name:text>", 2),
        Command::op("fawebim schemload <name:text>", 2),
        Command::op("fawebim schemlist", 2),
        Command::op("fawebim sphere <x:integer> <y:integer> <z:integer> <radius:number> <pattern:text>", 2),
        Command::op("fawebim hsphere <x:integer> <y:integer> <z:integer> <radius:number> <pattern:text>", 2),
        Command::op("fawebim cylinder <x:integer> <y:integer> <z:integer> <radius:number> <height:integer> <pattern:text>", 2),
        Command::op("fawebim hcylinder <x:integer> <y:integer> <z:integer> <radius:number> <height:integer> <pattern:text>", 2),
        Command::op("fawebim pyramid <x:integer> <y:integer> <z:integer> <size:integer> <pattern:text>", 2),
        Command::op("fawebim hpyramid <x:integer> <y:integer> <z:integer> <size:integer> <pattern:text>", 2),
        Command::op("fawebim line <x1:integer> <y1:integer> <z1:integer> <x2:integer> <y2:integer> <z2:integer> <pattern:text>", 2),
        Command::op("fawebim cone <x:integer> <y:integer> <z:integer> <radius:number> <height:integer> <pattern:text>", 2),
        Command::op("fawebim hcone <x:integer> <y:integer> <z:integer> <radius:number> <height:integer> <pattern:text>", 2),
        Command::op("fawebim torus <x:integer> <y:integer> <z:integer> <major:number> <minor:number> <pattern:text>", 2),
        Command::op("fawebim htorus <x:integer> <y:integer> <z:integer> <major:number> <minor:number> <pattern:text>", 2),
        Command::op("fawebim walls <pattern:text>", 2),
        Command::op("fawebim faces <pattern:text>", 2),
        Command::op("fawebim overlay <pattern:text>", 2),
        Command::op("fawebim hollow [thickness:integer] <pattern:text>", 2),
        Command::op("fawebim move <dx:integer> <dy:integer> <dz:integer>", 2),
        Command::op("fawebim stack <count:integer> <dx:integer> <dy:integer> <dz:integer>", 2),
        Command::op("fawebim smooth [iterations:integer]", 2),
        Command::op("fawebim erode [faces:integer] [iterations:integer]", 2),
        Command::op("fawebim dilate [faces:integer] [iterations:integer]", 2),
        Command::op("fawebim brush <kind:text>", 2),
        Command::op("fawebim brushset <radius:number> <pattern:text>", 2),
        Command::op("fawebim brushuse <x:integer> <y:integer> <z:integer>", 2),
        Command::op("fawebim brushinfo", 2),
        Command::op("fawebim brushlist", 2),
        Command::op("fawebim brushclear", 2),
        Command::op("fawebim session", 2),
        Command::op("fawebim limit <blocks:integer>", 2),
        Command::op("fawebim timeout <seconds:integer>", 2),
        Command::op("fawebim fast <enabled:text>", 2),
        Command::op("fawebim tips <enabled:text>", 2),
        Command::op("fawebim watchdog <enabled:text>", 2),
        Command::op("fawebim gmask <mask:text>", 2),
        Command::op("fawebim gpattern <pattern:text>", 2),
        Command::op("fawebim transform", 2),
        Command::op("fawebim transformreset", 2),
        Command::op("fawebim transformrotate <degrees:number> <axis:text>", 2),
        Command::op("fawebim transformscale <x:number> <y:number> <z:number>", 2),
        Command::op("fawebim transformoffset <x:number> <y:number> <z:number>", 2),
        Command::op("fawebim transformflip <axis:text>", 2),
        Command::op("fawebim transformrandom <x:integer> <y:integer> <z:integer>", 2),
        Command::op("fawebim count <mask:text>", 2),
        Command::op("fawebim distr", 2),
        Command::op("fawebim size", 2),
        Command::op("fawebim center", 2),
        Command::op("fawebim setbiome <biome:text>", 2),
        Command::op("fawebim biomedistr", 2),
        Command::op("fawebim gravity", 2),
        Command::op("fawebim naturalize", 2),
        Command::op("fawebim drain", 2),
        Command::op("fawebim green", 2),
        Command::op("fawebim snow", 2),
        Command::op("fawebim thaw", 2),
        Command::op("fawebim layers <layers:text>", 2),
        Command::op("fawebim scatter <x:integer> <y:integer> <z:integer> <radius:integer> <points:integer> <pattern:text>", 2),
        Command::op("fawebim deform <expression:text>", 2),
        Command::op("fawebim flood <x:integer> <y:integer> <z:integer> <radius:integer> <pattern:text>", 2),
        Command::op("fawebim removenear <x:integer> <y:integer> <z:integer> <radius:integer> <mask:text>", 2),
        Command::op("fawebim expand <dx:integer> <dy:integer> <dz:integer>", 2),
        Command::op("fawebim contract <dx:integer> <dy:integer> <dz:integer>", 2),
        Command::op("fawebim shift <dx:integer> <dy:integer> <dz:integer>", 2),
        Command::op("fawebim outset <amount:integer>", 2),
        Command::op("fawebim inset <amount:integer>", 2),
        Command::op("fawebim undo [steps:integer]", 2),
        Command::op("fawebim redo [steps:integer]", 2),
        Command::op("fawebim clearhistory", 2),
        // Compatibility aliases: each routes to the same validated engine path
        // as its canonical command rather than being a placeholder.
        Command::op("fawebim ball <x:integer> <y:integer> <z:integer> <radius:number> <pattern:text>", 2),
        Command::op("fawebim hball <x:integer> <y:integer> <z:integer> <radius:number> <pattern:text>", 2),
        Command::op("fawebim cyl <x:integer> <y:integer> <z:integer> <radius:number> <height:integer> <pattern:text>", 2),
        Command::op("fawebim hcyl <x:integer> <y:integer> <z:integer> <radius:number> <height:integer> <pattern:text>", 2),
        Command::op("fawebim pyr <x:integer> <y:integer> <z:integer> <size:integer> <pattern:text>", 2),
        Command::op("fawebim hpyr <x:integer> <y:integer> <z:integer> <size:integer> <pattern:text>", 2),
        Command::op("fawebim segment <x1:integer> <y1:integer> <z1:integer> <x2:integer> <y2:integer> <z2:integer> <pattern:text>", 2),
        Command::op("fawebim sides <pattern:text>", 2),
        Command::op("fawebim outline <pattern:text>", 2),
        Command::op("fawebim surface <pattern:text>", 2),
        Command::op("fawebim shell <pattern:text>", 2),
        Command::op("fawebim blend [iterations:integer]", 2),
        Command::op("fawebim erosion [faces:integer] [iterations:integer]", 2),
        Command::op("fawebim dilation [faces:integer] [iterations:integer]", 2),
        Command::op("fawebim fall", 2),
        Command::op("fawebim natural", 2),
        Command::op("fawebim deliquid", 2),
        Command::op("fawebim grassify", 2),
        Command::op("fawebim snowfall", 2),
        Command::op("fawebim melt", 2),
        Command::op("fawebim distribution", 2),
        Command::op("fawebim dimensions", 2),
        Command::op("fawebim midpoint", 2),
        Command::op("fawebim rollback [steps:integer]", 2),
        Command::op("fawebim reapply [steps:integer]", 2),
        Command::op("fawebim purgehistory", 2),
        Command::op("fawebim duplicate", 2),
        Command::op("fawebim clipclear", 2),
        Command::op("fawebim cliprotate <degrees:integer>", 2),
        Command::op("fawebim clipflip <axis:text>", 2),
        Command::op("fawebim paint <block:text>", 2),
        Command::op("fawebim substitute <from:text> <to:text>", 2),
        Command::op("fawebim shiftblocks <dx:integer> <dy:integer> <dz:integer>", 2),
        Command::op("fawebim repeat <count:integer> <dx:integer> <dy:integer> <dz:integer>", 2),
        Command::op("fawebim paintbiome <biome:text>", 2),
        Command::op("fawebim nearclear <x:integer> <y:integer> <z:integer> <radius:integer> <mask:text>", 2),
        Command::op("fawebim connected <x:integer> <y:integer> <z:integer> <radius:integer> <pattern:text>", 2),
    ]);
}

pub(crate) fn run(
    host: &mut dyn ServerHost,
    event: &RunModCommandEvent<'_>,
    sessions: &Mutex<SessionManager>,
    brushes: &Mutex<BrushManager>,
    clipboards: &Mutex<ClipboardManager>,
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
        Some("set" | "paint") => set(host, event, sessions, registry_cache),
        Some("replace" | "substitute") => replace(host, event, sessions, registry_cache),
        Some("copy" | "duplicate") => copy_or_cut(host, event, sessions, clipboards, registry_cache, false),
        Some("cut") => copy_or_cut(host, event, sessions, clipboards, registry_cache, true),
        Some("paste") => paste(host, event, sessions, clipboards, registry_cache),
        Some("rotate" | "cliprotate") => transform_clipboard(host, event, clipboards, true),
        Some("flip" | "clipflip") => transform_clipboard(host, event, clipboards, false),
        Some("clearclipboard" | "clipclear") => {
            let removed = clipboards.lock().unwrap_or_else(|p| p.into_inner()).clear(source_key(event.source));
            feedback(host, if removed { "fawebim clearclipboard: clipboard cleared" } else { "fawebim clearclipboard: clipboard already empty" });
            Flow::Cancel
        }
        Some("schemsave" | "schemload" | "schemlist") => schematic_command(host, event, clipboards, registry_cache),
        Some("sphere" | "hsphere" | "cylinder" | "hcylinder" | "pyramid" | "hpyramid" | "line" | "cone" | "hcone" | "torus" | "htorus" | "ball" | "hball" | "cyl" | "hcyl" | "pyr" | "hpyr" | "segment") => generate(host, event, sessions, registry_cache),
        Some("walls" | "faces" | "overlay" | "hollow" | "sides" | "outline" | "surface" | "shell") => region_operation(host, event, sessions, registry_cache),
        Some("move" | "stack" | "shiftblocks" | "repeat") => translate_operation(host, event, sessions, registry_cache),
        Some("smooth" | "erode" | "dilate" | "blend" | "erosion" | "dilation") => morphology_operation(host, event, sessions, registry_cache),
        Some("brush" | "brushset" | "brushinfo" | "brushlist" | "brushclear") => brush_config(host, event, brushes),
        Some("brushuse") => brush_use(host, event, sessions, brushes, registry_cache),
        Some("session" | "limit" | "timeout" | "fast" | "tips" | "watchdog" | "gmask" | "gpattern") => session_config(host, event, sessions),
        Some("transform" | "transformreset" | "transformrotate" | "transformscale" | "transformoffset" | "transformflip" | "transformrandom") => transform_config(host, event, sessions),
        Some("count" | "distr" | "size" | "center" | "distribution" | "dimensions" | "midpoint") => selection_info(host, event, sessions, registry_cache),
        Some("setbiome" | "biomedistr" | "paintbiome") => biome_operation(host, event, sessions, registry_cache),
        Some("gravity" | "naturalize" | "drain" | "green" | "snow" | "thaw" | "fall" | "natural" | "deliquid" | "grassify" | "snowfall" | "melt") => terrain_operation(host, event, sessions, registry_cache),
        Some("deform") => deform_operation(host, event, sessions, registry_cache),
        Some("layers") => layer_operation(host, event, sessions, registry_cache),
        Some("scatter") => scatter_operation(host, event, sessions, registry_cache),
        Some("flood" | "removenear" | "connected" | "nearclear") => near_operation(host, event, sessions, registry_cache),
        Some("expand" | "contract" | "shift" | "outset" | "inset") => selection_mutate(host, event, sessions),
        Some("undo" | "rollback") => history_step(host, event, sessions, registry_cache, true),
        Some("redo" | "reapply") => history_step(host, event, sessions, registry_cache, false),
        Some("clearhistory" | "purgehistory") => {
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

fn canonical_command(name: &str) -> &str {
    match name {
        "ball"=>"sphere","hball"=>"hsphere","cyl"=>"cylinder","hcyl"=>"hcylinder","pyr"=>"pyramid","hpyr"=>"hpyramid","segment"=>"line",
        "sides"=>"walls","outline"=>"faces","surface"=>"overlay","shell"=>"hollow",
        "blend"=>"smooth","erosion"=>"erode","dilation"=>"dilate","fall"=>"gravity","natural"=>"naturalize","deliquid"=>"drain","grassify"=>"green","snowfall"=>"snow","melt"=>"thaw",
        "distribution"=>"distr","dimensions"=>"size","midpoint"=>"center","shiftblocks"=>"move","repeat"=>"stack","paintbiome"=>"setbiome","nearclear"=>"removenear","connected"=>"flood",
        other=>other,
    }
}

fn number(event: &RunModCommandEvent<'_>, name: &str) -> Option<f64> {
    match event.command.argument(name) {
        Some(CommandArgumentValue::Number(value)) => Some(*value),
        Some(CommandArgumentValue::Integer(value)) => Some(*value as f64),
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
    let (mut region, global_mask, global_pattern) = {
        let mgr = lock_sessions(sessions);
        match mgr.peek(key) {
            Some(s) => (s.selection.region(), s.global_mask_expr.clone(), s.global_pattern_expr.clone()),
            None => (None, None, None),
        }
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

    let (pattern, mask) = {
        let reg = HorizonBlockRegistry::new(host, registry_cache.clone());
        let _ = reg.air();
        let effective_pattern = global_pattern.as_deref().unwrap_or(&block_name);
        let pattern = match Pattern::parse(&reg, effective_pattern) {
            Ok(pattern) => pattern,
            Err(error) => { feedback(host, &format!("fawebim set: {error}")); return Flow::Cancel; }
        };
        let mask = match global_mask.as_deref().map(|text| Mask::parse(&reg, text)).transpose() {
            Ok(mask) => mask,
            Err(error) => { feedback(host, &format!("fawebim set global mask: {error}")); return Flow::Cancel; }
        };
        (pattern, mask)
    };

    let dimension = resolve_dimension(host, event);
    let mut world = HorizonWorld::new(host, dimension, registry_cache.clone());
    let mut cached_reg = CachedBlockRegistry::new(registry_cache.clone());
    let mut positions = Vec::new();
    region.for_each_position(|x, y, z| {
        if mask.as_ref().map_or(true, |m| m.test(&world, &cached_reg, x, y, z)) {
            positions.push((x, y, z));
        }
    });

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
        for (x, y, z) in positions {
            edit.set_block(x, y, z, pattern.apply(x, y, z));
        }
        let finish_res = edit.finish(&mut session, Some(&mut mgr.edit_log));
        *mgr.get(key) = session;
        finish_res
    };

    match res {
        Ok(count) => feedback(
            host,
            &format!("fawebim set: {count} block(s) set with {block_name}"),
        ),
        Err(error) => feedback(host, &format!("fawebim set: the edit was refused: {error}")),
    }
    Flow::Cancel
}

fn layer_operation(host:&mut dyn ServerHost,event:&RunModCommandEvent<'_>,sessions:&Mutex<SessionManager>,registry_cache:&Arc<Mutex<HorizonRegistryCache>>)->Flow{
    let text=match event.command.argument("layers"){Some(CommandArgumentValue::String(s))=>s.trim(),_=>""};let key=source_key(event.source);let mut region={let mgr=lock_sessions(sessions);mgr.peek(key).and_then(|s|s.selection.region())};let Some(ref mut region)=region else{feedback(host,"fawebim layers: no selection");return Flow::Cancel;};let layers={let reg=HorizonBlockRegistry::new(host,registry_cache.clone());let mut out=Vec::new();for item in text.split(',').map(str::trim).filter(|s|!s.is_empty()){let(depth,body)=item.split_once('*').map_or((1,item),|(n,p)|(n.parse::<i32>().unwrap_or(0),p.trim()));if depth<=0{feedback(host,&format!("fawebim layers: invalid depth in '{item}'"));return Flow::Cancel;}match Pattern::parse(&reg,body){Ok(p)=>out.push((depth,p)),Err(e)=>{feedback(host,&format!("fawebim layers: {e}"));return Flow::Cancel;}}}out};if layers.is_empty(){feedback(host,"fawebim layers: expected depth*pattern entries");return Flow::Cancel;}let total:i32=layers.iter().map(|v|v.0).sum();let max_y=region.max().y;let dimension=resolve_dimension(host,event);let mut world=HorizonWorld::new(host,dimension,registry_cache.clone());let mut registry=CachedBlockRegistry::new(registry_cache.clone());let result={let mut manager=lock_sessions(sessions);let mut local=manager.get(key).clone();let mut edit=EditSession::open(&mut world,&mut registry,&mut local,"//layers",true);region.for_each_position(|x,y,z|{let mut slot=(max_y-y).rem_euclid(total);for(depth,pattern)in &layers{if slot<*depth{edit.set_block(x,y,z,pattern.apply(x,y,z));break;}slot-=*depth;}});let result=edit.finish(&mut local,Some(&mut manager.edit_log));*manager.get(key)=local;result};match result{Ok(n)=>feedback(host,&format!("fawebim layers: {n} block(s) changed")),Err(e)=>feedback(host,&format!("fawebim layers: {e}"))};Flow::Cancel
}

fn scatter_operation(host:&mut dyn ServerHost,event:&RunModCommandEvent<'_>,sessions:&Mutex<SessionManager>,registry_cache:&Arc<Mutex<HorizonRegistryCache>>)->Flow{
    let(Some(x),Some(y),Some(z))=(integer(event,"x"),integer(event,"y"),integer(event,"z"))else{feedback(host,"fawebim scatter: missing coordinates");return Flow::Cancel;};let radius=integer(event,"radius").unwrap_or(1).clamp(1,128);let wanted=integer(event,"points").unwrap_or(1).clamp(1,100_000)as usize;let text=match event.command.argument("pattern"){Some(CommandArgumentValue::String(s))=>s.trim(),_=>"air"};let pattern={let reg=HorizonBlockRegistry::new(host,registry_cache.clone());match Pattern::parse(&reg,text){Ok(p)=>p,Err(e)=>{feedback(host,&format!("fawebim scatter: {e}"));return Flow::Cancel;}}};let mut seed=(x as u32 as u64)<<32^(y as u32 as u64)<<16^(z as u32 as u64)^0x9E3779B97F4A7C15;let span=(radius*2+1)as u64;let mut points=HashSet::new();for _ in 0..wanted.saturating_mul(20){seed^=seed>>12;seed^=seed<<25;seed^=seed>>27;let a=seed.wrapping_mul(2685821657736338717);let dx=(a%span)as i32-radius;let dy=((a>>21)%span)as i32-radius;let dz=((a>>42)%span)as i32-radius;if dx*dx+dy*dy+dz*dz<=radius*radius{points.insert((x+dx,y+dy,z+dz));if points.len()>=wanted{break;}}}let key=source_key(event.source);let dimension=resolve_dimension(host,event);let mut world=HorizonWorld::new(host,dimension,registry_cache.clone());let mut registry=CachedBlockRegistry::new(registry_cache.clone());let result={let mut manager=lock_sessions(sessions);let mut local=manager.get(key).clone();let mut edit=EditSession::open(&mut world,&mut registry,&mut local,"//scatter",true);for(px,py,pz)in points{edit.set_block(px,py,pz,pattern.apply(px,py,pz));}let result=edit.finish(&mut local,Some(&mut manager.edit_log));*manager.get(key)=local;result};match result{Ok(n)=>feedback(host,&format!("fawebim scatter: {n} block(s) changed")),Err(e)=>feedback(host,&format!("fawebim scatter: {e}"))};Flow::Cancel
}

fn near_operation(host:&mut dyn ServerHost,event:&RunModCommandEvent<'_>,sessions:&Mutex<SessionManager>,registry_cache:&Arc<Mutex<HorizonRegistryCache>>)->Flow{
    let action=canonical_command(event.command.literals.get(1).map(String::as_str).unwrap_or("flood"));let (Some(x),Some(y),Some(z))=(integer(event,"x"),integer(event,"y"),integer(event,"z"))else{feedback(host,&format!("fawebim {action}: missing coordinates"));return Flow::Cancel;};let radius=integer(event,"radius").unwrap_or(0).clamp(1,128);let key=source_key(event.source);
    let (pattern,mask)={let reg=HorizonBlockRegistry::new(host,registry_cache.clone());if action=="flood"{let text=match event.command.argument("pattern"){Some(CommandArgumentValue::String(s))=>s.trim(),_=>"air"};match Pattern::parse(&reg,text){Ok(p)=>(Some(p),None),Err(e)=>{feedback(host,&format!("fawebim flood: {e}"));return Flow::Cancel;}}}else{let text=match event.command.argument("mask"){Some(CommandArgumentValue::String(s))=>s.trim(),_=>"#existing"};match Mask::parse(&reg,text){Ok(m)=>(None,Some(m)),Err(e)=>{feedback(host,&format!("fawebim removenear: {e}"));return Flow::Cancel;}}}};
    let dimension=resolve_dimension(host,event);let mut world=HorizonWorld::new(host,dimension,registry_cache.clone());let mut registry=CachedBlockRegistry::new(registry_cache.clone());let mut positions=Vec::new();if action=="flood"{let source=world.get_block(x,y,z);let mut queue=VecDeque::from([(x,y,z)]);let mut seen=HashSet::from([(x,y,z)]);let r2=radius*radius;while let Some((px,py,pz))=queue.pop_front(){if positions.len()>=2_000_000{break;}if (px-x).pow(2)+(py-y).pow(2)+(pz-z).pow(2)>r2||world.get_block(px,py,pz)!=source{continue;}positions.push((px,py,pz));for(dx,dy,dz)in[(1,0,0),(-1,0,0),(0,1,0),(0,-1,0),(0,0,1),(0,0,-1)]{let next=(px+dx,py+dy,pz+dz);if seen.insert(next){queue.push_back(next);}}}}else{let r=radius as f64;operations::sphere(BlockVector3::at(x,y,z),r,r,r,false,|px,py,pz|{if mask.as_ref().is_some_and(|m|m.test(&world,&registry,px,py,pz)){positions.push((px,py,pz));}});}
    let result={let mut manager=lock_sessions(sessions);let mut local=manager.get(key).clone();let mut edit=EditSession::open(&mut world,&mut registry,&mut local,format!("//{action}"),true);for(px,py,pz)in positions{let state=pattern.as_ref().map_or(0,|p|p.apply(px,py,pz));edit.set_block(px,py,pz,state);}let result=edit.finish(&mut local,Some(&mut manager.edit_log));*manager.get(key)=local;result};match result{Ok(n)=>feedback(host,&format!("fawebim {action}: {n} block(s) changed")),Err(e)=>feedback(host,&format!("fawebim {action}: {e}"))};Flow::Cancel
}

fn deform_operation(host:&mut dyn ServerHost,event:&RunModCommandEvent<'_>,sessions:&Mutex<SessionManager>,registry_cache:&Arc<Mutex<HorizonRegistryCache>>)->Flow{
    let formula=match event.command.argument("expression"){Some(CommandArgumentValue::String(s))=>s.trim(),_=>""};let expression=match Expression::compile(formula){Ok(e)=>e,Err(e)=>{feedback(host,&format!("fawebim deform: {}",e.0));return Flow::Cancel;}};let key=source_key(event.source);let mut region={let mgr=lock_sessions(sessions);mgr.peek(key).and_then(|s|s.selection.region())};let Some(ref mut region)=region else{feedback(host,"fawebim deform: no selection");return Flow::Cancel;};let dimension=resolve_dimension(host,event);let mut world=HorizonWorld::new(host,dimension,registry_cache.clone());let mut changes=Vec::new();let mut error=None;region.for_each_position(|x,y,z|{if error.is_some(){return;}let mut vars=Variables::new().set("x",x as f64).set("y",y as f64).set("z",z as f64);match expression.evaluate(&mut vars){Ok(_)=>{let sx=vars.get("x").round()as i32;let sy=vars.get("y").round()as i32;let sz=vars.get("z").round()as i32;changes.push((x,y,z,world.get_block(sx,sy,sz)));},Err(e)=>error=Some(e.0)}});if let Some(e)=error{feedback(host,&format!("fawebim deform: {e}"));return Flow::Cancel;}let mut registry=CachedBlockRegistry::new(registry_cache.clone());let result={let mut manager=lock_sessions(sessions);let mut local=manager.get(key).clone();let mut edit=EditSession::open(&mut world,&mut registry,&mut local,format!("//deform {formula}"),true);for(x,y,z,state)in changes{edit.set_block(x,y,z,state);}let result=edit.finish(&mut local,Some(&mut manager.edit_log));*manager.get(key)=local;result};match result{Ok(n)=>feedback(host,&format!("fawebim deform: {n} block(s) changed")),Err(e)=>feedback(host,&format!("fawebim deform: {e}"))};Flow::Cancel
}

fn terrain_operation(host:&mut dyn ServerHost,event:&RunModCommandEvent<'_>,sessions:&Mutex<SessionManager>,registry_cache:&Arc<Mutex<HorizonRegistryCache>>)->Flow{
    let action=canonical_command(event.command.literals.get(1).map(String::as_str).unwrap_or("gravity"));let key=source_key(event.source);let mut region={let mgr=lock_sessions(sessions);mgr.peek(key).and_then(|s|s.selection.region())};let Some(ref mut region)=region else{feedback(host,&format!("fawebim {action}: no selection"));return Flow::Cancel;};
    let (air,grass,dirt,stone,snow,water)={let reg=HorizonBlockRegistry::new(host,registry_cache.clone());(reg.air(),reg.default_state("grass_block"),reg.default_state("dirt"),reg.default_state("stone"),reg.default_state("snow"),reg.default_state("water"))};let dimension=resolve_dimension(host,event);let mut world=HorizonWorld::new(host,dimension,registry_cache.clone());let registry=CachedBlockRegistry::new(registry_cache.clone());let mut before=HashMap::new();region.for_each_position(|x,y,z|{before.insert((x,y,z),world.get_block(x,y,z));});let mut changes=HashMap::<(i32,i32,i32),i32>::new();let min=region.min();let max=region.max();
    match action{
        "gravity"=>{for z in min.z..=max.z{for x in min.x..=max.x{let mut solid=Vec::new();for y in min.y..=max.y{if region.contains(x,y,z){let s=*before.get(&(x,y,z)).unwrap_or(&air);if !registry.is_air_like(s){solid.push(s);}changes.insert((x,y,z),air);}}let mut y=min.y;for s in solid{while y<=max.y&&!region.contains(x,y,z){y+=1;}if y<=max.y{changes.insert((x,y,z),s);y+=1;}}}}},
        "naturalize"=>{for z in min.z..=max.z{for x in min.x..=max.x{let top=(min.y..=max.y).rev().find(|&y|region.contains(x,y,z)&&!registry.is_air_like(*before.get(&(x,y,z)).unwrap_or(&air)));if let Some(y)=top{changes.insert((x,y,z),grass);for d in 1..=3{if region.contains(x,y-d,z){changes.insert((x,y-d,z),dirt);}}for yy in min.y..y-3{if region.contains(x,yy,z)&&registry.is_air_like(*before.get(&(x,yy,z)).unwrap_or(&air)){changes.insert((x,yy,z),stone);}}}}}},
        "drain"=>{for(&p,&s)in &before{if registry.is_liquid(s){changes.insert(p,air);}}},
        "green"=>{for(&p,&s)in &before{if registry.name(s).ends_with(":dirt")&&before.get(&(p.0,p.1+1,p.2)).copied().map_or(true,|above|registry.is_air_like(above)){changes.insert(p,grass);}}},
        "snow"=>{for(&p,&s)in &before{if !registry.is_air_like(s)&&before.get(&(p.0,p.1+1,p.2)).copied().map_or(true,|above|registry.is_air_like(above)){changes.insert((p.0,p.1+1,p.2),snow);}}},
        "thaw"=>{for(&p,&s)in &before{let name=registry.name(s);if name.contains("snow"){changes.insert(p,air);}else if name.contains("ice"){changes.insert(p,water);}}},_=>{}
    }
    let mut registry=registry;let result={let mut manager=lock_sessions(sessions);let mut local=manager.get(key).clone();let mut edit=EditSession::open(&mut world,&mut registry,&mut local,format!("//{action}"),true);for((x,y,z),state)in changes{edit.set_block(x,y,z,state);}let result=edit.finish(&mut local,Some(&mut manager.edit_log));*manager.get(key)=local;result};match result{Ok(n)=>feedback(host,&format!("fawebim {action}: {n} block(s) changed")),Err(e)=>feedback(host,&format!("fawebim {action}: {e}"))};Flow::Cancel
}

fn biome_operation(host:&mut dyn ServerHost,event:&RunModCommandEvent<'_>,sessions:&Mutex<SessionManager>,registry_cache:&Arc<Mutex<HorizonRegistryCache>>)->Flow{
    let action=canonical_command(event.command.literals.get(1).map(String::as_str).unwrap_or("biomedistr"));let key=source_key(event.source);let mut region={let mgr=lock_sessions(sessions);mgr.peek(key).and_then(|s|s.selection.region())};let Some(ref mut region)=region else{feedback(host,&format!("fawebim {action}: no selection"));return Flow::Cancel;};
    if action=="biomedistr"{let dimension=resolve_dimension(host,event);let world=HorizonWorld::new(host,dimension,registry_cache.clone());let registry=CachedBlockRegistry::new(registry_cache.clone());let mut counts=HashMap::<i32,u64>::new();region.for_each_position(|x,y,z|{*counts.entry(world.get_biome(x,y,z)).or_insert(0)+=1;});let mut rows=counts.into_iter().collect::<Vec<_>>();rows.sort_by(|a,b|b.1.cmp(&a.1));let text=rows.into_iter().take(12).map(|(id,n)|format!("{}={n}",registry.biome_name(id))).collect::<Vec<_>>().join(", ");feedback(host,&format!("fawebim biomedistr: {text}"));return Flow::Cancel;}
    let name=match event.command.argument("biome"){Some(CommandArgumentValue::String(s))=>s.trim(),_=>""};let biome={let reg=HorizonBlockRegistry::new(host,registry_cache.clone());reg.biome(name)};if biome<0{feedback(host,&format!("fawebim setbiome: unknown biome '{name}'"));return Flow::Cancel;}let dimension=resolve_dimension(host,event);let mut world=HorizonWorld::new(host,dimension,registry_cache.clone());let mut registry=CachedBlockRegistry::new(registry_cache.clone());let mut changed=0u64;let result={let mut manager=lock_sessions(sessions);let mut local=manager.get(key).clone();let mut edit=EditSession::open(&mut world,&mut registry,&mut local,format!("//setbiome {name}"),true);region.for_each_position(|x,y,z|{if edit.set_biome(x,y,z,biome){changed+=1;}});let result=edit.finish(&mut local,Some(&mut manager.edit_log));*manager.get(key)=local;result};match result{Ok(_)=>feedback(host,&format!("fawebim setbiome: {changed} biome cell(s) changed to {name}")),Err(e)=>feedback(host,&format!("fawebim setbiome: {e}"))};Flow::Cancel
}

fn selection_mutate(host:&mut dyn ServerHost,event:&RunModCommandEvent<'_>,sessions:&Mutex<SessionManager>)->Flow{
    let action=canonical_command(event.command.literals.get(1).map(String::as_str).unwrap_or("shift"));let key=source_key(event.source);let mut manager=lock_sessions(sessions);let session=manager.get(key);let Some(mut region)=session.selection.region()else{feedback(host,&format!("fawebim {action}: no selection"));return Flow::Cancel;};
    let result=match action{
        "shift"=>region.shift(integer(event,"dx").unwrap_or(0),integer(event,"dy").unwrap_or(0),integer(event,"dz").unwrap_or(0)),
        "expand"=>region.expand(&[BlockVector3::at(integer(event,"dx").unwrap_or(0),integer(event,"dy").unwrap_or(0),integer(event,"dz").unwrap_or(0))]),
        "contract"=>region.contract(&[BlockVector3::at(integer(event,"dx").unwrap_or(0),integer(event,"dy").unwrap_or(0),integer(event,"dz").unwrap_or(0))]),
        "outset"|"inset"=>{let amount=integer(event,"amount").unwrap_or(0).abs();let dirs=[BlockVector3::at(amount,amount,amount),BlockVector3::at(-amount,-amount,-amount)];if action=="outset"{region.expand(&dirs)}else{region.contract(&dirs)}},
        _=>Ok(false),
    };
    match result{Ok(changed)=>{if changed{session.selection.selector_mut().learn_region(region);feedback(host,&format!("fawebim {action}: selection updated — {}",session.selection.describe()));}else{feedback(host,&format!("fawebim {action}: selection unchanged"));}},Err(error)=>feedback(host,&format!("fawebim {action}: {error}"))}Flow::Cancel
}

fn selection_info(host:&mut dyn ServerHost,event:&RunModCommandEvent<'_>,sessions:&Mutex<SessionManager>,registry_cache:&Arc<Mutex<HorizonRegistryCache>>)->Flow{
    let action=canonical_command(event.command.literals.get(1).map(String::as_str).unwrap_or("size"));let key=source_key(event.source);let mut region={let mgr=lock_sessions(sessions);mgr.peek(key).and_then(|s|s.selection.region())};let Some(ref mut region)=region else{feedback(host,&format!("fawebim {action}: no selection"));return Flow::Cancel;};let min=region.min();let max=region.max();
    if action=="size"{feedback(host,&format!("fawebim size: {} block(s), {}x{}x{}, min=({}, {}, {}), max=({}, {}, {})",region.volume(),max.x-min.x+1,max.y-min.y+1,max.z-min.z+1,min.x,min.y,min.z,max.x,max.y,max.z));return Flow::Cancel;}
    if action=="center"{feedback(host,&format!("fawebim center: ({:.1}, {:.1}, {:.1})",(min.x as f64+max.x as f64)/2.0,(min.y as f64+max.y as f64)/2.0,(min.z as f64+max.z as f64)/2.0));return Flow::Cancel;}
    let mask=if action=="count"{let text=match event.command.argument("mask"){Some(CommandArgumentValue::String(s))=>s.trim(),_=>"*"};let reg=HorizonBlockRegistry::new(host,registry_cache.clone());match Mask::parse(&reg,text){Ok(m)=>Some(m),Err(e)=>{feedback(host,&format!("fawebim count: {e}"));return Flow::Cancel;}}}else{None};let dimension=resolve_dimension(host,event);let world=HorizonWorld::new(host,dimension,registry_cache.clone());let registry=CachedBlockRegistry::new(registry_cache.clone());let mut counts=HashMap::<i32,u64>::new();let mut matched=0u64;region.for_each_position(|x,y,z|{let state=world.get_block(x,y,z);if mask.as_ref().map_or(true,|m|m.test(&world,&registry,x,y,z)){matched+=1;*counts.entry(state).or_insert(0)+=1;}});
    if action=="count"{feedback(host,&format!("fawebim count: {matched} matching block(s) out of {}",region.volume()));}else{let mut rows=counts.into_iter().collect::<Vec<_>>();rows.sort_by(|a,b|b.1.cmp(&a.1).then(a.0.cmp(&b.0)));let shown=rows.iter().take(12).map(|(state,n)|format!("{}={n}",registry.name(*state))).collect::<Vec<_>>().join(", ");feedback(host,&format!("fawebim distr: {matched} block(s), {shown}"));}Flow::Cancel
}

fn transform_config(host:&mut dyn ServerHost,event:&RunModCommandEvent<'_>,sessions:&Mutex<SessionManager>)->Flow{
    let action=event.command.literals.get(1).map(String::as_str).unwrap_or("transform");let key=source_key(event.source);let mut manager=lock_sessions(sessions);let session=manager.get(key);let axis=||match event.command.argument("axis"){Some(CommandArgumentValue::String(s))=>match s.trim().to_ascii_lowercase().as_str(){"x"=>Some(Axis::X),"y"=>Some(Axis::Y),"z"=>Some(Axis::Z),_=>None},_=>None};
    let next=match action{
        "transformreset"=>{session.transform=Transform::Identity;feedback(host,"fawebim transform: reset");return Flow::Cancel;},
        "transformrotate"=>{let Some(axis)=axis()else{feedback(host,"fawebim transformrotate: axis must be x, y, or z");return Flow::Cancel;};Transform::rotate(BlockVector3::ZERO,axis,number(event,"degrees").unwrap_or(0.0))},
        "transformscale"=>{let(x,y,z)=(number(event,"x").unwrap_or(1.0),number(event,"y").unwrap_or(1.0),number(event,"z").unwrap_or(1.0));if x==0.0||y==0.0||z==0.0{feedback(host,"fawebim transformscale: scales cannot be zero");return Flow::Cancel;}Transform::scale(BlockVector3::ZERO,x,y,z)},
        "transformoffset"=>Transform::offset(number(event,"x").unwrap_or(0.0),number(event,"y").unwrap_or(0.0),number(event,"z").unwrap_or(0.0)),
        "transformflip"=>{let Some(axis)=axis()else{feedback(host,"fawebim transformflip: axis must be x, y, or z");return Flow::Cancel;};Transform::flip(BlockVector3::ZERO,axis)},
        "transformrandom"=>Transform::random_offset(integer(event,"x").unwrap_or(0),integer(event,"y").unwrap_or(0),integer(event,"z").unwrap_or(0),0xFAFEB1),
        _=>{feedback(host,&format!("fawebim transform: {:?}",session.transform));return Flow::Cancel;}
    };session.transform=session.transform.clone().combine(next);feedback(host,&format!("fawebim transform: {:?}",session.transform));Flow::Cancel
}

fn session_config(host:&mut dyn ServerHost,event:&RunModCommandEvent<'_>,sessions:&Mutex<SessionManager>)->Flow{
    let action=canonical_command(event.command.literals.get(1).map(String::as_str).unwrap_or("session"));let key=source_key(event.source);let mut manager=lock_sessions(sessions);let session=manager.get(key);
    let parse_bool=|name:&str,current:bool|match event.command.argument(name){Some(CommandArgumentValue::String(s))=>match s.to_ascii_lowercase().as_str(){"on"|"true"|"yes"|"1"=>Some(true),"off"|"false"|"no"|"0"=>Some(false),"toggle"=>Some(!current),_=>None},_=>None};
    match action{
        "limit"=>{let value=integer(event,"blocks").unwrap_or(-1);session.max_blocks_changed=if value<0{-1}else{value};feedback(host,&format!("fawebim limit: {}",if value<0{"unlimited".into()}else{value.to_string()}));},
        "timeout"=>{let value=integer(event,"seconds").unwrap_or(-1);session.timeout_secs=if value<0{-1}else{value};feedback(host,&format!("fawebim timeout: {}",if value<0{"disabled".into()}else{format!("{value}s")}));},
        "fast"=>match parse_bool("enabled",session.fast_mode){Some(v)=>{session.fast_mode=v;feedback(host,&format!("fawebim fast: {v}"));},None=>feedback(host,"fawebim fast: use on, off, or toggle")},
        "tips"=>match parse_bool("enabled",session.tips){Some(v)=>{session.tips=v;feedback(host,&format!("fawebim tips: {v}"));},None=>feedback(host,"fawebim tips: use on, off, or toggle")},
        "watchdog"=>match parse_bool("enabled",session.watchdog){Some(v)=>{session.watchdog=v;feedback(host,&format!("fawebim watchdog: {v}"));},None=>feedback(host,"fawebim watchdog: use on, off, or toggle")},
        "gmask"=>{let value=match event.command.argument("mask"){Some(CommandArgumentValue::String(s))=>s.trim(),_=>"none"};session.global_mask_expr=(!matches!(value.to_ascii_lowercase().as_str(),"none"|"off"|"-")).then(||value.to_string());feedback(host,&format!("fawebim gmask: {}",session.global_mask_expr.as_deref().unwrap_or("disabled")));},
        "gpattern"=>{let value=match event.command.argument("pattern"){Some(CommandArgumentValue::String(s))=>s.trim(),_=>"none"};session.global_pattern_expr=(!matches!(value.to_ascii_lowercase().as_str(),"none"|"off"|"-")).then(||value.to_string());feedback(host,&format!("fawebim gpattern: {}",session.global_pattern_expr.as_deref().unwrap_or("disabled")));},
        _=>feedback(host,&format!("fawebim session: selector={}, selection={}, history={}/{}, limit={}, timeout={}, fast={}, tips={}, watchdog={}",session.selection.selector().kind().as_str(),session.selection.describe(),session.history.len(),session.history.len(),session.max_blocks_changed,session.timeout_secs,session.fast_mode,session.tips,session.watchdog)),
    }Flow::Cancel
}

fn schematic_command(host:&mut dyn ServerHost,event:&RunModCommandEvent<'_>,clipboards:&Mutex<ClipboardManager>,registry_cache:&Arc<Mutex<HorizonRegistryCache>>)->Flow{
    let action=canonical_command(event.command.literals.get(1).map(String::as_str).unwrap_or("schemlist"));let key=source_key(event.source);
    if action=="schemlist"{let folder=ConfigPath::new("schematics").unwrap();match host.data_files().list(&folder,None,1024){Ok(entries)=>{let names=entries.into_iter().filter_map(|e|e.name.strip_suffix(".fawebim").map(str::to_owned)).collect::<Vec<_>>();feedback(host,&format!("fawebim schematics ({}): {}",names.len(),if names.is_empty(){"none".into()}else{names.join(", ")}));},Err(e)=>feedback(host,&format!("fawebim schemlist: {e:?}"))}return Flow::Cancel;}
    let raw=match event.command.argument("name"){Some(CommandArgumentValue::String(s))=>s.trim(),_=>""};if raw.is_empty()||!raw.chars().all(|c|c.is_ascii_alphanumeric()||matches!(c,'_'|'-'|'.')){feedback(host,"fawebim schematic: name may contain only letters, numbers, _, -, and .");return Flow::Cancel;}let path=match ConfigPath::new(&format!("schematics/{raw}.fawebim")){Ok(p)=>p,Err(e)=>{feedback(host,&format!("fawebim schematic: {e:?}"));return Flow::Cancel;}};
    if action=="schemsave"{let clip={clipboards.lock().unwrap_or_else(|p|p.into_inner()).get(key).cloned()};let Some(clip)=clip else{feedback(host,"fawebim schemsave: clipboard is empty");return Flow::Cancel;};let reg=CachedBlockRegistry::new(registry_cache.clone());match schematic::encode(&clip,&reg).and_then(|bytes|host.data_files().write(&path,&bytes).map(|_|bytes.len()).map_err(|e|format!("{e:?}"))){Ok(bytes)=>feedback(host,&format!("fawebim schemsave: saved {raw} ({bytes} bytes)")),Err(e)=>feedback(host,&format!("fawebim schemsave: {e}"))}}
    else{match host.data_files().read(&path).map_err(|e|format!("{e:?}")).and_then(|bytes|{let reg=HorizonBlockRegistry::new(host,registry_cache.clone());schematic::decode(&bytes,&reg)}){Ok(clip)=>{let volume=clip.volume();clipboards.lock().unwrap_or_else(|p|p.into_inner()).set(key,clip);feedback(host,&format!("fawebim schemload: loaded {raw} ({volume} blocks)"));},Err(e)=>feedback(host,&format!("fawebim schemload: {e}"))}}
    Flow::Cancel
}

fn brush_config(host:&mut dyn ServerHost,event:&RunModCommandEvent<'_>,brushes:&Mutex<BrushManager>)->Flow{
    let action=canonical_command(event.command.literals.get(1).map(String::as_str).unwrap_or("brushinfo"));let key=source_key(event.source);let mut manager=brushes.lock().unwrap_or_else(|p|p.into_inner());
    match action{
        "brushlist"=>feedback(host,&format!("fawebim brushes ({}): {}",brush::BRUSH_NAMES.len(),brush::BRUSH_NAMES.join(", "))),
        "brushclear"=>{manager.clear(key);feedback(host,"fawebim brush: unbound");},
        "brushinfo"=>{if let Some(b)=manager.get(key){feedback(host,&format!("fawebim brush: {} radius={} pattern={} mask={}",b.kind,b.radius,b.pattern,b.mask.as_deref().unwrap_or("none")));}else{feedback(host,"fawebim brush: no brush bound");}},
        "brush"=>{let raw=match event.command.argument("kind"){Some(CommandArgumentValue::String(s))=>s.trim(),_=>""};if let Some(kind)=brush::canonical(raw){let mut settings=manager.get(key).cloned().unwrap_or_default();settings.kind=kind;manager.set(key,settings);feedback(host,&format!("fawebim brush: bound {kind}"));}else{feedback(host,&format!("fawebim brush: unknown brush '{raw}'"));}},
        "brushset"=>{let radius=number(event,"radius").unwrap_or(2.0);let pattern=match event.command.argument("pattern"){Some(CommandArgumentValue::String(s))=>s.trim(),_=>"stone"};let mut settings=manager.get(key).cloned().unwrap_or_default();settings.radius=radius.clamp(0.5,128.0);settings.pattern=pattern.to_string();manager.set(key,settings);feedback(host,&format!("fawebim brush: radius={} pattern={pattern}",radius.clamp(0.5,128.0)));},
        _=>{}
    }Flow::Cancel
}

fn brush_use(host:&mut dyn ServerHost,event:&RunModCommandEvent<'_>,sessions:&Mutex<SessionManager>,brushes:&Mutex<BrushManager>,registry_cache:&Arc<Mutex<HorizonRegistryCache>>)->Flow{
    let key=source_key(event.source);let settings={brushes.lock().unwrap_or_else(|p|p.into_inner()).get(key).cloned()};let Some(settings)=settings else{feedback(host,"fawebim brushuse: no brush bound");return Flow::Cancel;};let (Some(x),Some(y),Some(z))=(integer(event,"x"),integer(event,"y"),integer(event,"z"))else{feedback(host,"fawebim brushuse: expected coordinates");return Flow::Cancel;};
    let pattern={let reg=HorizonBlockRegistry::new(host,registry_cache.clone());match Pattern::parse(&reg,&settings.pattern){Ok(p)=>p,Err(e)=>{feedback(host,&format!("fawebim brushuse: {e}"));return Flow::Cancel;}}};let center=BlockVector3::at(x,y,z);let dimension=resolve_dimension(host,event);let mut world=HorizonWorld::new(host,dimension,registry_cache.clone());let mut registry=CachedBlockRegistry::new(registry_cache.clone());
    let result={let mut sessions=lock_sessions(sessions);let mut local=sessions.get(key).clone();let mut edit=EditSession::open(&mut world,&mut registry,&mut local,format!("/brush {}",settings.kind),true);let mut place=|px,py,pz|{edit.set_block(px,py,pz,pattern.apply(px,py,pz));};match settings.kind{
        "cylinder"|"circle"|"layer"=>{operations::cylinder(center,settings.radius,settings.radius,if settings.kind=="circle"{1}else{settings.radius.ceil()as i32},false,&mut place);},
        "surface"|"stencil"|"snow"|"snowsmooth"=>{operations::sphere(center,settings.radius,settings.radius,settings.radius,true,&mut place);},
        _=>{operations::sphere(center,settings.radius,settings.radius,settings.radius,false,&mut place);}
    }let result=edit.finish(&mut local,Some(&mut sessions.edit_log));*sessions.get(key)=local;result};match result{Ok(n)=>feedback(host,&format!("fawebim brushuse {}: {n} block(s) changed",settings.kind)),Err(e)=>feedback(host,&format!("fawebim brushuse: {e}"))};Flow::Cancel
}

fn region_operation(
    host:&mut dyn ServerHost,event:&RunModCommandEvent<'_>,sessions:&Mutex<SessionManager>,registry_cache:&Arc<Mutex<HorizonRegistryCache>>,
)->Flow{
    let name=canonical_command(event.command.literals.get(1).map(String::as_str).unwrap_or("operation"));let key=source_key(event.source);
    let mut region={let mgr=lock_sessions(sessions);mgr.peek(key).and_then(|s|s.selection.region())};let Some(ref mut region)=region else{feedback(host,&format!("fawebim {name}: no selection"));return Flow::Cancel;};
    let pattern_text=match event.command.argument("pattern"){Some(CommandArgumentValue::String(s))=>s.trim(),_=>{feedback(host,&format!("fawebim {name}: expected a pattern"));return Flow::Cancel;}};
    let pattern={let reg=HorizonBlockRegistry::new(host,registry_cache.clone());match Pattern::parse(&reg,pattern_text){Ok(p)=>p,Err(e)=>{feedback(host,&format!("fawebim {name}: {e}"));return Flow::Cancel;}}};
    let snapshot=region.clone();let min=snapshot.min();let max=snapshot.max();let thickness=integer(event,"thickness").unwrap_or(1).clamp(1,64);let mut points=Vec::new();
    region.for_each_position(|x,y,z|{let selected=match name{
        "walls"=>x==min.x||x==max.x||z==min.z||z==max.z,
        "faces"=>x==min.x||x==max.x||y==min.y||y==max.y||z==min.z||z==max.z,
        "overlay"=>!snapshot.contains(x,y+1,z),
        "hollow"=>{let mut edge=false;'outer:for d in 1..=thickness{for (dx,dy,dz) in [(d,0,0),(-d,0,0),(0,d,0),(0,-d,0),(0,0,d),(0,0,-d)]{if !snapshot.contains(x+dx,y+dy,z+dz){edge=true;break 'outer;}}}edge},
        _=>false};if selected{points.push((x,y,z));}});
    let dimension=resolve_dimension(host,event);let mut world=HorizonWorld::new(host,dimension,registry_cache.clone());let mut registry=CachedBlockRegistry::new(registry_cache.clone());
    let result={let mut manager=lock_sessions(sessions);let mut local=manager.get(key).clone();let mut edit=EditSession::open(&mut world,&mut registry,&mut local,format!("//{name} {pattern_text}"),true);for(x,y,z)in points{edit.set_block(x,y,z,pattern.apply(x,y,z));}let result=edit.finish(&mut local,Some(&mut manager.edit_log));*manager.get(key)=local;result};
    match result{Ok(n)=>feedback(host,&format!("fawebim {name}: {n} block(s) changed")),Err(e)=>feedback(host,&format!("fawebim {name}: {e}"))};Flow::Cancel
}

fn morphology_operation(host:&mut dyn ServerHost,event:&RunModCommandEvent<'_>,sessions:&Mutex<SessionManager>,registry_cache:&Arc<Mutex<HorizonRegistryCache>>)->Flow{
    let name=canonical_command(event.command.literals.get(1).map(String::as_str).unwrap_or("smooth"));let key=source_key(event.source);let mut region={let mgr=lock_sessions(sessions);mgr.peek(key).and_then(|s|s.selection.region())};let Some(ref mut region)=region else{feedback(host,&format!("fawebim {name}: no selection"));return Flow::Cancel;};
    let dimension=resolve_dimension(host,event);let mut world=HorizonWorld::new(host,dimension,registry_cache.clone());let mut before=HashMap::new();region.for_each_position(|x,y,z|{before.insert((x,y,z),world.get_block(x,y,z));});let iterations=integer(event,"iterations").unwrap_or(1).clamp(1,64)as usize;let faces=integer(event,"faces").unwrap_or(3).clamp(1,6)as usize;let mut registry=CachedBlockRegistry::new(registry_cache.clone());let after=match name{"erode"=>morphology::erode(&before,|s|registry.is_air_like(s),faces,iterations),"dilate"=>morphology::dilate(&before,|s|registry.is_air_like(s),faces,iterations),_=>morphology::smooth(&before,|s|registry.is_air_like(s),iterations)};let changes=morphology::changed(&before,&after);
    let result={let mut manager=lock_sessions(sessions);let mut local=manager.get(key).clone();let mut edit=EditSession::open(&mut world,&mut registry,&mut local,format!("//{name}"),true);for((x,y,z),state)in changes{edit.set_block(x,y,z,state);}let result=edit.finish(&mut local,Some(&mut manager.edit_log));*manager.get(key)=local;result};match result{Ok(n)=>feedback(host,&format!("fawebim {name}: {n} block(s) changed")),Err(e)=>feedback(host,&format!("fawebim {name}: {e}"))};Flow::Cancel
}

fn translate_operation(host:&mut dyn ServerHost,event:&RunModCommandEvent<'_>,sessions:&Mutex<SessionManager>,registry_cache:&Arc<Mutex<HorizonRegistryCache>>)->Flow{
    let name=canonical_command(event.command.literals.get(1).map(String::as_str).unwrap_or("move"));let key=source_key(event.source);let(mut region,dx,dy,dz)=({let mgr=lock_sessions(sessions);mgr.peek(key).and_then(|s|s.selection.region())},integer(event,"dx").unwrap_or(0),integer(event,"dy").unwrap_or(0),integer(event,"dz").unwrap_or(0));
    let Some(ref mut region)=region else{feedback(host,&format!("fawebim {name}: no selection"));return Flow::Cancel;};if dx==0&&dy==0&&dz==0{feedback(host,&format!("fawebim {name}: offset is zero"));return Flow::Cancel;}
    let count=if name=="stack"{integer(event,"count").unwrap_or(1).clamp(1,256)}else{1};let dimension=resolve_dimension(host,event);let mut world=HorizonWorld::new(host,dimension,registry_cache.clone());let mut source=Vec::new();region.for_each_position(|x,y,z|source.push((x,y,z,world.get_block(x,y,z))));
    let mut registry=CachedBlockRegistry::new(registry_cache.clone());let result={let mut manager=lock_sessions(sessions);let mut local=manager.get(key).clone();let mut edit=EditSession::open(&mut world,&mut registry,&mut local,format!("//{name}"),true);if name=="move"{for&(x,y,z,_)in &source{edit.set_block(x,y,z,0);}}for i in 1..=count{for&(x,y,z,state)in &source{edit.set_block(x+dx*i,y+dy*i,z+dz*i,state);}}let result=edit.finish(&mut local,Some(&mut manager.edit_log));*manager.get(key)=local;result};
    match result{Ok(n)=>feedback(host,&format!("fawebim {name}: {n} block(s) changed")),Err(e)=>feedback(host,&format!("fawebim {name}: {e}"))};Flow::Cancel
}

fn generate(
    host: &mut dyn ServerHost,
    event: &RunModCommandEvent<'_>,
    sessions: &Mutex<SessionManager>,
    registry_cache: &Arc<Mutex<HorizonRegistryCache>>,
) -> Flow {
    let name=canonical_command(event.command.literals.get(1).map(String::as_str).unwrap_or("shape"));
    let pattern_text=match event.command.argument("pattern"){Some(CommandArgumentValue::String(s))=>s.trim(),_=>{feedback(host,&format!("fawebim {name}: expected a pattern"));return Flow::Cancel;}};
    let pattern={let reg=HorizonBlockRegistry::new(host,registry_cache.clone());match Pattern::parse(&reg,pattern_text){Ok(p)=>p,Err(e)=>{feedback(host,&format!("fawebim {name}: {e}"));return Flow::Cancel;}}};
    let coord=|n|integer(event,n);let Some(x)=coord("x").or_else(||coord("x1"))else{feedback(host,"fawebim shape: missing coordinates");return Flow::Cancel;};
    let Some(y)=coord("y").or_else(||coord("y1"))else{feedback(host,"fawebim shape: missing coordinates");return Flow::Cancel;};
    let Some(z)=coord("z").or_else(||coord("z1"))else{feedback(host,"fawebim shape: missing coordinates");return Flow::Cancel;};
    let center=BlockVector3::at(x,y,z);let dimension=resolve_dimension(host,event);let mut world=HorizonWorld::new(host,dimension,registry_cache.clone());let mut registry=CachedBlockRegistry::new(registry_cache.clone());let key=source_key(event.source);
    let result={let mut manager=lock_sessions(sessions);let mut local=manager.get(key).clone();let mut edit=EditSession::open(&mut world,&mut registry,&mut local,format!("//{name} {pattern_text}"),true);let mut place=|px,py,pz|{edit.set_block(px,py,pz,pattern.apply(px,py,pz));};
        match name {
            "sphere"|"hsphere"=>{let r=number(event,"radius").unwrap_or(0.0);if r>0.0{operations::sphere(center,r,r,r,name=="hsphere",&mut place);}},
            "cylinder"|"hcylinder"=>{let r=number(event,"radius").unwrap_or(0.0);let h=integer(event,"height").unwrap_or(0);if r>0.0&&h!=0{operations::cylinder(center,r,r,h,name=="hcylinder",&mut place);}},
            "pyramid"|"hpyramid"=>{let size=integer(event,"size").unwrap_or(0);if size>0{operations::pyramid(center,size,name=="hpyramid",&mut place);}},
            "cone"|"hcone"=>{let r=number(event,"radius").unwrap_or(0.0);let h=integer(event,"height").unwrap_or(0);if r>0.0&&h>0{operations::cone(center,r,h,name=="hcone",&mut place);}},
            "torus"|"htorus"=>{let major=number(event,"major").unwrap_or(0.0);let minor=number(event,"minor").unwrap_or(0.0);if major>0.0&&minor>0.0{operations::torus(center,major,minor,name=="htorus",&mut place);}},
            "line"=>{if let (Some(x2),Some(y2),Some(z2))=(coord("x2"),coord("y2"),coord("z2")){operations::line(center,BlockVector3::at(x2,y2,z2),1.0,&mut place);}},
            _=>{}
        }
        let result=edit.finish(&mut local,Some(&mut manager.edit_log));*manager.get(key)=local;result};
    match result{Ok(n)=>feedback(host,&format!("fawebim {name}: {n} block(s) changed")),Err(e)=>feedback(host,&format!("fawebim {name}: {e}"))};Flow::Cancel
}

fn copy_or_cut(
    host: &mut dyn ServerHost,
    event: &RunModCommandEvent<'_>,
    sessions: &Mutex<SessionManager>,
    clipboards: &Mutex<ClipboardManager>,
    registry_cache: &Arc<Mutex<HorizonRegistryCache>>,
    cut: bool,
) -> Flow {
    let key = source_key(event.source);
    let mut region = { let mgr=lock_sessions(sessions); mgr.peek(key).and_then(|s|s.selection.region()) };
    let Some(ref mut region)=region else { feedback(host,"fawebim copy: no selection"); return Flow::Cancel; };
    if region.volume()>CuboidRegion::MAX_VOLUME { feedback(host,"fawebim copy: selection exceeds the block limit"); return Flow::Cancel; }
    let fill = if cut {
        let text=match event.command.argument("pattern") { Some(CommandArgumentValue::String(s)) if !s.trim().is_empty()=>s.trim(), _=>"air" };
        let reg=HorizonBlockRegistry::new(host,registry_cache.clone());
        match Pattern::parse(&reg,text) { Ok(p)=>Some(p),Err(e)=>{feedback(host,&format!("fawebim cut: {e}"));return Flow::Cancel;} }
    } else { None };
    let dimension=resolve_dimension(host,event);
    let mut world=HorizonWorld::new(host,dimension,registry_cache.clone());
    let clip=Clipboard::capture(&world,region);
    let volume=clip.volume(); let origin=clip.origin();
    clipboards.lock().unwrap_or_else(|p|p.into_inner()).set(key,clip);
    if let Some(pattern)=fill {
        let mut registry=CachedBlockRegistry::new(registry_cache.clone());
        let result={
            let mut manager=lock_sessions(sessions); let mut local=manager.get(key).clone();
            let mut edit=EditSession::open(&mut world,&mut registry,&mut local,"//cut",true);
            region.for_each_position(|x,y,z|{edit.set_block(x,y,z,pattern.apply(x,y,z));});
            let result=edit.finish(&mut local,Some(&mut manager.edit_log)); *manager.get(key)=local; result
        };
        if let Err(e)=result { feedback(host,&format!("fawebim cut: {e}")); return Flow::Cancel; }
    }
    feedback(host,&format!("fawebim {}: {volume} block(s), origin ({}, {}, {})",if cut{"cut"}else{"copy"},origin.x,origin.y,origin.z));
    Flow::Cancel
}

fn paste(
    host: &mut dyn ServerHost,
    event: &RunModCommandEvent<'_>,
    sessions: &Mutex<SessionManager>,
    clipboards: &Mutex<ClipboardManager>,
    registry_cache: &Arc<Mutex<HorizonRegistryCache>>,
) -> Flow {
    let key=source_key(event.source);
    let clip={let clips=clipboards.lock().unwrap_or_else(|p|p.into_inner());clips.get(key).cloned()};
    let Some(clip)=clip else {feedback(host,"fawebim paste: clipboard is empty");return Flow::Cancel;};
    let origin=clip.origin(); let dimension=resolve_dimension(host,event);
    let mut world=HorizonWorld::new(host,dimension,registry_cache.clone());
    let mut registry=CachedBlockRegistry::new(registry_cache.clone());
    let result={
        let mut manager=lock_sessions(sessions);let mut local=manager.get(key).clone();
        let mut edit=EditSession::open(&mut world,&mut registry,&mut local,"//paste",true);
        clip.for_each(|x,y,z,state|{edit.set_block(origin.x+x,origin.y+y,origin.z+z,state);});
        let result=edit.finish(&mut local,Some(&mut manager.edit_log));*manager.get(key)=local;result
    };
    match result {Ok(n)=>feedback(host,&format!("fawebim paste: {n} block(s) pasted")),Err(e)=>feedback(host,&format!("fawebim paste: {e}"))};
    Flow::Cancel
}

fn transform_clipboard(host:&mut dyn ServerHost,event:&RunModCommandEvent<'_>,clipboards:&Mutex<ClipboardManager>,rotate:bool)->Flow{
    let key=source_key(event.source);let mut clips=clipboards.lock().unwrap_or_else(|p|p.into_inner());
    let Some(clip)=clips.get_mut(key) else {drop(clips);feedback(host,"fawebim clipboard: clipboard is empty");return Flow::Cancel;};
    if rotate { let degrees=integer(event,"degrees").unwrap_or(0);if degrees%90!=0{drop(clips);feedback(host,"fawebim rotate: degrees must be a multiple of 90");return Flow::Cancel;}clip.rotate_y(degrees/90); }
    else { let axis=match event.command.argument("axis"){Some(CommandArgumentValue::String(s))=>s.chars().next().unwrap_or('y'),_=>'y'};if !matches!(axis,'x'|'y'|'z'){drop(clips);feedback(host,"fawebim flip: axis must be x, y, or z");return Flow::Cancel;}clip.flip(axis); }
    let size=clip.size();drop(clips);feedback(host,&format!("fawebim clipboard: transformed to {}x{}x{}",size.x,size.y,size.z));Flow::Cancel
}

fn replace(
    host: &mut dyn ServerHost,
    event: &RunModCommandEvent<'_>,
    sessions: &Mutex<SessionManager>,
    registry_cache: &Arc<Mutex<HorizonRegistryCache>>,
) -> Flow {
    let argument = |name| match event.command.argument(name) {
        Some(CommandArgumentValue::String(text)) if !text.trim().is_empty() => Some(text.trim()),
        _ => None,
    };
    let (Some(from), Some(to)) = (argument("from"), argument("to")) else {
        feedback(host, "fawebim replace: expected <from:mask> <to:pattern>");
        return Flow::Cancel;
    };
    let key = source_key(event.source);
    let (mut region, global_mask, global_pattern) = {
        let mgr = lock_sessions(sessions);
        match mgr.peek(key) {
            Some(session) => (session.selection.region(), session.global_mask_expr.clone(), session.global_pattern_expr.clone()),
            None => (None, None, None),
        }
    };
    let Some(ref mut region) = region else {
        feedback(host, "fawebim replace: no selection — mark pos1 and pos2 first");
        return Flow::Cancel;
    };
    if region.volume() > CuboidRegion::MAX_VOLUME {
        feedback(host, "fawebim replace: selection exceeds the block limit");
        return Flow::Cancel;
    }
    let (mask, pattern) = {
        let registry = HorizonBlockRegistry::new(host, registry_cache.clone());
        let _ = registry.air();
        let mut mask = match Mask::parse(&registry, from) {
            Ok(mask) => mask,
            Err(error) => { feedback(host, &format!("fawebim replace: {error}")); return Flow::Cancel; }
        };
        if let Some(text) = global_mask.as_deref() {
            match Mask::parse(&registry, text) {
                Ok(global) => mask = Mask::All(vec![mask, global]),
                Err(error) => { feedback(host, &format!("fawebim replace global mask: {error}")); return Flow::Cancel; }
            }
        }
        let pattern = match Pattern::parse(&registry, global_pattern.as_deref().unwrap_or(to)) {
            Ok(pattern) => pattern,
            Err(error) => { feedback(host, &format!("fawebim replace: {error}")); return Flow::Cancel; }
        };
        (mask, pattern)
    };
    let dimension = resolve_dimension(host, event);
    let mut world = HorizonWorld::new(host, dimension, registry_cache.clone());
    let mut registry = CachedBlockRegistry::new(registry_cache.clone());
    let mut positions = Vec::new();
    region.for_each_position(|x, y, z| {
        if mask.test(&world, &registry, x, y, z) { positions.push((x, y, z)); }
    });
    let result = {
        let mut manager = lock_sessions(sessions);
        let mut local = manager.get(key).clone();
        let mut edit = EditSession::open(&mut world, &mut registry, &mut local, format!("//replace {from} {to}"), true);
        for (x, y, z) in positions { edit.set_block(x, y, z, pattern.apply(x, y, z)); }
        let result = edit.finish(&mut local, Some(&mut manager.edit_log));
        *manager.get(key) = local;
        result
    };
    match result {
        Ok(changed) => feedback(host, &format!("fawebim replace: {changed} block(s) changed")),
        Err(error) => feedback(host, &format!("fawebim replace: the edit was refused: {error}")),
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
