import { defineCollection } from 'astro:content'
import { z } from 'zod'
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

/**
 * Taste lessons, in the order set by `order` below. Markdown rather than a
 * TypeScript array: a lesson is prose with one code sample, and prose written
 * inside an escaped template literal is painful to edit and easy to get wrong
 * (every `$(` in a Mezze snippet needs escaping from JS).
 */
const lessons = defineCollection({
  loader: glob({ pattern: '**/*.md', base: './src/content/lessons' }),
  schema: z.object({
    title: z.string(),
    blurb: z.string(),
  }),
})

/**
 * Concepts pages. Same authoring shape as a lesson or a post: Markdown, with
 * `mezze-editor` for a runnable snippet and `mezze-code` for a static one.
 * Nesting is a folder: concepts/records/open-closed.md is a child page.
 */
const concepts = defineCollection({
  loader: glob({ pattern: '**/*.md', base: './src/content/concepts' }),
  schema: z.object({
    title: z.string(),
    /** One line under the heading, and the sidebar tooltip. */
    blurb: z.string().optional(),
  }),
})

export const collections = { blog, lessons, concepts }
