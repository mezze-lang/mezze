/**
 * Wiring for the Editor component.
 *
 * Scoped to one root element rather than the document, so a page may hold
 * several editors. Returns a handle the host can drive — the taste uses it to
 * swap the source when the lesson changes.
 */
import { esc, highlight, withError } from '../lib/highlight'
import { plainFromSnippet } from '../lib/snippet'
import { renderHover } from '../lib/hover'
import { hideHoverTip, moveHoverTip, showHoverTip } from './hover-tip'
import {
  completionKey, hideCompletions, showCompletions,
} from './completion-popup'
import type { Completion } from './mezze-runtime'
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
  // Guard against double-mounting: a page that drives its own editor (the
  // taste) calls this directly, and autoMountEditors() also sweeps the document.
  if (root.dataset.mounted === '1') {
    return (root as any).__mezzeHandle as EditorHandle
  }
  root.dataset.mounted = '1'
  const q = <T extends Element>(sel: string) => root.querySelector(sel) as T
  const srcEl = q<HTMLTextAreaElement>('.ed-src')
  const codeEl = q<HTMLElement>('.ed-code')
  const preEl = q<HTMLElement>('.ed-hl')
  const outEl = q<HTMLElement>('.ed-out')
  const statusEl = q<HTMLElement>('.ed-status')
  const btns = Array.from(root.querySelectorAll('.ed-bar button')) as HTMLButtonElement[]

  let ready = false
  /** Diagnostics from the last check, for hover to report on. */
  let diags: any[] = []

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
      diags = res?.diagnostics ?? []
      paint(diags.length ? withError(srcEl.value, diags[0], diags[0].message) : undefined)
    } catch {
      // Keep the last good paint rather than clearing the editor.
    }
  }


  // .ed-hl/.ed-src carry .9rem of padding top and bottom; codeEl measures the
  // text alone, so the padding is added back here.
  const BASE_PX = parseFloat(getComputedStyle(document.documentElement).fontSize) || 16
  const PANE_PAD_PX = 1.8 * BASE_PX
  const LEADING_PX = 2

  /**
   * Keep --lines in step with the source.
   *
   * Where a host sizes the code pane from its line count (posts do; the
   * landing page does not, since its tabs must not resize as you switch),
   * typing past the original last line would otherwise start scrolling a
   * pane the reader can see the bottom of. Growing the box instead keeps
   * the whole snippet visible, which is the point of an editor sitting in
   * the middle of prose.
   */
  const sizeToContent = () => {
    const host = root.closest<HTMLElement>('[style*="--h"]')
    if (!host) return
    // Measure the rendered text rather than counting source lines. The panes
    // wrap (white-space: pre-wrap), so on a narrow screen a 56-char line takes
    // two rows or more; counting "\n" undercounts the height and the pane
    // scrolls even though it was sized to fit.
    //
    // Measure codeEl, NOT preEl or srcEl: both of those are absolutely
    // positioned at inset 0, so their scrollHeight is capped by the very box
    // this is computing. Reading them lets the height shrink but never grow,
    // which left the pane scrolling after Format made the source taller.
    //
    // Set the pixel height straight from the measurement. Converting to a row
    // count and letting CSS multiply it back rounded twice, and a pane a
    // fraction of a line short still shows a scrollbar with nothing to scroll.
    // ceil covers the sub-pixel remainder, and LEADING_PX covers the half-leading
    // the browser puts below the final line box. codeEl's rect stops at the
    // text; the pane's scroll height does not, so without this every editor
    // ends up about two pixels short and shows a scrollbar with nothing in it.
    const h = Math.ceil(codeEl.getBoundingClientRect().height + PANE_PAD_PX + LEADING_PX)
    host.style.setProperty('--h', h + 'px')

    // Resizing the pane can leave the two layers scrolled to different
    // offsets: growing the box stops the textarea needing to scroll, so its
    // scrollTop drops to 0, while the highlight layer keeps whatever it had.
    // The reader then sees text from one layer and gets character offsets
    // from the other, which shows up as hover reporting the line above.
    // syncScroll only runs on a scroll event, and this is not one.
    requestAnimationFrame(syncScroll)
  }

  srcEl.addEventListener('input', () => {
    paint()
    sizeToContent()
    askCompletions()
    clearTimeout(liveTimer)
    liveTimer = window.setTimeout(liveCheck, LIVE_CHECK_MS)
  })
  srcEl.addEventListener('scroll', syncScroll)

  // Wrapping depends on the pane's width, so a rotation or a window drag
  // changes how many rows the same source needs. Without this the pane keeps
  // the height it was given at the old width.
  if (typeof ResizeObserver !== 'undefined') {
    new ResizeObserver(() => sizeToContent()).observe(root)
  }
  // Leaving the editor is a natural point to validate: the reader has stopped
  // typing, and it costs a typecheck rather than a run.
  // Leaving the editor is the moment to say what is wrong. While typing,
  // liveCheck only paints squiggles — a message on every keystroke would
  // flicker through half-finished states. On blur the reader has stopped, so
  // report properly: same rendering as pressing Check.
  srcEl.addEventListener('blur', () => {
    hideCompletions()
    if (typeof window.checkMezze !== 'function') return
    try {
      report(window.checkMezze(srcEl.value))
    } catch {
      // Leave the last good state rather than clearing the editor.
    }
  })
  srcEl.addEventListener('keydown', (e) => {
    // The popup owns arrows, Enter, Tab and Escape while it is open.
    // Let the browser's own undo/redo through untouched: intercepting the
    // keydown at all breaks the textarea's native history.
    if ((e.metaKey || e.ctrlKey) && (e.key === 'z' || e.key === 'y')) {
      hideCompletions()
      return
    }
    if (completionKey(e)) { e.preventDefault(); return }
    if (e.key === ' ' && e.ctrlKey) { e.preventDefault(); askCompletions(true); return }
    if (e.key !== 'Tab') return
    e.preventDefault()
    replaceRange(srcEl.selectionStart, srcEl.selectionEnd, '  ')
    paint()
  })

  // ---- completion -------------------------------------------------------

  /** 1-based caret position, matching what completeMezze expects. */
  const caretPos = (): [number, number] => {
    const before = srcEl.value.slice(0, srcEl.selectionStart)
    return [before.split('\n').length, srcEl.selectionStart - before.lastIndexOf('\n')]
  }

  /**
   * Where to put the popup, in viewport coordinates.
   *
   * Derived from the caret's line and column rather than measured: a textarea
   * exposes no caret rect, but both layers share their metrics, so a column
   * times the character width lands on the right glyph.
   */
  const caretXY = (line: number, col: number): [number, number] => {
    const r = srcEl.getBoundingClientRect()
    const cs = getComputedStyle(srcEl)
    return [
      r.left + parseFloat(cs.paddingLeft) + (col - 1) * measureCharWidth(),
      r.top + parseFloat(cs.paddingTop) + (line - 1) * parseFloat(cs.lineHeight) - srcEl.scrollTop,
    ]
  }



  /**
   * Replace a span with text, preserving native undo.
   *
   * Assigning srcEl.value wipes the textarea's undo stack outright, which is
   * why Cmd+Z did nothing after accepting a completion or pressing Tab.
   * setRangeText with 'preserve'/'end' is an editing command the browser
   * records, so its own history keeps working. execCommand('insertText') is
   * the older equivalent; setRangeText is supported everywhere we target.
   */
  const replaceRange = (start: number, end: number, text: string) => {
    srcEl.focus()
    srcEl.setSelectionRange(start, end)
    srcEl.setRangeText(text, start, end, 'end')
  }

  /** Buffer offset of a 0-based line/col, as the engine reports them. */
  const offsetOf = (line: number, col: number): number => {
    const lines = srcEl.value.split('\n')
    let n = 0
    for (let i = 0; i < line && i < lines.length; i++) n += lines[i].length + 1
    return n + col
  }

  const applyCompletion = (c: Completion) => {
    const caret = srcEl.selectionStart
    const raw = c.insertText || c.label
    const text = c.snippet ? plainFromSnippet(raw) : raw

    // Prefer the engine's own replacement range. It knows what the item is
    // replacing: for a module path that is the whole `std::eff` typed so far,
    // where the fragment fallback below would keep `std::` and append the
    // full path after it. The LSP honours the same field.
    let start: number
    let end = caret
    if (c.edit) {
      start = offsetOf(c.edit.startLine, c.edit.startCol)
      end = offsetOf(c.edit.endLine, c.edit.endCol)
    } else {
      // No range given: replace the identifier fragment already typed, so
      // accepting `to_str` after `n.to` does not leave `n.toto_str`.
      const frag = srcEl.value.slice(0, caret).match(/[A-Za-z0-9_]*$/)?.[0] ?? ''
      start = caret - frag.length
    }

    replaceRange(start, end, text)
    paint()
    sizeToContent()
  }

  let cmpTimer: number | undefined
  /** force: Ctrl+Space, which opens the menu whatever precedes the caret. */
  const askCompletions = (force = false) => {
    clearTimeout(cmpTimer)
    cmpTimer = window.setTimeout(() => {
      if (!ready || typeof window.completeMezze !== 'function') return

      // The engine answers a completion request at ANY position, including a
      // bare cursor on a blank line, where it returns the entire keyword list.
      // In an editor embedded in prose that reads as noise, so this asks only
      // in the four positions where a suggestion is clearly wanted:
      //
      //   .          a method or field on the receiver to the left
      //   { or ,     an argument name inside a call's record
      //   use …      a module path
      //   match … is an arm for the scrutinee's cases
      //
      // Anything else — mid-expression, after a complete statement, on a fresh
      // line — stays quiet. Ctrl+Space overrides and asks regardless.
      const upto = srcEl.value.slice(0, srcEl.selectionStart)
      const curLine = upto.slice(upto.lastIndexOf('\n') + 1)

      // The word fragment already typed, if any. A trigger still counts once
      // the reader has started typing after it (`n.to`, `use std::ef`).
      const head = curLine.replace(/[A-Za-z0-9_]*$/, '')
      const trigger = head.trimEnd().slice(-1)

      // Two positions only, both of which resolve to something concrete:
      //
      //   .       methods and fields on the receiver's type
      //   use …   module paths
      //
      // Argument names inside `{ … }` are deliberately absent. The engine
      // cannot resolve a method's parameters while the call is still open —
      // the buffer does not typecheck, so the receiver has no type — and what
      // it returns instead is unrelated: variant constructors, or a snippet
      // whose placeholders leak into the buffer. Silence beats that.
      // Any `use` line, including once it opens a selective `::{ … }` block —
      // the bridge answers that case from the module's own exports.
      // `::` completes a module's exports (`map::empty`), the same question a
      // selective import asks, so it triggers alongside `.` and `use`.
      const afterColons = /::\w*$/.test(curLine)
      const wanted = trigger === '.' || afterColons ||
                     /^\s*(pub\s+)?use\s/.test(curLine)
      if (!force && !wanted) return hideCompletions()

      const [line, col] = caretPos()
      let list
      try {
        list = window.completeMezze(srcEl.value, line, col)
      } catch {
        return hideCompletions()
      }
      if (!list?.length) return hideCompletions()

      // Keywords are never what these four positions want. The engine has no
      // candidates for some of them (a selective `use …::{ ` block, an
      // argument name it cannot resolve) and falls back to the keyword list,
      // so `if`/`match`/`do` turned up after `{`. Dropping them leaves the
      // menu empty in that case, which is the honest answer.
      const useful = force ? list : list.filter((c) => c.kind !== 'Keyword')
      if (!useful.length) return hideCompletions()

      const [x, y] = caretXY(line, col)
      showCompletions(useful.slice(0, 40), x, y, applyCompletion)
    }, 90)
  }

  // ---- hover types ------------------------------------------------------
  // typeAt walks the TypedAST — the same utility the LSP hover uses — so this
  // resolves locals, parameters and subexpressions, not just top-level
  // bindings. Positions are 1-based, matching Loc.

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
   *
   * The caret comes from the textarea, which sits on top — the highlight layer
   * below it is pointer-events:none, so a hit test never reaches it.
   */
  const posAt = (x: number, y: number): [number, number] | null => {
    const r = srcEl.getBoundingClientRect()
    if (x < r.left || x > r.right || y < r.top || y > r.bottom) return null


    const doc = document as Document & {
      caretPositionFromPoint?: (x: number, y: number) => { offsetNode: Node; offset: number } | null
    }
    const caret = doc.caretPositionFromPoint?.(x, y)
    if (!caret) return null

    // Inside a textarea the browser reports an offset into its value directly,
    // so there is no node walk to do.
    const text = srcEl.value
    const offset = caret.offset
    if (offset < 0 || offset >= text.length) return null
    if (!/[A-Za-z0-9_]/.test(text[offset])) return null

    const before = text.slice(0, offset)
    return [before.split('\n').length, offset - before.lastIndexOf('\n')]
  }

  let hoverTimer: number | undefined
  let lastKey = ''
  /** True while the pointer is genuinely over the editor's text layer. */
  let pointerInside = false
  const hideTip = () => {
    // Cancel the pending lookup too: a hover queued moments before the pointer
    // left would otherwise resolve after this and re-show the tooltip over a
    // position nobody is pointing at any more.
    clearTimeout(hoverTimer)
    hideHoverTip()
    lastKey = ''
  }

  srcEl.addEventListener('mousemove', (e) => {
    pointerInside = true
    const p = posAt(e.clientX, e.clientY)
    if (!p) return hideTip()

    const key = p[0] + ':' + p[1]
    if (key === lastKey) {
      // Same token: just follow the pointer, no re-render and no lookup.
      moveHoverTip(e.clientX, e.clientY)
      return
    }

    clearTimeout(hoverTimer)
    const [line, col] = p
    const x = e.clientX
    const y = e.clientY
    hoverTimer = window.setTimeout(() => {
      // Re-check on arrival: 120ms is long enough for the pointer to have left
      // the editor entirely, and a stale async result must not resurrect it.
      if (!pointerInside) return
      const err = diags.find((d) => {
        const l = d?.location
        if (!l?.startLine) return false
        if (line !== l.startLine) return false
        return col >= (l.startCol ?? 1) && col <= (l.endCol ?? 1e9)
      })
      if (err) {
        lastKey = key
        showHoverTip(`<code class="ht-err">${esc(err.message)}</code>`, x, y)
        return
      }
      if (typeof window.typeAt !== 'function') return
      let t = ''
      try {
        t = String(window.typeAt(srcEl.value, line, col))
      } catch {
        // No type at this position; fall through to hide.
      }
      if (!t) return hideTip()

      lastKey = key
      // The compiler returns the same markdown the LSP shows in an editor —
      // fenced Mezze plus doc prose — so render it rather than splitting on
      // " : ", which only held while this was a bare "name : Type" string.
      showHoverTip(renderHover(t), x, y)
    }, HOVER_MS)
  })
  srcEl.addEventListener('pointerenter', () => { pointerInside = true })
  srcEl.addEventListener('mouseleave', () => { pointerInside = false; hideTip() })
  srcEl.addEventListener('scroll', hideTip)
  srcEl.addEventListener('input', hideTip)
  // Leaving the textarea by keyboard, or the page losing focus entirely, both
  // strand the tooltip — mouseleave never fires for either.
  srcEl.addEventListener('blur', hideTip)
  window.addEventListener('blur', () => { pointerInside = false; hideTip() })
  // The pointer can exit through the window edge without crossing the editor's
  // boundary, which skips mouseleave.
  document.addEventListener('mouseleave', () => { pointerInside = false; hideTip() })
  // A click anywhere else means attention has moved on.
  document.addEventListener('pointerdown', (e) => {
    if (e.target !== srcEl) { pointerInside = false; hideTip() }
  })

  // ---- actions ----------------------------------------------------------
  /**
   * Renders diagnostics; returns true when the source is RUNNABLE.
   *
   * Warnings are shown but do not block: an unused import is worth saying and
   * is not worth refusing to run over. Treating every diagnostic as fatal made
   * `use std::effects::http::{ Http, … }` — where only the operations are
   * named — print an error instead of the program's output.
   */
  const report = (res: any): boolean => {
    const ds = res?.diagnostics ?? []
    const errs = ds.filter((d: any) => d.severity !== 'warning')
    if (!ds.length) {
      paint()
      show('No errors.', 'ok')
      return true
    }
    // Underline the first thing worth looking at, preferring a real error.
    const first = errs[0] ?? ds[0]
    paint(withError(srcEl.value, first, first.message))
    // `pretty` is rendered by the compiler-s own ErrorFormatter, so the pane
    // reads exactly like `mez check`: error code, --> location, source snippet
    // with a caret, hints and notes. Reused rather than reimplemented here —
    // a second renderer in TypeScript would drift from the CLI silently.
    const pretty: string[] = res?.pretty ?? []
    show(
      pretty.length
        ? pretty.join('\n\n')
        : ds.map((d: any) => {
            const l = d.location
            const at = l?.startLine ? `${l.startLine}:${l.startCol}` : '?'
            const what = d.severity === 'warning' ? 'warning' : 'error'
            return `${what} at ${at}\n  ${d.message}`
          }).join('\n\n'),
      errs.length ? 'err' : 'warn',
    )
    return errs.length === 0
  }

  /** Disables the controls for the duration, since the call is synchronous. */
  const act = (fn: () => void) => () => {
    // The disabled attribute is not the only gate. A tap can still reach a
    // button that was enabled a frame earlier, or that moved under the finger
    // while the pane resized during load, and every action calls straight into
    // window.runMezze/checkMezze/formatMezze. Before the runtime lands those
    // are undefined, so the click surfaced as "Internal error: TypeError".
    if (!ready) {
      show('The Mezze runtime is still loading.')
      return
    }
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
      hideTip()
      // Through replaceRange, so Cmd+Z undoes a format. Assigning value
      // directly would clear the textarea's history.
      replaceRange(0, srcEl.value.length,
        String(window.formatMezze!(srcEl.value, editorCols())))
      paint()
      // Formatting rewrites the source, so the row count changes — it wraps to
      // the pane width, which on a narrow screen is a very different shape from
      // what the author wrote. Without this the pane keeps its old height and
      // starts scrolling.
      sizeToContent()
      show('Formatted.', 'ok')
    },
  }
  btns.forEach((b) => (b.onclick = act(ACTIONS[b.dataset.act!])))

  // ---- runtime ----------------------------------------------------------
  paint()
  // Size from the first paint, so a snippet whose lines wrap on a narrow
  // screen opens at the height it actually needs instead of scrolling until
  // the reader types or rotates.
  sizeToContent()
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

  const handle: EditorHandle = {
    setSource(code) {
      // Changing lesson replaces what the tooltip was describing, and fires no
      // pointer event — without this it hangs over the new source.
      hideTip()
      srcEl.value = code
      paint()
      sizeToContent()
      show(ready ? 'Ready. Press Run.' : 'Loading the compiler…')
    },
    show,
    isReady: () => ready,
  }
  ;(root as any).__mezzeHandle = handle
  return handle
}

/**
 * Mount every editor on the page that nobody has claimed.
 *
 * The component is markup only, so a page that merely includes it — a blog post
 * with an embedded sample — would otherwise render dead controls and never load
 * the runtime. Pages that need a handle (the taste) call mountEditor themselves;
 * the dataset guard keeps this from mounting those a second time.
 */
export function autoMountEditors(): void {
  for (const el of document.querySelectorAll<HTMLElement>('[data-editor]')) {
    if (el.dataset.mounted !== '1') mountEditor(el)
  }
}
