#!/usr/bin/env python3
"""Linker wrapper for the member build (`cargo` calls this as the wasm linker).

Expands the response-file argument rustc hands the linker (one argument per
line, `\\` escaping spaces and backslashes), then invokes `rust-lld` with the
shared-library flags Horizon's wasm groups need:

    --shared --strip-all --unresolved-symbols=import-dynamic -Bsymbolic
    --export=horizon_layout --export=horizon_in --export=horizon_hook

The module is self-contained (std is linked statically with hidden default
visibility), so nothing is dropped from the argument list. `--strip-all` keeps
the dynamic symbol table minimal; the exports above plus the synthetic
`__wasm_*` entry points are what survive. Mirrors the link step of the SDK's
`cargo horizon-mod build --wasm` for a single member with no mod dependencies;
see `horizon/README.md` for why the pinned `cargo horizon-mod` tool is not
used here.
"""
import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LLD = os.path.join(
    ROOT, ".tools", "rust", "lib", "rustlib",
    "x86_64-unknown-linux-gnu", "bin", "rust-lld",
)


def unescape(line: str) -> str:
    out = []
    chars = iter(line)
    for ch in chars:
        if ch == "\\":
            nxt = next(chars, None)
            out.append(nxt if nxt is not None else ch)
        else:
            out.append(ch)
    return "".join(out)


def expand(args):
    for arg in args:
        if arg.startswith("@"):
            with open(arg[1:], encoding="utf-8") as handle:
                for line in handle.read().splitlines():
                    if line.strip():
                        yield unescape(line)
        else:
            yield arg


def main():
    args = list(expand(sys.argv[1:]))
    out = list(args)
    out += [
        "--shared",
        "--strip-all",
        "--unresolved-symbols=import-dynamic",
        "-Bsymbolic",
        "--export=horizon_layout",
        "--export=horizon_in",
        "--export=horizon_hook",
    ]
    sys.exit(subprocess.call([LLD] + out))


if __name__ == "__main__":
    main()
