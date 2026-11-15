# Generic Ability Operations — an operation with its own type parameter

## STATUS: IMPLEMENTED 2026-11-15. This document is the record of what it cost.

The declaration below is accepted, implementable, and dispatches correctly —
directly and through forwarding, on the interpreter AND the monomorphised build.
`GenericAbilityOpRuntimeTest` and `GenericAbilityOpForwardingTest` pin it.

Measured on `mez dev` and on a built jar, identical output both ways:

| program | result |
|---|---|
| `a.uno { x = a/b/c }` — three instantiations of one op-own param | 1000 / 2000 / 3000 |
| `a.both { x = c, y = b }` — two own params, two different bounds | 23 |
| `a.fwd { x = b }` — forwarding to another such op | 2007 |
| `a.fwd2 { x = b }` — the same forward one level deeper | 4014 |

**The five monomorphisation concerns below did not materialise — except one, and
it was not in mono at all.** The dictionary SLOT (#3) was the real gap, and in a
place this document did not predict:

- `dictMethodsForHash` for an impl op came from SCANNING THE BODY for method
  calls on a VarType receiver. An op that calls the constrained op directly
  (`x.show {}`) is found; one that FORWARDS (`it.direct { x = x }`, concrete
  receiver) is not, so it compiled with no dict parameter to forward. Phase 2
  does not rescue it: it matches `BindingRef.ByHash` callees and an ability-op
  method call is not one. A DECLARED `with` is a requirement whatever the body
  does with it, so the declared set now decides parameters too.
- and the call site had to PREFER that slot over the concrete pile. Forwarding
  was the last resort, reached only when nothing resolved — but the pile holds
  the receiver, so `show` always resolved, to the receiver's impl. The pile
  cannot see what the caller's type variable is bound to; only the slot can.

Symptom before the fix: `a.fwd { x = b }` answered 1 (`Show A`, the receiver)
where 2 (`Show B`) is correct — silently wrong, and type-checking cleanly either
way, which is why both tests are RUNTIME tests.

The identity (#2), ABI (#4) and op-type (#5) concerns cost nothing: one impl op
hash per impl still suffices, because the op's own parameter is passed as
EVIDENCE rather than specialised into a distinct body. That is the finding worth
keeping from this whole document — **an operation parameter needs a dictionary,
not a specialisation.**

## The Problem (as originally written)

This declaration is accepted today:

```mezze
ability Wrap for 'w where {
  wrap: { it: 'w, x: 'a } with Show 'a -> Int,
}
```

`'a` belongs to the OPERATION, not to the ability. It is Rust's
`fn wrap<A: Show>(&self, x: A) -> i32`.

Every implementation of it is refused:

```mezze
impl Wrap for Src where {
  wrap: { it: Src, x: 'a } with Show 'a -> Int = { it, x } -> x.show {},
}
## error[V0417]: Type variables not allowed in ability implementation:
## operation 'wrap' parameter type contains type variables.
```

So an ability can be declared that nothing is able to implement. That is the
immediate defect — a declaration states an obligation the impl system cannot
accept — and it is why this file exists rather than a bug entry alone: the
honest fix is the feature, not a second refusal.

## What it would buy, and what already covers part of it

Mezze has ONE place to put a type parameter today: on the ability.

```mezze
ability Into 'b for 'a where { into: { it: 'a } -> 'b }
```

A class parameter takes part in IMPL SELECTION. `impl Into Int for Src` and
`impl Into Str for Src` are two different impls, told apart by what the call
produces (this is what BUG 39 was about). That is the right tool whenever the
parameter should choose the implementation.

An operation parameter is the other case: it does NOT choose the
implementation. One impl, one body, every `'a` that satisfies the bound.

|  | selects the impl | one body for all instantiations |
|---|---|---|
| Rust | type param on the trait — `trait Into<T>` | generic method — `fn collect<B: FromIterator>` |
| Mezze | class param — `ability Into 'b for 'a` | **missing** |

The pattern this blocks is `collect`-shaped: an operation that accepts or
produces "any type with this ability" without multiplying the impls. Expressed
with a class parameter instead, every target type needs its own impl of the
whole ability, which is the wrong granularity when the parameter has nothing to
do with which implementation runs.

Rust allows generic methods and pays for them with OBJECT SAFETY: a trait with
one cannot be used as `dyn Trait`, because a generic method has no single vtable
entry. Mezze has no `dyn`, so that particular price is not on the table here.
The price here is monomorphisation, below.

## Where the gate is

`AbilityValidator`, in the op-signature check:

```kotlin
val allowedTypeVars = impl.forallVars.toSet()
if (implOp.paramType != null && hasTypeVariables(actualParamType, allowedTypeVars)) {
    throw TypeCheckError(... V0417_TYPE_VAR_FORBIDDEN_IN_CONCRETE_IMPL)
}
```

`impl.forallVars` is every variable the impl HEADER binds. So the real rule in
force is:

> a type variable in an operation's signature must be bound by the ability or by
> the impl's own head.

That is one set, and widening it to include an operation's own quantified
variables is one line. Everything after that line is the actual work.

## Why it is not a one-line change

An ability operation is compiled as ONE function per impl, named by
`computeOpHash(impl.hash, opName)`. That identity assumes the op's type is
closed once the impl is chosen — which is exactly what V0417 enforces. An
operation parameter breaks the assumption in five places, all of them in
monomorphisation and identity:

1. **One body, many instantiations.** For a class parameter, each `(class arg,
   receiver)` pair is a SEPARATE impl with its own body and its own hash, so
   mono has something concrete to specialise. For an operation parameter there
   is one body that must serve every instantiation, so the impl's op becomes a
   POLYMORPHIC function needing per-call-site specialisation — the machinery a
   polymorphic top-level `let` already gets, but hanging off an impl operation.

2. **Identity.** `computeOpHash(implHash, opName)` yields one hash per impl
   operation. Specialised variants need distinct identities that are reachable
   by hash (content addressing is not optional here — a remote fetch, the store's
   dedup and the codebase-query edges all key on it), and the call sites have to
   be rewritten to them (`monoCallSiteRewrites`, `SpecRequest`).

3. **Dictionary slots.** The bound (`with Show 'a`) already becomes a
   `__dict_show` parameter filled per call site — that part exists, it is what
   the stdlib's `Decode` constraints do. But slot NAMES are keyed on the type
   variable (`__dict_show@a`), and every consumer — `normalizeDictKeys`,
   `enclosingDictSlot`, `dictMethodsForHash` — assumes that variable belongs to
   the ability or the impl. A variable belonging to neither has to thread
   through all of them. This is the code where BUG 9 and S-11 lived (two
   dictionaries collapsing onto one slot), so it needs its own probes.

4. **The call ABI.** Specialised operations meet `emitCallDirect`, the scalar
   ABI's param-name matching and `monoStripBySiteLoc`. Those are mono's hot
   paths.

5. **Everything that reads an op's type expecting it closed.** The per-site
   decode pins, `obligationDispatchTypes`, `ownTypePerMethod` — each currently
   relies on an impl operation's signature being concrete after selection.

In short: the type side of this is small and well understood; the cost is all
in mono, which is also where BUGS 38, 39, 40 and 41 came from.

## What already works and must keep working

Measured 2026-11-15, single-file `mez check`:

| shape | where the variable is bound | result |
|---|---|---|
| `impl One for 'a where { f: { it: 'a } -> Int }` | impl head (blanket) | accepted |
| `impl One for ['e] where { f: { it: ['e] } -> Int }` | impl head (generic container) | accepted |
| `ability Make 'b for 'a` + `impl Make Int for Src` | ability (class param) | accepted |
| `ability Carry for 'a having 'out` | ability (`having`) | accepted |
| `f: { it: 'a, x: 'b } with Show 'b` | nothing | **V0417** |

Generic blanket impls are NOT this feature and are unaffected: their variable is
bound by the impl head.

And the stdlib does not use the missing shape at all — **0 of 91 ability
operations across 55 abilities** mention a type variable the ability does not
bind. So nothing today depends on the outcome either way.

## A sketch of the work, in order

1. Decide the surface. Mezze has no `<T>` position, so the operation's own
   variables are implicit — any variable in the op's signature that the ability
   and the impl head do not bind is the operation's. That reads well and needs
   no syntax, but it means a TYPO in a variable name silently becomes a new
   quantified parameter. Compare `project_type_application_arity_and_namespace`,
   where surplus type arguments were retained and a typo minted a distinct type.
   An explicit spelling may be worth the characters.
2. Widen `allowedTypeVars` in `AbilityValidator` and let the op's own variables
   through, with the ability's declaration and the impl's agreeing on them.
3. Make the op's dictionary slots work for a variable bound by neither the
   ability nor the impl head — probe the BUG 9 shape first (two abilities
   declaring the same operation name, two slots, one frame).
4. Specialise impl operations per call site in mono, with distinct identities,
   and rewrite the call sites to them.
5. Probe the whole matrix again: an operation parameter used in a parameter
   position, in the result, in both; two of them; one that also appears in the
   ability's own parameters; a recursive call through the op; and a call where
   the operation parameter is never determined (which should be T0215's
   analogue, not a silent escape).

## Until then

The declaration is accepted and no impl can satisfy it. Whoever picks this up
should decide between refusing the declaration (sound immediately, one check
beside the `it` rule, deleted again when the feature lands) and warning at the
declaration (nothing breaks, the author learns at the declaration instead of at
the first impl). A refusal is the sound option; a warning is the one that does
not spend an error code on a shape we intend to support.

Found by the ability/constraint spec dive, 2026-11-15, probe `K21`. The sibling
finding from the same dive — the `for` type in a non-`it` parameter — is a
different thing and IS just a rule: that shape has no working call form at all.
