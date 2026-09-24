# Debugging in Mezze

## Two Layers

### Layer 1: Standard DAP Debugging (Truffle)

GraalVM Truffle provides breakpoint/stepping debugging via the Debug Adapter Protocol (DAP). Once wired up, this gives standard debugging in VSCode, Neovim, etc. — set a breakpoint, step through, inspect variables. This requires:

- SourceSection on all AST nodes (currently only on root nodes)
- InstrumentableNode implementation
- @ProvidedTags and StandardTags
- DAP server integration

This is table stakes. Every language should have it. (The old cross-reference here was `docs/architecture-v2/09-truffle-tooling.md`; `docs/` was deleted in 3f22f5cd2 and that file no longer exists anywhere in the repo.)

### Layer 2: Trace + Live Edit (Correctness Tooling)

Built on top of the interpreter as a recording layer. Not live debugging — run once, record everything, explore the recording. This is the novel part.

## Trace — Recorded Execution Browsing

### From Sample to Trace

The developer sees a sample result that looks wrong and digs into it:

```
$ mez sample module::clamp

  1. {val = 0,  min = 0,  max = 0}   -> 0
  2. {val = 5,  min = 1,  max = 10}  -> 5
  3. {val = -3, min = 0,  max = 10}  -> 0
  4. {val = 15, min = 0,  max = 10}  -> 10
  5. {val = 1,  min = 5,  max = 3}   -> 5       <- wrong?

> d 5
```

### Full Trace View

The entire execution is shown at once:

```
clamp {val = 1, min = 5, max = 3}

  [1] val < min        => 1 < 5        => True
  [2] if True then min => 5
  -> 5

  Bindings: {val = 1, min = 5, max = 3}
```

### Navigating the Trace

Jump to any step by number:

```
> 1
  [1] val < min  => 1 < 5  => True
  bindings: {val = 1, min = 5, max = 3}

> 2
  [2] if True then min  => 5
  bindings: {val = 1, min = 5, max = 3}
```

Since Mezze is immutable, every step's state is just the accumulated `let` bindings up to that point. There's nothing to undo — you just show what was in scope at each step. The full trace is computed once and you browse it freely.

### Step-Into for Function Calls

By default, function calls show their result but don't expand. To go deeper:

```
> s 1
Step into: items.fold {init = 0, f = {acc, x} -> acc + x}

  1.1: acc = 0, x = 49  -> acc + x -> 49
  1.2: acc = 49, x = 51 -> acc + x -> 100
  => 100
```

### Longer Trace Example

```
$ mez sample module::categorize

  {scores = [49, 51]}  -> "pass"

> d 3
categorize {scores = [49, 51]}

  [1] scores.fold {init = 0, f = ...}  => 100
  [2] scores.length {}                  => 2
  [3] 100 / 2                           => 50
  [4] 50 >= 90                          => False
  [5] 50 >= 70                          => False
  [6] 50 >= 50                          => True
  [7] "pass"                            => "pass"
  -> "pass"
```

### Why Mezze Makes This Easy

- **Immutable by default** — every step produces a new value, nothing is overwritten, so the trace is a simple list
- **Expression-based** — everything returns a value, so every step has something to show
- **Pure functions** — no hidden state changing between steps
- **Named fields** — trace reads naturally: `val = 1, min = 5` not `arg0 = 1, arg1 = 5`

## Pipeline-Aware Tracing

When the debugger hits a dot chain or pipe chain, it offers chain-aware navigation.

### Step Over Chain (see all intermediates at once)

```
Breakpoint at line 5

  Variables:
    users = [{alice, eng, True}, {bob, eng, False}, {carol, sales, True}]

  Step over chain? [y/n]
> y

  Pipeline:
    users           -> [{alice, eng, True}, {bob, eng, False}, {carol, sales, True}]
    .filter         -> [{alice, eng, True}, {carol, sales, True}]
    .group_by       -> {"eng": [{alice, ...}], "sales": [{carol, ...}]}
    .map_values     -> {"eng": ["alice"], "sales": ["carol"]}

  Now at line 9 (after chain)
```

### Step Through Chain (one call at a time)

```
> step into chain

  [chain 1/4] users
    -> [{alice, eng, True}, {bob, eng, False}, {carol, sales, True}]

> n

  [chain 2/4] .filter {p = {u} -> u.is_active}
    -> [{alice, eng, True}, {carol, sales, True}]

> n

  [chain 3/4] .group_by {f = {u} -> u.department}
    -> {"eng": [{alice, eng, True}], "sales": [{carol, sales, True}]}

> n

  [chain 4/4] .map_values {f = {vs} -> vs.map {f = {v} -> v.name}}
    -> {"eng": ["alice"], "sales": ["carol"]}
```

Works identically for pipe chains:

```
> step into chain

  [chain 1/3] map::empty {}
    -> {}

> n

  [chain 2/3] |> map::insert {m = @, k = "a", v = 1}
    -> {"a": 1}

> n

  [chain 3/3] |> map::insert {m = @, k = "b", v = 2}
    -> {"a": 1, "b": 2}
```

Both `.` and `|>` chains are first-class concepts in the debugger — it recognizes them as pipelines and gives chain-aware navigation instead of forcing you through individual AST nodes.

## Live Edit — Replace Expressions, Replay From That Point

### The Idea

The developer sees a wrong result in a trace. Instead of going back to the editor, changing code, recompiling, rerunning — they edit the expression right there and see how the result changes instantly.

### Type Safety Rule

**You can replace any expression with another expression of the same type.** The typechecker enforces this. If the edit doesn't typecheck, it's rejected immediately.

