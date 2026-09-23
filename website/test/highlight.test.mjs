/**
 * Syntax highlighting, for the cases that have actually broken.
 *
 * The string pattern has to understand `$(...)`: an interpolation may contain
 * its own quotes, and treating the first of them as the end of the string
 * mis-coloured everything after it on the line. Ordering matters too — the
 * interpolation branch must be tried before `[^"\\]`, or the bare `$` matches
 * and the scan falls back to the broken behaviour.
 */
import { test, describe } from 'node:test'
import assert from 'node:assert/strict'
import { highlight } from '../src/lib/highlight.ts'

/** Text content with the markup stripped — what the reader sees. */
const text = (html) => html.replace(/<[^>]+>/g, '')

describe('highlight', () => {
  test('round-trips the source it was given', () => {
    for (const src of [
      'let x = 1',
      '"plain string"',
      'println { msg = "olive oil: €$(price_of { item = "olive-oil" })" }',
      'println { msg = "$(name): $(grams.to_str {})g" }',
    ]) {
      assert.equal(text(highlight(src)), src, `round-trip failed for ${src}`)
    }
  })

  test('a quoted string inside an interpolation does not end the outer string', () => {
    const out = highlight('println { msg = "€$(price_of { item = "olive-oil" })" }')
    // The interpolation is recognised at all…
    assert.match(out, /tk-interp/)
    // …and the inner quotes are their own string, not a stray fragment.
    assert.match(out, /<span class="tk-str">"olive-oil"<\/span>/)
  })

  test('interpolation without nested quotes still works', () => {
    const out = highlight('"$(name): $(grams.to_str {})g"')
    assert.match(out, /tk-interp/)
    assert.match(out, /tk-mth/)
  })

  test('a plain string is one span with no interpolation markup', () => {
    const out = highlight('"just text"')
    assert.match(out, /<span class="tk-str">"just text"<\/span>/)
    assert.doesNotMatch(out, /tk-interp/)
  })

  test('an escaped quote does not end the string', () => {
    const src = '"a \\" b"'
    assert.equal(text(highlight(src)), src)
  })

  // A spread is not a member access. The `.method` pattern was matching the
  // third dot of `...ys` and colouring the name after it as a method, in both
  // the sequence and the record form.
  test('a spread is not coloured as a method', () => {
    for (const src of ['[...ys, 1]', '{ ...base, a = 1 }', '[h, ...rest]']) {
      assert.doesNotMatch(highlight(src), /tk-mth/, src)
    }
  })

  test('a real member access is still coloured', () => {
    assert.match(highlight('xs.size {}'), /<span class="tk-mth">\.size<\/span>/)
  })
})
