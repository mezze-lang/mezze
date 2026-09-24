# Custom parsers — one mechanism, many capabilities

> Status: idea, with the preconditions checked. Not planned work.
> This is *our* framing of the idea; `../macros/` is a separate, existing design
> and is cited here as evidence, not amended.

**The idea is singular: anything that produces Mezze AST is a valid source of
Mezze code.** Not "a pluggable parser" — that undersells it. Once the AST is the
thing the compiler consumes and text is merely one way to arrive at it, a
surprising number of separate-looking features turn out to be the same feature
at different scopes.

What one mechanism buys:

| capability | how it falls out |
|---|---|
| **DSL templates** — `sql!(…)`, `html!(…)` | a parser over a token stream, returning an expression |
| **Macros** | the same thing — this is already the macro design |
| **LLM-efficient syntax** | a parser over a compact encoding, returning declarations |
| **Alternative surface syntax** — Lisp, other | a parser over a whole file |
| **Markup-first files** — `.html.mz` component shape | a parser where markup hosts the logic |
| **Syntax versions in one project** | two parsers for two language versions, same AST |
| **Verifiable syntax migration** | same AST ⇒ same hash ⇒ provably unchanged meaning |

The last two are not DSL features at all, and are argued below to be the most
valuable — which is the sign that the mechanism is more fundamental than any of
its uses.

The concrete forms it takes: declare a parser in `Mezze.toml` or per-file at the
top; a Lisp surface syntax; a token-efficient syntax an LLM writes because it is
cheap to emit rather than pleasant to read; a DSL that compiles to the same
nodes.

## Prior art — this is not speculative

Every part of this exists somewhere. The useful question is what each precedent
got right and what it cost them.

### Racket — the ideal shape

`#lang` at the top of a file selects both the *reader* (text → syntax objects)
and the *expander*. `#lang racket`, `#lang typed/racket`, `#lang datalog`,
`#lang scribble/base` — per-file syntax declaration, with the module system
carrying it. Racket is a language for building languages, so this was designed
in from the start rather than retrofitted.

The lesson: per-file declaration is the granularity that works. Racket did not
need per-declaration, which is evidence for the earlier judgement that
declaration scope is the costly one with the weakest case.

### OCaml and Reason — proof it works retrofitted, and proof of the price

Reason is precisely this idea: a different surface syntax producing the **same
OCaml parsetree**, with `refmt` converting between `.re` and `.ml`. Not a
different language — a different projection of one AST. OCaml supports it
through `-pp` preprocessors and PPX extension points.

Two things to take from it:

**It validates the model.** Reason existed alongside OCaml in the same
ecosystem, sharing libraries, because the AST was the interchange layer. That is
exactly the claim in this document.

**It validates the cost.** OCaml's parsetree changes between compiler releases,
and every PPX breaks when it does. `ocaml-migrate-parsetree`, and later
`ppxlib`, exist *specifically* to insulate extensions from that churn — an
entire library ecosystem whose purpose is absorbing AST instability. That is §1
of this document, observed in the wild. It is the strongest argument for the
versioned-subset approach rather than exposing the live AST.

Reason also needed its own printer (`refmt`) and its own editor tooling, which
is §5 and §4 confirmed.

### Rust — editions, and macros at the wrong scope for this

Proc macros are `TokenStream -> TokenStream` at item and expression scope —
close to the macro row of the table, not the file row.

**Editions** are the syntax-versioning idea proven at ecosystem scale: crates
declare an edition, editions coexist in one dependency graph, and `cargo fix`
migrates. The granularity is per-crate rather than per-file, which is coarser
than proposed here.

And this is where Mezze would have something Rust does not. `rustfix` has to be
*trusted* — you read the diff and hope. With content addressing, a syntax
migration that changes meaning changes the hash, so the migration is
**checkable** rather than reviewable. That property is not available to any of
these precedents, because in all of them identity is the text.

### Haskell, Elixir, Nim — the DSL row

GHC has per-file `{-# LANGUAGE … #-}` pragmas (a language *variant* per file,
not a full syntax swap) and `-pgmF` preprocessors. **QuasiQuotes** are the
`sql!(…)` / `html!(…)` case almost exactly. Elixir's `quote`/`unquote` and its
documented AST make "everything is a macro" the norm. Nim's macros operate
directly on the AST.

All of these confirm the expression-scope row is well-trodden. None of them
attempt whole-file alternative syntax.

### Svelte, Vue, JSX — the markup-first row

