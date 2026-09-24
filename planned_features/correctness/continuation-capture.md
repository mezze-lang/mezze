# Continuation Capture — Live Replayable Coredumps

## The Problem

When a bug happens in production, CI, or a test, you get a stack trace. A stack trace is **dead information** — it tells you where the crash happened but not what the values were, not what path the code took, not what would have happened if something was different.

Coredumps capture more (full process memory) but are huge, opaque, language-unaware, and not replayable. You can poke at them with gdb but you can't step forward, change code, or re-execute.

## The Idea

When a crash or unexpected result happens, capture the **entire continuation** — the full computation state — and save it as a portable artifact. Later, the developer loads it up, steps through it, inspects values, tries live edits. A coredump you can replay.

## Why Mezze Can Do This

Mezze already has delimited continuations in the runtime for effects. The machinery exists. And because of Mezze's design, a captured continuation is self-contained:

- **Immutable values** — bindings are just values, no pointers to mutable heap state to clone
- **No hidden state** — effects are explicit, no global mutable variables
- **Content-hashed functions** — function references are stable hashes, not memory addresses
- **Named fields** — captured bindings are human-readable (`items = [-3, -1, -5]` not `arg0 = 0x7fff...`)

A continuation capture contains:
- All bindings in scope at each step (immutable values)
- The execution trace (which expressions evaluated to what)
- The effect handler stack
- The code position (module, line, expression)
- Function references (content hashes) for any closures

This is small, portable, and self-contained. No raw memory, no debugger symbols, no platform-specific binary format.

## How It Works

### Capture on crash

An effect handler that wraps Exception and saves state on throw:

```mezze
# Wrap with capture handler
perform CaptureOnCrash {path = "crashes/"} in do
  process {items = get_items {}}
```

When an exception happens, instead of just logging a stack trace, it writes a `.cont` file with the full computation state.

### Capture in sample

Sample automatically captures continuations for crashes:

```
$ mez sample module::process

  {items = [-3, -1, -5]}  -> CRASH: division by zero

  Continuation saved: .mezze/captures/process-001.cont

> replay
```

### Replay and debug

```
$ mez replay .mezze/captures/process-001.cont

Captured: module::process crashed at line 5
  Exception: division by zero

  Bindings at crash:
    items    = []
    filtered = []
    total    = 0
    count    = 0

  Trace:
    [1] items.filter {p = {x} -> x > 0}    => []
    [2] filtered.fold {init = 0, f = ...}   => 0
    [3] items.length {}                      => 0
    [4] total / count                        => CRASH (0 / 0)
```

The developer sees the full execution with all concrete values. They can jump to any step, inspect bindings, understand exactly what happened.

### Live edit on captured continuation

Since live edit works in the debugger, it works on replayed continuations too:

```
$ mez replay .mezze/captures/process-001.cont

  [3] items.length {}  => 0

> edit 3
  Current: items.length {}
  New: filtered.length {}

  Replaying from step 3...
  [3] filtered.length {}   => 0
  [4] total / count        => 0 / 0 => CRASH

  Still crashes. Need to handle empty:

> edit 4
  Current: total / count
  New: if count == 0 then 0 else total / count

  Replaying from step 4...
  [4] if 0 == 0 then 0     => 0
  [5] 0.to_float {}        => 0.0
  -> 0.0

> apply
  Edits written to module.mz
```

The developer fixed the bug by experimenting on the captured continuation, without ever reproducing the crash.

### Share captures

The continuation file IS the bug report. It contains everything needed to understand and fix the issue.

```bash
# CI hits a bug, saves the continuation
# Developer downloads it and replays locally

$ mez replay ci-artifacts/crash-42.cont

# Full debugging with all runtime values
# No need to reproduce the bug
# No need to figure out what inputs caused it
# The exact computation state is right there
```

No more "works on my machine." No more "can you reproduce this?" The `.cont` file is portable — anyone with the same codebase can replay it.

## Comparison to Coredumps

| | Coredump | Continuation Capture |
|---|---|---|
| **Size** | Huge (entire process memory) | Small (bindings + code positions) |
| **Format** | Raw bytes, platform-specific | Structured Mezze values, portable |
| **Readable** | Needs debugger symbols + gdb | Human-readable named fields |
| **Replayable** | No — inspect only | Yes — step forward from any point |
| **Editable** | No | Yes — change code, replay, see different result |
| **Shareable** | Platform-dependent, huge files | Small, portable, works on any machine |
| **Language-aware** | No — C-level memory layout | Yes — knows about records, variants, effects |

## Integration with Sample

Sample and continuation capture work together naturally:

1. `mez sample` runs a function on auto-generated inputs
2. If a case crashes, the continuation is captured automatically
3. The developer types `replay` to enter the debugger for that crash
4. They browse the trace, try live edits, find the fix
5. `apply` writes the fix to the source file
6. Rerun sample to verify all cases pass

## What Needs to Be Built

### Runtime: CaptureOnCrash effect handler

A new effect handler that wraps Exception. On throw, it serializes the current continuation state to disk. This builds on the existing continuation machinery used for effects.

### Serialization

All MezzeValues need serialization to/from a portable format:
- Primitives (Int, Float, Str, Bool, etc.) — straightforward
- Records, variants, seqs, maps, sets — structural serialization
- Function values (closures) — serialize as content hash + captured bindings
- Effect handler state — serialize scope values

### Replay engine

Load a `.cont` file, reconstruct the execution state, provide the trace/debug/live-edit interface. Reuses the same debugging infrastructure as the trace feature in `mez sample`.

## What Makes This Novel

Most post-mortem debugging tools let you inspect state. This lets you **re-execute and edit**. The combination of:

- Immutable values (no heap to clone)
- Content-hashed functions (stable references)
- Delimited continuations (computation state is already a first-class concept)
- Live edit with type-safe replay

...means the captured computation isn't a dead snapshot — it's a frozen computation you can thaw, modify, and continue. That's genuinely new.
