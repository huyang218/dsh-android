#!/usr/bin/env bash
# Build the three payloads the apk carries, into app/src/main/assets/runtime/.
#
#   node.tar         the Node binary and the .so files it links against
#   seed.tar         the dsh runtime snapshot (node_modules + package.json)
#   composition.tar  the handheld profile, its agent preset, and this repo's two
#                    plugins, laid out under dsh-home
#   stamp            identity of the three above; the installer compares it to
#                    what the data directory was last left with
#
# NOT .tar.gz. AAPT un-gzips any asset ending in .gz and drops the extension
# while packaging, so the app would ask for a name that no longer exists. The
# apk deflates its own entries regardless — 372 MB of tar ships as an 84 MB apk
# — so compressing here would only hide the payload from the tool that packages
# it.
#
# TAR, NOT ZIP, AND NOT `adb push`. The Node tree is 18 symlinks out of 35
# entries (libssl.so -> libssl.so.3, and so on down the icu chain) and the seed
# has its own. A zip entry cannot hold a symlink and `adb push` silently
# dereferences them — that is how the device tree ended up 744 MB instead of
# 307 MB. tar carries links, modes and all, and Android's own tar restores them.
#
#   scripts/prepare-runtime.sh                    seed from vendor/seed's lockfile
#   scripts/prepare-runtime.sh --from-device      also re-take the node tree from a device
#   scripts/prepare-runtime.sh --seed-from <dir>  snapshot a runtime directory instead
#
# THE SEED COMES FROM A LOCKFILE, NOT FROM SOMEBODY'S MACHINE. `npm ci` against
# vendor/seed/package-lock.json puts the same 588 packages on any machine; the
# earlier shape — rsync whatever the maintainer's dsh-desktop happened to be
# running — could not be reproduced by anyone else, and could not even be
# reproduced later on the same machine.
#
# A version number alone is NOT enough: dsh@0.1.0-rc.7 depends on its siblings
# with `^`, so a fresh install today resolves dsh-web-app to rc.8, whose
# dsh-attachment-local no longer exports `detectImage` — the handheld host dies
# at boot because packages/storage-no-hardlink imports it. That is what a lock
# is for. Moving to a newer dsh is a deliberate step: regenerate the lock, then
# re-verify on a device.
#
# The Node tree has no canonical source in this repo yet: today it is Termux's
# aarch64 build, lifted off a device that was provisioned by hand. Rebuilding it
# under our own prefix is still open (PLAN.md 线 A) and matters for both the
# path prefixes and the redistribution licences — bundling it into a public apk
# is exactly the case that makes it matter.
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT=$(pwd)
ASSETS=$ROOT/app/src/main/assets/runtime
PKG=io.github.huyang218.dshandroid

# The pinned dsh install: package.json + package-lock.json, both in git.
SEED_LOCK=$ROOT/vendor/seed
# The Node tree, in git as vendor/node.tar (see vendor/README.md).
NODE_SRC=${DSH_NODE_SRC:-$ROOT/vendor}

FROM_DEVICE=
SEED_DIR=
while [ $# -gt 0 ]; do
  case "$1" in
    --from-device) FROM_DEVICE=1 ;;
    --seed-from) SEED_DIR=${2:-}; shift ;;
    *) echo "未知参数:$1" >&2; exit 2 ;;
  esac
  shift
done

STAGE=$(mktemp -d)
trap 'rm -rf "$STAGE"' EXIT

mkdir -p "$ASSETS"

# ---------------------------------------------------------------- node ------
if [ -n "$FROM_DEVICE" ]; then
  echo "==> 从设备取 Node 树"
  mkdir -p "$NODE_SRC"
  # Tar it ON the device: the archive is what preserves the symlinks, so it has
  # to be created before the bytes cross adb.
  adb exec-out run-as "$PKG" tar -c -C "/data/data/$PKG/files" node > "$NODE_SRC/node.tar"
  echo "    $NODE_SRC/node.tar ($(du -h "$NODE_SRC/node.tar" | cut -f1))"
fi

if [ ! -f "$NODE_SRC/node.tar" ]; then
  echo "找不到 $NODE_SRC/node.tar" >&2
  echo "先跑一次 scripts/prepare-runtime.sh --from-device(需要一台已经铺好运行时的设备)," >&2
  echo "或者把 Node 树自己打成 node.tar(根目录是 node/)放到那里。" >&2
  exit 1
fi

echo "==> node.tar"
cp "$NODE_SRC/node.tar" "$ASSETS/node.tar"

