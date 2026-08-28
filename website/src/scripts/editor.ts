/**
 * Wiring for the Editor component.
 *
 * Scoped to one root element rather than the document, so a page may hold
 * several editors. Returns a handle the host can drive — the tour uses it to
 * swap the source when the lesson changes.
 */
import { esc, highlight, withError } from '../lib/highlight'
import { loadMezze } from './mezze-runtime'

/** How long typing must pause before diagnostics re-run. */
const LIVE_CHECK_MS = 600
/** typeAt re-typechecks, so the hover is debounced rather than per-pixel. */
const HOVER_MS = 120

export interface EditorHandle {
  /** Replace the source and repaint. */
  setSource(code: string): void
  /** Write a line into the output pane. `kind` maps to a status colour. */
  show(text: string, kind?: 'ok' | 'err' | 'warn'): void
  /** True once the runtime has loaded and the controls are live. */
  isReady(): boolean
}

export function mountEditor(root: HTMLElement): EditorHandle {
  const q = <T extends Element>(sel: string) => root.querySelector(sel) as T
  const srcEl = q<HTMLTextAreaElement>('.ed-src')
  const codeEl = q<HTMLElement>('.ed-code')
  const preEl = q<HTMLElement>('.ed-hl')
  const outEl = q<HTMLElement>('.ed-out')
  const statusEl = q<HTMLElement>('.ed-status')
  const btns = Array.from(root.querySelectorAll('.ed-bar button')) as HTMLButtonElement[]

  let ready = false

  const paint = (html?: string) => {
    codeEl.innerHTML = html !== undefined ? html : highlight(srcEl.value)
  }
  const show = (text: string, kind?: string) => {
    outEl.textContent = text
    outEl.className = 'ed-out ' + (kind || '')
  }
  // Only vertical: the layers wrap rather than scroll sideways.
  const syncScroll = () => {
    preEl.scrollTop = srcEl.scrollTop
  }

  // ---- live diagnostics -----------------------------------------------
  // Typecheck-only, so no user code runs while typing.
  let liveTimer: number | undefined
  const liveCheck = () => {
    if (typeof window.checkMezze !== 'function') return
    try {
      const res = window.checkMezze(srcEl.value) as any
      const ds = res?.diagnostics ?? []
      paint(ds.length ? withError(srcEl.value, ds[0]) : undefined)
    } catch {
      // Keep the last good paint rather than clearing the editor.
    }
  }

  srcEl.addEventListener('input', () => {
    paint()
    clearTimeout(liveTimer)
    liveTimer = window.setTimeout(liveCheck, LIVE_CHECK_MS)
  })
  srcEl.addEventListener('scroll', syncScroll)
  srcEl.addEventListener('keydown', (e) => {
    if (e.key !== 'Tab') return
    e.preventDefault()
    const a = srcEl.selectionStart
    const b = srcEl.selectionEnd
    srcEl.value = srcEl.value.slice(0, a) + '  ' + srcEl.value.slice(b)
    srcEl.selectionStart = srcEl.selectionEnd = a + 2
    paint()
  })

  // ---- hover types ------------------------------------------------------
  // typeAt walks the TypedAST — the same utility the LSP hover uses — so this
  // resolves locals, parameters and subexpressions, not just top-level
  // bindings. Positions are 1-based, matching Loc.
  const tip = document.createElement('div')
  tip.className = 'hover-tip'
  tip.hidden = true
  document.body.appendChild(tip)

  /**
   * Width of one character in the editor, in px.
   *
   * Measured with a probe inside the editor's own box, copying the font
   * metrics individually: the `font` shorthand drops letter-spacing, and a
   * probe appended to <body> can inherit a different font stack. Either
   * mistake under-measures, which shows up as hover landing on the wrong
   * column and Format wrapping too wide.
   */
  const measureCharWidth = (): number => {
    const cs = getComputedStyle(srcEl)
    const probe = document.createElement('span')
    probe.style.cssText = 'position:absolute;visibility:hidden;white-space:pre'
    probe.style.fontFamily = cs.fontFamily
    probe.style.fontSize = cs.fontSize
    probe.style.fontWeight = cs.fontWeight
    probe.style.letterSpacing = cs.letterSpacing
    probe.style.fontFeatureSettings = cs.fontFeatureSettings
    probe.textContent = 'x'.repeat(100)
    srcEl.parentElement!.appendChild(probe)
    const w = probe.getBoundingClientRect().width / 100
    probe.remove()
    return w
  }

  /**
   * How many characters fit across the editor right now.
   *
   * The formatter is fixed at 100 columns for the CLI, which overflows this
   * pane. Measuring here lets Format wrap to what the reader actually has.
   *
   * This overshot twice before: once when the textarea still scrolled
   * horizontally and reported its content width, and again when the width came
   * from the container while the padding came from the textarea.
   */
  const editorCols = (): number => {
    const w = measureCharWidth()
    const cs = getComputedStyle(srcEl)

    // Measure the textarea's own content box. Taking the container's width and
    // the textarea's padding mixes two boxes and overshoots by the container's
    // border. Now that the layers wrap rather than scroll, the textarea's width
    // tracks the pane and cannot be inflated by a long line.
    const usable = srcEl.clientWidth
      - parseFloat(cs.paddingLeft)
      - parseFloat(cs.paddingRight)

    if (!w || !usable) return 80
    // Two columns of slack so a trailing character never tips onto a new line.
    return Math.max(40, Math.min(100, Math.floor(usable / w) - 2))
  }

  /**
   * 1-based (line, col) under the pointer, or null if not over a word.
   *
   * Asks the browser which character is under the cursor rather than dividing
   * by a character width: lines wrap, so a visual row is not a source line and
   * the arithmetic version resolved the wrong token after the first wrap.
   * Reads from the highlight layer, whose text matches the textarea exactly.
   */
  const posAt = (x: number, y: number): [number, number] | null => {
    const r = srcEl.getBoundingClientRect()
    if (x < r.left || x > r.right || y < r.top || y > r.bottom) return null

    const doc = document as Document & {
      caretPositionFromPoint?: (x: number, y: number) => { offsetNode: Node; offset: number } | null
    }
    const caret = doc.caretPositionFromPoint?.(x, y)
    if (!caret || !codeEl.contains(caret.offsetNode)) return null

    const walker = document.createTreeWalker(codeEl, NodeFilter.SHOW_TEXT)
    let offset = 0
    let node: Node | null
    while ((node = walker.nextNode())) {
      if (node === caret.offsetNode) {
        offset += caret.offset
        break
      }
      offset += node.textContent?.length ?? 0
    }
    if (!node) return null

    const text = codeEl.textContent ?? ''
    if (!/[A-Za-z0-9_]/.test(text[offset] ?? '')) return null

    const before = text.slice(0, offset)
    return [before.split('\n').length, offset - before.lastIndexOf('\n')]
  }

  let hoverTimer: number | undefined
  let lastKey = ''
  const hideTip = () => {
    tip.hidden = true
    lastKey = ''
  }

  srcEl.addEventListener('mousemove', (e) => {
    const p = posAt(e.clientX, e.clientY)
    if (!p) return hideTip()

    const key = p[0] + ':' + p[1]
    if (key === lastKey) {
      tip.style.left = e.clientX + 12 + 'px'
      tip.style.top = e.clientY + 18 + 'px'
      return
    }

    clearTimeout(hoverTimer)
    const [line, col] = p
    const x = e.clientX
    const y = e.clientY
    hoverTimer = window.setTimeout(() => {
      if (typeof window.typeAt !== 'function') return
      let t = ''
      try {
        t = String(window.typeAt(srcEl.value, line, col))
      } catch {
        // No type at this position; fall through to hide.
      }
      if (!t) return hideTip()

      lastKey = key
      const i = t.indexOf(' : ')
      // The type is Mezze, so run it through the same highlighter as the code.
      tip.innerHTML = i > 0
        ? `<code class="ht-name">${esc(t.slice(0, i))}</code>` +
          `<code class="ht-sep"> : </code>` +
          `<code>${highlight(t.slice(i + 3))}</code>`
        : `<code>${highlight(t)}</code>`
      tip.style.left = x + 12 + 'px'
      tip.style.top = y + 18 + 'px'
      tip.hidden = false
    }, HOVER_MS)
  })
  srcEl.addEventListener('mouseleave', hideTip)
  srcEl.addEventListener('scroll', hideTip)
  srcEl.addEventListener('input', hideTip)

  // ---- actions ----------------------------------------------------------
  /** Renders diagnostics; returns true when the source is clean. */
  const report = (res: any): boolean => {
    const ds = res?.diagnostics ?? []
    if (!ds.length) {
      paint()
      show('No errors.', 'ok')
      return true
    }
    paint(withError(srcEl.value, ds[0]))
    show(
      ds.map((d: any) => {
        const l = d.location
        const at = l?.startLine ? `${l.startLine}:${l.startCol}` : '?'
        const hints = (d.hints ?? []).map((h: string) => `\n    hint: ${h}`).join('')
        return `${d.severity} at ${at}\n  ${d.message}${hints}`
      }).join('\n\n'),
      'err',
    )
    return false
  }

  /** Disables the controls for the duration, since the call is synchronous. */
  const act = (fn: () => void) => () => {
    btns.forEach((b) => (b.disabled = true))
    show('working…')
    // Yield first so the DOM repaints before the wasm call blocks.
    setTimeout(() => {
      try {
        fn()
      } catch (e) {
        show('Internal error: ' + e, 'err')
      }
      btns.forEach((b) => (b.disabled = !ready))
    }, 0)
  }

  const ACTIONS: Record<string, () => void> = {
    run: () => {
      if (!report(window.checkMezze!(srcEl.value))) return
      const out = String(window.runMezze!(srcEl.value))
      const denied = out.includes('Not supported in the browser')
      show(out, denied ? 'warn' : /^(TYPE ERROR|ERROR)/.test(out) ? 'err' : '')
    },
    check: () => void report(window.checkMezze!(srcEl.value)),
    fmt: () => {
      srcEl.value = String(window.formatMezze!(srcEl.value, editorCols()))
      paint()
      show('Formatted.', 'ok')
    },
  }
  btns.forEach((b) => (b.onclick = act(ACTIONS[b.dataset.act!])))

  // ---- runtime ----------------------------------------------------------
  paint()
  loadMezze()
    .then((rt) => {
      ready = true
      statusEl.textContent = `runtime ready (${rt.loadSeconds.toFixed(1)}s)`
      btns.forEach((b) => (b.disabled = false))
      show('Ready. Press Run.')
    })
    .catch(() => {
      ready = false
      btns.forEach((b) => (b.disabled = true))
      statusEl.textContent = 'runtime failed to load'
      show(
        'The Mezze runtime did not load.\n\n' +
          'This build needs a browser with WebAssembly 3.0 (GC + exception handling).',
        'err',
      )
    })

  return {
    setSource(code) {
      srcEl.value = code
      paint()
      show(ready ? 'Ready. Press Run.' : 'Loading the compiler…')
    },
    show,
    isReady: () => ready,
  }
}
