# `mez sample` — implementation plan

Staged so that each stage is independently useful and independently gated. Read
`README.md` first; it records where the design collides with the codebase.

## The shortcut that shapes everything

`sample.md` describes a bespoke execution path: parse the handler expression,
generate inputs, "wrap in perform block if handlers provided", execute. There is
a much cheaper route, because the CLI syntax the design chose **is already the
language's syntax**. `tooling/tree-sitter-mezze/grammar.js` has

```
perform Handler1, Handler2 in expr          // handler installation
handler: qualified_perform_name + optional record   // `FileLogger {path = "/tmp"}`
```

and the stdlib README uses `perform SeededRandom { seed = 42 } in program {}`
verbatim. So sample does not need to understand handlers at all. It can
**synthesise Mezze source** per case:

```mezze
perform Console, ExceptionToResult in module::parse_config { path = "/etc/hosts" }
```

and run it through the existing pipeline. Handler resolution, associated types,
parameterised handlers and multi-handler installation then work because they are
the language's, not a reimplementation — which also disposes of the
partial-handler and associated-type gaps in README §1.3, since the typechecker
judges the synthesised snippet.

The cost is one typecheck per case. Cache the module; only the call expression
changes.

## Stage 1 — refuse correctly (no generation at all)

The smallest thing that is not a lie. `mez sample module::f` resolves the
function, reads its type, and either reports the signature or refuses.

- Resolve `module::name` to a `TypedLetDecl`.
- **Extract `residualEffects` and `allEffectNames` out of `EntryPointCheck.kt`**
  into something both callers share. Do not copy them; that file's header
  records what happened when three callers each rolled their own.
- Non-empty residual row and no handlers → hard error naming the effects and
  showing the `perform … in` form to use.
- `<*>` and open row tails are **not** concrete effects — allow them.

Why this is a stage and not a footnote: an effectful function sampled without
handlers returns `MezzeSuspend`, so the failure is silent and the table looks
fine. Getting the refusal right is the feature.

**Gate:** a test per shape — pure, effectful-unhandled, effectful-handled,
`<*>`, open row, partially-handled, associated-type (`Exception having 'error`).

## Stage 2 — `InputGenerator` tier 1, pure functions only

`MezzeType -> List<MezzeValue>`, the one genuinely new component.

Start with `Int`, `Str`, `Bool`, `Float`, records of those. One-at-a-time
variation, not the cross product (the design is right about this: 5 edges × 2
args is ~10 cases, not 25).

Traps, from README §3:
- `Result`/`Option` have **two** representations, `TypeApp` and `VariantType`
  ([[project_result_option_two_representations]]). Pick deliberately and test both.
- Newtypes have two faces with distinct hashes
  ([[project_newtype_two_faces_hash_distinct]]).
- Polymorphic `'a` has no canonical instantiation. Default to `Int`, and **print
  the instantiation** — otherwise the developer reads a sample of a function
  they did not write.

**Gate:** generated values round-trip through the typechecker — for every
generated input, the synthesised call must typecheck. That is a strong, cheap
property and it catches wrong-representation bugs immediately.

## Stage 3 — execute, and survive the results

Per case: synthesise, typecheck, run, capture the value.

Note that `PipelineRunner` is `object PipelineRunner { fun compileFromIR(...) }`
— it compiles `FunctionIR` to Truffle nodes. It is not a "call this function
with these arguments" API, so the wiring is whatever `mez dev --no-watch`
already does to run a module, not a direct `PipelineRunner` call. Confirm that
path before estimating this stage.

The design assumes this succeeds. It must not have to:
- **Per-case timeout.** Generated input can hit an infinite loop, and tools in
  this repo must never hang ([[project_anti_hang_invariant]]).
- **Catch per-case failures.** Division by zero, stack overflow on deep
  recursion ([[project_405_fib_profile_findings]]). A crashing input is the
  most valuable row in the table — render it, do not abort the run.
