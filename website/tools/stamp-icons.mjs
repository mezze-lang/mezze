/**
 * Refresh the cache-busting stamp on the icon URLs.
 *
 * The icons live in public/, so Astro never fingerprints them — a redesigned
 * icon keeps its URL and a CDN serves the cached copy until the entry expires.
 * Hashing the bytes means the URL changes exactly when the artwork does.
 *
 * Run by tools/icons.sh; safe to run on its own after editing an icon by hand.
 */
import { createHash } from 'node:crypto'
import { readFileSync, writeFileSync, readdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const iconDir = join(here, '..', 'public', 'icons')
const dishes = join(here, '..', 'src', 'data', 'dishes.ts')

const h = createHash('sha1')
for (const f of readdirSync(iconDir).sort()) h.update(readFileSync(join(iconDir, f)))
const v = h.digest('hex').slice(0, 8)

const src = readFileSync(dishes, 'utf8')
const next = src.replace(/const ICON_V = '[^']*'/, `const ICON_V = '${v}'`)
if (next === src && !src.includes(`const ICON_V = '${v}'`)) {
  console.error('stamp-icons: could not find ICON_V in src/data/dishes.ts')
  process.exit(1)
}
writeFileSync(dishes, next)
console.log(`  stamp      ${v}`)
