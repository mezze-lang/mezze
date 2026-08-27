/**
 * Editor runtime: highlighting, the three runtime calls, and inline error marks.
 *
 * Highlighting mirrors tooling/vscode-extension/.../mezze.tmLanguage.json —
 * same keyword list, same token classes. Booleans are True/False (the grammar
 * said true/false, which never appears in Mezze; fixed in both places).
 */

const KEYWORDS = [
  'let', 'type', 'newtype', 'effect', 'ability', 'impl', 'use', 'as',
  'if', 'then', 'else', 'match', 'is', 'do', 'perform', 'in',
  'with', 'having', 'where', 'for', 'pub',
]

const esc = (s: string) =>
  s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')

/** Token-level highlighter. Deliberately not a parser — same scope as the
 *  TextMate grammar, which is token-only for the same reason. */
function highlight(src: string): string {
  const pattern = new RegExp(
    [
      '(##?(?:[ \\t][^\\n]*)?)',                 // comments
      '("(?:[^"\\\\\\n]|\\\\.)*")',              // strings
      "('[a-zA-Z_][a-zA-Z0-9_]*)",               // type variables
      '(<[A-Za-z_][A-Za-z0-9_, ]*>)',            // effect rows
      '\\b(True|False)\\b',                      // booleans
      '\\b([0-9][0-9_]*(?:\\.[0-9][0-9_]*)?n?)\\b', // numbers
      `\\b(${KEYWORDS.join('|')})\\b`,           // keywords
      '\\b([A-Z][A-Za-z0-9_]*)\\b',              // constructors / types
    ].join('|'),
    'g',
  )
  const cls = ['t-com', 't-str', 't-tv', 't-eff', 't-ctor', 't-num', 't-kw', 't-ctor']
  let out = ''
  let last = 0
  for (const m of src.matchAll(pattern)) {
    const i = m.index!
    out += esc(src.slice(last, i))
    const gi = m.slice(1).findIndex((g) => g !== undefined)
    out += `<span class="${cls[gi]}">${esc(m[0])}</span>`
    last = i + m[0].length
  }
  return out + esc(src.slice(last))
}

/** Underline one span, given 1-based line/col from checkMezze. */
function markError(src: string, d: any): string {
  const loc = d?.location
  if (!loc?.startLine) return highlight(src)
  const lines = src.split('\n')
  const li = loc.startLine - 1
  if (li < 0 || li >= lines.length) return highlight(src)
  const line = lines[li]
  const s = Math.max(0, (loc.startCol ?? 1) - 1)
  const e = Math.min(line.length, loc.endLine === loc.startLine ? loc.endCol ?? line.length : line.length)
  if (e <= s) return highlight(src)
  const before = lines.slice(0, li).join('\n')
  const after = lines.slice(li + 1).join('\n')
  return (
    (before ? highlight(before) + '\n' : '') +
    highlight(line.slice(0, s)) +
    `<span class="t-err">${highlight(line.slice(s, e))}</span>` +
    highlight(line.slice(e)) +
    (after ? '\n' + highlight(after) : '')
  )
}

declare global {
  interface Window {
    runMezze?: (s: string) => string
    checkMezze?: (s: string) => any
    formatMezze?: (s: string) => string
  }
}

export function initEditor(root: HTMLElement, initial: string) {
  const srcEl = root.querySelector('.ed-src') as HTMLTextAreaElement
  const hlEl = root.querySelector('.ed-hl code') as HTMLElement
  const outEl = root.querySelector('.ed-out') as HTMLElement
  const statusEl = root.querySelector('.ed-status') as HTMLElement
  const btns = Array.from(root.querySelectorAll('.ed-bar button')) as HTMLButtonElement[]

  const paint = (marked?: string) => {
    hlEl.innerHTML = marked ?? highlight(srcEl.value)
  }
  const show = (text: string, kind = '') => {
    outEl.textContent = text
    outEl.className = 'ed-out ' + kind
  }
  const sync = () => {
    const pre = hlEl.parentElement!
    pre.scrollTop = srcEl.scrollTop
    pre.scrollLeft = srcEl.scrollLeft
  }

  srcEl.value = initial
  paint()
  srcEl.addEventListener('input', () => paint())
  srcEl.addEventListener('scroll', sync)

  // Tab inserts two spaces instead of leaving the field.
  srcEl.addEventListener('keydown', (e) => {
    if (e.key !== 'Tab') return
    e.preventDefault()
    const { selectionStart: a, selectionEnd: b } = srcEl
    srcEl.value = srcEl.value.slice(0, a) + '  ' + srcEl.value.slice(b)
    srcEl.selectionStart = srcEl.selectionEnd = a + 2
    paint()
  })

  const setLoaded = (ok: boolean, secs: string) => {
    if (!ok) {
      statusEl.textContent = 'runtime failed to load'
      show(
        'The Mezze runtime did not load.\n\nThis build needs a browser with WebAssembly 3.0 (GC + exception handling).',
        'err',
      )
      return
    }
    statusEl.textContent = `runtime ready (${secs}s)`
    btns.forEach((b) => (b.disabled = false))
    show('Ready. Press Run.')
  }

  const renderDiagnostics = (res: any) => {
    const diags = res?.diagnostics ?? []
    if (!diags.length) {
      paint()
      return show('No errors.', 'ok')
    }
    paint(markError(srcEl.value, diags[0]))
    show(
      diags
        .map((d: any) => {
          const l = d.location
          const at = l?.startLine ? `${l.startLine}:${l.startCol}` : '?'
          const hints = (d.hints ?? []).map((h: string) => `\n    hint: ${h}`).join('')
          return `${d.severity} at ${at}\n  ${d.message}${hints}`
        })
        .join('\n\n'),
      'err',
    )
  }

  const act = (fn: () => void) => () => {
    btns.forEach((b) => (b.disabled = true))
    show('working…')
    // Yield so the DOM repaints before the synchronous wasm call blocks.
    setTimeout(() => {
      try {
        fn()
      } catch (e) {
        show('Internal error: ' + e, 'err')
      }
      btns.forEach((b) => (b.disabled = false))
    }, 0)
  }

  const handlers: Record<string, () => void> = {
    run: () => {
      // Run typechecks too, so a type error marks the offending span.
      const res = window.checkMezze!(srcEl.value)
      if ((res?.diagnostics ?? []).length) return renderDiagnostics(res)
      paint()
      const out = String(window.runMezze!(srcEl.value))
      const denied = out.includes('Not supported in the browser')
      show(out, denied ? 'warn' : /^(TYPE ERROR|ERROR)/.test(out) ? 'err' : '')
    },
    check: () => renderDiagnostics(window.checkMezze!(srcEl.value)),
    fmt: () => {
      srcEl.value = String(window.formatMezze!(srcEl.value))
      paint()
      show('Formatted.', 'ok')
    },
  }

  btns.forEach((b) => (b.onclick = act(handlers[b.dataset.act!])))

  return {
    load: (text: string) => {
      srcEl.value = text
      paint()
      show('Ready. Press Run.')
    },
    setLoaded,
  }
}
