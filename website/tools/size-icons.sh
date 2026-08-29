#!/usr/bin/env bash
#
# Normalise public/icons/*.png so every subject fills the same fraction of its
# canvas.
#
#   ./tools/size-icons.sh
#
# The icons come from different sources — five hand-corrected, one generated —
# and a subject that fills 96% of its canvas reads as much larger than one
# filling 81%, even though both files are 256x256. Trim to content, scale the
# longest side to a fixed share, recentre.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="$HERE/../public/icons"

CANVAS=256
FILL=82          # percent of the canvas the longest side occupies
SIDE=$(( CANVAS * FILL / 100 ))

for f in "$OUT"/*.png; do
  magick "$f" -trim +repage \
    -resize "${SIDE}x${SIDE}" \
    -background none -gravity center -extent "${CANVAS}x${CANVAS}" \
    -colors 128 \
    -channel A -level 0%,92% +channel \
    -strip -define png:compression-level=9 \
    "$f"
  printf '  %-14s %-12s %s\n' "$(basename "$f")" \
    "$(magick "$f" -trim -format '%wx%h' info: 2>/dev/null)" \
    "$(du -h "$f" | cut -f1)"
done

node "$HERE/stamp-icons.mjs"
