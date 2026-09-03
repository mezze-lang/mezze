---
title: "Open and closed records"
blurb: "Deciding what a function will accept."
---
A closed parameter says exactly which fields it takes, and **rejects** anything wider. Try passing `big` to `closed`.

Adding `...` opens it: `{ id: Int, ... }` means *at least* this field. Now a caller can hand over a record with more in it.

Open where you want callers to grow their data without breaking you; closed where the exact shape is the point.

<!--mezze-editor
use std::effects::log::{ Console, println }

# Closed: exactly these fields, nothing more.
let closed: { r: { id: Int } } -> Int = { r } -> r.id

# `...` opens it — the caller may pass extras.
let open: { r: { id: Int, ... } } -> Int = { r } -> r.id

let main = {} -> perform Console in do
  let small = { id = 1 }
  let big = { id = 2, name = "ada", active = True }

  # Only the open one accepts the wider record.
  println { msg = closed { r = small }.to_str {} }
  println { msg = open { r = big }.to_str {} }
-->
