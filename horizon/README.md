# horizon/ — FAWE-ButInMods as a Horizon code mod (experimental)

A Horizon **code mod**: a server-side wasm module the game's Horizon runtime
loads, reimplementing FAWE's surface (300 commands, 46 brushes, history) for
Horizon instead of Fabric.

> **Status: six engine milestones plus generation operations in.** The ABI bridge and the build system
> work — a member module (`fawebim.wasm`) builds with a statically-linked
> std and passes native tests plus an ABI/behavioural smoke test — and four
> engine slices are ported:
>
> 1. the **expression engine** (`src/expression.rs`, a port of
>    `Expression.java`) and the **noise functions** behind it (`src/noise.rs`,
>    the libnoise subset WorldEdit exposes: perlin, simplex, voronoi, ridged
>    multi), backing `/fawebim calc <formula:text>` and
>    `/fawebim calc at <x:number> <y:number> <z:number> <formula:text>`
>    (`src/calc.rs`);
> 2. **math, transforms, full region geometry, and all 9 selectors**
>    (`src/math.rs`, `src/transform.rs`, `src/region.rs`, `src/selection.rs`),
>    porting vectors, `BlockBox`, `KochanekBartels` splines, `Direction` +
>    player-relative `Directions`, geometric + block-state transforms, packed
>    26/12/26-bit `block_keys`, all 7 region shapes (`Cuboid`, `Cylinder`,
>    `Ellipsoid`, `Polygonal2D`, `ConvexPolyhedral`, `Polyhedral`, `Fuzzy`),
>    and all 9 `//sel` modes (`cuboid`, `extend`, `poly`, `ellipsoid`,
>    `sphere`, `cyl`, `convex`, `polyhedral`, `fuzzy`);
> 3. **selection & bulk `//set` across all shapes** (`src/set.rs`), backing
>    `/fawebim pos1`, `/fawebim pos2`, `/fawebim sel [mode]` and `/fawebim set`
>    via Horizon's bulk `edit_blocks` column-run path;
> 4. **registries, worlds, edit sessions and history** (`src/registry.rs`,
>    `src/world.rs`, `src/session.rs`, `src/history.rs`): lazy Horizon state
>    interning, packed chunk buffers, change limits, side effects, transforms,
>    bulk flushes and `/fawebim undo`, `redo`, and `clearhistory`;
> 5. **patterns and masks** (`src/pattern.rs`, `src/mask.rs`): weighted,
>    checker, Perlin/Simplex/Voronoi/ridged, offset and axis patterns, boolean
>    block/material/height masks, pattern-enabled `set`, and mask/pattern
>    `/fawebim replace`;
> 6. **in-memory clipboards** (`src/clipboard.rs`): selection capture, cut with
>    replacement patterns, paste with history, quarter-turn rotation, axis
>    flips, and per-player clipboard lifecycle commands;
> 7. **geometric generation** (`src/operations.rs`): solid/hollow spheres,
>    cylinders and pyramids plus thick 3D lines, all pattern-aware, buffered,
>    history-recorded and subject to session change limits;
> 8. **region operations and the complete 46-brush catalogue**
>    (`src/brush.rs`): walls, faces, overlays, hollowing, move/stack, every
>    upstream brush name and alias, per-player settings and pattern-aware brush
>    execution through the same buffered history pipeline;
> 9. **persistent schematics** (`src/schematic.rs`): portable palette-based
>    binary encoding, strict size/corruption validation, restart-safe block
>    names and atomic Horizon data-file save/load/list commands;
> 10. **terrain morphology** (`src/morphology.rs`): bounded iterative erosion,
>     dilation and majority smoothing with configurable face thresholds,
>     selection commands and undoable bulk output;
> 11. **selection inspection**: mask-aware block counting, sorted block
>     distributions, exact region dimensions/bounds and geometric centers;
> 12. **selection mutation**: shape-aware expand, contract, shift, inset and
>     outset operations that teach the active selector its updated geometry;
> 13. **session-wide filters**: persistent per-player global masks and global
>     patterns, validated against the live registry and composed into `set` and
>     `replace` without bypassing history or change limits;
> 14. **biome editing and inspection**: registry-validated selection biome
>     painting, compact biome history, undo/redo and sorted biome distributions;
> 15. **terrain utilities**: column gravity, natural terrain layering, liquid
>     draining, exposed-dirt greening, snow cover and snow/ice thawing;
> 16. **expression deformation**: `x`, `y` and `z` assignment expressions
>     remap every selected destination to a source coordinate, with buffered,
>     undoable output and bounded expression evaluation;
> 17. **connected and local edits**: bounded six-neighbour flood fill with
>     patterns and spherical mask-based removal around explicit coordinates;
> 18. **37 implemented compatibility commands**: short shape names, region and
>     morphology synonyms, terrain aliases, clipboard/history aliases and
>     familiar paint/replace/move/biome/nearby names all route to the same
>     validated implementations rather than no-op stubs;
> 19. **advanced generation**: solid/hollow cones and tori, repeating layered
>     patterns over arbitrary selections and deterministic bounded 3D scatter;
> 20. **composable session transforms**: inspect/reset plus axis rotation,
>     non-zero scaling, offsets, flips and deterministic random offsets. These
>     compose in order and transform both coordinates and directional states;
> 21. **in-game discovery** (`src/help.rs`): paginated categorized help,
>     searchable commands, version reporting and live pattern/mask validation.
>
> The Java sources in `../core` and `../fabric` are the reference
> implementation.

