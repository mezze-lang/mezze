# Distributing Mezze

Design notes for how people install and version the language. Status: **planning**
(nothing here is built yet). Written 2026-09.

## Goal

Make `mez` trivial to get and painless to manage:

- **Install** in one line (`curl | sh`) with **no prerequisites** — not even Java.
- **First-class Nix** — a real `flake.nix` and clean devenv use.
- **Multiple versions side by side** on one machine (like `rustup`, `ghcup`).
- **Per-project version selection** — a project pins its toolchain; a one-off can
  override; there's a sane global default.

Verdict on the overall shape: **it's the right model** — this is exactly what
rustup / ghcup / deno do, and it fits Mezze's content-addressed, per-project
`.mez/` store well. The only risk is scope: **don't build the whole version
manager for v0.1.** Phase it (see [Phasing](#phasing)).

---

## Current state (what exists today)

- **Install = build from source.** `scripts/install.sh` runs `./gradlew
  :cli:installNative`, which builds the native binary + a runtime jar and copies
  them into `~/.mezze/`, then wires PATH. Needs GraalVM, ~3 min. No prebuilt
  binaries anywhere.
- **Install layout:**
  - `~/.mezze/bin/mez` — native `mez` binary (AOT, ~80 MB): the CLI **front-end**
    (parse / typecheck / orchestrate).
  - `~/.mezze/lib/mezze-runtime.jar` — the runtime.
  - `~/.mezze/lib/tools/` — Truffle dev-tool jars (~20 MB).
  - `~/.mezze/stdlib/` — stdlib `.mz` sources (for go-to-definition).
- **Program execution needs a JVM — TODAY.** `mez exec` / `mez dev` / `mez build`
  don't run the program in the native binary. `WireRunner` **spawns a separate
  `java` child** running `com.mezze.interpreter.standalone.RuntimeServer` and
  talks to it over the wire protocol (#220). `JvmLauncher` finds `java` via
  `MEZZE_JAVA_HOME` → `JAVA_HOME` → PATH → sdkman/homebrew; `EnvironmentCheck`
  tells the user to install Java if it's missing. **This is the single biggest
  blocker to a no-prereq install** (see [Runtime decision](#the-runtime-decision)).
- **Versioning already half-exists.** `build.gradle.kts` stamps
  `nightly-YYYYMMDDHHMM` by default, overridable with `-Pversion=0.1.0`. No git
  tag → release flow yet.
- **`Mezze.toml`** has `[package]`, `[[app]]`, `[dependencies]` — but **no
  toolchain / language-version field**.
- **Nix:** `devenv` only (`devenv.yaml` inputs). **No first-class `flake.nix`.**
- **CI:** only `tree-sitter-parity.yml`. No build/test/release CI.

---

## Target architecture (rustup-style)

```
~/.mezze/
  bin/mez                     # tiny PROXY on PATH — resolves version, execs the toolchain
  toolchains/
    0.1.0/
      bin/mez                 # the real front-end binary for 0.1.0
      bin/mez-runtime         # self-contained native runtime (SVM JIT) — see runtime decision
      lib/…                   # (optional) JVM runtime jar for the `mez build` deploy path
      stdlib/…                # versioned stdlib
    0.2.0/…
    nightly-2026…/…
  settings.toml               # default channel, telemetry off, etc.
  downloads/                  # tarball cache
```

**Version resolution** (the proxy does this every invocation, first match wins):

1. Explicit one-off: `mez +0.1.3 build`   (like `cargo +nightly`)
2. Env: `MEZ_VERSION=0.1.3`                (CI, scripts)
3. Project pin: nearest `Mezze.toml` `[toolchain] channel` (walk up from cwd)
4. Global default: `~/.mezze/settings.toml`
5. Fallback: latest installed, else a friendly "run `mez toolchain install`".

**Management folded into `mez`** (one tool, not a separate `mezup`):

```
mez toolchain install <ver|latest|nightly>
mez toolchain list [--installed]
mez toolchain default <ver>
mez toolchain uninstall <ver>
mez self update            # update the proxy itself
```

**Per-project pin** in `Mezze.toml`:

```toml
[toolchain]
channel = "0.1"      # a track ("0.1", "nightly") or a pin ("0.1.3")
```

Refinement: keep `channel` (which *binary* runs) separate from a future `edition`
(a language-semantics epoch, à la Rust editions). Don't conflate them now — v0.1
only needs `channel`.

---

## Install channels (all first-class)

1. **`curl -fsSL https://mezze-lang.org/install | sh`** — the default for most
   users. Installs the proxy + downloads the latest **prebuilt** toolchain tarball
   for the detected OS/arch from GitHub Releases (mirrored to the existing R2
   bucket). Reads a `versions.json` manifest. Never compiles anything.
2. **`flake.nix`** — exposes `packages.<system>.mez`, `apps.mez`
   (`nix run github:shrynx/funk-lang`), an `overlay`, and a `devShell`. A Mezze
   *project* can pin a language version through the flake / devenv. Built with the
   `graalvm-ce` already in `devenv.nix` via `:cli:nativeCompile`.
3. **devenv** — stays the contributor path (this repo) and a per-project dev path.

---

## Builds: development vs release

- **Development** (unchanged): `just build` (installDist jar launcher), `just
  native` (`:cli:nativeCompile`), `mez dev` for fast-startup runs. Contributors
  never touch the release pipeline.
- **v0.1 release:** a **GitHub Actions matrix** — native-image can't
  cross-compile, so one runner per target:
  - `macos-14` (arm64), `macos-13` (x64), `ubuntu-latest` (x64), `ubuntu-24.04-arm` (arm64)
  - each: `./gradlew -Pversion=0.1.0 :cli:nativeCompile` (+ runtime), package a
    tarball, attach to a tagged Release, update `versions.json`.
  - source of truth: **git tag `v0.1.0`** → `-Pversion` → Release.

---

## The runtime decision

This is the one that gates "simple install." Program execution today = a spawned
**JVM** child (`RuntimeServer` over the wire protocol). Three ways to remove the
Java prerequisite:

- **(A) Native runtime child (recommended).** Ship `mez-runtime` as a
  self-contained **native** binary with the **SVM JIT** (#402 already shows native
  SVM-JIT beating the JVM 28×). `mez exec/dev/check/run` spawn / embed *that*, not
  `java`. Zero external deps for the common path. The wire-protocol split (#220)
  already decouples front-end from runtime, so this is "point WireRunner at a
  native RuntimeServer" rather than a rewrite. `mez-shell-native` is likely the
  seed of this.
- **(B) Bundle a trimmed JRE** in `toolchains/<v>/jre/`, point `MEZZE_JAVA_HOME`
  at it. Simple, but +40–60 MB per toolchain and keeps the JVM in the hot path.
- **(C) Require system Java.** Rejected for the default install — breaks the
  one-line promise.

**Recommendation:** (A) for `run / dev / exec / check` → no Java, ever. Keep the
**JVM jar `mez build`** (JIT, warm perf, deploys as a polyglot jar) as an
*optional* path that requires or bundles Java — it's a power-user/deploy artifact,
so a Java dependency there is acceptable. `mez build --native` continues to need
GraalVM at build time (document the Docker path, like the WASM build).

Action item before committing to (A): confirm the native SVM-JIT `RuntimeServer`
runs the full stdlib + effects correctly and how far it is from shippable.

---

## Phasing

**v0.1 — ship an installable language:**
1. Release CI matrix → prebuilt per-platform tarballs on a tagged Release + `versions.json`.
2. `curl | sh` installer that drops a **single** version into
   `~/.mezze/toolchains/0.1.0/`, `~/.mezze/bin/mez` a symlink for now.
3. First-class `flake.nix`.
4. Resolve the runtime decision so the tarball has **no Java prerequisite** for
   the common path.
   *Lay the `toolchains/<v>/` dir from day one so it's forward-compatible.*

**v0.2 — the manager:**
5. Replace the symlink with the real **proxy** (version resolution).
6. `mez toolchain …`, `mez self update`.
7. `Mezze.toml [toolchain] channel` + `mez +ver` + `MEZ_VERSION`.

---

## Open decisions

1. **Runtime:** native SVM-JIT runtime child (A) vs bundled JRE (B). How close is
   the native `RuntimeServer` to running everything? (Blocks the no-Java promise.)
2. **Proxy implementation:** shell script first (ships day one) → tiny static
   binary (Zig/Rust/Go) later. A native-image proxy would be ~80 MB — too big.
3. **Version manifest & channels:** `versions.json` shape; do we ship `stable` +
   `nightly` tracks, or only pinned versions, at v0.1?
4. **Disk:** ~80 MB × N toolchains. Acceptable (rust is similar) but worth a
   `mez toolchain gc`.
5. **`edition` vs `channel`:** confirm we reserve `edition` for language epochs and
   only ship `channel` now.
