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
  headline: 'Write what it ~onion~does~.\nThe types ~~olive:keep up~~.',
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
  caption: "Every sample here was run through the compiler — the output is what it printed.",
  tabs: [
    {
      dish: "olive",
      label: "Handlers",
      blurb: "Swap where output goes",
      code: `use std::effects::log::{ Console, QuietConsole, println }

# The same function, two destinations.
let audit = { order } -> println { msg = "saw \$(order)" }

let report = { n } -> println { msg = "\$(n) orders seen" }

let main = {} -> do
  # Console prints it...
  perform Console in audit { order = "A-1" }

  # ...QuietConsole drops it. Nothing in audit changed.
  perform QuietConsole in audit { order = "A-2" }

  perform Console in report { n = "2" }`,
      output: "saw A-1\n2 orders seen",
    },
    {
      dish: "lemon",
      label: "Named arguments",
      blurb: "Order never matters",
      code: `use std::effects::log::{ Console, println }

# Every argument is named, so the order is yours.
let label = { name, unit } -> "\$(name) sold by the \$(unit)"

let main = {} -> perform Console in do
  println { msg = label { name = "olive oil", unit = "litre" } }

  # Same call, fields the other way round.
  println { msg = label { unit = "gram", name = "za'atar" } }


  # Add a field later and these calls still compile.
  let honey = label { name = "honey", unit = "jar" }
  println { msg = honey }`,
      output: "olive oil sold by the litre\nza’atar sold by the gram\nhoney sold by the jar",
    },
    {
      dish: "onion",
      label: "Records",
      blurb: "Copy with a change",
      code: `use std::effects::log::{ Console, println }

# A record is just named fields — no class, no constructor.
let base = { host = "localhost", port = 8080, tls = False }

# Copy with one field changed; the original is untouched.
let prod = { ...base, host = "mezze-lang.org", tls = True }

let main = {} -> perform Console in do
  println { msg = base.host }
  println { msg = prod.host }

  # Destructuring is exhaustive — \`...\` opts out of the rest.
  let { host, port, ... } = prod
  println { msg = "\$(host):\$(port.to_str {})" }`,
      output: "localhost\nmezze-lang.org\nmezze-lang.org:8080",
    },
    {
      dish: "tomato",
      label: "Exhaustive match",
      blurb: "Miss a case, it will not compile",
      code: `use std::effects::log::{ Console, println }

# A closed set of cases, some carrying a payload.
type Status = | Queued, Running, Done { ms: Int } |

# Miss a case and it will not compile.
let describe = { s } -> match s is
  Queued -> "waiting"
  Running -> "in progress"
  Done { ms } -> "finished in \$(ms.to_str {})"


let main = {} -> perform Console in do
  println { msg = describe { s = Queued } }
  println { msg = describe { s = Done { ms = 42 } } }`,
      output: "waiting\nfinished in 42",
    },
    {
      dish: "mint",
      label: "Concurrency",
      blurb: "Two requests at once",
      code: `use std::effects::async::{ Async, LoomAsync }
use std::effects::log::{ Console, println }
use std::result::{ get_or_else }

# Two requests at once, not one after the other.
let fetch = { url } -> do
  println { msg = "GET \$(url)" }
  url

let main = {} -> perform Console in perform LoomAsync in do
  let a = Async.spawn { f = {} -> fetch { url = "/users" } }
  let b = Async.spawn { f = {} -> fetch { url = "/orders" } }

  println { msg = get_or_else { result = a.await {}, def = "?" } }
  println { msg = get_or_else { result = b.await {}, def = "?" } }`,
      output: "GET /users\nGET /orders\n/users\n/orders\n\n(the GET lines race — order varies run to run)",
    },
    {
      dish: "aubergine",
      label: "Abilities",
      blurb: "Dot-methods on your own types",
      code: `use std::effects::log::{ Console, println }

# An interface your own types can implement.
ability Money for 'a where {
  cents: { it: 'a } -> Int
}

type Eur = { amount: Int }

impl Money for Eur where {
  cents = { it } -> it.amount
}

let main = {} -> perform Console in
  println { msg = { amount = 1299 }.cents {}.to_str {} }`,
      output: "1299",
    },
  ],
}

/** One per dish, in cycle order — the mark colour follows the palette. */
export const features = [
  {
    title: 'Write like a ~lemon~dynamic language~, get a strict one',
    body:
      'Full inference across the whole program. Annotate a signature where it helps ' +
      'someone reading it, ~~lemon:never~~ to satisfy the compiler.',
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
      'clock or a frozen one. Tests get a different ~~tomato:handler~~, not different code.',
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
      'Optimised data structures, loop fusion, and mutation that is ~~aubergine:safe~~ because the ' +
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

