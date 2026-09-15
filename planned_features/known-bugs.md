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

## BUG 1 — Ability dispatch keyed on a MANGLED NAME, not structural identity

**This is the big one.** The whole `Result <Lang>Foreign InteropError` dot-chain
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

## BUG 2 — Codec `.to_py {}` / `.to_js {}` do not work for containers (lists, maps, records)

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

## BUG 3 — An effect performed but never used is silently accepted

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

## BUG 4 — Production runs print JVM/plugin log noise

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

## BUG 5 — `installNative` does not update the toolchain proxy default (context for BUG 1c)

`./gradlew :cli:installNative` builds a correct native binary and installs it as a
NEW toolchain, but the `~/.mezze/bin/mez` proxy keeps resolving the OLD global
default, so `mez` (and any LSP launched via it) runs stale code until
`mez toolchain default <new-version>` is run manually. It also stamps two
mismatched versions per install run. This is why BUG 1c (LSP shows T0206 while the
CLI at the same HEAD works) is most likely a stale-toolchain artifact rather than
a live dispatch bug. Fix: `installNative` should set the freshly-installed
toolchain as the default (and stamp one consistent version).

---

## Pre-existing dispatch bug (folded from deleted `docs/bugs/`)

### `.size {}` / `.is_empty {}` on a match-arm-bound seq crashes in CallIndirect

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

Gates for every change: `:typechecker:test` and `:interpreter:test` run
SEPARATELY with `--no-daemon` (`feedback_gate_run_suites_separately`). Strip debug
prints before the full suite (`feedback_no_debug_prints_in_full_suite`). No type
annotation may ever be required to typecheck — if adding one "fixes" a chain, that
is an inference bug (`feedback_no_required_type_annotations`).
