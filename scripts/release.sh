#!/usr/bin/env bash
# Build a release apk here, on this machine, and put it on a GitHub Release.
#
# CI does not do this on purpose. Distribution is GitHub-direct, so the signing
# key is the only thing telling a user that this update comes from whoever sent
# the last one — and Android refuses to install an update signed with a
# different key. A key that never leaves this machine cannot leak from a
# repository secret, so the build that uses it stays here too.
#
#   scripts/release.sh v0.1.0            build, verify, upload to a new Release
#   scripts/release.sh v0.1.0 --dry-run  build and verify, upload nothing
#
# Refuses to upload an unsigned apk. That is the whole point of the script.
set -euo pipefail

TAG=${1:-}
DRY_RUN=${2:-}
if [ -z "$TAG" ]; then
  echo "用法: scripts/release.sh <tag> [--dry-run]    例:scripts/release.sh v0.1.0" >&2
  exit 2
fi

cd "$(dirname "$0")/.."
ROOT=$(pwd)

# AGP 8.7.3 wants JDK 17; a newer default JDK on this machine would fail late
# and confusingly, so resolve it up front.
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME:-}/bin/javac" ]; then
  JAVA_HOME=$(/usr/libexec/java_home -v 17 2>/dev/null || echo /usr/local/opt/openjdk@17)
  export JAVA_HOME
fi
echo "JAVA_HOME=$JAVA_HOME"

# The tests are seconds and they cover the two packages a release can break in
# ways the apk build cannot see (the apk carries no JS at all yet).
npm test --prefix packages/mobile-layout
npm test --prefix packages/storage-no-hardlink

./gradlew --no-daemon :app:assembleRelease

APK=$ROOT/app/build/outputs/apk/release/app-release.apk
if [ ! -f "$APK" ]; then
  echo "没有 app-release.apk。找到的是:" >&2
  ls -1 "$ROOT/app/build/outputs/apk/release/" >&2
  echo >&2
  echo "多半是这台机器上没有 keystore.properties,构建出了 app-release-unsigned.apk。" >&2
  echo "签名是 GitHub 直发唯一的来源凭据,所以这里不上传未签名的包。" >&2
  echo "怎么配见 docs/packaging.md 的「签名」一节。" >&2
  exit 1
fi

# Verify rather than trust: a signingConfig that silently fell back would
# otherwise be discovered by users, not by us.
SDK=${ANDROID_HOME:-$(sed -n 's/^sdk.dir=//p' "$ROOT/local.properties" 2>/dev/null)}
APKSIGNER=$(ls -1 "$SDK"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1 || true)
if [ -n "$APKSIGNER" ]; then
  "$APKSIGNER" verify --print-certs "$APK" | sed -n '1,4p'
else
  echo "警告:找不到 apksigner,跳过签名校验(SDK=$SDK)" >&2
fi

VERSION=$(sed -n "s/.*versionName '\\(.*\\)'.*/\\1/p" "$ROOT/app/build.gradle")
OUT=$ROOT/app/build/outputs/apk/release/dsh-android-$VERSION.apk
cp "$APK" "$OUT"
echo "产物:$OUT ($(wc -c < "$OUT") 字节)"

NOTES=$(mktemp)
cat > "$NOTES" <<'NOTES_EOF'
非官方项目,不上架,安装需要打开"未知来源"。

**这个 apk 还不能单独用**:里面没有 Node 二进制,也没有 dsh 运行时快照,启动后会
一直等一个不存在的 host。运行时目前仍要用 adb 铺进应用数据目录,见 PLAN.md 线 A
里"运行时打进 apk"那一条。

`targetSdk 28` 是保住真实 POSIX 路径和"从数据目录 exec 二进制"的前提,不是没跟上,
理由见 docs/packaging.md。
NOTES_EOF

if [ "$DRY_RUN" = "--dry-run" ]; then
  echo "--dry-run:到此为止,没有创建 Release。说明文本在 $NOTES"
  exit 0
fi

gh release create "$TAG" --title "$TAG" --notes-file "$NOTES" "$OUT"
rm -f "$NOTES"
