---
title: Aliases and newtypes
blurb: Naming a shape, and making a name mean something.
---

Two ways to put a name on a type, and they do opposite things. An alias is a
shorthand the compiler forgets; a newtype is a distinct type it enforces.

## type is a shorthand

`type` gives an existing type another name. Nothing changes but the reading.

<!--mezze-code
type Celsius = Int

let t : Celsius = 21     # an Int, called Celsius
-->

`Celsius` *is* `Int`. No conversion on the way in, none on the way out, and an
ordinary `Int` is accepted anywhere a `Celsius` is asked for. The name shows up
in signatures and on hover, and that is the whole benefit.

That makes an alias right when the shape is long and the meaning is obvious:
`type Reading = { city: Str, temp: Int }` saves repeating four tokens. It is
the wrong tool when the point is to stop two values being confused, because it
does not stop anything.

## newtype makes a real type

`newtype` wraps a type in a new one the compiler keeps separate.

<!--mezze-editor
use std::effects::log::{ Console, println }

newtype Money = Int
newtype UserId = Int

# Money.(n) wraps in an expression, and unwraps in a pattern.
let cents = { m } -> match m is
  Money.(n) -> n

let main = {} -> perform Console in do
  let price = Money.(350)
  println { msg = "$(cents { m = price }.to_str {}) cents" }
-->

`Money` and `UserId` are both `Int` underneath and neither is interchangeable
with the other, or with a bare `Int`. Passing one where the other is expected
is a compile error:

<!--mezze-error
error[T0201]: Field `m` expects Money but got Int in call to `f`
-->

That error is the reason to reach for a newtype. An identifier, a quantity and
an amount of money are all integers, and mixing them up is the kind of bug a
type system should be catching.

## Wrapping and unwrapping

`Money.(350)` constructs. The same syntax in a pattern position takes it apart:

<!--mezze-code
let price = Money.(350)          # wrap

match price is
  Money.(n) -> n                 # unwrap, n : Int
-->

There is no field access, and that is deliberate. Getting at the contents means
matching, which means the unwrapping is written down where it happens rather
than scattered through the code as `.val`.

## Hiding the representation

Attach behaviour to the wrapper and callers never see the inside at all:

<!--mezze-editor
use std::effects::log::{ Console, println }

newtype Money = Int

# Methods on the wrapper. The Int stays an implementation detail.
impl Money where {
  cents = { it } -> match it is
    Money.(n) -> n,
  to_str = { it } -> match it is
    Money.(n) -> "$((n / 100).to_str {}).$((n % 100).to_str {})"
}

let main = {} -> perform Console in do
  let price = Money.(1250)
  println { msg = price.to_str {} }
  println { msg = "$(price.cents {}.to_str {}) cents" }
-->

`price.to_str {}` formats as currency because `Money` says what that means.
Nothing outside the impl needs to know money is stored in cents, and if that
changes to a `Frac` later, only this block changes.

## Which to use

Alias when the name is for the reader: a long record shape, a type that recurs
across signatures. It costs nothing and prevents nothing.

Newtype when the name is for the compiler: money, identifiers, units,
anything where two values of the same underlying type must not be swapped.
You pay a wrap and an unwrap, and you get an error instead of a bug.
