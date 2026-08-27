# Editing the site

Everything you would normally want to change lives in `src/data/` or
`src/content/`. You should not need to touch a page or a component for
ordinary edits.

## Where things live

| I want to change… | Edit |
|---|---|
| Headline, tagline, nav, topic tags | `src/data/content.ts` |
| Homepage code samples (the Run box tabs) | `src/data/content.ts` → `playground.tabs` |
| Homepage feature blurbs | `src/data/content.ts` → `features` |
| Taste of Mezze lessons — prose *and* code | `src/data/lessons.ts` |
| Blog posts | `src/content/blog/*.md` |
| Colours, spacing, every style | `src/styles/tokens.css` |
| The dish cycle and its order | `src/data/dishes.ts` |

## Inline syntax

The site has its own tiny markup for highlighter marks and hand-drawn
squiggles. **The delimiters differ between the two places**, because Markdown
claims some of them for itself.

### In `content.ts` and `lessons.ts`

```
~onion~word~        highlighter mark
~~word~~            squiggle underline
~~tomato:word~~     squiggle in a palette colour
`code`              inline code
```

Mark colours: `onion` `lemon` `auber` `mint` `olive`
Squiggle colours: `olive` `aubergine` `tomato` `lemon` `onion` `mint`

### In blog posts (Markdown)

```
==onion:word==      highlighter mark
~word~              squiggle underline
~tomato:word~       squiggle in a palette colour
```

Markdown turns `--` into an en-dash and claims `~~` for strikethrough, so
posts use `==` for marks. Anything inside backticks or a fenced block is left
alone, so you can show the raw syntax in a post.

## Writing a post

Drop a file in `src/content/blog/`. The filename becomes the URL.

```markdown
---
title: Running a compiler in the browser
blurb: One line, shown on the card and under the title.
date: 2026-08-20
colour: olive      # optional — card accent. Defaults to the current dish.
draft: true        # optional — hides it from the index, builds no page.
---

Body in normal Markdown.
```

`colour` accepts only the six dish names. A typo fails the build rather than
rendering wrong — that is deliberate.

## Components

| Component | What it does |
|---|---|
| `Base.astro` | The only layout. Owns the header, theme, and dish switcher. `width="page"` (default) or `width="full"`. |
| `SiteHeader.astro` | Header. Rendered by `Base` — never add it to a page. |
| `Mascot.astro` | The dish icon. `size={n}`, `static` to opt out of click-to-cycle. |
| `Prose.astro` | Renders text through the inline syntax above. `as="p"` picks the tag. |
| `Tag.astro` | A palette-coloured pill. `colour="onion"`. |
| `ThemeToggle.astro` | Light/dark switch. |

## Colours

Eight tokens, six of them dishes with matching artwork:

`--olive` `--lemon` `--onion` `--tomato` `--mint` `--aubergine`

Everything else — marks, squiggles, semantic states, syntax highlighting — is
derived from those with `color-mix()`. Change a dish and the whole site
follows. There are no raw hex values outside the token declarations, and it
should stay that way.

## Building

Use `npx astro build`. `npm run build` also runs `astro check`, which currently
reports pre-existing type errors on the inline DOM scripts and blocks the
build.

## Icons

`public/icons/` holds the six dish icons. They are committed, so an edit that
goes wrong is reverted with git rather than from a backup copy:

    git checkout -- public/icons/
