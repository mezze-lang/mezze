---
title: "Variants"
blurb: "Open and closed sets of cases."
---
A **variant** lists its cases between pipes, separated by commas. Some carry a payload, some do not.

A trailing `...` leaves the variant **open** — a value may be a case listed here or one added elsewhere. Without it the type is closed.

Closed means `match` must cover every case: delete the `Point` arm and press Check. An open variant needs a catch-all instead, since the compiler cannot know every case.

<!--mezze-editor
use std::effects::log::{ Console, println }

# Cases sit between pipes, separated by commas.
type Shape = |
  Circle { radius: Int },
  Rect { w: Int, h: Int },
  Point
|

# A trailing `...` leaves a variant open.
type Level = | Low, High, ... |

let area = { shape } -> match shape is
  Circle { radius } -> radius * radius * 3
  Rect { w, h } -> w * h
  Point -> 0

let main = {} -> perform Console in do
  # Closed, so match must cover every case.
  let c = area { shape = Circle { radius = 5 } }
  println { msg = "circle: $(c.to_str {})" }
-->
