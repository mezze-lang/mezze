/**
 * Every runnable sample on the site, through the real runtime.
 *
 *   npm test        (needs the dev server up: npm run dev)
 *
 * Three things are checked per sample, because each has been wrong at least
 * once and only the first was being caught:
 *
 *   1. it typechecks
 *   2. it produces NO diagnostics — warnings included. `mez check` exits 0 on
 *      a warning, so an exit-code gate misses unused imports entirely, and
 *      four of them shipped.
 *   3. it RUNS and prints something. `impl Summary for { …shape… }` called on
 *      an alias-annotated receiver typechecks and then dies with "Field not
 *      found" — a sample can be green on types and still be broken.
 */
import { test, describe } from 'node:test'
import assert from 'node:assert/strict'
import { readFile, readdir } from 'node:fs/promises'

const BASE = process.env.MEZZE_WASM ?? 'http://localhost:4321/wasm'
const EDITOR = /<!--mezze-editor\s*([\s\S]*?)-->/g

let loading
function runtime() {
  loading ??= (async () => {
    globalThis.window = globalThis
    globalThis.document = { currentScript: { src: BASE + '/mezze.js' } }
    globalThis.location = { href: BASE + '/mezze.js' }
    const src = await fetch(BASE + '/mezze.js').then((r) => r.text())
    const { default: vm } = await import('node:vm')
    vm.runInThisContext(src, { filename: BASE + '/mezze.js' })
    for (let i = 0; i < 900 && !globalThis.runMezze; i++) {
      await new Promise((r) => setTimeout(r, 100))
    }
    assert.ok(globalThis.runMezze, 'runMezze never appeared on the runtime')
  })()
  return loading
}

/** [name, source] for every mezze-editor block under src/content. */
async function samples() {
  const out = []
  for (const dir of ['src/content/concepts', 'src/content/taste', 'src/content/blog']) {
    let files = []
    try { files = await readdir(dir) } catch { continue }
    for (const f of files.filter((f) => f.endsWith('.md'))) {
      const md = await readFile(dir + '/' + f, 'utf8')
      let m, i = 0
      while ((m = EDITOR.exec(md))) {
        out.push([`${f.replace('.md', '')}_${i++}`, m[1].trim()])
      }
    }
  }
  return out
}

await runtime()
const found = await samples()

describe('site samples', () => {
  test('there are samples to check', () => assert.ok(found.length > 0))

  for (const [name, src] of found) {
    // A sample may be a deliberate demonstration of a compile error — the blog
    // asks the reader to press Check on one. It opts out with a comment on its
    // first line, which is invisible to the page but visible here.
    const expectError = /^#\s*expect-error\b/m.test(src.split('\n')[0] ?? '')
    if (expectError) {
      test(`${name} fails to typecheck, as the page says it does`, () => {
        const ds = globalThis.checkMezze(src)?.diagnostics ?? []
        assert.ok(
          ds.some((d) => d.severity !== 'warning'),
          `${name} is marked expect-error but typechecks clean`,
        )
      })
      continue
    }
    test(`${name} typechecks with no diagnostics`, async () => {
      const res = globalThis.checkMezze(src)
      const ds = res?.diagnostics ?? []
      assert.deepEqual(
        ds.map((d) => `${d.severity ?? '?'}: ${d.message}`),
        [],
        `${name} is not clean`,
      )
    })

    test(`${name} runs and prints something`, async () => {
      const out = String(globalThis.runMezze(src) ?? '')
      assert.ok(!/^ERROR:/m.test(out), `${name} failed at runtime:\n${out}`)
      assert.ok(out.trim().length > 0, `${name} produced no output`)
    })
  }
})
