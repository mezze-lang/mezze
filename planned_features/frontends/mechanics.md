# Mechanics — how a custom parser would actually work

Concrete answers, grounded in what the tree already has. Companion to
`README.md`, which argues *why*; this is *how*.

## 1. What language can a frontend be written in?

**Mezze.** A frontend is a Mezze function. Everything else in this section is
why the alternatives are worse, kept short because they are not the plan.

### The AST maps onto Mezze's own data constructs exactly

This is the fact that makes it work, and it is not a coincidence — both sides
are describing algebraic data:

| `parsedast` (Kotlin) | count | Mezze |
|---|---|---|
| sealed hierarchies | **15** | closed variants |
| data classes | **74** | records |

Records have named fields; so do the data classes. Closed variants enumerate
their constructors; so do the sealed types. There is no impedance mismatch to
design around — the AST **raises to Mezze data structures** directly.

### The serialisation boundary already exists

`StoreSerialization.serializeParsedModule` / `deserializeParsedModule` are
**CBOR**, and they run on every store write today. A Mezze frontend builds a
value of the Mezze `ParsedModule` type; the boundary encodes it; the compiler
decodes it with the function it already calls. **No new wire format, no new
serialisation code** — the path is exercised on every compile already.

### Effects are the sandbox, and they self-police

```mezze
{source: Str, file: Str} -> ParseResult
```

An **empty effect row** is a typechecker proof that the frontend cannot read the
filesystem, open a socket, or read the clock. Static, checkable before anything
runs — so `mez add` can reject a frontend that declares effects a parser has no
business having. The rule is legible: *a parser is a pure function from text to
AST.*

This also dissolves the "what about parsers written in other languages"
question. A frontend that shells out to an existing Rust parser **declares the
effect that lets it**, and is then visibly not pure. No separate subprocess-
frontend mechanism is needed; the escape hatch falls out of the effect system,
and it announces itself.

### Native image is not a blocker — the asymmetry that matters

`~/.mezze/bin/mez` is a native image (ELF, no `libjvm`). Native Image closes the
world at build time, so it **cannot load a Java or Kotlin class** it did not know
about. That rules out JVM plugins for the binary users install.

It does not touch Mezze. **Mezze code is not a class — it is data to the Truffle
interpreter**, and `mez dev` already loads and runs Mezze at runtime inside that
same native binary. Mezze is the one plugin format the shipped binary can
already load.

### Why not WASM

WASM was the previous recommendation here and it was the wrong instinct: it
reaches for *runtime containment* to get a property the **type system already
proves statically**, and it pays for that with the whole polyglot apparatus —
GraalWasm on the engine, a foreign toolchain to build frontends, and a foreign
ABI at the boundary instead of Mezze values.

`polyglot-wasm-natives/` exists and GraalWasm is registered when polyglot is on,
so it remains *possible*. It is simply not better than the language's own
functions at anything that matters here, and it is worse at ecosystem: a Mezze
frontend is a **Mezze package**, installed with `mez add`, versioned and
hash-pinned like any dependency. That is the ecosystem, and it needs no new
distribution story.

## 2. How is it passed to the toolchain?

### One hook point

`ModuleResolver.kt:555`:

```kotlin
parser.parseParsedModule(source, file = filePath.toString())
```

That single call is where source becomes AST for every module. A frontend
dispatch goes exactly there: choose a parser based on the file, call it, get a
`ParsedModule`. Everything downstream — typechecking, hashing, storage — is
already parser-agnostic because it consumes `ParsedModule`, not text.

### Declaration and resolution

```toml
[frontends.lisp]
wasm = "frontends/lisp.wasm"     # or  command = "…"  /  package = "…"
extensions = [".lisp.mz"]
hash = "sha256:…"                 # pinned, like a dependency
```

Resolved like a dependency, pinned by hash, so a build is reproducible and a
frontend cannot change under you. Declaring in `Mezze.toml` rather than per-file
is the safer half of the tension noted in `README.md` §3 — a pulled file cannot
change how it is parsed.

## 2b. Binding file patterns to preprocessors

