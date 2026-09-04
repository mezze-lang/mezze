/**
 * The one completion popup on the page, created the first time it is shown.
 *
 * Same arrangement as hover-tip: a single lazily-created element on <body>,
 * because the editor clips its overflow and a popup nested inside would be cut
 * off at the pane's edge.
 *
 * This module owns presentation and keyboard state only. Deciding when to ask
 * for candidates, and what to do with an accepted one, belongs to the editor.
 */
import type { Completion } from './mezze-runtime'

let el: HTMLElement | undefined
let items: Completion[] = []
let active = 0
let onPick: ((c: Completion) => void) | undefined

function popup(): HTMLElement {
  if (el?.isConnected) return el
  const d = document.createElement('div')
  d.className = 'cmp-popup'
  d.hidden = true
  // Mousedown, not click: the textarea would lose focus on mousedown and the
  // blur handler would close the popup before a click ever landed.
  d.addEventListener('mousedown', (e) => {
    const row = (e.target as HTMLElement).closest<HTMLElement>('[data-i]')
    if (!row) return
    e.preventDefault()
    accept(Number(row.dataset.i))
  })
  document.body.appendChild(d)
  el = d
  return d
}

function render(): void {
  const d = popup()
  d.innerHTML = items
    .map(
      (c, i) =>
        `<div class="cmp-row${i === active ? ' is-active' : ''}" data-i="${i}">` +
        `<span class="cmp-label">${esc(c.label)}</span>` +
        (c.detail ? `<span class="cmp-detail">${esc(c.detail)}</span>` : '') +
        `</div>`,
    )
    .join('')
  const row = d.querySelector<HTMLElement>('.is-active')
  // Keep the highlighted row in view when arrowing past the visible window.
  row?.scrollIntoView({ block: 'nearest' })
}

const esc = (s: string) =>
  s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')

/** Show candidates anchored near a caret at (x, y) in viewport coordinates. */
export function showCompletions(
  list: Completion[],
  x: number,
  y: number,
  pick: (c: Completion) => void,
): void {
  if (!list.length) return hideCompletions()
  items = list
  active = 0
  onPick = pick
  const d = popup()
  d.hidden = false
  render()

  // Flip above the caret when there is not room below, so the list never
  // hangs off the bottom of a short viewport.
  const h = d.offsetHeight
  const below = window.innerHeight - y
  d.style.left = Math.min(x, window.innerWidth - d.offsetWidth - 8) + 'px'
  d.style.top = (below < h + 24 ? y - h - 20 : y + 18) + 'px'
}

export function hideCompletions(): void {
  if (el) el.hidden = true
  items = []
  onPick = undefined
}

export function completionsOpen(): boolean {
  return !!el && !el.hidden && items.length > 0
}

function accept(i: number): void {
  const c = items[i]
  const pick = onPick
  hideCompletions()
  if (c && pick) pick(c)
}

/**
 * Handle a key while the popup is open. Returns true if the key was consumed,
 * in which case the caller must not let the editor act on it too.
 */
export function completionKey(e: KeyboardEvent): boolean {
  if (!completionsOpen()) return false
  switch (e.key) {
    case 'ArrowDown':
      active = (active + 1) % items.length
      render()
      return true
    case 'ArrowUp':
      active = (active - 1 + items.length) % items.length
      render()
      return true
    case 'Enter':
    case 'Tab':
      accept(active)
      return true
    case 'Escape':
      hideCompletions()
      return true
    default:
      return false
  }
}
