#!/usr/bin/env bash
#
# Rebuild public/icons/*.png from a contact sheet holding all six dishes.
#
#   ./tools/icons.sh ~/Downloads/sheet.png
#
# Expects them left to right, top to bottom:
#   tomato  onion  aubergine
#   lemon   mint   olive
#
# Written down because the first pass was reconstructed by eye from the
# committed PNGs — the numbers below are the whole recipe.
set -euo pipefail

SHEET="${1:?usage: icons.sh <sheet.png>}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="$HERE/../public/icons"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

names=(tomato onion aubergine lemon mint olive)

# Palette token each dish is pulled toward, and how far.
#
# The pull is MASKED BY LUMINANCE so it lands on the coloured body only. A bare
# -colorize blends every pixel, so the black eyes and outlines get dragged
# toward the token too — at the pull olive needs, its eyes came out olive-grey
# and the face all but vanished.
#
# Values solved per dish against the set this replaces (02eadbe84), through the
# whole pipeline including quantisation: measuring an intermediate step was off
# by enough to leave everything over-saturated.
#
# Capped well below what a pure body match wants, because luminance masking
# protects black but not other hues: at 90% the tomato-s green stem turned
# brown and the lemon-s leaf turned yellow. The body barely moves between 45%
# and 90% anyway — 197,67,29 vs 198,64,31 on tomato — so the extra pull bought
# nothing and cost the foliage.
#
# Mint and olive are the exception and go to 100: they are the two the generator
# drifts furthest (mint arrived as a plain green with almost no blue, where its
# token is teal), and neither has green foliage to protect, so there is nothing
# for a strong pull to spoil.
tokens=(D6453C E8608F 7A4E9E F2C230 5FA8A0 5E7A2E)
pulls=(45 45 15 45 100 100)

# Ranges of actual content along one axis, from the alpha channel.
#
# Cutting an even 3x2 grid is wrong: a generated sheet does not centre its
# subjects in equal cells. On the first sheet the rows sat at y=57..524 and
# y=550..969, so splitting at the midpoint sliced 12px off the top row and
# pulled 38px of it into the bottom — every icon then carried a crescent of its
# neighbour, a purple smear above the olive. Cut through the gaps instead.
bounds() {   # bounds <row|col> [crop-geometry]
  local axis="$1" geom="${2-}" scale
  if [ "$axis" = row ]; then scale='1x1024!'; else scale='1536x1!'; fi
  # shellcheck disable=SC2086
  magick "$SHEET" ${geom:+-crop "$geom"} +repage \
    -alpha extract -threshold 15% -scale "$scale" -depth 8 txt: 2>/dev/null \
  | awk -F'[,:) (]+' -v axis="$axis" 'NR>1{ if (axis=="row") print $2, $4; else print $1, $3 }' \
  | awk '$2>20{print $1}' \
  | awk 'NR==1{s=$1;p=$1;next}{if($1!=p+1){print s" "p; s=$1} p=$1} END{if(NR)print s" "p}'
}

PAD=12   # breathing room, so a soft edge is not clipped

i=0
while read -r y0 y1; do
  h=$(( y1 - y0 + 1 ))
  while read -r x0 x1; do
    w=$(( x1 - x0 + 1 ))
    # Re-measure the vertical extent WITHIN this column. The row band is the
    # union of three subjects, so the tallest one sets it — using that height
    # for all three reached past the shorter ones into the row below, which is
    # how a green shard of mint ended up under the onion.
    # A column spans the whole sheet, so it reports one band per row. Pick the
    # one that overlaps the row being processed — taking the first would be
    # right for the top row and wrong for every row under it.
    cy0=$y0; cy1=$y1
    while read -r b0 b1; do
      if [ "$b0" -le "$y1" ] && [ "$b1" -ge "$y0" ]; then cy0=$b0; cy1=$b1; break; fi
    done < <(bounds row "${w}x1024+${x0}+0")
    ch=$(( cy1 - cy0 + 1 ))
    magick "$SHEET" \
      -crop "$(( w + PAD*2 ))x$(( ch + PAD*2 ))+$(( x0 - PAD ))+$(( cy0 - PAD ))" +repage \
      "$WORK/tile_$i.png"
    i=$(( i + 1 ))
  done < <(bounds col "1536x${h}+0+${y0}")
done < <(bounds row)

[ "$i" -eq 6 ] || { echo "expected 6 subjects, found $i" >&2; exit 1; }

for i in 0 1 2 3 4 5; do
  n="${names[$i]}"
  # -extent squares the (unequal) crops on a transparent ground before the
  # resize, so every icon lands on the same canvas and none is stretched.
  SZ=$(magick "$WORK/tile_$i.png" -format '%[fx:max(w,h)]' info:)
  # Split in two: an mpr: register does not survive the whole chain — folded
  # into one command every icon came out solid black.
  magick "$WORK/tile_$i.png" -write mpr:s +delete \
    mpr:s \( mpr:s -fill "#${tokens[$i]}" -colorize "${pulls[$i]}%" \) \
    \( mpr:s -alpha off -colorspace Gray -auto-level \) -compose over -composite \
    \( mpr:s -alpha extract \) -alpha off -compose CopyOpacity -composite \
    "$WORK/tinted_$i.png"
  magick "$WORK/tinted_$i.png" \
    -background none -alpha on \
    -gravity center -extent "${SZ}x${SZ}" \
    -resize 256x256 \
    -colors 128 \
    -channel A -level 0%,92% +channel \
    -strip -define png:compression-level=9 \
    "$OUT/$n.png"
  printf '  %-10s %s\n' "$n" "$(du -h "$OUT/$n.png" | cut -f1)"
done

# The URL must change when the artwork does — see tools/stamp-icons.mjs.
node "$HERE/stamp-icons.mjs"

# 128 colours costs ~0.6% RMSE — invisible on flat-shaded art — and takes the
# set from ~390K to ~70K. It quantises the ALPHA channel too, though, which left
# the interior at 200-253 instead of 255: no fully opaque pixel anywhere, so the
# whole icon was faintly see-through and read as soft against a dark ground.
# Levelling alpha snaps that interior back to solid while leaving the genuine
# one-pixel anti-aliased rim intact.
echo "▸ Done — $(du -sh "$OUT" | cut -f1) in public/icons/"
