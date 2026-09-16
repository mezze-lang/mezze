# Known bugs — polyglot dot-chaining, codec, dispatch, and DX

Hand-off doc for the next agent. These are open bugs surfaced while building the
`mezze-showcase` polyglot demo (one Mezze seq → numpy stats → JS asciichart, all
dot-chained). The dot-chaining core was landed across commits `77d10d473`,
`3799a152a`, `790e0c047` (all gated green), but the fixes are narrow and leave
real gaps. Each bug below has: symptom, root cause, what was already tried, and
what a proper fix looks like.

Repo baseline: branch `feat/cli-ux-graalvm-provisioning`, HEAD `790e0c047`.
The demo lives at `~/Projects/mezze-showcase`.

---

## BUG 1 — FIXED: applied types are keyed on structure, not on a mangled name

**Fixed on `feat/soundness`.** `Hash.computeAppliedHash` mints
`hash(constructor ⊕ argument identities)`, and `appliedHashOf`
(`typechecker/.../util/ReceiverTypeName.kt`) is the single function both sides
go through — `ContextBuilder` when filing an impl, and all four resolution
ladders (ExprMethodConstraints, ConstraintSolver, TypedAstBuilder,
DispatchResolver) when looking one up, via `pickByReceiverHash`. Measured on the
real pipeline: `Result PyF IErr` → `225f0bdb`, `Result JsF IErr` → `eea33543`,
both still sharing declared `25a44d83`.

It is a MORE-SPECIFIC key layered over the constructor hash, not a replacement.
An impl with a type variable in any argument (`impl Result 'a 'e`,
`impl Result 'a IErr`) mints no applied hash and keeps the constructor hash,
because it has to serve every receiver in that position; receivers offer
`[applied, constructor]` and callers take the first that matches.

Consequences: the mangled `targetTypeName` is gone from the TypingContext
registry key; `anonymousImplMethodsByHash` no longer collides; and
`processInherentImpl` mints the same applied hash the impl was filed under
instead of the bare constructor's, which returned whichever provider landed in
the index last. Arguments with no declaration anywhere (`Int`, `Str`, `Seq`)
contribute their reserved name — the accepted fallback `receiverTypeName`
documents — and an argument with neither makes `appliedHashOf` decline rather
than mint a key that could compare equal to an unrelated type's.

Pinned by `AppliedTypeDispatchTest` (10 cases: the hash itself, plus end-to-end
dispatch through two same-constructor impls).

### Original report

**This was the big one.** The whole `Result <Lang>Foreign InteropError` dot-chain
dispatch leans on comparing the *mangled type-application name* string
(`Result_PyForeign_InteropError`) rather than a structural content hash. That
violates the project rule "NEVER name-match to ID; key on structure"
(`feedback_no_name_string_dispatch_ever`). The current code works for the demo
but is fragile and has at least one still-live collision.

### Symptom

- `np.attr{name}.call{[arr]}.method{item}.as_float{}` chains fail to typecheck
  with `T0206: Cannot resolve inherent method 'call'/'method'/'as_float': the
  receiver's type could not be determined` — but ONLY in some configurations
  (multi-provider, stale binary; see BUG 1c). At HEAD `790e0c047` the demo
  typechecks + runs on the CLI.
- Before the fixes: `PyForeign is a different declaration than JsForeign
  (content hash differs)` when both `mezze-python` and `mezze-js` were imported.

### Root cause (the architecture)

Every `<Lang>Foreign` provider ships two impl blocks with the SAME method names:
`impl PyForeign where { attr, call, method, as_float, … }` and
`impl Result PyForeign InteropError where { attr, call, method, as_float, … }`
(same for `JsForeign`). So `abilityOpIndex["as_float"]` holds MULTIPLE candidates
across providers, and dispatch must pick by the receiver's type.

The receiver of a chained `.as_float{}` is a `TypeApplication("Result",
[PyForeign, InteropError])`. Dispatch keys on:
- the **constructor** hash (`Result`) — `declaredHashOf` in
  `typechecker/.../util/ReceiverTypeName.kt` returns the TypeApplication's
  `identity.hash`, which for `Result` is the SAME hash whether the arg is
  `PyForeign` or `JsForeign`; and
- the **mangled name** (`Result_PyForeign_InteropError`) — `receiverTypeNames`
  in the same file joins constructor + arg names with `_`.

The arg (`PyForeign` vs `JsForeign`) is what distinguishes the two impls, but the
constructor hash ignores it — so any keying on the constructor hash collides.

### What was already tried (all committed, all narrow)

1. **`polyglotNativeSignature`** (`constraints/ExprLiteralConstraints.kt`,
   `generateIdentifier` NotFound branch): the polyglot member natives
   (`__native_polyglot_read_member/invoke_member/execute/instantiate/read_array/
   write_array`) were typed as a bare fresh var by the generic `__native_*` path,
   erasing the foreign type. Gave them `{ val: 'F, … } -> Result 'F InteropError`
   so the foreign flows through the chain and provider impls INFER their return
   type with zero annotations. The error arm is pinned to concrete `InteropError`
   (not a free var) so the intermediate mangles to `Result_<Foreign>_InteropError`
   and not `Result_<Foreign>_'?`. Also pinned the fallible coercion natives
   (`as_string/as_long/as_double/as_boolean`) to concrete primitive returns so a
   GENERIC combinator can chain past a coercion (`x.as_str{}.get_or{…}`).

2. **`ContextBuilder` constructor-hash for TypeApp targets**
   (`context/ContextBuilder.kt`, `addAnonymousImpl` ~L718,
   `registerImportedInherentImpl` ~L792, helper `implTargetDeclaredHash` ~L873):
   an `impl Result <T> <E>` used to register `targetTypeHash =
   currentTypeDecls[mangledName]` = null (the mangled name is never a decl key),
   so a `Result …` receiver never matched it and dispatch picked the wrong impl.
   Now falls back to the CONSTRUCTOR's decl hash — but ONLY for a genuine type
   application with args (`converted is TypeApplication && args.isNotEmpty()`),
   never a bare nominal (a bare nominal's cross-module conversion can mint a hash
   that differs from the receiver's — that regressed cross-module
   `impl Client`/`impl Req` chains; the `args.isNotEmpty()` gate is load-bearing).

