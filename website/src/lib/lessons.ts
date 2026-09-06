import { getCollection, type CollectionEntry } from 'astro:content'

/**
 * The order lessons are taught in.
 *
 * Explicit rather than derived from filenames: teaching order is not
 * alphabetical, and encoding it as `01-primitives.md` would mean renaming
 * files to reorder and stripping the numbers back out of every slug. One
 * list, one line per lesson.
 *
 * A slug here with no matching file fails the build rather than silently
 * dropping a lesson from the rail.
 */
export const ORDER = [
  'primitives',
  'functions',
  'records',
  'variants',
  'shapes',
  'aliases',
  'methods',
  'abilities',
  'effects',
  'custom-effects',
] as const

export interface Lesson {
  slug: string
  title: string
  blurb: string
  /** Rendered HTML for the prose, editor marker already stripped out. */
  body: string
  /** The Mezze source from the lesson's `mezze-editor` block. */
  code: string
}

const MARKER = /<!--mezze-editor\s*([\s\S]*?)-->/

export async function lessons(): Promise<Lesson[]> {
  const entries = await getCollection('lessons')
  const byId = new Map(entries.map((e: CollectionEntry<'lessons'>) => [e.id, e]))

  return Promise.all(
    ORDER.map(async (slug) => {
      const entry = byId.get(slug)
      if (!entry) throw new Error(`lessons: no file for "${slug}" — check src/content/lessons/`)
      const html = (entry.rendered?.html ?? '') as string
      const hit = html.match(MARKER)
      return {
        slug,
        title: entry.data.title,
        blurb: entry.data.blurb,
        body: hit ? html.replace(hit[0], '') : html,
        code: hit ? hit[1].trim() : '',
      }
    }),
  )
}
