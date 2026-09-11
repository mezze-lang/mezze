# Version governance: toolchain, store schema, and IR

Mezze has **three** independent version numbers. Confusing them is how you ship a
toolchain that silently loads incompatible cached or distributed IR. This is the
maintainer's rule sheet: what each one guards, and exactly when to bump it.

| Version | Lives in | Guards | Audience |
|---|---|---|---|
| **Toolchain version** | `build.gradle.kts` `mezzeVersion` → `BuildInfo.VERSION` | a release of the whole `mez` (CLI + compiler + stdlib) | users (`mez toolchain`, pins) |
| **Store schema** | `SQLiteMezzeStore.SCHEMA_VERSION` | the on-disk shape of `.mez/store/*.sqlite` | internal cache only |
| **IR version** | *(to introduce with #45/#46)* `IrVersion` | the shape + semantics of compiled/lowered IR and interfaces shipped between projects | packages (compat check) |

They are deliberately separate because they change for different reasons and have
different blast radii.

## 1. Toolchain version — `BuildInfo.VERSION`

The user-facing release identity. `nightly-YYYYMMDDHHMM` by default, `-Pversion=X.Y.Z`
for a release. It is:

- what `~/.mezze/toolchains/<version>/` is keyed by, and what `mez toolchain
  pin/default/+ver` selects (ToolchainResolver);
- the **binary identity** (`VERSION+GIT_SHA`) stamped into a project's
  `.mez/store` `mez_version` metadata. On a mismatch, `ProjectStore.open`
  (every compile path) wipes the typecheck/IR cache and recompiles — so a project
  never reuses another toolchain's IR (#44).

**Bump:** every release. Never reuse a version for different bytes — the whole
cache-invalidation + pin model assumes `VERSION+GIT_SHA` uniquely identifies a
compiler. (`nightly-*` embeds a timestamp; releases get an explicit `-Pversion`.)

## 2. Store schema — `SCHEMA_VERSION`

The physical table layout of the SQLite stores. On open, a differing recorded
schema triggers a full rebuild (`SQLiteMezzeStore`, currently `= 10`). Purely an
internal cache-format guard; never distributed.

**Bump when:** you add/rename/drop a column or table, change a serialization
encoding in a blob column, or otherwise make an old DB unreadable by new code.
Do **not** bump it for compiler-semantics changes that leave the table shape
intact — that is what IR version is for (and `VERSION+GIT_SHA` already forces a
per-project rebuild anyway).

## 3. IR version — `IrVersion` (to introduce)

The compatibility key for **distributed** compiled artifacts. The never-fail model
(`content-addressed-model.md`) ships, per definition: compiled IR + interface +
source, stamped with an IR version. A consumer decides, without running the
producer's toolchain:

```
load the shipped compiled IR   iff   pkg.irVersion == our IrVersion
otherwise                             recompile from the shipped source,
                                      keyed/memoized on (sourceHash, our VERSION+GIT_SHA)
```

Exact-match is the **conservative, always-correct** policy: never load compiled IR
from a different IR version; fall back to source. It is strictly a performance vs
safety trade — a wider compat window (accept a *range* of IR versions) is an
optimization to add only with evidence the shapes are truly compatible, never by
default.

**Bump when:** you change the shape or meaning of anything that crosses a package
boundary in compiled form — the lowered IR node set/encoding, the interface
format, or hashing that affects the hash-closure compat check. A pure
optimization that produces byte-identical IR does **not** bump it.

**Relationship to the others:** IR version changes far less often than
`BuildInfo.VERSION` (every nightly bumps the toolchain; most nightlies don't touch
IR shape). That's the point — pinning IR compat to the toolchain version would
force every consumer to recompile every dep on every nightly. A stable `IrVersion`
lets a compiled dep from last week load unchanged this week, while a real IR change
cleanly forces recomp-from-source.

## Compatibility matrix (exact-match policy)

| Consumer `IrVersion` | Package shipped with | Result |
|---|---|---|
| `k` | `k` | load compiled IR (fast path) |
| `k` | `j ≠ k` | ignore compiled IR → recompile from shipped source |
| `k` | *(source only, no IR)* | compile from source, memoize under `(sourceHash, VERSION+GIT_SHA)` |
| `k` | *(IR only, source missing, `j ≠ k`)* | **hard error** — cannot load, nothing to fall back to |

The last row is the one invariant to enforce at publish time: **a package must
always ship source** (or IR that is guaranteed loadable), so a consumer can never
be stranded. "Never-fail" is a property of the *publisher's* obligation to include
source, plus the *consumer's* deterministic recompile.

## Where this bites if ignored

- Bump `BuildInfo.VERSION` but not the bytes → two different compilers share a
  project cache → stale IR at runtime (`expected MezzeFunction got null`).
- Change IR shape but not `IrVersion` → a consumer loads last week's compiled dep
  against this week's incompatible node set.
- Bump `IrVersion` on every optimization → consumers pointlessly recompile every
  dep constantly.

## Status

- Toolchain version + `VERSION+GIT_SHA` cache invalidation: **shipped** (#44).
- `SCHEMA_VERSION`: **shipped**, currently 10.
- `IrVersion` + shipped compiled-IR/interface/source + compat check + source
  fallback: **planned** (#45/#46), governed by the rules above.
