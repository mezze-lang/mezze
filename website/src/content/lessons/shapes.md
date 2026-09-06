---
title: "Shapes without names"
blurb: "You have been using types nobody declared."
---
Look back at what you have written. `{ name = "chickpeas", grams = 400, euros = 1.90 }` had no `type` above it. Neither did the record `scale` takes. They are types all the same — a record **is** its shape, and a `type` declaration only gives that shape a name.

The same is true of variants. The arms of a match are enough to say what a value can be, payloads included, with nothing declared anywhere. Below, `Grams` and `Pinch` exist only because they appear in `show`.

The last arm binds the whole value instead of naming a case, so anything not matched above still has somewhere to go — including `ToTaste`, which is never written down. Take that arm away and the type closes to exactly the cases listed, and passing anything else becomes a compile error that names both sides:

<!--mezze-error
error[T0201]: Field `qty` expects | Grams { n: Int }, Pinch |
but got | ToTaste | in call to `show`
-->

Records have the matching idea: `{ name: Str, ... }` accepts *at least* a name, so a caller may pass a whole ingredient where only the name is needed. You met that `...` already, destructuring on the last page. Open where callers should be free to carry more than you asked for; closed where the exact shape is the point.

<!--mezze-editor
use std::effects::log::{ Console, println }
use std::int

# No type declared. The record IS its shape.
let oil = { name = "olive oil", grams = 200 }

# The match arms are the only place these cases
# appear. Together they say what `qty` accepts.
let show = { qty } -> match qty is
  Grams { n } -> "$(int::to_str { n = n })g"
  Pinch -> "a pinch"
  # Binds the whole value, not just a case name.
  other -> "unknown: $(other.to_dbg {})"

# `...` means at least a name — more is welcome.
let name_of = { r : { name: Str, ... } } -> r.name

let main = {} -> perform Console in do
  println { msg = show { qty = Grams { n = 400 } } }
  println { msg = show { qty = Pinch } }
  # Never mentioned above, and still handled.
  println { msg = show { qty = ToTaste } }

  # A whole ingredient, where only a name was asked for.
  println { msg = name_of { r = oil } }
-->
