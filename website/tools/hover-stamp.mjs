/**
 * Stamp hovers.json with a hash of the samples it was generated from, and warn
 * when they no longer match.
 *
 *   node tools/hover-stamp.mjs          # check, fail on drift
 *   node tools/hover-stamp.mjs --write  # record the current hash
 *
 * hovers.json is generated from the exact source text of the homepage samples,
 * so editing one leaves the hover data describing code that is no longer on the
 * page. It fails silently: a stale entry still renders, just against the wrong
 * token. Regenerating takes ~25s, too slow to hang off the dev server, so the
 * mismatch is made loud instead.
 */
import { createHash } from 'node:crypto'
import { readFileSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const contentPath = join(here, '..', 'src', 'data', 'content.ts')
// A sidecar, not a key inside hovers.json: that file is imported as typed
// JSON (Record<string, Record<string, string>>) and a string at the top level
// breaks the type.
const stampPath = join(here, '..', 'src', 'data', 'hovers.stamp')

// Hash only the sample sources: a reworded blurb changes no types, and hashing
// the whole file would cry stale on every copy edit.
const src = readFileSync(contentPath, 'utf8')
const codes = [...src.matchAll(/code:\s*`([\s\S]*?)`,\n/g)].map((m) => m[1])
if (!codes.length) {
  console.error('hover-stamp: found no `code:` blocks in content.ts')
  process.exit(1)
}
const want = createHash('sha1').update(codes.join(' ')).digest('hex').slice(0, 12)

let have = ''
try { have = readFileSync(stampPath, 'utf8').trim() } catch { /* never built */ }

if (process.argv.includes('--write')) {
  writeFileSync(stampPath, want + '\n')
  console.log(`  hover stamp  ${want}`)
} else if (have !== want) {
  console.error(
    '\n  hovers.json is STALE - the homepage samples changed since it was built.' +
    '\n  Types on hover will be wrong or missing. Run:  just hovers\n',
  )
  process.exit(1)
}