`.svelte` and `.vue` files are markup-dominant sources compiled to the host
language, which is the `.html.mz` shape. They also demonstrate its real cost:
each needed a bespoke compiler, language server, and highlighting story. That is
a large investment per file type, and it argues for markup-first files being a
deliberate first-party thing rather than something users define casually.

### Unison — the precondition without the payoff

Unison has the property this whole document rests on — the codebase is the AST,
text is a projection — and does *not* offer multiple syntaxes. So the
combination proposed here (content-addressed codebase **plus** pluggable
surface) appears to be genuinely unoccupied ground, rather than something
obvious that everyone does.

### Summary

| | per-file syntax | AST as contract | syntax versioning | verifiable migration |
|---|---|---|---|---|
| Racket | ● designed in | ● syntax objects | | |
| OCaml/Reason | ● via `-pp`/PPX | ● and it churns | | |
| Rust | | ● TokenStream | ● editions (per crate) | ✗ trust `rustfix` |
| Haskell | ◐ pragmas | ● TH | | |
| Unison | | ● codebase is AST | | ◐ property unused |
| **Mezze** | **possible** | **needs a subset** | **possible, per file** | **● free from hashing** |

The right-hand column is the one nobody has.

## Why this fits Mezze specifically

Most languages cannot do this cleanly because identity is the text. Here it
already is not.

**Hashes exclude formatting, comments, metadata and file path.** Identity is the
content hash of the declaration's structure, and *"names are metadata, not
identity"*. So two files in two different syntaxes that produce the same AST
produce **the same hash** — they are not equivalent code, they are *the same
code*, indistinguishable to the store, the typechecker and the runtime.

That single fact dissolves the objection that normally kills this idea.
Alternative syntaxes usually fragment a community: your Lisp dialect and my
normal syntax cannot read each other. Here the store holds the AST, the
formatter renders it, and **rendering is per-reader**. You write Lisp, I read
the default syntax, and neither of us has converted anything.

It is the Unison observation — the codebase is not text — with the consequence
taken one step further: if text is already a projection, there is no reason
there must be only one projection.

## Preconditions, checked

| needed | state |
|---|---|
| AST is a serialisable interchange format | **yes** — `@Serializable` throughout `parsedast`, with `serializeParsedModule` / `deserializeParsedModule` already used to store modules as CBOR blobs |
| AST → text exists (the other direction) | **yes** — the formatter's `PrettyPrinter` |
| diagnostics can point into a foreign syntax | **yes** — `Loc(file, startLine, startCol, endLine, endCol)` is plain text coordinates, so a frontend emits Locs into *its own* source and errors land where the author typed |
| a place to declare it | `Mezze.toml` exists; a per-file directive does not |

So the machinery is largely present. What is absent is a *contract*.

## What it would actually cost

### 1. The ParsedAST becomes a public API

This is the real price, and it is not small. `parsedast` is currently an
internal Kotlin data model that changes whenever the language changes. A
frontend contract freezes it — every new node, every renamed field, becomes a
breaking change for every frontend.

The user instinct to say "or a **compatible subset**" is the right one. Define a
**versioned core subset** that frontends target, with the full AST remaining
internal. A frontend emitting the subset gets stability; the language keeps
freedom to evolve outside it. That subset needs designing before anything else,
because it is what everything downstream depends on.

### 2. Comments and metadata must survive

Hashes exclude `##` docs and `#{ }` metadata, but the store keeps them and hover
shows them. A frontend that emits only executable structure silently drops
documentation — the code still hashes the same, and the docs are gone. Any
frontend contract has to carry doc and metadata, not just expressions.

### 3. A frontend is arbitrary code at compile time

"A Java-written function, or anything" means running third-party code during the
build. That is a supply-chain surface and needs deciding up front: sandbox it,
require it to be declared in `Mezze.toml` rather than per-file (a per-file
directive lets a single pulled file change how it is parsed), pin it by hash.

Note the tension with the per-file idea: per-file declaration is the convenient
version and the dangerous one.

### 4. Every tool assumes one syntax

Tree-sitter grammar, TextMate grammar, the website highlighter, `mez fmt`, the
LSP's completion and code actions — all of them are written against the default
syntax. A frontend that is not also accompanied by highlighting and formatting
produces files no editor understands.

This is where the cost lands in practice, and it argues for frontends being a
*deliberate, small* set rather than something anyone enables casually.

### 5. `mez fmt` needs an answer

If a file declares a frontend, formatting must round-trip through *that* syntax,
which means a frontend is not just a parser — it is a parser **and** a printer,
or formatting is unavailable for it.

## The LLM case is the interesting one

The human-readable argument for a compact syntax is weak. The token argument is
not: an agent emitting many lines across many files pays for every token, and
the default syntax is optimised for a reader who is no longer the primary
author.

