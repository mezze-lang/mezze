---
title: "Effects"
blurb: "What a function does to the world, in its type."
---
Every price so far has been made up. Real data comes from somewhere else, which means leaving the program — and that is the first thing here the type system insists on saying out loud.

`lookup` asks [Open Food Facts](https://world.openfoodfacts.org) what a barcode is, so its type carries `<Http>`. That travels up to everyone who calls it: a function that calls a function that reaches the network cannot hide it. What the type does **not** say is *who answers*. A **handler** decides that — press Run and the first call genuinely leaves your browser, while the second is the same function answered from a fixture. `lookup` cannot tell which it got, which is why tests for code like this need no network.

Now look at what is missing from `name_of`: a schema. Nobody declared what the reply looks like. The chain reads `val.product.product_name`, and that **is** the schema — `from_json` builds its decoder from the shape the code actually uses, nested record and all, and ignores the rest of the reply. Read another field tomorrow and the decoder grows to match, because the type grew.

And every disappointment has somewhere to go. `from_json` returns a `Result`: text that is not JSON, and a reply missing the field the chain needs, are both an `Err` rather than a crash — and both funnel into `get_or`.

<!--mezze-editor
use std::effects::log::{ Console, println }
use std::effects::http::{ HttpClient, MockHttpClient, mock_get, request }
use std::codec::json::{ FromJson, ToJson }

# Asks Open Food Facts what a barcode is. Its type
# says <Http>. It never says who answers.
let lookup = { code } ->
  request { url = "https://world.openfoodfacts.org/api/v2/product/$(code).json?fields=product_name" }
    .send {}.text_or { def = "{}" }

# No schema. The decoder is inferred from usage — the field
# this chain reads is exactly what `from_json` decodes.
let name_of = { body } -> body
  .from_json {}
  .map { f = { val } -> val.product.product_name }
  .get_or { def = "the reply was not what we expected" }


let main = {} -> perform Console in do
  let code = "5050179405767"

  # The real thing. This request leaves your browser.
  perform HttpClient in
    println { msg = name_of { body = lookup { code } } }

  # The same function, from a fixture. The encoder is inferred too — from `reply`-s shape.
  let reply = { product = { product_name = "Pitted Black Olives (mock)" } }
  let rules = [ mock_get { body = reply.to_json_str {}, url = "*" } ]
  perform MockHttpClient { rules = rules } in
    println { msg = name_of { body = lookup { code } } }
-->

Change the barcode and press Run — the live call follows. Read a second field, `val.product.brands` say, and the decoder now requires that too.
