/**
 * Strip LSP snippet syntax down to plain text.
 *
 * Completion candidates may be marked as snippets, carrying insertText in the
 * LSP placeholder form: `insert {k = ${1:k}, v = ${2:v}}`. A real LSP client
 * turns those into tab stops the reader tabs through. The browser editor has
 * no tab stops, so it keeps each placeholder's default text and drops the
 * scaffolding around it.
 *
 * Lives here rather than inside the editor so it can be tested directly —
 * the nesting rule below is easy to get wrong and was, once.
 */
export function plainFromSnippet(t: string): string {
  let out = ''
  for (let i = 0; i < t.length; i++) {
    const ch = t[i]
    if (ch === '\\' && t[i + 1] === '$') { out += '$'; i++; continue }
    if (ch !== '$') { out += ch; continue }
    if (t[i + 1] === '{') {
      // Scan to the MATCHING brace: a default can itself contain braces
      // (`${1:{ x } -> x}`), so stopping at the first one would eat it.
      let depth = 1
      let j = i + 2
      while (j < t.length && depth > 0) {
        if (t[j] === '{') depth++
        else if (t[j] === '}') depth--
        if (depth > 0) j++
      }
      const body = t.slice(i + 2, j)
      const colon = body.indexOf(':')
      const bar = body.indexOf('|')
      // Recurse on the default. A placeholder default is itself snippet text
      // and routinely nests another tabstop: the engine emits
      // `map {f = ${1:{x} -> $0}}` for a lambda argument, so returning the
      // default verbatim left that inner `$0` sitting in the buffer.
      out += colon >= 0 ? plainFromSnippet(body.slice(colon + 1))
           : bar >= 0 ? plainFromSnippet(body.slice(bar + 1).split(/[,|]/)[0])
           : ''
      i = j
      continue
    }
    let j = i + 1
    while (j < t.length && t[j] >= '0' && t[j] <= '9') j++
    if (j > i + 1) { i = j - 1; continue }
    out += '$'
  }
  return out
}
