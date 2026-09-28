# Publishing on Modrinth

Everything needed to put FAWE-BIM on Modrinth: what the rules require, what goes in each field, and
the description itself, ready to paste. Checked on 26 September 2026 against Modrinth's
[Content Rules](https://modrinth.com/legal/rules) (last modified 13 August 2026), its support
articles on [AI usage](https://support.modrinth.com/en/articles/16551575-disclosure-and-usage-of-ai)
and [content disclosures](https://support.modrinth.com/en/articles/16567675-content-disclosures), the
[environment options](https://modrinth.com/news/article/new-environments/), and the source of
Modrinth's API (field limits) and Markdown renderer (what HTML a description may use).

## 1. Read this first: the AI rule

Rule 6 is the one that decides whether this project can be public.

- **6.1** The "Contains AI-generated content" disclosure is mandatory when a substantial part of the
  code, or anything on the page (description, changelogs), comes from generative AI.
- **6.2.2** "Projects may not be published publicly if the contents are primarily or entirely a
  product of AI output." The support article says this targets projects "created purely through
  'prompting and testing' with minimal or no real human-made content", and that "in most cases,
  such projects can still be made unlisted, but will be prohibited from appearing in public search
  results". It names loader ports produced with AI as a concern of its own, and judges a fork on
  the content it adds to its source.
- **6.2.1** No AI-made image anywhere on the page: icon, gallery, description.

The public history of this repository started with a commit by an AI coding agent, and nearly every
commit since is co-authored by one. By Modrinth's definition that is very likely a project whose
contents are primarily AI output, and a moderator can read the same history. Unless a significant
part of it is the maintainer's own work, expect the review to refuse **Public** visibility. The
honest options are:

| Option | What it gives |
|---|---|
| Modrinth, **Unlisted**, with the AI disclosure | A page and a download link to share; no search listing |
| GitHub Releases | Always possible under the GPL; attach the jar to a tag |
| CurseForge | No AI ban today, but it rejects "clones" case by case and asks for a distinct name |
| Write significant parts by hand | The support article allows heavy AI use in some parts when genuine human work makes up a significant portion of the project |

Do not hide the AI use to get through review: rule 6 asks projects to be "forthright and honest"
about it, and rules 2 and 5.9 require the disclosures to be accurate.

## 2. Copyright, licence and credit

The licence side is in order.

- **Licence.** WorldEdit and FAWE are GPL-3.0; FAWE-BIM is `GPL-3.0-only` (`fabric.mod.json`,
  `LICENSE.txt`). The jar carries `META-INF/LICENSE.txt` and `META-INF/NOTICE`.
- **Source.** The GPL asks that whoever receives the jar can get the source of that exact build: tag
  each release (`v1.0.0`) on GitHub, link the repository, and optionally attach a sources jar as an
  "additional file", the one use rule 5.7 names for them.
- **Rule 4 (reuploads and forks).** FAWE-BIM copies no upstream source file (see `NOTICE`); it
  reimplements the commands and their behaviour. Credit is given on the page and in the derivative
  disclosure below.
- **Name (rules 1.3, 1.8, 1.9).** "FAWE" is IntellectualSites' project name, and a title that
  starts with it can read as an official FAWE build. No trademark policy of IntellectualSites or
  EngineHub was found, and FAWE's own `fabric` branch has been idle since 2020, but the safe paths
  are to ask IntellectualSites on [their Discord](https://discord.gg/intellectualsites), or to pick
  a name of your own and keep "WorldEdit and FAWE commands" in the summary, which is descriptive use.
  The "Unofficial project" notice below stays either way.

## 3. Project settings

| Field | Value |
|---|---|
| Title | `FAWE-BIM` (3 to 64 characters; only the name, rule 5.2) |
| Slug | `fawe-bim` (3 to 64 characters) |
| Summary | `The WorldEdit and FastAsyncWorldEdit command set as a standalone Fabric mod: fast edits, brushes, schematics and nine selection shapes, in singleplayer and on servers.` (167 of 255 characters; no formatting, no title, rule 5.3) |
| Featured categories | Utility, Management (the ones WorldEdit and FAWE use; at most three) |
| Environment | **Server-side only, works in singleplayer** (`server_only`). The client half only opens the settings screen for the singleplayer host, which is the case this option already covers; players on a server need nothing |
| Loaders and versions | Fabric; Minecraft 1.21.10 (the only version `fabric.mod.json` accepts) |
| License | `GPL-3.0-only`, URL `https://github.com/MaxLananas/FAWE-ButInMods/blob/main/LICENSE.txt` |
| Source | https://github.com/MaxLananas/FAWE-ButInMods |
| Issues | https://github.com/MaxLananas/FAWE-ButInMods/issues |
| Wiki | https://github.com/MaxLananas/FAWE-ButInMods#readme |
| Discord | https://discord.gg/pnJhKuU2QK (the invite the join banner and `/fawebim-discord` give) |
| Visibility | See section 1 |

## 4. Content disclosures

Settings, then the Disclosures tab.

**Contains AI-generated content: on.** Code: yes. Text: yes (the description and the changelogs were
written with AI). Assets: none, and keep it that way (the icon and screenshots must be real).
Functionality: no, the mod calls no AI at run time.

**Contains derivative content: on.** Modrinth asks for it when a project "uses the work of others" or
needs attribution, and not when a project is only inspired by another. FAWE-BIM takes its command
names, arguments, parser syntax, defaults and semantics from the two projects, and its `NOTICE` calls
that behaviour derived, so this is the careful reading. Entries to paste:

| Work | Link | Note |
|---|---|---|
| WorldEdit | https://github.com/EngineHub/WorldEdit | Reimplements WorldEdit 7.3.17's commands, arguments, masks, patterns and behaviour as a Fabric mod. No WorldEdit source file is copied. WorldEdit is not AI-generated; the AI-assisted work is this project's own. |
| FastAsyncWorldEdit | https://github.com/IntellectualSites/FastAsyncWorldEdit | Reimplements the commands, brushes and buffered editing model FAWE adds, without the plugin. No FAWE source file is copied. FAWE is not AI-generated; the AI-assisted work is this project's own. |

**Telemetry, advertising, external system interactions, photosensitivity: off.** The mod sends
nothing anywhere. Its only network access is `//image <url>`, which downloads the picture a player
asked for.

## 5. Versions

For each release:

1. Build with `./gradlew build`, or take the `FAWE-BIM` artifact of the CI run, and upload
   `FAWE-BIM-<version>.jar` from `fabric/build/libs`.
2. Version number as `mod_version` in `gradle.properties` says, name `FAWE-BIM <version>`. Channel
   **Beta** while the engine is tested head-less more than by players in game.
3. Loader Fabric, game version 1.21.10.
4. Dependencies: Fabric API **required**; Mod Menu **optional**; WorldEdit **incompatible** (both
   register the same commands, and Brigadier merges them into one broken tree).
5. Tag the commit `v<version>` on GitHub, and link the tag in the changelog.

Changelog of 1.1.0:

```markdown
The commands behave as FAWE's in many more places, above all the brushes.

- Brushes and tools are bound per item, as in FAWE: each item keeps its own brush, tool and settings,
  and `/tool secondary` gives the left click a brush of its own
- A click fires the brush on the clicked block, aimed by `/tool range`, `tracemask`, `target` and
  `targetoffset`; `/brush` alone shows what the item in hand holds
- Brushes redone to do what FAWE's do: blendball, erode, pull, morph, dilate, rock, circle, height,
  cliff, splatter, shatter, surface, scatter, scattercommand, command, line, catenary, image, forest,
  feature, structure, snow, biome, raise and lower
- Masks and patterns: `>` `<` `$` `^`, `#offset`, `#existing`, `##tag`, `#spread`, `#surfacespread`,
  the linear and colour patterns, `#typeswap`, `#clipboard`, `#relative`, `#hotbar`; `#simplex` reads
  FAWE's own noise, so the same line places the same blocks as on a FAWE server
- Trees, features and structures grow through the edit: masks, limits and `//undo` apply to them
- `/tool` alone lists the tools instead of unbinding the one in hand; a mistyped brush is named back
- Schematics in folders (`//schem save trees/oak`), FAWE's format names, `//schem move`, `unload`
  and `delete *`
- Tab completion completes the argument being typed; `//help <command>` explains one command
- Counts read "1 block", "2 blocks"; `/we version` names the real Fabric loader and API versions

Source: https://github.com/MaxLananas/FAWE-ButInMods/releases/tag/v1.1.0
```

Changelog of the first version:

```markdown
First release, for Minecraft 1.21.10 on Fabric.

- The WorldEdit 7.3.17 and FAWE command set: 300 commands and aliases, 46 brushes, nine selection shapes
- Sponge v1/v2/v3, MCEdit and structure schematics, exchanged with WorldEdit and FAWE both ways
- Undo, redo, history log and snapshots; block limits, timeouts and /cancel
- Settings screen (/fawebim), vanilla-client particle outline (/cui)

Source: https://github.com/MaxLananas/FAWE-ButInMods/releases/tag/v1.0.0
```

## 6. Icon and gallery

The repository has no icon yet. Modrinth takes a square image (a 512x512 PNG displays well), and it
must not be AI-made (rule 6.2.1): draw it, or crop a real screenshot. Once it exists, put it in
`fabric/src/main/resources/assets/fawebim/icon.png` and add `"icon": "assets/fawebim/icon.png"` to
`fabric.mod.json`, so Mod Menu shows it too.

Gallery ideas, real in-game screenshots with a title each (rule 5.5: nothing the mod cannot do):

1. A selection outlined by `/cui`, with the size shown above the hotbar (featured image)
2. Before and after a sphere and a smooth brush on a hillside
3. A `//stack` of a bridge section, with its answer in chat
4. A schematic pasted with `//paste`, chest contents kept
5. The settings screen (`/fawebim`)
6. `//generate` terrain from a Perlin formula

## 7. Description

Everything below the line is the description. It uses only what Modrinth's renderer keeps:
Markdown, `<center>`, `<details>`/`<summary>` and images. Images from hosts outside Modrinth's
list (here jsDelivr) are served through its image proxy.

---

<center>

[![Available for Fabric](https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/supported/fabric_vector.svg)](https://modrinth.com/mod/fawe-bim/versions)
[![Requires Fabric API](https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/requires/fabric-api_vector.svg)](https://modrinth.com/mod/fabric-api)
[![Source on GitHub](https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/available/github_vector.svg)](https://github.com/MaxLananas/FAWE-ButInMods)
[![Chat on Discord](https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/social/discord-plural_vector.svg)](https://discord.gg/pnJhKuU2QK)

**The WorldEdit and FastAsyncWorldEdit (FAWE) commands, as one Fabric mod for Minecraft 1.21.10.**
Select, fill, replace, copy, paste, stack, sculpt terrain with brushes and load schematics, in
singleplayer or on a dedicated server: no plugin, no server software, no client mod.

</center>

> **Unofficial project.** FAWE-BIM is not affiliated with, endorsed by or supported by EngineHub
> (WorldEdit) or IntellectualSites (FastAsyncWorldEdit). Report problems with this mod on
> [its own issue tracker](https://github.com/MaxLananas/FAWE-ButInMods/issues), not to them.

## Why FAWE-BIM

- **The commands you already know.** Every command name WorldEdit 7.3.17 and FAWE declare is
  registered and does its job: `//set`, `//replace`, `//copy`, `//paste`, `//stack`, `//move`,
  `//brush`, `/tool`, `//schem`, `//undo`, `/history`, `/snapshot`, `/anvil` and the rest, with the
  same arguments, switches, masks, patterns and expressions.
- **Built for large edits.** Writes go to per-chunk buffers of palette-packed sections and reach the
  world a chunk at a time; history is kept per section in compact arrays, so undoing a million blocks
  costs megabytes, not a heap full of objects.
- **Nothing to install for players.** The server does everything and players join with a vanilla
  client. Selections can be outlined with particles (`/cui`), and the selection size shows above the
  hotbar while you pick corners.
- **Singleplayer and servers.** The owner of a singleplayer world can edit even with cheats off; on
  a server, operators can.

## Features

| | |
|---|---|
| **Selections** | Cuboid, extend, polygon, ellipsoid, sphere, cylinder, convex, and FAWE's polyhedral and fuzzy (magic wand); `//sel` turns the current selection into the new shape, as WorldEdit does |
| **Region edits** | `//set`, `//replace`, `//walls`, `//faces`, `//overlay`, `//smooth`, `//naturalize`, `//hollow`, `//deform`, `//stack`, `//move`, `//regen` |
| **Generation** | `//sphere`, `//cyl`, `//pyramid`, `//cone`, `//line`, `//curve`, `//generate` with WorldEdit's `perlin`, `voronoi` and `ridgedmulti`, `//ores`, `//caves`, `//forestgen`, `//image`, `//feature` and `//structure` |
| **Brushes** | 46 brushes with FAWE's arguments: sphere, cylinder, smooth, blend, height and cliff, erode and dilate, clipboard, scatter, spline, catenary, stencil, recurse, gravity and more, bound per item and saved as presets |
| **Masks and patterns** | FAWE's syntax: `#existing`, `#surface`, `%`, `!`, `=expression`, angle and offset masks; weighted, noise, gradient, colour and clipboard patterns |
| **Clipboard and schematics** | Sponge v1, v2 and v3 (`.schem`), MCEdit (`.schematic`) and structure (`.nbt`) files, exchanged with WorldEdit and FAWE both ways; entities, block data and biomes travel with a copy; `//paste -o` puts a build back where it stood; rotate and flip turn stairs, logs, item frames and paintings |
| **Tools** | Super pickaxe, long-range wand, replacer, cycler, flood fill, tree and feature placers, navigation wand, mouse-wheel brush settings |
| **History** | Undo and redo, an edit log with `/history find`, `rollback` and `restore`, and `/snapshot` archives |
| **Safety** | Block-change limits, operation timeouts, `/cancel`, a memory budget for large buffers, and file names that cannot leave the schematics folder |

## Getting started

```text
//wand                      the selection wand: left click, then right click
//set stone                 fill the selection
//replace dirt grass_block  replace inside it
//copy                      copy it, relative to where you stand
//paste                     paste it at the same offset from you
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
3. Start the game. Settings live in `config/fawebim.yml` and can all be changed in game, without a
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
- **The particle outline** is drawn by the server: exact for every shape, but dotted and redrawn
  twice a second rather than rendered like a client mod would.
- **Made with AI assistance:** much of the code and of this page was written with AI coding tools, as
  the project's disclosures say. Every change runs through the engine's test suite (over 1,700
  checks) and the command audits in CI.

<details>
<summary><b>Is it as fast as FAWE?</b></summary>

It uses the same ideas: buffered chunk writes, packed sections and history, deferred lighting and
neighbour updates. The project's own benchmark, a world in memory on one thread, measures `//set`
at over 40 million blocks per second; in game, lighting and sending chunks to players add to that.

</details>

<details>
<summary><b>Do players need to install anything?</b></summary>

No. The mod runs on the server, and players join with a vanilla client. In singleplayer, the same
jar adds the settings screen.

</details>

<details>
<summary><b>Can I use my WorldEdit and FAWE schematics?</b></summary>

Yes. Sponge `.schem` files of every version, MCEdit `.schematic` files and vanilla structure `.nbt`
files load with `//schem load`, and `//schem save` writes Sponge v3 the way WorldEdit and FAWE write
it, so the files paste the same on their servers, biomes included.

</details>

<details>
<summary><b>Can I include it in a modpack?</b></summary>

Yes. It is licensed under GPL-3.0; a link back to this page is appreciated.

</details>

## Reporting a problem

Use the [issue tracker](https://github.com/MaxLananas/FAWE-ButInMods/issues) or the
[Discord server](https://discord.gg/pnJhKuU2QK). Say which command you ran, attach
`logs/latest.log`, and list your other mods.

## Credits

FAWE-BIM reimplements the behaviour of two GPL-3.0 projects and would not exist without them:
[WorldEdit](https://github.com/EngineHub/WorldEdit) by sk89q, EngineHub and contributors, and
[FastAsyncWorldEdit](https://github.com/IntellectualSites/FastAsyncWorldEdit) by IntellectualSites
and contributors. Their command names, syntax and semantics are the specification this mod follows.
FAWE-BIM is free software under the
[GNU GPL v3](https://github.com/MaxLananas/FAWE-ButInMods/blob/main/LICENSE.txt); its source,
issue tracker and contribution guide are on
[GitHub](https://github.com/MaxLananas/FAWE-ButInMods).
