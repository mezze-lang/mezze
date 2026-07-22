# Shared parser corpus

Each `*.mz` file here is a list of **valid** Mezze snippets, one per block,
separated by a line that is exactly `# ===` (optionally followed by a label).
Blank lines inside a block are part of the snippet.

Two consumers read these files, and both must agree:

1. **The Kotlin parser** — `SharedCorpusParityTest` (in `:parser:test`) reads
   every block and asserts `MezzeParser.parseParsedModule` accepts it. This is
   what makes the parser the *source of truth*: a snippet only belongs here if
   the real parser parses it.

2. **The tree-sitter grammar** — `tooling/tree-sitter-mezze/scripts/parity.sh`
   reads the same blocks and runs each through `tree-sitter parse`, failing on
   any `ERROR`/`MISSING` node. This is the CI gate that keeps the grammar in
   sync.

To add coverage, add a block here. If the Kotlin test rejects it, the snippet
isn't valid Mezze — fix the snippet. If the parity script rejects it, the
grammar has a gap — fix `grammar.js`.

The block delimiter is `# ===` because `#` starts a line comment in Mezze, so
a delimiter line is also a no-op comment — a whole fixture file is itself a
parseable Mezze module (handy for a quick `tree-sitter parse fixtures/x.mz`).
