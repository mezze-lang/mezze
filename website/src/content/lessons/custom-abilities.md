---
title: "Your own abilities"
blurb: "Interfaces you can implement for any type."
---
An **ability** declares an interface. The receiver is named `it`, and every method takes a record like any other function.

`impl Priced for Book` supplies it for one type — including types you did not write.

`with Priced 'a` on a signature means *any type that implements this*. Write the function once, and it works for every implementation.

<!--mezze-editor
use std::effects::log::{ Console, println }

# An ability is an interface. The receiver is it.
ability Priced for 'a where {
  cents: { it: 'a } -> Int
}

type Book = { title: Str, price: Int }

impl Priced for Book where {
  cents = { it } -> it.price
}

# `with Priced 'a` means any type implementing it.
let show: { it: 'a } with Priced 'a -> Str = { it } ->
  "$(it.cents {}.to_str {}) cents"

let main = {} -> perform Console in do
  let dune = { title = "Dune", price = 1299 }
  println { msg = show { it = dune } }
-->
