# adler2 — vendored source of truth

Hand-written, dependency-free, `no_std` implementation of the Adler-32 checksum
(RFC 1950), providing the small `Adler32` incremental API `miniz_oxide`
consumes.

Written for this repository: the build environment had no route to crates.io,
and the upstream crate's repository was not reachable either. The implementation
is byte-for-byte compatible with zlib's `adler32` on every input tested,
including the RFC 1950 example vectors (`""` -> 1, `"Wikipedia"` -> 0x11e60398).

`tools/fetch-vendor.py` copies this directory into `.vendor/adler2@2.0.1`.
