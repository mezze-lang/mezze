# Plan: ship compiled IR (#45) + memoized compile-from-source (#46)

This is the execution plan for the two remaining never-fail-model tasks. It is
written against the code as it stands so a focused session can implement it
without re-discovering the architecture.

## What already holds (the correctness baseline — do not regress)

The "never-fail" *guarantee* is already met, and verified end-to-end against a
real GitHub dependency:

- A package ships its **source** (`.mz`) in its tarball.
- `mez sync` materializes each dep at `.deps/mezze/<name>-<ver>/` (symlink into
  the content store for `url#sha256` deps).
- The consumer's `ModuleResolver` finds the dep's source there and compiles it as
  part of the consumer's own compile (typecheck → lower).
- The result is memoized in the consumer's `.mez/store` (content-hash keyed), and
  invalidated on a compiler-version shift by `ProjectStore.open` (#44).

So a consumer **always** resolves a dep: reuse the cached IR, else recompile
deterministically. #45/#46 make that *faster* and enable *closed-source*
distribution — they must not weaken the guarantee above.

## What's missing

1. **#45 — ship compiled IR + interface, stamped with `IrVersion`.** Today
   `deps.sqlite` is created empty (`SQLiteMezzeStore.openProject` step 3) and never
   populated; deps are always compiled from source. We want a package to *also*
   carry its lowered IR + public interface, so a consumer on a matching
   `IrVersion` loads it and skips compilation.
2. **#46 — memoize `compile(defHash, format)` globally.** The `.mez/store` memo is
   per-project; the same dep is recompiled once per consuming project. A memo in
   the shared content-store entry makes it once-ever-per-compiler.

## The template already in the tree

`StdlibPrecompiler` (cli) is exactly #45 for stdlib: it lowers all stdlib modules
to `FunctionIR` and writes `stdlib.sqlite` + a CBOR snapshot via
`StoreSerialization`, which `openProject` ATTACHes AS `stdlib`. `deps.sqlite` is
already ATTACHed AS `deps` by the same code. So the store, the serialization
format, and the attach-and-resolve path all exist — this is a "populate deps.sqlite
like we populate stdlib.sqlite" job, not new infrastructure.

## Implementation steps

### A. Introduce `IrVersion` (foundation, from versioning-governance.md)
- Add `const val IR_VERSION` next to the IR definitions (interpreter/truffleir or
  store). Bump rules: any change to lowered-IR shape/encoding, interface format, or
  the hash-closure inputs. See `versioning-governance.md`.
- Stamp it into any shipped compiled artifact and into `Mezze.lock` nodes.

### B. Precompile a package's IR at publish time (#45, producer side)
- Add a `DepsPrecompiler` mirroring `StdlibPrecompiler`: given a package's source,
  lower it and write `<pkg>.sqlite` (compiled IR) + an interface table, stamped
  `IR_VERSION`. Ship it in the tarball next to `src/`.
- `mez build`/a `mez package` step produces it. Source stays in the tarball
  (the publish-time invariant: always ship source).

### C. Load compiled IR when compatible (#45, consumer side)
- In `mez sync` / at store-open, for each dep: if it carries a `<pkg>.sqlite` whose
  stamped `IR_VERSION` == ours AND the hash-closure check passes, copy/merge it
  into `deps.sqlite` (or ATTACH it) so the resolver finds compiled IR and skips
  source compilation.
- Else fall back to the current source path (B/C absent ⇒ exactly today's behavior).
  This is the exact-match policy from the governance doc; a wider window is a later
  optimization.

### D. Global compile memo (#46)
- When a dep is compiled from source (no compatible shipped IR), write the result
  into the content-store entry at `~/.mezze/store/<sha>/.compiled/<IR_VERSION>/`
  keyed by `(defHash, IR_VERSION)`. Next consumer with the same compiler reuses it
  instead of recompiling.
- This is the cross-project version of the per-project `.mez/store` memo; the
  content store is already shared, so it only needs a compiled sub-namespace.

### E. Hash-closure compatibility check
- Before loading shipped IR, verify the dep's referenced hashes resolve in the
  consumer's world (stdlib + transitive deps). A mismatch ⇒ recompile from source.
  This is what makes cross-toolchain IR loads safe rather than a coin flip.

## Test plan
- Producer: `DepsPrecompiler` output round-trips through `StoreSerialization`
  (mirror the stdlib precompile tests).
- Consumer fast path: a dep shipping matching-`IR_VERSION` IR is loaded without a
  source compile (assert no recompile; e.g. a compile counter or timing).
- Fallback: an `IR_VERSION` mismatch (or missing IR) recompiles from source and
  still produces a working `check`/`exec` — the never-fail guarantee.
- Global memo: two projects depending on the same `url#sha256` dep compile it once
  (second sees the content-store `.compiled/<IR_VERSION>/` hit).

## Risk
The correctness baseline works and is verified; every step above is *additive*
with a source fallback, so a bug degrades to "recompiled from source" (slow), never
to "wrong IR" — provided the hash-closure check (E) gates every compiled-IR load.
