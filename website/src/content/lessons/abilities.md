---
title: "Abilities"
blurb: "Inspect is derived; to_str is not."
---
`Inspect` is special: it is the one ability the compiler **derives for every type**, so `.to_dbg {}` works on anything, including a type you declared a line ago.

`to_str` is **not** an ability. Each type defines its own as an inherent method, which is why `Int` has one and `User` does not.

A dot call resolves against the receiver's type either way. The next lesson defines an ability of your own.

<!--mezze-editor
use std::effects::log::{ Console, println }

type User = { name: Str, age: Int }

let main = {} -> perform Console in do
  let u = { name = "ada", age = 36 }

  # Inspect is the one ability the compiler
  # derives for every type, so this always works.
  println { msg = u.to_dbg {} }

  # to_str is not an ability. Each type defines
  # its own, which is why Int has one and User
  # does not.
  println { msg = (36).to_str {} }
-->
