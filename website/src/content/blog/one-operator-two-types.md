---
title: One operator, two types
blurb: Numeric operators used to demand both sides match. Now they carry the argument type and the result type, so Int * Float works and so does Length * Length = Area.
date: 2026-09-01
colour: olive
draft: true
---

Mezze's arithmetic operators desugar to abilities. `*` calls `NumMul`, `+`
calls `NumAdd`, and the same holds for `-`, `/` and `%`. If a type implements
the ability, the operator works on it. If it does not, the operator is a type
error, and the error names the impl you would have to write.

That much has always been true. What changed is the shape of the ability.

## The old shape

`NumMul` used to take one type parameter:

<!--mezze-code
ability NumMul for 'a where {
  mul: { it: 'a, r: 'a } -> 'a
}
-->

Read the operation signature and the restriction falls out. Both operands are
`'a` and the result is `'a`, so multiplication was a closed operation: same type
in on both sides, same type out.

For `Int * Int` that is exactly right. For `Float * Float` too. And the payoff
was real, because you could ==onion:reach for the same operator on your own
types==:

<!--mezze-code
newtype Money = Int

impl NumAdd for Money where {
  add = { it, r } -> ...
}
-->

Two prices add to a price, so `Money + Money -> Money` is honest, and writing
that impl let you use `+`. Meanwhile `Money * Money` stayed a type error, which
is also honest, because there is no such thing as a squared price. You opted
into the operators that made sense for your type and left the rest alone.

## Where it broke down

Three ordinary things were impossible.

`Int * Float`. Both are numbers, everyone expects the product, and neither is
the other's type. Under the old shape you would have needed `Int` and `Float`
to be the same `'a`, which they are not.

`Money * Int`. Three coffees at 350 each is a price. The operands are different
types, and one of them is not money at all.

And the interesting one: `Length * Length`. Multiplying two lengths is
perfectly meaningful, but the answer ~is not a length~. It is an area. The old
signature could not say that, because the result was pinned to `'a` along with
both operands.

Notice these are three different failures. The first two are about the operands
differing from each other. The third is about the ==lemon:result differing from
the operands==. A fix that only handled one of them would have left the others.

## The new shape

`NumMul` now carries both:

<!--mezze-code
ability NumMul 'r for 'l having 'out where {
  mul: { it: 'l, r: 'r } -> 'out
}
-->

Three names instead of one. `'l` is the left operand, the type the impl is
*for*. `'r` is the right operand, a parameter of the ability. `'out` is an
associated type, fixed by each impl through `having 'out = ...`.

That is the whole change. Everything below follows from it.

## What the stdlib does with it

`Int * Float` and `Float * Int` both work now, and so does mixed addition:

<!--mezze-editor
use std::effects::log::{ Console, println }

let main = {} -> perform Console in do
  println { msg = (2 * 1.5).to_str {} }
  println { msg = (1.5 * 2).to_str {} }
  println { msg = (7 + 0.5).to_str {} }
-->

Press Run. Every combination of `Int`, `Nat` and `Float` ships across all four
operators. There is no coercion rule in the compiler and no promotion pass. The
conversion is explicitly written in the impl, in Mezze, and you can read it:

<!--mezze-code
impl NumAdd Float for Int having 'out = Float where {
  add = { it, r } -> it.to_float {} + r
}
-->

`it` is the `Int`, `r` is the `Float`. Widen the `Int` with `to_float {}`, then
add two `Float`s, which is a different impl. `Int` to `Float` is the safe
direction, so nothing is lost. `0.5 + 7` is a separate impl on `Float` that
widens the other operand, `it + r.to_float {}`, and the stdlib ships both.

`Duration` shows the result type doing work of its own. `Instant - Instant`
gives a `Duration`, not an `Instant`. Subtracting two points in time gives you
a gap, and the signature can now say so.

## Your own types

The `Money` impl is barely different, just more explicit about what it returns:

<!--mezze-editor
use std::effects::log::{ Console, println }
use std::abilities::numeric::{ NumAdd, NumMul }

newtype Money = Int

# Money.(n) in a pattern position unwraps; in an expression it wraps.
let cents = { m } -> match m is
  Money.(n) -> n

impl NumAdd Money for Money having 'out = Money where {
  add = { it, r } -> Money.(cents { m = it } + cents { m = r })
}

impl NumMul Int for Money having 'out = Money where {
  mul = { it, r } -> Money.(cents { m = it } * r)
}

let main = {} -> perform Console in do
  let coffee = Money.(350)
  let cake = Money.(475)
  println { msg = (coffee + cake).to_dbg {} }
  println { msg = (coffee * 3).to_dbg {} }
-->

Two impls, two different shapes. Adding money to money gives money. Multiplying
money by a plain `Int` gives money. The second one was not expressible before,
because `Int` and `Money` could not both be `'a`.

What has not changed is the part worth keeping. `Money * Money` is still a type
error:

<!--mezze-editor
use std::effects::log::{ Console, println }
use std::abilities::numeric::{ NumAdd }

newtype Money = Int

let cents = { m } -> match m is
  Money.(n) -> n

impl NumAdd Money for Money having 'out = Money where {
  add = { it, r } -> Money.(cents { m = it } + cents { m = r })
}

let main = {} -> perform Console in do
  let coffee = Money.(350)
  println { msg = (coffee * coffee).to_dbg {} }
-->

Press Check on that one. The compiler tells you what is missing and what you
would write to allow it, and then lists the impls that do exist.

Nothing about the new shape makes a nonsense operation legal. It only stops the
ability from ==mint:forbidding sensible ones by accident==.

## The one that needed all three names

Here is where `'out` stops being bookkeeping. Multiply two lengths and you get
an area, a genuinely different type:

<!--mezze-editor
use std::effects::log::{ Console, println }
use std::abilities::numeric::{ NumMul }

newtype Length = Float
newtype Area = Float

let metres = { l } -> match l is
  Length.(n) -> n

impl NumMul Length for Length having 'out = Area where {
  mul = { it, r } -> Area.(metres { l = it } * metres { l = r })
}

let main = {} -> perform Console in do
  let wall = Length.(4.0)
  let height = Length.(2.5)
  println { msg = (wall * height).to_dbg {} }
-->

`impl NumMul Length for Length having 'out = Area`. Both operands are the same
type and the result is not, which is the case the old signature could not
express at all. Add `Volume` and an `Area * Length` impl and the units keep
checking themselves as you go.

This is dimensional analysis with no library and no macro, just the operator
sugar plus an associated type. The compiler will not let you add a `Length` to
an `Area` unless you write the impl saying what that means, and you will not,
because it does not mean anything.

## What it cost

Existing impls had to be rewritten, and the old single-parameter form is now a
hard error rather than a deprecation. Given how early Mezze is, a clean break
beat carrying two spellings of the same ability. The stdlib went from a handful
of same-type impls to the full matrix across `Int`, `Nat` and `Float`, which is
more lines but no more concepts.

The other cost is that a signature reads as busier. `NumMul 'r for 'l having
'out` asks more of a reader than `NumMul for 'a`.
