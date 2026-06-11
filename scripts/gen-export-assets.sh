#!/usr/bin/env bash
#
# Regenerate the server-side GIF-export assets from the canonical frontend sources.
#
# Why: the export renderer keeps its own bundled copy of the player avatars (the
# server reads PNGs at runtime — no SVG library / Apache Batik dependency). This
# script rasterizes those PNGs straight from the frontend's source of truth,
#   promptle-app/src/app/core/models/player-icons.ts
# so the export set never drifts: it derives each avatar's id + SVG from that
# PLAYER_ICONS array, rasterizes it to export-assets/player-icons/<id>.png, and
# converts the real app logo (logos.ico) to the export logo PNG.
#
# Because it reads player-icons.ts directly, adding a new avatar needs NO edit
# here — just register it in player-icons.ts (as usual) and re-run this script.
#
# Avatars are named by their runtime avatarId (icon-N.png), so the renderer can
# load export-assets/player-icons/<avatarId>.png directly with no lookup table.
#
# Requires: rsvg-convert (brew install librsvg), sips (macOS built-in).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SVG_DIR="$REPO_ROOT/promptle-app/public/player-icons"
ICONS_TS="$REPO_ROOT/promptle-app/src/app/core/models/player-icons.ts"
ICO="$REPO_ROOT/promptle-app/public/logos.ico"
OUT_DIR="$REPO_ROOT/promptle/src/main/resources/export-assets/player-icons"
LOGO_OUT="$REPO_ROOT/promptle/src/main/resources/export-assets/promptle-logo.png"

AVATAR_PX=256
LOGO_PX=128

command -v rsvg-convert >/dev/null || { echo "rsvg-convert not found (brew install librsvg)"; exit 1; }
command -v sips >/dev/null || { echo "sips not found (macOS required)"; exit 1; }
[ -f "$ICONS_TS" ] || { echo "player-icons.ts not found: $ICONS_TS"; exit 1; }

echo "Clearing old avatar PNGs in $OUT_DIR"
rm -f "$OUT_DIR"/*.png
mkdir -p "$OUT_DIR"

# Derive each avatar from PLAYER_ICONS: { id: 'icon-N', path: 'player-icons/<name>.svg' }
# -> "icon-N <name>". player-icons.ts is the single source of truth shared with the UI,
# so adding an avatar there is all it takes — no edit to this script.
# (Portable read loop — macOS ships bash 3.2, which has no `mapfile`.)
count=0
while read -r id name; do
  [ -n "$id" ] || continue
  src="$SVG_DIR/$name.svg"
  [ -f "$src" ] || { echo "MISSING SVG referenced by player-icons.ts: $src"; exit 1; }
  rsvg-convert -w "$AVATAR_PX" -h "$AVATAR_PX" "$src" -o "$OUT_DIR/$id.png"
  echo "  $id.png  <- $name.svg"
  count=$((count + 1))
done < <(
  grep -oE "id:[[:space:]]*'[^']+'[[:space:]]*,[[:space:]]*path:[[:space:]]*'player-icons/[^']+\.svg'" "$ICONS_TS" \
    | sed -E "s/.*id:[[:space:]]*'([^']+)'.*path:[[:space:]]*'player-icons\/([^']+)\.svg'.*/\1 \2/"
)
[ "$count" -gt 0 ] || { echo "No PLAYER_ICONS entries parsed from $ICONS_TS"; exit 1; }

echo "Converting logo: logos.ico -> promptle-logo.png (${LOGO_PX}px, alpha)"
# sips picks the largest frame (128px) from the multi-res .ico and keeps alpha.
sips -s format png "$ICO" --out "$LOGO_OUT" >/dev/null
sips -z "$LOGO_PX" "$LOGO_PX" "$LOGO_OUT" >/dev/null

echo "Done. Generated $count avatars (from player-icons.ts) + logo."
