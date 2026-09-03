---
title: Reading the syntax
blurb: What each bracket means, and where annotations go.
---

Mezze reuses a small number of symbols, and each one means the same thing
everywhere it appears. This page is the key. Every later section assumes it.

## Comments

`#` starts an ordinary comment and runs to the end of the line.

`##` is a doc comment. It attaches to the declaration below it and is what
hover and the LSP show, so it is worth writing in full sentences.

`#{ ... }` is a metadata comment, a record of facts about the declaration.
Some fields are acted on by the compiler, such as `deprecated = True`, and
others are there for tooling or for a reader.

<!--mezze-code
 # an ordinary note to a reader

 ## Returns the larger of two values.
 ## Works for any type that implements Ord.
 #{ deprecated = True, message = "use std::math::max instead" }
 let bigger = { a, b } -> if a.gt { r = b } then a else b
-->

## Curly braces are records

`{ ... }` is a record, and that holds in every position. A record literal, a
function's parameter list, a record type, a pattern that destructures one, and
the argument list at a call site are all the same construct:

<!--mezze-code
let point = { x = 3, y = 4 }              # a value
let area = { w, h } -> w * h              # parameters
let { x, y } = point                      # destructuring
area { w = 3, h = 4 }                     # arguments
-->

`{}` with nothing in it is the empty record, which is also the unit value. A
function taking no arguments is written `{} ->` and called with `{}`, and a
method with no arguments is `.size {}`.

Abilities and effects use the same braces, because they are named sets of
operations and an operation is a function. `ability Ord for 'a where { ... }`
and `effect Log where { ... }` both hold their operations in a record, and an
`impl` supplies the matching one.

## Parentheses group

`( ... )` does one job: it groups an expression so precedence works out.
`(1 + 2) * 3`, or `(0.1 + 0.2).to_str {}` to call a method on the sum rather
than on `0.2`.

Inside a string, `$( ... )` is the same brackets doing the same job. The
dollar marks an interpolation and the parens group the expression, so
anything that evaluates to a `Str` fits, including a whole method chain.

## Square brackets are sequences

`[1, 2, 3]` is a seq literal, and `[Int]` is its type. The same brackets, one
holding values and one holding a type.

## Vertical bars are variants

`type Status = | Queued, Shipped { days: Int } |` declares a variant, with the
cases between bars. A trailing `...` before the closing bar leaves it open.

In a `match`, a bar is not needed: arms are written one per line. The `|` in
a type declaration and the arms of a match are separate syntax for the same
idea.

## Annotations

A type annotation can sit in three places, and you can use any combination:

<!--mezze-code
let shout : { msg: Str } -> {} <Log> = { msg } -> ...   # the whole binding
let area = { w: Int, h: Int } -> w * h                  # individual parameters
let n : Int = area { w = 3, h = 4 }                     # a local binding
-->

Annotating the whole binding is the only form that can state an effect row or
a `with` constraint, so it is what a public function usually carries.
Everything else is inference, and none of it is required.

`with` adds a constraint on a type variable. It sits before the arrow:

<!--mezze-code
let bigger : { a: 'a, b: 'a } with Ord 'a -> 'a = { a, b } -> ...
-->

That signature accepts any type at all, provided it implements `Ord`. Without
the constraint the body could not call `.gt {}`, because nothing would
guarantee the type has it.

## Angle brackets are the effect row

`<Log>` after a return type says the function performs the `Log` effect and
cannot run until a handler supplies it. A function that performs nothing has
no row at all.

The row is a list, and two symbols can appear in it.

`...` stands for "whatever else the caller brings". `<Http, ...>` means this
function needs `Http`, and passes through any other effects its arguments
perform. A bare `<...>` is the same idea with nothing named: `list::map` is
written `{ xs: List 'a, f: { x: 'a } -> 'b <...> } -> List 'b <...>`, which
says whatever the callback performs, `map` performs too. That is why you can
call `println` inside a `.map {}` without `map` knowing about `Log`.

`*` marks effects that have been handled. A bare `<*>` means all of them were,
which is what you see on `main`. `<Http, *>` means some were handled and
`Http` was not, so the caller still has to supply it:

<!--mezze-code
let report = { url } -> perform Console in
  println { msg = request { url }.send {}.text_or { def = "offline" } }

# report : { url: Str } -> {} <Http, *>
-->

`perform Console in` discharged `Log`, leaving `Http` named in the row.

## Match and guards

`match x is` opens a match, and each arm is `pattern -> expression`. A pattern
can be a variant case, a record, a literal, or a binding. Add `if` after the
pattern for a guard:

