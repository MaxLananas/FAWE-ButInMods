//! Selection, bulk `//set`, and `//undo` / `//redo` commands backed by
//! `SessionManager`, `HorizonBlockRegistry`, `HorizonWorld`, and `EditSession`.

use std::collections::{HashMap, HashSet, VecDeque};
use std::sync::{Arc, Mutex};

use mod_api::{
    Command, CommandArgumentValue, ConfigPath, Flow, RunModCommandEvent, ServerHost, Text,
};
#[cfg(test)]
use mod_api::BlockRun;

use crate::brush::{self, BrushManager};
use crate::clipboard::{Clipboard, ClipboardManager};
use crate::expression::{Expression, Variables};
use crate::help;
use crate::mask::Mask;
use crate::morphology;
use crate::operations;
use crate::pattern::Pattern;
use crate::region::{BlockVector2, BlockVector3, ConvexPolyhedralRegion, CuboidRegion, CylinderRegion, EllipsoidRegion, Polygonal2DRegion, RegionShape, Vector2, Vector3};
use crate::registry::{
    BlockStateRegistry, CachedBlockRegistry, HorizonBlockRegistry, HorizonRegistryCache,
};
use crate::schematic;
use crate::selection::{SelectionKey, SelectorKind};
use crate::session::{replay_history_record, EditSession, SessionManager};
use crate::transform::{Axis, BlockStateTransform, Transform};
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
        Command::op("fawebim pasteat <x:integer> <y:integer> <z:integer>", 2),
        Command::op("fawebim pasteoffset <dx:integer> <dy:integer> <dz:integer>", 2),
        Command::op("fawebim clipboardinfo", 2),
        Command::op("fawebim pastenonair", 2),
        Command::op("fawebim pasteair", 2),
        Command::op("fawebim pastemasked <mask:text>", 2),
        Command::op("fawebim clipboardorigin <x:integer> <y:integer> <z:integer>", 2),
        Command::op("fawebim clipboardshift <dx:integer> <dy:integer> <dz:integer>", 2),
        Command::op("fawebim clipboardscale <x:integer> <y:integer> <z:integer>", 2),
        Command::op("fawebim clipboardtrim", 2),
        Command::op("fawebim pastecenter <x:integer> <y:integer> <z:integer>", 2),
        Command::op("fawebim pastearray <nx:integer> <ny:integer> <nz:integer> <dx:integer> <dy:integer> <dz:integer>", 2),
        Command::op("fawebim clipboardreplace <from:text> <to:text>", 2),
        Command::op("fawebim clipboardchecksum", 0),
        Command::op("fawebim clipboardstats", 0),
        Command::op("fawebim clipboardpalette", 0),
        Command::op("fawebim clipboardcount <block:text>", 0),
        Command::op("fawebim clipboardcompare", 0),
        Command::op("fawebim rotate <degrees:integer>", 2),
        Command::op("fawebim rotateaxis <axis:text> <degrees:integer>", 2),
        Command::op("fawebim flip <axis:text>", 2),
        Command::op("fawebim clearclipboard", 2),
        Command::op("fawebim schemsave <name:text>", 2),
        Command::op("fawebim schemload <name:text>", 2),
        Command::op("fawebim schemloadat <name:text> <x:integer> <y:integer> <z:integer>", 2),
        Command::op("fawebim schemlist", 2),
        Command::op("fawebim scheminfo <name:text>", 0),
        Command::op("fawebim schemvalidate <name:text>", 0),
        Command::op("fawebim schemcompare <name:text>", 0),
        Command::op("fawebim schemstats <name:text>", 0),
        Command::op("fawebim schemupgrade <name:text>", 2),
        Command::op("fawebim sphere <x:integer> <y:integer> <z:integer> <radius:number> <pattern:text>", 2),
        Command::op("fawebim hsphere <x:integer> <y:integer> <z:integer> <radius:number> <pattern:text>", 2),
        Command::op("fawebim cylinder <x:integer> <y:integer> <z:integer> <radius:number> <height:integer> <pattern:text>", 2),
        Command::op("fawebim hcylinder <x:integer> <y:integer> <z:integer> <radius:number> <height:integer> <pattern:text>", 2),
        Command::op("fawebim pyramid <x:integer> <y:integer> <z:integer> <size:integer> <pattern:text>", 2),
        Command::op("fawebim hpyramid <x:integer> <y:integer> <z:integer> <size:integer> <pattern:text>", 2),
        Command::op("fawebim line <x1:integer> <y1:integer> <z1:integer> <x2:integer> <y2:integer> <z2:integer> <pattern:text>", 2),
        Command::op("fawebim bezier <x1:integer> <y1:integer> <z1:integer> <x2:integer> <y2:integer> <z2:integer> <x3:integer> <y3:integer> <z3:integer> [thickness:number] <pattern:text>", 2),
        Command::op("fawebim helix <x:integer> <y:integer> <z:integer> <radius:number> <height:integer> <turns:number> [thickness:number] <pattern:text>", 2),
        Command::op("fawebim cone <x:integer> <y:integer> <z:integer> <radius:number> <height:integer> <pattern:text>", 2),
        Command::op("fawebim hcone <x:integer> <y:integer> <z:integer> <radius:number> <height:integer> <pattern:text>", 2),
        Command::op("fawebim torus <x:integer> <y:integer> <z:integer> <major:number> <minor:number> <pattern:text>", 2),
        Command::op("fawebim htorus <x:integer> <y:integer> <z:integer> <major:number> <minor:number> <pattern:text>", 2),
        Command::op("fawebim ellipsoid <x:integer> <y:integer> <z:integer> <rx:number> <ry:number> <rz:number> <pattern:text>", 2),
        Command::op("fawebim hellipsoid <x:integer> <y:integer> <z:integer> <rx:number> <ry:number> <rz:number> <pattern:text>", 2),
        Command::op("fawebim disk <x:integer> <y:integer> <z:integer> <rx:number> <rz:number> <pattern:text>", 2),
        Command::op("fawebim hdisk <x:integer> <y:integer> <z:integer> <rx:number> <rz:number> <pattern:text>", 2),
        Command::op("fawebim dome <x:integer> <y:integer> <z:integer> <rx:number> <ry:number> <rz:number> <pattern:text>", 2),
        Command::op("fawebim hdome <x:integer> <y:integer> <z:integer> <rx:number> <ry:number> <rz:number> <pattern:text>", 2),
        Command::op("fawebim cuboid <x1:integer> <y1:integer> <z1:integer> <x2:integer> <y2:integer> <z2:integer> <pattern:text>", 2),
        Command::op("fawebim hcuboid <x1:integer> <y1:integer> <z1:integer> <x2:integer> <y2:integer> <z2:integer> <pattern:text>", 2),
        Command::op("fawebim prism <x:integer> <y:integer> <z:integer> <sides:integer> <radius:number> <height:integer> <pattern:text>", 2),
        Command::op("fawebim hprism <x:integer> <y:integer> <z:integer> <sides:integer> <radius:number> <height:integer> <pattern:text>", 2),
        Command::op("fawebim staircase <x:integer> <y:integer> <z:integer> <length:integer> <width:integer> <rise:integer> <direction:text> <pattern:text>", 2),
        Command::op("fawebim ramp <x:integer> <y:integer> <z:integer> <length:integer> <width:integer> <rise:integer> <direction:text> <pattern:text>", 2),
        Command::op("fawebim thickline <x1:integer> <y1:integer> <z1:integer> <x2:integer> <y2:integer> <z2:integer> <thickness:number> <pattern:text>", 2),
        Command::op("fawebim tube <x1:integer> <y1:integer> <z1:integer> <x2:integer> <y2:integer> <z2:integer> <thickness:number> <pattern:text>", 2),
        Command::op("fawebim capsule <x:integer> <y:integer> <z:integer> <radius:number> <height:integer> <pattern:text>", 2),
        Command::op("fawebim hcapsule <x:integer> <y:integer> <z:integer> <radius:number> <height:integer> <pattern:text>", 2),
        Command::op("fawebim walls <pattern:text>", 2),
        Command::op("fawebim faces <pattern:text>", 2),
        Command::op("fawebim overlay <pattern:text>", 2),
        Command::op("fawebim hollow [thickness:integer] <pattern:text>", 2),
        Command::op("fawebim frame <pattern:text>", 2),
        Command::op("fawebim corners <pattern:text>", 2),
        Command::op("fawebim pillars <pattern:text>", 2),
        Command::op("fawebim cross <pattern:text>", 2),
        Command::op("fawebim cross2d <pattern:text>", 2),
        Command::op("fawebim lattice <xgap:integer> <ygap:integer> <zgap:integer> <pattern:text>", 2),
        Command::op("fawebim stripes <axis:text> <spacing:integer> <pattern:text>", 2),
        Command::op("fawebim border <thickness:integer> <pattern:text>", 2),
        Command::op("fawebim checkerfill <size:integer> <first:text> <second:text>", 2),
        Command::op("fawebim maskedset <mask:text> <pattern:text>", 2),
        Command::op("fawebim randomset <chance:number> <pattern:text>", 2),
        Command::op("fawebim surfaceset [depth:integer] <pattern:text>", 2),
        Command::op("fawebim underlay [depth:integer] <pattern:text>", 2),
        Command::op("fawebim maskchoose <mask:text> <matching:text> <other:text>", 2),
        Command::op("fawebim compactdown", 2),
        Command::op("fawebim compactup", 2),
        Command::op("fawebim compactwest", 2),
        Command::op("fawebim compacteast", 2),
        Command::op("fawebim compactnorth", 2),
        Command::op("fawebim compactsouth", 2),
        Command::op("fawebim levelterrain <y:integer> <pattern:text>", 2),
        Command::op("fawebim raiseterrain <amount:integer> <pattern:text>", 2),
        Command::op("fawebim lowerterrain <amount:integer>", 2),
        Command::op("fawebim swapblocks <first:text> <second:text>", 2),
        Command::op("fawebim cycleblocks <blocks:text>", 2),
        Command::op("fawebim delete <mask:text>", 2),
        Command::op("fawebim keep <mask:text>", 2),
        Command::op("fawebim coat <mask:text> <pattern:text>", 2),
        Command::op("fawebim gradientfill <axis:text> <min:number> <max:number> <patterns:text>", 2),
        Command::op("fawebim wavefill <axis:text> <wavelength:number> <patterns:text>", 2),
        Command::op("fawebim noisefill <kind:text> <scale:number> <patterns:text>", 2),
        Command::op("fawebim radialfill <x:number> <y:number> <z:number> <radius:number> <patterns:text>", 2),
        Command::op("fawebim regionflip <axis:text>", 2),
        Command::op("fawebim regionrotate <degrees:integer>", 2),
        Command::op("fawebim regionrotateaxis <axis:text> <degrees:integer>", 2),
        Command::op("fawebim regionclone <dx:integer> <dy:integer> <dz:integer>", 2),
        Command::op("fawebim regionscale <x:integer> <y:integer> <z:integer>", 2),
        Command::op("fawebim regioncenter <x:integer> <y:integer> <z:integer>", 2),
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
        Command::op("fawebim gmaskclear", 2),
        Command::op("fawebim gpatternclear", 2),
        Command::op("fawebim sessionreset", 2),
        Command::op("fawebim transform", 2),
        Command::op("fawebim transformreset", 2),
        Command::op("fawebim transformrotate <degrees:number> <axis:text>", 2),
        Command::op("fawebim transformscale <x:number> <y:number> <z:number>", 2),
        Command::op("fawebim transformoffset <x:number> <y:number> <z:number>", 2),
        Command::op("fawebim transformflip <axis:text>", 2),
        Command::op("fawebim transformrandom <x:integer> <y:integer> <z:integer>", 2),
        Command::op("fawebim count <mask:text>", 2),
        Command::op("fawebim distr", 2),
        Command::op("fawebim aircount", 0),
        Command::op("fawebim solidcount", 0),
        Command::op("fawebim liquidcount", 0),
        Command::op("fawebim surfacecount", 0),
        Command::op("fawebim selectiondensity", 0),
        Command::op("fawebim selectionchecksum", 0),
        Command::op("fawebim size", 2),
        Command::op("fawebim center", 2),
        Command::op("fawebim setbiome <biome:text>", 2),
        Command::op("fawebim biomedistr", 2),
        Command::op("fawebim biomecount <biome:text>", 0),
        Command::op("fawebim replacebiome <from:text> <to:text>", 2),
        Command::op("fawebim biomelist [query:text]", 0),
        Command::op("fawebim gravity", 2),
        Command::op("fawebim naturalize", 2),
        Command::op("fawebim drain", 2),
        Command::op("fawebim green", 2),
        Command::op("fawebim snow", 2),
        Command::op("fawebim thaw", 2),
        Command::op("fawebim layers <layers:text>", 2),
        Command::op("fawebim scatter <x:integer> <y:integer> <z:integer> <radius:integer> <points:integer> <pattern:text>", 2),
        Command::op("fawebim forest [density:integer]", 2),
        Command::op("fawebim flora [density:integer]", 2),
        Command::op("fawebim pumpkins [density:integer]", 2),
        Command::op("fawebim ores [density:integer] <pattern:text>", 2),
        Command::op("fawebim floor <pattern:text>", 2),
        Command::op("fawebim ceiling <pattern:text>", 2),
        Command::op("fawebim centerpattern <pattern:text>", 2),
        Command::op("fawebim removeabove [layers:integer]", 2),
        Command::op("fawebim removebelow [layers:integer]", 2),
        Command::op("fawebim fixwater", 2),
        Command::op("fawebim fixlava", 2),
        Command::op("fawebim extinguish", 2),
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
        Command::op("fawebim history", 0),
        Command::op("fawebim historylimit <records:integer>", 2),
        Command::op("fawebim historylist [page:integer]", 0),
        Command::op("fawebim historystats", 0),
        Command::op("fawebim chunkselect <x:integer> <z:integer> [miny:integer] [maxy:integer]", 2),
        Command::op("fawebim chunkexpand [radius:integer]", 2),
        Command::op("fawebim chunklist", 0),
        Command::op("fawebim selectionarea", 0),
        Command::op("fawebim inspect <x:integer> <y:integer> <z:integer>", 0),
        Command::op("fawebim columninfo <x:integer> <z:integer> [miny:integer] [maxy:integer]", 0),
        Command::op("fawebim surfaceinfo <x:integer> <z:integer> [miny:integer] [maxy:integer]", 0),
        Command::op("fawebim selectionbounds <miny:integer> <maxy:integer>", 2),
        Command::op("fawebim selectionpad <x:integer> <y:integer> <z:integer>", 2),
        Command::op("fawebim selectionalign <grid:integer>", 2),
        Command::op("fawebim selectiontrim [mask:text]", 2),
        Command::op("fawebim selectioncorners", 0),
        Command::op("fawebim selectionchunks", 0),
        Command::op("fawebim selsphere <x:integer> <y:integer> <z:integer> <radius:number>", 2),
        Command::op("fawebim selellipsoid <x:integer> <y:integer> <z:integer> <rx:number> <ry:number> <rz:number>", 2),
        Command::op("fawebim selcylinder <x:integer> <z:integer> <rx:number> <rz:number> <miny:integer> <maxy:integer>", 2),
        Command::op("fawebim selbox <x1:integer> <y1:integer> <z1:integer> <x2:integer> <y2:integer> <z2:integer>", 2),
        Command::op("fawebim selpolygon <points:text> <miny:integer> <maxy:integer>", 2),
        Command::op("fawebim selconvex <points:text>", 2),
        Command::op("fawebim selpolyhedron <points:text>", 2),
        Command::op("fawebim extrude <direction:text> <distance:integer> <mask:text> <pattern:text>", 2),
        Command::op("fawebim help [page:integer]", 0),
        Command::op("fawebim commands [query:text]", 0),
        Command::op("fawebim version", 0),
        Command::op("fawebim patterninfo <pattern:text>", 0),
        Command::op("fawebim maskinfo <mask:text>", 0),
        Command::op("fawebim biomeinfo", 0),
        Command::op("fawebim brushes", 0),
        Command::op("fawebim chunkinfo", 0),
        Command::op("fawebim desel", 2),
        Command::op("fawebim listchunks", 0),
        Command::op("fawebim ore [density:integer] <pattern:text>", 2),
        Command::op("fawebim replacenear <x:integer> <y:integer> <z:integer> <radius:integer> <mask:text>", 2),
        Command::op("fawebim gtexture <pattern:text>", 2),
        Command::op("fawebim gtransform", 2),
        Command::op("fawebim smask <mask:text>", 2),
        Command::op("fawebim transforms", 0),
        Command::op("fawebim masks", 0),
        Command::op("fawebim patterns", 0),
        Command::op("fawebim perf", 0),
        Command::op("fawebim we", 0),
        Command::op("fawebim world", 0),
        Command::op("fawebim tool", 0),
        Command::op("fawebim schem", 0),
        Command::op("fawebim drawsel <pattern:text>", 2),
        Command::op("fawebim ceil <pattern:text>", 2),
        Command::op("fawebim lay <pattern:text>", 2),
        Command::op("fawebim pos <x:integer> <y:integer> <z:integer>", 2),
        Command::op("fawebim hpos1 <x:integer> <y:integer> <z:integer>", 2),
        Command::op("fawebim hpos2 <x:integer> <y:integer> <z:integer>", 2),
        Command::op("fawebim chunk <x:integer> <z:integer> [miny:integer] [maxy:integer]", 2),
        Command::op("fawebim fill <x:integer> <y:integer> <z:integer> <radius:integer> <pattern:text>", 2),
        Command::op("fawebim fillr <x:integer> <y:integer> <z:integer> <radius:integer> <pattern:text>", 2),
        Command::op("fawebim curve <x1:integer> <y1:integer> <z1:integer> <x2:integer> <y2:integer> <z2:integer> <x3:integer> <y3:integer> <z3:integer> [thickness:number] <pattern:text>", 2),
        Command::op("fawebim masks-list", 0),
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
        Some("pos1" | "pos" | "hpos1") => pos(host, event, sessions, Corner::One),
        Some("pos2" | "hpos2") => pos(host, event, sessions, Corner::Two),
        Some("sel") => sel(host, sessions, event),
        Some("desel") => {let key=source_key(event.source);lock_sessions(sessions).get(key).selection.clear();feedback(host,"fawebim desel: selection cleared");Flow::Cancel},
        Some("set" | "paint") => set(host, event, sessions, registry_cache),
        Some("checkerfill") => checker_fill(host,event,sessions,registry_cache),
        Some("maskedset" | "randomset" | "surfaceset" | "underlay" | "maskchoose") => selective_fill(host,event,sessions,registry_cache),
        Some("compactdown" | "compactup" | "compactwest" | "compacteast" | "compactnorth" | "compactsouth") => compact_operation(host,event,sessions,registry_cache),
        Some("levelterrain" | "raiseterrain" | "lowerterrain") => height_operation(host,event,sessions,registry_cache),
        Some("swapblocks" | "cycleblocks" | "delete" | "keep" | "coat") => material_operation(host,event,sessions,registry_cache),
        Some("extrude") => extrude_operation(host,event,sessions,registry_cache),
        Some("gradientfill" | "wavefill" | "noisefill" | "radialfill") => procedural_fill(host,event,sessions,registry_cache),
        Some("regionflip" | "regionrotate" | "regionrotateaxis" | "regionclone") => region_transform(host,event,sessions,registry_cache),
        Some("regionscale" | "regioncenter") => region_resize(host,event,sessions,registry_cache),
        Some("replace" | "substitute") => replace(host, event, sessions, registry_cache),
        Some("copy" | "duplicate") => copy_or_cut(host, event, sessions, clipboards, registry_cache, false),
        Some("cut") => copy_or_cut(host, event, sessions, clipboards, registry_cache, true),
        Some("paste" | "pasteat" | "pasteoffset" | "pastecenter" | "pastearray" | "pastenonair" | "pasteair" | "pastemasked") => paste(host, event, sessions, clipboards, registry_cache),
        Some("clipboardinfo" | "clipboardorigin") => { let key=source_key(event.source);let mut clips=clipboards.lock().unwrap_or_else(|p|p.into_inner());if let Some(c)=clips.get_mut(key){if event.command.literals.get(1).is_some_and(|s|s=="clipboardorigin"){c.set_origin(BlockVector3::at(integer(event,"x").unwrap_or(0),integer(event,"y").unwrap_or(0),integer(event,"z").unwrap_or(0)));}let s=c.size();let o=c.origin();feedback(host,&format!("fawebim clipboard: {} block(s), {}x{}x{}, origin=({}, {}, {})",c.volume(),s.x,s.y,s.z,o.x,o.y,o.z));}else{feedback(host,"fawebim clipboard: empty");}Flow::Cancel },
        Some("clipboardshift" | "clipboardscale" | "clipboardtrim" | "clipboardreplace") => modify_clipboard(host,event,clipboards,registry_cache),
        Some("clipboardchecksum" | "clipboardstats" | "clipboardpalette" | "clipboardcount" | "clipboardcompare") => inspect_clipboard(host,event,clipboards,registry_cache),
        Some("rotate" | "rotateaxis" | "cliprotate") => transform_clipboard(host, event, clipboards, registry_cache, true),
        Some("flip" | "clipflip") => transform_clipboard(host, event, clipboards, registry_cache, false),
        Some("clearclipboard" | "clipclear") => {
            let removed = clipboards.lock().unwrap_or_else(|p| p.into_inner()).clear(source_key(event.source));
            feedback(host, if removed { "fawebim clearclipboard: clipboard cleared" } else { "fawebim clearclipboard: clipboard already empty" });
            Flow::Cancel
        }
        Some("schemsave" | "schemload" | "schemloadat" | "schemlist" | "scheminfo" | "schemvalidate" | "schemcompare" | "schemstats" | "schemupgrade" | "schem") => schematic_command(host, event, clipboards, registry_cache),
        Some("sphere" | "hsphere" | "cylinder" | "hcylinder" | "pyramid" | "hpyramid" | "line" | "cone" | "hcone" | "torus" | "htorus" | "ellipsoid" | "hellipsoid" | "disk" | "hdisk" | "dome" | "hdome" | "cuboid" | "hcuboid" | "prism" | "hprism" | "staircase" | "ramp" | "curve" | "thickline" | "tube" | "capsule" | "hcapsule" | "ball" | "hball" | "cyl" | "hcyl" | "pyr" | "hpyr" | "segment" | "bezier" | "helix") => generate(host, event, sessions, registry_cache),
        Some("walls" | "faces" | "overlay" | "hollow" | "sides" | "outline" | "surface" | "shell" | "frame" | "drawsel" | "corners" | "pillars" | "cross" | "cross2d" | "lattice" | "stripes" | "border") => region_operation(host, event, sessions, registry_cache),
        Some("move" | "stack" | "shiftblocks" | "repeat") => translate_operation(host, event, sessions, registry_cache),
        Some("smooth" | "erode" | "dilate" | "blend" | "erosion" | "dilation") => morphology_operation(host, event, sessions, registry_cache),
        Some("brush" | "brushset" | "brushinfo" | "brushlist" | "brushclear" | "brushes" | "tool") => brush_config(host, event, brushes),
        Some("brushuse") => brush_use(host, event, sessions, brushes, registry_cache),
        Some("session" | "limit" | "timeout" | "fast" | "tips" | "watchdog" | "gmask" | "gpattern" | "gmaskclear" | "gpatternclear" | "sessionreset" | "gtexture" | "smask" | "perf" | "world") => session_config(host, event, sessions),
        Some("transform" | "transformreset" | "transformrotate" | "transformscale" | "transformoffset" | "transformflip" | "transformrandom" | "gtransform" | "transforms") => transform_config(host, event, sessions),
        Some("count" | "distr" | "aircount" | "solidcount" | "liquidcount" | "surfacecount" | "selectiondensity" | "selectionchecksum" | "size" | "center" | "distribution" | "dimensions" | "midpoint") => selection_info(host, event, sessions, registry_cache),
        Some("setbiome" | "biomedistr" | "paintbiome" | "biomecount" | "replacebiome" | "biomeinfo") => biome_operation(host, event, sessions, registry_cache),
        Some("gravity" | "naturalize" | "drain" | "green" | "snow" | "thaw" | "fall" | "natural" | "deliquid" | "grassify" | "snowfall" | "melt") => terrain_operation(host, event, sessions, registry_cache),
        Some("deform") => deform_operation(host, event, sessions, registry_cache),
        Some("layers") => layer_operation(host, event, sessions, registry_cache),
        Some("scatter") => scatter_operation(host, event, sessions, registry_cache),
        Some("forest" | "flora" | "pumpkins" | "ores" | "ore") => populate_operation(host, event, sessions, registry_cache),
        Some("floor" | "ceiling" | "ceil" | "lay" | "centerpattern" | "removeabove" | "removebelow" | "fixwater" | "fixlava" | "extinguish") => slice_operation(host, event, sessions, registry_cache),
        Some("flood" | "fill" | "fillr" | "removenear" | "connected" | "nearclear" | "replacenear") => near_operation(host, event, sessions, registry_cache),
        Some("expand" | "contract" | "shift" | "outset" | "inset") => selection_mutate(host, event, sessions),
        Some("help" | "commands" | "version" | "patterninfo" | "maskinfo" | "biomelist" | "masks" | "masks-list" | "patterns" | "we") => help_command(host, event, registry_cache),
        Some("history" | "historylimit" | "historylist" | "historystats") => history_config(host,event,sessions),
        Some("chunkselect" | "chunk" | "chunkexpand" | "chunklist" | "chunkinfo" | "listchunks" | "selectionarea" | "selectionbounds" | "selectionpad" | "selectionalign" | "selectioncorners" | "selectionchunks") => chunk_command(host,event,sessions),
        Some("inspect" | "columninfo" | "surfaceinfo") => inspect_command(host,event,registry_cache),
        Some("selectiontrim") => selection_trim(host,event,sessions,registry_cache),
        Some("selsphere" | "selellipsoid" | "selcylinder" | "selbox" | "selpolygon" | "selconvex" | "selpolyhedron") => selection_shape(host,event,sessions),
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
        "distribution"=>"distr","dimensions"=>"size","midpoint"=>"center","shiftblocks"=>"move","repeat"=>"stack","paintbiome"=>"setbiome","nearclear"|"replacenear"=>"removenear","connected"=>"flood",
        "ore"=>"ores","fill"|"fillr"=>"flood","curve"=>"bezier","chunk"=>"chunkselect","masks-list"=>"masks","gtexture"=>"gpattern","smask"=>"gmask","gtransform"|"transforms"=>"transform","biomeinfo"=>"biomedistr","chunkinfo"=>"selectionchunks","listchunks"=>"chunklist","drawsel"=>"frame","ceil"=>"ceiling","lay"=>"floor","perf"|"world"=>"session","we"=>"version","tool"=>"brushinfo","schem"=>"schemlist","brushes"=>"brushlist",
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

