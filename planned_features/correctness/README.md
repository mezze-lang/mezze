# Correctness tooling — status and review

Eight design documents, recovered 2026-09-24 from `shrynx/funk-lang` at
`feat/perf` (`04259a64`), where they lived under `docs/correctness/`. That repo
is reachable over SSH only; `docs/` was deleted from this repo in `3f22f5cd2`,
so this was one private branch away from being lost. **None of it is built.**

The thesis holds up and is worth keeping: code is increasingly LLM-generated, so
the developer's job shifts from writing to verifying intent, and types catch
structural errors while logic errors pass the typechecker. Hence *make
correctness visible, not provable* — generate the inputs, let the human read the
outputs.

What follows is the part the documents do not contain: where they collide with
the codebase as it actually stands. Read it before implementing any of them.

---

## 1. Unhandled effects are SILENT, not an error — this is the load-bearing one

`sample.md` says an effectful function without handlers should be refused with a
message. It is right, but its stated reason is too weak, and the real reason
decides the design.

Mezze's effect model is lazy-until-discharged. Calling an effectful function
outside a `perform` does not crash and does not error — it returns a
**`MezzeSuspend`**, a deferred computation
(`interpreter/.../values/MezzeSuspend.java`):

> - Inside perform: effects execute immediately (hot path)
> - Outside perform: effects return MezzeSuspend (cold path)

So a sample run that forgot handlers does not fail. It produces a table of
plausible-looking rows whose outputs are all suspended computations, and a
`println` entry point *prints nothing*. `EntryPointCheck.kt` already says this
in as many words:

> any CONCRETE effect left on its residual row leaks a MezzeSuspend at runtime
> — there is no host auto-discharge for "ambient" effects (a bare `println`
> entry prints nothing).

That is the worst failure mode a tool of this kind can have: **it looks like it
worked**. Refusing up front is not politeness, it is the only way the output
means anything. Sample must treat a non-empty residual row as a hard error and
must never render a `MezzeSuspend` as a result value.

**The machinery already exists** — do not write a second copy.
`cli/.../commands/EntryPointCheck.kt` has `residualEffects(fn)`, which walks to
the last arrow of a possibly-curried function type and returns its row, and
`MezzeType.EffectRow.allEffectNames` (`ast/.../ast/Type.kt:426`), which is
`effects + effectInstances.map { it.name }`. Extract them rather than
duplicating; that file's own header records what happened last time three
callers each rolled their own entry check and drifted apart.

### Three cases the documents never mention

1. **Partial handlers.** The design shows all-or-nothing. A user supplying one
   of two handlers must get the same refusal, which means computing the residual
   row *after* applying the `perform`, not comparing name sets before it.
2. **`*` and open row tails are not concrete effects.** `EntryPointCheck`
   deliberately allows `{} -> {} <*>`, because a performed marker and an open
   row-var are absent from `allEffectNames`. Sample must make the same
   distinction or it will refuse functions that are perfectly runnable.
3. **Associated types.** `effectInstances` carry bindings — `Exception having
   'error = Str`. A handler has to match the binding, not just the effect name.
   Nothing in `sample.md` addresses this, and `perform Exception in …` is
   ambiguous without it. See [[project_exception_assoc_type_thread]].

## 2. The runtime-node references are stale; the source-AST ones are fine

`instrumentation.md` and `debugging.md` name `MatchNode`, `IfNode` and
`MezzeStatementNode`. **None of the three exists.** The interpreter moved to the
Truffle Bytecode DSL (`interpreter/.../bytecode/MezzeBytecodeRootNode.java`), so
control flow is bytecode operations, not AST nodes to hang instrumentation tags
on. Anything in those documents that assumes per-node `InstrumentableNode`
tagging needs rethinking against BDSL.

Still real: `PipelineRunner`, `MezzeRootNode`, `MezzeFunctionRootNode`,
`InvokeNode`, `MonomorphicCallNode`, `BlockNode`. Existing instrumentation is
thin — `MezzeDebugScope`, `MezzeAllocationsInstrument`, `FrameSlotResolver`.

This does **not** affect sample's `--cover` and `--boundaries` modes, which is
worth stating plainly because it is easy to conclude the opposite: those read
the *source* AST (`ParsedModule` / `TypedModule`) to find comparisons, guards
and match arms. Only the pipeline view — capturing intermediate values inside a
`.`/`|>` chain at runtime — needs the instrumentation layer.

And that is **less blocked than it first appears**. `@GenerateBytecode` sets
`enableTagInstrumentation = true`, annotated *"LSP / debugger"*, so BDSL
instrumentation is on. What is missing is granularity: only `StatementTag` is
emitted, under `if (debug)`, and there is no `ExpressionTag`. A chain is one
statement containing many intermediate expressions, so the work is
expression-level tag emission, gated to cost nothing in production — real work,
but not a feasibility question. See `../tooling-platform/readiness.md`.

## 3. What is genuinely new is smaller than it looks

`sample.md` claims the only new piece is `InputGenerator: MezzeType ->
[MezzeValue]`, with parsing, typechecking and execution already existing. That
is broadly true and is the reason to start here. Two caveats:

- **Generating a `MezzeValue` is not free.** Records, variants and newtypes have
  runtime representations with invariants (see [[project_newtype_two_faces_hash_distinct]]
  and [[project_result_option_two_representations]] — `Result`/`Option` have
  *two* representations, `TypeApp` and `VariantType`). A generator that builds
  the wrong one produces values that typecheck on paper and misbehave at
  runtime.
- **Polymorphic signatures have no canonical instantiation.** `{x: 'a} -> 'a`
  cannot be sampled without choosing a type for `'a`. The documents never say
  what to choose. Defaulting to `Int` is defensible but must be stated in the
  output, or the developer reads a sample of a function they did not write.

## 4. Unstated failure modes

The documents assume sampling terminates and succeeds:

- **Non-termination.** Generated input hits an infinite loop. Sample needs a
  per-case timeout; the repo has a hard rule that tools must never hang
  ([[project_anti_hang_invariant]]).
- **Crashes.** A generated input divides by zero or blows the stack. Non-tail
  user recursion overflows the native stack at a few thousand levels
  ([[project_405_fib_profile_findings]]), and a generator producing a long list
  will find that. Per-case failures must be caught and shown as a row, not
  abort the run — showing the crashing input *is* the valuable output.
- **Effects that are not reproducible.** `SeededRandom {seed = 42}` is fine;
  a handler doing real IO is not. `--seed` promises reproducibility the handler
  may not honour, and the documents do not say so.

## 5. A bug in the design's own example

From `sample.md`'s higher-order section:

```
  {f = ({x} -> x + 1), x = 0}  -> 2
```

`0 + 1` is `1`. Left as-is in the ported text — worth keeping as a reminder
that reading example tables is exactly the activity this tool is for, and it
still takes attention.

---

## Implementation order

See `implementation-plan.md` for sample specifically.

But read `../tooling-platform/README.md` first. `mez sample` shares three
pieces with the REPL, a REBL-style browser, the LSP panel and a doc browser —
an evaluation channel, a value representation, and a query index — and building
sample alone means building private versions of all three, then rewriting them.
Stage 1 there (refuse correctly) is the exception: it is pure typechecker work
and depends on none of it.

`property-testing.md` in this directory needs a warning it does not carry: it is
built on `ast_gen` using comptime + reflection macros, and comptime does not
exist outside `planned_features/macros/`. It cannot be built yet.
