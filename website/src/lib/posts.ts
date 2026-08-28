import { getCollection, type CollectionEntry } from 'astro:content'

/**
 * Blog posts, newest first.
 *
 * Drafts are visible while running `astro dev` and dropped from a production
 * build — so a work in progress can be read and linked locally without ever
 * reaching the deployed site.
 */
export async function posts(): Promise<CollectionEntry<'blog'>[]> {
  const all = await getCollection('blog', ({ data }) => import.meta.env.DEV || !data.draft)
  return all.sort((a, b) => b.data.date.valueOf() - a.data.date.valueOf())
}
