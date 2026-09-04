---
title: Variants
blurb: One of several shapes, and the match that takes them apart.
---

A variant is a value that is one of several named cases. Cases sit between
pipes, and a case may carry fields of its own.

<!--mezze-code
type Status = | Queued, Shipped { days: Int }, Cancelled { why: Str } |
-->

`Queued` carries nothing. `Shipped` carries a `days`, `Cancelled` a `why`.
A `Status` is exactly one of the three, and the compiler knows which cases
exist.

## With a name, or without

The `type` above is an alias, the same as for a record: it names the shape so
you can say `Status` instead of repeating the cases. The variant can also be
written inline where it is used:

<!--mezze-editor
use std::effects::log::{ Console, println }

# Anonymous: the cases written where the parameter is declared.
let flag = { f : | On, Off | } -> match f is
  On -> "on"
  Off -> "off"

let main = {} -> perform Console in println { msg = flag { f = On } }
-->

Inline suits a two-case switch used in one place. A name is worth it as soon
as the type appears in more than one signature, or as soon as the name says
something the cases do not.

## Matching

`match x is` takes a variant apart. Each arm names a case, binds any fields it
carries, and gives the result for that case.

<!--mezze-editor
use std::effects::log::{ Console, println }

type Status = | Queued, Shipped { days: Int }, Cancelled { why: Str } |

let describe = { s } -> match s is
  Queued -> "waiting"
  Shipped { days = 0 } -> "arrives today"
  Shipped { days } if days > 7 -> "arrives eventually"
  Shipped { days } -> "arrives in $(days.to_str {})"
  Cancelled { why } -> "cancelled: $(why)"

let main = {} -> perform Console in do
  println { msg = describe { s = Queued } }
  println { msg = describe { s = Shipped { days = 0 } } }
  println { msg = describe { s = Shipped { days = 30 } } }
  println { msg = describe { s = Shipped { days = 3 } } }
  println { msg = describe { s = Cancelled { why = "stock" } } }
-->

Three arms match `Shipped` and they are tried in order, so the specific ones
come first. `Shipped { days = 0 }` matches a literal. `Shipped { days } if
days > 7` adds a guard, an ordinary boolean expression that has to hold for
the arm to be taken. The last one binds `days` and catches the rest.

## Miss a case and it will not compile

A match has to cover every case the type admits. This one leaves out
`Cancelled` — press Check and the compiler names exactly what is missing:

<!--mezze-editor
# expect-error: the Cancelled arm is missing on purpose; Check reports V0601.
use std::effects::log::{ Console, println }

type Status = | Queued, Shipped { days: Int }, Cancelled { why: Str } |

let describe = { s : Status } -> match s is
  Queued -> "waiting"
  Shipped { days } -> "arrives in $(days.to_str {})"

let main = {} -> perform Console in println { msg = describe { s = Queued } }
-->

<!--mezze-error
error[V0601]: This match expression doesn't handle all cases. Missing case:
Cancelled. Add the missing patterns or use `_ ->` as a catch-all.
  = hint: add a case for: Cancelled
-->

Add the arm and it compiles. This is the reason variants are worth declaring:
add a fourth case to `Status` a year from now and every match that needs
updating becomes a compile error, listed by name, instead of a branch that
silently does nothing.

## Two more the compiler catches

An arm after a catch-all can never run, and saying so is more useful than
letting it sit there looking meaningful:

<!--mezze-editor
# expect-error: the Queued arm is dead; Check reports V0603.
use std::effects::log::{ Console, println }

type Status = | Queued, Shipped { days: Int } |

let describe = { s : Status } -> match s is
  _ -> "anything"
  Queued -> "waiting"

let main = {} -> perform Console in println { msg = describe { s = Queued } }
-->

<!--mezze-error
error[V0603]: Unreachable pattern: Previous pattern already matches all cases
  = hint: this pattern can never match because an earlier pattern already covers it
-->

And a case the type does not have is caught where you wrote it, with the ones
that do exist listed:

<!--mezze-editor
# expect-error: Refunded is not a Status case; Check reports V0704.
use std::effects::log::{ Console, println }

type Status = | Queued, Shipped { days: Int } |

let describe = { s : Status } -> match s is
  Queued -> "waiting"
  Shipped { days } -> "soon"
  Refunded -> "money back"

let main = {} -> perform Console in println { msg = describe { s = Queued } }
-->

<!--mezze-error
error[V0704]: Variant type does not have constructor 'Refunded'.
Available constructors: 'Queued', 'Shipped'
-->

Between them and V0601, a match is checked from both sides: no case left out,
and no arm that cannot match.

## Open variants

A trailing `...` leaves a variant open, meaning "these cases, and possibly
others":

<!--mezze-editor
use std::effects::log::{ Console, println }

type Level = | Low, High, ... |

# Low and High are known. The '...' says there may be others, so the
# compiler requires the last arm: without it this does not compile.
let name = { l : Level } -> match l is
  Low -> "low"
  High -> "high"
  _ -> "something else"

let main = {} -> perform Console in do
  println { msg = name { l = High } }
  println { msg = name { l = Low } }
-->

An open variant is the mirror of an open record. A closed one is a promise
about the whole set of cases, which is what makes exhaustiveness checking
possible; an open one gives that up in exchange for extension. Because the set
is not known, a match on an open variant needs a catch-all arm — and that
catch-all is exactly what stops the compiler telling you about a case you
forgot.

Reach for closed by default. The compile error when you add a case is the
feature, and opening a variant turns it off.
