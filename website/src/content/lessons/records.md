---
title: "Records"
blurb: "The parameter list was a record all along."
---
Four loose bindings for one ingredient was always a bit of a fiction. They belong together, and a **record** is that: one value with named fields.

You have already written one. `{ grams, servings }` in `scale` is a record — the parameter list and the value share a syntax, because they are the same thing. Every call in this language passes exactly one record.

Two things follow. `{ ...chickpeas, name = "butter beans" }` builds a **copy** with some fields changed, leaving the original untouched, which is how you substitute an ingredient without editing the pantry. And `let { name, grams, ... } = chickpeas` takes one apart again, using the shape a parameter list already uses. The `...` is required unless you name every field — the compiler wants to know you meant to ignore the rest.

<!--mezze-editor
use std::effects::log::{ Console, println }

# The same facts, gathered into one value.
let chickpeas = { name = "chickpeas", grams = 400, euros = 1.90 }

let scale = { grams, servings } -> grams * servings / 4

let main = {} -> perform Console in do
  println { msg = "$(chickpeas.name): $(chickpeas.grams.to_str {})g" }

  # Out of chickpeas. Substitute, changing only what differs.
  let butter_beans = { ...chickpeas, name = "butter beans", euros = 2.40 }
  println { msg = "$(butter_beans.name): €$(butter_beans.euros.to_str {})" }
  println { msg = "still have: $(chickpeas.name)" }

  # Take one apart. `...` says: ignore the fields I did not name.
  let { name, grams, ... } = chickpeas
  println { msg = "for two: $(name), $(scale { grams = grams, servings = 2 }.to_str {})g" }
-->
