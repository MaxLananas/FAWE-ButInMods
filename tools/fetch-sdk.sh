#!/bin/sh
# Fetch the Horizon modding SDK the build needs, into .horizon/sdk/api (git-ignored).
#
# Horizon packages the SDK as the `sdk-<commit>` tag of the game repository. A code
# mod builds against the API of one exact build of the game, and cargo horizon-mod
# pins that tag, so the checkout below must be the same one the package metadata
# names (see the `[package.metadata.horizon]` table in Cargo.toml).
#
# The tree is large (~200 MB) because the API generator re-reads the game's own
# sources at build time. It is not committed; re-run this script instead.
set -eu

SDK_TAG="sdk-dd67b76ff23e28c6d8e21859df600d31572f81a0"
SDK_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"

if [ -d "$SDK_DIR/.horizon/sdk/api/crates/horizon-mod-api" ]; then
  echo "Horizon SDK already present in $SDK_DIR/.horizon/sdk/api"
  exit 0
fi

mkdir -p "$SDK_DIR/.horizon/sdk"
echo "Fetching horizon-rust/horizon@$SDK_TAG ..."
curl -fL --retry 3 -o "$SDK_DIR/.horizon/sdk/sdk.tar.gz" \
  "https://codeload.github.com/horizon-rust/horizon/tar.gz/refs/tags/$SDK_TAG"
rm -rf "$SDK_DIR/.horizon/sdk/api"
mkdir -p "$SDK_DIR/.horizon/sdk/api"
tar -xzf "$SDK_DIR/.horizon/sdk/sdk.tar.gz" -C "$SDK_DIR/.horizon/sdk/api" --strip-components=1
rm "$SDK_DIR/.horizon/sdk/sdk.tar.gz"
echo "SDK unpacked into $SDK_DIR/.horizon/sdk/api"
