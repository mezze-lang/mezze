/* ===========================================================================
   Landing page content — EDIT THIS FILE
   ---------------------------------------------------------------------------
   All copy lives here. Nothing else needs touching to change words.

   Inline formatting, usable in any string below:

     ~onion~word~   highlighter mark, pickled-onion pink
     ~lemon~word~   highlighter mark, lemon yellow
     ~auber~word~   highlighter mark, aubergine purple
     ~mint~word~    highlighter mark, mint
     ~olive~word~   highlighter mark, olive
     ~~word~~       hand-drawn squiggle underline
 *     ~~tomato:word~~ squiggle in a palette colour (olive aubergine tomato lemon onion mint)
     `code`         inline monospace

   Every snippet in `playground` is real Mezze — it is executed by the
   compiler on the page, so it has to compile.
   =========================================================================== */

export const site = {
  name: 'mezze',
  tagline: 'A functional language with effects in the type.',
  nav: [
    { label: 'Taste of Mezze', href: '/taste' },
    { label: 'Blog', href: '/blog' },
  ],
}

export const hero = {
  headline: 'Write what it ~onion~does~.\nThe types ~~keep up~~.',
  lede:
    'Every function tells you what it touches — files, network, tasks, failure — and ' +
    'you never annotate one. Full inference, structural types, running on the JVM.',
  primaryCta: { label: 'Taste of Mezze', href: '/taste' },
  footnote: 'Runs in your browser. Nothing to install to try it.',
}

export const topics = [
  { label: 'effects', colour: 'onion' },
  { label: 'handlers', colour: 'tomato' },
  { label: 'records', colour: 'lemon' },
  { label: 'abilities', colour: 'olive' },
  { label: 'concurrency', colour: 'mint' },
  { label: 'mutation', colour: 'auber' },
] as const

export const playground = {
  caption: 'Every sample here was run through the compiler — the output is what it printed.',
  tabs: [
    {
      label: 'Effects',
      code: `use std::effects::log::{ Log, Console, QuietConsole, println }

# <Log> says this touches the outside world. The caller cannot miss it.
let audit: { order: Str } -> {} <Log> = { order } ->
  println { msg = "saw $(order)" }

let main = {} -> do
  perform Console in audit { order = "A-1" }
  perform QuietConsole in audit { order = "A-2" }`,
      output: 'saw A-1',
    },
    {
      label: 'Records',
      code: `use std::effects::log::{ Console, println }
use std::int

type Item = { name: Str, price: Int }

let main = {} -> perform Console in do
  let item = { name = "olive oil", price = 899 }
  let sale = { ...item, price = 499 }

  println { msg = "$(sale.name): $(int::to_str {n = sale.price})" }`,
      output: 'olive oil: 499',
    },
    {
      label: 'Concurrency',
      code: `use std::effects::async::{ Async, LoomAsync }
use std::effects::log::{ Log, Console, println }
use std::result::{ get_or_else }

let fetch: { url: Str } -> Str <Log> = { url } -> do
  println { msg = "GET $(url)" }
  url

let main = {} -> perform Console in perform LoomAsync in do
  # Both start now; neither waits for the other.
  let a = Async.spawn { f = {} -> fetch { url = "/users" } }
  let b = Async.spawn { f = {} -> fetch { url = "/orders" } }

  # await hands back a Result, so a failure cannot be dropped.
  println { msg = get_or_else { result = a.await {}, def = "?" } }
  println { msg = get_or_else { result = b.await {}, def = "?" } }`,
      output: 'GET /users\nGET /orders\n/users\n/orders',
    },
    {
      label: 'Abilities',
      code: `use std::effects::log::{ Console, println }
use std::int

ability Priced for 'a where {
  cents: { it: 'a } -> Int
}

type Book = { price: Int }

impl Priced for Book where {
  cents = { it } -> it.price
}

let main = {} -> perform Console in
  println { msg = int::to_str {n = { price = 1299 }.cents {}} }`,
      output: '1299',
    },
  ],
}

/** One per dish, in cycle order — the mark colour follows the palette. */
export const features = [
  {
    title: 'Write like a ~lemon~dynamic language~, get a strict one',
    body:
      'Full inference across the whole program. Annotate a signature where it helps ' +
      'someone reading it, ~~never~~ to satisfy the compiler.',
  },
  {
    title: 'A few small pieces, ~onion~reused everywhere~',
    body:
      'Records are the whole story: arguments are records, so every call names its ' +
      'fields. Learn one shape and it holds from a two-field literal to a module API.',
  },
  {
    title: 'Swap out the ~tomato~side effects~ when you need to',
    body:
      'The same function runs against the real filesystem or an in-memory one, a live ' +
      'clock or a frozen one. Tests get a different ~~handler~~, not different code.',
  },
  {
    title: 'First-class ~olive~continuations~',
    body:
      'Exceptions and early return are ordinary functions here, not syntax the language ' +
      'had to grow. Write your own control flow and it looks like everyone else\'s.',
  },
  {
    title: 'Fearless concurrency, ~mint~built on Loom~',
    body:
      'Millions of virtual threads, and cancellation that actually interrupts a parked ' +
      'task. STM, atoms, channels, agents and latches ship with it.',
  },
  {
    title: 'Competitively fast on ~auber~GraalVM~',
    body:
      'Optimised data structures, loop fusion, and mutation that is ~~safe~~ because the ' +
      'type system keeps it in scope. AOT native binaries when you want them.',
  },
  {
    title: 'Distributed computing^TBD^',
    body:
      'Durable workflows and distributed execution, built on the same effect system. ' +
      'Designed, not yet shipped.',
  },
  {
    title: 'Keep the libraries you already have^TBD^',
    body:
      'Mezze runs on GraalVM, so calling the Java, Kotlin and Python you already have ' +
      'is the plan. Designed, not yet shipped.',
  },
]

