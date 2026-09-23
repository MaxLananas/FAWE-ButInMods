# FAWE-BIM

**FastAsyncWorldEdit, but in mods** — the complete WorldEdit + FastAsyncWorldEdit command set
running as a standalone **Fabric** mod for **Minecraft 1.21.10**, with no WorldEdit plugin and no
server platform required.

[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-blue.svg)](LICENSE.txt)
[![Minecraft](https://img.shields.io/badge/minecraft-1.21.10-brightgreen.svg)](https://www.minecraft.net/)
[![Fabric](https://img.shields.io/badge/loader-Fabric%200.17.3%2B-dbb69c.svg)](https://fabricmc.net/)
[![Engine tests](https://img.shields.io/badge/engine%20tests-213%20passing-success.svg)](docs/STATUS.md)
[![Commands](https://img.shields.io/badge/commands-304%20registered-informational.svg)](docs/COMMANDS.md)
[![Coverage](https://img.shields.io/badge/WorldEdit%2BFAWE%20names-259%2F259-success.svg)](docs/COMMANDS.md)

---

## What this is

FAWE-BIM ports the WorldEdit 7.3.17 and FastAsyncWorldEdit feature set to a single Fabric mod.
Everything a player expects from FAWE is here and runs in singleplayer as well as on a dedicated
server:

* the full `//` and `/` command namespaces (`//set`, `//copy`, `//paste`, `//brush`, `/tool`,
  `/schem`, `/snapshot`, `/we`, …),
* WorldEdit's selections (cuboid, polygon, ellipsoid, cylinder, convex polyhedron) and the wand,
* masks, patterns and transforms, including the `#`/`%`/`##` parsers and `//gmask`, `//gsmask`,
* clipboards and schematics (Sponge v1/v2/v3, MCEdit `.schematic`, structure `.nbt`),
* brushes and tools with the FAWE extras (`blendball`, `shatter`, `scatter`, `stacker`, `lrbuild`,
  `farwand`, `deltree`, feature and structure placers, …),
* the chunk tools (`/anvil clear|copy|paste|count|countall|distr|replace…|removelayers|trimallair|
  trimallplots|deletebiomechunks|deleteallunvisited|deletealloldregions|remapall`), which read the
  world's region files to decide what qualifies and edit the chunks through the server,
* mouse-wheel bindings (`/tool scroll size|range|mask|pattern|target|targetoffset|clipboard`),
* multi clipboards (`//schem loadall` + a `//paste` that picks one at random),
* CraftScripts (`//cs`, `//.s`) through whatever JSR-223 engine the server has,
* FAWE's engine: palette-packed chunk sections, bulk chunk writes, deferred side effects,
  chunk-level history, operation timeouts and block-change limits.

The engine (`core/`) has **no Minecraft types at all** — it talks to the game through
`BlockStateRegistry` and `World`, which the Fabric adapter (`fabric/`) implements. That is why the
whole editing engine, including its test suite, runs without launching Minecraft.

## Status

| | |
|---|---|
| Engine tests | **213 passing, 0 failing** (`./gradlew :core:selfTest`) |
| Commands registered | **304** |
| Implemented | **250** |
| Aliases of an implemented command | **54** |
| Still to port | **0** |
| WorldEdit + FAWE command names that resolve | **259 / 259** |

The exact list of every command, its aliases, arguments and status is generated from the live
registry into [`docs/COMMANDS.md`](docs/COMMANDS.md) and [`docs/STATUS.md`](docs/STATUS.md); the
machine-readable form is [`docs/commands-spec.json`](docs/commands-spec.json).

Every name WorldEdit 7.3.17 and FastAsyncWorldEdit declare is registered and resolves, with no
stub left in the registry. Two behaviours depend on the platform rather than on the port:

* CraftScripts (`//cs`, `//.s`) run through a JSR-223 engine; modern JVMs ship none, so the mod says
  so instead of pretending the script ran.
* `/anvil` decides what to touch by reading the dimension's region files (read-only) and then edits
  the chunks through the server. Rewriting region files behind a running server is what made FAWE's
  own anvil commands unsafe and is deliberately not done.

## Requirements

| Component | Version |
|---|---|
| Minecraft | 1.21.10 |
| Fabric Loader | 0.17.3 or newer |
| Fabric API | 0.136.0+1.21.10 or newer |
| Java | 21 |

## Installation

1. Install Fabric Loader for Minecraft 1.21.10.
2. Put [Fabric API](https://modrinth.com/mod/fabric-api) and the FAWE-BIM jar in `.minecraft/mods`.
3. Launch the game. FAWE-BIM works in singleplayer and on a Fabric server; the config file is
   written to `config/fawe.yml` on first start.

The jar is produced by `./gradlew build` at `fabric/build/libs/FAWE-BIM-<version>.jar`.

## Usage

Both spellings of every command work, because Minecraft strips one slash from what you type
(`//set` reaches the command registered as `/set`):

```text
//wand                      give yourself the selection wand
//pos1  //pos2              set the corners (or left/right click with the wand)
//set stone                 fill the selection
//replace stone,dirt grass_block
//copy   //paste            clipboard
//schem save house          schematics in ./schematics
//sphere 15 glass           shapes: //sphere, //cyl, //pyramid, //cone, //line, //spline, ...
//brush sphere 5 stone      bind a brush to the held item
/brush savebrush round      save and reload brush presets
//tool tree                 bind a tool to the held item
/tool mask #existing        brush settings: mask, material, range, size, tracemask, transform
//undo  //redo              history
//regen                     regenerate the selected chunks
```

Selection outlines are drawn by the mod itself (server-side particles), so a vanilla client needs no
mods beyond Fabric API.

## Architecture

```text
core/      platform-independent engine: regions, masks, patterns, transforms, EditSession and its
           chunk queue, history, clipboards and schematics, brushes, tools, expressions, and the
           command registry that holds the whole WorldEdit + FAWE command surface.
fabric/    Fabric adapter: mod entry point, Brigadier registration of every command, world access
           (bulk section writes, lighting, entities), the block-state/biome bridge, click and
           interaction callbacks, one mixin and one access widener (as WorldEdit's own adapter does).
docs/      generated documentation (COMMANDS.md, STATUS.md, commands-spec.json) and the reference
           command inventory extracted from WorldEdit 7.3.17 and FastAsyncWorldEdit.
scripts/   generators for the command tables and documentation.
```

Data flow for an edit: command → `EditSession` (mask, transform, history) → per-chunk `ChunkSet`
→ `World.applyChunk` → palette-packed section writes, biome updates, block updates and relighting
on the server thread, in batches. History is recorded per chunk section (parallel `int[]` of
indices / previous / new states), which is what makes `//undo` on millions of blocks cheap.

## Development

Prerequisites: **JDK 21**. Gradle, Loom, Minecraft, the Parchment mappings and Fabric API are
fetched automatically.

```bash
./gradlew :core:selfTest      # engine test suite, no Minecraft required
./gradlew build               # core + Fabric mod
./gradlew :fabric:runClient   # test client
./gradlew :fabric:runServer   # test server
./gradlew :core:genDocs       # regenerate docs/ from the live command registry
python3 scripts/generate_command_tables.py   # after changing the command set
```

The documentation is generated *from the registry*, never written by hand, so it cannot drift from
the implementation. The command tables (`SubCommandTable`, `StubTable`) are generated from
`docs/commands-inventory.json` and the registry dump, which is how the project tracks which
WorldEdit/FAWE commands are ported and which are still missing.

See [CONTRIBUTING.md](CONTRIBUTING.md) for the porting workflow, the code style and how to verify a
change, and [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) for community expectations.

## Credits and licence

FAWE-BIM is licensed **GPL-3.0** (`LICENSE.txt`, the WorldEdit 7.3.17 licence). It is an
independent implementation whose behaviour is derived from the GPL-3.0 projects
[WorldEdit](https://github.com/EngineHub/WorldEdit) (7.3.17, the Minecraft 1.21.10 release) and
[FastAsyncWorldEdit](https://github.com/IntellectualSites/FastAsyncWorldEdit). Command names,
aliases, flags, parser ids, brush and tool semantics and default configuration values come from
those projects; see [NOTICE](NOTICE) for details.

Author and maintainer: **MaxLananas**.
