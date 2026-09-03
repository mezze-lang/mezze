---
title: "Values and types"
blurb: "Bindings, inference, and a live editor."
---
This editor is **live**. Change anything and press Run, or hover a name to see the type the compiler inferred for it — no annotations required.

Everything starts with `let`. None of these bindings say what they are, yet all of them have exact types: `Str`, `Float`, `Bool`, and `[Int]` for the seq.

Interpolation with `$(...)` takes a `Str` and nothing else. Strict at first glance; it means a stray value never quietly becomes text in a log line.

A seq holds one type, and methods chain straight off it with a dot. You will see that same `.method {}` shape on every kind of value here.

<!--mezze-editor
use std::effects::log::{ Console, println }

# This editor is live. Change anything and press
# Run, or hover a name to see its inferred type.
let name = "Mezze"
let release = 0.1
let ready = False
let versions = [1, 2, 3]

let main = {} -> perform Console in do
  # Interpolation takes a Str and nothing else, so
  # every value says how it becomes one.
  println { msg = "$(name) $(release.to_str {}), ready: $(ready.to_str {})" }

  # A seq holds one type. Methods chain off it.
  println { msg = "$(versions.size {}.to_str {}) releases so far" }
-->
