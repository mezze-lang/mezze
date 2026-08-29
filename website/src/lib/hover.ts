/**
 * Render the hover Markdown that HoverInfo returns.
 *
 * The compiler now hands back the same Markdown the LSP shows in an editor:
 * ```mezze fences for code, plain paragraphs for docs, and occasional links.
 * This is a deliberately small renderer — fences, inline code, bold, and links
 * are all that hover emits, so a full Markdown parser would be dead weight.
 *
 * Code inside a fence goes through the site's own highlighter, so a type in a
 * tooltip is coloured exactly like the same type in the editor.
 */
import { esc, highlight } from './highlight'

/** Inline markdown: `code`, **bold**, [text](url). Escapes everything else. */
function inline(s: string): string {
  const parts: string[] = []
  let last = 0
  const re = /`([^`]+)`|\*\*([^*]+)\*\*|\[([^\]]+)\]\(([^)]+)\)/g
  for (const m of s.matchAll(re)) {
    parts.push(esc(s.slice(last, m.index)))
    if (m[1] !== undefined) parts.push(`<code>${highlight(m[1])}</code>`)
    else if (m[2] !== undefined) parts.push(`<strong>${esc(m[2])}</strong>`)
    else {
      // Hover links are file:// jumps for an editor; there is nowhere to go in
      // a browser, so keep the text and drop the target.
      parts.push(esc(m[3]))
    }
    last = m.index! + m[0].length
  }
  parts.push(esc(s.slice(last)))
  return parts.join('')
}

/** Hover markdown to HTML. */
export function renderHover(md: string): string {
  const out: string[] = []
  const lines = md.split('\n')
  let i = 0
  while (i < lines.length) {
    const line = lines[i]
    if (line.trimStart().startsWith('```')) {
      const body: string[] = []
      i++
      while (i < lines.length && !lines[i].trimStart().startsWith('```')) {
        body.push(lines[i])
        i++
      }
      i++   // closing fence
      out.push(`<pre class="ht-code"><code>${highlight(body.join('\n'))}</code></pre>`)
      continue
    }
    if (line.trim() === '') { i++; continue }
    // Consecutive non-blank, non-fence lines form one paragraph.
    const para: string[] = []
    while (i < lines.length && lines[i].trim() !== '' && !lines[i].trimStart().startsWith('```')) {
      para.push(lines[i].trim())
      i++
    }
    out.push(`<p class="ht-doc">${inline(para.join(' '))}</p>`)
  }
  return out.join('')
}
