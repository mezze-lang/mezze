# `mez lint`: built-in rules, and rules written in Mezze

**Status:** design agreed, not implemented. Deferred until the syntax tree it builds
on has been used for a while.

## What it is

A separate command, `mez lint`, that reports lint findings as warnings or errors.

- **Built-in rules** ship with `mez`. Each one can be turned off.
- **Rules written in Mezze** extend it. A project can have its own, and a library can
  ship rules for its users.

`mez check` is not involved, and never will be. `check` and the editor never run user
code: that is what keeps a broken or hostile `comptime` from hanging an editor or
touching files while you type. A rule written in Mezze is user code, so it runs only
when someone asks for it, the way `comptime` runs only in build, dev and exec.

## Rule codes

Every rule has a code, with the same shape as the compiler's diagnostic codes.

| Rules | Code | Example |
|---|---|---|
| Built-in | `L` and four digits | `L0001` |
| A project's or library's | the package name, `::`, then `L` and four digits | `shapes::L0001` |

The `L` keeps lint rules apart from compiler warnings (`W0501`). The package prefix
keeps two libraries from claiming the same code.

## Registering a rule: `Mezze.toml`

A rule is registered by its code, against the function that implements it. Every
rule's level is set in one table, built-in rules and compiler warnings included.

```toml
[lint.rules]
"shapes::L0001" = "shapes::lints::float_eq"

[lint.levels]
"shapes::L0001" = "warn"   # off | warn | error
"L0003" = "off"            # a built-in rule
"W0501" = "error"          # a compiler warning, through the same table
```

## The rule signature

Every rule has one type, so the runner calls them all the same way:

```mezze
{ decl: SyntaxDecl } -> [Finding] <Codebase>

pub type Finding = { span: Span, message: Str, fix: Option Fix }
```

- **One declaration at a time.** The runner walks the project, and skips declarations
  that have not changed since the last run.
- **`<Codebase>`.** A rule can still ask wider questions, such as whether a type's
  `Eq` is derived.
- **No code on a `Finding`.** The code comes from `Mezze.toml`, so a rule cannot report
  under a name it was not registered with.

A rule reads what was written and what the compiler made of it through `std::syntax`:
the syntax tree, the `facts` on each node, `all_exprs`, and `Codebase.occurrences`.

```mezze
## `==` on `Float`: compare with a tolerance instead.
let float_eq: { decl: SyntaxDecl } -> [Finding] <Codebase> = { decl } ->
  seq::flat_map { xs = all_exprs { decl = decl }, f = { x } -> match x is
    BinaryExpr { op = "==", left, span, ... } -> match facts_of { e = left } is
      Some { val = { ty = TFloat, ... } } -> [{ span = span, message = "`==` on Float; compare with a tolerance", fix = None }]
      _ -> []
    _ -> [] }
```

## Turning rules off locally: `#{ … }`

Metadata is for switching rules off and on where they apply, never for declaring
them. It already attaches to the three places this needs:

```mezze
#{ allow: ["shapes::L0001"] }          # a whole declaration
let approx = { a: Float, b: Float } -> do
  #{ allow: ["L0003"] }                # one statement: a line
  let close = a == b
  match close is
    #{ allow: ["shapes::L0001"] }      # one match arm: a section
    True -> 1
    False -> 0
```

## The editor

- **Built-in rules** are the compiler's own code, so the editor can run them live, as
  it already shows W0501.
- **Rules written in Mezze** are user code. The editor runs them on save, behind an
  opt-in setting (as rust-analyzer runs Clippy on save), or it shows the findings of the
  last `mez lint` run without running anything itself.

Either way a finding is an ordinary diagnostic, with its fix as a code action.

## Still open

- **`Fix`:** replacement text for one span (simple, enough for most rules), or edits
  across several spans.
- **The first built-in rules.**
- **`facts_of`:** reaching a node's facts is a `match` over every kind today; a helper
  belongs in `std::syntax`.
