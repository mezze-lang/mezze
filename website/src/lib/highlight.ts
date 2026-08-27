/**
 * Mezze syntax highlighting.
 *
 * Token-level, mirroring tooling/vscode-extension/…/mezze.tmLanguage.json —
 * same keyword list, same categories. Deliberately not a parser: the grammar
 * it mirrors is token-only for the same reason.
 *
 * Booleans are True/False. (The TextMate grammar matched `true|false`, which
 * never appears in Mezze; fixed in both places.)
 */

const KEYWORDS = [
  'let', 'type', 'newtype', 'effect', 'ability', 'impl', 'use', 'as',
  'if', 'then', 'else', 'match', 'is', 'do', 'perform', 'in',
  'with', 'having', 'where', 'for', 'pub',
]

const esc = (s: string) =>
  s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')

const RE = new RegExp(
  [
    '(##?(?:[ \\t][^\\n]*)?$)',                          // comments
    '("(?:[^"\\\\\\n]|\\\\.)*")',                        // strings
    "('[a-zA-Z_][a-zA-Z0-9_]*)",                         // 'typevars
    '(<[A-Z][A-Za-z0-9_]*(?:\\s*,\\s*[A-Za-z0-9_.]+)*>)', // <Effect> rows
    '\\b(True|False)\\b',                                // booleans
    '\\b([0-9][0-9_]*(?:\\.[0-9][0-9_]*)?n?)\\b',        // numbers
    `\\b(${KEYWORDS.join('|')})\\b`,                     // keywords
    '(?<=\\blet\\s)([a-z_][A-Za-z0-9_]*)',               // binding being defined
    '(\\.[a-z_][A-Za-z0-9_]*)',                          // .dot_methods
    '([a-z_][A-Za-z0-9_]*)(?=\\s*=[^=])',                // record field names
    '\\b([A-Z][A-Za-z0-9_]*)\\b',                        // constructors / types
  ].join('|'),
  'gm',
)

const CLS = [
  'tk-com', 'tk-str', 'tk-tv', 'tk-eff', 'tk-bool',
  'tk-num', 'tk-kw', 'tk-fn', 'tk-mth', 'tk-fld', 'tk-ctor',
]

/** A string literal, with `$(…)` interpolations highlighted as code. */
function highlightString(str: string): string {
  let out = ''
  let last = 0
  for (const m of str.matchAll(/\$\(/g)) {
    // Scan to the matching close paren so nested calls survive.
    let depth = 1
    let j = m.index! + 2
    while (j < str.length && depth > 0) {
      if (str[j] === '(') depth++
      else if (str[j] === ')') depth--
      j++
    }
    if (depth !== 0) break
    out += `<span class="tk-str">${esc(str.slice(last, m.index))}</span>`
    out += `<span class="tk-interp">${esc('$(')}</span>`
    out += highlight(str.slice(m.index! + 2, j - 1))
    out += `<span class="tk-interp">)</span>`
    last = j
  }
  return out + `<span class="tk-str">${esc(str.slice(last))}</span>`
}

export function highlight(src: string): string {
  let out = ''
  let last = 0
  for (const m of src.matchAll(RE)) {
    out += esc(src.slice(last, m.index))
    const gi = m.slice(1).findIndex((g) => g !== undefined)
    out += CLS[gi] === 'tk-str'
      ? highlightString(m[0])
      : `<span class="${CLS[gi]}">${esc(m[0])}</span>`
    last = m.index! + m[0].length
  }
  return out + esc(src.slice(last))
}

/** 1-based source location, as reported by checkMezze. */
export interface Diagnostic {
  location?: {
    startLine?: number
    startCol?: number
    endLine?: number
    endCol?: number
  }
}

/**
 * The source with one diagnostic span underlined.
 *
 * The marked line is escaped rather than highlighted: the underline wraps a
 * character range that does not respect token boundaries, so highlighting it
 * would mean splitting spans mid-token. Falls back to plain highlighting when
 * the location is missing or degenerate.
 */
export function withError(src: string, d: Diagnostic | null | undefined): string {
  const loc = d?.location
  if (!loc?.startLine) return highlight(src)

  const lines = src.split('\n')
  const li = loc.startLine - 1
  if (li < 0 || li >= lines.length) return highlight(src)

  const line = lines[li]
  const start = Math.max(0, (loc.startCol ?? 1) - 1)
  const end = loc.endLine === loc.startLine
    ? Math.min(line.length, loc.endCol ?? line.length)
    : line.length
  if (end <= start) return highlight(src)

  const head = lines.slice(0, li).join('\n')
  const tail = lines.slice(li + 1).join('\n')
  return (head ? esc(head) + '\n' : '')
    + esc(line.slice(0, start))
    + `<span class="tk-err">${esc(line.slice(start, end))}</span>`
    + esc(line.slice(end))
    + (tail ? '\n' + esc(tail) : '') + '\n'
}
