---
title: "Effects"
blurb: "What a function does to the world, in its type."
---
`<Log>` on the return type is an **effect row**. It says this function talks to the outside world, and that travels up to everyone who calls it.

A **handler** decides what the effect means. `Console` prints; `QuietConsole` discards. The function itself does not change.

Run it: only one line appears. The second call was handled into silence.

<!--mezze-editor
use std::effects::log::{ Console, QuietConsole, println }

# audit says <Log> in its type. It never says where the line goes.
let audit = { who } -> println { msg = "$(who) signed in" }

let main = {} -> do
  perform Console in audit { who = "ada" }

  # Same function, a handler that discards every line.
  perform QuietConsole in audit { who = "grace" }
-->
