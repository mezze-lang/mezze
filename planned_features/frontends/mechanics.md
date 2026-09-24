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

## 3. How does a file say which syntax it is?

Two mechanisms, and they are not equivalent.

### By extension — `foo.lisp.mz`

Needs `resolveModuleFile` to learn additional suffixes; today the module path
`a::b` maps to `src/a/b.mz`. Extension-based is unambiguous, visible in a
directory listing, and lets editors pick a highlighter by filename — which
matters, since every editor integration keys off the extension.

### By directive — `#lang lisp`

**This token is available.** The lexer defines
`line_comment: token(seq('#', optional(/[ \t][^\n]*/)))` — `#` alone or `# text`
is a comment, `##` and `#{` win as longer tokens, and **`#foo` with no space
matches nothing**. So `#lang` is currently a lexical error, not a comment, which
means it can be claimed as a new token with no ambiguity and no risk of a file
silently parsing two ways.

That is a small, lucky piece of design headroom. Racket's `#lang` works for the
same reason — `#` is not its comment character either.

**Recommendation:** extension for the file-type cases (`.html.mz`), directive for
alternative whole-language syntax, because a `.mz` file that is Lisp inside is
better announced in its first line than in its name.

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
