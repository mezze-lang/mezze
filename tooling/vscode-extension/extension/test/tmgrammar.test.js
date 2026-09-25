#!/usr/bin/env node
//
// Scope assertions for syntaxes/mezze.tmLanguage.json.
//
// This grammar is hand-maintained and, until this file existed, untested: it
// had no rule at all for binding names, method calls, module paths or record
// fields, and nothing would have said so. It runs the REAL engine VS Code runs
// (vscode-textmate over vscode-oniguruma), so a pass here is a statement about
// what a user sees, not about a regex approximated in another dialect.
//
//   node test/tmgrammar.test.js
//
// Exit 0 iff every expectation holds.
const fs = require('fs')
const path = require('path')

let oniguruma, vsctm
try {
  oniguruma = require('vscode-oniguruma')
  vsctm = require('vscode-textmate')
} catch (e) {
  // Loudly, not skipped: a syntax test that quietly passes when it did not run
  // is worse than no test.
  console.error('error: vscode-textmate/vscode-oniguruma not installed.')
  console.error('       run `npm install` in tooling/vscode-extension/extension')
  process.exit(2)
}

const GRAMMAR = path.join(__dirname, '..', 'syntaxes', 'mezze.tmLanguage.json')

// `text` is matched against the FIRST token whose text is exactly it, and its
// most specific scope must be `scope`. A `~` prefix matches the first token
// CONTAINING the text instead, for spans the engine leaves unstyled and so
// merges with their neighbours. `null` means "carries no scope of its own" —
// itself an assertion: it is how we pin that a spread is not a member access.
const CASES = [
  ['use std::codec::json', [
    ['use', 'keyword.control'],
    ['std', 'entity.name.namespace'],
    ['codec', 'entity.name.namespace'],
    ['json', 'entity.name.namespace'],
  ]],
  ['let r = Show.show { x }', [
    ['let', 'keyword.control'],
    ['r', 'entity.name.function'],
    ['Show', 'entity.name.type'],
    ['.show', 'entity.name.function.member'],
  ]],
  // Newtype construction: the dot belongs to `.(`, and must not be eaten as
  // the start of a member access.
  ['let u = UserId.(5)', [
    ['UserId', 'entity.name.type'],
    ['5', 'constant.numeric.integer'],
  ]],
  // THE spread regression. `...ys` is not `ys` accessed off something; the
  // `(?<!\.)` guard in #member is the only thing keeping it that way.
  ['let s = [...ys, 1]', [
    ['s', 'entity.name.function'],
    ['~...ys', null],
    ['1', 'constant.numeric.integer'],
  ]],
  ['let rec = { ...base, a = 1 }', [
    ['rec', 'entity.name.function'],
    ['a ', 'variable.other.property'],
  ]],
  ['let n = xs.size {}', [
    ['.size', 'entity.name.function.member'],
  ]],
  // Field ACCESS and method CALL are different scopes, split on whether
  // arguments follow — the one distinction a regex grammar can actually make.
  ['let v = point.x', [
    ['.x', 'variable.other.property'],
  ]],
  ['let t: codec::Entry Bool Int = 0', [
    ['codec', 'entity.name.namespace'],
    ['Entry', 'entity.name.type'],
  ]],
  // `pub` must not swallow the keyword after it (the original cascade bug),
  // and the binding rule must still see the name.
  ['pub let f = 1', [
    ['pub', 'storage.modifier'],
    ['let', 'keyword.control'],
    ['f', 'entity.name.function'],
  ]],
  ['let msg = "total: $(f { x = 1 }) done"', [
    ['"', 'punctuation.definition.string.begin'],
    ['total: ', 'string.quoted.double'],
    ['$(', 'punctuation.section.interpolation.begin'],
    ['x ', 'variable.other.property'],
    [')', 'punctuation.section.interpolation.end'],
  ]],
  ['## a doc comment', [['## a doc comment', 'comment.block.documentation']]],
  ["let id: 'a -> 'a = { x } -> x", [["'a", 'variable.parameter.type']]],
  ['let b = True', [['True', 'constant.language.boolean']]],
  ['let big = 1_000n', [['1_000n', 'constant.numeric.natural']]],
]

function leaf(scopes) {
  for (let i = scopes.length - 1; i >= 0; i--) {
    if (scopes[i] !== 'source.mezze') return scopes[i].replace(/\.mezze$/, '')
  }
  return null
}

