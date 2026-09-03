---
title: "Putting it together"
blurb: "A record type, an ability, an effect, and a fold."
---
All of it at once: a record type for the data, an ability for behaviour, a `<Log>` row the compiler infers rather than you writing, and a fold over a sequence.

`x.total {}` is the ability method, and `.fold {}` is a plain stdlib function called as a method. Nothing here is special-cased.

Edit the cart and press Run.

<!--mezze-editor
use std::effects::log::{ Console, println }

type Item = { name: Str, price: Int, qty: Int }

ability Priced for 'a where {
  total: { it: 'a } -> Int
}

impl Priced for Item where {
  total = { it } -> it.price * it.qty
}

let main = {} -> perform Console in do
  let cart = [
    { name = "olive oil", price = 899, qty = 2 },
    { name = "pita", price = 199, qty = 3 },
  ]

  cart.map { f = { x } ->
    println { msg = "$(x.name): $(x.total {}.to_str {})" } }

  let sum = cart.fold {
    init = 0,
    f = { acc, x } -> acc + x.total {},
  }
  println { msg = "total: $(sum.to_str {}) cents" }
-->