# ---------------------------------------------------------------- seed ------
mkdir -p "$STAGE/runtime"
if [ -n "$SEED_DIR" ]; then
  echo "==> seed.tar(快照:$SEED_DIR)"
  [ -d "$SEED_DIR/node_modules" ] || { echo "$SEED_DIR 里没有 node_modules" >&2; exit 1; }
  rsync -a "$SEED_DIR/" "$STAGE/runtime/"
else
  echo "==> seed.tar(npm ci,锁在 vendor/seed/)"
  cp "$SEED_LOCK/package.json" "$SEED_LOCK/package-lock.json" "$STAGE/runtime/"
  # --omit=dev: the phone runs this tree, it never builds against it.
  ( cd "$STAGE/runtime" && npm ci --omit=dev --no-audit --no-fund )
fi
# The lock is a build input, not something the phone needs.
rm -f "$STAGE/runtime/package-lock.json"

# Drop what can never load on android-arm64. Each of these is either a
# platform-tagged optional dependency (the loader picks a different one here)
# or a package the handheld composition does not mount at all.
#
#   node-pty                     the reason there is no shell on the phone; the
#                                row that imports it is disabled, and its
#                                prebuilds are darwin/win32/linux-x64 anyway
#   *-darwin-arm64               built for the machine that made this snapshot
#   sharp-libvips-darwin-arm64   17 MB of dylib; sharp falls back to wasm32
before=$(du -sm "$STAGE/runtime" | cut -f1)
rm -rf \
  "$STAGE/runtime/node_modules/node-pty" \
  "$STAGE/runtime/node_modules/node-addon-require-builtin-darwin-arm64" \
  "$STAGE/runtime/node_modules/@koromix/koffi-darwin-arm64" \
  "$STAGE/runtime/node_modules/@img/sharp-darwin-arm64" \
  "$STAGE/runtime/node_modules/@img/sharp-libvips-darwin-arm64" \
  "$STAGE/runtime/node_modules/@vscode/ripgrep-darwin-arm64"
after=$(du -sm "$STAGE/runtime" | cut -f1)
echo "    剪掉平台专属包:${before}M -> ${after}M"
tar -C "$STAGE" -cf "$ASSETS/seed.tar" runtime

# --------------------------------------------------------- composition ------
echo "==> composition.tar"
HOME_DIR=$STAGE/dsh-home
mkdir -p "$HOME_DIR/profiles/handheld" "$HOME_DIR/.agent-presets"
rsync -a "$ROOT/composition/profiles/handheld/" "$HOME_DIR/profiles/handheld/"
rsync -a "$ROOT/composition/agent-presets/handheld" "$HOME_DIR/.agent-presets/"

# The two plugins this repo owns must resolve from the profile directory: the
# loader looks in the installation first and the profile second, and neither
# of these ships with dsh. Vendored as real directories — `npm link` would be a
# symlink to a checkout that does not exist on a phone.
for plugin in mobile-layout storage-no-hardlink; do
  name=$(sed -n 's/.*"name": "\(.*\)".*/\1/p' "$ROOT/packages/$plugin/package.json" | head -1)
  dest=$HOME_DIR/profiles/handheld/node_modules/$name
  mkdir -p "$dest"
  # Ship what the package declares in `files`, not the whole directory: tests
  # and scratch files have no business on a phone.
  rsync -a --exclude test --exclude node_modules "$ROOT/packages/$plugin/" "$dest/"
  echo "    $name"
done
tar -C "$STAGE" -cf "$ASSETS/composition.tar" dsh-home

# --------------------------------------------------------------- stamp ------
# Line 1 is the identity of the payloads — not of the repo: the installer must
# re-unpack when any of the three changes, and must NOT when only unrelated app
# code did.
#
# The lines after it are `<name> <bytes>`, which is how the first-run screen can
# show a real percentage. The app cannot measure this itself: the payloads are
# deflated inside the apk, so `openFd` refuses them and the only length an
# AssetManager stream reports is the compressed one.
STAMP=$(cat "$ASSETS/node.tar" "$ASSETS/seed.tar" "$ASSETS/composition.tar" \
  | shasum -a 256 | cut -c1-16)
{
  echo "$STAMP"
  for payload in node.tar seed.tar composition.tar; do
    echo "$payload $(wc -c < "$ASSETS/$payload" | tr -d ' ')"
  done
} > "$ASSETS/stamp"

echo
echo "载荷:"
ls -lh "$ASSETS" | tail -n +2
echo "stamp: $STAMP"