- **Never render a `MezzeSuspend`.** If one reaches the formatter, Stage 1
  failed; assert rather than print it.

**Gate:** a case that loops forever, one that divides by zero, and one that
overflows the stack all produce a table with a marked row and exit 0.

## Stage 4 — CLI surface

`--cases`, `--seed`, `--json`. Interactive `[m]ore [f]ocus [c]ustom [q]uit`
last, and behind a TTY check so `--json` and pipes stay clean.

`--seed` promises reproducibility that a handler doing real IO cannot honour
(README §4). Say so in `--help` rather than implying determinism sample cannot
deliver.

## Stage 5 — tiers 2 and 3

Pairwise covering arrays on `[m]ore`, then bounded random. Both are plain
combinatorics over Stage 2's edge sets; no new interpreter contact.

## Stage 6 — code-driven modes (`--cover`, `--boundaries`)

These read the **source** AST — `ParsedModule`/`TypedModule` — for comparisons,
guards and match arms, then work backward to inputs. Unaffected by the BDSL
migration, contrary to what README §2's warning about stale node names might
suggest.

This is where the design's central claim is actually cashed: a library sees the
signature, sample sees the implementation.

## Stage 7 — LSP (needs an execution channel it does not have)

**The language server cannot run Mezze code.** `lsp/build.gradle.kts` depends on
`:ast`, `:parser`, `:typechecker`, `:langservice`, `:store-sqlite` and
`:formatter` — there is no `:interpreter`, no Truffle `Engine`, no `Context`
anywhere under `lsp/`. The design says "results appear in a panel beside the
code" and never addresses this.

So the work splits cleanly, and the split is favourable:

- Everything up to execution is **typechecker work the LSP already does** —
  resolving the function, reading its signature, computing the residual effect
  row, and therefore choosing between `[Sample]` and `[Sample (needs handlers)]`.
  Stage 1 lands entirely inside the server.
- Execution has to be delegated. Three options, in increasing order of weight:
  1. **Shell out to `mez sample --json`.** Cheapest, and `--json` exists in the
     design precisely for editor integration. Process per request; fine for a
     CodeLens the user clicks.
  2. **Reuse the RuntimeServer wire protocol**
     (`interpreter/.../standalone/RuntimeServer.kt`, [[project_wire_protocol]]).
     A warm runtime already speaking a protocol beats a cold process per click.
  3. **Add `:interpreter` to the LSP.** Rejected unless the others fail: it puts
     a Truffle Engine inside a long-running server, and Engines retain every
     CallTarget they create ([[project_oom_root_cause]]) — the exact shape of
     leak that has already cost this project once.

Option 1 to prove the feature, option 2 if latency justifies it.

CodeLens above each `pub let` (`[Sample]`, or `[Sample (needs handlers)]` when
the residual row is non-empty — Stage 1 already computes exactly that). The LSP
server already advertises `codeLensProvider` with `resolveProvider = true`
(`MezzeLanguageServer.kt:122`) and has a `codeLensEnabled` switch, so this is an
additional lens rather than new infrastructure.

## Deferred: pipeline view

Inline intermediate values for `.`/`|>` chains needs runtime instrumentation.
The per-AST-node tagging `instrumentation.md` assumes is gone — the interpreter
moved to the Truffle Bytecode DSL — but the capability is not:
`@GenerateBytecode(enableTagInstrumentation = true)` is set and annotated
*"LSP / debugger"*.

The gap is granularity. Only `StatementTag` is emitted, and only under
`if (debug)`; there is no `ExpressionTag`. A chain is a single statement with
many intermediate expressions, so this needs expression-level tags, gated so
they cost nothing in production. Deferred for effort, not for feasibility.

## Order of value

Stages 1–4 give a genuinely useful tool. Stage 6 gives it the justification for
being a language tool rather than a library. Stage 7 makes it ambient. The
pipeline view is the most quoted feature in the design and the least certain to
be buildable — which is a reason to sequence it last, not first.
