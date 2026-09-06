---
title: Modules
blurb: Files, imports, and what a module chooses to show.
---

A module is a file. Its path under `src` is its name, so
`src/shop/money.mz` is the module `shop::money`, and `::` separates the parts
of that path everywhere it appears.

## Two ways to import

`use` brings something into scope, in one of two shapes:

<!--mezze-code
use std::effects::log::{ Console, println }   # selected names, used bare
use std::collections::map                     # the module, used qualified
-->

The first puts `Console` and `println` directly in scope. The second puts
`map` in scope, and its contents are reached through it as `map::empty {}`.

Prefer the qualified form when a bare name would be ambiguous or uninformative.
`map::empty` and `set::empty` sit happily in one file; two imported `empty`s do
not. Prefer the selective form for names that read well alone, which is why
every example on this site imports `println` rather than writing
`log::println`.

A module can also be renamed on the way in:

<!--mezze-code
use std::collections::map as m

m::empty {}
-->

## pub decides what escapes

A declaration is private to its module unless marked `pub`.

<!--mezze-code
## A price in cents.
pub newtype Money = Int

pub let of_cents : { n: Int } -> Money = { n } -> Money.(n)

pub let cents : { m: Money } -> Int = { m } -> match m is
  Money.(n) -> n

# No pub: an implementation detail, invisible outside this file.
let double = { m: Money } -> Money.(cents { m = m } * 2)
-->

Reaching for `double` from another module is a compile error that says what to
do about it:

<!--mezze-error
error[V0702]: 'double' exists in module 'money' but is not public.
Add 'pub' to export it.
-->

That is the useful pairing with [newtypes](#aliases). Export the
type and the operations that make sense on it, keep the representation
private, and callers cannot depend on money being an `Int` because they can
never see that it is.

`pub use` re-exports something imported from elsewhere, so a module can present
a curated surface without callers knowing where each piece came from.

## A project

A single file is enough to try something, and `mez check file.mz` will type it.
A project adds `Mezze.toml`:

<!--mezze-code
[package]
name = "shop"
version = "0.1.0"

[[app]]
name = "shop"
path = "src/app.mz"
entry = "main"
-->

`entry` names the function the program starts at. That function is where
effects have to be handled: the runtime supplies the ambient ones, and a
user-defined effect left unhandled on the entry's arrow is a compile error
rather than a failure at run time.

<!--mezze-error
Entry 'main' performs Audit, but nothing discharges it — the entry has no
enclosing handler, so it leaks at runtime. Wrap the body in a handler:
`perform <Performer> in …`
-->

That error is the last link in the chain [Effects](#effects) describes. An
effect raises from where it is performed, through every arrow that calls it,
up to the entry — and if nothing has handled it by then, the program does not
compile.