<!--mezze-code
match s is
  Shipped { days } if days == 0 -> "arriving today"
  Shipped { days } -> "arriving in $(days.to_str {})"
  Queued -> "waiting"
-->

Arms are tried top to bottom, so the literal case above has to precede the one
that binds `days`. A match must cover every case, and the compiler will not
accept one that does not.

## Names

An ordinary name starts with a lowercase letter or an underscore, and may
contain letters, digits and underscores after that. `snake_case` is the
convention for values and functions, and a leading underscore marks something
deliberately unused.

Types, variant cases and abilities are `UpperCamelCase`: `Status`, `Shipped`,
`NumAdd`. That casing is a convention rather than a rule, and the compiler
will accept a capitalised binding, but every name in the stdlib follows it and
patterns read better when a constructor is visibly distinct from a binding.

A type variable starts with an apostrophe: `'a`, `'k`, `'v`. Those are the
placeholders in a generic signature, and by convention `'a` is the first, `'k`
and `'v` are a map's key and value.

## Operators

The full set is deliberately small.

`+ - * / %` for arithmetic, `== !=` for equality, `< <= > >=` for ordering,
and `&& ||` for logic. That is the whole set.

None of them is built in. Each desugars to an ability, so `+` calls `NumAdd`
and `<` calls `Ord`, and implementing that ability is what makes the operator
work on your own type.

There are no prefix operators at all. Boolean negation is `.not {}` and
arithmetic negation is `.neg {}`.

Reaching for `!x` gets you a parse error saying so. The reason is uniformity:
a method reads the same as every other operation on a value, participates in
a chain, and needs no precedence rule of its own. `-` in front of a number
literal is still ordinary syntax; it is only the prefix *operator* that is
absent.

## Modules

`::` separates the parts of a module path, and `use` brings names into scope:

<!--mezze-code
use std::effects::log::{ Console, println }   # selected names
use std::collections::map                     # the module itself
-->

The first form puts `Console` and `println` in scope directly. The second puts
`map` in scope, so its contents are reached as `map::empty {}`. Reach for the
second when a bare name would be ambiguous: `map::empty` and `set::empty` read
better than two imported `empty`s.

`pub` marks a declaration as visible outside its module, and `pub use`
re-exports a name that came from elsewhere.

## impl, and where `for` goes

`impl` covers three different jobs, told apart by whether `for` appears and
what follows it:

<!--mezze-code
impl Ord for Int where { ... }          # Int implements the Ord ability
impl Log for Console where { ... }      # Console is a handler for the Log effect
impl Bool where { ... }                 # methods on Bool itself, no ability
-->

The first two read the same because they are the same shape: a named set of
operations, and something supplying them. What differs is that `Int` is a type
implementing an ability, while `Console` is a handler supplying an effect.

The declarations differ slightly. An ability names the type it is about,
because it is written once and implemented many times:

<!--mezze-code
ability Ord for 'a where { ... }
effect Log where { ... }
-->

An effect does not, because the thing supplying it is a handler rather than
the type the operations act on.

## Keywords

| | | |
|---|---|---|
| `let` | bind a value | |
| `type` | declare a type or alias | |
| `newtype` | declare a distinct wrapper | |
| `effect` | declare an effect | |
| `ability` | declare an ability | |
| `impl` | implement an ability, supply an effect handler, or add methods to a type | |
| `use` | import | |
| `pub` | export | |
| `as` | rename an import | |
| `if` `then` `else` | conditional | |
| `match` `is` | pattern match | |
| `do` | sequence statements | |
| `perform` `in` | supply a handler | |
| `with` | constrain a type variable | |
| `having` | fix an associated type | |
| `where` | open a body of operations | |
| `for` | name the type an ability, impl or handler applies to | |
| `abort` `resume` | abort and resume continuations, inside a handler | |
| `scope` | effect handler scopes | |

## All of it together

<!--mezze-editor
use std::effects::log::{ Console, println }

type Status = | Queued, Shipped { days: Int } |

# The whole binding annotated: params, return, and effect row.
let shout : { msg: Str } -> {} <Log> = { msg } -> println { msg = msg }

# Or just the parameters, letting the rest be inferred.
let area = { w: Int, h: Int } -> w * h

let describe = { s } -> match s is
  Shipped { days } if days == 0 -> "arriving today"
  Shipped { days } -> "arriving in $(days.to_str {})"
  Queued -> "waiting"

let main = {} -> perform Console in do
  let n : Int = area { w = 3, h = 4 }
  shout { msg = "area $(n.to_str {})" }
  shout { msg = describe { s = Shipped { days = 0 } } }
  shout { msg = describe { s = Queued } }
-->
