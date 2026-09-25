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

/** HTML-escape. Exported because callers building their own markup need it. */
export const esc = (s: string) =>
  s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')

const RE = new RegExp(
  [
    '(#\\{[^}]*\\})',                                  // #{ metadata }
    '(##?(?:[ \\t][^\\n]*)?$)',                          // comments
    // Strings, which may span lines. The `\$\([^)]*\)` alternative keeps an
    // interpolation together even when it contains its own quotes: without it
    // `"€$(price_of { item = "olive-oil" })"` ended at the quote before
    // `olive-oil`, and every token after that was coloured as if the string
    // had closed. Nested parens inside one interpolation are not handled —
    // `[^)]*` stops at the first `)` — which is the same limit highlightString
    // already has.
    // The interpolation branch has to come FIRST: `[^"\\]` would otherwise
    // match the bare `$` and the scan would fall back to ending at the next
    // quote, which is the bug this is here to fix.
    '("(?:\\$\\([^)]*\\)|[^"\\\\]|\\\\.)*")',
    "('[a-zA-Z_][a-zA-Z0-9_]*)",                         // 'typevars
    '(<[A-Z][A-Za-z0-9_]*(?:\\s*,\\s*[A-Za-z0-9_.]+)*>)', // <Effect> rows
    '\\b(True|False)\\b',                                // booleans
    '\\b([0-9][0-9_]*(?:\\.[0-9][0-9_]*)?n?)\\b',        // numbers
    `\\b(${KEYWORDS.join('|')})\\b`,                     // keywords
    '(?<=\\blet\\s)([a-z_][A-Za-z0-9_]*)',               // binding being defined
    // The lookbehind is what keeps a SPREAD from rendering as a member access:
    // in `[...ys, 1]` and `{ ...base, a = 1 }` the third dot was matching and
    // `ys`/`base` came out coloured as methods. A spread is not a member
    // access, and this highlighter gives operators and plain variable
    // references no colour at all — so declining to match is the whole fix.
    '(?<!\\.)(\\.[a-z_][A-Za-z0-9_]*)',                    // .dot_methods
    '([a-z_][A-Za-z0-9_]*)(?=\\s*=[^=])',                // record field names
    '\\b([A-Z][A-Za-z0-9_]*)\\b',                        // constructors / types
  ].join('|'),
  'gm',
)

const CLS = [
  // Order matches the alternation in RE, one class per capture group.
  'tk-com', 'tk-com', 'tk-str', 'tk-tv', 'tk-eff', 'tk-bool',
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
export function withError(
  src: string,
  d: Diagnostic | null | undefined,
  message?: string,
): string {
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

  // Highlight the whole file first, then wrap the offending span. Escaping
  // everything instead — as this used to — dropped syntax colouring the moment
  // a diagnostic appeared, so the editor went plain black on any error.
  const before = lines.slice(0, li).join('\n')
  const title = message ? ` title="${esc(message)}"` : ''

  return (before ? highlight(before) + '\n' : '')
    + highlight(line.slice(0, start))
    + `<span class="tk-err"${title}>${highlight(line.slice(start, end))}</span>`
    + highlight(line.slice(end))
    + (li + 1 < lines.length ? '\n' + highlight(lines.slice(li + 1).join('\n')) : '')
    + '\n'
}
