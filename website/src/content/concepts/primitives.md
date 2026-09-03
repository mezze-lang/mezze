---
title: Primitives
blurb: One section per built-in type, then the containers.
---

Nothing on this page is annotated and all of it has an exact type. Hover any
name in the editor at the bottom to see what the compiler worked out.

## Int

A signed 64-bit integer, written `42`. Arithmetic saturates at the bounds
rather than wrapping: adding one to the largest `Int` gives the largest `Int`
back, not a negative number. That is the safer failure of the two, since a
value stuck at the maximum is easier to notice than one that silently changed
sign.

## Nat

A non-negative 64-bit integer, written `5n`. A count that cannot go below zero
says so in its type instead of relying on a comment or a runtime check.

Going from `Int` to `Nat` can fail, so `int::to_nat` hands back an `Option`
rather than guessing.

Subtracting two `Nat`s gives an `Int`, because the answer can be negative.
The type follows what the operation can actually produce rather than what its
inputs happened to be.

## Float

A 64-bit IEEE 754 double, written `1.5`. Same representation as `double` in C
or Java, with the same infinities and NaN.

## Frac

An exact rational, written `1//3`. It holds a numerator and a denominator
rather than a binary approximation, so thirds stay thirds and no value is
unrepresentable. Arithmetic keeps the fraction reduced.

## Bool

`Bool` is a variant with two cases, `True` and `False`. Nothing about it is
special to the compiler: the capitals are there because those are constructor
names, the same as `Some` or `Ok`, and you can match on them the way you match
any other variant.

`&&` and `||` are the operators, and they are not built in either: they
desugar to the `LogicalAnd` and `LogicalOr` abilities, which `Bool` implements
like any other type would. Boolean negation is `.not {}`, since Mezze has no prefix operators.

## Str

A string of Unicode text. Interpolation puts an expression inside a string, so
`"$(name) has $(count.to_str {}) letters"` splices both values in. The
interpolated expression has to be a `Str`, which is why `count` calls
`to_str {}` rather than sitting there bare. That is stricter than most
languages and deliberate. A value that quietly converts itself is how a log
line ends up reading `[object Object]`, or how a missing value becomes the
four characters `null` in a report.

A string can also run across several lines. There is no separate syntax for
it, no triple quotes and no continuation character. Open a quote, write the
lines, close it. Newlines and leading spaces are kept exactly as typed, and
interpolation works the same as on one line.

## Char

A single character. There is no character literal syntax, so a `Char` comes
from `char::from_str`, which returns an `Option` because the string might not
hold exactly one.

## Seq

The literal container, written `[1, 2, 3]`. Indexed and contiguous, and the
one most code reaches for. It holds a single type, so `[Int]` cannot be given
a `Str`.

## List

A linked list. Cheap to push onto the front and to take apart at the head,
which suits recursion and building a collection one element at a time.

## Map and Set

Hashed, so lookup is fast and iteration order is not promised. A `Set` drops
duplicates on insert; a `Map` replaces the value at a key.

## SortedMap and SortedSet

The same operations with keys kept in order. Iteration is sorted and range
queries are possible, which costs a little on insert.

## Deque

A double-ended queue. Pushes and pops at both ends are cheap, so it works as
a stack or a queue without changing type.

## Bytes

A sequence of raw bytes rather than characters. This is what file and network
operations carry, and `bytes::to_str` decodes it when the bytes really are
text.

## All of it together

Containers are built by chaining from an empty one, and the methods carry the
same names wherever the operation means the same thing.

<!--mezze-editor
use std::effects::log::{ Console, println }
use std::collections::list
use std::collections::set
use std::collections::map
use std::collections::deque

let int = 42
let nat = 5n
let float = 6.7
let frac = 1//3
let bool = True
let name = "Mezze"
let multi_line = "$(name) reads across
two lines, indentation and all"

let seq = [3, 1, 2]
let lst = list::empty {}.push { x = 2 }.push { x = 1 }
let st = set::empty {}.insert { x = 3 }.insert { x = 3 }
let mp = map::empty {}.insert { k = "ada", v = 36 }
let dq = deque::empty {}.push { x = 1 }

let main = {} -> perform Console in do
  println { msg = "$(int.to_str {}) $(nat.to_str {}) $(float.to_str {}) $(frac.to_str {}) $(bool.to_str {})" }
  println { msg = multi_line }

  println { msg = "seq $(seq.size {}.to_str {}), list $(lst.size {}.to_str {})" }
  println { msg = "set $(st.size {}.to_str {}), map $(mp.size {}.to_str {}), deque $(dq.size {}.to_str {})" }
-->
