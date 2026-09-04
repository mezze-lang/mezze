# Mezze

**A functional language with first-class effects, running on GraalVM.**

Write what a function *does* — the types keep up. Every function tells you what it
touches (files, network, failure, concurrency), and you never annotate one. Full
type safety, the performance of GraalVM, and the same program can ship as a native
binary or a polyglot JVM jar.

**[▶ Try it in your browser — mezze-lang.org](https://mezze-lang.org)** · nothing to install.

---

## A taste

Effects are first-class. A function's type says *what* it needs, never *which*
implementation answers — so the caller swaps a real client for a mock without
touching the function:

```mezze
use std::effects::log::{ Console, println }
use std::effects::http::{ HttpClient, MockHttpClient, mock_get, request }

# weather says <Http> in its type. It never says which client answers,
# so the caller below picks that, not weather.
let weather = { url } -> request { url }.send {}.text_or { def = "offline" }

let main = {} -> perform Console in do
  let url = "https://wttr.in/Amsterdam?format=3"

  # The real client — goes over the network.
  perform HttpClient in println { msg = weather { url } }

  # Same function, same url, no network at all.
  let rules = [mock_get { body = "Amsterdam: Always raining", url = "*" }]
  perform MockHttpClient { rules } in println { msg = weather { url } }
```

Pattern matching is exhaustive — miss a case and it will not compile:

```mezze
type Status = | Queued, Shipped { days: Int }, Cancelled { why: Str } |

let describe = { order } -> match order.status is
  Queued               -> "$(order.id) waiting"
  Shipped { days = 0 } -> "$(order.id) arrives today"
  Shipped { days }     -> "$(order.id) arrives in $(days.to_str {}) days"
  Cancelled { why }    -> "$(order.id) cancelled: $(why)"
```

Abilities give shared behaviour to your own types — one method, any type:

```mezze
ability Summary for 'a where { summarize: { it: 'a } -> Str }

type Post = { user: Str, body: Str }
impl Summary for Post where { summarize = { it } -> "@$(it.user): $(it.body)" }
```

More — async & structured concurrency, method chaining, scoped exceptions — at
**[mezze-lang.org/taste](https://mezze-lang.org/taste)**, all runnable in the page.

---

## Why "Mezze"?

Mezze is a spread of small dishes, where the meal is the combination rather than
any one plate. A few simple constructs — records, variants, abilities, effects —
combine to cover what larger languages need dedicated features for. A parameter
list *is* a record; an ability and an effect are both named sets of operations,
written and read the same way — what differs is who supplies them and when. Learn
records well and most of the syntax follows.

---

## Getting started

The zero-install path is the **[browser playground](https://mezze-lang.org)**. To
build from source:

### With Nix + devenv (recommended)

The repo ships a [`devenv`](https://devenv.sh) shell with the exact GraalVM/JDK,
Kotlin and Node it needs:

```bash
git clone https://github.com/shrynx/funk-lang.git mezze
cd mezze
devenv shell     # drops you into a shell with mez, gradle & graalvm on PATH
build            # build the compiler  (./gradlew :cli:installDist)
mez --help
```

### With Gradle directly

```bash
./gradlew :cli:installDist
export PATH="$PWD/cli/build/install/mez/bin:$PATH"
mez --help
```

### Your first program

```bash
mez init hello
cd hello
mez dev          # run it
```

…or run a single file: `mez exec app.mz`.

---

## The `mez` CLI

| command | what it does |
|---|---|
| `mez init <name>` | scaffold a new project |
| `mez dev` | run the app — interpreted, fast startup, no JIT warm-up |
| `mez build` | build a JVM jar (JIT); add `--native` for an AOT GraalVM binary |
| `mez exec <file.mz>` | run a single file |
| `mez check` | type-check without running |
| `mez fmt` | format source |
| `mez index` | build the content-addressed store |
| `mez lsp` | language server (editor integration) |

---

## How it's built

Mezze is a Kotlin front-end on a GraalVM/Truffle runtime, split into focused
Gradle modules:

| module | role |
|---|---|
| `ast` | parsed-AST nodes, diagnostics, and the shared type core (content hashing) |
| `parser` | hand-written recursive-descent parser + combinators (no ANTLR) |
| `typechecker` | inference, unification, ability/effect/exhaustiveness checking |
| `interpreter` | GraalVM/Truffle runtime — effect handlers, native nodes |
| `langservice` | shared diagnostics, hover & completion — powers LSP, CLI and the WASM playground |
| `lsp` | Language Server Protocol server |
| `cli` | the `mez` command |
| `store` · `store-sqlite` | content-addressed storage (SQLite-backed) |
| `stdlib` | the standard library, written in Mezze |
| `formatter` | source formatter |
| `datastructures` | Truffle-native collections (Seq/List/Map/Set) |
| `wire-protocol` | runtime wire protocol for distributed execution |

Every declaration is hashed by structure, which drives incremental compilation,
the store, and the language server.

---

## Development

Inside `devenv shell`:

```bash
build            # ./gradlew :cli:installDist
test             # build + run the test suites
build-native     # AOT native binary via :cli:nativeCompile
```

The typechecker and interpreter suites are heavy and share a lot of RAM — run them
as **separate** Gradle invocations rather than one `gradle test`:

```bash
./gradlew :parser:test       --no-daemon
./gradlew :typechecker:test  --no-daemon
./gradlew :interpreter:test  --no-daemon
```

The marketing site + browser playground live in `website/` and are driven by the
`Justfile` (`just dev`, `just build`, `just deploy`). The tree-sitter grammar lives
in `tooling/` (`ts-build`, `ts-test` in the devenv shell).

---

## Status

Under active development. The language, type system, effect runtime, formatter,
LSP and browser playground all work today; APIs still move. `planned_features/`
holds designs not yet implemented (macros, polyglot interop).

## License

No license yet — all rights reserved for now.
