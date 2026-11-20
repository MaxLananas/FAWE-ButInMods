//! Vendored placeholder for the `wasi` crate.
//!
//! The real crate carries the wasi_snapshot_preview1 bindings and is only ever
//! built for `cfg(wasi)` targets, which this mod never targets. It exists so the
//! locked dependency graph resolves while crates.io is unreachable.
