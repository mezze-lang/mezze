import { defineCollection, z } from 'astro:content'
import { glob } from 'astro/loaders'

/**
 * Blog posts live in src/content/blog as Markdown. Add a file, it appears —
 * no registration step. The schema below is enforced at build time, so a typo
 * in the frontmatter fails the build instead of rendering a broken card.
 */
const blog = defineCollection({
  loader: glob({ pattern: '**/*.md', base: './src/content/blog' }),
  schema: z.object({
    title: z.string(),
    /** Shown on the index and under the post title. */
    blurb: z.string(),
    date: z.coerce.date(),
    /** Palette colour for the card accent. Defaults to the current dish. */
    colour: z.enum(['olive', 'lemon', 'onion', 'tomato', 'mint', 'aubergine']).optional(),
    /** Set true to keep a post out of the published list. */
    draft: z.boolean().default(false),
  }),
})

export const collections = { blog }
