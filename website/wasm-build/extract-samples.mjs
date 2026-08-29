/**
 * Write each homepage sample out as a .mz file, for the hover extractor.
 *
 *   node extract-samples.mjs <content.ts> <out-dir>
 *
 * Its own file rather than `node -e` inside hovers.sh: the unescaping below
 * needs both backticks and backslashes, which do not survive being nested in a
 * shell string.
 */
import { readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'

const [, , contentPath, outDir] = process.argv
if (!contentPath || !outDir) {
  console.error('usage: extract-samples.mjs <content.ts> <out-dir>')
  process.exit(1)
}

const src = readFileSync(contentPath, 'utf8')
const re = /dish: "([^"]+)",[\s\S]*?code: `([\s\S]*?)`,/g

let m
let count = 0
while ((m = re.exec(src))) {
  // The samples are TypeScript template literals, so Mezze's interpolation is
  // written \$( to stop JS from evaluating it. Undo that here: the compiler has
  // to see a real $( or it reads an ordinary string, and a parameter that an
  // interpolation should pin to Str stays a free type variable — which is why
  // hover reported `{ order: 'a }` where the compiler infers `{ order: Str }`.
  const code = m[2].replace(/\\\$\(/g, '$(').replace(/\\`/g, '`')
  writeFileSync(join(outDir, `${m[1].toLowerCase()}.mz`), code)
  count++
}

if (!count) {
  console.error('no samples found in content.ts')
  process.exit(1)
}
