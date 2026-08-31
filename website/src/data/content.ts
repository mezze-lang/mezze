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
    'Type safety that stays out of your way. Swap out effects when you need to. Built for concurrent and distributed programming. Ship a native binary or a polyglot jar, whichever the job wants.',
  tabs: [
    {
      dish: "olive",
      label: "Effects",
      blurb: "Mock it or mean it",
      code: `use std::effects::log::{ Console, println }
use std::effects::http::{ HttpClient, MockHttpClient, mock_get, request }

# weather says <Http> in its type. It never says which client answers,
# so the caller below picks that, not weather.
let weather = { url } -> request { url }.send {}.text_or { def = "offline" }

let main = {} -> perform Console in do
  let url = "https://wttr.in/Amsterdam?format=3"

  # The real client. This one goes over the network.
  perform HttpClient in println { msg = weather { url } }

  # Same function, same url, no network at all.
  let rules = [mock_get { body = "Amsterdam: Always raining", url = "*" }]
  perform MockHttpClient { rules } in println { msg = weather { url } }
`,
      output: "Amsterdam: 🌦️  +16°C\nAmsterdam: Always raining",
    },
    {
      dish: "lemon",
      label: "Async",
      blurb: "Cheap threads, structured concurrency",
      code: `use std::effects::log::{ Console, println }
use std::effects::async::{ Async, LoomAsync, sleep }
use std::effects::time::{ SystemTime }

let fetch = { url, ms } -> do
  sleep { ms }
  "\$(url) replied"

let main = {} -> perform Console, LoomAsync, SystemTime in do
  let fast = Async.spawn { f = {} -> fetch { url = "eu", ms = 10 } }
  let slow = Async.spawn { f = {} -> fetch { url = "us", ms = 500 } }

  # race returns the first to finish. The loser is cancelled.
  match [fast, slow].race {} is
    Ok { val } -> println { msg = val }
    Err { error } -> println { msg = "both failed: \$(error.to_dbg {})" }
`,
      output: "eu replied",
    },
    {
      dish: "onion",
      label: "Pattern matching",
      blurb: "Miss a case, it will not compile",
      code: `use std::effects::log::{ Console, println }

type Status = | Queued, Shipped { days: Int }, Cancelled { why: Str } |

# One match, four arms. Miss a case and it will not compile.
let describe = { order } -> match order.status is
  Queued -> "\$(order.id) waiting"
  Shipped { days = 0 } -> "\$(order.id) arrives today"
  Shipped { days } -> "\$(order.id) arrives in \$(days.to_str {}) days"
  Cancelled { why } -> "\$(order.id) cancelled: \$(why)"

let main = {} -> perform Console in do
  let order = { id = "A-1", qty = 3, status = Queued }
  println { msg = describe { order } }
  println { msg = describe { order = { ...order, status = Shipped { days = 0 } } } }
  println { msg = describe { order = { ...order, status = Cancelled { why = "stock" } } } }
`,
      output: "A-1 waiting\nA-1 arrives today\nA-1 cancelled: stock",
    },
    {
      dish: "tomato",
      label: "Chaining",
      blurb: "Chain your functions together",
      code: `use std::effects::log::{ Console, println }

let orders = [
  { item = "olive oil", qty = 2, price = 1200 },
  { item = "za'atar",   qty = 5, price = 300 },
  { item = "sumac",     qty = 1, price = 450 },
]

let main = {} -> perform Console in do
  # One pass over the list, each step a method on the last.
  orders
    .map { f = { x } -> "\$(x.item): \$((x.qty * x.price).to_str {})" }
    .for_each { f = { x } -> println { msg = x } }

  let total = orders.map { f = { x } -> x.qty * x.price }.sum {}
  println { msg = "total: \$(total.to_str {})" }
`,
      output: "olive oil: 2400\nza'atar: 1500\nsumac: 450\ntotal: 4350",
    },
    {
      dish: "mint",
      label: "Abilities",
      blurb: "One method, any type",
      code: `use std::effects::log::{ Console, println }

# One method, and two unrelated types can both answer it.
ability Summary for 'a where { summarize: { it: 'a } -> Str }

type Post = { user: Str, body: Str }
type Article = { headline: Str, author: Str }

impl Summary for Post where { summarize = { it } -> "@\$(it.user): \$(it.body)" }
impl Summary for Article where { summarize = { it } -> "\$(it.headline), by \$(it.author)" }

let main = {} -> perform Console in do
  let post = { user = "dev", body = "bug fix and shipped it" }
  println { msg = post.summarize {} }
  let article = { headline = "Mezze 1.0", author = "the team" }
  println { msg = article.summarize {} }
`,
      output: "@dev: bug fix and shipped it\nMezze 1.0, by the team",
    },
    {
      dish: "aubergine",
      label: "Exceptions",
      blurb: "Throw once, the handler decides",
      code: `use std::effects::log::{ Console, println }
use std::effects::exception::{ Exception, ExceptionToResult, ExceptionToOption }

# safe_div throws. It does not decide what a failure looks like,
# so the same function works under either handler below.
let safe_div = { a, b } ->
  if b == 0 then Exception.throw { error = "divide by zero" } else a / b

let main = {} -> perform Console in do
  # This handler turns a throw into an Err, keeping the message.
  let bad = perform ExceptionToResult in safe_div { a = 10, b = 0 }.ok {}
  println { msg = "bad = \$(bad.to_dbg {})" }

  # Same throw, same function. This one gives an Option instead.
  let good = perform ExceptionToOption in safe_div { a = 10, b = 5 }.some {}
  println { msg = "good = \$(good.to_dbg {})" }
`,
      output: "bad = Err { error = \"divide by zero\" }\ngood = Some { val = 2 }",
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
      'fields. Effects are records of functions and so are Abilities. Learn record, variant, effect, ability, and you have the language covered.',
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
