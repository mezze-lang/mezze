---
title: Abilities
blurb: One method, many types, chosen by the type rather than the caller.
---

An ability is a set of methods a type can implement. Declare the ability once,
implement it for as many types as you like, and code written against the
ability works for all of them.

<!--mezze-code
ability Shape for 'a where { area: { it: 'a } -> Int }
-->

`'a` is the implementing type, and `it` is the receiver — the value the method
is called on. Both are ordinary names in an ordinary record: an ability
declaration is a record of function types, in the same braces as everything
else in the language.

## Implementing it

An `impl` supplies the methods for one type.

<!--mezze-editor
use std::effects::log::{ Console, println }

ability Shape for 'a where { area: { it: 'a } -> Int }

type Square = { side: Int }
type Rect = { w: Int, h: Int }

impl Shape for Square where { area = { it } -> it.side * it.side }
impl Shape for Rect where { area = { it } -> it.w * it.h }

let main = {} -> perform Console in do
  println { msg = { side = 3 }.area {}.to_str {} }
  println { msg = { w = 2, h = 5 }.area {}.to_str {} }
-->

A square and a rectangle store different fields and compute area differently.
What they share is that both answer `area`, and that is enough for anything
written against `Shape`.

## Writing against the ability

A function that calls `area` works for any type that implements it. There is
nothing to declare: the constraint is inferred from the call.

<!--mezze-editor
use std::effects::log::{ Console, println }

ability Shape for 'a where { area: { it: 'a } -> Int }

type Square = { side: Int }
type Rect = { w: Int, h: Int }
type Circle = { r: Int }

impl Shape for Square where { area = { it } -> it.side * it.side }
impl Shape for Rect where { area = { it } -> it.w * it.h }
impl Shape for Circle where { area = { it } -> 3 * it.r * it.r }

# describe never names a shape type. It works for all three.
let describe = { s } -> "area $(s.area {}.to_str {})"

let main = {} -> perform Console in do
  println { msg = describe { s = { side = 3 } } }
  println { msg = describe { s = { w = 2, h = 5 } } }
  println { msg = describe { s = { r = 1 } } }
-->

Add a fourth shape a year from now, give it an `impl`, and `describe` works on
it without being touched.

Call the method on a type with no implementation and the compiler says so, and
lists what does implement it:

<!--mezze-error
error[Constraint Solving]: Int does not implement 'Shape'.
To enable this, implement: impl Shape for Int where { ... }
  Available impls: impl Shape for Square
-->

## With a name, or without

Records and variants can be written inline rather than named, and the target
of an `impl` is no different. This implements `Shape` for a bare record shape,
with no alias anywhere:

<!--mezze-editor
use std::effects::log::{ Console, println }

ability Shape for 'a where { area: { it: 'a } -> Int }

# The target is the shape itself, written where the impl needs it.
impl Shape for { w: Int, h: Int } where { area = { it } -> it.w * it.h }

let main = {} -> perform Console in
  println { msg = { w = 2, h = 5 }.area {}.to_str {} }
-->

## An alias is only a name

A `type` does not create a new type, it names an existing shape. So an impl
written against the name is found by a value that never mentions it:

<!--mezze-editor
use std::effects::log::{ Console, println }

ability Shape for 'a where { area: { it: 'a } -> Int }

type Rect = { w: Int, h: Int }

impl Shape for Rect where { area = { it } -> it.w * it.h }

# A bare record literal. Its shape is Rect's shape, so the impl applies.
let main = {} -> perform Console in
  println { msg = { w = 2, h = 5 }.area {}.to_str {} }
-->

Name the target when the name is the clearer thing to read. The values it
covers are the same either way.

## More than one method

An ability can require several methods. Each `impl` supplies all of them,
separated by commas, the same as any other record.

<!--mezze-editor
use std::effects::log::{ Console, println }

ability Shape for 'a where {
  area: { it: 'a } -> Int,
  name: { it: 'a } -> Str
}

type Square = { side: Int }
type Rect = { w: Int, h: Int }

impl Shape for Square where {
  area = { it } -> it.side * it.side,
  name = { it } -> "square"
}
impl Shape for Rect where {
  area = { it } -> it.w * it.h,
  name = { it } -> "rect"
}

let describe = { s } -> "$(s.name {}) area $(s.area {}.to_str {})"

let main = {} -> perform Console in do
  println { msg = describe { s = { side = 3 } } }
  println { msg = describe { s = { w = 2, h = 5 } } }
-->

## Abilities and effects

The two look alike on purpose. Both declare a set of operations in a record of
function types, and both leave the implementation to be supplied elsewhere.
The difference is who supplies it and when.

An ability is chosen by the **type** of the receiver, decided at the call site
and fixed once the type is known. An effect is chosen by the **handler** that
is in scope, decided by whoever runs the computation, and the same code can
run under a different handler tomorrow. Reach for an ability when behaviour
belongs to a type; reach for an effect when it belongs to the context the
program runs in.
