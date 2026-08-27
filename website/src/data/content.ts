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
  caption: 'Live — the real compiler, compiled to WebAssembly.',
  tabs: [
    {
      label: 'Effects',
      code: `use std::effects::log::{ Console, QuietConsole, println, info }

let process = { id, total } -> do
  info { msg = "handling order #$(id.to_dbg_str {})" }
  total

# the same function under two handlers
let main = {} -> perform Console in do
  let a = perform Console in process { id = 1, total = 40 }
  let b = perform QuietConsole in process { id = 2, total = 2 }
  println { msg = "total = $((a + b).to_dbg_str {})" }`,
    },
    {
      label: 'Records',
      code: `use std::effects::log::{ Console, println }

# structural — anything with a name field fits
let greet = { who } -> "hello, $(who.name)!"

let main = {} -> perform Console in
  println { msg = greet { who = { name = "friend", age = 30 } } }`,
    },
    {
      label: 'Abilities',
      code: `use std::effects::log::{ Console, println }

type Vec2 = | Vec2 { x: Int, y: Int } |

impl Vec2 where {
  len_sq = { it } -> match it is
    Vec2 { x, y } -> x * x + y * y,
}

let main = {} -> perform Console in
  println { msg = "len_sq = $(Vec2 { x = 3, y = 4 }.len_sq {}.to_dbg_str {})" }`,
    },
    {
      label: 'Signatures',
      code: `use std::effects::log::{ Log, Console, println }

# 'a is a type variable: describe works for any type that can print itself.
let describe: { it: 'a, loud: Bool } -> Str =
  { it, loud } -> do
    let name = it.to_dbg_str {}
    match loud is
      True -> name.to_upper {}
      False -> name

# <Log> is the effect row: this function logs, and the type says so.
let shout: { msg: Str } -> {} <Log> =
  { msg } -> println { msg = msg }

let main = {} -> perform Console in do
  shout { msg = describe { it = "mezze", loud = True } }
  shout { msg = describe { it = "mezze", loud = False } }
  shout { msg = describe { it = 42, loud = False } }`,
    },
  ],
}

/** One per dish, in cycle order — the mark colour follows the palette. */
export const features = [
  {
    title: 'Dot-chaining on ~olive~any~ type',
    body:
      'Abilities give ad-hoc polymorphism with associated types. Type a dot, ' +
      'see what a value can do.',
  },
  {
    title: 'Every argument has a ~lemon~name~',
    body:
      'Functions take records, so nothing depends on argument order and adding ' +
      'a field never breaks a call site.',
  },
  {
    title: 'Effects are ~onion~in the type~',
    body:
      'What a function does to the world is part of its signature, not a ' +
      'convention. Handlers decide what those effects mean.',
  },
  {
    title: 'Types you never ~tomato~write down~',
    body:
      'Full inference across the program. Annotate where it helps a reader, ' +
      'not to satisfy the compiler.',
  },
  {
    title: 'Sits beside your ~mint~Java~',
    body:
      'GraalVM underneath. Call the Java, Kotlin, Python and JavaScript you ' +
      'already have.',
  },
  {
    title: 'Mutation that ~auber~stays put~',
    body:
      'In-place updates behind a hermetic effect. Fast loops, with the type ' +
      'system keeping them from escaping.',
  },
]

