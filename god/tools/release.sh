#!/data/data/com.termux/files/usr/bin/bash
# Publish a god-termux build to the private builds repo (releases only, no sources). Rescue reads latest.json from the newest release.
#   bash god/tools/release.sh [--beta] [--no-build] [--apk FILE] [--notes TEXT] [--keep N]
# --beta     prerelease on the beta channel (tag beta-*); without it: stable (tag v*)
# --no-build use the APK that is already built (default: run god/build-local.sh first)
# --apk F    publish this file instead of the build output
# --keep N   keep the newest N releases per channel (default 10), delete older ones with their tags
# Needs: gh (logged in, scope repo), aapt2, git. Env: BUILDS_REPO (default CrafterMinecrafter/god-termux-builds).
set -euo pipefail
SRC=$(cd "$(dirname "$0")/../.." && pwd); REPO=${BUILDS_REPO:-CrafterMinecrafter/god-termux-builds}
CHANNEL=stable; BUILD=1; APK=; NOTES=; KEEP=10
while [ $# -gt 0 ]; do case $1 in
  --beta) CHANNEL=beta ;; --no-build) BUILD=0 ;; --apk) APK=$2; BUILD=0; shift ;; --notes) NOTES=$2; shift ;; --keep) KEEP=$2; shift ;;
  -h|--help) sed -n '2,10p' "$0"; exit 0 ;; *) echo "неизвестная опция: $1" >&2; exit 2 ;; esac; shift; done

command -v gh >/dev/null || { echo "нужен gh (pkg install gh)" >&2; exit 1; }
[ "$BUILD" = 1 ] && bash "$SRC/god/build-local.sh"
[ -n "$APK" ] || APK=$(ls -t "${GOD_BUILD:-$HOME/god-build}"/app/build/outputs/apk/debug/*universal*.apk 2>/dev/null | head -1)
[ -f "$APK" ] || { echo "APK не найден (соберите: bash god/build-local.sh)" >&2; exit 1; }

BADGING=$(aapt2 dump badging "$APK" 2>/dev/null | sed -n 1p)
PKG=$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<<"$BADGING"); VCODE=$(sed -n "s/.*versionCode='\([^']*\)'.*/\1/p" <<<"$BADGING"); VNAME=$(sed -n "s/.*versionName='\([^']*\)'.*/\1/p" <<<"$BADGING")
[ "$PKG" = com.termux ] || { echo "в APK пакет '$PKG', ожидался com.termux" >&2; exit 1; }
COMMIT=$(git -C "$SRC" rev-parse --short HEAD 2>/dev/null || echo unknown); git -C "$SRC" diff --quiet 2>/dev/null || COMMIT="$COMMIT-dirty"
SHA=$(sha256sum "$APK" | cut -d' ' -f1); SIZE=$(stat -c %s "$APK"); DATE=$(date -u +%Y%m%d-%H%M)
TAG=$([ "$CHANNEL" = beta ] && echo "beta-$DATE" || echo "v$VNAME-$DATE")
NAME="god-termux-$TAG.apk"
W=$(mktemp -d); trap 'rm -rf "$W"' EXIT
cp "$APK" "$W/$NAME"
cat > "$W/latest.json" <<EOF
{"channel":"$CHANNEL","tag":"$TAG","versionCode":$VCODE,"versionName":"$VNAME","sha256":"$SHA","size":$SIZE,"apk":"$NAME","commit":"$COMMIT","date":"$DATE"}
EOF
echo "публикую $TAG ($VNAME, код $VCODE, $((SIZE/1048576)) МБ, коммит $COMMIT, sha256 ${SHA:0:12}…) в $REPO"
FLAGS=(); [ "$CHANNEL" = beta ] && FLAGS+=(--prerelease)
gh release create "$TAG" "$W/$NAME" "$W/latest.json" --repo "$REPO" --title "$TAG" --notes "${NOTES:-Сборка $VNAME (код $VCODE), коммит $COMMIT. SHA-256: $SHA}" "${FLAGS[@]}"

# keep the newest KEEP releases per channel
PFX=$([ "$CHANNEL" = beta ] && echo beta- || echo v)
gh release list --repo "$REPO" --limit 200 --json tagName,createdAt --jq 'sort_by(.createdAt)|reverse|.[].tagName' | grep "^$PFX" | tail -n +$((KEEP+1)) | while read -r old; do
  echo "удаляю старый релиз $old"; gh release delete "$old" --repo "$REPO" --cleanup-tag -y
done
echo "готово: https://github.com/$REPO/releases/tag/$TAG"
