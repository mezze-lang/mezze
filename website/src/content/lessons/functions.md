---
title: "Functions"
blurb: "Named parameters, always."
---
A function is `{ params } -> body`. Every parameter is **named** — Mezze has no positional arguments at all.

So a call reads the way it is written, and the order is yours: both calls below do the same thing.

Add a parameter later and existing calls keep compiling. That is the trade positional arguments cannot make.

<!--mezze-editor
use std::effects::log::{ Console, println }

# Every parameter is named. Mezze has no
# positional arguments at all.
let rect = { width, height } -> width * height

let main = {} -> perform Console in do
  let a = rect { width = 3, height = 4 }
  println { msg = a.to_str {} }

  # Same call, fields the other way round.
  let b = rect { height = 4, width = 3 }
  println { msg = b.to_str {} }
-->
