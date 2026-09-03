---
title: "Your own effects"
blurb: "Effects are library code, not syntax."
---
Effects are not built in. `effect Config` declares one: a name, and the operations it offers.

The handler decides what it means. `Defaults` answers from a lookup; another handler could read a file or a flag, and `greet` would not change.

Exceptions, early return and retries are all ordinary code here. There is no syntax to wait for.

<!--mezze-editor
use std::effects::log::{ Console, println }

# Effects are not built in — declare your own.
effect Config where {
  get: { key: Str } -> Str <Config>
}

# A handler gives the operation its meaning.
impl Config for Defaults where {
  get = { key } -> if key == "host" then "localhost" else "?"
}

let greet = {} -> do
  let host = Config.get { key = "host" }
  "connecting to $(host)"

let main = {} ->
  perform Console in perform Defaults in
    println { msg = greet {} }
-->
