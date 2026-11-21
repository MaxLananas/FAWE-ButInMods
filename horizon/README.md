# horizon/ — FAWE-ButInMods as a Horizon code mod (experimental)

A Horizon **code mod**: a server-side wasm module the game's Horizon runtime
loads, reimplementing FAWE's surface (300 commands, 46 brushes, history) for
Horizon instead of Fabric.

> **Status: two engine milestones in.** The ABI bridge and the build system
> work — a member module (`fawebim.wasm`) builds with a statically-linked
> std and passes an ABI check plus a behavioural smoke test — and two engine
> slices are ported:
>
> 1. the **expression engine** (`src/expression.rs`, a port of
>    `Expression.java`) and the **noise functions** behind it (`src/noise.rs`,
>    the libnoise subset WorldEdit exposes: perlin, simplex, voronoi, ridged
>    multi), backing `/fawebim calc <formula:text>` and
>    `/fawebim calc at <x:number> <y:number> <z:number> <formula:text>`
>    (`src/calc.rs`);
> 2. **cuboid regions, per-source selections, and bulk `//set`**
>    (`src/region.rs`, `src/selection.rs`, `src/set.rs`), backing
>    `/fawebim pos1`, `/fawebim pos2`, `/fawebim sel` and `/fawebim set` via
>    Horizon's bulk `edit_blocks` column-run path.
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
| `src/region.rs` | `BlockVector3` and `CuboidRegion` (port of `math/BlockVector3.java` + `region/CuboidRegion.java`) |
| `src/selection.rs` | per-source `Selection` and `SelectionStore` |
| `src/set.rs` | `/fawebim pos1`, `pos2`, `sel` and `set` via bulk `edit_blocks` runs |
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
