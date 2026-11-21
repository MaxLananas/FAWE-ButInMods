#!/bin/sh
# Recreate `.tools/` from scratch: the Rust 1.99.0 toolchain used for the
# Horizon wasm build, installed from the pinned npm packages in
# tools/toolchain/ (npm is the only distribution channel reachable from the
# original build environment), plus the sysroot `library/Cargo.lock` this
# repository pins (tools/sysroot-library-Cargo.lock).
#
# Usage:  sh tools/setup-wasm-toolchain.sh
#
# Idempotent: sub-steps already done are skipped. After running it,
# tools/fetch-sdk.sh and tools/fetch-vendor.py give you a complete tree.
set -eu

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
TOOLS="$ROOT/.tools"

if [ ! -d "$TOOLS/rust" ]; then
  echo ">> npm install (toolchain manifests from tools/toolchain/)"
  mkdir -p "$TOOLS"
  cp "$ROOT/tools/toolchain/package.json" "$ROOT/tools/toolchain/package-lock.json" "$TOOLS/"
  (cd "$TOOLS" && npm ci --no-audit --no-fund)
  for pkg in rustc-1.99.0-x86_64-unknown-linux-gnu \
             cargo-1.99.0-x86_64-unknown-linux-gnu \
             rust-std-1.99.0-x86_64-unknown-linux-gnu \
             rust-std-1.99.0-wasm32-unknown-unknown; do
    echo ">> installing @toolchain-bin/$pkg"
    bash "$TOOLS/node_modules/@toolchain-bin/$pkg/install.sh" \
      --prefix="$TOOLS/rust" --disable-ldconfig >/dev/null
  done
  chmod +x "$TOOLS/rust/lib/rustlib"/*/bin/rust-lld 2>/dev/null || true
  chmod +x "$TOOLS/rust/bin/"* 2>/dev/null || true
else
  echo ">> .tools/rust already present"
fi

# The npm toolchain ships no Rust source; -Zbuild-std builds std from the
# superproject's `library/` tree. Fetch it sparsely at the exact commit the
# toolchain compiler was built from (1.99.0 == b940084d7...), plus its
# `library/backtrace` submodule (a plain tarball; the superproject's gitlink
# must match the compiler for ABI stability).
RUST_COMMIT="b940084d7eb6a299eb4bfeb8e34901bc051e7ac4"
BACKTRACE_COMMIT="d902726a1dcdc1e1c66f73d1162181b5423c645b"
RUST_SRC="$TOOLS/rust-src"
if [ ! -d "$RUST_SRC/library/std" ]; then
  echo ">> fetching rust-lang/rust@$RUST_COMMIT (sparse, library/ only)"
  rm -rf "$RUST_SRC"
  mkdir -p "$RUST_SRC"
  git -C "$RUST_SRC" init -q
  git -C "$RUST_SRC" remote add origin https://github.com/rust-lang/rust.git
  git -C "$RUST_SRC" config extensions.partialClone origin
  git -C "$RUST_SRC" sparse-checkout init --cone
  git -C "$RUST_SRC" sparse-checkout set library
  git -C "$RUST_SRC" fetch -q --depth 1 --filter=blob:none origin "$RUST_COMMIT"
  git -C "$RUST_SRC" checkout -q FETCH_HEAD
  echo ">> fetching backtrace-rs@$BACKTRACE_COMMIT (library/backtrace)"
  rm -rf "$RUST_SRC/library/backtrace"
  curl -fL --retry 3 -o "$TOOLS/backtrace.tar.gz" \
    "https://codeload.github.com/rust-lang/backtrace-rs/tar.gz/$BACKTRACE_COMMIT"
  mkdir -p "$RUST_SRC/library/backtrace"
  tar -xzf "$TOOLS/backtrace.tar.gz" -C "$RUST_SRC/library/backtrace" --strip-components=1
  rm "$TOOLS/backtrace.tar.gz"
else
  echo ">> .tools/rust-src already present"
fi

echo ">> linking sysroot src to tools/rust-src and installing pinned library/Cargo.lock"
mkdir -p "$TOOLS/rust/lib/rustlib/src"
if [ ! -L "$TOOLS/rust/lib/rustlib/src/rust" ]; then
  ln -s "$RUST_SRC" "$TOOLS/rust/lib/rustlib/src/rust"
fi
cp "$ROOT/tools/sysroot-library-Cargo.lock" "$RUST_SRC/library/Cargo.lock"
echo ">> sysroot library/Cargo.lock installed"

# -Zbuild-std compiles core/std from source; the prebuilt wasm32 rlibs from the
# npm package must be hidden, otherwise rustc loads two of everything and
# reports duplicate lang items (E0152).
PREBUILT="$TOOLS/rust/lib/rustlib/wasm32-unknown-unknown/lib"
if [ -d "$PREBUILT" ]; then
  mv "$PREBUILT" "$PREBUILT.bak-prebuilt-std"
  echo ">> prebuilt wasm32 std hidden as lib.bak-prebuilt-std (restore by renaming back)"
else
  echo ">> prebuilt wasm32 std already hidden"
fi

echo ">> done. Next: sh tools/fetch-sdk.sh && python3 tools/fetch-vendor.py"
