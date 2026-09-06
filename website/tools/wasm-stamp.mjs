/**
 * Content hash of the WebAssembly runtime, for use as a URL path segment.
 *
 *   node tools/wasm-stamp.mjs        -> prints e.g. 9f3a1c74e0b2
 *
 * The runtime is served `immutable` for a year, which is a lie unless the URL
 * changes when the bytes do. Both halves of the deploy read this one value:
 * the site build stamps it into PUBLIC_WASM_STAMP, and push-wasm uploads to
 * `wasm/<stamp>/`. Computing it in one place is the point — a stamp that
 * disagreed between the two would 404 the runtime on every page.
 *
 * Hashed from the UNCOMPRESSED .wasm rather than the brotli output: brotli is
 * not guaranteed byte-identical across versions, so compressing the same
 * runtime with a newer library would otherwise mint a new URL and make every
 * visitor re-download 14MB for nothing.
 *
 * Twelve hex characters. Long enough that a collision is not a real concern
 * for a handful of deploys a week, short enough to read in a URL.
 */
import { createHash } from 'node:crypto'
import { readFile } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const wasm = join(here, '..', 'public', 'wasm', 'mezze.js.wasm')

const bytes = await readFile(wasm).catch(() => {
  console.error(`no runtime at ${wasm} — run \`just wasm\` first`)
  process.exit(1)
})

process.stdout.write(createHash('sha256').update(bytes).digest('hex').slice(0, 12))
