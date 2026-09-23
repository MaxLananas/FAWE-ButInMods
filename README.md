<div align="center">

# FAWE-BIM

**FastAsyncWorldEdit — but in mods**

The complete **WorldEdit 7.3.17** + **FastAsyncWorldEdit** command set, running as a standalone
**Fabric** mod for **Minecraft 1.21.10**. No plugin, no server software, no client mod, no
placeholder commands.

[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-blue?style=flat-square&logo=gnu&logoColor=white)](LICENSE.txt)
[![Minecraft 1.21.10](https://img.shields.io/badge/minecraft-1.21.10-62b47a?style=flat-square)](https://www.minecraft.net/)
[![Fabric Loader](https://img.shields.io/badge/fabric%20loader-0.17.3%2B-dbb69c?style=flat-square)](https://fabricmc.net/)
[![Fabric API](https://img.shields.io/badge/fabric%20api-0.136.0%2B1.21.10-dbb69c?style=flat-square)](https://modrinth.com/mod/fabric-api)
[![Java 21](https://img.shields.io/badge/java-21-ed8b00?style=flat-square&logo=openjdk&logoColor=white)](https://adoptium.net/)

[![Build](https://github.com/MaxLananas/FAWE-ButInMods/actions/workflows/build.yml/badge.svg)](https://github.com/MaxLananas/FAWE-ButInMods/actions/workflows/build.yml)
[![Engine tests](https://img.shields.io/badge/engine%20tests-268%20passing-3fb950?style=flat-square)](docs/STATUS.md)
[![Commands](https://img.shields.io/badge/commands-266%20registered-58a6ff?style=flat-square)](docs/COMMANDS.md)
[![Coverage](https://img.shields.io/badge/upstream%20names-255%2F255-3fb950?style=flat-square)](docs/COMMANDS.md)
[![Brushes](https://img.shields.io/badge/brushes-46-8957e5?style=flat-square)](docs/COMMANDS.md)
[![Switches](https://img.shields.io/badge/upstream%20switches-0%20missing-3fb950?style=flat-square)](scripts/flag_audit.py)
[![Stubs](https://img.shields.io/badge/stubs-0-3fb950?style=flat-square)](docs/STATUS.md)

[![Last commit](https://img.shields.io/github/last-commit/MaxLananas/FAWE-ButInMods?style=flat-square&label=last%20commit)](https://github.com/MaxLananas/FAWE-ButInMods/commits)
[![Commit activity](https://img.shields.io/github/commit-activity/m/MaxLananas/FAWE-ButInMods?style=flat-square&label=commits%2Fmonth)](https://github.com/MaxLananas/FAWE-ButInMods/commits)
[![Issues](https://img.shields.io/github/issues/MaxLananas/FAWE-ButInMods?style=flat-square)](https://github.com/MaxLananas/FAWE-ButInMods/issues)
[![Pull requests](https://img.shields.io/badge/PRs-welcome-8957e5?style=flat-square)](CONTRIBUTING.md)
[![Stars](https://img.shields.io/github/stars/MaxLananas/FAWE-ButInMods?style=flat-square)](https://github.com/MaxLananas/FAWE-ButInMods/stargazers)

[Install](#install) · [Commands](docs/COMMANDS.md) · [Status](docs/STATUS.md) ·
[Contributing](CONTRIBUTING.md) · [Code of conduct](CODE_OF_CONDUCT.md)

</div>

---

## Table of contents

| | |
|---|---|
| [What this is](#what-this-is) | What you get, in one screen |
| [Install](#install) | Requirements and setup |
| [Quick tour](#quick-tour) | The commands you will type first |
| [Feature matrix](#feature-matrix) | Everything the mod covers, group by group |
| [How it works](#how-it-works) | The engine, and why it is fast |
| [Status](#status) | Live numbers, always generated from the registry |
| [Known platform limits](#known-platform-limits) | What a mod cannot do that a plugin can |
| [Development](#development) | Build, test, regenerate the docs, continuous integration |
| [Project layout](#project-layout) | Where things live |
| [Credits and licence](#credits-and-licence) | Upstream projects and attribution |

## What this is

FAWE-BIM ports the WorldEdit and FastAsyncWorldEdit feature set to a single Fabric mod. Everything
a WorldEdit player expects is here, and it runs in singleplayer as well as on a dedicated server:

| | |
|---|---|
| **Commands** | The full `//` and `/` namespaces: `//set`, `//copy`, `//paste`, `//brush`, `/tool`, `/schem`, `/snapshot`, `/we`, `/anvil`, and 255 of 255 upstream names |
| **Selections** | Cuboid, polygon, ellipsoid, sphere, cylinder, convex polyhedron, extend, fuzzy — plus the wand and position limits |
| **Masks & patterns** | `#`/`%`/`##` parsers, `//gmask`, `//gsmask`, angle masks, expression masks, biome and block-tag patterns |
| **Clipboards** | Sponge v1/v2/v3, MCEdit `.schematic`, structure `.nbt`; entities, biomes and structure voids survive a copy |
| **Brushes** | 46 brushes with FAWE's arguments and switches, saved as presets, bound per hand |
| **Tools** | Tools, super-pickaxe modes, feature/structure placers, mouse-wheel scroll bindings |
| **Chunk tools** | `/anvil` reads the dimension's region files to decide what qualifies, then edits the chunks through the server |
| **History** | Undo/redo, per-chunk change sets, an edit log behind `/history find|rollback|restore`, and `/snapshot` archives |
| **Engine** | Palette-packed sections, bulk chunk writes, deferred side effects, operation timeouts, block-change limits, worker-pool writes for large schematics |

> [!NOTE]
> The engine (`core/`) has **no Minecraft types at all**. It talks to the game through
> `BlockStateRegistry` and `World`, which the Fabric adapter (`fabric/`) implements — which is why
> the whole editing engine, including its 268-test suite, runs without launching Minecraft.

## Install

| Component | Version |
|---|---|
| Minecraft | **1.21.10** |
| Fabric Loader | 0.17.3 or newer |
| [Fabric API](https://modrinth.com/mod/fabric-api) | 0.136.0+1.21.10 or newer |
| Java | 21 |

1. Install **Fabric Loader** for **Minecraft 1.21.10**.
2. Drop [Fabric API](https://modrinth.com/mod/fabric-api) and the FAWE-BIM jar into `.minecraft/mods`.
3. Launch the game.

The mod works in singleplayer and on a Fabric server. Its configuration is written to
`config/fawebim.yml` on first start. Schematics are read from and written to `schematics/` (the
`general.schematicSaveDirectory` setting) and diagnostics reports to `fawe-reports/`, both relative
to the game directory.

Build the jar yourself with `./gradlew build` — it lands in `fabric/build/libs/FAWE-BIM-<version>.jar`.

## Quick tour

Both spellings of every command work, because Minecraft strips one slash from what you type
(`//set` reaches the command registered as `/set`).

```text
//wand                        give yourself the selection wand
//pos1  //pos2                set the corners (or left/right click with the wand)
//set stone                   fill the selection
//replace stone,dirt grass_block
//copy  //paste -a            clipboard, keeping the blocks the clipboard's air covers
//schem save house -f         schematics in ./schematics
//sphere glass 15             shapes: //sphere, //cyl, //pyramid, //cone, //line, //spline, ...
//brush sphere stone 5        bind a brush to the held item (pattern first, like FAWE)
//brush clipboard -a -m #existing
//tool tree                   bind a tool to the held item
/tool mask #existing         brush settings: mask, material, range, size, tracemask, transform
/tool material -h stone      the same settings for the brush in the offhand
//undo  //redo                history
/history find -u Steve -t 2h the edits of the last two hours, by any player name starting with Steve
//regen                       regenerate the selected chunks
//generatebiome desert abs(x) < 20
```

Selection outlines are drawn by the mod itself with server-side particles, so a vanilla client
needs nothing beyond Fabric API.

## Feature matrix

| Group | Count | Highlights |
|---|---:|---|
| Region | 46 | `//set`, `//replace`, `//walls`, `//faces`, `//hollow`, `//overlay`, `//smooth`, `//distr`, `//count`, `//size`, `//expand`, `//contract`, `//shift` |
| Generation | 24 | `//sphere`, `//cyl`, `//pyramid`, `//cone`, `//line`, `//spline`, `//image`, `//ores`, `//caves`, `//forestgen`, `//generatebiome`, `//feature`, `//structure` |
| Brush | 51 | Sphere, cylinder, smooth, blendball, terrain (`height`, `cliff`, `flatten`, `heightmap`), clipboard, copypaste, catenary, stencil, scatter, spline, gravity, recurse, butcher, … |
| Tool | 24 | `/tool` bindings, super-pickaxe, `farwand`, `lrbuild`, `deltree`, `tree`, `inspect`, scroll actions |
| Clipboard | 14 | `//copy`, `//cut`, `//paste`, `//rotate`, `//flip`, `//stack`, `//move`, `//place`, lazy copy/cut |
| Selection | 19 | `//sel` for six selector types, `//pos1`, `//pos2`, `//hpos1`, `//hpos2`, `//wand`, `//drawsel`, `//chunk` |
| Anvil | 21 | `clear`, `copy`, `paste`, `count`, `countall`, `distr`, `replace*`, `removelayers`, `trimallair`, `trimallplots`, `deletebiomechunks`, `deleteallunvisited`, `deleteunclaimed`, … |
| Navigation | 8 | `/nav`, `/up`, `/ceil`, `/descend`, `/thru`, `/unstuck`, `/jumpto`, `/ascend` |
| Utility | 25 | `/worldedit`, `/we`, `/brush`, `/tool`, `/we report`, `/searchitem`, `/calculate`, `//registry`, `//cancel` |
| History | 4 | `//undo`, `//redo`, `/history list|find|rollback|restore` |
| Snapshot | 6 | `/snapshot list|use|before|after|sel|restore` |
| Chunk | 4 | `//listchunks`, `//delchunks`, `//chunk`, `//regen` |
| Biome | 5 | `//setbiome`, `//biomelist`, `//biomeinfo` |
| Schematic | 2 | `//schem` with `list`, `save`, `load`, `loadall`, `move`, `delete`, `formats` |

The exhaustive list — every command, its aliases, arguments, switches and status — is generated from
the live registry into [`docs/COMMANDS.md`](docs/COMMANDS.md), with the machine-readable form in
[`docs/commands-spec.json`](docs/commands-spec.json).

## How it works

```mermaid
flowchart LR
    A["command line<br/><code>//set stone</code>"] --> B["Ctx<br/>arguments, switches, session"]
    B --> C["pattern / mask<br/>parsers"]
    C --> D["EditSession<br/>mask · transform · history · limits"]
    D --> E["ChunkSet<br/>palette-packed sections"]
    E --> F["World.applyChunk<br/>bulk section writes"]
    F --> G[("Minecraft<br/>world, biomes, entities")]
    D -.-> H["History.Record<br/>int[] of index / before / after"]
    H -.-> I["/undo · /history · /snapshot"]
    D -.-> J["worker pool<br/>large schematic writes"]
```

Three ideas do most of the work:

| Idea | What it buys |
|---|---|
| **Chunk buffering** | Writes land in per-chunk buffers instead of touching the world block by block, then flush in one batch per chunk. |
| **Packed sections** | History and clipboards store a 16³ section as parallel `int[]` arrays plus a palette, so a million-block edit costs megabytes, not a list of boxed objects. |
| **Deferred side effects** | Lighting and neighbour updates are collected per changed position and applied once, on the server thread, after the edit. |

## Status

| | |
|---|---|
| Engine tests | **268 passing, 0 failing** (`./gradlew :core:selfTest`) |
| Commands registered | **266** |
| Implemented | **246** |
| Aliases of an implemented command | **20** |
| Brushes with their upstream signature | **46** |
| Command switches upstream declares but this build lacks | **0** |
| Registered, behaviour still to port | **0** |
| WorldEdit + FAWE command names that resolve | **255 / 255** |

Every name WorldEdit 7.3.17 and FastAsyncWorldEdit declare is registered and resolves, with no stub
left in the registry. `./gradlew :core:verify` runs the self-tests and then feeds the 255 declared
commands and their 201 aliases (457 spellings in total) to the same lookup the dispatcher uses, so a
name cannot quietly stop working. Every command switch upstream declares is declared here too, with
the same kind — `scripts/flag_audit.py` compares the two and currently reports zero missing.

## Known platform limits

A few upstream features need something a standalone Fabric mod does not have. Where that is the
case, the command says so instead of failing silently.

> [!IMPORTANT]
> **CraftScripts** (`//cs`, `//.s`) run through a JSR-223 engine. Modern JVMs ship none, so the
> command reports that no engine is available rather than pretending the script ran.

> [!NOTE]
> **Custom regeneration seeds** (`//regen <seed>`) need a second chunk source. Minecraft builds one
> from the level seed, so the command regenerates with the world seed and tells the player the seed
> was ignored. `-b` (regenerate biomes) works: the adapter keeps the biome grid when it is absent.

> [!NOTE]
> **`/anvil`** reads the dimension's region files (read-only) to decide which chunks qualify, then
> edits those chunks through the server. Rewriting region files behind a running server is what made
> FAWE's own anvil commands unsafe, and is deliberately not done.

> [!NOTE]
> **Claim checks** in `/anvil deleteunclaimed` and `/anvil trimallplots` need a claim provider
> (WorldGuard, PlotSquared, GriefPrevention). FAWE asks one; a mod has none to ask, so the chunk age
> test decides on its own and the report says the claim check was skipped.

> [!NOTE]
> **CUI** (`/cui`) targets FAWE's client mod, which a vanilla client does not run; the command
> reports the state it would advertise.

## Development

Prerequisites: **JDK 21**. Gradle, Loom, Minecraft, the Parchment mappings and Fabric API are
fetched automatically.

```bash
./gradlew :core:selfTest      # engine test suite, no Minecraft required
./gradlew :core:verify        # self-tests + every upstream command name through the dispatcher
./gradlew build               # core + Fabric mod
./gradlew :fabric:runClient   # test client
./gradlew :fabric:runServer   # test server
./gradlew :core:genDocs       # regenerate docs/ from the live command registry
python3 scripts/generate_command_tables.py   # after changing the command set
python3 scripts/flag_audit.py                # compare switches with upstream
```

The documentation is generated *from the registry*, never written by hand, so it cannot drift from
the implementation. The command tables (`SubCommandTable`, `StubTable`, `BrushTable`) are generated
from `docs/commands-inventory.json`, which is itself extracted from the upstream sources, and from
the registry dump — that is how the project tracks which WorldEdit/FAWE commands are ported and
which are still missing.

Continuous integration runs on every push and pull request
([`.github/workflows/build.yml`](.github/workflows/build.yml)): it builds the engine, runs the
self-tests and the command inventory check, builds the mod jar, and fails when the generated
documentation or the generated command tables have drifted from the registry.

See [CONTRIBUTING.md](CONTRIBUTING.md) for the porting workflow, the code style and how to verify a
change, and [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) for community expectations.

## Project layout

```text
core/      platform-independent engine: regions, masks, patterns, transforms, EditSession and its
           chunk queue, history, clipboards and schematics, brushes, tools, expressions, and the
           command registry that holds the whole WorldEdit + FAWE command surface.
fabric/    Fabric adapter: mod entry point, Brigadier registration of every command, world access
           (bulk section writes, lighting, entities), the block-state/biome bridge, click and
           interaction callbacks, one mixin and one access widener, as WorldEdit's own adapter does.
docs/      generated documentation (COMMANDS.md, STATUS.md, commands-spec.json) and the reference
           command inventory extracted from WorldEdit 7.3.17 and FastAsyncWorldEdit.
scripts/   generators for the command tables, the documentation and the upstream flag audit.
```

## Credits and licence

FAWE-BIM is licensed **GPL-3.0** ([`LICENSE.txt`](LICENSE.txt), the WorldEdit 7.3.17 licence).

It is an independent implementation whose behaviour is derived from the GPL-3.0 projects
[WorldEdit](https://github.com/EngineHub/WorldEdit) (7.3.17, the Minecraft 1.21.10 release) and
[FastAsyncWorldEdit](https://github.com/IntellectualSites/FastAsyncWorldEdit). Command names,
aliases, switches, argument order, parsers and messages follow those projects; see
[`NOTICE`](NOTICE) for the full attribution and
[docs/commands-inventory.json](docs/commands-inventory.json) for the extracted upstream surface.

Author and maintainer: **MaxLananas**.
