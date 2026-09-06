---
title: "Abilities"
blurb: "One question, asked of types that answer differently."
---
The pantry has been pretending every ingredient is priced the same way. It is not. Feta is sold by weight, pita by the piece, and the two have no field in common to read — working out what either costs is a different calculation.

An **ability** is that question, written once. `ability Cost` declares the interface; each `impl` answers it for one type, including types you did not write. The receiver is `it`, exactly as in the inherent impl on the last page — the difference is that many types can implement the same ability.

`line` is the payoff. It never mentions `ByWeight` or `ByUnit`: it asks for a price and formats it. Add a third way of pricing something next month, give it an `impl`, and `line` handles it untouched.

<!--mezze-editor
use std::effects::log::{ Console, println }

# The question, asked once.
ability Cost for 'a where { euros: { it: 'a } -> Float }

# Two ways a shop prices things. No shared field.
type ByWeight = { name: Str, kilos: Float, per_kilo: Float }
type ByUnit   = { name: Str, count: Float, each: Float }

# Same question, genuinely different arithmetic.
impl Cost for ByWeight where { euros = { it } -> it.kilos * it.per_kilo }
impl Cost for ByUnit   where { euros = { it } -> it.count * it.each }

# Names neither type. Works for both.
let line = { x } -> "€$(x.euros {}.to_str {})"

let main = {} -> perform Console in do
  println { msg = line { x = { name = "feta", kilos = 0.25, per_kilo = 16.0 } } }
  println { msg = line { x = { name = "pita", count = 6.0, each = 0.45 } } }

  # `to_dbg` needs no impl at all: Inspect is the one
  # ability the compiler derives for every type.
  println { msg = { name = "sumac", kilos = 0.02, per_kilo = 90.0 }.to_dbg {} }
-->

Note what `line` does *not* do: read a field. Adding `x.name` would narrow `x` to a record that has a `name`, and that shape has no `Cost` impl — the constraint is the whole interface, not a starting point to add fields to.
