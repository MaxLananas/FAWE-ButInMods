# rustc-literal-escaper — vendored source of truth

Implementation of the `rustc-literal-escaper 0.0.8` public API as required by the
sysroot `proc_macro` crate bundled with the toolchain (`MixedUnit`, `Char`,
`HighByte`, `unescape_byte`, `unescape_char`, `unescape_str`, `unescape_byte_str`,
`unescape_c_str`, the 23-variant `EscapeError`, and `is_fatal()`).

Written for this repository: crates.io and static.crates.io are not reachable
from the build environment, and the upstream crate's repository could not be
located through the GitHub search API. `no_std`, with the `rustc-dep-of-std`
feature wired to the sysroot workspace shims, matching the published crate's
feature shape. The escape semantics follow the Rust reference (simple escapes,
`\xHH`, `\u{...}`, line continuations, c-string NUL rejection, lone-surrogate
and out-of-range rejection); `unescape_*` callbacks report per-unit spans the
way the sysroot `proc_macro` expects.

`tools/fetch-vendor.py` copies this directory into `.vendor/rustc-literal-escaper@0.0.8`.
