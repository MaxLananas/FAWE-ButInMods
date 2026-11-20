# Building the Horizon member module (fawebim.wasm)

This repository ships the FAWE-ButInMods engine in two runtimes: the Java/Fabric
mod (see the README) and **horizon/**, the same feature surface as a **Horizon
code mod** — a server-side wasm component loaded by the game's Horizon runtime.
`horizon/` is at an early skeleton stage: the ABI bridge, the build, and the
tooling are in place; the engine port itself has not started. This document
describes how the member module is built, from a clean checkout, with no network
access to crates.io.

## TL;DR

```sh
sh tools/setup-wasm-toolchain.sh   # Rust 1.99.0 via npm + sparse rust-src for -Zbuild-std
sh tools/fetch-sdk.sh              # the pinned Horizon SDK into .horizon/ (git-ignored)
python3 tools/fetch-vendor.py      # every crate the build needs into .vendor/ (git-ignored)
cd horizon && \
RUSTC_BOOTSTRAP=1 \
CARGO_TARGET_X86_64_UNKNOWN_LINUX_GNU_LINKER=$PWD/../tools/cc-wrapper.sh \
CARGO_TARGET_WASM32_UNKNOWN_UNKNOWN_LINKER=$PWD/../tools/wasm-link.py \
RUSTFLAGS="-Crelocation-model=pic -Zshare-generics=n -Zdefault-visibility=hidden --cfg=horizon_shared_artifacts_v3" \
CARGO_TARGET_DIR=$PWD/../target/horizon-mod/member CARGO_INCREMENTAL=0 \
cargo build --lib --release --target wasm32-unknown-unknown -Zbuild-std=std,panic_abort
cd .. && python3 tools/check-wasm.py
```

The artifact is `target/horizon-mod/member/wasm32-unknown-unknown/release/fawebim.wasm`
(≈4.6 MB). `tools/check-wasm.py` verifies the Horizon wasm-group ABI with
`wasmtime` (`pip install wasmtime`): dylink.0 imports, the `horizon::host` /
`horizon::host_reply` bridge, the three guest exports, instantiation against a
stub linker, a stable `horizon_layout()` hash — and the mod's behaviour
through the same entry points the game calls: it decodes the subscriptions the
guest reports, the commands `register_commands` registers, then dispatches
`/fawebim calc 1+2*3` through `run_mod_command` and checks the feedback the
mod sends back carries `fawebim calc: 1+2*3 = 7`. The stub host answers the
one import the mod uses (`Commands::feedback`, import id 1133) with canned
success bytes and traps loudly on any other id. The payloads are encoded by
hand, mirroring the SDK's wire rules (strings are u32 length + bytes, derived
enums are u32 tags, `Result` and `Option` are u8 tags, `Flow` is a u32 tag);
there is no SDK crate linked on the check side, and the guest's
`wire_guest_generated.rs` is the reference when the SDK pin moves.

To type-check without linking (faster), use the same target with
`-Zbuild-std=std,panic_abort` (not `std` alone — `panic_abort` must be a build
member or the prebuilt-archive lookup fails) and the host linker wrapper:

```sh
cd horizon && PATH=$PWD/../.tools/rust/bin:$PATH \
RUSTC_BOOTSTRAP=1 \
CARGO_TARGET_X86_64_UNKNOWN_LINUX_GNU_LINKER=$PWD/../tools/cc-wrapper.sh \
CARGO_TARGET_DIR=$PWD/../target/horizon-mod/check \
cargo check --lib --target wasm32-unknown-unknown -Zbuild-std=std,panic_abort
```

## Why not `cargo horizon-mod build`

The SDK carries the official tool (`cargo horizon-mod build --wasm`), which is
the reference implementation for packing a mod — including the member-group
link, the `horizon_group_state` synthesis and the `.horizon-mod` package. It is
pinned to a newer Rust than this environment can install, and crates.io is not
reachable from it. The build above reproduces exactly what that tool does for a
single member with no mod dependencies, with a pinned npm-distributed Rust
**1.99.0** (`tools/toolchain/`) plus `-Zbuild-std` for std built from source.
When the pack step (`horizon pack`) becomes available it should drive this
build rather than duplicate it; the layout hash is part of the packed ABI, so
packaging must use the same toolchain.

## Layout and provenance

| Path | Purpose |
|---|---|
| `horizon/` | the member crate (`fawebim`); `horizon-mod.toml` is the mod manifest |
| `tools/setup-wasm-toolchain.sh` | installs `.tools/`: npm-pinned Rust 1.99.0 (+ cargo, std for host and wasm32), a sparse `library/` checkout of `rust-lang/rust` at the compiler's own commit, and its `library/backtrace` submodule; hides the prebuilt wasm32 std rlibs (see below) |
| `tools/toolchain/` | committed `package.json` / `package-lock.json` pinning the four `@toolchain-bin/*` crates |
| `tools/sysroot-library-Cargo.lock` | the `library/Cargo.lock` the sysroot is provisioned with (the npm package ships none; `-Zbuild-std` needs one) |
| `tools/fetch-sdk.sh` | unpacks the pinned Horizon SDK (`sdk-dd67b76…`) into `.horizon/sdk/api` |
| `tools/fetch-vendor.py` | fills `.vendor/`: the SDK dependency closure, the std closure, and the directory-source `.cargo-checksum.json` files matching both lockfiles; also generates `.cargo/config.toml` |
| `tools/shims/` | hand-written vendored crates (see their READMEs): `adler2` (RFC 1950, verified against zlib) and `rustc-literal-escaper` (the API the sysroot `proc_macro` requires), plus the SDK's shims |
| `tools/wasm-link.py` | the linker for wasm: response-file expansion + `rust-lld --shared -Bsymbolic` and the Horizon export set |
| `tools/cc-wrapper.sh`, `tools/cc_wrapper.py` | drops `-fuse-ld=lld` for the host build (host has no collect2-visible `ld.lld`) |
| `tools/check-wasm.py` | wasmtime smoke check (see above) |

Nothing in `.tools/`, `.horizon/`, `.vendor/`, `.cargo/` or `target/` is
committed — all are reproducible from the scripts above. `horizon/Cargo.lock`
is committed on purpose: it pins the member's resolution.

## Things that will bite you if you skip them

1. **Hide the prebuilt wasm32 std.** The npm `rust-std-1.99.0-wasm32-unknown-unknown`
   package ships prebuilt `.rmeta`/`.rlib` files. `-Zbuild-std` builds std from
   source *in addition*, and rustc then reports duplicate lang items (E0152,
   e.g. `sized`/`panic_impl`). `tools/setup-wasm-toolchain.sh` moves
   `wasm32-unknown-unknown/lib` aside; restore it by renaming back.
2. **`library/Cargo.lock` must match the vendored sources.** Std's lock pins a
   few crates (e.g. `memchr 2.8.3`) whose registry checksums cargo verifies
   against the directory source. `fetch-vendor.py` injects the matching
   `package` values; do not hand-edit `.vendor/*/.cargo-checksum.json`.
3. **Resolution stubs.** The std lockfile names crates the wasm32 build never
   compiles (windows/wasi/sgx/motorola targets, `clap`, `memmap2`, …). They are
   vendored as empty stubs that exist only to satisfy resolution; all are marked
   as never compiled for `wasm32-unknown-unknown`. If the sysroot lockfile
   changes, extend `STD_STUBS` in `tools/fetch-vendor.py`.
4. **`-Zbuild-std=std,panic_abort`** with `panic = "abort"` semantics. Do not
   use `-Zbuild-std` with a prebuilt std in scope (see 1), and keep
   `RUSTC_BOOTSTRAP=1` (or a nightly) — this toolchain is stable.
5. **The wasm link is position-independent shared**: `-Crelocation-model=pic`,
   `-Zshare-generics=n`, `-Zdefault-visibility=hidden`,
   `--cfg=horizon_shared_artifacts_v3`, `--shared -Bsymbolic
   --unresolved-symbols=import-dynamic`, exports limited to
   `horizon_layout`/`horizon_in`/`horizon_hook`.

## The ABI (what the game expects)

Per `.horizon/sdk/api/docs/modding/src/how-to/wasm-mods.md` once the SDK is
unpacked:

- imports: `env::memory`, `env::__indirect_function_table`,
  `env::__stack_pointer`, `env::__memory_base`, `env::__table_base`;
  `GOT.mem::__heap_base`, `GOT.mem::__heap_end`, `GOT.mem::horizon_group_state`
  (synthesized by the group; one state per member group);
  `horizon::host(id, in_ptr, in_len, out_ptr, out_cap) -> u32` and
  `horizon::host_reply(out_ptr)`.
- exports: `horizon_layout() -> u64` (hash of the hook layout — mismatch is
  refused), `horizon_in(len) -> ptr`, `horizon_hook(id) -> u64` (high 32 bits =
  reply pointer, low 32 bits = reply length; `u32::MAX` length = error with a
  wire string at the pointer). Reserved IDs `0xFFFF_FF00..03` (INIT, RELEASE,
  HOST_RELEASE, HOST_LOG).
- one memory/table/std per member group; no atomics/shared memory; no
  `#[global_allocator]`; position-independent code with hidden default
  visibility.

