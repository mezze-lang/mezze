---
title: "Functions"
blurb: "Named parameters, always."
---
The recipe serves four. Cooking for two means scaling every quantity, which is the first thing worth naming: `scale`.

A function is `{ params } -> body`, and every parameter is **named**. Mezze has no positional arguments at all, so there is no argument order to remember and no way to pass the servings where the grams should go. Both calls below are the same call.

The gain is at the edges. Add a parameter next month — a wastage factor, say — and every existing call still compiles, because none of them depended on position. That is a trade positional arguments cannot make.

<!--mezze-editor
use std::effects::log::{ Console, println }

# The recipe serves four; scale it to any number.
let scale = { grams, servings } -> grams * servings / 4

let main = {} -> perform Console in do
  println { msg = "for two:  $(scale { grams = 400, servings = 2 }.to_str {})g" }

  # The same call, written the other way round.
  println { msg = "for six:  $(scale { servings = 6, grams = 400 }.to_str {})g" }
-->