```
> edit 1
  Current: if val < min then min    # type: Int
  New: if val < min then 0          # type: Int  ✓ accepted

> edit 1
  Current: if val < min then min    # type: Int
  New: if val < min then "nope"     # type: Str  ✗ rejected — expected Int, got Str
```

The edit must also typecheck in the same scope as the original expression. You can use any bindings available at that point — function params, earlier `let` bindings, imports — but nothing that wasn't in scope. You can introduce new bindings with `let` expressions since `let` is an expression in Mezze.

### How Replay Works

Everything before the edit is frozen — those values are facts. Everything from the edit onward is recomputed with the new expression.

```
> d 5
clamp {val = 1, min = 5, max = 3}

  [1] val < min        => True
  [2] if True then min => 5
  -> 5

> edit 2
  Current: min
  New: if min <= max then min else val

  Replaying from step 2 with edit...
  [1] val < min              => True            (kept — before edit)
  [2] min <= max             => 5 <= 3 => False (new code)
  [3] else val               => 1               (new code)
  -> 1
```

This works because of immutability. Step 1 produced `True`. That's a value. It doesn't change. The bindings at that point are immutable. Replaying from step 2 with new code is just: take the bindings from step 1, evaluate the new expression, continue.

### Rerun All Samples

After an edit, the tool reruns all sample cases and shows what changed:

```
  Rerunning all samples with edit...
  {val = 0,  min = 0,  max = 0}   -> 0   (was 0)
  {val = 5,  min = 1,  max = 10}  -> 5   (was 5)
  {val = -3, min = 0,  max = 10}  -> 0   (was 0)
  {val = 15, min = 0,  max = 10}  -> 10  (was 10)
  {val = 1,  min = 5,  max = 3}   -> 1   (was 5) CHANGED
```

### Editing Any Expression in a Pipeline

Every subexpression has an inferred type and can be swapped:

```mezze
pub let process = {items: [Int], threshold: Int} -> [Str]
  items                                    # type: [Int]
    .filter {p = {x} -> x > threshold}     # type: [Int]
    .map {f = {x} -> x.to_str {}}          # type: [Str]
```

```
> edit filter
  Current: {x} -> x > threshold        # type: {x: Int} -> Bool
  New: {x} -> x >= threshold            # type: {x: Int} -> Bool  ✓

  Replaying from filter...
  [1] items                                    -> [3, -1, 0, 5]     (kept)
  [2] .filter {p = {x} -> x >= threshold}      -> [3, 0, 5]         CHANGED
  [3] .map {f = {x} -> x.to_str {}}            -> ["3", "0", "5"]   CHANGED (consequence)
  -> ["3", "0", "5"]
```

### Multiple Edits Stack

```
> edit 1
  New: {x} -> x >= 0
  ...replays...

> edit 3
  Current: {x} -> x.to_str {}
  New: {x} -> "num:$(x)"

  Replaying (edits 1 and 3 applied)...
  [1] .filter {p = {x} -> x >= 0}             -> [3, 0, 5]                    (edit 1)
  [2] .map {f = {x} -> x * 2}                 -> [6, 0, 10]                   (original)
  [3] .map {f = {x} -> "num:$(x)"}            -> ["num:6", "num:0", "num:10"] (edit 3)
  -> ["num:6", "num:0", "num:10"]

  Active edits: [1, 3]
> apply
  Both edits written to module.mz
```

Edits accumulate. The developer builds up a fixed version of the function interactively, seeing each change's effect immediately. When satisfied, `apply` writes all edits to the actual file. `discard` throws them away.

### LSP Experience

In the editor during a sample/debug session:

1. Click on any expression in the code
2. The editor highlights it and shows its type
3. An inline edit field appears
4. As you type, it typechecks live — green border if valid, red if type mismatch
5. On enter, samples rerun and results update in the panel
6. Apply to save, escape to discard

### Implementation

When the user edits step N:

1. Take the bindings (all `let` values) accumulated up to step N-1
2. Parse and typecheck the new expression in that scope
3. Compile just that expression to a Truffle node
4. Re-execute from step N forward with those bindings and the same inputs
5. Show the new trace from N onward, plus rerun all samples

Since the interpreter already compiles individual expressions and the bindings are just a map of name to value, this is straightforward — inject bindings into the frame and evaluate.

## Provenance — Backward Data Flow

Instead of forward trace (step 1, 2, 3...), trace backward from a value to its origin. Click on any value in a result and ask "where did this come from?"

```
$ mez sample module::process

  {user = {name = "alice", role = "admin"}}  -> "WELCOME ALICE"

> why "ALICE"
  "ALICE" came from:
    str::to_upper {s = "alice"}
      "alice" came from:
        user.name
          user = {name = "alice", role = "admin"} (input)
```

Follows the value backward through every transformation to the original input. Useful for complex pipelines where data flows through many functions.

## Contract Inference

After running many samples, the tool notices patterns and proposes contracts:

```
$ mez sample module::sort --cases 50 --infer

  Inferred properties (held for all 50 samples):
    - result.length {} == xs.length {}          (preserves length)
    - result.all {p = {x} -> xs.contains {x}}  (same elements)
    - result == result.sort {}                   (output is sorted)

  Accept as contracts? [y/n]
```

If accepted, saved as annotations. Future runs of `mez sample` check them automatically. Contracts are discovered from behavior, not written by hand.

## Feature Summary

| Feature | What it does | When to use |
|---|---|---|
| **Trace** | Browse full execution, jump to any step | "How did it get this result?" |
| **Pipeline view** | Show intermediate values at each chain step | Dot chains and pipe chains |
| **Live edit** | Replace expression, replay from that point | "What if I change this?" |
| **Provenance** | Trace a value backward to its origin | "Where did this value come from?" |
| **Contract inference** | Discover properties from many samples | Regression safety net |
