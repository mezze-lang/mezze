/**
 * The single entry point to the Mezze WASM runtime.
 *
 * The wasm bundle is ~56MB, so it must be fetched exactly once per visit no
 * matter how many editors are on the page or how many pages are visited in a
 * session. Everything goes through `loadMezze()`, which caches the promise on
 * `window` — later callers get the same promise back, whether the runtime is
 * still loading or already up.
 *
 * The wrapper self-executes and installs globals, then main() returns, so
 * readiness is detected by polling for the globals rather than awaiting a
 * module export.
 */

declare global {
  interface Window {
    __mezzePromise?: Promise<MezzeRuntime>
    /** Set once the prefetch links are in the document. */
    __mezzePrefetched?: boolean
    runMezze?: (source: string) => string
    checkMezze?: (source: string) => string
    /** cols is optional; 0 or omitted keeps the CLI width. */
    formatMezze?: (source: string, cols?: number) => string
    typeAt?: (source: string, line: number, col: number) => string
  }
}

export interface MezzeRuntime {
  run: (source: string) => string
  check: (source: string) => string
  /** Seconds spent loading, for the status line. */
  loadSeconds: number
}

/**
 * Where the runtime is served from.
 *
 * Cloudflare Pages rejects files over 25 MiB and the wasm is ~56, so in
 * production both files live in an R2 bucket; PUBLIC_WASM_BASE is set at build
 * time from the Pulumi stack output. Locally it is unset and they are served
 * from public/wasm/ as before.
 *
 * Both files must come from the same place: mezze.js locates its .wasm via
 * `document.currentScript.src`, so a script loaded from R2 fetches the binary
 * from R2 automatically, and one loaded locally stays local.
 */
const BASE = (import.meta.env.PUBLIC_WASM_BASE ?? '').replace(/\/$/, '')

const SRC = `${BASE}/wasm/mezze.js`
/** The bulk of the download — the wrapper fetches this itself at runtime. */
const WASM = `${BASE}/wasm/mezze.js.wasm`
const POLL_MS = 100
const TIMEOUT_MS = 90_000

export function loadMezze(): Promise<MezzeRuntime> {
  if (window.__mezzePromise) return window.__mezzePromise

  window.__mezzePromise = new Promise<MezzeRuntime>((resolve, reject) => {
    const t0 = performance.now()

    // Reuse the tag if a previous call already injected it.
    if (!document.querySelector(`script[src="${SRC}"]`)) {
      const tag = document.createElement('script')
      tag.src = SRC
      tag.onerror = () => reject(new Error(`Could not fetch ${SRC}`))
      document.head.appendChild(tag)
    }

    const started = performance.now()
    const tick = () => {
      if (typeof window.runMezze === 'function') {
        resolve({
          run: (s) => String(window.runMezze!(s)),
          check: (s) => String(window.checkMezze?.(s) ?? ''),
          loadSeconds: (performance.now() - t0) / 1000,
        })
        return
      }
      if (performance.now() - started > TIMEOUT_MS) {
        reject(new Error('The Mezze runtime did not load.'))
        return
      }
      setTimeout(tick, POLL_MS)
    }
    tick()
  })

  return window.__mezzePromise
}

/**
 * Start fetching the runtime once the page has settled.
 *
 * Fetching on load competes with the page's own assets and spends ~16MB
 * gzipped on a reader who may never open an editor; waiting for a hover means
 * the download only begins as they click, so they wait the whole thing. This
 * splits the difference: begin during the first idle moment after load, so the
 * bytes are usually in cache before anyone asks, and prefetch (not preload)
 * keeps it at the lowest priority so nothing visible is delayed.
 *
 * Skipped under Save-Data and on 2g/3g, where a speculative 16MB is a real
 * cost — those readers pay it only when they actually open an editor, via the
 * hover trigger below.
 *
 * @param selector links whose hover/focus should trigger the fetch immediately,
 *                 for the case where the idle fetch was skipped or is still
 *                 pending.
 */
export function prewarmMezze(selector = 'a[href^="/taste"]'): void {
  if (window.__mezzePromise || window.__mezzePrefetched) return

  const conn = (navigator as any).connection
  const frugal = conn?.saveData || /2g|3g/.test(conn?.effectiveType ?? '')

  const fire = () => prefetchMezze()

  // Hover/focus is the strong signal — honour it even when frugal.
  for (const el of Array.from(document.querySelectorAll(selector))) {
    el.addEventListener('pointerenter', fire, { once: true })
    el.addEventListener('focus', fire, { once: true })
  }

  if (frugal) return

  // Idle, not load: let the page finish its own work first.
  const idle = (window as any).requestIdleCallback
  if (typeof idle === 'function') idle(fire, { timeout: 3000 })
  else setTimeout(fire, 1500)
}

/** Queue the bytes into the HTTP cache without instantiating anything. */
export function prefetchMezze(): void {
  if (window.__mezzePromise || window.__mezzePrefetched) return
  window.__mezzePrefetched = true
  for (const href of [SRC, WASM]) {
    if (document.querySelector(`link[href="${href}"]`)) continue
    const link = document.createElement('link')
    link.rel = 'prefetch'
    link.as = 'fetch'
    // Only for a cross-origin fetch: setting it on a same-origin request makes
    // the browser treat the prefetch as a different cache entry than the real
    // one, so the file downloads twice.
    if (BASE) link.crossOrigin = 'anonymous'
    link.href = href
    document.head.appendChild(link)
  }
}

/** True once the runtime is up, for callers that must not block. */
export const isMezzeReady = () => typeof window.runMezze === 'function'
