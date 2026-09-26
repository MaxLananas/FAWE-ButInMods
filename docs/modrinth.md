# Modrinth page

What to paste into each field of the project page, and why. The description itself starts at
[Description](#description); everything above it is for whoever fills the form.

## Project settings

| Field | Value |
|---|---|
| Title | `FAWE-BIM` (only the name: Modrinth rule 5.2 refuses filler in titles) |
| Summary | `The WorldEdit and FastAsyncWorldEdit command set as a standalone Fabric mod: fast edits, brushes, schematics and nine selection shapes, in singleplayer and on servers.` |
| Categories | Utility, Management |
| Environment | Server: required. Client: optional (only for the settings screen) |
| Loaders / versions | Fabric, Minecraft 1.21.10 |
| License | `GPL-3.0-only` (the same identifier as `fabric.mod.json`) |
| Source | https://github.com/MaxLananas/FAWE-ButInMods |
| Issues | https://github.com/MaxLananas/FAWE-ButInMods/issues |
| Wiki | https://github.com/MaxLananas/FAWE-ButInMods#readme |
| Dependencies (per version) | Fabric API: required. Mod Menu: optional. WorldEdit (Fabric): incompatible, both register the same commands |
| Content disclosures | **Contains derivative content**: WorldEdit (https://github.com/EngineHub/WorldEdit) and FastAsyncWorldEdit (https://github.com/IntellectualSites/FastAsyncWorldEdit); their behaviour is the specification this mod reimplements. **Contains AI-generated content**: required by rule 6.1 when a substantial part of the code or of the page was produced with generative AI |
| Gallery | Real in-game screenshots only, each with a title (rule 6.2 removes AI-generated images) |

## Description

Everything below this line is the description, in the Markdown Modrinth renders.

---

<center>

![Fabric](https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/supported/fabric_vector.svg)
![Requires Fabric API](https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/requires/fabric-api_vector.svg)
[![Source on GitHub](https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/available/github_vector.svg)](https://github.com/MaxLananas/FAWE-ButInMods)

**The WorldEdit and FastAsyncWorldEdit (FAWE) command set, as one Fabric mod for Minecraft 1.21.10.**
Select, fill, replace, copy, paste, stack, sculpt terrain with brushes and load schematics, in
singleplayer or on a dedicated server, with no plugin, no server software and no client mod.

</center>

> **Unofficial project.** FAWE-BIM is not affiliated with, endorsed by or supported by EngineHub
> (WorldEdit) or IntellectualSites (FastAsyncWorldEdit). Please report problems with this mod on
> [its own issue tracker](https://github.com/MaxLananas/FAWE-ButInMods/issues), not to them.

## Why FAWE-BIM

- **The commands you already know.** Every command name WorldEdit 7.3.17 and FAWE declare is
  registered and implemented: `//set`, `//replace`, `//copy`, `//paste`, `//stack`, `//move`,
  `//brush`, `/tool`, `//schem`, `//undo`, `/history`, `/snapshot`, `/anvil` and the rest, with the
  same arguments, switches, masks and patterns.
- **Built for large edits.** Writes go to per-chunk buffers of palette-packed sections and reach the
  world a chunk at a time; history is stored per section in compact arrays, so an undo of a million
  blocks costs megabytes, not a heap full of objects.
- **Works on a vanilla client.** The server does everything. Selections can be outlined with
  particles (`/cui`), and the selection size shows above the hotbar while you pick corners.
- **Singleplayer and servers.** The owner of a singleplayer world can edit even with cheats off; on
  a server, operators can.

## Features

| | |
|---|---|
| **Selections** | Cuboid, extend, polygon, ellipsoid, sphere, cylinder, convex, and FAWE's polyhedral and fuzzy (magic wand). The wand answers each shape the way WorldEdit does, and switching shapes keeps your selection |
| **Region edits** | `//set`, `//replace`, `//walls`, `//faces`, `//overlay`, `//smooth`, `//naturalize`, `//hollow`, `//deform`, `//stack`, `//move`, `//regen` |
| **Generation** | `//sphere`, `//cyl`, `//pyramid`, `//cone`, `//line`, `//curve`, `//generate`, `//ores`, `//caves`, `//forestgen`, `//image`, worldgen features and structures |
| **Brushes** | 46 brushes with FAWE's arguments: sphere, cylinder, smooth, blend, height and cliff, erode and dilate, clipboard, scatter, spline, catenary, stencil, recurse, gravity and more, bound per item and saved as presets |
| **Masks and patterns** | FAWE's syntax: `#existing`, `#surface`, `%`, `!`, `=expression`, angle and offset masks; weighted, noise, gradient, colour and clipboard patterns |
| **Clipboard and schematics** | Sponge v1, v2 and v3 (`.schem`), MCEdit (`.schematic`) and structure (`.nbt`) files; entities, block data and biomes travel with a copy; rotate and flip turn stairs, logs and paintings |
| **Tools** | Super pickaxe, long-range wand, replacer, cycler, flood fill, tree and feature placers, navigation wand, mouse-wheel brush settings |
| **History** | Undo and redo, an edit log with `/history find`, `rollback` and `restore`, and `/snapshot` archives |
| **Safety** | Block-change limits, operation timeouts, `/cancel`, a memory budget for large buffers, and file names that cannot leave the schematics folder |

## Getting started

```text
//wand                      the selection wand: left click, then right click
//set stone                 fill the selection
//replace dirt grass_block  replace inside it
//copy                      copy it, then //paste where you stand
//stack 10 up               repeat it ten times upwards
//brush sphere stone 4      bind a brush to the item in your hand
//schem save castle         save the clipboard to schematics/castle.schem
//undo                      take the last edit back
/cui                        outline the selection with particles
/fawebim                    open the settings screen
```

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft **1.21.10**.
2. Put [Fabric API](https://modrinth.com/mod/fabric-api) and FAWE-BIM in the `mods` folder of the
   game or of the server.
3. Start the game. Settings live in `config/fawebim.yml` and can all be changed in game, with no
   restart.

| Requirement | Version |
|---|---|
| Minecraft | 1.21.10 |
| Fabric Loader | 0.17.3 or newer |
| Fabric API | 0.136.0+1.21.10 or newer |
| Java | 21 |

## Good to know

- **Permissions:** there are no per-command permission nodes yet. Operators (level 2) and the owner of
  a singleplayer world can use every command; other players cannot use any.
- **Do not install WorldEdit alongside it:** both register the same commands.
- **CraftScripts** (`//cs`) need a JavaScript engine that modern Java no longer ships, so the command
  says so instead of running.
- **`/anvil`** reads region files only to decide which chunks qualify; the chunks are then edited
  through the server, never by rewriting the files under it.
- **The particle outline** is drawn by the server: it is exact for every shape, but it is dotted and
  redrawn twice a second rather than rendered like a client mod would.

<details>
<summary><b>Is it as fast as FAWE?</b></summary>

It uses the same ideas: buffered chunk writes, packed sections and history, deferred lighting and
neighbour updates. The project's own benchmark, a world in memory on one thread, measures `//set`
at over 40 million blocks per second; in game, lighting and sending chunks to players add to that.

</details>

<details>
<summary><b>Do players need to install anything?</b></summary>

No. The mod runs on the server; players join with a vanilla client. The client half only adds the
settings screen for the singleplayer host.

</details>

<details>
<summary><b>Can I use my WorldEdit and FAWE schematics?</b></summary>

Yes: Sponge `.schem` files of every version, MCEdit `.schematic` files and vanilla structure `.nbt`
files load with `//schem load`, and `//schem save` writes Sponge v3 by default.

</details>

<details>
<summary><b>Can I include it in a modpack?</b></summary>

Yes. It is licensed under GPL-3.0; a link back to this page is appreciated.

</details>

## Credits

FAWE-BIM reimplements the behaviour of two GPL-3.0 projects and would not exist without them:
[WorldEdit](https://github.com/EngineHub/WorldEdit) by sk89q, EngineHub and contributors, and
[FastAsyncWorldEdit](https://github.com/IntellectualSites/FastAsyncWorldEdit) by IntellectualSites
and contributors. Their command names, syntax and semantics are the specification this mod follows.
FAWE-BIM is free software under the
[GNU GPL v3](https://github.com/MaxLananas/FAWE-ButInMods/blob/main/LICENSE.txt); its source,
issue tracker and contribution guide are on
[GitHub](https://github.com/MaxLananas/FAWE-ButInMods).
