---
title: What Mezze is
blurb: A short orientation, and where the name comes from.
---

Mezze is a functional language on GraalVM. Records and variants hold data,
abilities give shared behaviour to your own types, and effects account for
everything a function reaches outside itself. Types are inferred, so you
annotate where it helps a reader rather than to satisfy the compiler.

The parts are meant to be few, and to be reused. A function's parameter list
is a record. An ability is a named set of operations, and so is an effect.
They are written the same way and read the same way; what differs is who
supplies the operations and when. An ability is implemented by a type, and the
compiler resolves the call at the site. An effect is supplied by a handler
somewhere up the call stack, and the typechecker tracks which effects a
function still needs before it can run.

Learn records well and most of the syntax is already familiar.

## Why the name

Mezze is a spread of small dishes, where the meal is the combination rather
than any one plate. That is the intent here too. A few simple constructs,
combined, cover what larger languages need dedicated features for.

The other half of the name is the table. Mezze is shared food, and the
language is built for programs that are shared as well, running across threads
and eventually across machines, with the effect system tracking what touches
what.

## What to read next

Start with primitives and functions, then records. Effects come later, but
they are the part that makes the rest unusual, so do not stop before them.
