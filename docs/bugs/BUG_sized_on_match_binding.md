# `.size {}` on a value bound in a match arm dies in CallIndirect

## What happens

A seq bound from a variant payload in a match arm crashes when `.size {}` or
`.is_empty {}` is called on it:

```
com.oracle.truffle.api.dsl.UnsupportedSpecializationException:
  Unexpected values provided for MezzeBytecodeRootNodeGen.CallIndirect_Node.Uncached:
  [{}, [Ljava.lang.Object;], [MezzeRecord,Object[]]
```

Typechecks cleanly. Fails on native `mez exec` and in the WASM runtime alike.

## Reproduction

```mezze
use std::effects::log::{ Console, println }

type T = | Wrap { items: [Int] } |

let n = { t : T } -> match t is
  Wrap { items } -> items.size {}

let main = {} -> perform Console in
  println { msg = n { t = Wrap { items = [1, 2] } }.to_str {} }
```

Expected `2`. The parameter is fully annotated and inference is correct —
`n : { t: T } -> Int` — so this is a lowering bug, not a typing one.

## Scope

| receiver | `.size {}` |
|---|---|
| a seq literal | works |
| a `let` binding | works |
| a record field | works |
| a record field of a function parameter | works |
| **a variant payload bound in a match arm** | **crashes** |

Element type is irrelevant — `[Int]` and `[{ k: Str }]` both crash.

`.is_empty {}` fails the same way. But `.map { … }.size {}` **works**: putting
any intermediate call between the binding and the `Sized` method fixes it,
which suggests the receiver reaching dispatch differs by one step.

## Related, and probably the same root cause

This is the `Sized` version of a bug already fixed for `Inspect` in
`TypedASTCompiler.kt` (~line 1360). There, `to_dbg` on a receiver whose type
stayed polymorphic fell through to the mono/dict path, where no dictionary slot
exists for a derived ability, and dispatch found a record where it wanted the
impl function — the identical `[MezzeRecord, Object[]]` signature.

That fix was scoped to `to_dbg`/`to_str` by name. `size`/`is_empty` reach the
same broken path by a different route, so the underlying gap is wider than the
fix applied to it: **a receiver that arrives at dispatch without a resolvable
impl should not reach CallIndirect at all.**

Worth checking whether one correction covers both rather than adding a second
method-name special case.

See also `project_sized_mut_ability` — `Sized`/`SizedMut` use receiver-driven
same-op dispatch, which is the mechanism failing here.

## Acceptance criteria

- The reproduction prints `2`.
- `.is_empty {}` on the same binding works.
- Every row of the scope table above still passes.
- `.map { … }.size {}` still works (it does today).
- Full `:typechecker:test` and `:interpreter:test` suites pass, run separately
  with `--no-daemon`.

## Provenance

Found writing a JSON example for the Taste: parsing an Open Food Facts response
and counting the fields of the resulting `JObject` is the obvious first thing to
do with `std::codec::json`, and it crashes.
