#!/usr/bin/env bash
#
# Rebuild public/icons/*.png from a 3x2 contact sheet.
#
# The source is a single generated image holding all six dishes. Written down
# because the last pass was reconstructed by eye from the committed PNGs — the
# numbers below are the whole recipe, so a new sheet takes one command.
#
#   ./tools/icons.sh ~/Downloads/sheet.png
#
# Expects the dishes in this order, left to right, top to bottom:
#   tomato  onion  aubergine
#   lemon   mint   olive
set -euo pipefail

SHEET="${1:?usage: icons.sh <sheet.png>}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="$HERE/../public/icons"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

names=(tomato onion aubergine lemon mint olive)

# Palette token each dish is nudged toward (light theme, src/styles/tokens.css).
tokens=(D6453C E8608F 7A4E9E F2C230 5FA8A0 5E7A2E)

# How far to pull, in percent. Deliberately small: the generated art already
# sits near the palette, and a heavy pull flattens the shading that makes the
# icons read as objects rather than swatches. Mint and olive need more — the
# generator drifts them green/yellow, losing mint's teal entirely.
pulls=(6 6 6 6 26 22)

magick "$SHEET" -crop 3x2@ +repage "$WORK/tile_%d.png"

for i in 0 1 2 3 4 5; do
  n="${names[$i]}"
  magick "$WORK/tile_$i.png" \
    -background none -alpha on \
    -fill "#${tokens[$i]}" -colorize "${pulls[$i]}%" \
    -modulate 100,104,100 \
    -resize 256x256 \
    -colors 128 \
    -strip -define png:compression-level=9 \
    "$OUT/$n.png"
  printf '  %-10s %s\n' "$n" "$(du -h "$OUT/$n.png" | cut -f1)"
done

# 128 colours costs ~0.6% RMSE — invisible on these flat-shaded icons — and
# takes the set from ~390K to ~70K.
echo "▸ Done — $(du -sh "$OUT" | cut -f1) in public/icons/"