`tools/check-wasm.py` asserts this surface, then drives the guest through
`horizon_in`/`horizon_hook` and reads the replies from its memory. A green run
means the module is loadable, ABI-shaped, and that `/fawebim calc` computes
and feeds back the right value — still not that the whole mod works: only the
expression and noise slice is ported.

## Known limits

- **Not bit-reproducible.** `horizon_layout()` and the module bytes depend on
  build paths and the toolchain; two fresh builds can differ. The official
  `cargo horizon-mod` pipeline handles this for packed mods; we do not yet.
- **Packaging is not wired.** This produces the member module only. The
  `.horizon-mod` package (manifest embedding, group layout) is out of scope here.
- **CI does not run this.** `.github/workflows/build.yml` covers the Java mod;
  the wasm build is verified manually for now.
- **The Rust port is one slice.** The expression engine
  (`horizon/src/expression.rs`, a port of `Expression.java`) and the noise
  functions behind it (`horizon/src/noise.rs`) are ported and unit-tested
  (`cargo test` in `horizon/`, host toolchain). They are exposed as
  `/fawebim calc <formula:text>` and
  `/fawebim calc at <x:number> <y:number> <z:number> <formula:text>`, the
  port of FAWE's `//calculate` (`horizon/src/calc.rs`): same formula grammar,
  same unbound-reads-as-0 variables, same `<input> = <value>` feedback with
  grouped thousands and at most five decimals. The `at` usage is the port's
  own convenience (a selection-would-go hook), not a FAWE usage. The brushes,
  the history and the rest of the ~300 commands are not ported yet.
