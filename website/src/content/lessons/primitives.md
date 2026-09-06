---
title: "Values and types"
blurb: "A few facts, and the types nobody wrote."
---
Over these nine pages you will build a small shop-and-kitchen program. It starts with one ingredient, described as loosely as possible: four separate facts, none of them saying what they are.

They have exact types all the same — `Str`, `Int`, `Float`, `Bool` — because the compiler reads them off the values. Hover any name to see what it inferred. You will not write a type annotation until page six, and even then only because it makes the code clearer to a person.

The one strictness here is `$(...)`. It interpolates a `Str` and refuses anything else, so a number has to say `to_str {}` out loud. That is deliberate: it means a stray value never quietly becomes text in a line someone reads later.

This editor is live. Change a value and press Run.

<!--mezze-editor
use std::effects::log::{ Console, println }

# One ingredient, as a handful of separate facts.
# Nothing here says what they are. Hover to see.
let name = "chickpeas"
let grams = 400
let euros = 1.90
let vegetarian = True

let main = {} -> perform Console in do
  println { msg = "$(name): $(grams.to_str {})g, €$(euros.to_str {})" }
  println { msg = "vegetarian: $(vegetarian.to_str {})" }
-->
