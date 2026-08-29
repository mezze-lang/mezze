/**
 * Pre-compress the WebAssembly runtime with brotli, for upload to R2.
 *
 *   node tools/compress-wasm.mjs
 *
 * R2 serves objects verbatim — it has no on-the-fly compression — so the
 * runtime went out as 56.3MB of uncompressed wasm to every visitor. Stored
 * brotli-compressed with `Content-Encoding: br` it is 11.0MB, an 80% cut on
 * the single largest asset on the site.
 *
 * Quality 11 costs about two minutes here and nothing at all for the reader:
 * browsers decode brotli natively at hundreds of MB/s, streaming as the bytes
 * arrive. The compression is paid once per deploy.
 *
 * There is no uncompressed copy in the bucket. Every browser has supported
 * brotli over HTTPS for years, and R2's public URL is HTTPS-only; a client
 * that did not send `Accept-Encoding: br` would receive bytes it cannot read,
 * which for a browser-only asset is a trade worth making.
 *
 * Uses node's built-in zlib rather than the brotli CLI, so the deploy needs no
 * tool that is not already a dependency.
 */
import { brotliCompress, constants } from 'node:zlib'
import { readFile, writeFile, mkdir } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { promisify } from 'node:util'

const compress = promisify(brotliCompress)

const here = dirname(fileURLToPath(import.meta.url))
const wasmDir = join(here, '..', 'public', 'wasm')
const outDir = join(wasmDir, '.br')

const FILES = ['mezze.js', 'mezze.js.wasm']

const mb = (n) => (n / 1048576).toFixed(1) + ' MB'

await mkdir(outDir, { recursive: true })

for (const name of FILES) {
  const raw = await readFile(join(wasmDir, name))
  const started = Date.now()
  const packed = await compress(raw, {
    params: {
      [constants.BROTLI_PARAM_QUALITY]: 11,
      // Telling brotli the exact input size lets it size its window properly;
      // without it a 56MB input is compressed as if it might be tiny.
      [constants.BROTLI_PARAM_SIZE_HINT]: raw.length,
    },
  })
  await writeFile(join(outDir, name), packed)
  const pct = Math.round((1 - packed.length / raw.length) * 100)
  const secs = ((Date.now() - started) / 1000).toFixed(0)
  console.log(
    `  ${name.padEnd(15)} ${mb(raw.length)} -> ${mb(packed.length)}  (${pct}% smaller, ${secs}s)`,
  )
}
