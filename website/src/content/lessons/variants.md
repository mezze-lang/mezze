---
title: "Variants"
blurb: "One value, one of several situations."
---
Knowing an ingredient is not the same as knowing you can cook with it. Stock is the awkward part: sometimes it is a quantity on the shelf, sometimes a date it comes back, sometimes nothing at all because the line was dropped.

A record cannot say that honestly. You would end up with a `grams` that means nothing when the item is out, a `day` that means nothing when it is in, and a comment explaining which to trust. A **variant** says *or* where a record says *and*, and each case carries only what is actually true in that situation — `OutUntil` has a day and no grams, `Discontinued` has neither.

`match` gets you back in, and the payoff is `can_cook` rather than a string: the cases decide something. Because the type is closed, the compiler checks you considered all of them. Delete the `Discontinued` arm and press Check — it names what is missing instead of letting the answer quietly default to something plausible.

<!--mezze-editor
use std::effects::log::{ Console, println }

# What we know about an ingredient's availability.
# Each case carries what is true in that situation.
type Stock = |
  InStock { grams: Int },
  Low { grams: Int },
  OutUntil { day: Str },
  Discontinued
|

# The cases decide something, not just how it reads.
let can_cook = { s } -> match s is
  InStock { grams } -> grams > 100
  Low { grams } -> grams > 100
  OutUntil { day = _ } -> False
  Discontinued -> False

let note = { s } -> match s is
  InStock { grams } -> "$(grams.to_str {})g on the shelf"
  Low { grams } -> "only $(grams.to_str {})g left"
  OutUntil { day } -> "back on $(day)"
  Discontinued -> "gone for good"

let main = {} -> perform Console in
  [
    { name = "chickpeas", stock = InStock { grams = 400 } },
    { name = "tahini",    stock = Low { grams = 60 } },
    { name = "sumac",     stock = OutUntil { day = "Tuesday" } },
    { name = "mastic",    stock = Discontinued },
  ].for_each { f = { x } -> do
      let mark = if can_cook { s = x.stock } then "cook" else "skip"
      println { msg = "$(mark)  $(x.name): $(note { s = x.stock })" }
  }
-->