// Mezze is highlighted three independent times — here, by the tree-sitter
// grammar (Zed/nvim/Helix), and by website/src/lib/highlight.ts — and nothing
// held them to the same keyword list. That is the drift that actually happens:
// a keyword is added to the language and two of the three never hear about it.
// tree-sitter is the reference because it is generated from the grammar.
function keywordSync() {
  const root = path.join(__dirname, '..', '..', '..', '..')
  const tsGrammar = path.join(root, 'tooling', 'tree-sitter-mezze', 'src', 'grammar.json')
  const webHighlight = path.join(root, 'website', 'src', 'lib', 'highlight.ts')
  if (!fs.existsSync(tsGrammar) || !fs.existsSync(webHighlight)) {
    console.error('error: cannot locate the other two highlighters from ' + root)
    process.exit(2)
  }

  // Every keyword-shaped anonymous token in the generated grammar. `_` is the
  // wildcard pattern, not a keyword.
  const ts = new Set()
  const collect = (o) => {
    if (Array.isArray(o)) return o.forEach(collect)
    if (!o || typeof o !== 'object') return
    if (o.type === 'STRING' && /^[a-z_][a-z0-9_]*$/.test(o.value) && o.value !== '_') ts.add(o.value)
    Object.values(o).forEach(collect)
  }
  collect(JSON.parse(fs.readFileSync(tsGrammar, 'utf8')))

  const grammarSrc = fs.readFileSync(GRAMMAR, 'utf8')
  const tm = new Set()
  for (const m of grammarSrc.matchAll(/"match":\s*"\\\\b\(([a-z|]+)\)\\\\b"/g)) {
    m[1].split('|').forEach((k) => tm.add(k))
  }

  const webSrc = fs.readFileSync(webHighlight, 'utf8')
  const webList = webSrc.split('const KEYWORDS = [')[1]?.split(']')[0] ?? ''
  const web = new Set([...webList.matchAll(/'([a-z_]+)'/g)].map((m) => m[1]))

  let bad = 0
  for (const [name, set] of [['TextMate', tm], ['website', web]]) {
    const missing = [...ts].filter((k) => !set.has(k)).sort()
    const extra = [...set].filter((k) => !ts.has(k)).sort()
    if (missing.length) { bad++; console.log(`FAIL ${name} is missing keyword(s): ${missing.join(' ')}`) }
    if (extra.length) { bad++; console.log(`FAIL ${name} has keyword(s) tree-sitter does not: ${extra.join(' ')}`) }
  }
  console.log(`keyword sync: ${ts.size} keywords, TextMate and website ${bad ? 'DIVERGE' : 'both agree'}`)
  return bad
}

const wasm = fs.readFileSync(require.resolve('vscode-oniguruma/release/onig.wasm')).buffer
const onigLib = oniguruma.loadWASM(wasm).then(() => ({
  createOnigScanner: (p) => new oniguruma.OnigScanner(p),
  createOnigString: (s) => new oniguruma.OnigString(s),
}))
const registry = new vsctm.Registry({
  onigLib,
  loadGrammar: () =>
    Promise.resolve(vsctm.parseRawGrammar(fs.readFileSync(GRAMMAR, 'utf8'), GRAMMAR)),
})

registry.loadGrammar('source.mezze').then((grammar) => {
  if (!grammar) {
    console.error('error: scope source.mezze did not load from ' + GRAMMAR)
    process.exit(2)
  }
  let failures = 0
  let checks = 0
  for (const [src, expectations] of CASES) {
    const r = grammar.tokenizeLine(src, vsctm.INITIAL)
    const toks = r.tokens.map((t) => ({
      text: src.substring(t.startIndex, t.endIndex),
      scope: leaf(t.scopes),
    }))
    for (const [text, want] of expectations) {
      checks++
      const tok = text.startsWith('~')
        ? toks.find((t) => t.text.includes(text.slice(1)))
        : toks.find((t) => t.text === text)
      if (!tok) {
        failures++
        console.log(`FAIL ${JSON.stringify(src)}`)
        console.log(`     no token with text ${JSON.stringify(text)}`)
        console.log(`     got: ${toks.map((t) => JSON.stringify(t.text) + '→' + t.scope).join(' ')}`)
        continue
      }
      const got = tok.scope
      const ok = want === null ? got === null : got === want
      if (!ok) {
        failures++
        console.log(`FAIL ${JSON.stringify(src)}`)
        console.log(`     ${JSON.stringify(text)}: want ${want ?? '(no scope)'}, got ${got ?? '(no scope)'}`)
      }
    }
  }
  console.log('-----------------------------------------')
  console.log(`tmgrammar: ${checks - failures}/${checks} scope assertions hold`)
  failures += keywordSync()
  process.exit(failures === 0 ? 0 : 1)
})
