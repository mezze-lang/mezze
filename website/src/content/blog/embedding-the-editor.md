---
title: Embedding the Mezze editor
blurb: Drop a running Mezze editor into a page, with or without the buttons.
date: 2026-08-28
colour: mint
draft: true
---

The editor on ==mint:Taste of Mezze== is a component. It carries the compiler
with it — the same parser, typechecker and evaluator the CLI uses, compiled to
WebAssembly — so anything you embed genuinely runs.

## Try it

This is the component, running. Edit it and press Run — it is the same
compiler, not a recording.

<!--mezze-editor
use std::effects::log::{ Console, println }

let greet = { who } -> "hello $(who)"

let main = {} -> perform Console in
  println { msg = greet { who = "Mezze" } }
-->

To put one in a post, wrap the source in a `mezze-editor` comment:

```
<!--mezze-editor
let main = {} -> 1
-->
```

A comment rather than a fenced block, because the syntax highlighter claims
every fence — it rewrites the markup and relabels an unknown language as
plaintext, which loses the marker.

## The full editor

```astro
---
import Editor from '../components/Editor.astro'
---
<Editor code={`let main = {} -> 1`} />
```

That gives you Run, Check and Format, a live output pane, red squiggles as you
type, and type-on-hover.

The runtime is fetched **once**, not once per editor and not once per page.
Within a page `loadMezze()` caches its promise, so a second editor reuses the
first one's. Across pages the HTTP cache carries it: `public/_headers` marks
`/wasm/*` as `immutable` for a year, so a navigation re-reads it from disk
rather than the network. It is re-instantiated per page — roughly 850ms — but
not re-downloaded.

Two caveats. The dev server sends `Cache-Control: no-cache`, so you will see a
fresh transfer every reload while developing; only the deployed site gets the
immutable header. And a full page navigation tears down the JS context, so the
*instance* does not survive even though the bytes do.

To drive it from your own code, keep the handle:

```ts
import { mountEditor } from '../scripts/editor'

const editor = mountEditor(document.querySelector('[data-editor]'))
editor.setSource('let main = {} -> 2')
editor.show('Ready.', 'ok')
```

`setSource` replaces the code and repaints. `show` writes a line into the
output pane — `'ok'`, `'err'` and `'warn'` pick the colour.

## Read-only, no buttons

For a sample nobody is meant to edit, skip the component and highlight the
source directly:

```astro
---
import { highlight } from '../lib/highlight'

const code = `let greet = { who } -> "hello $(who)"`
---
<pre class="code"><code set:html={highlight(code)} /></pre>
```

No WebAssembly is downloaded, because nothing needs to run. The colouring is
the same tokeniser the live editor uses, so a static sample and an editable one
never disagree about what a keyword looks like.

This is what the ~~mint:landing page~~ does — its samples are highlighted at
build time, and their output is recorded rather than executed.

## Types without the runtime

A read-only sample can still show types on hover. `wasm-build/hovers.sh` runs
the typechecker at build time and writes a position-to-type map:

```sh
./wasm-build/hovers.sh    # -> src/data/hovers.json
```

The result is about 28KB for six samples, against 55MB for the runtime. Worth
it whenever the code is fixed.

## What the editor cannot do

Anything needing a thread. `async`, `parallel` and the filesystem effects are
denied at the boundary and report themselves rather than failing strangely —
WebAssembly has no threading story that ships today, and Loom needs one.

Everything else works, including outbound HTTPS.
