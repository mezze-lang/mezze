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
7 |> rect { width = @, height = 2 }
-->

Languages that have pipes usually have to pick a convention. Some are
"data first", so the piped value always becomes argument one; others are
"data last" and it always becomes the final argument. Both exist because
neither suits every function, and libraries end up ordering their parameters
around whichever one their language chose.

Mezze does not have to choose. The placeholder goes wherever the value
belongs, so `|>` works with any function without that function having been
designed for it.
