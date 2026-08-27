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
    runMezze?: (source: string) => string
    checkMezze?: (source: string) => string
    formatMezze?: (source: string) => string
    typeAt?: (source: string, line: number, col: number) => string
  }
}

export interface MezzeRuntime {
  run: (source: string) => string
  check: (source: string) => string
  /** Seconds spent loading, for the status line. */
  loadSeconds: number
}

const SRC = '/wasm/mezze.js'
/** The bulk of the download — the wrapper fetches this itself at runtime. */
const WASM = '/wasm/mezze.js.wasm'
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
 * Warm the HTTP cache without instantiating anything.
 *
 * A full page navigation tears down the JS context, so the in-memory promise
 * cannot survive it — the browser cache is what carries the runtime from one
 * page to the next. Calling this on a page that may navigate to an editor lets
 * that fetch finish early, so the editor starts from cache instead of the
 * network. Requires the long-lived Cache-Control set in public/_headers.
 */
export function prewarmMezze(): void {
  if (window.__mezzePromise) return
  for (const href of [SRC, WASM]) {
    if (document.querySelector(`link[href="${href}"]`)) continue
    const link = document.createElement('link')
    link.rel = 'prefetch'
    link.as = 'fetch'
    link.crossOrigin = 'anonymous'
    link.href = href
    document.head.appendChild(link)
  }
}

/** True once the runtime is up, for callers that must not block. */
export const isMezzeReady = () => typeof window.runMezze === 'function'
