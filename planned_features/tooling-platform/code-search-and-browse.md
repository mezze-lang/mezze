# Hoogle on steroids — what the index still needs

`README.md` names the missing signature index. That understates the work, and it
also understates what is already there. This is the detail.

## Already solved, and worth knowing before designing

**Search already spans user code, stdlib and dependencies.** The store is three
SQLite databases ATTACHed together — `main` (project src), `stdlib`
(`.mez/store/stdlib.sqlite`, materialised from a bundled JAR resource), and
`deps` (`.mez/store/deps.sqlite`, read-only, written by `mez sync`) — and
queries are built with `unionAll`, which templates a statement across all three.
"Browse library code and user code" is architecturally satisfied at the storage
layer. Nothing needs re-plumbing.

**Content addressing gives free permalinks.** Declarations are keyed by a stable
content hash that excludes comments, metadata, formatting and file path. A doc
browser URL of the form `/decl/<hash>` is therefore permanent and survives
renames and moves — the Unison property, already paid for here.

## 1. The hard part of Hoogle is matching, not storage

Storing a signature string and running `LIKE` over it is not Hoogle. The search
has to be **type-directed**:

- **Alpha-normalisation.** `{xs: ['a]} -> Int` and `{xs: ['b]} -> Int` are the
  same signature. Type variables must be canonically renamed before indexing, or
  identical types miss each other.
- **Subsumption, not equality.** A query for `['a] -> Int` should match
  `[Str] -> Int` (the query is more general) and a query for `[Str] -> Int`
  should match `['a] -> Int` (the result is more general). These are different
  directions and users expect both.
- **Argument-order insensitivity.** Mezze functions take *records*, which is a
  genuine advantage: `{x: Int, y: Str}` is a set of named fields, so there is no
  argument-permutation explosion. Hoogle spends real effort on this; Mezze does
  not have the problem.
- **Ranking.** Exact type, then subsumption distance, then name match, then doc
  match. `searchDeclsByName` today is `LIKE` with `ORDER BY decl_name LIMIT 200`
  — no relevance model at all.

The unifier already exists in the typechecker. Type-directed search is
"normalise, index, then unify candidates against the query", and the second half
is solver work, not string work. Budget it as such.

## 2. Effects and abilities are part of the signature

A Mezze signature is not just shape — it carries an effect row and ability
constraints. `{path: Str} -> Config <FileSystem, Exception>` and
`{path: Str} -> Config` are different answers to "what can I call here".

So the index needs effects and constraints as **first-class filterable
dimensions**, not text inside a rendered signature:

- "functions that need no effects" (safe to call anywhere)
- "functions that can throw"
- "everything requiring `Ord 'a`"

Today `getEffectPerformers(effectHash)` answers "who performs this effect" and
needs a hash, not a name. There is no "find every declaration whose row includes
`FileSystem`".

## 3. The store holds no source text

`modules` is `(path, file_path, source_hash)` — there is no source column. To
*browse* code, a web app needs the text, and reading `file_path` from disk works
for project source but is questionable for the stdlib, which is materialised
from a JAR resource, and for deps under `.deps/`.

Decide explicitly: serve source by reading `file_path`, or store the source
blob. The second costs space and makes the browser self-contained; the first is
free until it isn't.

## 4. Samples belong in the store, not recomputed

"Ask to generate samples" implies a live runtime behind every page view. That
makes the browser slow, stateful, and unavailable when no runtime is attached.

Better: **cache sample results keyed by declaration hash**. The hash is content
addressed and excludes formatting, so a cached sample is valid until the
function's *meaning* changes — exactly the right invalidation. Samples then
become durable documentation: the browser serves them instantly, regenerates on
demand, and an LLM reading the page sees concrete behaviour rather than only a
signature.

This is the strongest argument for the two substrates living in one server: the
doc browser needs query *and* eval at once.

## 5. Search by behaviour — the actual "on steroids"

Hoogle cannot do this because no Hoogle has a runtime attached. This platform
would: given input/output pairs, **run candidates and keep the ones that match**.

```
> find  {xs = [3,1,2]} -> [1,2,3]
  std::collections::seq::sort
  std::collections::seq::sort_by  {f = ...}
```

Type-directed search narrows the candidate set; the evaluator confirms. It needs
sandboxing and per-candidate timeouts (`[[project_anti_hang_invariant]]`) and
only works for pure candidates — which the effect row tells you for free, since
anything with a non-empty residual row is not safely runnable. That is the same
check Stage 1 of sample needs.

## 6. The API is MCP, not just HTTP

"An interface for an LLM to ask" has a concrete answer now: **MCP**. Naming it
avoids inventing a bespoke API and then writing an MCP adapter anyway.

The tool surface falls out of the above: `search_by_signature`,
`search_by_name`, `search_by_effect`, `get_decl`, `get_source`, `get_samples`,
`generate_samples`, `eval`. Two of those reach the evaluation substrate, which
is the same argument as §4.

HTTP still matters for the human browser. Both are adapters over one service
layer; neither should be the place logic lives.

## 7. Freshness

The store mirrors files on disk and is updated on save. A doc browser served
from it shows **stale content for unsaved buffers**, and shows no dependency
until `mez sync` has populated `deps.sqlite`.

For a docs site that is correct behaviour. For an editor-adjacent browser it is
surprising. Decide which one is being built; they want different answers.

## 8. Smaller, but real

- **Pagination.** `LIMIT 200` is hardcoded in `searchDeclsByName`. An API needs
  cursors.
- **Package provenance.** `DeclRow` has no package/version field. Results from
  `deps` cannot say which package or version they came from — needed the moment
  two dependencies export the same name.
- **Doc comments are unstructured.** `decls.doc` is text. A browser wants
  params, returns and examples as fields. `##` has no structure today.
- **Re-exports are invisible to the index.** `pub use mod::{ x }` is real
  (`ParsedPubUseDecl`), but it was fixed by resolving transitively at the
  CONSUMER's import lookup in `ImportResolver` — deliberately *not* in the eager
  export builder, because that runs before a module's dependencies are resolved
  and pulling them in there risks re-entrant resolution. Consequence for search:
  a module's stored exports hold only its **own** declarations, so a re-exported
  name is not indexed under the path you would actually import it from.
  Answering "where can I import this from?" means walking `pub use` chains, and
  that walk does not exist outside import resolution.

## Revised order

The signature index is still first, but it is three things, not one:

1. **Normalise + store signatures** — alpha-normalised type, effect row,
   ability constraints, written alongside `putDecl`.
2. **Unification-based query + ranking** — reuse the typechecker's unifier.
3. **Package provenance + pagination** — cheap, but painful to retrofit into a
   published API.

Then source serving (§3), then sample caching (§4) once eval exists, then the
MCP/HTTP adapters (§6). Search-by-behaviour (§5) last: it needs everything else,
and it is the one nobody else can offer.
