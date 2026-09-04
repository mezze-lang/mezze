/**
 * Completion, end to end through the real WASM runtime.
 *
 *   npm test        (needs the dev server up: npm run dev)
 *
 * These load the same mezze.js the browser loads and call completeMezze
 * exactly as the editor does, so a pass means the path a reader exercises
 * works — not that a fragment of it does in isolation.
 *
 * Every earlier "bug" in this area turned out twice to be a mistake in a
 * throwaway script: a caret at the end of the buffer rather than where a
 * person would type it. Hence `at()` below, which takes the caret as a `|`
 * inside the source and cannot drift from the text around it.
 */
import { test, describe } from 'node:test'
import assert from 'node:assert/strict'

const BASE = process.env.MEZZE_WASM ?? 'http://localhost:4321/wasm'

// Loaded once and awaited by each test. A `before` hook runs per suite, and
// the runtime takes seconds to instantiate — three parallel loads raced and
// every suite but one saw an unfinished globalThis.
let loading
function runtime() {
  loading ??= (async () => {
    globalThis.window = globalThis
    globalThis.document = { currentScript: { src: BASE + '/mezze.js' } }
    globalThis.location = { href: BASE + '/mezze.js' }
    const src = await fetch(BASE + '/mezze.js').then((r) => r.text())
    const { default: vm } = await import('node:vm')
    vm.runInThisContext(src, { filename: BASE + '/mezze.js' })
    for (let i = 0; i < 900 && !globalThis.completeMezze; i++) {
      await new Promise((r) => setTimeout(r, 100))
    }
    assert.ok(globalThis.completeMezze, 'completeMezze never appeared on the runtime')
  })()
  return loading
}

/** Split a `|`-marked source into [text, line, col], both 1-based. */
function at(marked) {
  const caret = marked.indexOf('|')
  assert.ok(caret >= 0, 'test source needs a | for the caret')
  const text = marked.slice(0, caret) + marked.slice(caret + 1)
  const upto = text.slice(0, caret)
  return [text, upto.split('\n').length, caret - upto.lastIndexOf('\n')]
}

/** Labels the editor would show: the engine's answer minus keywords. */
function labels(marked) {
  const [text, line, col] = at(marked)
  return globalThis
    .completeMezze(text, line, col)
    .filter((c) => c.kind !== 'Keyword')
    .map((c) => c.label)
}

describe('dot completion', () => {
  test('methods on an Int', async () => {
    await runtime()
    const got = labels('let main = {} -> do\n  let n = 42\n  n.|')
    assert.ok(got.includes('to_str'), `expected to_str, got ${got.slice(0, 6)}`)
    assert.ok(got.includes('to_float'))
  })

  test('narrows as you type', async () => {
    await runtime()
    const got = labels('let main = {} -> do\n  let n = 42\n  n.to|')
    assert.ok(got.length > 0)
    assert.ok(got.every((l) => l.startsWith('to')), `unfiltered: ${got.slice(0, 6)}`)
  })

  test('methods on a stdlib type', async () => {
    await runtime()
    const got = labels(
      'use std::collections::map\nlet m = map::empty {}\nlet x = m.|',
    )
    assert.ok(got.includes('insert'), `expected insert, got ${got.slice(0, 6)}`)
    assert.ok(got.includes('get'))
  })

  test('offers nothing for an unresolvable receiver', async () => {
    await runtime()
    // No binding called `mystery`, so there is no type to list methods from.
    // Guessing here would be worse than silence.
    const got = labels('let main = {} -> do\n  mystery.|')
    assert.equal(got.length, 0)
  })
})