```toml
[dependencies]
mezze-sql-syntax  = "0.1"
mezze-lisp-syntax = "0.1"

[frontends]
"*.sql.mz"  = "mezze-sql-syntax::parse"     # glob -> a Mezze function
"*.html.mz" = "mezze-html-syntax::parse"
lisp        = "mezze-lisp-syntax::parse"    # named, for `#lang lisp`
```

The right-hand side is a **qualified Mezze function reference**, not an opaque
plugin id. That is worth insisting on: it removes a registry indirection, it is
resolved by the module system that already exists, and it is checkable — the
typechecker can verify the named function has the frontend type before the build
proceeds, rather than discovering at parse time that it does not.

Frontends being ordinary dependencies means hash pinning, version resolution and
`mez add` come free. Two consequences worth stating:

- A frontend package must be compiled **before** any file it parses. That is
  dependency ordering, which the build already does — but it forbids a frontend
  parsing files in its own package, which should be an explicit error rather
  than a confusing cycle.
- `resolveModuleFile` must learn the extra suffixes; today `a::b` maps to
  `src/a/b.mz` only.

## 3. How does a file say which syntax it is? — the glob already said it

**Extension only. There is no in-file directive, and `#lang` is not needed.**

An earlier draft proposed `#lang lisp` alongside the glob. That was redundant:
if `Mezze.toml` maps `*.lisp.mz` to a parser, the file has already declared its
syntax by being named that. A second mechanism would only create the question of
which wins.

It is also the safer half of the supply-chain tension in `README.md` §3 — with
configuration-only binding, a pulled file cannot change how it is parsed.

What this does need: `resolveModuleFile` learning additional suffixes, since
`a::b` maps to `src/a/b.mz` today. Extension-based binding is also what every
editor integration keys off, so it is the form that makes highlighting possible
at all.

(For the record, `#lang` *would* have been available — the lexer is
`token(seq('#', optional(/[ \t][^\n]*/)))`, so `#` or `# text` is a comment,
`##`/`#{` win as longer tokens, and `#foo` with no space matches nothing. Noted
only so nobody re-derives it as a reason to add the directive.)

## 4. How do frontends and macros end up being one thing?

The macro design is already `TokenStream -> Expr`
(`../macros/02_TAGGED_TEMPLATES.md`). A frontend is `Source -> [Decl]`. Same
family, different scope — so one plugin host serves both:

| scope | signature | invoked by |
|---|---|---|
| expression | `TokenStream -> Expr` | `sql!(…)` |
| declaration | `TokenStream -> Decl` | a directive on one decl |
| file | `Source -> ParsedModule` | extension or `#lang` |

Build the host once, with three entry points. The AST subset contract
(`README.md` §1) is then shared rather than duplicated — which is the main
practical argument for treating these as one feature instead of two.

All three are **Mezze functions over Mezze AST types**, so "the host" is: resolve
a function reference, call it through the runtime, decode the CBOR it returns.
The scopes differ only in what is passed in and what shape comes back.

### Macros need no per-file mechanism at all

An earlier draft invented `#use-macros` and a `[macros]` glob table. **Both were
unnecessary.** A macro is a *name*. It comes into scope the way every other name
does:

```mezze
use mezze_html::{ html }

let page = html!(<div>hello</div>)
```

`use` to import, `!()` to expand — and `!()` is already the designed expansion
operator (`../macros/04_SYNTAX_EXPANSION.md`). Nothing new is required.

### Macros are a *Mezze-syntax* concept

This is the simplification that removes the rest of the machinery. A macro
invocation is written in Mezze's surface syntax, so it only has to exist there.
A Lisp frontend defines whatever invocation form Lisp wants; an HTML frontend
may have no macro concept at all. **The host language of a file decides how
macros are spelled in it**, and that is the frontend author's problem, not the
compiler's.

Consequences: no declaration-scope syntax marker is needed, and the earlier
"one mechanism at three scopes" table over-generalised. There are two:

| scope | how | who defines the spelling |
|---|---|---|
| expression | `html!(…)` in a `.mz` file | Mezze — the `!()` operator |
| file | `*.mz.html` glob in `Mezze.toml` | configuration, no syntax at all |

### One parser, two entry points

The unification worth having is that **the same Mezze code** — an HTML-to-AST
converter — serves both:

```toml
"*.mz.html" = "custom_html_preprocessor::html"   # whole file
```
```mezze
use custom_html_preprocessor::{ html }
let page = html!(<div>…</div>)                    # one expression
```

The parsing logic is written once. The two uses differ in signature — expression
scope returns `Expr`, file scope returns declarations — so a package exposes two
thin entry points over one parser rather than literally one function.

**Unless macros gain declaration scope**, in which case file scope *is* a
declaration-scope macro applied to the whole file, and it collapses to one
mechanism. The current design returns `Expr` only
(`macro sql: {tokens: TokenStream} -> Expr`), so this is a choice still open —
and worth making deliberately, because it is the difference between two
mechanisms and one.

### Declaration syntax: the `macro` keyword, not metadata

