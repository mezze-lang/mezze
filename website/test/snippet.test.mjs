/**
 * Snippet stripping.
 *
 * The browser editor has no tab stops, so a candidate marked `snippet` has to
 * be flattened to plain text before it goes into the buffer. Anything left
 * behind is visible to the reader as syntax they did not type — the bug that
 * put `.map {f = {x} -> $0}` on screen.
 *
 * The inputs below are verbatim `insertText` values captured from the real
 * completion engine, not invented ones.
 */
import { test, describe } from 'node:test'
import assert from 'node:assert/strict'
import { plainFromSnippet } from '../src/lib/snippet.ts'

describe('plainFromSnippet', () => {
  const cases = [
    // A lambda argument nests $0 INSIDE a placeholder default. This is the
    // case that regressed: the default was returned verbatim, tabstop and all.
    ['map {f = ${1:{x} -> $0}}', 'map {f = {x} -> }'],
    ['filter {p = ${1:{x} -> $0}}', 'filter {p = {x} -> }'],
    ['partition {p = ${1:{k, v} -> $0}}', 'partition {p = {k, v} -> }'],
    ['fold {init = ${1:init}, f = ${2:{a, b} -> $0}}', 'fold {init = init, f = {a, b} -> }'],
    // Plain placeholders, no nesting.
    ['insert {k = ${1:k}, v = ${2:v}}', 'insert {k = k, v = v}'],
    ['insert_at {i = ${1:i}, x = ${2:x}}', 'insert_at {i = i, x = x}'],
    // A bare final tabstop.
    ['foo { $0 }', 'foo {  }'],
    // An escaped dollar is literal text, not a tabstop.
    ['\\${1:literal}', '${1:literal}'],
  ]

  for (const [input, want] of cases) {
    test(input, () => assert.equal(plainFromSnippet(input), want))
  }

  test('no placeholder syntax survives any case', () => {
    for (const [input] of cases) {
      if (input.startsWith('\\')) continue // escaped: the $ is meant to remain
      const got = plainFromSnippet(input)
      assert.ok(!/\$\d/.test(got), `bare tabstop left in ${JSON.stringify(got)}`)
      assert.ok(!/\$\{/.test(got), `placeholder left in ${JSON.stringify(got)}`)
    }
  })
})
