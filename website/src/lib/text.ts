/**
 * Inline text formatting for copy in src/data/content.ts.
 *
 *   ~onion~word~   -> <mark class="mark-onion">word</mark>
 *   ~~word~~       -> squiggle underline in the default colour
 *   ~~tomato:word~~-> squiggle underline in that palette colour
 *
 * Underscore is NOT a delimiter: snake_case identifiers appear throughout the copy.
 *   ^TBD^          -> superscript marker, for a claim not yet shipped
 *   **word**       -> <strong>word</strong>
 *   `code`         -> <code>code</code>
 *   \n             -> <br>
 *
 * Escapes first, so copy can contain < and & safely.
 */

const MARKS = ['olive', 'lemon', 'onion', 'tomato', 'mint', 'auber'] as const

/** Squiggle colours are the palette itself, so they read as strokes not washes. */
const SQUIGGLES = ['olive', 'aubergine', 'tomato', 'lemon', 'onion', 'mint'] as const
type Squiggle = (typeof SQUIGGLES)[number]

const esc = (s: string) =>
  s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')

/** Hand-drawn underline. Inline SVG so the stroke can be a palette colour. */
const squiggle = (word: string, colour?: Squiggle) =>
  `<span class="squiggle${colour ? ` sq-${colour}` : ''}">${word}` +
  `<svg viewBox="0 0 120 9" preserveAspectRatio="none" aria-hidden="true">` +
  `<path d="M2,6 C24,2 44,8 64,4 C84,1 102,7 118,3"/></svg></span>`

export function format(input: string): string {
  let out = esc(input)

  for (const name of MARKS) {
    out = out.replace(
      new RegExp(`~${name}~([^~]+)~`, 'g'),
      `<mark class="mark-${name}">$1</mark>`,
    )
  }

  // One pattern for both squiggle forms: the optional `colour:` prefix picks a
  // palette stroke, and anything else stays the default.
  out = out.replace(/~~(?:([a-z]+):)?([^~\n]+)~~/g, (_, colour, word) =>
    SQUIGGLES.includes(colour)
      ? squiggle(word, colour as Squiggle)
      : squiggle(colour ? `${colour}:${word}` : word),
  )

  out = out.replace(/\^([^^\n]+)\^/g, '<sup class="tbd" title="Designed, not yet shipped">$1</sup>')
  out = out.replace(/\*\*([^*\n]+)\*\*/g, '<strong>$1</strong>')
  out = out.replace(/`([^`]+)`/g, '<code>$1</code>')
  out = out.replace(/\n/g, '<br>')

  return out
}
