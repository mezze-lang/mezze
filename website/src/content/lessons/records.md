---
title: "Records"
blurb: "The parameter list was a record all along."
---
That parameter list — `{ val, low, high }` — is a **record**: one value with named fields. Records are that same shape standing on their own.

`{ ...point, x = 10 }` copies with one field changed. The original is untouched; nothing mutates.

And `let { x, y } = point` destructures using the syntax a parameter list already uses. One idea, three places.

<!--mezze-editor
use std::effects::log::{ Console, println }

# That parameter list was a record all along:
# one value with named fields.
let point = { x = 3, y = 4 }

let main = {} -> perform Console in do
  println { msg = "x = $(point.x.to_str {})" }

  # Copy with a change; the original is untouched.
  let moved = { ...point, x = 10 }
  println { msg = "moved x = $(moved.x.to_str {})" }

  # Destructuring uses the same shape again.
  let { x, y } = point
  println { msg = "$(x.to_str {}), $(y.to_str {})" }
-->
