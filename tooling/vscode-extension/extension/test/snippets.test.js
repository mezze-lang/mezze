#!/usr/bin/env node
//
// Every snippet must produce code the grammar accepts.
//
// Six of the 45 did not. `ability` emitted `ability Name 'a where {…}` and
// `effect` emitted `effect Name {…}` — the two keywords exactly backwards,
// because abilities gained `for` and effects gained `where` and the snippets
// were never updated. `funability` put `with` after the return type (P0120
// exists to say so), `matchm` separated alternative patterns with `,` instead
// of `|`, and `lambdat` emitted a type spliced onto `= body`. Pressing Tab on
// any of them inserted code that does not compile.
//
//   node test/snippets.test.js
//
// Checked against the tree-sitter grammar, not the compiler: it is in-repo, it
// is fast, and a snippet is a SYNTAX template. Snippets whose placeholder
// defaults are prose rather than code, or that are expression fragments meant
// to be typed into a larger expression, are wrapped or exempted below — each
// with the reason, since an unexplained exemption is how the six got in.
const fs = require('fs')
const path = require('path')
const { execFileSync } = require('child_process')

const EXT = path.join(__dirname, '..')
const GRAMMAR_DIR = path.join(EXT, '..', '..', 'tree-sitter-mezze')
const SNIPPETS = path.join(EXT, 'snippets', 'mezze.json')

// Expression fragments: the body is an expression to be typed INSIDE something
// else, so it cannot stand alone as a declaration. Wrapped in a binding rather
// than skipped — that still proves the expression itself parses.
const EXPRESSION_SNIPPETS = new Set([
  // `1//2` — a fraction LITERAL, so it is an expression, not a declaration.
  // Its placeholder defaults have to be numerals: `//` does not join
  // identifiers, so the old `numerator//denominator` did not parse either.
  'Fraction Literal',
])

function resolvePlaceholders(body) {
  let s = Array.isArray(body) ? body.join('\n') : body
  s = s.replace(/\$\{\d+\|([^,|}]+)[^}]*\|\}/g, '$1') // ${1|a,b|} -> a
  s = s.replace(/\$\{\d+:([^{}]*)\}/g, '$1')          // ${1:x}    -> x
  s = s.replace(/\$\{\d+\}/g, 'x')
  s = s.replace(/\$0/g, '')
  s = s.replace(/\$\d+/g, 'x')
  return s
}

const snippets = JSON.parse(fs.readFileSync(SNIPPETS, 'utf8'))
const tmp = fs.mkdtempSync(path.join(require('os').tmpdir(), 'mezze-snip-'))

let failures = 0
let checked = 0
for (const [name, s] of Object.entries(snippets)) {
  let src = resolvePlaceholders(s.body)
  if (EXPRESSION_SNIPPETS.has(name)) src = `let snippet_probe = ${src}`
  const file = path.join(tmp, 'snippet.mz')
  fs.writeFileSync(file, src + '\n')
  checked++
  let out
  try {
    out = execFileSync('tree-sitter', ['parse', '-q', file], {
      cwd: GRAMMAR_DIR,
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'pipe'],
    })
  } catch (e) {
    if (e.code === 'ENOENT') {
      console.error('error: tree-sitter CLI not on PATH')
      process.exit(2)
    }
    out = (e.stdout || '') + (e.stderr || '')
    failures++
    console.log(`FAIL ${name}  (prefix: ${s.prefix})`)
    console.log(src.split('\n').map((l) => '     ' + l).join('\n'))
    const where = out.trim().split('\n').find((l) => l.includes('ERROR') || l.includes('error'))
    if (where) console.log(`     -> ${where.trim()}`)
  }
}

fs.rmSync(tmp, { recursive: true, force: true })
console.log('-----------------------------------------')
console.log(`snippets: ${checked - failures}/${checked} parse`)
process.exit(failures === 0 ? 0 : 1)
