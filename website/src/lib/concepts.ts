import { getCollection, type CollectionEntry } from 'astro:content'

/**
 * The concept tree, in reading order.
 *
 * Explicit rather than derived from the folder layout. Teaching order is not
 * alphabetical, section titles are not always derivable from a slug ("STM",
 * "Why the name"), and nesting should be a decision rather than a side effect
 * of where a file happens to sit. One entry per page; `children` nests.
 *
 * A slug with no matching file fails the build, so the sidebar cannot drift
 * from what actually exists.
 */
export interface Node {
  slug: string
  children?: Node[]
}

export const TREE: Node[] = [
  { slug: 'intro' },
  { slug: 'syntax' },
  { slug: 'primitives' },
  { slug: 'functions' },
]

export interface Heading {
  /** Anchor id, unique across the whole document. */
  id: string
  text: string
}

export interface Page {
  slug: string
  title: string
  blurb?: string
  /** Rendered prose, with editor markers already split out. */
  html: string
  depth: number
  /** `##` headings inside the page, for the sidebar and scroll-spy. */
  headings: Heading[]
}

export async function pages(): Promise<Page[]> {
  const entries = await getCollection('concepts')
  const byId = new Map(entries.map((e: CollectionEntry<'concepts'>) => [e.id, e]))
  const out: Page[] = []

  const walk = (nodes: Node[], depth: number) => {
    for (const n of nodes) {
      const entry = byId.get(n.slug)
      if (!entry) {
        throw new Error(`concepts: no file for "${n.slug}" — expected src/content/concepts/${n.slug}.md`)
      }
      // Astro ids each rendered heading, but those ids only have to be unique
      // per file and every page shares one document here. Prefixing with the
      // slug lets two pages both have a "Why names" section without their
      // anchors colliding.
      let html = (entry.rendered?.html ?? '') as string
      const headings: Heading[] = []
      html = html.replace(/<h2 id="([^"]*)">([\s\S]*?)<\/h2>/g, (_m, id, inner) => {
        const scoped = n.slug + '--' + id
        headings.push({ id: scoped, text: String(inner).replace(/<[^>]+>/g, '') })
        return '<h2 id="' + scoped + '">' + inner + '</h2>'
      })

      out.push({
        slug: n.slug,
        title: entry.data.title,
        blurb: entry.data.blurb,
        html,
        depth,
        headings,
      })
      if (n.children) walk(n.children, depth + 1)
    }
  }
  walk(TREE, 0)
  return out
}
