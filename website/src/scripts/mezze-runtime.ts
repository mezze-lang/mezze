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
 * Warm the HTTP cache when the reader looks likely to need the runtime.
 *
 * The bundle is ~16MB gzipped, so fetching it on every page load spends a
 * phone-sized download on visitors who never open an editor. Instead this
 * arms a one-shot prefetch that fires on the first real signal of intent:
 * pointing at or focusing a link to a page that has an editor.
 *
 * A full page navigation tears down the JS context, so the in-memory promise
 * cannot survive it — the browser cache is what carries the runtime from one
 * page to the next, via the long-lived Cache-Control in public/_headers.
 *
 * Respects Save-Data and 2g/3g connections: there, the reader waits for the
 * editor page rather than paying for a download they may not use.
 *
 * @param selector links whose hover/focus should trigger the prefetch.
 */
export function prewarmMezzeOnIntent(selector = 'a[href^="/taste"]'): void {
  if (window.__mezzePromise || window.__mezzePrefetched) return

  const conn = (navigator as any).connection
  if (conn?.saveData) return
  if (/2g|3g/.test(conn?.effectiveType ?? '')) return

  const links = Array.from(document.querySelectorAll(selector))
  if (!links.length) return

  const fire = () => {
    prefetchMezze()
    for (const el of links) {
      el.removeEventListener('pointerenter', fire)
      el.removeEventListener('focus', fire)
    }
  }
  for (const el of links) {
    el.addEventListener('pointerenter', fire, { once: true })
    el.addEventListener('focus', fire, { once: true })
  }
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
