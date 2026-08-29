/**
 * Applies the site's inline syntax to rendered Markdown posts.
 *
 *   ==onion:word==   highlighter mark
 *   ~word~           squiggle underline (default colour)
 *   ~tomato:word~    squiggle in a palette colour
 *
 * These differ from the landing-page forms in lib/text.ts because Markdown
 * runs first: it turns `--` into an en-dash, and claims every `~` form for
 * strikethrough. So marks take `==`, and squiggles are read back out of the
 * <del> element Markdown produces for `~word~`.
 *
 * Code is protected: anything inside <code> or <pre> is set aside before
 * formatting and restored afterwards, so a post can show the raw syntax
 * without it being interpreted.
 */

const CODE = /<(pre|code)\b[\s\S]*?<\/\1>/gi
const SLOT = (i: number) => `@@MZ${i}@@`

/** Marks use the highlighter washes; squiggles use the palette directly. */
const MARKS = ['olive', 'lemon', 'onion', 'tomato', 'mint', 'auber']
const SQUIGGLES = ['olive', 'aubergine', 'tomato', 'lemon', 'onion', 'mint']

const squiggle = (word: string, colour?: string) =>
  `<span class="squiggle${colour ? ` sq-${colour}` : ''}">${word}` +
  `<svg viewBox="0 0 120 9" preserveAspectRatio="none" aria-hidden="true">` +
  `<path d="M2,6 C24,2 44,8 64,4 C84,1 102,7 118,3"/></svg></span>`

export function applyMarkup(html: string): string {
  const held: string[] = []
  let out = html.replace(CODE, (m) => SLOT(held.push(m) - 1))

  out = out.replace(/==(?:([a-z]+):)?([^=]{1,200}?)==/g, (whole, colour, word) =>
    MARKS.includes(colour) ? `<mark class="mark-${colour}">${word}</mark>` : whole,
  )

  out = out.replace(/<del>(?:([a-z]+):)?([^<]+)<\/del>/g, (whole, colour, word) =>
    colour === undefined
      ? squiggle(word)
      : SQUIGGLES.includes(colour)
        ? squiggle(word, colour)
        : whole,
  )

  return out.replace(/@@MZ(\d+)@@/g, (_, i) => held[Number(i)])
}