Invocation is `macro_name!()` — the `!()` expansion operator. Declaration stays
the `macro` keyword from `../macros/`.

One alternative gets floated and should be recorded as rejected: declaring a
macro via metadata, `#{ macro: True }`. It breaks metadata's own contract.
`metadata.md` states that metadata is *"information **about** code, not code
itself"*, *"does not change runtime behavior"*, and is **excluded from the
content hash** — while a macro declaration is semantic. Putting it there either
makes metadata semantic (losing the property that a rendering hint can never
invalidate a cache) or leaves a semantic declaration outside the hash, so two
modules that compile differently would hash the same. Hash exclusion is
load-bearing for verifiable syntax migration.

## 5. Mezze as the frontend language — and why it is the *best* option

An earlier version of this document staged Mezze-as-frontend last, behind
comptime, on a bootstrapping argument. **That was wrong**, and the correction
matters because it changes the recommendation.

### Running a Mezze library as a preprocessor is not comptime

Comptime is staged evaluation *inside* a compilation unit. Loading an
already-compiled Mezze package and calling a function in it is something else
entirely: it is just **running a Mezze program**, which the toolchain
demonstrably does. Compile the frontend package like any dependency, then call
it while parsing. No new evaluation model.

Bootstrapping is correspondingly mild. The frontend is written in the default
syntax and compiled before the files that use it — a dependency-ordering
problem the build system already solves. A frontend cannot be written in the
syntax it defines, which is normal: Racket's `#lang` implementations are written
in Racket.

### Native image is not a blocker — and this is the asymmetry that matters

§1 rules out JVM plugins because Native Image closes the world at build time and
cannot load an unknown class. **Mezze code is not a class — it is data to the
Truffle interpreter.** `mez dev` already loads and runs Mezze at runtime inside
the native binary.

So the constraint that kills Java frontends does not touch Mezze frontends. The
language is the one plugin format the shipped binary can already load.

### Effects *are* the sandbox

A frontend has a type:

```mezze
{source: Str, file: Str} -> ParseResult
```

With an **empty effect row**, the typechecker has proven it cannot read the
filesystem, open a socket, or read the clock. That is the supply-chain answer
§1 reached for WASM to provide — except it is a *static proof, checkable before
anything runs*, rather than runtime confinement.

WASM sandboxes by containment. Mezze sandboxes by type. For this particular job
the second is strictly better: you can reject a frontend at `mez add` time for
declaring effects it has no business having, and the rule is legible — *a parser
is a pure function from text to AST, and anything else is suspicious*.

This is the same insight as effects gating tool-initiated execution
(`../tooling-platform/thesis.md`), applied to plugins.

### What is actually required

1. **An SPI, because of layering.** `:typechecker` does not depend on
   `:interpreter` (they are siblings over `:ast`/`:store`/`:stdlib`), and it
   should not start. So define a `FrontendProvider` interface low in the stack,
   have `ModuleResolver` call it when set, and let `:cli` — which has both —
   inject an implementation backed by the runtime.

   **There is a precedent to copy exactly:** `ModuleResolver.setStore(store)` is
   already this pattern — an interface from a lower module, injected by the
   driver, held in a `@Volatile` static.

2. **A Mezze-side representation of the AST subset.** The contract of
   `README.md` §1, expressed as Mezze types. This is arguably *better* than a
   foreign-language contract, because the typechecker then checks frontends:
   a frontend that builds a malformed AST fails to compile rather than failing
   at parse time.

3. **A warm runtime.** Interpreter startup per parse is the real cost. The
   daemon already exists to amortise exactly this.

### Recommendation

Frontends are Mezze functions, distributed as Mezze packages. A frontend needing
a foreign parser declares the effect to call one, which keeps the escape hatch
available and visible rather than building a second plugin system for it.

## 6. How does comptime still fit?

This is the end state and it is **staged**, because of bootstrapping: a frontend
written in Mezze must be compiled before it can parse Mezze.

The pieces are designed but unbuilt:

- **comptime** — `../macros/03_COMPTIME.md` executes Mezze at compile time via a
  **`CompileTime` effect**. That is an elegant fit with everything else here:
  "may run at compile time" becomes a type-level property, the same way "safe
  for a tool to run" is. It does not exist — `comptime` appears zero times
  outside `planned_features/macros/`.
- **phases** — the macro docs discuss phase separation, which is what makes
  bootstrapping tractable. Racket's answer.

Comptime is still needed for **macros** — expression-scope expansion *within* a
module being compiled, where there is no separate package to precompile. That is
a genuine staging dependency and it remains unbuilt.