Layout:

| Path | Purpose |
|---|---|
| `Cargo.toml` | the member crate (`fawebim`, cdylib + rlib) |
| `horizon-mod.toml` | the Horizon mod manifest (`[package.metadata.horizon]` pins the SDK) |
| `src/lib.rs` | module entry: `#[mod_api::horizon_mod]` impl of `Hooks` |
| `src/expression.rs` | the expression engine (port of `Expression.java`) |
| `src/noise.rs` | perlin / simplex / voronoi / ridged-multi + a `JavaRandom` for reference-exact shuffles |
| `src/calc.rs` | FAWE's `//calculate`, with `at <x> <y> <z>` as the port's own variant |
| `src/math.rs` | `BlockVector2/3`, `Vector2/3`, `BlockBox`, `KochanekBartels`, `Direction`, `directions` |
| `src/transform.rs` | `Axis`, `Transform` (`Rotate`, `Flip`, `Scale`, `Offset`, `RandomOffset`, `Chain`), `BlockStateTransform` |
| `src/region.rs` | `block_keys` + all 7 region shapes (`Cuboid`, `Cylinder`, `Ellipsoid`, `Polygonal2D`, `ConvexPolyhedral`, `Polyhedral`, `Fuzzy`) |
| `src/selection.rs` | all 9 `RegionSelector` modes, per-source `Selection` and `SelectionStore` |
| `src/pattern.rs`, `src/mask.rs` | composable pattern/mask parsers used by set, replace, operations and brushes |
| `src/brush.rs` | all 46 FAWE brush names/aliases and per-player brush settings |
| `src/clipboard.rs` | dense selection clipboards with rotate/flip transforms and per-player storage |
| `src/operations.rs` | lines, spheres, cylinders and pyramids with solid/hollow generation |
| `src/morphology.rs` | iterative terrain erosion, dilation and smoothing |
| `src/registry.rs` | lazy Horizon block/biome/item registry bridge and in-memory test registry |
| `src/world.rs` | packed block arrays, chunk buffers, extents, and Horizon bulk world writes |
| `src/help.rs` | categorized, searchable in-game command reference |
| `src/history.rs` | compact block/biome/entity change sets and undo/redo history |
| `src/schematic.rs` | portable palette-based clipboard codec for persistent Horizon data files |
| `src/session.rs` | local sessions, limits, transforms, side effects, edit buffering and history replay |
| `src/set.rs` | selections, `set`, `undo`, `redo`, and `clearhistory` commands |
| `Cargo.lock` | committed on purpose; pins the member's resolution |

Build and verification: see `../docs/wasm-build.md` (two scripts to fetch the
SDK and the vendored crates, one command to build, one to check). The wasm
ABI is Horizon's wasm-group ABI: dylink.0 + `horizon::host`/`host_reply`,
three exports (`horizon_layout`, `horizon_in`, `horizon_hook`).

Host-side unit tests: `cd horizon && PATH=$PWD/../.tools/rust/bin:$PATH cargo test`
(the parser keeps the reference's nesting limit; the deep-nesting test spawns
a 32 MiB thread so the default 8 MiB test stack is not at risk).

Status and next steps are tracked in `../docs/wasm-build.md#known-limits`. In
short: the expression engine and `//calc` are in; packaging
(`cargo horizon-mod pack`) and CI coverage are unwired on purpose until more
engine milestones land.
