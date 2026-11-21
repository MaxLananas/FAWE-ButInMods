#!/bin/sh
# Linker wrapper for the HOST proc-macro build (the npm toolchain passes
# `-fuse-ld=lld`, and the host has no collect2-visible `ld.lld`; the flag makes
# the link fail even though plain GNU ld works fine). Drops the flag, keeps
# everything else, spaces included.
set -eu
exec python3 "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)/cc_wrapper.py" "$@"