3. **`TypingContext.extendWithAnonymousMethod` key collision**
   (`context/TypingContext.kt` ~L285): keying methods on `targetTypeHash.hex`
   alone made `impl Result PyForeign` and `impl Result JsForeign` collide (both =
   `Result`'s hash) → one clobbered the other → a pure-Py chain lost its
   candidate. Fixed by keying on `"${hash.hex}:${targetTypeName}"` (mangled name
   distinguishes them). **← still a name-based key.**

4. **`ExprMethodConstraints.tryGenerateMethodCall` specificity filter**
   (`constraints/ExprMethodConstraints.kt` ~L78): the multi-candidate filter used
   `candidateTypeNames` (bare constructor "Result") which never matched a
   candidate registered under its mangled `targetTypeName`, so two per-language
   Result impls stayed ambiguous → a LOAD-ORDER tiebreak picked whichever provider
   was imported first. Now walks `receiverTypeNames` (specificity-ordered,
   mangled-first) and takes the first UNIQUE-target match. **← name-based.**

### Issues that REMAIN (what to actually fix)

- **`anonymousImplMethodsByHash` STILL collides.** `extendWithAnonymousMethod`
  (`TypingContext.kt` ~L288) builds the by-hash index with
  `Hash.computeOpHash(targetTypeHash, methodName)` using the CONSTRUCTOR hash, so
  `impl Result PyForeign.method` and `impl Result JsForeign.method` map to the
  same key — one overwrites the other. Its only reader is `processInherentImpl`
  (`constraints/ConstraintGenerator.kt` ~L887,
  `findAnonymousMethodByHash(constructorHash, op.name)`), which looks up an impl's
  OWN method info while checking the impl body. It returns the wrong provider's
  info; this is currently benign ONLY because both providers' method signatures
  are structurally identical modulo the foreign type, and there's a
  `findAnonymousMethod(targetTypeName, …)` fallback. Fragile — a provider with a
  differently-shaped method would mis-check.

- **The whole thing is name-based.** Points 3 and 4 above key on the mangled
  string `Result_PyForeign_InteropError`. The correct fix is a **structural
  type-application hash**: `hash(constructor-decl-hash ⊕ arg-identity-hashes)`,
  computed identically on (a) the receiver side (`declaredHashOf` for a
  `TypeApplication` with concrete args), (b) the impl-target side
  (`ContextBuilder`), and (c) the by-hash index. Then `Result<Py>` and
  `Result<Js>` have DISTINCT content hashes, the registry never collides, and
  `DispatchResolver.pickInherent` (`solving/DispatchResolver.kt` ~L265) matches by
  hash without needing the mangled-name walk. This removes points 3, 4, and the
  remaining collision in one structural change. `DispatchResolver.receiverKey`
  (~L117) and `pickInherent` should then be re-examined: today `pickInherent`'s
  `ByHash` arm matches on the constructor hash and would throw
  `DispatchAmbiguityException` for two same-constructor Result impls if it ever
  reached them with concrete args — it doesn't today only because
  `ExprMethodConstraints` pre-filters by mangled name first.

- **T0206 in the LSP but not the CLI** (BUG 1c): the user sees `T0206: Cannot
  resolve inherent method 'call'/'method'/'as_float'` in the editor while the
  exact same file typechecks and RUNS on `~/.mezze/bin/mez` at the same HEAD.
  This is almost certainly a **stale LSP toolchain**: the LSP server is a
  long-running process pinned to an older `mez`, and `installNative` does not
  update the proxy's global default (see BUG 5), so the editor kept resolving
  pre-fix code. Restarting the LSP / `mez toolchain default <latest>` should clear
  it. If it does NOT, there is a genuine second dispatch path only the LSP hits —
  worth confirming by pointing the LSP at HEAD explicitly.

### Repro (multi-provider, pre-fix behaviour)

```mezze
use mezze_python::python::{ Python, GraalPy, PyForeign, null_val as py_null }
use mezze_js::js::{ Js, GraalJs, JsForeign }        # importing BOTH is what breaks it
let main = {} -> perform Console, GraalPy, GraalJs in do
  let np = Python.import { module = "numpy" }
  # any chain past .attr fails: PyForeign vs JsForeign mismatch / T0206
  np.attr { name = "pi" }.as_float {}
```

---

## BUG 2 — PARTIALLY FIXED: the runtime primitive exists; provider impls remain

**Runtime half fixed on `feat/soundness`.** `ForeignContainers` (interpreter)
adds guest-readable list and dict VIEWS, and `PolyglotNatives.makeList` /
`makeDict` expose them as `__native_polyglot_make_list` / `make_dict` plus the
per-language `__native_<lang>_make_list` / `make_dict`. They are interop views,
not copies, so one implementation serves Python, JS and WASM: the guest reads
elements on demand rather than going through any one language's list builder.

Reachable in-tree through `std::polyglot::jvm`'s `jvm_list` / `jvm_dict`, and
covered by `PolyglotCoreTest` — including a case that hands a built list to
`java.util.Arrays.asList`, which only works if the value genuinely answers the
interop array messages.

**Still open, and outside this repo:** steps 2 and 3 below — the `EncodeList` /
`EncodeMap` / `EncodeRecord` impls and their decode mirrors live in the
`mezze-python` and `mezze-js` provider packages. They now have a native to call.

### Original report

### Symptom

```mezze
let data = [2.0, 4.0, 4.0]
Python.eval { source = data.to_py {} }   # T0205: Cannot access field 'to_py' on non-record type: [Float]
```

A user reasonably expects a codec to compose over containers: a `[Float]` is
"encode each element, wrap in a Python list". It doesn't.

### Root cause

The **framework is complete but the providers are not.** `std::codec::core`
(`stdlib/std/codec/core.mz`) declares the full tier of container encode abilities
— `EncodeList` (L91: `encode_list: { it, elems: ['out], ctx } -> 'out`),
`EncodeMap` (L96), `EncodeRecord` (L80), `EncodeVariant` (L85), plus decode
mirrors. The JSON codec implements them (e.g. `json.mz` L218:
`encode_list = { it, elems, ctx } -> JArray { items = elems }`).

But the **Python provider** (`mezze-python/src/codec.mz`) implements ONLY
`EncodeScalar` + `EncodeOption` (and their decoders) and explicitly punts on
containers (its own header comment, L13–18: *"Records / lists / maps are NOT
implemented here…"*). The JS provider (`mezze-js/src/codec.mz`) is the same. So
`Encode PythonC [Float]` has no chain → the `ToPy` blanket
(`impl ToPy for 'a where { to_py … with Encode PythonC 'a }`, codec.mz L172) does
not apply to a seq → `.to_py {}` is reported as a field access on a non-record.

**Second half of the gap: no runtime primitive to build a guest list.**
`interpreter/.../nodes/builtin/polyglot/PolyglotNatives.java` +
`NativeDispatch.java` expose `read_array` / `write_array` / `array_size` (operate
on an EXISTING guest array) but NOTHING that CONSTRUCTS a guest list/dict from
Mezze-side `[MezzeForeign]` elements. So even with an `impl EncodeList`, there is
no native for its body to call. `encode_list` receives `elems: [PyForeign]`
(each already encoded) and must produce one Python-list `PyForeign`.

### Fix (chosen scope: make `to_py`/`to_js` structural)

1. **Runtime native** — add `__native_polyglot_make_list { elems: [<Foreign>] }
   -> <Foreign>` (and `make_dict` for maps) in `PolyglotNatives.java`, registered
   in `NativeDispatch.java`. Build the guest list from the delegate values — via
   a Truffle list proxy or the guest's own list builder. Language-agnostic (same
   `MezzeForeign`), so one native serves Python and JS.
2. **Provider impls** — `impl EncodeList for PythonC` (and `for JsC`) whose
   `encode_list` calls the make-list native; ideally `EncodeMap` + `EncodeRecord`
   too so records/maps round-trip. Bump both provider package versions.
3. **Decode mirror** — `DecodeList`/`DecodeMap` for `from_py`/`from_js` so a guest
   list decodes back to a Mezze seq.

### Caveat worth writing into the provider docs

`to_py {}` returns a `PyForeign` (a live Python object), NOT a source string.
`Python.eval { source = … }` takes a **`Str`** of Python source, so
`Python.eval { source = data.to_py {} }` is a type error even once `to_py` works
for lists — it would hand `eval` a PyForeign where it wants Str. The value form
is `numpy.array(<PyForeign list>)` or passing the PyForeign directly as a `.call`
arg. Today the demo sidesteps all of this with `data.to_dbg {}`, whose derived
`Inspect` output (`[2.0, 4.0, …]`) happens to be valid Python AND JS list
syntax — a convenient coincidence, not a designed conversion path.

---

## BUG 3 — FIXED: both directions of the usage-tracking gap

**Fixed on `feat/soundness`.** Two halves of one weak spot — usage tracking did
not model effect discharge or method dispatch as a reference.

*Performed but never discharged:* `generatePerform` now asks the V0510 question
once PER HANDLER (it previously fired only when the body performed nothing at
all, so a dead handler beside a live one was never examined) and reports each
dead one as **W0401** — a warning code that had an explanation written for it but
was emitted by nothing. Warning, not error, on purpose: the body's effect row is
inferred, and once other effects are flowing it is weaker ground than the
all-pure case V0510 rejects outright. Pinned by `UnusedHandlerLintTest`,
including the case where one handler's effect is reached only transitively
through another's impl. The full typechecker gate shows no false positive.

*The inverse:* the LSP's remove-unused-import quick fix called `unusedItemsFor`
without the ability-operations map, recomputing a narrower answer than the
diagnostic that triggered it — and offering to delete an ability imported purely
for dot-dispatch, breaking the build on the very edit presented as the fix. It
now takes and is passed that map (`AbilityImportUsageTest`).

### Original report

### Symptom

```mezze
let main = {} -> perform Console, GraalPy, GraalJs in do
  # ... only Python is ever used; GraalJs is never performed ...
```

`GraalJs` in the `perform` list is dead — nothing in the body performs a `Js`
operation — yet there is no error or warning. Confirmed at HEAD: the trimmed
showcase `app.mz` performs `Console, GraalPy, GraalJs`, uses only Python, and
typechecks + runs clean.

### Why it's a bug

A performed handler that is never discharged is dead code at best and a
correctness smell at worst (the reader assumes JS is in play). This should be at
least a warning ("effect `GraalJs` is performed but never used — remove it from
the `perform`"), following the graceful-actionable-error principle
(`feedback_cli_ux_graceful_errors`).

### Related pre-existing bug (usage tracking, the INVERSE direction)

`BUG_ability_import_false_unused` (folded from the deleted `docs/bugs/`): an
ability imported ONLY so its methods resolve via dot-dispatch is FALSE-flagged as
unused, and following the warning breaks the build:

```mezze
use std::codec::json::{ Json, parse_str, FromJson }   # FromJson only used via j.from_json {}
let decode: { j: Json } -> Result Reply DecodeError = { j } -> j.from_json {}
# warning: Unused import: 'FromJson' is never referenced  ← WRONG
# remove it → error: Cannot access field 'from_json' on variant type: Json
```

The unused check looks for the identifier as a NAME and does not count method
dispatch (or a `with` constraint, or a `perform`) as a use. BUG 3 is the same
analysis failing the other way — it does not notice a performed effect is unused.
Both point at one weak spot: **usage tracking does not model method-dispatch /
effect discharge as a reference.** Acceptance: an ability/effect used only via
dispatch counts as used (no false warning); an effect performed but never
discharged warns (no false silence).

---

## BUG 4 — FIXED (the part that is ours): packaging leak closed, plugin chatter demoted

**Fixed on `feat/soundness`.**

*The packaging leak, which was the real bug:* `TestNativePlugin` and its
`META-INF/services` entry lived in `interpreter/src/main` and so shipped on the
production service-loader path. Both moved to the test source set. Verified by
scanning every jar under `cli/build/install` — neither the class nor the service
entry appears in any shipped artifact, and `NativePluginTest` still finds the
plugin on the test classpath.

*The plugin chatter:* `loadPluginsViaServiceLoader` logs registrations at FINE
instead of INFO. A registration FAILURE stays at WARNING and now names the
plugin and what to do about it — its natives are missing, so the program is
about to fail somewhere far from the cause. A fresh `mez dev --no-watch` is
clean.

*The incubator notice is NOT ours and was deliberately left alone.* `WARNING:
Using incubator modules: jdk.incubator.vector` is written to stderr by
ModuleBootstrap before any Mezze code runs. Measured against `-Xlog:disable`,
`-Xlog:all=off`, `-XX:-PrintWarnings` and `--enable-preview`: none suppress it.
Both workarounds cost more than the line is worth — piping the launcher's stderr
through a filter gives up the `exec`, so signals reach the wrapping shell rather
than the JVM (Ctrl-C breaks) and every launch grows a process; dropping
`--add-modules` silently disables the SIMD path. The reasoning is recorded in the
generated launcher's own comments so it is not re-litigated.

### Original report

### Symptom

A normal `mez dev --no-watch` (or `mez build`) prints, before program output:

```
WARNING: Using incubator modules: jdk.incubator.vector
… com.mezze.interpreter.nodes.builtin.NativeDispatch loadPluginsViaServiceLoader
INFO: Registered native plugin: TestNativePlugin (interpreter SPI smoke test)
… INFO: Registered native plugin: PolyglotJsPlugin (mezze-js provider)
… INFO: Registered native plugin: PolyglotPythonPlugin (mezze-python provider)
data  ->  [2.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0]
```

### Why it's a bug

- These are internal INFO/WARNING lines from `NativeDispatch.loadPluginsViaServiceLoader`
  and the JVM incubator-module notice. Per `feedback_cli_ux_graceful_errors`,
  JVM/Truffle chatter belongs behind `--verbose`, not in a clean run.
- **`TestNativePlugin (interpreter SPI smoke test)` registers in a PRODUCTION
  run.** A test-only SPI plugin is on the production service-loader path — that is
  a packaging leak, not just noise. It should not be discoverable outside tests.

### Fix

Route plugin-registration + incubator notices through the logger at DEBUG (shown
only under `--verbose`), and exclude `TestNativePlugin` from the production
service-loader manifest (move it to a test-only source set / resources dir).

---

## BUG 5 — FIXED: `installNative` now always sets the freshly-installed toolchain as the default

`./gradlew :cli:installNative` builds a correct native binary and installs it as a
NEW toolchain, but the `~/.mezze/bin/mez` proxy keeps resolving the OLD global
default, so `mez` (and any LSP launched via it) runs stale code until
`mez toolchain default <new-version>` is run manually. This is why BUG 1c (LSP
shows T0206 while the CLI at the same HEAD works) is most likely a stale-toolchain
artifact rather than a live dispatch bug.

FIXED (cli/build.gradle.kts, `installNative`): the task seeded
`~/.mezze/settings.toml` with `default = <version>` ONLY when no `default` line
existed, so every install after the first left the proxy resolving the old
toolchain. It now rewrites the line every time and logs the transition
(`Default toolchain → <new>  (was <old>)`), so a stale default is visible rather
than silent. An explicit `mez toolchain default <v>` afterwards still wins.

On the "two mismatched versions" note: the version is a single configure-time
`val mezzeVersion` shared by every module, and `BuildInfo.VERSION` is generated
from it, so a version bump regenerates the source and forces a recompile — the two
cannot diverge within one invocation. Nothing to fix there.

NEXT for BUG 1c: reinstall, restart the LSP, and re-run the BUG 1 repro. If T0206
persists in the editor at a HEAD where the CLI is clean, there IS a second
dispatch path only the LSP reaches — record which it was.

---

## Pre-existing dispatch bug (folded from deleted `docs/bugs/`)

### FIXED: `.size {}` / `.is_empty {}` on a match-arm-bound seq crashes in CallIndirect

**No longer reproduces** — closed by the dispatch work in this effort. Verified
against the report's source verbatim (with and without the seq import) and locked
by `MatchArmSizeDispatchTest`, which covers the two methods that crashed plus the
`.map{…}.size{}` shape that always worked, so a regression is caught as a
regression rather than rediscovered.

### Original report

```mezze
type T = | Wrap { items: [Int] } |
let n = { t : T } -> match t is
  Wrap { items } -> items.size {}          # crashes at runtime
```

Typechecks (`n : { t: T } -> Int`), fully annotated — a LOWERING bug, not a
typing one. Crashes with
`UnsupportedSpecializationException … CallIndirect … [MezzeRecord, Object[]]`.
A seq bound from a variant payload in a match arm reaches `Sized` dispatch without
a resolvable impl and falls through to the mono/dict path where no dictionary slot
exists for the (derived-ish) `Sized` op. Scope: crashes ONLY for a variant payload
bound in a match arm (seq literal / `let` / record field / param-record-field all
work). `.map{…}.size{}` works — any intermediate call between the binding and the
`Sized` method fixes it, so the receiver reaching dispatch differs by one step.

Same shape as the `Inspect` (`to_dbg`/`to_str`) fix in `TypedASTCompiler.kt`
(~L1360), which was scoped by method NAME; `size`/`is_empty` reach the same broken
path by a different route. Proper fix: **a receiver that arrives at dispatch
without a resolvable impl must not reach CallIndirect at all** — one structural
correction covering both rather than per-method-name special cases. Related:
`project_sized_mut_ability` (receiver-driven same-op dispatch is the mechanism
failing here). Overlaps thematically with BUG 1: both are dispatch reaching a
value whose concrete type the emitter never pinned.

---

## Suggested order for the fixing agent

1. **BUG 5** first (one-liner) — so the LSP/CLI run the same code and BUG 1c
   disappears or proves real.
2. **BUG 1** structural type-application hash — the root fix; removes the
   name-based keying (points 3/4) and the `anonymousImplMethodsByHash` collision.
3. **BUG 2** codec containers — make-list native + `EncodeList`/`EncodeMap` in
   both providers.
4. **BUG 3** usage tracking (both directions) and **BUG 4** log noise — DX.
5. The pre-existing `.size {}`-in-match-arm lowering bug — likely shares BUG 1's
   "no resolvable impl → CallIndirect" root; check if one fix covers both.
6. **BUG 6** (below) is the class all of the above belong to; its master task
   list (Track 1 → Track 2) is the plan. BUG 1's structural type-application
   hash is task B.1 there; the `.size {}` crash is closed by A.2.

Gates for every change: `:typechecker:test` and `:interpreter:test` run
SEPARATELY with `--no-daemon` (`feedback_gate_run_suites_separately`). Strip debug
prints before the full suite (`feedback_no_debug_prints_in_full_suite`). No type
annotation may ever be required to typecheck — if adding one "fixes" a chain, that
is an inference bug (`feedback_no_required_type_annotations`).

---

## BUG 7 — FIXED: a hand-written `impl Ord` ignored by generic consumers

**The most serious defect found in this effort, because it was SILENTLY WRONG and
not only a crash.** Found 2026-09-15 while re-checking GAP #30 (mis-filed as
"fails to type" — it type-checks and then misbehaves at runtime).

### Symptom

```mezze
type P = { k: Int, tag: Int }
impl Ord for P where { lt = { it, r } -> it.k < r.k, le = …, gt = …, ge = … }

let xs: [P] = [{ k = 3, tag = 30 }, { k = 1, tag = 10 }]
xs.min {}                                  # Cannot compare MezzeRecord < MezzeRecord
sorted_set::from_seq { xs = xs }.min {}    # same
```

For a newtype it did not crash — it returned the WRONG ANSWER, because a newtype
wraps an Int at runtime so the primitive fallback agrees with an ordinary
ordering. A deliberately REVERSED newtype Ord returned the smallest where it must
return the largest.

### Two separate causes

**1. The seq operator paths** (`seq::min`, `seq::max`, `seq::sort` over records).
Those calls are monomorphized. Inside a spec, a receiver typed by the generic's
own variable has no dict slot (the spec strips it, the constraint being concrete)
and cannot inline (not a primitive), so `x < acc` fell to the primitive node.
`specEvidenceOpHash` now resolves it from the spec's own recorded evidence
(`Specialization.abilityImpls`). One narrowing remains and is load-bearing: this
applies only to an INLINEABLE op with no dict slot — the case that would
otherwise reach the primitive comparison node.

A second narrowing is GONE. `specEvidenceOpHash` also used to fall back to the
spec's TYPE ARGUMENTS, gated to specs with exactly ONE argument, because with two
(`map::filter` at `Map K V`) the receiver's variable could be either and
resolving against the pile broke seven map/set filter tests. That was a guess
with a guard on it, and **C.1 deleted it outright**: once a specialization
resolves its OWN constraints instead of inheriting its caller's, the evidence is
present wherever it is meaningful. Measured both ways before choosing —
broadening the fallback reproduced exactly those seven failures
(MapIntegrationTest x2, SetMapAdvancedOperationsTest x4, MapModuleTest x1);
deleting it produced none.

Also needed: `prescanForDictMethods` phase 2 now propagates a callee's dict needs
through IMPL-OP bodies, not only top-level lets.

**2. The sorted containers** — a completely different cause, and the long detour
through "whether the element's Ord is used depends on what ELSE is in the
program" was a red herring. **The element's Ord reached the container correctly
all along.** Measured at the native boundary: the stored comparator is called,
calls P's `lt`, returns the right answer, and the set is built correctly ordered
(`to_seq` reads it back sorted, `contains` answers correctly).

`ChainCanonicalizer` was rewriting `__native_sorted_set_min { s }` into
`to_seq { s } |> Min`, and the fused `Min` sink compares with `DirectLessThan`,
which only specializes long/double/String/Frac. So a sorted set built with the
element's own `cmp` closure had that comparator swapped for a primitive `<` at
the moment it was READ BACK. Adding a `min` call to a program is what introduced
the fusable native — which is exactly why the same construction passed when the
program stopped at `size`.

**Fix**: `Min`/`Max` no longer fuse over a container that owns its ordering
(`ownsItsOrdering` in `ChainCanonicalizer`). Nothing is lost —
`__native_sorted_set_{min,max}` are the ONLY bare min/max element natives that
exist (every other collection's `min` is a Mezze `reduce` over the element's Ord,
which never reaches the canonicalizer), and the native reads `elements[0]` in
O(1) where the fused form rescanned in O(n). BUG 8 was the same family: a fused
numeric pipeline firing on values it cannot compare.

### What the investigation ruled out

Each of these was tested, not argued, and each is recorded because it looked
convincing:

- *A name collision on `min`.* Five of the stdlib's six `min` declarations carry
  `with Ord 'a`, so resolving to the wrong one would explain it exactly. A
  uniquely-named probe with the identical body failed identically.
- *The lambda-hash collision.* `sorted_set.mz` holds EIGHT byte-identical
  `{ a, b } -> if a < b …` comparators, `hashExpr` is type- and
  environment-blind, and the dedupe is first-occurrence-wins — 15 declarations
  across `sorted_set`/`deque`/`seq` collapse onto two hashes. Real, and measured,
  but not this bug: salting them apart changed nothing. Not kept, because salting
  by enclosing HASH also makes a `newtype` declaration change an otherwise
  identical program's IR (`NewtypeErasureIRTest` catches that).
- *The solver, monomorphizer and call-site rewriting.* All correct: `from_seq`
  gets the same spec with `abilityImpls=1` in passing and failing programs, the
  same site rewrite, and every specialized body is right — including `min`'s,
  which is a clean `CallNativeIR(__native_sorted_set_min)`.

### Tooling notes for anyone tracing something similar here

Three false signals cost a round each: `NativeDispatch` and
`NativeDispatchScalarNode` are NOT on the live path (natives are bytecode
operations, so probes there never fire); `MezzeLanguage.getCachedStdlibCallTarget`
serves USER functions too, at its route 2, so resolving through it does not mean
"stdlib"; and IR dumps must filter on `op=LT` — `"LessThan"` is the runtime
node's name and never appears in IR, so that filter reports a false negative.

The probe that finally located it was dumping the entry FunctionIR at
`MezzeLanguage.compileEntryFn`, where the `FusedChainIR(… sink=Min, fallback=
CallNativeIR(__native_sorted_set_min))` is plainly visible.

### Why the existing gates missed it

`ElementInstanceMatrixTest` has exactly the two blind spots that hide this: its
element type is a NEWTYPE (where the primitive fallback agrees), and it exercises
`min_by`/`max_by` (an explicit key function) rather than the operator-based
`min`/`max`/`sort`.

### Acceptance

`SortedContainerReadBackTest` — 19 cases, all passing, none disabled, including
the REVERSED-Ord newtype that detects silent wrongness rather than a crash.

---

## BUG 10 — FIXED: the `_by` fusions compare the KEY with a primitive `<`

Found 2026-09-16, immediately after BUG 7's sorted-container half, by asking
whether that fix's siblings shared the hazard. They do.

### Symptom

```mezze
type K = { n: Int }
impl Ord for K where { lt = { it, r } -> it.n < r.n, … }
type P = { k: K, tag: Int }
let xs: [P] = [{ k = { n = 3 }, tag = 30 }, { k = { n = 1 }, tag = 10 }]

xs.min_by { f = { x } -> x.k }        # Cannot compare MezzeRecord < MezzeRecord
xs.max_by { f = { x } -> x.k }        # same
xs.sort_by_key { f = { x } -> x.k }   # same
xs.min_by { f = { x } -> x.tag }      # OK — primitive key
```

And the serious case, which does not crash: a NEWTYPE key whose Ord disagrees
with the wrapped type's returns the WRONG ELEMENT, exactly as BUG 7 did.

### Cause

`min_by`/`max_by`/`sort_by_key` are `{ xs: ['a], f: { x: 'a } -> 'b }
with Ord 'b`. The stdlib implements each by handing `*_with_cmp` an explicit
comparator built from the key's Ord — correct, and it is what the non-fused path
runs. `ChainCanonicalizer` fuses the call into a `MinBy`/`MaxBy` sink, and
`BytecodeCompiler`'s `MinMaxByKey` case emits the key comparison as
`beginDirectLessThan()`, which only specializes long/double/String/Frac. **The
supplied comparator is discarded.**

Same family as BUG 7's sorted-container half and BUG 8: a fused fast path
substituting a primitive operation for one that needed an instance.

### Layer 1 — the fusion half — FIXED

The decision needs the KEY'S TYPE, so the compiler now makes it where the solved
type is in hand and CARRIES it: `FunctionIR.returnOrd: OrdKind?`
(`DIRECT` = long/double/Str/Frac, what the Direct comparison nodes specialize;
`INSTANCE` = needs the type's own `Ord`; null = undecided). `ordKindOf` in
`TypedASTCompiler` computes it from the body's solved type, and
`classifyMinMaxByMode` declines the fusion unless it is `DIRECT` — null declines
too, because fusing on a guess is what caused this.

A NEWTYPE answers `INSTANCE` even when it wraps an Int. That is the whole point:
`newtype R = Int` with a reversed `Ord` compares as a long at runtime, so the
primitive path agrees with the WRAPPED type's order and silently contradicts the
one the author wrote.

Verified: the primitive-key fast path still fuses (`min_by`/`max_by` on an Int
key pass), and the record-key path no longer silently discards the comparator.

### Layer 2 — the fallback was equally wrong — FIXED by C.1

Declining the fusion is not enough, because the NON-fused path loses the
instance too. Measured:

```
[spec] min_by@seq.mz:310  typeArgs=[P{k: K{n: Int}}, K{n: Int}]  impls=0
```

The type arguments are fully concrete — `'a = P`, `'b = K` — and yet the
specialization carries **no evidence at all**. So inside it, `specEvidenceOpHash`
has nothing in `Specialization.abilityImpls` to consult, and its type-args
fallback declines because there are TWO arguments and the receiver's variable
could be either. The comparator's `<` then reaches the primitive node.

The gap is precisely located:

- `MonoDriver.seedFromMethodCall` builds its `SpecRequest` with
  `abilityImpls = emptyList()` — method-style calls record no evidence.
- `Monomorphizer`'s transitive worklist passes `r.inst?.abilityImpls` — the
  ENCLOSING instance's evidence — instead of resolving the CALLEE'S OWN
  constraints against the callee's now-concrete type arguments. Instrumenting
  `resolveAbilityConstraints` shows only the impl op `min_by@677` ever reaches
  it; the module-level `min_by@310`, the one that actually carries
  `with Ord 'b`, never does.

**Fixed**: `Monomorphizer` now takes a `resolveEvidence` callback — supplied by
`MonoDriver`, which owns the impl set, so there is ONE rule for what discharges a
constraint rather than two — and a transitive specialization resolves its own
constraints instead of inheriting the caller's.

The change is deliberately additive: a site that already carries evidence keeps
it untouched, and only the empty case is filled in. `abilityImpls` feeds
`specializedHash`, so re-deciding evidence for sites that already had an answer
would renumber specializations wholesale — which is how a broader version of
this resolution previously broke seven map/set filter tests. Both gates are green
with no filter regressions.

### Why it was not fixed alongside BUG 7

BUG 7's half was fixable by refusing to fuse, because
`__native_sorted_set_{min,max}` are the only bare min/max element natives and the
native is O(1) — nothing profitable was lost. **That argument does not transfer.**
`min_by` over an Int key is a genuine, perf-critical fast path, so the fusion has
to stay and the decision has to be made on the KEY'S TYPE.

That type is not available. Measured: the key lambda for an Int key and for a
record key compile to STRUCTURALLY IDENTICAL IR —

```
ReadFieldIR(record=ReadLocalIR(slot=0, kind=OBJECT, typeTag=null),
            fieldName=<tag|k>, fieldIndex=-1)
```

— differing only in the field NAME, and `FunctionIR.returnType` is null for both.
There is nothing to branch on. A runtime dispatch on the key's class is not an
option either: that is a name/structure lookup in the hottest loop the compiler
has.

So this is blocked on the key type being CARRIED rather than reconstructed —
tasks **C.1** (solver records evidence per constraint per node) and **A.2**
(builder reads types by NodeId; a miss is an ElaborationError, never a fresh
var). It is the first demonstrated failing case those tasks own, and should serve
as their acceptance test.

### Acceptance

`KeyedFusionOrdTest` — 7 cases, all passing, none disabled: 3 guard the
primitive-key fast path (which still fuses) and 4 cover the broken orderings,
including the reversed-newtype case that detects silent wrongness rather than a
crash.

---

## BUG 11 — OPEN: a generic `with Ord` body compares a newtype by its WRAPPED order

Found 2026-09-16 while probing C.3's evidence selection. The probe was hunting a
wrong ref being picked among several; what it found is that even a SINGLE
constraint gets this wrong.

### Symptom

`B` wraps an Int and its `Ord` is deliberately REVERSED, so `B.(1) < B.(3)` is
False. Every concrete path agrees. Only the generic one does not:

| path | answer |
|---|---|
| `B.(1) < B.(3)` — operator, concrete | false |
| `B.(1).lt { r = B.(3) }` — method, concrete | false |
| `let f: { x: B, y: B } -> Bool = …` — concrete annotation | false |
| `[B].min {}` — through seq (BUG 7's fix) | 3 |
| `let g: { x: 'b, y: 'b } with Ord 'b -> Bool = { x, y } -> x < y` | **true** |

Silent wrongness, not a crash — the same shape as BUG 7 and BUG 10. A newtype is
erased to its wrapped value at runtime, so a primitive comparison agrees with the
WRAPPED type's ordering and quietly contradicts the one the author wrote.

### Cause

Compiling the impl op shows the generic's `<` lowered to

```
CallIndirectIR(function=ReadFieldIR(record=ReadLocalIR(slot=2), fieldName="lt"),
               arg=MakeRecordIR([it = x, r = y]))
```

— a DYNAMIC field read named `lt` off `x`, which at runtime is a raw long. The
compiler emits that shape when the receiver has no type, and the receiver has no
type because of `TypedAstBuilder`'s binding fallback:

```kotlin
else structuralBindings[name] ?: MezzeType.VarType(TypeVar.freshWithName(name))
```

When neither the solver nor the structural read supplies a type, the binding gets
a FRESH VARIABLE — and a fresh variable is indistinguishable from a legitimately
polymorphic one, so nothing downstream can tell that the compiler simply did not
know. Instrumenting that site shows real cases arriving with
`solver=[] structural=[]`.

That fallback is exactly what task **A.2** removes ("builder reads types by
NodeId; a miss is an ElaborationError, never a fresh var"). This is its first
demonstrated silent-wrongness case and should be its acceptance test.

Two halves need attention: the fresh-var fallback itself, and the fact that the
structural read is gated on `typingImplOpBody`, which is FALSE on this path — so
the structural recovery never even runs.

Related and already fixed as part of this investigation:
`patternBindingTypes`'s `ParsedNewtypePattern` arm returned `emptyMap()`,
dropping the inner pattern entirely so `B.(x)` never typed `x` structurally. That
is now recursive against the wrapped type. It does not fix this bug (the repro
does not reach the structural read at all) but it closes the same one-sidedness
the seq arm was hardened against.

### Acceptance

`GenericNewtypeOrdTest` — 4 enabled cases pinning the concrete paths that work,
2 `@Disabled` on the generic paths.

---

## BUG 8 — FIXED: the fused LONG pipeline fired on a seq of records

Found 2026-09-16 while tracing BUG 7 — the sorted-container round trip was dying
here rather than in any comparison.

`FusedLongPipeline` is a numeric fast path: its loop reads every element with
`((Number) raw).longValue()`. The classifier that chooses it works from the SHAPE
of the call chain, and `SinkIR.ToSeq` / `ToList` mapped unconditionally to
`FusedSinkMode.Materialize`, which on its own admitted a chain into the pipeline.

Materialize is the odd sink out. Every other one that enters here — sum, a
numeric fold, a reduce with a long op — only makes sense over numbers.
`to_seq`/`to_list` are TYPE-PRESERVING and say nothing about the element, so a
seq of records reached the loop and died with `MezzeRecord cannot be cast to
Number`.

Fix: a Materialize sink is not by itself a reason to fuse. With STAGES present
the pipeline is constrained back to numbers anyway —
`tryBuildFusedLongPipeline` rejects any stage whose operand is not a long
literal — so the term now requires one, which makes it redundant with `hasStages`
and is deliberately written that way so the condition reads as the rule.

`FusedPipelineElementTypeTest` covers it, including the numeric case the fusion
exists for (still fused) and the `sorted_set` of Ints control (still works). Note
the plain chains — `size`, `fold`, `filter`, a generic element type — all passed
BEFORE the fix; only the sorted-container route reached the bad path, which is
why this survived the perf suites.

---

## BUG 9 — FIXED: two abilities with the same operation name collided

Found 2026-09-16 while assessing task C.2. **Silent wrong answer, no error.**

```mezze
ability Weigh for 'a where { rank: { it: 'a } -> Int }
ability Price for 'a where { rank: { it: 'a } -> Int }

type Item = { n: Int }
impl Weigh for Item where { rank = { it } -> it.n }
impl Price for Item where { rank = { it } -> it.n + 100 }

let both: { x: 'a } with Weigh 'a, Price 'a -> Int = { x } ->
  Weigh.rank { it = x } + Price.rank { it = x }

both { x = { n = 3 } }      # returns 6; must return 106
```

Both calls dispatched `Weigh.rank`. `Price`'s implementation never ran.

### Fixed: the CONCRETE receiver

The typechecker knew the ability per call site all along — `MethodCallInfo`
carries `abilityName`, and it is `Weigh` for one call and `Price` for the other.
Emit dropped it, leaving the compiler to re-resolve through
`tryResolveAbilityOp`, whose index is keyed on the operation NAME: both impls
target `Item`, so the receiver could not choose and the first won. Emit now
resolves the impl from the recorded ability and writes it out by hash.

Deliberately narrow — it applies only when more than one ability DECLARATION in
scope has an operation of that name. Where the name is unambiguous the existing
path is already right, and every ability method call is far too much surface to
change for a bug that needs the ambiguity to exist. The ability is matched by
declaration HASH (the part a name cannot distinguish, and the whole reason this
exists); the impl's target is matched by name, which is all
`AbilityImplInfo.targetTypes` holds and only has to separate impls within one
ability.

This is the shape **C.3** generalizes: emit the evidence, do not re-resolve.

### Also fixed: the GENERIC receiver — task C.2 landed

`MagicParams.dictKey(methodName, typeVar)` builds `"rank@a"` — method name plus
type variable, no ability. `Weigh.rank@a` and `Price.rank@a` are therefore the
same slot, and whichever is filled first answers for both. It is the same class
as BUG 1: a NAME standing in for an identity.

The per-VARIABLE half of the keying already works — one ability's `rank` on two
different type variables resolves correctly (`__dict_rank@a` vs `__dict_rank@b`),
which the second test in `DictKeyAbilityCollisionTest` pins.

### How (C.2)

`TypedASTCompiler.normalizeDictKeys` is where the two collapse:

```kotlin
raw.groupBy { methodOfKey(it) }.flatMap { (method, keys) ->
    val vars = keys.mapNotNull { typeVarOfKey(it) }.toSet()
    if (vars.size >= 2) keys.filter { … } else listOf(method)   // ← one plain slot
}
```

Traced: a function constrained `with Weigh 'a, Price 'a` raises `rank@a` from both
calls; they group under `rank`; `vars.size == 1`; the result is a single plain
`rank` slot serving both. Confirmed by probe — `dictMethodsForHash[generic]` is
exactly `[rank]`.

The grouping key has to become `(ability, method)`. The obstacle is getting the
ability THERE: the scan consults `abilityOpIndex`, which is keyed on the operation
name and by construction cannot separate them.

**Both evidence channels already exist and are both empty** — which is C.3 stated
as a fact rather than a plan:

- `TypedCall.instantiation.abilityImpls` is on the emitted node and serialized.
  Probed at the failing call sites: `[]`. And `grep '\.instantiation'` in
  `TypedASTCompiler` returns NOTHING — the compiler never reads it even when it is
  populated.
- `ConstraintResult.MethodCallInfo.abilityImpls` is the typechecker-side twin.
  Also empty. Its sibling `abilityName` IS populated and correct per call site,
  which is what the concrete-receiver fix uses.

For a GENERIC receiver the IMPL genuinely is not known at the call — it arrives
through the caller's dict — so `AbilityImplRef`, which requires an impl hash, is
the wrong shape. What has to be carried is the ABILITY. `MethodCallInfo`
already has it and the builder drops it; one optional `abilityHash` on the emitted
method-call node would carry it to the compiler, and then five coordinated reads
follow: the prescan key (`scanExprForVarTypeMethods`), `normalizeDictKeys`'
grouping, `enclosingDictSlotIn`'s match, and the two injection sites.
`methodOfKey` must keep working throughout — it feeds the op index and the
runtime's primitive fallback.

### Acceptance

`DictKeyAbilityCollisionTest` — all three pass, nothing disabled.

---

## BUG 6 — Types are RECONSTRUCTED, not carried; instances decided by shape, name, or Java

**This is the class BUG 1 and the `.size {}` crash are instances of.** Landed
work on `feat/soundness` (`1c8ce2802` on main, `8270986b2` on the branch)
fixed the visible symptoms for Eq/Ord; the master task list below removes the
class. Cross-references: BUG 1's "structural type-application hash" is task
**B.1**; the `.size {}` match-arm crash is what task **A.2**'s invariant turns
into a compile error.

### Symptom (measured 2026-09-12/13, all fixed unless marked OPEN)

- `[T] == [T]`, `[[T]] == [[T]]`, `List T == List T` ignored the element's Eq
  (structural natives, receiver dropped by the Monomorphizer's ByHash rewrite).
- A derived record/variant `==` was a Java `deepEquals`: `{ p = P(1,10) } ==
  { p = P(1,99) }` with a newtype `P` whose Eq compares only `x` answered
  `false`; same for `Some P`, `Ok P`, user `Tree`/`Box 'a`/`Pair 'a 'b`.
- A hand-written `impl Ord for Tree` with `a < b` on pattern-bound payloads
  crashed (`Cannot compare MezzeRecord < MezzeRecord`) — impl bodies were
  compiled UNTYPED, so every `==`/`<` inside fell to the structural default.
- `Result 'a 'e` could hold only ONE `__dict_eq` (dicts keyed by method NAME).
- `impl Eq` on a record that derives Eq was silently accepted as an override.
- OPEN: ~50 collection natives still compare/order elements structurally
  (`interpreter/src/test/resources/native-structural-compare-allowlist.txt`);
  `SortedSet`/`SortedMap` order every non-primitive as EQUAL
  (`SortedSetNatives.compareElements` returns 0 → a sorted set of records
  collapses to one element); `Map`/`Set` hash keys with Java `hashCode` and
  there is no `Hash` ability.

### Root cause (five diseases, one class)

1. **Types reconstructed, not carried.** The solver produces a substitution +
   side tables keyed by `Loc` (`matchCaseBindings`, `constraintResults[name]`);
   `TypedAstBuilder` re-walks the parsed AST, looks types up, and falls back to
   a fresh `unknown` VarType on every miss; impl bodies are solved by
   `ImplValidator` and the solution is thrown away. `TypedASTCompiler` then
   guesses types back from shapes: `resolveVariantReceiverType`,
   `extractConcreteElementTypeFromExpr`, `structuralElem`,
   `structuralConstraintTypes`, `bindConstraintArgsViaArg`,
   `containerElementType`, `resolveDictFromSpecTypeArgs`,
   `specBoundReceiverType`, the #13/#346 recoveries. Each covers one shape.
2. **Two representations per type.** `SeqType` vs `TypeApplication("Seq")`,
   `RecordType(Declared)` vs `TypeApplication("R")`, `VariantType` vs
   `TypeApplication("Option")`, `NewtypeType` vs its application — and (BUG 1)
   a type application whose identity is only its constructor's hash.
3. **Dictionaries keyed by method name** (`__dict_eq`, one per function); call
   sites re-resolve impls from arg types. Now per-type-var (`__dict_eq@a`) as a
   stopgap; the solver already knows the evidence and discards it.
4. **Implicit instances.** Derived Eq existed only as a boolean in the checker;
   the compiler had a parallel notion (deep-equals) and they disagreed. Fixed
   for Eq (`DerivedImplGenerator`); Inspect still implicit.
5. **Duplicated logic.** Two `canDeriveAbility`, three `substituteTypeParams`,
   several unifiers; mono keys specializations on laundered surface types.

### What is landed (on main `1c8ce2802` + branch `8270986b2`)

- Coherence: `impl Eq` on a deriving record/variant → V0407 duplicate; the
  stdlib's redundant `impl Eq for Option/Result` removed.
- Derived Eq is a compiler-GENERATED impl (`typechecker/derive/
  DerivedImplGenerator.kt`, `ContextBuilder.deriveAbilityImpls`, anonymous
  shapes via `AbilityChecker.registerDerivedShapeImpl`), with explicit
  `with Eq 'a` so a newtype without Eq poisons every containing shape at the
  use site (`DerivedEqRequiresFieldEqTest`).
- All ability-impl op bodies are typed (params from the ability's declared
  signature, structural match-binding typing) except codec-flavored ones.
- Per-type-var dict slots (`MagicParams.dictKey`), instance-head binding at
  call sites and inside mono specs; `TypedTypeDecl.typeParams` populated (was
  always empty); mono no longer picks an impl for an unbound constraint var.
- `NativeEvidenceGateTest`: ratchet — a collection native that compares/orders/
  hashes elements structurally beyond the allowlist fails the build.
- Tests: `SeqUserTypeCompareTest` (52), `UserContainerInstanceTest` (14),
  `DerivedEqRequiresFieldEqTest` (9), fixtures moved to newtypes.

### Master task list

Track 1 — element instances flow everywhere (~2 weeks)

- **T1.1** DONE — ratchet gate + user-container tests + typed impl bodies.
- **T1.2** Thread evidence through the remaining structural natives until the
  allowlist is empty (List/Deque/NEL/Seq: contains, index_of, last_index_of,
  min_by/max_by, sort_by_key, is_sorted*, binary_search/lower/upper_bound,
  dedup/distinct; Map/Set: min/max key/value, min_by/max_by). Pattern = Seq's
  post-#23 shape (`stdlib/std/collections/seq.mz`: `*_with_cmp`/`*_with_eq`
  helpers with a declared comparator param, `with Eq/Ord 'a` on the pub let).
  Delete the 24 dead natives (`__native_list_min/max/sort/is_sorted*`, …).
- **T1.3** Sorted containers CARRY their comparator from construction
  (`add`/`from_*`/`singleton` already have `with Ord 'a`; store the Mezze cmp
  in `MezzeSortedSet`/`MezzeSortedMap`, use it for every later op; delete
  `compareElements`).
- **T1.4** `Hash` ability (`std::abilities::hash`, derived structurally like
  Eq; newtypes derive nothing → OWNER DECISION: `newtype X = T deriving Eq,
  Ord, Hash` opt-in); `Map 'k`/`Set 'a` require `Hash + Eq`; natives take
  hash+eq, structural fast path only when provable from the type.
- **T1.5** Combinatorial gate: shapes {record, variant, newtype, Option,
  Result, anon record, user Tree/Box} × containers {seq, list, map, set,
  sorted*, deque, NEL} × abilities {Eq, Ord, Inspect} × ops, asserting the
  instance is honoured.

Track 2 — elaborate once (~7–9 weeks, each phase mergeable behind a flag)

- **0.1** RE-SCOPED by measurement (2026-09-15). The stated motivation — that
  side tables keyed by `Loc` lose entries when two nodes share a location —
  **does not hold today**. Across 21,389 stdlib expression nodes, no location is
  used by more than one node (`LocKeyCollisionMeasurementTest`, kept as a
  ratchet); and with temporary instrumentation in the constraint accumulator
  over 67 stdlib modules, no side-table write ever replaced an entry with a
  DIFFERENT value. The 983 gaps 0.2 found are therefore NOT Loc collisions —
  they are the solver having no entry to record in the first place. `NodeId` is
  still worth having as the identity A.2's `ElaborationError` names, but it is
  not producing wrong types today, and a 60-site key-type refactor ahead of
  **A.1** (where 566 of the findings live) would be effort spent off the
  critical path. Near miss worth knowing: `ParsedIf.toMatchExpr` gives the
  synthesized match AND both of its cases the same `loc` as the original `if` —
  harmless only because those cases bind nothing.
- **0.2** DONE — `ElaborationInvariants` + a ratchet in both gates.
  Measured over the typed stdlib, and corrected twice since: **689** declarations
  with an unbound type variable, **193** with an untyped parameter, **3** reading
  a field off a receiver with no type (baseline:
  `typechecker/src/test/resources/elaboration-invariant-baseline.txt`). Almost
  all of it is in impl operations, which is what A.1 changes. The 24 include
  `impl NumAdd Int for Float { add = { it, r } -> it + r.to_float {} }` —
  `r.to_float` dispatching on something the compiler cannot name.
  Two corrections to the CHECK itself, both of which mattered: a lambda's own
  signature binds for its body (without that it flagged every generic function in
  the stdlib, since a scheme is normalized to `'a` while its body carries the
  solver's `'m802`), and a var-typed PARAMETER is only a gap when the variable is
  bound nowhere — `pair_up: { a: 'a, b: 'a }` is correct code, and treating it as
  untyped accounted for 79 of the original 272.
  `UserCodeElaborationGateTest` asks the same of ordinary programs and states
  each one's gaps inline: a match-arm-bound seq still types as literal
  `unknown` at `.size {}`, a fully annotated polymorphic helper's parameters
  are still bare fresh variables, and one chained call with an inline lambda
  leaves seven nodes untyped.
- **0.3** DONE — `HeuristicDeletionAcceptanceTest`: one test per heuristic,
  named after it, each running a program whose ANSWER depends on the right
  instance (a newtype whose `eq` compares mod 10, so a structural fallback
  answers differently rather than quietly working). A control proves the
  fixture discriminates. A.4 deletes a heuristic and runs the test named after
  it, instead of deleting and hoping.
- **A.1** LANDED (partly; the rest is blocked on A.3 and B.1).
  `ImplValidator.validateImplOperationBody` always ran a full solve of every impl
  operation and then discarded it; that solve is now kept (`ImplOpSolution`) and
  merged into the module's side tables and substitution. Gated by
  `ElaborationFlags.carryImplOpSolves`, default ON, `MEZZE_ELAB=0` to disable.
  **Result: VARTYPE_RECEIVER 24 → 3.** Every Char/Bytes/SortedMap/SortedSet
  comparison op and `impl NumAdd Int for Float`'s `r.to_float {}` now elaborate
  with a known receiver.
  **`matchCaseBindings` is excluded from the merge**, and the reason is measured,
  not assumed: bisecting the merge, every other table together passes and that
  one alone fails `JsonDecodeExhaustiveTest` + `UserDecodeAbilityTest` with a
  CallIndirect on a record. The codec decode bodies depend on their match
  bindings arriving as FRESH VARIABLES — supplying the real type, even only
  where the structural read had nothing, routes the decoder wiring differently.
  That dependency on an absence is what **A.3** removes, and the exclusion comes
  out with it. The isolated solve also returns some types in the OTHER
  representation (`TypeApplication("Nat")` vs the declared nominal `Nat`), which
  is disease 2 and **B.1**'s job. So A.1's remainder is sequenced behind A.3 and
  B.1, not behind C.3.
- **A.2** Builder reads by NodeId; a miss is an `ElaborationError`, never a
  fresh var; drive misses to zero across stdlib + both gates. (Turns the
  `.size {}` match-arm crash into a compile error.)
- **A.3** RE-STATED from evidence. Codec impls ARE already generated into the
  module (`TypedModuleEmitter` → `codecGen.generateEncodeImpl/generateDecodeImpl`
  → `typed.copy(abilityImpls = …)`), but at EMIT time, whereas derived impls are
  generated into the RESOLVED module before the solve
  (`ContextBuilder.deriveAbilityImpls`). Moving codec generation to match is
  chicken-and-egg, and that is the actual work: `from_json` returns
  `Result 'a DecodeError` where `'a` is inferred purely from USAGE, and
  `DecodeTargetResolver`'s own header states the target "NEVER lands on the call
  node or in the solve substitution: it must stay a bare var there, or the
  `Decode` ability check rejects it and codegen reads a record where the input is
  a string." So the solve cannot see the codec impls, because the target the
  impls are generated FOR is only known after the solve. A.3 therefore requires
  making `from_json`'s target inferable INSIDE the constraint system — a design
  change, not a move — after which `DecodeTargetResolver` (423 lines of
  structural recovery from field accesses on the callback binder) and A.1's
  `matchCaseBindings` exclusion both come out.
  Demonstrated by A.1: supplying those bodies ANY binding type — ground,
  declared-shape, or only where nothing existed — breaks `JsonDecodeExhaustiveTest`
  and `UserDecodeAbilityTest`. The codec path depends on not knowing.
- **A.4** Delete the heuristics from 0.3, one per gated test.
- **B.1** One canonical type form: builtin containers as `TypeApplication`
  (dedicated nodes deleted = backlog #315); declared types nominal with
  identity the sole carrier, opened only through one `open(type, registry)`;
  **type-application identity = `hash(constructor ⊕ args)`** (BUG 1's fix).
- **B.2 / B.3** MEASURED; B.1's direction confirmed, the dual-arm code is
  smaller than assumed. `TypeRepresentationAuditTest` counts both forms across
  the typed stdlib: **5,722 name-carried `TypeApplication("Seq", …)` against
  1,546 dedicated nodes**, with **158 declarations carrying both** — mostly the
  container CONVERSION functions (`to_list`/`from_list`/…), which mention two
  container types and get them in different forms. So the dedicated nodes are
  the minority in what actually flows and B.1's "delete them" is the right
  direction, even though the SOURCE has 247 dedicated-node sites against 44
  TypeApplication constructions.
  A scan for one-sided consumers (functions branching on ≥2 dedicated container
  nodes with no TypeApplication arm) found only **two**: `builtinConstructorName`,
  which is one-sided by design, and `TypedAstBuilder.patternBindingTypes`, whose
  seq-pattern arm read the element type from `SeqType`/`ListType` only. Fixed —
  but honestly, it is HARDENING, not a bug fix: six programs covering every route
  to that code (impl target, record field, variant payload) all passed before the
  fix, because `[P]` parses straight to a dedicated node. `SeqPatternInImplBodyTest`
  keeps them as forward protection for when B.1 moves these types around.
- **C.1** Solver records per constraint per node
  `Evidence = Impl(hash, sub) | Param(abilityHash, typeVar) | Structural(type)`.
- **C.2** DONE for the identity half. Dict keys are now `(ability, method, var)`:
  `MagicParams.dictKey` takes an optional ability segment (`rank@a#3f9c1b`),
  `normalizeDictKeys` groups by `(ability, method)` instead of method alone,
  `enclosingDictSlotIn` looks up ability-qualified slots first and — when the
  caller asked for one — ONLY (falling back to the plain slot would hand over the
  other ability's dictionary, which is the bug), and both injection sites plus
  `resolveOpHashFromConcreteTypes`/`tryResolveAbilityOp` confine resolution to the
  key's ability. `TypedFieldAccess.abilityHash` carries the ability from emit,
  set only where the operation name is ambiguous, so unambiguous calls keep
  byte-identical keys. **BUG 9 fixed end to end** — 106, concrete and generic.
  Still open in C.2: replacing the prescan itself with declared constraints
  (`C.5`).
- **C.3** Compiler EMITS evidence (`refToDictedOp` = materialize); no
  re-resolution at call sites. **C.4** Mono keyed on (generic hash, evidence at
  site). **C.5** Delete prescan, `refToDictedOp` guessing, `@var` names.
- **D.3** INVESTIGATED; no change made, and that is the finding. Inherent impl
  operations are excluded from parameter seeding (`!isInherent` in
  `TypedAstBuilder.buildAbilityImpl`) because they have no ability declaration to
  read a signature from — the same asymmetry that produced BUG 7. But the
  receiver's type IS known there: the builder already constructs the op's context
  type as `{ it: <target> } -> <solved return>`. I added seeding from that source,
  confirmed it fires **762 times** across the stdlib supplying real types
  (`it=TypeApplication`, `key=StringType`, `f=FunctionType`), and could not make
  any program behave differently with it on or off — including the shapes it
  should most affect, a component `==` inside an inherent op. Reverted: an
  unjustified change to impl-op body typing is not worth carrying on the path
  whose last such change caused a StackOverflow (the newtype-seeding incident).
  A.1's merged impl-op solves are the likely reason those cases already work.
  `InherentImplBodyTypedTest` keeps the shapes as coverage. **D.3's remaining
  value needs a case that FAILS first** — the untyped-parameter COUNT cannot
  supply one, because for a seeded op the emitted parameter type is deliberately
  a laundered fresh variable (mono specializes on it), so that metric partly
  measures a design choice rather than a gap.
- **D.1** PRECURSOR LANDED; the change itself is sized and not started.
  Inspect has no generated impl: it is an implicit "everything derives it" in the
  typechecker plus a deep-printer in the runtime, and the two agree only because
  the printer never refuses — a record holding a function renders
  `{ name = "d", run = <function:lambda> }`, which no field-by-field derivation
  produces. **617 assertion sites across 110 test files depend on the exact
  strings**, so replacing the printer without knowing what it emits is a 617-site
  guess. `InspectRenderingGoldenTest` + `inspect-rendering-golden.txt` record the
  rendering of 33 shapes, which turns D.1 into "reproduce this file" — mechanical
  and verifiable — and catches any accidental change to user-visible output as
  one readable diff instead of scattered failures. Facts worth knowing from it:
  the three container kinds print with three different bracket styles
  (`List(1, 2)`, `Set{1, 2}`, `Deque[1, 2]`), a newtype prints through its
  constructor (`Money.(5)`), and `<function:lambda>` is the case with no
  derivable equivalent. **D.2** Structural fast path from a PROVEN
  property of the evidence tree + perf gate. **D.3** Test typed hand-written
  bodies (`impl Eq for N { it.q == r.q }`).
- **F** PARTLY LANDED.
  *`canDeriveAbility` — done, and it was hiding a divergence.* `TypeValidator`'s
  copy had the same decision arms as `AbilityChecker`'s but no cycle guard, no
  record expansion, and **no newtype rule** — and it gates the
  unsatisfied-ability-constraint check, so a newtype with no `impl Eq` used where
  Eq is required was silently ACCEPTED. Both now call
  `derive/DerivabilityRules.canDeriveAbilityStructurally`; only the
  component-satisfied test stays per-caller, because one consults registered
  impls and the other matches target annotations in scope.
  `DeriveRuleAgreementTest` pins the newtype case in both directions.
  *`substituteTypeParams` — done.* The two differed in OPPOSITE directions: the
  `ImplValidator` copy rebuilt a `TypeApplication` through `WellKnownTypeApp.of`
  so a well-known constructor regains its identity hash, the `Unification` one
  tolerated the `'` prefix on lookup. The survivor does both.
  *One unifier — RE-STATED.* `MonoTypeOps.unify` and `Unification.unify` are NOT
  duplicates: one is a one-way pattern MATCHER with deliberate mono-specific
  asymmetries (refuse to bind a free value var; symmetric free-var acceptance;
  distinct-declared-record veto), the other is the solver's two-way unifier.
  Merging them would be wrong. The real duplication is **`typeMatchesTarget`**,
  which exists twice (`MonoTypeOps.kt:63`, `TypedASTCompiler.kt:4285`) with
  overlapping prim/identity/TypeApplication arms — the mono copy's own comment
  says it "must agree with the compiler's typeMatchesTarget", i.e. the coupling
  is already known and maintained by hand. They are not mergeable by union: the
  compiler's extra arms (Bool-variant normalisation, VariantType/TypeApplication
  alias recovery for a stripped identity) exist for specific fixed bugs, and
  giving them to mono would make it match more eagerly and seed wrong
  specializations. Needs its own pass with targeted tests per arm.

Guarantee after Track 2: every ability use is resolved to one instance or a
dictionary parameter AT ELABORATION TIME, the compiler only reads evidence, a
gap is a compile error, and the runtime never decides equality/ordering/hashing
itself (the ratchet). Not covered: lawfulness of user instances (an `Eq`
inconsistent with `Hash`), and performance — hence D.2's proof, not a guess.

Gates for every change: `:typechecker:test` and `:interpreter:test` SEPARATELY,
`--no-daemon`; stdlib type changes need `:cli:clean :cli:compileStdlib`.
