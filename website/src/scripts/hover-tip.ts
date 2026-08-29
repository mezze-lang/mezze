/**
 * The one hover tooltip on the page, created the first time something shows it.
 *
 * Its own module, not part of editor.ts: the landing page needs the tooltip but
 * deliberately never imports the runtime loader, and pulling in editor.ts for
 * one function would drag that along.
 *
 * It hangs off <body> because the editor sets `overflow: hidden` — nested
 * inside, a tooltip would be clipped at the editor's edge. Shared and lazy, so
 * a page nobody hovers on ends up with no extra DOM at all; the previous
 * version appended an empty `<div class="hover-tip" hidden>` on every page.
 */
let tip: HTMLElement | undefined

/** The tooltip element, creating it if this is the first show. */
export function hoverTip(): HTMLElement {
  if (tip?.isConnected) return tip
  const el = document.createElement('div')
  el.className = 'hover-tip'
  el.hidden = true
  document.body.appendChild(el)
  tip = el
  return el
}

/** Hide it if it exists. Never creates — this runs on every pointer move. */
export function hideHoverTip(): void {
  if (tip) tip.hidden = true
}

/** Follow the pointer without re-rendering. No-op when nothing is shown. */
export function moveHoverTip(x: number, y: number): void {
  if (!tip || tip.hidden) return
  tip.style.left = x + 12 + "px"
  tip.style.top = y + 18 + "px"
}

/** Position the tooltip near the pointer and reveal it. */
export function showHoverTip(html: string, x: number, y: number): void {
  const el = hoverTip()
  el.innerHTML = html
  el.style.left = x + 12 + 'px'
  el.style.top = y + 18 + 'px'
  el.hidden = false
}
