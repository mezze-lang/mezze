---
title: "Aliases and newtypes"
blurb: "Naming a shape, or making a genuinely new one."
---
The ingredient shape has appeared on every page now, so it has earned a name. `type Ingredient = { … }` gives it one — and nothing more. The alias is **transparent**: an `Ingredient` *is* that record, so any value with the right fields fits, whether or not it was written with the name in mind. It is documentation the compiler checks, not a box to put things in.

`newtype Grams = Int` is the other choice, and it does make a new type. A `Grams` is **not** an `Int`. Pass one where a count or a price is expected and it will not compile, which is the entire point — this kitchen has too many numbers for them all to be interchangeable.

You pay for it at the boundary: getting the number back means matching `Grams.(n)`. Reach for a newtype when confusing two values would be a real bug, and an alias when you only wanted a shorter way to say the same shape.

<!--mezze-editor
use std::effects::log::{ Console, println }

# The shape has earned a name. An Ingredient IS
# that record — anything with the fields fits.
type Ingredient = { name: Str, grams: Int, euros: Float }

# A newtype is different: Grams is NOT an Int, so it
# cannot be swapped with a count or a price by mistake.
newtype Grams = Int

let weigh = { g } -> match g is
  Grams.(n) -> n

let label = { i : Ingredient } -> "$(i.name) $(i.grams.to_str {})g"

let main = {} -> perform Console in do
  # Never mentions Ingredient, and is one.
  println { msg = label { i = { name = "tahini", grams = 60, euros = 4.50 } } }

  let flour = Grams.(500)
  println { msg = "flour: $(weigh { g = flour }.to_str {})g" }
-->
