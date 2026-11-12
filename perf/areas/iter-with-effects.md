<!-- Prose, not generated: this surface's work is not a list of
     operations. PERF-LOG.md has the full story of each line. -->

# Iter with effects — UNMEASURED, and the fuser does not check

**Nothing here has ever been measured.** This page exists because the gap is
structural rather than an oversight, and because one line of the canonicalizer
makes it a correctness question as well as a performance one.

## What the language allows

Every `iter` sink and stage carries an effect row on its callback:

```
pub let fold
  : { it: Iter 'a, init: 'b, f: { acc: 'b, x: 'a } -> 'b <...> }
  -> 'b <...>
```

So `f` MAY perform. A pipeline whose folder logs, reads a config, draws from a
sampler, or touches STM is well-typed and ordinary.

## What is not known

- **No bench has an effectful callback inside a `fold` or a `map`.** Checked:
  no `bench/cases/*/src/*.mz` puts a `perform` or a `println` in a pipeline
  callback. Every fusion number on this branch is for a PURE callback.
- So the cost of a performed effect per element is unknown, as is whether a
  chain with an effectful stage fuses at all, and what the handler lookup costs
  when it happens 10 million times inside one loop.
- `Iter` crosses a `@TruffleBoundary`, which is recorded as working; what it
  costs when the callback must reach a handler on the other side is not.

## And the fuser does not check

```kotlin
private fun isEffectSafe(chain: FusedChainIR, allFunctions: Map<Hash, FunctionIR>): Boolean {
    return true
}
```

Every sink branch in `ChainCanonicalizer` calls this before accepting a
candidate, and it is a **stub that returns `true`**. So a chain is fused without
anyone asking whether its stages perform.

That is probably fine today — fusion preserves the ORDER and the COUNT of
callback invocations, which is what an effect needs — but two of this branch's
rewrites move more than that:

- the **chain rewrite** reorders when the second SOURCE EXPRESSION is evaluated,
  which is why it carries its own whitelist rather than relying on this stub;
- a **short-circuiting sink** (`any`, `all`, `find`) stops early, so a stage's
  effects happen fewer times than an unfused walk would do them — correct for a
  pure stage, and a question for an effectful one.

## What to do first

1. Add rows: a `fold` whose folder performs a trivial op, with the handler
   installed OUTSIDE the loop and then INSIDE it; the same for a `map` stage.
   Compare against the pure rows and against `effectsurface`'s op-with-handler
   number (1 165 ps).
2. Decide whether `isEffectSafe` should be real, and write the test that fails
   if a chain with an effectful stage is fused when it must not be.
