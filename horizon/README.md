# horizon/ — FAWE-ButInMods as a Horizon code mod (experimental)

A Horizon **code mod**: a server-side wasm module the game's Horizon runtime
loads, reimplementing FAWE's surface (300 commands, 46 brushes, history) for
Horizon instead of Fabric.

> [!WARNING]
> **This is a skeleton.** The ABI bridge and the build system work — a member
> module (`fawebim.wasm`) builds with a statically-linked std and passes an
> ABI smoke test — but no engine, commands, or brushes have been ported to
> Rust yet. The Java sources in `../core` and `../fabric` are the reference
> implementation and remain the only runnable form of the mod.

Layout:

| Path | Purpose |
|---|---|
| `Cargo.toml` | the member crate (`fawebim`, cdylib + rlib) |
| `horizon-mod.toml` | the Horizon mod manifest (`[package.metadata.horizon]` pins the SDK) |
| `src/lib.rs` | module entry: `#[mod_api::horizon_mod]` impl of `Hooks` |
| `Cargo.lock` | committed on purpose; pins the member's resolution |

Build and verification: see `../docs/wasm-build.md` (two scripts to fetch the
SDK and the vendored crates, one command to build, one to check). The wasm
ABI is Horizon's wasm-group ABI: dylink.0 + `horizon::host`/`host_reply`,
three exports (`horizon_layout`, `horizon_in`, `horizon_hook`).

Status and next steps are tracked in `../docs/wasm-build.md#known-limits`. In
short: the engine port has not started; packaging (`cargo horizon-mod pack`)
and CI coverage are unwired on purpose until the first engine milestone lands.
