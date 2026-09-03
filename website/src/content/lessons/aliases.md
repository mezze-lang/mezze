---
title: "Type aliases"
blurb: "Naming a shape you use more than once."
---
`type Reading = { city: Str, temp: Int }` gives a name to a shape.

The alias is **transparent**: a `Reading` *is* that record, not a wrapper around it. Any value with the right fields fits, whether or not it was built with the name in mind.

So an alias is documentation the compiler checks, not a new type to convert in and out of.

<!--mezze-editor
use std::effects::log::{ Console, println }

# An alias names a shape so you say it once.
type Reading = { city: Str, temp: Int }

# A Reading IS that record, not a wrapper —
# anything with the right fields fits.
let describe: { r: Reading } -> Str = { r } ->
  "$(r.city): $(r.temp.to_str {})°C"

let main = {} -> perform Console in do
  let lisbon = { city = "Lisbon", temp = 21 }
  println { msg = describe { r = lisbon } }

  let cairo = { city = "Cairo", temp = 35 }
  println { msg = describe { r = cairo } }
-->