What makes it defensible here rather than merely clever: the compact form is
**not a different language**. It hashes identically, so a human never has to
read it — they read the same declarations rendered in the default syntax, and a
reviewer diffing a change sees normal Mezze regardless of what the agent
actually wrote.

That also suggests the honest first experiment, which needs none of the above:
**measure it**. Render a representative module through a hypothetical compact
syntax and count tokens. If the saving is small, the idea is interesting and not
worth the contract cost. If it is large, the contract cost buys something real.
Nobody has measured it.

## One mechanism at three scopes

The macro design already builds the core of this, which reframes the whole idea.
`planned_features/macros/02_TAGGED_TEMPLATES.md` declares:

```mezze
macro sql: {tokens: TokenStream} -> Expr (SqlQuery <>) = {tokens} -> …
```

`TokenStream -> Expr`. **That is a parser that produces AST nodes.** "Macros
essentially become parsers and AST generators" is not a proposal — it is the
existing design, at expression scope.

So there is one mechanism at three scopes, and only the first exists:

| scope | invocation | contract | state |
|---|---|---|---|
| expression | `sql!("SELECT …")` | `TokenStream -> Expr` | designed (macros) |
| declaration | a syntax marker on one decl | `TokenStream -> Decl` | new |
| file | `foo.lisp.mz` | `TokenStream -> [Decl]` | new |

That is a much better framing than "swappable parsers", because it means the
contract question (§1 above) is *shared* with macros rather than new. Whatever
subset of the AST macros are allowed to construct is the same subset a frontend
constructs. Design it once.

### Which scope fits which case — templates belong at macro scope

`file.sql.mz` is the wrong tool for SQL, and `sql!()` is the right one. You
rarely want a *file* of SQL; you want a query inside a Mezze function, composing
with surrounding code, with typed interpolation — which is exactly what the
macro gives, including `compile_error` on invalid SQL at compile time. A
whole-file SQL frontend would have to reinvent "how is this called from Mezze".

`file.html.mz` is more defensible, because markup-dominant component files (the
`.vue` / `.svelte` shape) genuinely are file-shaped: mostly markup, a little
logic. The test is which language dominates the file. Query embedded in logic →
macro. Markup hosting logic → file frontend.

### Declaration scope is the costly one

Mixed syntaxes inside one file mean an editor highlighting different regions
differently, the LSP switching parsers mid-file, and formatting per region.
There is at least a standard answer for the highlighting half: tree-sitter
`injections.scm` is exactly the embedded-language mechanism, it is one of the
query files Zed's extension host loads, and this project has never written one.

Even so, this is the scope with the highest tooling cost and the weakest
motivating case. Expression scope covers embedded DSLs; file scope covers
alternative whole-language syntax. It is not obvious what declaration scope
covers that neither does.

## The strongest case: syntax versions in one project

Nothing in `planned_features/` mentions editions, syntax versions or migration.
This is the idea in this document most worth having, and it is not about DSLs at
all.

If syntax is declared per file, a language syntax change stops being a big-bang
migration. Old files declare `syntax = "1.0"`, new files `syntax = "2.0"`, both
produce the same AST, and a project migrates a file at a time. That is Rust
editions, at finer granularity.

And there is a property here that Rust editions **cannot** have. Because hashes
exclude syntax, comments and formatting, a file migrated from one syntax to
another has the **same hash** if and only if it means the same thing. So a
syntax migration is *verifiable*: run the migration, compare hashes, and a
changed hash is a bug in the migration tool. `rustfix` has to be trusted;
here it is checkable.

That makes per-file syntax declaration valuable even if nobody ever writes a
Lisp frontend — it is insurance on the language's own ability to change its
surface, and the verification property is a direct dividend of content
addressing.

## Relationship to macros

`planned_features/macros/` already covers pattern macros, tagged templates and
comptime — extension *within* the language. A syntax frontend is extension
*of the surface*, one level coarser. They are complementary, but a frontend is
not a substitute for macros and should not be justified as one: if the goal is
a DSL inside Mezze files, that is macros, and macros do not require freezing the
AST.

## Verdict

The idea is sound and unusually well-suited to this language, and the
preconditions are mostly met. The cost is concentrated in one place — **turning
the ParsedAST into a versioned public contract** — and that cost is paid
permanently, in language-evolution friction, for as long as frontends exist.

Worth doing *after* the platform primitives, not before: a frontend is a
producer of ASTs, and the read/evaluate/speculate core is what makes any AST
useful. And worth the token measurement first, since that is the one argument
that would make it clearly worth the price.
