# Mechanics — how a custom parser would actually work

Concrete answers, grounded in what the tree already has. Companion to
`README.md`, which argues *why*; this is *how*.

## 1. What language can a frontend be written in?

### Not arbitrary JVM code — the shipped `mez` forbids it

`~/.mezze/bin/mez` is a **native image** (ELF, no `libjvm`, no child JVM).
Native Image closes the world at build time, so it cannot load a Java or Kotlin
class it did not know about when it was built. "A Java-written function" is
viable for a JVM-hosted build path and **not** for the binary users install.
Deciding this early matters, because it rules out the most obvious design.

### WASM — the strong answer, and it is already wired in

`polyglot-wasm-natives/` exists, and GraalWasm is registered as a language on
the running engine (`RuntimeServer` and `EmbeddedExec` both add `"wasm"` to the
enabled languages when polyglot is on).

That makes WASM the natural frontend format:

- **Language-agnostic** — write the frontend in Rust, Zig, C, AssemblyScript,
  or eventually Mezze compiled to wasm.
- **Sandboxed by construction** — which answers §3 of `README.md` without
  inventing a sandbox. A frontend gets no filesystem and no network unless
  granted.
- **Works from the native binary** — no dynamic JVM class loading needed.
- **Precedent in this repo** — the Zed extension is exactly this shape, a
  `wasm32-wasip1` component with a declared entry point.

### JS and Python — available today

`polyglot-js-natives/` and `polyglot-python-natives/` exist on the same
mechanism. A frontend written in Python is plausible with what is already built.
Slower to start than WASM, easier to write.

### Subprocess — the simplest thing that works

`mez` runs `my-frontend path/to/file`, the process writes a serialised
`ParsedModule` on stdout, `mez` deserialises it. Any language, no host
integration, OS-level sandboxing, trivial to debug.

Cost is a process per file — but that is exactly what the **changeset**
primitive amortises: one process invocation per batch rather than per file.

**Recommendation:** subprocess for the prototype, WASM for the real thing. The
wire format is the same either way, so the choice is not load-bearing.

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

## 5. How does it become programmable from Mezze itself?

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

**Staging:**

1. Frontends are external plugins (subprocess or WASM) in any language. No
   bootstrapping problem, because they are not Mezze.
2. Macros land with comptime, giving Mezze-in-Mezze at expression scope.
3. File-scope frontends in Mezze, once phases are settled — a frontend is then
   just a comptime function of the right type, compiled with the default parser
   and used for the next phase.

Stage 1 is worth doing without waiting for 2 and 3, and it is also how you learn
what the AST subset actually needs to contain.

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
