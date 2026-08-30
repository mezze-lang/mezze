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
  tagline: 'A functional language with first class effects running on GraalVM.',
  nav: [
    { label: 'Taste', href: '/taste' },
    { label: 'Dev Blog', href: '/dev-blog' },
  ],
}

export const hero = {
  headline: 'Write what it ~onion~does~.\nThe types ~~olive:keep up~~.',
  lede:
    'Every function tells you what it touches, files, network, failure, and you never annotate one. Full type safety, performance of GraalVM.',
  primaryCta: { label: 'Taste of Mezze', href: '/taste' },
  footnote: 'Runs in your browser. Nothing to install to try it.',
}

/**
 * Mailing list.
 *
 * `action` is the EmailOctopus *embedded form* URL — Lists → your list →
 * Forms → Embedded. It is safe to publish: that endpoint can only add an
 * address. Never put an API key here. A key is readable by anyone who views
 * source and can read and delete lists, not just append to them.
 */
export const subscribe = {
  /**
   * EmailOctopus form id — Forms -> your form -> Embed. Safe to publish: it
   * only opens their hosted form, and the copy inside it is theirs to edit.
   * Never put an API key here; a key is readable by anyone viewing source and
   * can read and delete lists.
   */
  formId: '2f717668-a2f9-11f1-ac5a-0f5779896a9a',
  cta: 'Install',
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
  /** Sits beside the samples, above the per-tab blurb. */
  aside:
    'Type safety that stays out of your way. Built for concurrent and distributed programming. Swap out effects when you need to. Ship a native binary or a polyglot jar, whichever the job wants.',
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

  perform Console in report { n = "2" }





`,
      output: "saw A-1\n2 orders seen",
    },
    {
      dish: "lemon",
      label: "Async",
      blurb: "Two requests at once",
      code: `use std::effects::http::{ Http, HttpClient, fetch_all, body_string }
use std::effects::async::{ LoomAsync }
use std::effects::log::{ Console, println }

# Both requests go out at once — fetch_all is parallel.
let body = { resp } -> match resp is
  Ok { val } -> body_string { resp = val }.get_or_else { def = "?" }
  Err { error = _ } -> "failed"

# <Http, Async> is in the type. Swap HttpClient for a mock and
# the same code runs in a test with no network.
let main = {} -> perform Console in perform LoomAsync in perform HttpClient in
  fetch_all { urls = ["https://mezze-lang.org/", "https://example.com/"] }
    .for_each { f = { x } -> println { msg = body { resp = x } } }






`,
      output: "<!doctype html>...\n<!doctype html>...",
    },
    {
      dish: "onion",
      label: "Pattern matching",
      blurb: "Every arm returns a new record",
      code: `use std::effects::log::{ Console, println }

type Status = | Queued, Shipped { days: Int }, Cancelled { why: Str } |
type Order = { id: Str, qty: Int, status: Status }

# Each arm returns a new order — the original is never touched.
let advance : { order: Order } -> Order = { order } -> match order.status is
  Queued -> { ...order, status = Shipped { days = 2 } }
  Shipped { days = 0 } -> { ...order, status = Cancelled { why = "late" } }
  Shipped { days } -> { ...order, status = Shipped { days = days - 1 } }
  Cancelled { why = _ } -> order

let describe = { order } -> match order.status is
  Queued -> "\$(order.id) waiting"
  Shipped { days } -> "\$(order.id) arrives in \$(days.to_str {})"
  Cancelled { why } -> "\$(order.id) cancelled: \$(why)"

let main = {} -> perform Console in do
  let order = { id = "A-1", qty = 3, status = Queued }
  println { msg = describe { order } }
  println { msg = describe { order = advance { order } } }`,
      output: "A-1 waiting\nA-1 arrives in 2",
    },
    {
      dish: "tomato",
      label: "Chaining",
      blurb: "Pipelines read top to bottom",
      code: `use std::effects::log::{ Console, println }

let orders = [
  { item = "olive oil", qty = 2, price = 1200 },
  { item = "za'atar",   qty = 5, price = 300 },
  { item = "sumac",     qty = 1, price = 450 },
]

let main = {} -> perform Console in do
  orders
    .filter { p = { x } -> x.qty > 1 }
    .map { f = { x } -> "\$(x.item): \$((x.qty * x.price).to_str {})" }
    .for_each { f = { x } -> println { msg = x } }

  let total = orders.map { f = { x } -> x.qty * x.price }.sum {}
  println { msg = "total \$(total.to_str {})" }




`,
      output: "olive oil: 2400\nza’atar: 1500\ntotal 4350",
    },
    {
      dish: "mint",
      label: "Abilities",
      blurb: "One interface, any of your types",
      code: `use std::effects::log::{ Console, println }

# One interface, implemented for two unrelated types.
ability Priced for 'a where {
  cents: { it: 'a } -> Int
}

type Book = { title: Str, pounds: Int }
type Coffee = { shots: Int }

impl Priced for Book where { cents = { it } -> it.pounds * 100 }
impl Priced for Coffee where { cents = { it } -> it.shots * 175 }

let main = {} -> perform Console in do
  println { msg = { title = "Dune", pounds = 12 }.cents {}.to_str {} }
  println { msg = { shots = 2 }.cents {}.to_str {} }




`,
      output: "1200\n350",
    },
    {
      dish: "aubergine",
      label: "Exceptions",
      blurb: "Throw once, choose the shape",
      code: `use std::effects::exception::{ Exception, ExceptionToResult }
use std::effects::log::{ Console, println }

type Err = | Missing { key: Str } |

let lookup = { key } -> match key is
  "host" -> Ok { val = "mezze-lang.org" }
  "port" -> Ok { val = "443" }
  _ -> Err { error = Missing { key } }

# Each .try {} unwraps, or short-circuits the whole block.
let config = {} -> do
  let host = lookup { key = "host" }.try {}
  let port = lookup { key = "port" }.try {}
  Ok { val = "\$(host):\$(port)" }

let main = {} -> perform Console in do
  let ok = perform ExceptionToResult in config {}
  println { msg = ok.to_dbg_str {} }

`,
      output: "Ok { val = \"mezze-lang.org:443\" }",
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
    title: 'Swap out the ~tomato~side effects~ when you need to',
    body:
      'The same function runs against the real filesystem or an in-memory one, a live ' +
      'clock or a frozen one. Tests get a different ~~tomato:handler~~, not different code.',
  },
  {
    title: 'A few small pieces, ~onion~reused everywhere~',
    body:
      'Records at core, arguments are just ~~onion:records~~, so every call names its ' +
      'fields. Effects are records of functions and so are Abilities. Learn record, variant, effect, and ability and you have the language covered.',
  },
  {
    title: 'First-class ~olive~continuations~',
    body:
      'Async, exceptions, and early return are ~~olive:ordinary functions~~ here, not syntax language ' +
      'had to grow. Write your own control flow and it looks like everyone else\'s.',
  },
  {
    title: 'Structured concurrency, ~mint~built on Loom~',
    body:
      'Millions of ~~mint:virtual threads~~, spawning a new one is cheap. ' +
      'Software Transactional Memory, Atoms, Channels, Cancellation all built to work together.',
  },
  {
    title: 'Competitively fast on ~auber~GraalVM~',
    body:
      'Optimised data structures, loop fusion, and safe mutation effect. AOT ~~aubergine:native binaries~~ when you want them.',
  },
  {
    title: 'Keep the libraries you already have^TBD^',
    body:
      'Mezze runs on GraalVM, so calling the Java, Kotlin, Python, and Javascript you already have ' +
      'is the plan. Designed, not yet shipped.',
  },
  {
    title: 'Distributed computing^TBD^',
    body:
      'Durable workflows and distributed execution, built on the same effect system. ' +
      'Designed, not yet shipped.',
  },
]
