# Changelog

Every release of FAWE-BIM, newest first. The defect ledger behind these entries — cause, fix and file for
each of the **69** defects found so far — is [`BUGS.md`](BUGS.md).

---

## 1.2.0 — Minecraft 26.2

The mod is now built for **Minecraft 26.2**, on Java 25, against Fabric Loader 0.20.1 and Fabric API
0.161.0+26.2, with Loom 1.19 and Gradle 9.8.2. It is not a recompile: 26.1 removed the obfuscation
tables, renamed `ResourceLocation` to `Identifier`, moved NBT onto `ValueInput`/`ValueOutput`, made
`ChunkAccess#getNoiseBiome` the biome path and changed how a ticket keeps a chunk loaded. Every one of
those is handled in the source, not papered over by a stub.

### The port

| Area | What 26.2 changed | What the mod does |
| --- | --- | --- |
| Identifiers | `ResourceLocation` → `Identifier`, `fromNamespaceAndPath` | every registry key, tag key and pattern name |
| NBT | reader/writer on `ValueInput` / `ValueOutput` | `.schem`, `.schematic`, `.nbt`, snapshots, clipboard files |
| Biomes | 4×4×4 palette container per section | `//setbiome`, `//copy -b`, `//regen` write the palette directly |
| Chunks | `TicketType` with flags, light thread | `LightTickets` holds a chunk with light work queued on it |
| Blocks | unobfuscated class files, no mappings | plain `implementation` dependency, no remapping step |
| Worldgen | `ChunkAccess#getNoiseBiome(x>>2, y>>2, z>>2)` | `//regen` reads the generator the way the game does |

Commands: **300** spellings, **253** implemented commands plus **47** aliases, **255/255** upstream
WorldEdit names answered, **146** console-runnable, **46** brushes, **43** configuration settings, and
**1** mixin.

### Engine throughput

Measured on the flat test world, 4 096-block edits unless stated otherwise.

| Operation | Rate |
| --- | --- |
| `//undo` / `//redo` | 78–92 M blocks/s |
| `//copy` | 68–72 M blocks/s |
| writes without history | 63–66 M blocks/s |
| `//set` | 41–43 M blocks/s |
| `//sphere r40` | 32–43 M blocks/s |
| `//paste` | 31–33 M blocks/s |
| `//replace` | 30–33 M blocks/s |
| writes with history | 34–38 M blocks/s |
| mask checks | 262–294 M checks/s |

Hot-path work in this release: palette lookups 487 → 3.7 ns, mask tests 13 → 3.0 ns, buffer fills
75.6 → 54.3 ms, `//hsphere` 2.0 → 0.9 ms, `//walls` 1.1 → 0.52 ms, expression evaluation 260 → 112 ns.

### 69 defects found and fixed

Eight lots, in [`BUGS.md`](BUGS.md). The ones a builder would have felt:

- **`LongObjectMap` lost keys behind a removal.** A linear-probe table emptied the slot of a removed
  key, which hid every key inserted after it: an edit session dropping one chunk buffer made the
  buffers beside it unreachable, and the writes they still held vanished without an error. The hole is
  closed by shifting the cluster back, and a self test compares 100 000 `put`/`get`/`remove` against a
  `HashMap` on 400 keys so the clusters pile up.
- **The configuration did not reload what it had saved.** `MiniYaml` wrote strings raw, and its own
  reader turns `2024` into an integer, `true` into a flag, `[a, b]` into a list and an empty value into
  a section — so a schematic folder named `2024` did not survive a save.
- **A corrupt region header crashed `//anvil`.** A 24-bit sector index times 4096 overflows an `int`;
  the negative offset threw out of the whole scan instead of skipping one chunk.
- **A deeply nested formula took the server thread with it.** `StackOverflowError` is an `Error`, not
  something a command can catch; the expression parser now bounds its nesting at 128 levels.
- **`//undo` of `//setbiome` counted cells it had not touched**, and a negative biome id was recorded in
  the history where no undo could put it back.
- **Text that reached the world as the wrong block**: biome holders are cached per id and per registry
  generation, so a datapack reload can no longer renumber what `//copy -b` writes.
- **Arguments that answered with a parse exception** — `#extrema[a]`, `#surface[abc]`,
  `#buffer[stone][x]`, `~[stone][deux]`, `#xaxis[abc]`, `/anvil deleteallunvisited 99999999999999999999`,
  `//chunk 12,abc` — now name what to correct, and reversed bounds (`#extrema[10][2]`, `{8}{2}`) are
  refused instead of matching nothing for ever.
- **Primitive walks, everywhere they matter**: `splatter`, `shatter`, the layer brush, the flood fills
  and the height maps no longer box a `Long` or build a string per block.

### Build and CI

- A green run compiles, runs the engine self tests (**2 488** assertions), jars the mod, boots a real
  dedicated server with it, drives it over RCON and reads the blocks back — in about **71 s**.
- **Zero CI artifacts**: nothing is uploaded, and a final job deletes anything the repository still
  holds.
- The build is cached on the whole Gradle home, because the build scripts are compiled too.

### Documentation

`README.md` is written for developers: the engine, the measured numbers, the verification table, the
configuration reference, the known platform limits and the migration notes. No artwork, no banner —
the diagrams it once carried are text now.

---

## 1.0.0 — first public build

Region, generation, brush, tool, clipboard, selection, anvil, navigation, utility, history, snapshot,
chunk, biome and schematic commands; the palette/index/packed-long section format; the undo history
with a memory budget; the WorldEdit 7.4 + FastAsyncWorldEdit command surface, with no stubs left in it.
