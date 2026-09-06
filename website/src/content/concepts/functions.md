---
title: Functions
blurb: One record in, named arguments, and what falls out of that.
---

A function is `{ params } -> body`, and the parameter list is a record. Not
"like a record" — it is one. So every argument is named at every call site,
and Mezze has no positional arguments at all.

<!--mezze-editor
use std::effects::log::{ Console, println }

let rect = { width, height } -> width * height

let main = {} -> perform Console in do
  # The same call, written two ways. Order carries no meaning.
  println { msg = rect { width = 3, height = 4 }.to_str {} }
  println { msg = rect { height = 4, width = 3 }.to_str {} }

  # Supply some fields now, the rest later.
  let strip = rect { height = 1 }
  println { msg = strip { width = 12 }.to_str {} }

  # |> feeds a value into the field marked @.
  println { msg = (7 |> rect { width = @, height = 2 }).to_str {} }
-->

## Why names, not positions

Argument order stops being something to remember. `rect { width = 3, height = 4 }`
and `rect { height = 4, width = 3 }` are the same call, so there is no
convention to learn per function.

The bigger win is reading. Take a call written positionally:

<!--mezze-code
send_mail("me@work.com", "customer@shop.com", "New offer inside")
-->

Which address is the sender? You cannot tell. You go and look at the
definition, or you trust that whoever wrote it got the order right. Reviewing
a diff that swaps those two arguments, you would have to know the signature by
heart to catch it.

The same call in Mezze:

<!--mezze-code
send_mail { from = "me@work.com", to = "customer@shop.com", subject = "New offer inside" }
-->

Now the call site says what each value is for. Nothing about the types
changed, and newtypes are still worth reaching for when a value deserves its
own type. This is about the reader: the information was always there in the
function definition, and named arguments carry it to where the code is
actually read.

That matters more than it used to. Code is read far more often than written,
and a growing share of it is written by a model. A model generating the second
form has the field names to check itself against and can see immediately that
`from` and `to` are the wrong way round. Generating the first form it has
only position, and position is exactly the information that a reader, human or
otherwise, cannot recover from the call.

## Currying comes free

Currying means calling a function with some of its arguments and getting back
a function that wants the rest. It is how you turn a general operation into a
specific one: given a function of two things, fix one and you have a function
of the other.

In most languages this is awkward. Arguments have an order, so you can only
fix them left to right, and library authors spend real effort deciding which
argument goes first so that partial application is useful later. Get it wrong
and callers reach for a lambda instead.

Here it is just record application. Give a call some of the fields and you get
a function expecting the ones you left out:

<!--mezze-code
let rect = { width, height } -> width * height

let strip = rect { height = 1 }
strip { width = 12 }
-->

Any subset works, in any combination, because the fields are named. There is
no privileged first argument.

The error messages are the part worth dwelling on. A curried function in a
positional language often fails somewhere far from the mistake, with a message
about a function type rather than about your call. Here a partially applied
function still takes a record, so the compiler can talk about fields:

<!--mezze-error
error[T0203]: Extra field(s) 'depth' in call to 'strip'.
Function expects fields: width
-->

That is `strip` from above, called with the wrong field name. The compiler
knows `height` was already supplied and that `width` is what remains.

## The pipe operator

A pipeline reads left to right in the order things happen, rather than
inside-out the way nested calls do. `|>` takes the value on its left and
feeds it into the call on its right, with `@` marking where it lands:

<!--mezze-code
"  alice , bob , carol  "
  |> str::split { s = @, delimiter = "," }         # @ is the string to split
  |> seq::map { xs = @, f = { x } -> x.trim {} }   # @ is the sequence to map
  |> str::join { strings = @, separator = ", " }   # @ is the pieces to join
  |> str::concat { l = "members: ", r = @ }        # @ is the second field now
-->

Read top to bottom, each line is one step: split the line on commas, trim each
piece, join them back, tag the result. The nested-call form of the same thing
reads inside-out — `concat { l = ..., r = join { strings = map { ... }, ... } }` —
and you assemble it from the middle.

