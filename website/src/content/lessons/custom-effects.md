---
title: "Your own effects"
blurb: "Effects are library code, not syntax."
---
`Http` is not built into the language. It is a declaration in a library, and you can write your own the same way.

The shop needs one. A delivery arrives and stock has to be recorded — that is an operation the shop *has*, so `effect Inventory` declares it: a name and the operations it offers, nothing about how they work.

The handlers supply that. `ToFile` appends a line to a CSV; `DryRun` says what it would have done and touches nothing. `delivery` is written against neither — it restocks, and has no idea whether a file exists. Which is what makes the dry run possible at all: not a flag inside the function, but a different answer to the same question.

Everything you would reach for syntax to do — exceptions, retries, early return, async — is this shape underneath. There is no keyword to wait for.

<!--mezze-editor
use std::effects::log::{ Console, println }
use std::effects::filesystem::{ MemoryFileSystem, append_text, read_text_or }

# An operation the shop has. Declared, not defined.
effect Inventory where {
  restock: { item: Str, price: Str } -> {} <Inventory>
}

# One handler writes a line to a file.
impl Inventory for ToFile where {
  restock = { item, price } -> do
    append_text { path = "inventory.csv", text = "$(item),$(price)\n" }
    {}
}

# Another says what it would do, and does nothing.
impl Inventory for DryRun where {
  restock = { item, price } ->
    println { msg = "  would record $(item) at €$(price)" }
}

# The delivery restocks. It knows nothing about files.
let delivery = {} -> do
  Inventory.restock { item = "olive-oil", price = "8.99" }
  Inventory.restock { item = "tahini", price = "4.50" }

let main = {} -> perform Console in do
  println { msg = "dry run:" }
  perform DryRun in delivery {}

  println { msg = "for real:" }
  # An in-memory filesystem: nothing touches the disk.
  perform MemoryFileSystem in perform ToFile in do
    delivery {}
    println { msg = read_text_or { path = "inventory.csv", def = "" } }
-->

That is the tour. Ten pages, one small program, and every type on it inferred rather than written.
