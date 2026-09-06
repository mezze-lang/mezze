---
title: Records
blurb: Structural data, open and closed, with and without a name.
---

A record is a set of named fields. Writing one is all it takes; there is no
declaration step and no class to define first.

<!--mezze-code
let point = { x = 3, y = 4 }
-->

`point` has the type `{ x: Int, y: Int }`, which is a description of its shape
rather than a name someone chose. Two records with the same fields are the
same type, whatever the code that made them.

## Structural, not nominal

This is the part that differs from most typed languages. A function asking for
`{ city: Str, temp: Int }` accepts any record with those fields, from anywhere,
with no declaration in common.

That cuts both ways, and it is worth knowing which way you want. Structural
typing means less ceremony and no import just to name a shape. It also means
two unrelated concepts that happen to share fields are interchangeable. When
you want them kept apart, that is what [newtypes](#aliases) are
for.

## Copy, do not mutate

A record is a value. To get one with a field changed, spread the original and
name what differs:

<!--mezze-editor
use std::effects::log::{ Console, println }

let point = { x = 3, y = 4 }

let main = {} -> perform Console in do
  # Copy with a change. The original is untouched.
  let moved = { ...point, x = 10 }
  println { msg = "moved $(moved.x.to_str {}), original $(point.x.to_str {})" }

  # Destructuring reads the same as construction, in reverse.
  let { x, y } = moved
  println { msg = "sum $((x + y).to_str {})" }
-->

The spread copies every field the original had and the ones you name replace
them. Nothing is modified, so the value you spread from is still whatever it
was.

Destructuring uses the same braces to take a record apart. And because a
function's parameter list *is* a record, `{ x, y } ->` in a signature is the
same construct doing the same job.

## Naming a shape

`type` gives a shape a name. The name is an alias, not a wrapper: a `Reading`
*is* `{ city: Str, temp: Int }`, and anything with those fields is one.

<!--mezze-code
type Reading = { city: Str, temp: Int }

let describe : { r: Reading } -> Str = { r } ->
  "$(r.city) at $(r.temp.to_str {})"

describe { r = { city = "Lisbon", temp = 21 } }   # no conversion needed
-->

So an alias buys readability rather than safety. The signature says `Reading`
instead of repeating the fields, and hover shows the name. If you want a type
the compiler will keep separate from its contents, reach for a newtype.

## Closed and open

A record type is closed by default: exactly these fields and no others. A
trailing `...` opens it, meaning *at least* these fields.

<!--mezze-editor
use std::effects::log::{ Console, println }

type Reading = { city: Str, temp: Int }

# Closed: exactly city and temp.
let describe : { r: Reading } -> Str = { r } ->
  "$(r.city) at $(r.temp.to_str {})"

# Open: city, plus whatever else the caller has.
let label : { r: { city: Str, ... } } -> Str = { r } -> r.city

let main = {} -> perform Console in do
  let lisbon = { city = "Lisbon", temp = 21 }
  println { msg = describe { r = lisbon } }

  # An extra field is fine here, and would not be for describe.
  println { msg = label { r = { city = "Cairo", temp = 35, humid = True } } }
-->

Open records are how you write a function that cares about part of a value.
`label` needs a `city` and is honest that it ignores the rest, so it works on
a `Reading`, on a richer record, and on anything a caller invents later.

Prefer closed when the shape is the point, and open when you are reaching for
one field. An open type in a return position is rarely what you want, since it
promises the caller less than you know.