It is **not** needed for file-scope frontends, per §5. The two were conflated
because both are "Mezze running at compile time", but only one of them needs
staged evaluation inside a compilation unit.

## 5b. Really good error messages

The risk is concrete: frontends are third-party, error quality is the first
thing third parties skimp on, and a syntax whose errors are bad is worse than no
syntax. Four things, in order of leverage.

### 1. Make good errors the only expressible result

The return type decides the ceiling. `Result<ParsedModule, Error>` caps quality
at one error and loses recovery. The contract should be:

```mezze
type ParseResult = {
  decls:  [Decl],          # what SURVIVED — possibly partial
  errors: [ParseError],    # possibly many
}

type ParseError = {
  code:    Str,            # frontend-namespaced, e.g. "LISP0003"
  loc:     Loc,            # into the frontend's OWN source
  message: Str,
  hints:   [Str],
  notes:   [{ loc: Loc, message: Str }],   # secondary spans
}
```

Partial declarations **plus** errors, because Mezze's own parser recovers per
declaration — one bad decl does not kill the file, and survivors still
typecheck. A frontend returning `Result` cannot express that, so it would lose
graceful degradation exactly where it matters most: the LSP, where half-typed
code is the normal state, and agent changesets, where one bad decl would
otherwise blind you to the other eleven files.

Secondary spans matter more than they look — "expected `)` to close **this**"
with a pointer at the opening paren is the difference between a usable and a
useless parenthesis error.

### 2. Validate `Loc` at the boundary

A frontend emitting nonsense spans produces diagnostics pointing nowhere, and
the failure is silent — the message looks fine, the caret is in the wrong place.
Check at the boundary that every `Loc` is within the source, that
`start <= end`, and that the file matches. Reject the frontend's output
otherwise, with an error naming the frontend rather than the user's code. The
user should never debug a frontend's bug as if it were theirs.

### 3. Reuse the quality ratchet that already exists

`parser/…/ParserErrorQualityTest.kt` is the mechanism, and it is better than
most projects have. Each corpus entry asserts: it rejects, the raw message is
non-blank, the location is valid, the message is **not** a generic ("No
alternatives matched"), and — the part that matters — `mustMention`, a substring
the *correct* message must contain, so a **misdiagnosis fails the test**. Plus a
ratchet toward code coverage: an `UNCATEGORIZED` entry must stay uncategorised
until someone assigns a code and hint, at which point the entry is updated.

A frontend package should ship the same corpus shape and be held to the same
bar. `mez` can run it — a frontend whose errors are generic or misdiagnosing
fails its own test suite, visibly, before anyone depends on it.

This is the single highest-leverage item here, because it makes quality
*measurable* rather than aspirational, and the harness is already written.

### 4. The rule for *downstream* diagnostics

Type errors are produced by the typechecker, not the frontend, and located by
the `Loc`s the frontend emitted. So locations are right for free. The trap is
vocabulary:

- **Diagnostics that quote the user's source are fine.** The rustc-style
  `--> file:line:col` block shows the author's actual text, whatever syntax it
  is in.
- **Diagnostics that *generate* syntax are wrong.** "try writing `{ x = 1 }`"
  is nonsense in a Lisp file. Anything that constructs code — code actions,
  suggested fixes, `mez fmt` output — must go through the **frontend's printer**.

Which is the fourth independent argument that a frontend must be a parser *and*
a printer. `mez fmt` needed it, round-tripping needed it, the multi-syntax
rendering story needed it, and now suggested fixes need it. If the contract has
only one direction, all four break quietly.

## 6. The operational gaps this exposes

Concrete versions of the questions `README.md` left abstract:

- **Error recovery.** Mezze's parser recovers per declaration — one bad decl does
  not kill the file, and surviving decls still typecheck. A plugin will not do
  that by default, so alternative-syntax files lose graceful degradation exactly
  where it matters most (LSP, and agent changesets). **The contract should carry
  a list of partial declarations plus errors, not `Result<Module>`** — recovery
  must be expressible, or it will not happen.
- **Incrementality.** The LSP reparses on `didChange`. A plugin is a black box
  with no partial reparse. Extension-based files may need a whole-file reparse
  per keystroke-batch; the 250 ms debounce becomes load-bearing.
- **Module system.** Does a frontend emit imports and exports, or only
  declarations? If a Lisp surface wants its own import syntax it must emit the
  former, which widens the contract considerably.
- **Diagnostics.** A plugin must emit `Loc` into its own source — which works,
  since `Loc` is `(file, line, col, line, col)` — but a plugin that emits
  nonsense locations produces diagnostics that point nowhere. Worth validating
  at the boundary.