describe('import completion', () => {
  test('lists modules on a bare use', async () => {
    await runtime()
    const got = labels('use |')
    assert.ok(got.length > 20, `expected many modules, got ${got.length}`)
    assert.ok(got.includes('std::collections::map'))
  })

  test('includes non-prelude modules', async () => {
    await runtime()
    // These live outside the prelude, so they are missing unless the module
    // list comes from the stdlib manifest rather than the precompiled types.
    const got = labels('use |')
    assert.ok(got.includes('std::effects::http'), 'std::effects::http missing')
    assert.ok(got.includes('std::effects::log'))
  })

  test('filters by the path typed so far', async () => {
    await runtime()
    const got = labels('use std::effects::h|')
    assert.ok(got.length > 0)
    assert.ok(got.every((l) => l.startsWith('std::effects::h')), `got ${got}`)
  })

  test('replaces the whole path, not just the last word', async () => {
    await runtime()
    // Accepting `std::effects::http` after `use std::eff` must not leave
    // `use std::std::effects::http` — the item carries its own range.
    const [text, line, col] = at('use std::eff|')
    const items = globalThis.completeMezze(text, line, col)
    const pick = items.find((c) => c.label === 'std::effects::http')
    assert.ok(pick, 'std::effects::http not offered')
    assert.ok(pick.edit, 'no replacement range on the item')
    const before = text.slice(0, pick.edit.startCol)
    assert.equal(before + pick.label, 'use std::effects::http')
  })
})

describe('selective imports', () => {
  test('lists a module\'s exports inside ::{ }', async () => {
    await runtime()
    const got = labels('use std::effects::async::{ |')
    assert.ok(got.length > 10, `expected many exports, got ${got.length}`)
    assert.ok(got.includes('Promise'), `expected Promise, got ${got.slice(0, 6)}`)
    assert.ok(got.includes('await'))
  })

  test('filters by what is typed', async () => {
    await runtime()
    const got = labels('use std::effects::async::{ Pro|')
    assert.ok(got.length > 0)
    assert.ok(got.every((l) => l.startsWith('Pro')), `unfiltered: ${got}`)
  })

  test('does not offer a name already imported', async () => {
    await runtime()
    const got = labels('use std::effects::async::{ Promise, |')
    assert.ok(got.length > 0)
    assert.ok(!got.includes('Promise'), 'Promise offered twice')
  })

  test('replaces only the partial name', async () => {
    await runtime()
    const [text, line, col] = at('use std::effects::async::{ Pro|')
    const pick = globalThis.completeMezze(text, line, col).find((c) => c.label === 'Promise')
    assert.ok(pick?.edit, 'no replacement range')
    const out = text.slice(0, pick.edit.startCol) + pick.label
    assert.equal(out, 'use std::effects::async::{ Promise')
  })

  test('unknown module offers nothing rather than guessing', async () => {
    await runtime()
    const got = labels('use std::nope::{ |')
    assert.equal(got.length, 0)
  })
})

describe('qualified names', () => {
  const H = 'use std::collections::map\n'

  test("lists an imported module's functions", async () => {
    await runtime()
    const got = labels(H + 'let x = map::|')
    assert.ok(got.includes('empty'), `expected empty, got ${got.slice(0, 6)}`)
    assert.ok(got.includes('insert'))
  })

  test('filters by what is typed', async () => {
    await runtime()
    const got = labels(H + 'let x = map::em|')
    assert.ok(got.every((l) => l.startsWith('em')), `unfiltered: ${got}`)
  })

  test('replaces only the partial name', async () => {
    await runtime()
    const [text, line, col] = at(H + 'let x = map::em|')
    const pick = globalThis.completeMezze(text, line, col).find((c) => c.label === 'empty')
    assert.ok(pick?.edit, 'no replacement range')
    const lineText = text.split('\n')[line - 1]
    assert.equal(lineText.slice(0, pick.edit.startCol) + pick.label, 'let x = map::empty')
  })
})

describe('candidate shape', () => {
  test('every item carries text to insert', async () => {
    await runtime()
    const [text, line, col] = at('let main = {} -> do\n  let n = 42\n  n.|')
    for (const c of globalThis.completeMezze(text, line, col)) {
      assert.ok(c.label, 'candidate with no label')
      assert.ok(typeof c.kind === 'string', `bad kind on ${c.label}`)
    }
  })

  test('snippet items are flagged as such', async () => {
    await runtime()
    // The editor strips ${1:…} placeholders, but only for items that say
    // they are snippets. An unflagged snippet would be inserted verbatim.
    const [text, line, col] = at('let main = {} -> do\n  let n = 42\n  n.|')
    for (const c of globalThis.completeMezze(text, line, col)) {
      if (/\$\{|\$\d/.test(c.insertText ?? '')) {
        assert.equal(c.snippet, true, `${c.label} has placeholders but snippet=false`)
      }
    }
  })
})
