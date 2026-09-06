---
title: "Methods and chaining"
blurb: "Give the type its own verbs, then line them up."
---
`label` was a loose function that only ever applies to one type. `impl Ingredient where { … }` moves it where it belongs: methods of its own, with the receiver named `it`.

They are called with a dot, which means they line up. `chickpeas.for_two {}.label {}` reads left to right, in the order the work actually happens — double the quantity, then describe it — instead of inside-out as `label { i = for_two { i = chickpeas } }` would.

And the pantry is a list now. `filter`, `map` and `sum_float` are ordinary stdlib functions, not syntax, called with the same dot on a seq. Note `label` appearing inside that chain: a method you defined two lines up composes with library code without any arrangement between them.

<!--mezze-editor
use std::effects::log::{ Console, println }

type Ingredient = { name: Str, grams: Int, euros: Float }

# Methods belonging to this type. `it` is the receiver.
impl Ingredient where {
  label = { it } -> "$(it.name) $(it.grams.to_str {})g",
  for_two = { it } -> { ...it, grams = it.grams * 2 }
}

let pantry = [
  { name = "chickpeas", grams = 400, euros = 1.90 },
  { name = "tahini",    grams = 60,  euros = 4.50 },
  { name = "olive oil", grams = 200, euros = 8.99 },
]

let main = {} -> perform Console in do
  # Two methods, left to right in the order they happen.
  let chickpeas = { name = "chickpeas", grams = 400, euros = 1.90 }
  println { msg = chickpeas.for_two {}.label {} }

  # The same dot, now over a whole list.
  pantry
    .filter { p = { x } -> x.grams > 100 }
    .map { f = { x } -> x.label {} }
    .for_each { f = { x } -> println { msg = x } }

  let bill = pantry.map { f = { x } -> x.euros }.sum_float {}
  println { msg = "bill: €$(bill.to_str {})" }
-->