fn region_resize(host:&mut dyn ServerHost,event:&RunModCommandEvent<'_>,sessions:&Mutex<SessionManager>,registry_cache:&Arc<Mutex<HorizonRegistryCache>>)->Flow{let action=event.command.literals.get(1).map(String::as_str).unwrap_or("regionscale");let key=source_key(event.source);let mut region={let manager=lock_sessions(sessions);manager.peek(key).and_then(|s|s.selection.region())};let Some(ref mut region)=region else{feedback(host,&format!("fawebim {action}: no selection"));return Flow::Cancel;};let min=region.min();let max=region.max();let factors=(integer(event,"x").unwrap_or(1),integer(event,"y").unwrap_or(1),integer(event,"z").unwrap_or(1));if action=="regionscale"&&(!(1..=8).contains(&factors.0)||!(1..=8).contains(&factors.1)||!(1..=8).contains(&factors.2)){feedback(host,"fawebim regionscale: factors must be 1..8");return Flow::Cancel;}let multiplier=if action=="regionscale"{factors.0 as u64*factors.1 as u64*factors.2 as u64}else{1};if region.volume().saturating_mul(multiplier)>CuboidRegion::MAX_VOLUME{feedback(host,&format!("fawebim {action}: result exceeds the block limit"));return Flow::Cancel;}let offset=if action=="regioncenter"{(factors.0.saturating_sub((min.x as i64+max.x as i64).div_euclid(2)as i32),factors.1.saturating_sub((min.y as i64+max.y as i64).div_euclid(2)as i32),factors.2.saturating_sub((min.z as i64+max.z as i64).div_euclid(2)as i32))}else{(0,0,0)};let dimension=resolve_dimension(host,event);let mut world=HorizonWorld::new(host,dimension,registry_cache.clone());let mut source=Vec::new();region.for_each_position(|x,y,z|source.push((x,y,z,world.get_block(x,y,z))));let mut target=Vec::with_capacity(source.len()*multiplier as usize);for&(x,y,z,state)in &source{if action=="regionscale"{let base=(x.saturating_sub(min.x).saturating_mul(factors.0).saturating_add(min.x),y.saturating_sub(min.y).saturating_mul(factors.1).saturating_add(min.y),z.saturating_sub(min.z).saturating_mul(factors.2).saturating_add(min.z));for dy in 0..factors.1{for dz in 0..factors.2{for dx in 0..factors.0{target.push((base.0.saturating_add(dx),base.1.saturating_add(dy),base.2.saturating_add(dz),state));}}}}else{target.push((x.saturating_add(offset.0),y.saturating_add(offset.1),z.saturating_add(offset.2),state));}}let mut lo=BlockVector3::at(i32::MAX,i32::MAX,i32::MAX);let mut hi=BlockVector3::at(i32::MIN,i32::MIN,i32::MIN);for&(x,y,z,_)in &target{lo.x=lo.x.min(x);lo.y=lo.y.min(y);lo.z=lo.z.min(z);hi.x=hi.x.max(x);hi.y=hi.y.max(y);hi.z=hi.z.max(z);}let mut registry=CachedBlockRegistry::new(registry_cache.clone());let result={let mut manager=lock_sessions(sessions);let mut local=manager.get(key).clone();let mut edit=EditSession::open(&mut world,&mut registry,&mut local,format!("//{action}"),true);for&(x,y,z,_)in &source{edit.set_block(x,y,z,0);}for(x,y,z,state)in target{edit.set_block(x,y,z,state);}let result=edit.finish(&mut local,Some(&mut manager.edit_log));if result.is_ok(){local.selection.selector_mut().learn_region(RegionShape::Cuboid(CuboidRegion::new(lo,hi)));}*manager.get(key)=local;result};match result{Ok(n)=>feedback(host,&format!("fawebim {action}: {n} block(s) changed")),Err(e)=>feedback(host,&format!("fawebim {action}: {e}"))};Flow::Cancel}

fn region_transform(host:&mut dyn ServerHost,event:&RunModCommandEvent<'_>,sessions:&Mutex<SessionManager>,registry_cache:&Arc<Mutex<HorizonRegistryCache>>)->Flow{let action=event.command.literals.get(1).map(String::as_str).unwrap_or("regionflip");let key=source_key(event.source);let mut region={let manager=lock_sessions(sessions);manager.peek(key).and_then(|s|s.selection.region())};let Some(ref mut region)=region else{feedback(host,&format!("fawebim {action}: no selection"));return Flow::Cancel;};let axis=match event.command.argument("axis"){Some(CommandArgumentValue::String(s))=>s.trim().to_ascii_lowercase(),_=>"y".into()};if matches!(action,"regionflip"|"regionrotateaxis")&&!matches!(axis.as_str(),"x"|"y"|"z"){feedback(host,"fawebim regionflip: axis must be x, y, or z");return Flow::Cancel;}let turns=integer(event,"degrees").unwrap_or(0).div_euclid(90).rem_euclid(4);if matches!(action,"regionrotate"|"regionrotateaxis")&&integer(event,"degrees").unwrap_or(0)%90!=0{feedback(host,"fawebim regionrotate: degrees must be a multiple of 90");return Flow::Cancel;}let offset=(integer(event,"dx").unwrap_or(0),integer(event,"dy").unwrap_or(0),integer(event,"dz").unwrap_or(0));let min=region.min();let max=region.max();let dimension=resolve_dimension(host,event);let mut world=HorizonWorld::new(host,dimension,registry_cache.clone());let mut source=Vec::new();region.for_each_position(|x,y,z|source.push((x,y,z,world.get_block(x,y,z))));let mut registry=CachedBlockRegistry::new(registry_cache.clone());let state_transform=if matches!(action,"regionrotate"|"regionrotateaxis"){let rotation_axis=match axis.as_str(){"x"=>Axis::X,"z"=>Axis::Z,_=>Axis::Y};BlockStateTransform::of(&Transform::rotate(BlockVector3::ZERO,rotation_axis,integer(event,"degrees").unwrap_or(0)as f64))}else if action=="regionflip"{let axis=match axis.as_str(){"x"=>Axis::X,"z"=>Axis::Z,_=>Axis::Y};BlockStateTransform::of(&Transform::flip(BlockVector3::ZERO,axis))}else{None};let mut state_transform=state_transform;let mut transformed=Vec::with_capacity(source.len());for&(x,y,z,state)in &source{let target=if action=="regionclone"{(x.saturating_add(offset.0),y.saturating_add(offset.1),z.saturating_add(offset.2))}else if action=="regionflip"{match axis.as_str(){"x"=>(min.x.saturating_add(max.x).saturating_sub(x),y,z),"z"=>(x,y,min.z.saturating_add(max.z).saturating_sub(z)),_=>(x,min.y.saturating_add(max.y).saturating_sub(y),z)}}else{let(cx,cy,cz)=((min.x as f64+max.x as f64)/2.0,(min.y as f64+max.y as f64)/2.0,(min.z as f64+max.z as f64)/2.0);let(mut dx,mut dy,mut dz)=(x as f64-cx,y as f64-cy,z as f64-cz);for _ in 0..turns{match axis.as_str(){"x"=>(dy,dz)=(-dz,dy),"z"=>(dx,dy)=(-dy,dx),_=>(dx,dz)=(-dz,dx)}}((cx+dx).round()as i32,(cy+dy).round()as i32,(cz+dz).round()as i32)};let state=state_transform.as_mut().map_or(state,|transform|transform.apply(&mut registry,state as u32)as i32);transformed.push((target.0,target.1,target.2,state));}let mut lo=BlockVector3::at(i32::MAX,i32::MAX,i32::MAX);let mut hi=BlockVector3::at(i32::MIN,i32::MIN,i32::MIN);for&(x,y,z,_)in &transformed{lo.x=lo.x.min(x);lo.y=lo.y.min(y);lo.z=lo.z.min(z);hi.x=hi.x.max(x);hi.y=hi.y.max(y);hi.z=hi.z.max(z);}let result={let mut manager=lock_sessions(sessions);let mut local=manager.get(key).clone();let mut edit=EditSession::open(&mut world,&mut registry,&mut local,format!("//{action}"),true);if action!="regionclone"{for&(x,y,z,_)in &source{edit.set_block(x,y,z,0);}}for(x,y,z,state)in transformed{edit.set_block(x,y,z,state);}let result=edit.finish(&mut local,Some(&mut manager.edit_log));if result.is_ok(){local.selection.selector_mut().learn_region(RegionShape::Cuboid(CuboidRegion::new(lo,hi)));}*manager.get(key)=local;result};match result{Ok(n)=>feedback(host,&format!("fawebim {action}: {n} block(s) changed")),Err(e)=>feedback(host,&format!("fawebim {action}: {e}"))};Flow::Cancel}

fn procedural_fill(host:&mut dyn ServerHost,event:&RunModCommandEvent<'_>,sessions:&Mutex<SessionManager>,registry_cache:&Arc<Mutex<HorizonRegistryCache>>)->Flow{let action=event.command.literals.get(1).map(String::as_str).unwrap_or("gradientfill");let key=source_key(event.source);let mut region={let manager=lock_sessions(sessions);manager.peek(key).and_then(|s|s.selection.region())};let Some(ref mut region)=region else{feedback(host,&format!("fawebim {action}: no selection"));return Flow::Cancel;};let arg=|name|match event.command.argument(name){Some(CommandArgumentValue::String(s))=>s.trim(),_=>""};let patterns=arg("patterns");let pattern_text=match action{"gradientfill"=>format!("#gradient[{}][{}][{}][{patterns}]",arg("axis"),number(event,"min").unwrap_or(0.0),number(event,"max").unwrap_or(1.0)),"wavefill"=>format!("#wave[{}][{}][{patterns}]",arg("axis"),number(event,"wavelength").unwrap_or(1.0)),"noisefill"=>format!("#{}[{}][{patterns}]",arg("kind").to_ascii_lowercase(),number(event,"scale").unwrap_or(0.1)),_=>format!("#radialgradient[{}][{}][{}][{}][{patterns}]",number(event,"x").unwrap_or(0.0),number(event,"y").unwrap_or(0.0),number(event,"z").unwrap_or(0.0),number(event,"radius").unwrap_or(1.0))};let pattern={let reg=HorizonBlockRegistry::new(host,registry_cache.clone());match Pattern::parse(&reg,&pattern_text){Ok(p)=>p,Err(e)=>{feedback(host,&format!("fawebim {action}: {e}"));return Flow::Cancel;}}};let dimension=resolve_dimension(host,event);let mut world=HorizonWorld::new(host,dimension,registry_cache.clone());let mut registry=CachedBlockRegistry::new(registry_cache.clone());let result={let mut manager=lock_sessions(sessions);let mut local=manager.get(key).clone();let mut edit=EditSession::open(&mut world,&mut registry,&mut local,format!("//{action}"),true);region.for_each_position(|x,y,z|{edit.set_block(x,y,z,pattern.apply(x,y,z));});let result=edit.finish(&mut local,Some(&mut manager.edit_log));*manager.get(key)=local;result};match result{Ok(n)=>feedback(host,&format!("fawebim {action}: {n} block(s) changed")),Err(e)=>feedback(host,&format!("fawebim {action}: {e}"))};Flow::Cancel}

fn extrude_operation(host:&mut dyn ServerHost,event:&RunModCommandEvent<'_>,sessions:&Mutex<SessionManager>,registry_cache:&Arc<Mutex<HorizonRegistryCache>>)->Flow{let key=source_key(event.source);let mut region={let manager=lock_sessions(sessions);manager.peek(key).and_then(|s|s.selection.region())};let Some(ref mut region)=region else{feedback(host,"fawebim extrude: no selection");return Flow::Cancel;};let direction=match event.command.argument("direction"){Some(CommandArgumentValue::String(s))=>match s.trim().to_ascii_lowercase().as_str(){"up"|"y"=>(0,1,0),"down"|"-y"=>(0,-1,0),"east"|"x"=>(1,0,0),"west"|"-x"=>(-1,0,0),"south"|"z"=>(0,0,1),"north"|"-z"=>(0,0,-1),_=>{feedback(host,"fawebim extrude: invalid direction");return Flow::Cancel;}},_=>{feedback(host,"fawebim extrude: missing direction");return Flow::Cancel;}};let distance=integer(event,"distance").unwrap_or(1).clamp(1,256);let(mask,pattern)={let reg=HorizonBlockRegistry::new(host,registry_cache.clone());let mask_text=match event.command.argument("mask"){Some(CommandArgumentValue::String(s))=>s.trim(),_=>"#existing"};let pattern_text=match event.command.argument("pattern"){Some(CommandArgumentValue::String(s))=>s.trim(),_=>"stone"};let mask=match Mask::parse(&reg,mask_text){Ok(m)=>m,Err(e)=>{feedback(host,&format!("fawebim extrude: {e}"));return Flow::Cancel;}};let pattern=match Pattern::parse(&reg,pattern_text){Ok(p)=>p,Err(e)=>{feedback(host,&format!("fawebim extrude: {e}"));return Flow::Cancel;}};(mask,pattern)};let dimension=resolve_dimension(host,event);let mut world=HorizonWorld::new(host,dimension,registry_cache.clone());let mut registry=CachedBlockRegistry::new(registry_cache.clone());let mut placements=Vec::new();let snapshot=region.clone();region.for_each_position(|x,y,z|if !snapshot.contains(x+direction.0,y+direction.1,z+direction.2)&&mask.test(&world,&registry,x,y,z){for d in 1..=distance{let(px,py,pz)=(x+direction.0*d,y+direction.1*d,z+direction.2*d);placements.push((px,py,pz,pattern.apply(px,py,pz)));}});let result={let mut manager=lock_sessions(sessions);let mut local=manager.get(key).clone();let mut edit=EditSession::open(&mut world,&mut registry,&mut local,"//extrude",true);for(x,y,z,state)in placements{edit.set_block(x,y,z,state);}let result=edit.finish(&mut local,Some(&mut manager.edit_log));*manager.get(key)=local;result};match result{Ok(n)=>feedback(host,&format!("fawebim extrude: {n} block(s) changed")),Err(e)=>feedback(host,&format!("fawebim extrude: {e}"))};Flow::Cancel}

fn material_operation(host:&mut dyn ServerHost,event:&RunModCommandEvent<'_>,sessions:&Mutex<SessionManager>,registry_cache:&Arc<Mutex<HorizonRegistryCache>>)->Flow{let action=event.command.literals.get(1).map(String::as_str).unwrap_or("delete");let key=source_key(event.source);let mut region={let manager=lock_sessions(sessions);manager.peek(key).and_then(|s|s.selection.region())};let Some(ref mut region)=region else{feedback(host,&format!("fawebim {action}: no selection"));return Flow::Cancel;};let(states,mask,pattern)={let reg=HorizonBlockRegistry::new(host,registry_cache.clone());if action=="swapblocks"{let parse=|name:&str|match event.command.argument(name){Some(CommandArgumentValue::String(s))=>reg.parse(s.trim()),_=>-1};let(a,b)=(parse("first"),parse("second"));if a<0||b<0{feedback(host,"fawebim swapblocks: unknown block");return Flow::Cancel;}(vec![a,b],None,None)}else if action=="cycleblocks"{let text=match event.command.argument("blocks"){Some(CommandArgumentValue::String(s))=>s.trim(),_=>""};let states=text.split(',').map(|s|reg.parse(s.trim())).collect::<Vec<_>>();if states.len()<2||states.iter().any(|&s|s<0){feedback(host,"fawebim cycleblocks: expected at least two valid comma-separated blocks");return Flow::Cancel;}(states,None,None)}else{let text=match event.command.argument("mask"){Some(CommandArgumentValue::String(s))=>s.trim(),_=>"*"};let mask=match Mask::parse(&reg,text){Ok(m)=>m,Err(e)=>{feedback(host,&format!("fawebim {action}: {e}"));return Flow::Cancel;}};let pattern=if action=="coat"{let text=match event.command.argument("pattern"){Some(CommandArgumentValue::String(s))=>s.trim(),_=>"air"};match Pattern::parse(&reg,text){Ok(p)=>Some(p),Err(e)=>{feedback(host,&format!("fawebim coat: {e}"));return Flow::Cancel;}}}else{None};(Vec::new(),Some(mask),pattern)}};let dimension=resolve_dimension(host,event);let mut world=HorizonWorld::new(host,dimension,registry_cache.clone());let mut registry=CachedBlockRegistry::new(registry_cache.clone());let mut placements=Vec::new();region.for_each_position(|x,y,z|{let state=world.get_block(x,y,z);let target=match action{"swapblocks"=>if state==states[0]{Some(states[1])}else if state==states[1]{Some(states[0])}else{None},"cycleblocks"=>states.iter().position(|&s|s==state).map(|i|states[(i+1)%states.len()]),"delete"=>mask.as_ref().unwrap().test(&world,&registry,x,y,z).then_some(0),"keep"=>(!mask.as_ref().unwrap().test(&world,&registry,x,y,z)).then_some(0),"coat"=>{let exposed=[(1,0,0),(-1,0,0),(0,1,0),(0,-1,0),(0,0,1),(0,0,-1)].iter().any(|&(dx,dy,dz)|registry.is_air_like(world.get_block(x+dx,y+dy,z+dz)));(exposed&&mask.as_ref().unwrap().test(&world,&registry,x,y,z)).then(||pattern.as_ref().unwrap().apply(x,y,z))},_=>None};if let Some(to)=target{placements.push((x,y,z,to));}});let result={let mut manager=lock_sessions(sessions);let mut local=manager.get(key).clone();let mut edit=EditSession::open(&mut world,&mut registry,&mut local,format!("//{action}"),true);for(x,y,z,state)in placements{edit.set_block(x,y,z,state);}let result=edit.finish(&mut local,Some(&mut manager.edit_log));*manager.get(key)=local;result};match result{Ok(n)=>feedback(host,&format!("fawebim {action}: {n} block(s) changed")),Err(e)=>feedback(host,&format!("fawebim {action}: {e}"))};Flow::Cancel}

fn height_operation(host:&mut dyn ServerHost,event:&RunModCommandEvent<'_>,sessions:&Mutex<SessionManager>,registry_cache:&Arc<Mutex<HorizonRegistryCache>>)->Flow{let action=event.command.literals.get(1).map(String::as_str).unwrap_or("levelterrain");let key=source_key(event.source);let mut region={let manager=lock_sessions(sessions);manager.peek(key).and_then(|s|s.selection.region())};let Some(ref mut region)=region else{feedback(host,&format!("fawebim {action}: no selection"));return Flow::Cancel;};let pattern=if action!="lowerterrain"{let text=match event.command.argument("pattern"){Some(CommandArgumentValue::String(s))=>s.trim(),_=>"stone"};let reg=HorizonBlockRegistry::new(host,registry_cache.clone());match Pattern::parse(&reg,text){Ok(p)=>Some(p),Err(e)=>{feedback(host,&format!("fawebim {action}: {e}"));return Flow::Cancel;}}}else{None};let dimension=resolve_dimension(host,event);let mut world=HorizonWorld::new(host,dimension,registry_cache.clone());let mut registry=CachedBlockRegistry::new(registry_cache.clone());let min=region.min();let max=region.max();let amount=integer(event,"amount").unwrap_or(1).clamp(1,256);let target=integer(event,"y").unwrap_or(min.y).clamp(min.y,max.y);let mut placements=Vec::new();for z in min.z..=max.z{for x in min.x..=max.x{let top=(min.y..=max.y).rev().find(|&y|region.contains(x,y,z)&&!registry.is_air_like(world.get_block(x,y,z)));if let Some(top)=top{let desired=match action{"raiseterrain"=>top.saturating_add(amount).min(max.y),"lowerterrain"=>top.saturating_sub(amount).max(min.y.saturating_sub(1)),_=>target};if desired>top{for y in top+1..=desired{if region.contains(x,y,z){placements.push((x,y,z,pattern.as_ref().unwrap().apply(x,y,z)));}}}else if desired<top{for y in desired+1..=top{if region.contains(x,y,z){placements.push((x,y,z,0));}}}}}}let result={let mut manager=lock_sessions(sessions);let mut local=manager.get(key).clone();let mut edit=EditSession::open(&mut world,&mut registry,&mut local,format!("//{action}"),true);for(x,y,z,state)in placements{edit.set_block(x,y,z,state);}let result=edit.finish(&mut local,Some(&mut manager.edit_log));*manager.get(key)=local;result};match result{Ok(n)=>feedback(host,&format!("fawebim {action}: {n} block(s) changed")),Err(e)=>feedback(host,&format!("fawebim {action}: {e}"))};Flow::Cancel}

fn compact_operation(host:&mut dyn ServerHost,event:&RunModCommandEvent<'_>,sessions:&Mutex<SessionManager>,registry_cache:&Arc<Mutex<HorizonRegistryCache>>)->Flow{let action=event.command.literals.get(1).map(String::as_str).unwrap_or("compactdown");let key=source_key(event.source);let mut region={let manager=lock_sessions(sessions);manager.peek(key).and_then(|s|s.selection.region())};let Some(ref mut region)=region else{feedback(host,&format!("fawebim {action}: no selection"));return Flow::Cancel;};let dimension=resolve_dimension(host,event);let mut world=HorizonWorld::new(host,dimension,registry_cache.clone());let mut registry=CachedBlockRegistry::new(registry_cache.clone());let min=region.min();let max=region.max();let axis=if action.ends_with("down")||action.ends_with("up"){1}else if action.ends_with("west")||action.ends_with("east"){0}else{2};let high=action.ends_with("up")||action.ends_with("east")||action.ends_with("south");let mut placements=Vec::new();let mut process=|coords:Vec<(i32,i32,i32)>|{let states=coords.iter().filter_map(|&(x,y,z)|{let state=world.get_block(x,y,z);(!registry.is_air_like(state)).then_some(state)}).collect::<Vec<_>>();let offset=if high{coords.len()-states.len()}else{0};for(i,(x,y,z))in coords.into_iter().enumerate(){let state=if i>=offset&&i<offset+states.len(){states[i-offset]}else{0};placements.push((x,y,z,state));}};match axis{1=>for z in min.z..=max.z{for x in min.x..=max.x{process((min.y..=max.y).filter(|&y|region.contains(x,y,z)).map(|y|(x,y,z)).collect());}},0=>for z in min.z..=max.z{for y in min.y..=max.y{process((min.x..=max.x).filter(|&x|region.contains(x,y,z)).map(|x|(x,y,z)).collect());}},_=>for y in min.y..=max.y{for x in min.x..=max.x{process((min.z..=max.z).filter(|&z|region.contains(x,y,z)).map(|z|(x,y,z)).collect());}}}drop(process);let result={let mut manager=lock_sessions(sessions);let mut local=manager.get(key).clone();let mut edit=EditSession::open(&mut world,&mut registry,&mut local,format!("//{action}"),true);for(x,y,z,state)in placements{edit.set_block(x,y,z,state);}let result=edit.finish(&mut local,Some(&mut manager.edit_log));*manager.get(key)=local;result};match result{Ok(n)=>feedback(host,&format!("fawebim {action}: {n} block(s) changed")),Err(e)=>feedback(host,&format!("fawebim {action}: {e}"))};Flow::Cancel}

fn selective_fill(host:&mut dyn ServerHost,event:&RunModCommandEvent<'_>,sessions:&Mutex<SessionManager>,registry_cache:&Arc<Mutex<HorizonRegistryCache>>)->Flow{
    let action=event.command.literals.get(1).map(String::as_str).unwrap_or("maskedset");let key=source_key(event.source);let mut region={let manager=lock_sessions(sessions);manager.peek(key).and_then(|s|s.selection.region())};let Some(ref mut region)=region else{feedback(host,&format!("fawebim {action}: no selection"));return Flow::Cancel;};
    let(pattern,other,mask)={let reg=HorizonBlockRegistry::new(host,registry_cache.clone());let pattern_arg=if action=="maskchoose"{"matching"}else{"pattern"};let text=match event.command.argument(pattern_arg){Some(CommandArgumentValue::String(s))=>s.trim(),_=>"air"};let pattern=match Pattern::parse(&reg,text){Ok(p)=>p,Err(e)=>{feedback(host,&format!("fawebim {action}: {e}"));return Flow::Cancel;}};let other=if action=="maskchoose"{let text=match event.command.argument("other"){Some(CommandArgumentValue::String(s))=>s.trim(),_=>"air"};match Pattern::parse(&reg,text){Ok(p)=>Some(p),Err(e)=>{feedback(host,&format!("fawebim {action}: {e}"));return Flow::Cancel;}}}else{None};let mask_text=if action=="randomset"{format!("#chance[{}]",number(event,"chance").unwrap_or(0.0))}else{match event.command.argument("mask"){Some(CommandArgumentValue::String(s))=>s.clone(),_=>"*".into()}};let mask=match Mask::parse(&reg,&mask_text){Ok(m)=>m,Err(e)=>{feedback(host,&format!("fawebim {action}: {e}"));return Flow::Cancel;}};(pattern,other,mask)};
    let dimension=resolve_dimension(host,event);let mut world=HorizonWorld::new(host,dimension,registry_cache.clone());let mut registry=CachedBlockRegistry::new(registry_cache.clone());let depth=integer(event,"depth").unwrap_or(1).clamp(1,256);let mut placements=Vec::new();
    if matches!(action,"surfaceset"|"underlay"){let min=region.min();let max=region.max();for z in min.z..=max.z{for x in min.x..=max.x{let top=(min.y..=max.y).rev().find(|&y|region.contains(x,y,z)&&!registry.is_air_like(world.get_block(x,y,z)));if let Some(top)=top{for d in 0..depth{let y=if action=="underlay"{top-d-1}else{top-d};if y>=min.y&&region.contains(x,y,z){placements.push((x,y,z,pattern.apply(x,y,z)));}}}}}}
    else{region.for_each_position(|x,y,z|{let matched=mask.test(&world,&registry,x,y,z);if matched{placements.push((x,y,z,pattern.apply(x,y,z)));}else if let Some(ref other)=other{placements.push((x,y,z,other.apply(x,y,z)));}});}
    let result={let mut manager=lock_sessions(sessions);let mut local=manager.get(key).clone();let mut edit=EditSession::open(&mut world,&mut registry,&mut local,format!("//{action}"),true);for(x,y,z,state)in placements{edit.set_block(x,y,z,state);}let result=edit.finish(&mut local,Some(&mut manager.edit_log));*manager.get(key)=local;result};match result{Ok(n)=>feedback(host,&format!("fawebim {action}: {n} block(s) changed")),Err(e)=>feedback(host,&format!("fawebim {action}: {e}"))};Flow::Cancel
}

fn checker_fill(host:&mut dyn ServerHost,event:&RunModCommandEvent<'_>,sessions:&Mutex<SessionManager>,registry_cache:&Arc<Mutex<HorizonRegistryCache>>)->Flow{let key=source_key(event.source);let mut region={let manager=lock_sessions(sessions);manager.peek(key).and_then(|s|s.selection.region())};let Some(ref mut region)=region else{feedback(host,"fawebim checkerfill: no selection");return Flow::Cancel;};let(first,second)=(match event.command.argument("first"){Some(CommandArgumentValue::String(s))=>s.trim(),_=>"air"},match event.command.argument("second"){Some(CommandArgumentValue::String(s))=>s.trim(),_=>"air"});let pattern_text=format!("#checker[{first},{second}][{}]",integer(event,"size").unwrap_or(1).max(1));let pattern={let reg=HorizonBlockRegistry::new(host,registry_cache.clone());match Pattern::parse(&reg,&pattern_text){Ok(p)=>p,Err(e)=>{feedback(host,&format!("fawebim checkerfill: {e}"));return Flow::Cancel;}}};let dimension=resolve_dimension(host,event);let mut world=HorizonWorld::new(host,dimension,registry_cache.clone());let mut registry=CachedBlockRegistry::new(registry_cache.clone());let result={let mut manager=lock_sessions(sessions);let mut local=manager.get(key).clone();let mut edit=EditSession::open(&mut world,&mut registry,&mut local,"//checkerfill",true);region.for_each_position(|x,y,z|{edit.set_block(x,y,z,pattern.apply(x,y,z));});let result=edit.finish(&mut local,Some(&mut manager.edit_log));*manager.get(key)=local;result};match result{Ok(n)=>feedback(host,&format!("fawebim checkerfill: {n} block(s) changed")),Err(e)=>feedback(host,&format!("fawebim checkerfill: {e}"))};Flow::Cancel}

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

fn slice_operation(host:&mut dyn ServerHost,event:&RunModCommandEvent<'_>,sessions:&Mutex<SessionManager>,registry_cache:&Arc<Mutex<HorizonRegistryCache>>)->Flow{
    let action=canonical_command(event.command.literals.get(1).map(String::as_str).unwrap_or("floor"));let key=source_key(event.source);let mut region={let mgr=lock_sessions(sessions);mgr.peek(key).and_then(|s|s.selection.region())};let Some(ref mut region)=region else{feedback(host,&format!("fawebim {action}: no selection"));return Flow::Cancel;};let pattern=if matches!(action,"floor"|"ceiling"|"centerpattern"){let text=match event.command.argument("pattern"){Some(CommandArgumentValue::String(s))=>s.trim(),_=>"air"};let reg=HorizonBlockRegistry::new(host,registry_cache.clone());match Pattern::parse(&reg,text){Ok(p)=>Some(p),Err(e)=>{feedback(host,&format!("fawebim {action}: {e}"));return Flow::Cancel;}}}else{None};let dimension=resolve_dimension(host,event);let mut world=HorizonWorld::new(host,dimension,registry_cache.clone());let mut registry=CachedBlockRegistry::new(registry_cache.clone());let min=region.min();let max=region.max();let layers=integer(event,"layers").unwrap_or(1).clamp(1,max.y-min.y+1);let mut positions=Vec::new();
    if action=="centerpattern"{let xs=if(min.x+max.x)%2==0{vec![(min.x+max.x)/2]}else{vec![(min.x+max.x)/2,(min.x+max.x)/2+1]};let ys=if(min.y+max.y)%2==0{vec![(min.y+max.y)/2]}else{vec![(min.y+max.y)/2,(min.y+max.y)/2+1]};let zs=if(min.z+max.z)%2==0{vec![(min.z+max.z)/2]}else{vec![(min.z+max.z)/2,(min.z+max.z)/2+1]};for x in xs{for&y in &ys{for&z in &zs{if region.contains(x,y,z){positions.push((x,y,z,pattern.as_ref().unwrap().apply(x,y,z)));}}}}}
    else{region.for_each_position(|x,y,z|{let state=world.get_block(x,y,z);let replacement=match action{"floor" if y==min.y=>pattern.as_ref().map(|p|p.apply(x,y,z)),"ceiling" if y==max.y=>pattern.as_ref().map(|p|p.apply(x,y,z)),"removeabove" if y>max.y-layers=>Some(0),"removebelow" if y<min.y+layers=>Some(0),"extinguish"=>{let name=registry.name(state);(name.ends_with(":fire")||name.ends_with(":soul_fire")).then_some(0)},"fixwater"|"fixlava" if registry.is_air_like(state)=>{let needle=if action=="fixwater"{"water"}else{"lava"};[(1,0,0),(-1,0,0),(0,1,0),(0,-1,0),(0,0,1),(0,0,-1)].iter().find_map(|&(dx,dy,dz)|{let n=world.get_block(x+dx,y+dy,z+dz);registry.name(n).ends_with(needle).then_some(n)})},_=>None};if let Some(to)=replacement{positions.push((x,y,z,to));}});}
    let result={let mut manager=lock_sessions(sessions);let mut local=manager.get(key).clone();let mut edit=EditSession::open(&mut world,&mut registry,&mut local,format!("//{action}"),true);for(x,y,z,state)in positions{edit.set_block(x,y,z,state);}let result=edit.finish(&mut local,Some(&mut manager.edit_log));*manager.get(key)=local;result};match result{Ok(n)=>feedback(host,&format!("fawebim {action}: {n} block(s) changed")),Err(e)=>feedback(host,&format!("fawebim {action}: {e}"))};Flow::Cancel
}

fn population_hash(x:i32,y:i32,z:i32)->u32{let mut n=(x as u32).wrapping_mul(0x9E3779B1)^(y as u32).wrapping_mul(0x85EBCA77)^(z as u32).wrapping_mul(0xC2B2AE3D);n^=n>>16;n=n.wrapping_mul(0x7FEB352D);n^=n>>15;n}
fn populate_operation(host:&mut dyn ServerHost,event:&RunModCommandEvent<'_>,sessions:&Mutex<SessionManager>,registry_cache:&Arc<Mutex<HorizonRegistryCache>>)->Flow{
    let action=canonical_command(event.command.literals.get(1).map(String::as_str).unwrap_or("flora"));let density=integer(event,"density").unwrap_or(10).clamp(0,100)as u32;let key=source_key(event.source);let mut region={let mgr=lock_sessions(sessions);mgr.peek(key).and_then(|s|s.selection.region())};let Some(ref mut region)=region else{feedback(host,&format!("fawebim {action}: no selection"));return Flow::Cancel;};
    let (primary,secondary)={let reg=HorizonBlockRegistry::new(host,registry_cache.clone());let names=match action{"forest"=>("oak_log","oak_leaves"),"flora"=>("short_grass","dandelion"),"pumpkins"=>("pumpkin","pumpkin"),"ores"=>{let text=match event.command.argument("pattern"){Some(CommandArgumentValue::String(s))=>s.trim(),_=>"coal_ore"};let pattern=match Pattern::parse(&reg,text){Ok(p)=>p,Err(e)=>{feedback(host,&format!("fawebim ores: {e}"));return Flow::Cancel;}};return populate_ores(host,event,sessions,registry_cache,key,region,density,pattern);},_=>("short_grass","dandelion")};let a=reg.default_state(names.0);let b=reg.default_state(names.1);if a<0||b<0{feedback(host,&format!("fawebim {action}: required vanilla blocks are unavailable"));return Flow::Cancel;}(a,b)};
    let dimension=resolve_dimension(host,event);let mut world=HorizonWorld::new(host,dimension,registry_cache.clone());let registry=CachedBlockRegistry::new(registry_cache.clone());let min=region.min();let max=region.max();let mut placements=Vec::new();for z in min.z..=max.z{for x in min.x..=max.x{if population_hash(x,0,z)%100>=density{continue;}if let Some(y)=(min.y..=max.y).rev().find(|&y|region.contains(x,y,z)&&!registry.is_air_like(world.get_block(x,y,z))){match action{"forest"=>{for dy in 1..=5{placements.push((x,y+dy,z,primary));}operations::sphere(BlockVector3::at(x,y+5,z),2.5,2.0,2.5,false,|px,py,pz|placements.push((px,py,pz,secondary)));},"flora"=>placements.push((x,y+1,z,if population_hash(x,y,z)&1==0{primary}else{secondary})),_=>placements.push((x,y+1,z,primary))}}}}let mut registry=registry;let result=finish_population(sessions,key,&mut world,&mut registry,action,placements);match result{Ok(n)=>feedback(host,&format!("fawebim {action}: {n} block(s) changed")),Err(e)=>feedback(host,&format!("fawebim {action}: {e}"))};Flow::Cancel
}
fn populate_ores(host:&mut dyn ServerHost,event:&RunModCommandEvent<'_>,sessions:&Mutex<SessionManager>,registry_cache:&Arc<Mutex<HorizonRegistryCache>>,key:SelectionKey,region:&mut RegionShape,density:u32,pattern:Pattern)->Flow{let dimension=resolve_dimension(host,event);let mut world=HorizonWorld::new(host,dimension,registry_cache.clone());let mut registry=CachedBlockRegistry::new(registry_cache.clone());let mut placements=Vec::new();region.for_each_position(|x,y,z|{let state=world.get_block(x,y,z);if !registry.is_air_like(state)&&population_hash(x,y,z)%100<density{placements.push((x,y,z,pattern.apply(x,y,z)));}});let result=finish_population(sessions,key,&mut world,&mut registry,"ores",placements);match result{Ok(n)=>feedback(host,&format!("fawebim ores: {n} block(s) changed")),Err(e)=>feedback(host,&format!("fawebim ores: {e}"))};Flow::Cancel}
fn finish_population(sessions:&Mutex<SessionManager>,key:SelectionKey,world:&mut HorizonWorld<'_>,registry:&mut CachedBlockRegistry,name:&str,placements:Vec<(i32,i32,i32,i32)>)->Result<u64,String>{let mut manager=lock_sessions(sessions);let mut local=manager.get(key).clone();let mut edit=EditSession::open(world,registry,&mut local,format!("//{name}"),true);for(x,y,z,state)in placements{edit.set_block(x,y,z,state);}let result=edit.finish(&mut local,Some(&mut manager.edit_log));*manager.get(key)=local;result}

fn layer_operation(host:&mut dyn ServerHost,event:&RunModCommandEvent<'_>,sessions:&Mutex<SessionManager>,registry_cache:&Arc<Mutex<HorizonRegistryCache>>)->Flow{
    let text=match event.command.argument("layers"){Some(CommandArgumentValue::String(s))=>s.trim(),_=>""};let key=source_key(event.source);let mut region={let mgr=lock_sessions(sessions);mgr.peek(key).and_then(|s|s.selection.region())};let Some(ref mut region)=region else{feedback(host,"fawebim layers: no selection");return Flow::Cancel;};let layers={let reg=HorizonBlockRegistry::new(host,registry_cache.clone());let mut out=Vec::new();for item in text.split(',').map(str::trim).filter(|s|!s.is_empty()){let(depth,body)=item.split_once('*').map_or((1,item),|(n,p)|(n.parse::<i32>().unwrap_or(0),p.trim()));if depth<=0{feedback(host,&format!("fawebim layers: invalid depth in '{item}'"));return Flow::Cancel;}match Pattern::parse(&reg,body){Ok(p)=>out.push((depth,p)),Err(e)=>{feedback(host,&format!("fawebim layers: {e}"));return Flow::Cancel;}}}out};if layers.is_empty(){feedback(host,"fawebim layers: expected depth*pattern entries");return Flow::Cancel;}let total:i32=layers.iter().map(|v|v.0).sum();let max_y=region.max().y;let dimension=resolve_dimension(host,event);let mut world=HorizonWorld::new(host,dimension,registry_cache.clone());let mut registry=CachedBlockRegistry::new(registry_cache.clone());let result={let mut manager=lock_sessions(sessions);let mut local=manager.get(key).clone();let mut edit=EditSession::open(&mut world,&mut registry,&mut local,"//layers",true);region.for_each_position(|x,y,z|{let mut slot=(max_y-y).rem_euclid(total);for(depth,pattern)in &layers{if slot<*depth{edit.set_block(x,y,z,pattern.apply(x,y,z));break;}slot-=*depth;}});let result=edit.finish(&mut local,Some(&mut manager.edit_log));*manager.get(key)=local;result};match result{Ok(n)=>feedback(host,&format!("fawebim layers: {n} block(s) changed")),Err(e)=>feedback(host,&format!("fawebim layers: {e}"))};Flow::Cancel
}

fn scatter_operation(host:&mut dyn ServerHost,event:&RunModCommandEvent<'_>,sessions:&Mutex<SessionManager>,registry_cache:&Arc<Mutex<HorizonRegistryCache>>)->Flow{
    let(Some(x),Some(y),Some(z))=(integer(event,"x"),integer(event,"y"),integer(event,"z"))else{feedback(host,"fawebim scatter: missing coordinates");return Flow::Cancel;};let radius=integer(event,"radius").unwrap_or(1).clamp(1,128);let wanted=integer(event,"points").unwrap_or(1).clamp(1,100_000)as usize;let text=match event.command.argument("pattern"){Some(CommandArgumentValue::String(s))=>s.trim(),_=>"air"};let pattern={let reg=HorizonBlockRegistry::new(host,registry_cache.clone());match Pattern::parse(&reg,text){Ok(p)=>p,Err(e)=>{feedback(host,&format!("fawebim scatter: {e}"));return Flow::Cancel;}}};let mut seed=(x as u32 as u64)<<32^(y as u32 as u64)<<16^(z as u32 as u64)^0x9E3779B97F4A7C15;let span=(radius*2+1)as u64;let mut points=HashSet::new();for _ in 0..wanted.saturating_mul(20){seed^=seed>>12;seed^=seed<<25;seed^=seed>>27;let a=seed.wrapping_mul(2685821657736338717);let dx=(a%span)as i32-radius;let dy=((a>>21)%span)as i32-radius;let dz=((a>>42)%span)as i32-radius;if dx*dx+dy*dy+dz*dz<=radius*radius{points.insert((x.saturating_add(dx),y.saturating_add(dy),z.saturating_add(dz)));if points.len()>=wanted{break;}}}let key=source_key(event.source);let dimension=resolve_dimension(host,event);let mut world=HorizonWorld::new(host,dimension,registry_cache.clone());let mut registry=CachedBlockRegistry::new(registry_cache.clone());let result={let mut manager=lock_sessions(sessions);let mut local=manager.get(key).clone();let mut edit=EditSession::open(&mut world,&mut registry,&mut local,"//scatter",true);for(px,py,pz)in points{edit.set_block(px,py,pz,pattern.apply(px,py,pz));}let result=edit.finish(&mut local,Some(&mut manager.edit_log));*manager.get(key)=local;result};match result{Ok(n)=>feedback(host,&format!("fawebim scatter: {n} block(s) changed")),Err(e)=>feedback(host,&format!("fawebim scatter: {e}"))};Flow::Cancel
}

fn near_operation(host:&mut dyn ServerHost,event:&RunModCommandEvent<'_>,sessions:&Mutex<SessionManager>,registry_cache:&Arc<Mutex<HorizonRegistryCache>>)->Flow{
    let action=canonical_command(event.command.literals.get(1).map(String::as_str).unwrap_or("flood"));let (Some(x),Some(y),Some(z))=(integer(event,"x"),integer(event,"y"),integer(event,"z"))else{feedback(host,&format!("fawebim {action}: missing coordinates"));return Flow::Cancel;};let radius=integer(event,"radius").unwrap_or(0).clamp(1,128);let key=source_key(event.source);
    let (pattern,mask)={let reg=HorizonBlockRegistry::new(host,registry_cache.clone());if action=="flood"{let text=match event.command.argument("pattern"){Some(CommandArgumentValue::String(s))=>s.trim(),_=>"air"};match Pattern::parse(&reg,text){Ok(p)=>(Some(p),None),Err(e)=>{feedback(host,&format!("fawebim flood: {e}"));return Flow::Cancel;}}}else{let text=match event.command.argument("mask"){Some(CommandArgumentValue::String(s))=>s.trim(),_=>"#existing"};match Mask::parse(&reg,text){Ok(m)=>(None,Some(m)),Err(e)=>{feedback(host,&format!("fawebim removenear: {e}"));return Flow::Cancel;}}}};
    let dimension=resolve_dimension(host,event);let mut world=HorizonWorld::new(host,dimension,registry_cache.clone());let mut registry=CachedBlockRegistry::new(registry_cache.clone());let mut positions=Vec::new();if action=="flood"{let source=world.get_block(x,y,z);let mut queue=VecDeque::from([(x,y,z)]);let mut seen=HashSet::from([(x,y,z)]);let r2=radius*radius;while let Some((px,py,pz))=queue.pop_front(){if positions.len()>=2_000_000{break;}if (px-x).pow(2)+(py-y).pow(2)+(pz-z).pow(2)>r2||world.get_block(px,py,pz)!=source{continue;}positions.push((px,py,pz));for(dx,dy,dz)in[(1,0,0),(-1,0,0),(0,1,0),(0,-1,0),(0,0,1),(0,0,-1)]{let next=(px.saturating_add(dx),py.saturating_add(dy),pz.saturating_add(dz));if seen.insert(next){queue.push_back(next);}}}}else{let r=radius as f64;operations::sphere(BlockVector3::at(x,y,z),r,r,r,false,|px,py,pz|{if mask.as_ref().is_some_and(|m|m.test(&world,&registry,px,py,pz)){positions.push((px,py,pz));}});}
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
    if action=="biomecount"{let name=match event.command.argument("biome"){Some(CommandArgumentValue::String(s))=>s.trim(),_=>""};let id={let reg=HorizonBlockRegistry::new(host,registry_cache.clone());reg.biome(name)};if id<0{feedback(host,&format!("fawebim biomecount: unknown biome '{name}'"));return Flow::Cancel;}let dimension=resolve_dimension(host,event);let world=HorizonWorld::new(host,dimension,registry_cache.clone());let mut count=0u64;region.for_each_position(|x,y,z|if world.get_biome(x,y,z)==id{count+=1;});drop(world);feedback(host,&format!("fawebim biomecount: {count}/{} cell(s) are {name}",region.volume()));return Flow::Cancel;}
    if action=="replacebiome"{let(from_name,to_name)=(match event.command.argument("from"){Some(CommandArgumentValue::String(s))=>s.trim(),_=>""},match event.command.argument("to"){Some(CommandArgumentValue::String(s))=>s.trim(),_=>""});let(from,to)={let reg=HorizonBlockRegistry::new(host,registry_cache.clone());(reg.biome(from_name),reg.biome(to_name))};if from<0||to<0{feedback(host,"fawebim replacebiome: unknown source or destination biome");return Flow::Cancel;}let dimension=resolve_dimension(host,event);let mut world=HorizonWorld::new(host,dimension,registry_cache.clone());let mut registry=CachedBlockRegistry::new(registry_cache.clone());let mut changed=0u64;let result={let mut manager=lock_sessions(sessions);let mut local=manager.get(key).clone();let mut edit=EditSession::open(&mut world,&mut registry,&mut local,format!("//replacebiome {from_name} {to_name}"),true);region.for_each_position(|x,y,z|if edit.get_biome(x,y,z)==from&&edit.set_biome(x,y,z,to){changed+=1;});let result=edit.finish(&mut local,Some(&mut manager.edit_log));*manager.get(key)=local;result};match result{Ok(_)=>feedback(host,&format!("fawebim replacebiome: {changed} cell(s) changed")),Err(e)=>feedback(host,&format!("fawebim replacebiome: {e}"))};return Flow::Cancel;}
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
    let count_mask=match action{"count"=>Some(match event.command.argument("mask"){Some(CommandArgumentValue::String(s))=>s.as_str(),_=>"*"}),"aircount"=>Some("#air"),"solidcount"=>Some("#solid"),"liquidcount"=>Some("#liquid"),"surfacecount"=>Some("#surface"),_=>None};let mask=if let Some(text)=count_mask{let reg=HorizonBlockRegistry::new(host,registry_cache.clone());match Mask::parse(&reg,text){Ok(m)=>Some(m),Err(e)=>{feedback(host,&format!("fawebim {action}: {e}"));return Flow::Cancel;}}}else{None};let dimension=resolve_dimension(host,event);let world=HorizonWorld::new(host,dimension,registry_cache.clone());let registry=CachedBlockRegistry::new(registry_cache.clone());let mut counts=HashMap::<i32,u64>::new();let mut matched=0u64;let mut nonair=0u64;let mut checksum=0xcbf29ce484222325u64;region.for_each_position(|x,y,z|{let state=world.get_block(x,y,z);if !registry.is_air_like(state){nonair+=1;}checksum^=(state as u32 as u64)^((x as u32 as u64)<<32)^(y as u32 as u64).rotate_left(17)^(z as u32 as u64).rotate_left(41);checksum=checksum.wrapping_mul(0x100000001b3);if mask.as_ref().map_or(true,|m|m.test(&world,&registry,x,y,z)){matched+=1;*counts.entry(state).or_insert(0)+=1;}});
    if action=="selectiondensity"{feedback(host,&format!("fawebim selectiondensity: {nonair}/{} non-air blocks ({:.2}%)",region.volume(),nonair as f64*100.0/region.volume().max(1)as f64));}else if action=="selectionchecksum"{feedback(host,&format!("fawebim selectionchecksum: {checksum:016x} over {} block(s)",region.volume()));}else if count_mask.is_some(){feedback(host,&format!("fawebim {action}: {matched} matching block(s) out of {}",region.volume()));}else{let mut rows=counts.into_iter().collect::<Vec<_>>();rows.sort_by(|a,b|b.1.cmp(&a.1).then(a.0.cmp(&b.0)));let shown=rows.iter().take(12).map(|(state,n)|format!("{}={n}",registry.name(*state))).collect::<Vec<_>>().join(", ");feedback(host,&format!("fawebim distr: {matched} block(s), {shown}"));}Flow::Cancel
}

fn transform_config(host:&mut dyn ServerHost,event:&RunModCommandEvent<'_>,sessions:&Mutex<SessionManager>)->Flow{
    let action=canonical_command(event.command.literals.get(1).map(String::as_str).unwrap_or("transform"));let key=source_key(event.source);let mut manager=lock_sessions(sessions);let session=manager.get(key);let axis=||match event.command.argument("axis"){Some(CommandArgumentValue::String(s))=>match s.trim().to_ascii_lowercase().as_str(){"x"=>Some(Axis::X),"y"=>Some(Axis::Y),"z"=>Some(Axis::Z),_=>None},_=>None};
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
        "gmaskclear"=>{session.global_mask_expr=None;feedback(host,"fawebim gmask: disabled");},
        "gpatternclear"=>{session.global_pattern_expr=None;feedback(host,"fawebim gpattern: disabled");},
        "sessionreset"=>{*session=Default::default();feedback(host,"fawebim session: reset to defaults");},
        _=>feedback(host,&format!("fawebim session: selector={}, selection={}, history={}/{}, limit={}, timeout={}, fast={}, tips={}, watchdog={}",session.selection.selector().kind().as_str(),session.selection.describe(),session.history.len(),session.history.len(),session.max_blocks_changed,session.timeout_secs,session.fast_mode,session.tips,session.watchdog)),
    }Flow::Cancel
}

fn schematic_command(host:&mut dyn ServerHost,event:&RunModCommandEvent<'_>,clipboards:&Mutex<ClipboardManager>,registry_cache:&Arc<Mutex<HorizonRegistryCache>>)->Flow{
    let action=canonical_command(event.command.literals.get(1).map(String::as_str).unwrap_or("schemlist"));let key=source_key(event.source);
    if action=="schemlist"{let folder=ConfigPath::new("schematics").unwrap();match host.data_files().list(&folder,None,1024){Ok(entries)=>{let names=entries.into_iter().filter_map(|e|e.name.strip_suffix(".fawebim").map(str::to_owned)).collect::<Vec<_>>();feedback(host,&format!("fawebim schematics ({}): {}",names.len(),if names.is_empty(){"none".into()}else{names.join(", ")}));},Err(e)=>feedback(host,&format!("fawebim schemlist: {e:?}"))}return Flow::Cancel;}
    let raw=match event.command.argument("name"){Some(CommandArgumentValue::String(s))=>s.trim(),_=>""};if raw.is_empty()||!raw.chars().all(|c|c.is_ascii_alphanumeric()||matches!(c,'_'|'-'|'.')){feedback(host,"fawebim schematic: name may contain only letters, numbers, _, -, and .");return Flow::Cancel;}let path=match ConfigPath::new(&format!("schematics/{raw}.fawebim")){Ok(p)=>p,Err(e)=>{feedback(host,&format!("fawebim schematic: {e:?}"));return Flow::Cancel;}};
    if action=="schemsave"{let clip={clipboards.lock().unwrap_or_else(|p|p.into_inner()).get(key).cloned()};let Some(clip)=clip else{feedback(host,"fawebim schemsave: clipboard is empty");return Flow::Cancel;};let reg=CachedBlockRegistry::new(registry_cache.clone());match schematic::encode(&clip,&reg).and_then(|bytes|host.data_files().write(&path,&bytes).map(|_|bytes.len()).map_err(|e|format!("{e:?}"))){Ok(bytes)=>feedback(host,&format!("fawebim schemsave: saved {raw} ({bytes} bytes)")),Err(e)=>feedback(host,&format!("fawebim schemsave: {e}"))}}
    else{match host.data_files().read(&path).map_err(|e|format!("{e:?}")).and_then(|bytes|{let reg=HorizonBlockRegistry::new(host,registry_cache.clone());schematic::decode(&bytes,&reg)}){Ok(mut clip)=>{let volume=clip.volume();if matches!(action,"schemload"|"schemloadat"){if action=="schemloadat"{clip.set_origin(BlockVector3::at(integer(event,"x").unwrap_or(0),integer(event,"y").unwrap_or(0),integer(event,"z").unwrap_or(0)));}let origin=clip.origin();clipboards.lock().unwrap_or_else(|p|p.into_inner()).set(key,clip);feedback(host,&format!("fawebim {action}: loaded {raw} ({volume} blocks), origin=({}, {}, {})",origin.x,origin.y,origin.z));}else if action=="schemstats"{let size=clip.size();let capacity=(size.x as usize).saturating_mul(size.y as usize).saturating_mul(size.z as usize);let counts=clip.state_counts();let registry=CachedBlockRegistry::new(registry_cache.clone());let top=counts.iter().take(8).map(|(state,count)|format!("{}={count}",registry.describe(*state))).collect::<Vec<_>>().join(", ");feedback(host,&format!("fawebim schemstats: {raw}, blocks={volume}, holes={}, unique={}, top=[{top}]",capacity.saturating_sub(volume),counts.len()));}else if action=="schemcompare"{let current={clipboards.lock().unwrap_or_else(|p|p.into_inner()).get(key).cloned()};match current{Some(current)=>match current.difference_count(&clip){Some(changed)=>feedback(host,&format!("fawebim schemcompare: {raw}, differing={changed}, identical={}, origin_equal={}",changed==0,current.origin()==clip.origin())),None=>{let a=current.size();let b=clip.size();feedback(host,&format!("fawebim schemcompare: incompatible sizes {}x{}x{} versus {}x{}x{}",a.x,a.y,a.z,b.x,b.y,b.z));}},None=>feedback(host,"fawebim schemcompare: clipboard is empty")}}else if action=="schemupgrade"{let reg=CachedBlockRegistry::new(registry_cache.clone());match schematic::encode(&clip,&reg).and_then(|bytes|host.data_files().write(&path,&bytes).map(|_|bytes.len()).map_err(|e|format!("{e:?}"))){Ok(bytes)=>feedback(host,&format!("fawebim schemupgrade: rewrote {raw} as compact checksummed v3 ({bytes} bytes)")),Err(e)=>feedback(host,&format!("fawebim schemupgrade: {e}"))}}else{let size=clip.size();let origin=clip.origin();feedback(host,&format!("fawebim {action}: {raw}, valid=true, blocks={volume}, size={}x{}x{}, origin=({}, {}, {}), checksum={:016x}",size.x,size.y,size.z,origin.x,origin.y,origin.z,clip.checksum()));}},Err(e)=>feedback(host,&format!("fawebim {action}: {e}"))}}
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
    let snapshot=region.clone();let min=snapshot.min();let max=snapshot.max();let thickness=integer(event,"thickness").unwrap_or(1).clamp(1,64);let center=((min.x+max.x)/2,(min.y+max.y)/2,(min.z+max.z)/2);let gaps=(integer(event,"xgap").unwrap_or(1).max(1),integer(event,"ygap").unwrap_or(1).max(1),integer(event,"zgap").unwrap_or(1).max(1));let spacing=integer(event,"spacing").unwrap_or(1).max(1);let axis=match event.command.argument("axis"){Some(CommandArgumentValue::String(s))=>s.trim().to_ascii_lowercase(),_=>"y".into()};let mut points=Vec::new();
    region.for_each_position(|x,y,z|{let selected=match name{
        "walls"=>x==min.x||x==max.x||z==min.z||z==max.z,
        "faces"=>x==min.x||x==max.x||y==min.y||y==max.y||z==min.z||z==max.z,
        "overlay"=>!snapshot.contains(x,y+1,z),
        "frame"=>[x==min.x||x==max.x,y==min.y||y==max.y,z==min.z||z==max.z].into_iter().filter(|v|*v).count()>=2,
        "corners"=>(x==min.x||x==max.x)&&(y==min.y||y==max.y)&&(z==min.z||z==max.z),
        "pillars"=>(x==min.x||x==max.x)&&(z==min.z||z==max.z),
        "cross"=>x==center.0||y==center.1||z==center.2,
        "cross2d"=>y==center.1&&(x==center.0||z==center.2),
        "lattice"=>(x-min.x).rem_euclid(gaps.0)==0&&(y-min.y).rem_euclid(gaps.1)==0&&(z-min.z).rem_euclid(gaps.2)==0,
        "stripes"=>match axis.as_str(){"x"=>(x-min.x).rem_euclid(spacing)==0,"z"=>(z-min.z).rem_euclid(spacing)==0,_=>(y-min.y).rem_euclid(spacing)==0},
        "border"=>{let dx=(x-min.x).min(max.x-x);let dy=(y-min.y).min(max.y-y);let dz=(z-min.z).min(max.z-z);dx.min(dy).min(dz)<thickness},
        "hollow"=>{let mut edge=false;'outer:for d in 1..=thickness{for (dx,dy,dz) in [(d,0,0),(-d,0,0),(0,d,0),(0,-d,0),(0,0,d),(0,0,-d)]{if !snapshot.contains(x.saturating_add(dx),y.saturating_add(dy),z.saturating_add(dz)){edge=true;break 'outer;}}}edge},
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
    let mut registry=CachedBlockRegistry::new(registry_cache.clone());let result={let mut manager=lock_sessions(sessions);let mut local=manager.get(key).clone();let mut edit=EditSession::open(&mut world,&mut registry,&mut local,format!("//{name}"),true);if name=="move"{for&(x,y,z,_)in &source{edit.set_block(x,y,z,0);}}for i in 1..=count{for&(x,y,z,state)in &source{edit.set_block(x.saturating_add(dx.saturating_mul(i)),y.saturating_add(dy.saturating_mul(i)),z.saturating_add(dz.saturating_mul(i)),state);}}let result=edit.finish(&mut local,Some(&mut manager.edit_log));*manager.get(key)=local;result};
    match result{Ok(n)=>feedback(host,&format!("fawebim {name}: {n} block(s) changed")),Err(e)=>feedback(host,&format!("fawebim {name}: {e}"))};Flow::Cancel
}

fn generate(
    host: &mut dyn ServerHost,
    event: &RunModCommandEvent<'_>,
    sessions: &Mutex<SessionManager>,
    registry_cache: &Arc<Mutex<HorizonRegistryCache>>,
) -> Flow {
    let name=canonical_command(event.command.literals.get(1).map(String::as_str).unwrap_or("shape"));
    for parameter in ["radius","rx","ry","rz","major","minor"] { if let Some(value)=number(event,parameter) { if !value.is_finite() || value.abs()>512.0 { feedback(host,&format!("fawebim {name}: {parameter} must be finite and at most 512"));return Flow::Cancel; } } }
    for parameter in ["thickness","turns"] { if let Some(value)=number(event,parameter) { if !value.is_finite() || value.abs()>1024.0 { feedback(host,&format!("fawebim {name}: {parameter} must be finite and at most 1024"));return Flow::Cancel; } } }
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
            "ellipsoid"|"hellipsoid"=>{let(rx,ry,rz)=(number(event,"rx").unwrap_or(0.0),number(event,"ry").unwrap_or(0.0),number(event,"rz").unwrap_or(0.0));if rx>0.0&&ry>0.0&&rz>0.0{operations::sphere(center,rx,ry,rz,name=="hellipsoid",&mut place);}},
            "disk"|"hdisk"=>{let(rx,rz)=(number(event,"rx").unwrap_or(0.0),number(event,"rz").unwrap_or(0.0));if rx>0.0&&rz>0.0{operations::cylinder(center,rx,rz,1,name=="hdisk",&mut place);}},
            "dome"|"hdome"=>{let(rx,ry,rz)=(number(event,"rx").unwrap_or(0.0),number(event,"ry").unwrap_or(0.0),number(event,"rz").unwrap_or(0.0));if rx>0.0&&ry>0.0&&rz>0.0{operations::sphere(center,rx,ry,rz,name=="hdome",|px,py,pz|if py>=center.y{place(px,py,pz);});}},
            "cuboid"|"hcuboid"=>{if let(Some(x2),Some(y2),Some(z2))=(coord("x2"),coord("y2"),coord("z2")){operations::cuboid(center,BlockVector3::at(x2,y2,z2),name=="hcuboid",&mut place);}},
            "prism"|"hprism"=>{let(sides,radius,height)=(integer(event,"sides").unwrap_or(3),number(event,"radius").unwrap_or(0.0),integer(event,"height").unwrap_or(0));if radius>0.0&&height!=0{operations::regular_prism(center,sides,radius,height,name=="hprism",&mut place);}},
            "capsule"|"hcapsule"=>{let(radius,height)=(number(event,"radius").unwrap_or(0.0),integer(event,"height").unwrap_or(0));if radius>0.0&&height>0{operations::capsule(center,radius,height,name=="hcapsule",&mut place);}},
            "staircase"|"ramp"=>{let direction=match event.command.argument("direction"){Some(CommandArgumentValue::String(s))=>match s.trim().to_ascii_lowercase().as_str(){"x"|"east"=>(1,0),"-x"|"west"=>(-1,0),"z"|"south"=>(0,1),"-z"|"north"=>(0,-1),_=>(1,0)},_=>(1,0)};operations::staircase(center,integer(event,"length").unwrap_or(1),integer(event,"width").unwrap_or(1),integer(event,"rise").unwrap_or(1),direction,name=="ramp",&mut place);},
            "line"=>{if let (Some(x2),Some(y2),Some(z2))=(coord("x2"),coord("y2"),coord("z2")){operations::line(center,BlockVector3::at(x2,y2,z2),1.0,&mut place);}},
            "thickline"|"tube"=>{if let(Some(x2),Some(y2),Some(z2))=(coord("x2"),coord("y2"),coord("z2")){let thickness=number(event,"thickness").unwrap_or(1.0).clamp(1.0,64.0);if name=="tube"{operations::hollow_line(center,BlockVector3::at(x2,y2,z2),thickness,&mut place);}else{operations::line(center,BlockVector3::at(x2,y2,z2),thickness,&mut place);}}},
            "bezier"=>{if let(Some(x2),Some(y2),Some(z2),Some(x3),Some(y3),Some(z3))=(coord("x2"),coord("y2"),coord("z2"),coord("x3"),coord("y3"),coord("z3")){operations::quadratic_bezier(center,BlockVector3::at(x2,y2,z2),BlockVector3::at(x3,y3,z3),number(event,"thickness").unwrap_or(1.0).clamp(1.0,32.0),&mut place);}},
            "helix"=>{let(radius,height,turns,thickness)=(number(event,"radius").unwrap_or(0.0),integer(event,"height").unwrap_or(0),number(event,"turns").unwrap_or(1.0),number(event,"thickness").unwrap_or(1.0).clamp(1.0,32.0));if radius>0.0&&height!=0&&turns!=0.0{operations::helix(center,radius,height,turns,thickness,&mut place);}},
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
    let base=clip.origin();
    let origin=match event.command.literals.get(1).map(String::as_str){
        Some("pasteat")=>BlockVector3::at(integer(event,"x").unwrap_or(base.x),integer(event,"y").unwrap_or(base.y),integer(event,"z").unwrap_or(base.z)),
        Some("pasteoffset")=>BlockVector3::at(base.x+integer(event,"dx").unwrap_or(0),base.y+integer(event,"dy").unwrap_or(0),base.z+integer(event,"dz").unwrap_or(0)),
        Some("pastecenter")=>{let size=clip.size();BlockVector3::at(integer(event,"x").unwrap_or(base.x)-(size.x-1)/2,integer(event,"y").unwrap_or(base.y)-(size.y-1)/2,integer(event,"z").unwrap_or(base.z)-(size.z-1)/2)},
        _=>base,
    };let action=event.command.literals.get(1).map(String::as_str).unwrap_or("paste");let destination_mask=if action=="pastemasked"{let text=match event.command.argument("mask"){Some(CommandArgumentValue::String(s))=>s.trim(),_=>"*"};let reg=HorizonBlockRegistry::new(host,registry_cache.clone());match Mask::parse(&reg,text){Ok(mask)=>Some(mask),Err(e)=>{feedback(host,&format!("fawebim pastemasked: {e}"));return Flow::Cancel;}}}else{None};let dimension=resolve_dimension(host,event);
    let mut world=HorizonWorld::new(host,dimension,registry_cache.clone());
    let mut registry=CachedBlockRegistry::new(registry_cache.clone());let mut placements=Vec::new();let counts=if action=="pastearray"{(integer(event,"nx").unwrap_or(1).clamp(1,64),integer(event,"ny").unwrap_or(1).clamp(1,64),integer(event,"nz").unwrap_or(1).clamp(1,64))}else{(1,1,1)};let copies=counts.0 as u64*counts.1 as u64*counts.2 as u64;if copies.saturating_mul(clip.volume()as u64)>CuboidRegion::MAX_VOLUME{return{drop(world);feedback(host,"fawebim pastearray: result exceeds the block limit");Flow::Cancel};}let step=(integer(event,"dx").unwrap_or(0),integer(event,"dy").unwrap_or(0),integer(event,"dz").unwrap_or(0));for iy in 0..counts.1{for iz in 0..counts.2{for ix in 0..counts.0{let copy_origin=BlockVector3::at(origin.x.saturating_add(ix.saturating_mul(step.0)),origin.y.saturating_add(iy.saturating_mul(step.1)),origin.z.saturating_add(iz.saturating_mul(step.2)));clip.for_each(|x,y,z,state|{let(px,py,pz)=(copy_origin.x+x,copy_origin.y+y,copy_origin.z+z);let accepted=match action{"pastenonair"=>!registry.is_air_like(state),"pasteair"=>registry.is_air_like(world.get_block(px,py,pz)),"pastemasked"=>destination_mask.as_ref().is_some_and(|m|m.test(&world,&registry,px,py,pz)),_=>true};if accepted{placements.push((px,py,pz,state));}});}}}
    let result={
        let mut manager=lock_sessions(sessions);let mut local=manager.get(key).clone();
        let mut edit=EditSession::open(&mut world,&mut registry,&mut local,"//paste",true);
        for(x,y,z,state)in placements{edit.set_block(x,y,z,state);}
        let result=edit.finish(&mut local,Some(&mut manager.edit_log));*manager.get(key)=local;result
    };
    match result {Ok(n)=>feedback(host,&format!("fawebim paste: {n} block(s) pasted")),Err(e)=>feedback(host,&format!("fawebim paste: {e}"))};
    Flow::Cancel
}

fn inspect_clipboard(host:&mut dyn ServerHost,event:&RunModCommandEvent<'_>,clipboards:&Mutex<ClipboardManager>,registry_cache:&Arc<Mutex<HorizonRegistryCache>>)->Flow{let action=event.command.literals.get(1).map(String::as_str).unwrap_or("clipboardchecksum");let key=source_key(event.source);let clip={let clips=clipboards.lock().unwrap_or_else(|p|p.into_inner());clips.get(key).cloned()};let Some(clip)=clip else{feedback(host,"fawebim clipboard: clipboard is empty");return Flow::Cancel;};if matches!(action,"clipboardstats"|"clipboardpalette"){let size=clip.size();let capacity=(size.x as usize).saturating_mul(size.y as usize).saturating_mul(size.z as usize);let counts=clip.state_counts();let registry=CachedBlockRegistry::new(registry_cache.clone());let limit=if action=="clipboardpalette"{16}else{8};let states=counts.iter().take(limit).map(|(state,count)|format!("{}={count}",registry.describe(*state))).collect::<Vec<_>>().join(", ");feedback(host,&format!("fawebim {action}: blocks={}, holes={}, unique={}, size={}x{}x{}, states=[{states}]",clip.volume(),capacity.saturating_sub(clip.volume()),counts.len(),size.x,size.y,size.z));return Flow::Cancel;}if action=="clipboardchecksum"{feedback(host,&format!("fawebim clipboardchecksum: {:016x} over {} block(s)",clip.checksum(),clip.volume()));return Flow::Cancel;}if action=="clipboardcount"{let text=match event.command.argument("block"){Some(CommandArgumentValue::String(s))=>s.trim(),_=>""};let state={let reg=HorizonBlockRegistry::new(host,registry_cache.clone());reg.parse(text)};if state<0{feedback(host,&format!("fawebim clipboardcount: unknown block '{text}'"));return Flow::Cancel;}feedback(host,&format!("fawebim clipboardcount: {} occurrence(s) of {text}",clip.count_state(state)));return Flow::Cancel;}let message={let dimension=resolve_dimension(host,event);let world=HorizonWorld::new(host,dimension,registry_cache.clone());let origin=clip.origin();let mut equal=0usize;let mut different=0usize;clip.for_each(|x,y,z,state|if world.get_block(origin.x+x,origin.y+y,origin.z+z)==state{equal+=1}else{different+=1});format!("fawebim clipboardcompare: equal={equal}, different={different}, total={}",equal+different)};feedback(host,&message);Flow::Cancel}

fn modify_clipboard(host:&mut dyn ServerHost,event:&RunModCommandEvent<'_>,clipboards:&Mutex<ClipboardManager>,registry_cache:&Arc<Mutex<HorizonRegistryCache>>)->Flow{let action=event.command.literals.get(1).map(String::as_str).unwrap_or("clipboardshift");let key=source_key(event.source);if action=="clipboardreplace"{let from_text=match event.command.argument("from"){Some(CommandArgumentValue::String(s))=>s.trim(),_=>""};let to_text=match event.command.argument("to"){Some(CommandArgumentValue::String(s))=>s.trim(),_=>""};let(from,to)={let reg=HorizonBlockRegistry::new(host,registry_cache.clone());let from=reg.parse(from_text);if from<0{feedback(host,&format!("fawebim clipboardreplace: unknown block '{from_text}'"));return Flow::Cancel;}let to=match Pattern::parse(&reg,to_text){Ok(p)=>p,Err(e)=>{feedback(host,&format!("fawebim clipboardreplace: {e}"));return Flow::Cancel;}};(from,to)};let mut clips=clipboards.lock().unwrap_or_else(|p|p.into_inner());let Some(clip)=clips.get_mut(key)else{drop(clips);feedback(host,"fawebim clipboard: clipboard is empty");return Flow::Cancel;};let changed=clip.replace_state(from,|x,y,z|to.apply(x,y,z));drop(clips);feedback(host,&format!("fawebim clipboardreplace: {changed} block(s) replaced"));return Flow::Cancel;}let message={let mut clips=clipboards.lock().unwrap_or_else(|p|p.into_inner());if let Some(clip)=clips.get_mut(key){match action{"clipboardshift"=>clip.shift_origin(integer(event,"dx").unwrap_or(0),integer(event,"dy").unwrap_or(0),integer(event,"dz").unwrap_or(0)),"clipboardscale"=>{let(x,y,z)=(integer(event,"x").unwrap_or(1),integer(event,"y").unwrap_or(1),integer(event,"z").unwrap_or(1));if !(1..=16).contains(&x)||!(1..=16).contains(&y)||!(1..=16).contains(&z){return{feedback(host,"fawebim clipboardscale: factors must be 1..16");Flow::Cancel};}let volume=(clip.size().x as u64*x as u64)*(clip.size().y as u64*y as u64)*(clip.size().z as u64*z as u64);if volume>CuboidRegion::MAX_VOLUME{return{feedback(host,"fawebim clipboardscale: result exceeds the block limit");Flow::Cancel};}clip.scale(x,y,z)},"clipboardtrim"=>{let registry=CachedBlockRegistry::new(registry_cache.clone());clip.trim(|state|!registry.is_air_like(state));},_=>{}}let size=clip.size();let origin=clip.origin();format!("fawebim {action}: {} block(s), {}x{}x{}, origin=({}, {}, {})",clip.volume(),size.x,size.y,size.z,origin.x,origin.y,origin.z)}else{"fawebim clipboard: clipboard is empty".into()}};feedback(host,&message);Flow::Cancel}

fn transform_clipboard(host:&mut dyn ServerHost,event:&RunModCommandEvent<'_>,clipboards:&Mutex<ClipboardManager>,registry_cache:&Arc<Mutex<HorizonRegistryCache>>,rotate:bool)->Flow{
    let key=source_key(event.source);let mut clips=clipboards.lock().unwrap_or_else(|p|p.into_inner());
    let Some(clip)=clips.get_mut(key) else {drop(clips);feedback(host,"fawebim clipboard: clipboard is empty");return Flow::Cancel;};
    let transform=if rotate { let degrees=integer(event,"degrees").unwrap_or(0);if degrees%90!=0{drop(clips);feedback(host,"fawebim rotate: degrees must be a multiple of 90");return Flow::Cancel;}let axis=match event.command.argument("axis"){Some(CommandArgumentValue::String(s))=>s.chars().next().unwrap_or('y'),_=>'y'};if !matches!(axis,'x'|'y'|'z'){drop(clips);feedback(host,"fawebim rotateaxis: axis must be x, y, or z");return Flow::Cancel;}clip.rotate_axis(axis,degrees/90);Transform::rotate(BlockVector3::ZERO,match axis{'x'=>Axis::X,'z'=>Axis::Z,_=>Axis::Y},degrees as f64) }
    else { let axis=match event.command.argument("axis"){Some(CommandArgumentValue::String(s))=>s.chars().next().unwrap_or('y'),_=>'y'};if !matches!(axis,'x'|'y'|'z'){drop(clips);feedback(host,"fawebim flip: axis must be x, y, or z");return Flow::Cancel;}clip.flip(axis);Transform::flip(BlockVector3::ZERO,match axis{'x'=>Axis::X,'z'=>Axis::Z,_=>Axis::Y}) };
    if let Some(mut state_transform)=BlockStateTransform::of(&transform){let mut registry=CachedBlockRegistry::new(registry_cache.clone());clip.map_states(|state|state_transform.apply(&mut registry,state as u32)as i32);}
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

fn selection_shape(host:&mut dyn ServerHost,event:&RunModCommandEvent<'_>,sessions:&Mutex<SessionManager>)->Flow{let action=event.command.literals.get(1).map(String::as_str).unwrap_or("selsphere");let key=source_key(event.source);let x=integer(event,"x").unwrap_or(0);let z=integer(event,"z").unwrap_or(0);let shape=if matches!(action,"selconvex"|"selpolyhedron"){let text=match event.command.argument("points"){Some(CommandArgumentValue::String(s))=>s.trim(),_=>""};let mut hull=ConvexPolyhedralRegion::new();for item in text.split(';').filter(|s|!s.trim().is_empty()){let values=item.split(',').map(|v|v.trim().parse::<i32>()).collect::<Result<Vec<_>,_>>();let values=match values{Ok(v)if v.len()==3=>v,_=>{feedback(host,&format!("fawebim {action}: invalid point '{item}'"));return Flow::Cancel;}};if let Err(e)=hull.add_vertex(BlockVector3::at(values[0],values[1],values[2])){feedback(host,&format!("fawebim {action}: {e}"));return Flow::Cancel;}}if !hull.is_defined(){feedback(host,&format!("fawebim {action}: at least four non-coplanar points are required"));return Flow::Cancel;}RegionShape::Convex(hull)}else if action=="selbox"{RegionShape::Cuboid(CuboidRegion::new(BlockVector3::at(integer(event,"x1").unwrap_or(0),integer(event,"y1").unwrap_or(0),integer(event,"z1").unwrap_or(0)),BlockVector3::at(integer(event,"x2").unwrap_or(0),integer(event,"y2").unwrap_or(0),integer(event,"z2").unwrap_or(0))))}else if action=="selpolygon"{let text=match event.command.argument("points"){Some(CommandArgumentValue::String(s))=>s.trim(),_=>""};let points=text.split(';').map(|item|{let(a,b)=item.split_once(',').ok_or_else(||format!("invalid point '{item}'"))?;Ok(BlockVector2::at(a.trim().parse::<i32>().map_err(|_|format!("invalid X in '{item}'"))?,b.trim().parse::<i32>().map_err(|_|format!("invalid Z in '{item}'"))?))}).collect::<Result<Vec<_>,String>>();let points=match points{Ok(p)=>p,Err(e)=>{feedback(host,&format!("fawebim selpolygon: {e}"));return Flow::Cancel;}};match Polygonal2DRegion::new(points,integer(event,"miny").unwrap_or(-64),integer(event,"maxy").unwrap_or(319)){Ok(r)=>RegionShape::Polygon(r),Err(e)=>{feedback(host,&format!("fawebim selpolygon: {e}"));return Flow::Cancel;}}}else if action=="selcylinder"{let(rx,rz)=(number(event,"rx").unwrap_or(0.0),number(event,"rz").unwrap_or(0.0));if !rx.is_finite()||!rz.is_finite()||rx<=0.0||rz<=0.0{feedback(host,"fawebim selcylinder: radii must be finite and positive");return Flow::Cancel;}RegionShape::Cylinder(CylinderRegion::new(Vector2::new(x as f64,z as f64),rx,rz,integer(event,"miny").unwrap_or(-64),integer(event,"maxy").unwrap_or(319)))}else{let y=integer(event,"y").unwrap_or(0);let(rx,ry,rz)=if action=="selsphere"{let r=number(event,"radius").unwrap_or(0.0);(r,r,r)}else{(number(event,"rx").unwrap_or(0.0),number(event,"ry").unwrap_or(0.0),number(event,"rz").unwrap_or(0.0))};if !rx.is_finite()||!ry.is_finite()||!rz.is_finite()||rx<=0.0||ry<=0.0||rz<=0.0{feedback(host,&format!("fawebim {action}: radii must be finite and positive"));return Flow::Cancel;}RegionShape::Ellipsoid(EllipsoidRegion::new(Vector3::new(x as f64,y as f64,z as f64),Vector3::new(rx,ry,rz)))};let mut manager=lock_sessions(sessions);let session=manager.get(key);session.selection.selector_mut().learn_region(shape);feedback(host,&format!("fawebim {action}: {}",session.selection.describe()));Flow::Cancel}

fn selection_trim(host:&mut dyn ServerHost,event:&RunModCommandEvent<'_>,sessions:&Mutex<SessionManager>,registry_cache:&Arc<Mutex<HorizonRegistryCache>>)->Flow{let key=source_key(event.source);let mut region={let manager=lock_sessions(sessions);manager.peek(key).and_then(|s|s.selection.region())};let Some(ref mut region)=region else{feedback(host,"fawebim selectiontrim: no selection");return Flow::Cancel;};let text=match event.command.argument("mask"){Some(CommandArgumentValue::String(s))=>s.trim(),_=>"#existing"};let mask={let reg=HorizonBlockRegistry::new(host,registry_cache.clone());match Mask::parse(&reg,text){Ok(m)=>m,Err(e)=>{feedback(host,&format!("fawebim selectiontrim: {e}"));return Flow::Cancel;}}};let dimension=resolve_dimension(host,event);let world=HorizonWorld::new(host,dimension,registry_cache.clone());let registry=CachedBlockRegistry::new(registry_cache.clone());let mut lo=BlockVector3::at(i32::MAX,i32::MAX,i32::MAX);let mut hi=BlockVector3::at(i32::MIN,i32::MIN,i32::MIN);region.for_each_position(|x,y,z|if mask.test(&world,&registry,x,y,z){lo.x=lo.x.min(x);lo.y=lo.y.min(y);lo.z=lo.z.min(z);hi.x=hi.x.max(x);hi.y=hi.y.max(y);hi.z=hi.z.max(z);});drop(world);if lo.x==i32::MAX{feedback(host,"fawebim selectiontrim: no matching blocks");return Flow::Cancel;}let mut manager=lock_sessions(sessions);let session=manager.get(key);session.selection.selector_mut().learn_region(RegionShape::Cuboid(CuboidRegion::new(lo,hi)));feedback(host,&format!("fawebim selectiontrim: {}",session.selection.describe()));Flow::Cancel}

fn inspect_command(host:&mut dyn ServerHost,event:&RunModCommandEvent<'_>,registry_cache:&Arc<Mutex<HorizonRegistryCache>>)->Flow{
    let action=event.command.literals.get(1).map(String::as_str).unwrap_or("inspect");let x=integer(event,"x").unwrap_or(0);let z=integer(event,"z").unwrap_or(0);let dimension=resolve_dimension(host,event);
    let message={let world=HorizonWorld::new(host,dimension,registry_cache.clone());let registry=CachedBlockRegistry::new(registry_cache.clone());
        if action=="inspect"{let y=integer(event,"y").unwrap_or(0);let state=world.get_block(x,y,z);format!("fawebim inspect: ({x}, {y}, {z}) state={state}, block={}, biome={}",registry.name(state),registry.biome_name(world.get_biome(x,y,z)))}
        else{let miny=integer(event,"miny").unwrap_or(-64);let maxy=integer(event,"maxy").unwrap_or(319);let(lo,hi)=(miny.min(maxy),miny.max(maxy));if action=="surfaceinfo"{if let Some(y)=(lo..=hi).rev().find(|&y|!registry.is_air_like(world.get_block(x,y,z))){let state=world.get_block(x,y,z);format!("fawebim surfaceinfo: ({x}, {y}, {z}) {}, biome={}",registry.name(state),registry.biome_name(world.get_biome(x,y,z)))}else{format!("fawebim surfaceinfo: no surface at ({x}, {z}) in y={lo}..{hi}")}}else{let mut runs=Vec::<(i32,i32,i32)>::new();let mut start=lo;let mut previous=world.get_block(x,lo,z);for y in lo+1..=hi{let state=world.get_block(x,y,z);if state!=previous{runs.push((start,y-1,previous));start=y;previous=state;}}runs.push((start,hi,previous));let shown=runs.iter().rev().take(16).map(|(a,b,s)|format!("{a}..{b}:{}",registry.name(*s))).collect::<Vec<_>>().join(", ");format!("fawebim columninfo: {} run(s), top-down {shown}",runs.len())}}
    };feedback(host,&message);Flow::Cancel
}

fn chunk_command(host:&mut dyn ServerHost,event:&RunModCommandEvent<'_>,sessions:&Mutex<SessionManager>)->Flow{
    let action=canonical_command(event.command.literals.get(1).map(String::as_str).unwrap_or("chunklist"));let key=source_key(event.source);let mut manager=lock_sessions(sessions);let session=manager.get(key);
    if action=="chunkselect"{let cx=integer(event,"x").unwrap_or(0);let cz=integer(event,"z").unwrap_or(0);let miny=integer(event,"miny").unwrap_or(-64);let maxy=integer(event,"maxy").unwrap_or(319);let region=RegionShape::Cuboid(CuboidRegion::new(BlockVector3::at(cx.saturating_mul(16),miny,cz.saturating_mul(16)),BlockVector3::at(cx.saturating_mul(16).saturating_add(15),maxy,cz.saturating_mul(16).saturating_add(15))));session.selection.selector_mut().learn_region(region);feedback(host,&format!("fawebim chunkselect: chunk ({cx}, {cz}), y={miny}..{maxy}"));return Flow::Cancel;}
    let Some(region)=session.selection.region()else{feedback(host,&format!("fawebim {action}: no selection"));return Flow::Cancel;};let min=region.min();let max=region.max();let(mincx,maxcx,mincz,maxcz)=(min.x>>4,max.x>>4,min.z>>4,max.z>>4);
    if action=="selectioncorners"{feedback(host,&format!("fawebim selectioncorners: min=({}, {}, {}), max=({}, {}, {}), center=({:.1}, {:.1}, {:.1})",min.x,min.y,min.z,max.x,max.y,max.z,(min.x as f64+max.x as f64)/2.0,(min.y as f64+max.y as f64)/2.0,(min.z as f64+max.z as f64)/2.0));return Flow::Cancel;}if action=="selectionchunks"{feedback(host,&format!("fawebim selectionchunks: x={}..{}, z={}..{}, total={}",mincx,maxcx,mincz,maxcz,((maxcx-mincx+1)as u64)*((maxcz-mincz+1)as u64)));return Flow::Cancel;}
    if action=="selectionbounds"||action=="selectionpad"||action=="selectionalign"{let updated=if action=="selectionbounds"{let a=integer(event,"miny").unwrap_or(min.y);let b=integer(event,"maxy").unwrap_or(max.y);RegionShape::Cuboid(CuboidRegion::new(BlockVector3::at(min.x,a.min(b),min.z),BlockVector3::at(max.x,a.max(b),max.z)))}else if action=="selectionpad"{let(x,y,z)=(integer(event,"x").unwrap_or(0).abs(),integer(event,"y").unwrap_or(0).abs(),integer(event,"z").unwrap_or(0).abs());RegionShape::Cuboid(CuboidRegion::new(BlockVector3::at(min.x.saturating_sub(x),min.y.saturating_sub(y),min.z.saturating_sub(z)),BlockVector3::at(max.x.saturating_add(x),max.y.saturating_add(y),max.z.saturating_add(z))))}else{let grid=integer(event,"grid").unwrap_or(1).clamp(1,4096);let floor=|v:i32|v.div_euclid(grid)*grid;let ceil=|v:i32|v.div_euclid(grid).saturating_add(1).saturating_mul(grid).saturating_sub(1);RegionShape::Cuboid(CuboidRegion::new(BlockVector3::at(floor(min.x),floor(min.y),floor(min.z)),BlockVector3::at(ceil(max.x),ceil(max.y),ceil(max.z))))};session.selection.selector_mut().learn_region(updated);feedback(host,&format!("fawebim {action}: {}",session.selection.describe()));return Flow::Cancel;}
    if action=="chunkexpand"{let radius=integer(event,"radius").unwrap_or(0).clamp(0,1024);let region=RegionShape::Cuboid(CuboidRegion::new(BlockVector3::at((mincx-radius).saturating_mul(16),min.y,(mincz-radius).saturating_mul(16)),BlockVector3::at((maxcx+radius).saturating_mul(16).saturating_add(15),max.y,(maxcz+radius).saturating_mul(16).saturating_add(15))));session.selection.selector_mut().learn_region(region);feedback(host,&format!("fawebim chunkexpand: aligned with {radius} chunk margin — {}",session.selection.describe()));}
    else if action=="selectionarea"{let dx=(max.x-min.x+1)as u64;let dy=(max.y-min.y+1)as u64;let dz=(max.z-min.z+1)as u64;let surface=2u64.saturating_mul(dx.saturating_mul(dy).saturating_add(dx.saturating_mul(dz)).saturating_add(dy.saturating_mul(dz)));feedback(host,&format!("fawebim selectionarea: bounding surface={surface}, footprint={}, chunks={}",dx.saturating_mul(dz),((maxcx-mincx+1)as u64).saturating_mul((maxcz-mincz+1)as u64)));}
    else{let total=((maxcx-mincx+1)as u64).saturating_mul((maxcz-mincz+1)as u64);let mut shown=Vec::new();'outer:for cz in mincz..=maxcz{for cx in mincx..=maxcx{shown.push(format!("{cx},{cz}"));if shown.len()==32{break 'outer;}}}let suffix=if total>32{format!(" … +{}",total-32)}else{String::new()};feedback(host,&format!("fawebim chunklist: {total} chunk(s): {}{suffix}",shown.join(" ")));}Flow::Cancel
}

fn history_config(host:&mut dyn ServerHost,event:&RunModCommandEvent<'_>,sessions:&Mutex<SessionManager>)->Flow{let action=event.command.literals.get(1).map(String::as_str).unwrap_or("history");let key=source_key(event.source);let mut manager=lock_sessions(sessions);let history=&mut manager.get(key).history;if action=="historylimit"{let limit=integer(event,"records").unwrap_or(history.max_records()as i32).clamp(1,10_000)as usize;history.set_max_records(limit);feedback(host,&format!("fawebim historylimit: {limit}"));}else if action=="historylist"{let page=integer(event,"page").unwrap_or(1).max(1)as usize;let start=(page-1)*8;let rows=history.records().iter().enumerate().skip(start).take(8).map(|(i,r)|format!("{}:{} ({} blocks, {} biomes)",i+1,r.description(),r.change_count(),r.biome_change_count())).collect::<Vec<_>>().join("; ");feedback(host,&format!("fawebim historylist page {page}: {}",if rows.is_empty(){"empty"}else{&rows}));}else if action=="historystats"{let bytes: u64=history.records().iter().map(|r|r.estimated_bytes()).sum();let blocks:usize=history.records().iter().map(|r|r.change_count()).sum();let biomes:usize=history.records().iter().map(|r|r.biome_change_count()).sum();feedback(host,&format!("fawebim historystats: records={}, blocks={}, biomes={}, estimatedBytes={bytes}",history.len(),blocks,biomes));}else{feedback(host,&format!("fawebim history: {} record(s), position={}, max={}, canUndo={}, canRedo={}",history.len(),history.current_index()+1,history.max_records(),history.can_undo(),history.can_redo()));}Flow::Cancel}

fn help_command(host:&mut dyn ServerHost,event:&RunModCommandEvent<'_>,registry_cache:&Arc<Mutex<HorizonRegistryCache>>)->Flow{
    let action=canonical_command(event.command.literals.get(1).map(String::as_str).unwrap_or("help"));match action{
        "version"=>feedback(host,&format!("FAWE-ButInMods Horizon v{} — server-side wasm",env!("CARGO_PKG_VERSION"))),
        "commands"=>{let query=match event.command.argument("query"){Some(CommandArgumentValue::String(s))=>s.trim(),_=>""};let found=help::search(query);let text=found.iter().map(|e|format!("{} [{}] — {}",e.command,e.category,e.summary)).collect::<Vec<_>>().join("; ");feedback(host,&format!("fawebim commands: {}",if text.is_empty(){"no matches".into()}else{text}));},
        "patterninfo"=>{let text=match event.command.argument("pattern"){Some(CommandArgumentValue::String(s))=>s.trim(),_=>""};let reg=HorizonBlockRegistry::new(host,registry_cache.clone());match Pattern::parse(&reg,text){Ok(pattern)=>feedback(host,&format!("fawebim patterninfo: valid, deterministic={}, {pattern:?}",pattern.is_deterministic())),Err(e)=>feedback(host,&format!("fawebim patterninfo: invalid — {e}"))}},
        "maskinfo"=>{let text=match event.command.argument("mask"){Some(CommandArgumentValue::String(s))=>s.trim(),_=>""};let reg=HorizonBlockRegistry::new(host,registry_cache.clone());match Mask::parse(&reg,text){Ok(mask)=>feedback(host,&format!("fawebim maskinfo: valid, {mask:?}")),Err(e)=>feedback(host,&format!("fawebim maskinfo: invalid — {e}"))}},
        "masks"=>feedback(host,"fawebim masks: blocks, ##category, #air, #existing, #solid, #liquid, #surface, #biome, #y, #chance, #noise, #expression, #offset, #adjacent, #nearby, #exposed, #sphere, #cylinder, #grid, #stripe, #scale, #rotate, #mirror, #same, #different; combine with ! & |"),
        "patterns"=>feedback(host,"fawebim patterns: weighted lists, #checker, #gradient, #expression, #perlin, #simplex, #voronoi, #ridged, #linear, #radial, #radialgradient, #wave, #offset, #scale, #rotate, #mirror, #quantize, #tile"),
        "biomelist"=>{let query=match event.command.argument("query"){Some(CommandArgumentValue::String(s))=>s.trim().to_ascii_lowercase(),_=>String::new()};let text={let reg=HorizonBlockRegistry::new(host,registry_cache.clone());reg.biome_names().into_iter().filter(|name|query.is_empty()||name.to_ascii_lowercase().contains(&query)).take(50).collect::<Vec<_>>().join(", ")};feedback(host,&format!("fawebim biomelist: {}",if text.is_empty(){"no matches"}else{&text}));},
        _=>{let page=integer(event,"page").unwrap_or(1).max(1)as usize;let(pages,entries)=help::page(page,5);let text=entries.iter().map(|e|format!("{} — {}",e.command,e.summary)).collect::<Vec<_>>().join("; ");feedback(host,&format!("fawebim help {page}/{pages}: {text}"));}
    }Flow::Cancel
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
#[cfg(test)]
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