Notice where `@` sits. On the first three steps it is the leading field; on the
last it is the second. That is the whole point of the placeholder.

Languages that have pipes usually have to pick a convention. Some are
"data first", so the piped value always becomes argument one; others are
"data last" and it always becomes the final argument. Both exist because
neither suits every function, and libraries end up ordering their parameters
around whichever one their language chose.

Mezze does not have to choose. `@` goes wherever the value belongs, so `|>`
works with any function without that function having been designed for it.

## Working for any type

A function does not have to name concrete types. Where a parameter could be
anything, its type is a *type variable* — an apostrophe and a name, `'a`,
standing for "some one type, the same everywhere it appears". Write a function
that only moves values around and it is already polymorphic; you do not ask
for it, and you do not annotate it:

<!--mezze-editor
use std::effects::log::{ Console, println }

let swap = { pair } -> { first = pair.second, second = pair.first }

let main = {} -> perform Console in do
  let flipped = swap { pair = { first = 1, second = "two" } }
  println { msg = flipped.first }
-->

Hover `swap` and its type reads `{ pair: { second: 'b, first: 'a } } -> { first: 'b, second: 'a }`.
Two different variables, because the two fields need not match; `'a` and `'b`
are inferred, not written. The one call above fixes them to `Int` and `Str`,
and a different call fixes them to something else. One definition, every pair.

The moment the body actually *does* something to the value, that need becomes
part of the type. Compare two of them, and the type variable picks up a
constraint:

<!--mezze-editor
use std::effects::log::{ Console, println }

let larger = { a, b } -> if a > b then a else b

let main = {} -> perform Console in do
  println { msg = larger { a = 3, b = 9 }.to_str {} }
  println { msg = larger { a = "apple", b = "pear" } }
-->

Hover `larger`: `{ a: 'a, b: 'a } with Ord 'a -> 'a`. Both fields are the same
`'a` this time — you compared them, so they must be the same type — and `with
Ord 'a` appeared on its own. `>` comes from the `Ord` ability, so the compiler
records that `larger` works for any type *that has `Ord`*, and nothing else.
Call it on ordered types and it runs; call it on a type without `Ord` and the
error is about the missing ability, at the call, not deep inside the body.

None of that was annotated. As everywhere else, the annotation is optional and
the inference is the same either way — a public function usually writes the
signature down so its constraints read at a glance:

<!--mezze-code
let larger : { a: 'a, b: 'a } with Ord 'a -> 'a = { a, b } -> if a > b then a else b
-->

`with` states the constraint, and it is the only form that can. This is also
exactly how the standard library is built: `list::map` is
`{ xs: List 'a, f: { x: 'a } -> 'b } -> List 'b` — any element type in, any
element type out — and `math::max` is `{ l: 'a, r: 'a } with Ord 'a -> 'a`, the
same shape as `larger`. [Abilities](#abilities) is where `Ord` and constraints
like it come from.

## The arrow carries the effects

A function's type is not only what goes in and what comes out. It also records
what calling it *does*:

<!--mezze-code
{ url: Str } -> Str <Http>
-->

The part in angle brackets is the effect row, and it belongs to the arrow
rather than to either type beside it. Calling this reaches the network; the
`Str` that comes back is an ordinary `Str`.

An effect a function does not handle raises to that function's own arrow, and
calling it raises the effect again to the caller:

<!--mezze-code
let outer = {} -> do
  let inner = {} -> do
    fetch { url = "https://example.com" }   # performs Http, not handled here
  # inner : {} -> Str <Http>                  raised to inner's arrow
  inner {}                                  # calling inner incurs it
# outer : {} -> Str <Http>                    raised again to outer's arrow
-->

Neither `inner` nor `outer` mentions the network, and both types say
`<Http>`. That is the guarantee: between the operation and the handler that
catches it, every arrow in the chain declares it.

A function that performs nothing has no row at all, and that absence is worth
as much as the row. `{ x: Int } -> Int` is a promise, not an omission.

[Effects](#effects) covers handlers, the `*` marker and the rest of the model.
