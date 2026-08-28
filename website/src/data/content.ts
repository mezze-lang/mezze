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
  headline: 'Write what it ~onion~does~.\nLet the types ~~follow~~.',
  lede:
    'Effects are part of the signature — files, logging, tasks, failure. ' +
    'You never annotate one. Structural types, full inference, running on the JVM.',
  primaryCta: { label: 'Taste of Mezze', href: '/taste' },
  footnote: 'Runs in your browser. Nothing to install to try it.',
}

export const topics = [
  { label: 'abilities', colour: 'olive' },
  { label: 'records', colour: 'lemon' },
  { label: 'effects', colour: 'onion' },
  { label: 'types', colour: 'tomato' },
  { label: 'async', colour: 'mint' },
  { label: 'mutation', colour: 'auber' },
] as const

export const playground = {
  caption: 'Every sample here was run through the compiler — the output is what it printed.',
  tabs: [
    {
      label: 'Effects',
      code: `use std::effects::log::{ Log, Console, QuietConsole, println }
use std::int

# The <Log> in this type is the point: this function reaches the outside
# world, and the signature has to say so.
let charge: { order: Int, amount: Int } -> {} <Log> = { order, amount } ->
  println { msg = "charging #$(int::to_str {n = order}): $(int::to_str {n = amount})" }

let main = {} -> do
  # Same function, two handlers. The caller decides what logging means.
  perform Console in charge { order = 1001, amount = 4200 }
  perform QuietConsole in charge { order = 1002, amount = 900 }
  perform Console in println { msg = "done" }`,
      output: 'charging #1001: 4200\ndone',
    },
    {
      label: 'Records',
      code: `use std::effects::log::{ Console, println }
use std::int

type Order = { id: Int, total: Int, currency: Str }

# Arguments are named, so the call site says what each number means.
let summarise: { order: Order } -> Str = { order } ->
  "#$(int::to_str {n = order.id}) — $(int::to_str {n = order.total}) $(order.currency)"

let main = {} -> perform Console in do
  let o = { id = 1001, total = 4200, currency = "EUR" }
  println { msg = summarise { order = o } }

  # Copy with one field changed; the rest come along untouched.
  println { msg = summarise { order = { ...o, total = 0 } } }`,
      output: '#1001 — 4200 EUR\n#1001 — 0 EUR',
    },
    {
      label: 'Async',
      code: `use std::effects::async::{ Async, LoomAsync }
use std::effects::log::{ Log, Console, println }
use std::result::{ get_or_else }
use std::int

let fetch_user: { id: Int } -> Str <Log> = { id } -> do
  println { msg = "fetching user $(int::to_str {n = id})" }
  "ada"

let fetch_orders: { id: Int } -> Int <Log> = { id } -> do
  println { msg = "fetching orders for $(int::to_str {n = id})" }
  3

let main = {} -> perform Console in perform LoomAsync in do
  # Both start now; neither waits for the other.
  let user = Async.spawn { f = {} -> fetch_user { id = 7 } }
  let orders = Async.spawn { f = {} -> fetch_orders { id = 7 } }

  # await returns a Result, so a failed task cannot be dropped by accident.
  let name = get_or_else { result = user.await {}, def = "unknown" }
  let count = get_or_else { result = orders.await {}, def = 0 }
  println { msg = "$(name) has $(int::to_str {n = count}) orders" }`,
      output: 'fetching user 7\nfetching orders for 7\nada has 3 orders',
    },
    {
      label: 'Abilities',
      code: `use std::effects::log::{ Console, println }
use std::int

# One ability, one method. The receiver is named it.
ability Money for 'a where {
  cents: { it: 'a } -> Int
}

# A plain record — no wrapper needed to give it behaviour.
type Eur = { amount: Int }

impl Money for Eur where {
  cents = { it } -> it.amount
}

# Works for any type with a Money impl, including ones added later.
let describe: { it: 'a } with Money 'a -> Str = { it } ->
  "$(int::to_str {n = it.cents {}}) cents"

let main = {} -> perform Console in do
  println { msg = describe { it = { amount = 4200 } } }`,
      output: '4200 cents',
    },
  ],
}

/** One per dish, in cycle order — the mark colour follows the palette. */
export const features = [
  {
    title: 'Stop guessing what a value ~olive~can do~',
    body:
      'Type a dot and the editor lists every operation that applies — including ' +
      'ones you defined yourself, on types you did not write.',
  },
  {
    title: 'Never break a caller by ~lemon~adding a field~',
    body:
      'Arguments are named, always. Add one, reorder them, and every existing ' +
      'call site keeps compiling. No positional arguments to get wrong.',
  },
  {
    title: 'Know what a function ~onion~touches~ before you call it',
    body:
      'If it writes files or hits the network, that is in its type. Nothing ' +
      'reaches the outside world without saying so first.',
  },
  {
    title: 'Tests that cannot reach ~tomato~production~',
    body:
      'Swap the handler, not the code. The same function logs to a console, a ' +
      'buffer, or nowhere — decided by the caller, checked by the compiler.',
  },
  {
    title: 'Keep the libraries you ~mint~already have~',
    body:
      'Runs on GraalVM, so your Java and Kotlin come with you. No rewrite, no ' +
      'bindings to maintain.',
  },
  {
    title: 'Fast loops without ~auber~losing track~',
    body:
      'Mutate in place where it counts. The type system makes sure none of it ' +
      'escapes the scope you allowed it in.',
  },
]

