# polyglot_python_pure — Mezze × Python stdlib demo

The bare-minimum polyglot example. Uses only Python's built-in
`math`, `statistics`, and `functools` — no third-party packages, no
`python_path`, no venv setup. GraalPy ships the stdlib inside its
own resource jars, so this demo works end-to-end on any machine
where mez is installed.

## Why this demo exists

`polyglot_numpy` requires a working GraalPy venv with a compiled
numpy install — see its README for the ~25 min bootstrap. That
demo is the "real workload" proof of the polyglot surface. This
demo is the "does polyglot work at all" proof — good for smoke
testing and for confirming that `mez build --native` correctly
wires `[polyglot.python]` into the produced binary.

## Run under `mez exec` (JVM, ~1s)

```
just mez-run-python-pure-demo
```

## Run as a native binary (`mez build --native`)

```
just native-python-pure-demo
```

The recipe:
1. `mez build --native` on this project. Because `[polyglot.python]`
   is declared, native-image builds with `--language:python`, and
   GraalPy is baked into the resulting binary.
2. Runs `examples/polyglot_python_pure/.build/polyglot_python_pure-native`.

The binary is relocatable — `cp` it to any directory and it still
runs. No venv sidecar is needed because this demo uses only the
Python stdlib (which lives inside the binary itself).

## What's demonstrated

- `[polyglot.python]` alone opts into GraalPy — no `python_path`.
- `math` / `statistics` / `functools` — Python stdlib callable
  from Mezze through the generic `Foreign` surface.
- Floats and ints round-trip: Python computes, coerces to `Float` /
  `Int`, Mezze formats and prints.
- Same code path as `polyglot_numpy` — the `Foreign` surface
  doesn't know or care whether the callee is stdlib or a wheel.

## Native binary state

Verified working (see the top-level session log). The pure-Python
demo builds and runs at native without additional caveats. numpy at
native remains blocked by upstream GraalPy issues — see
`examples/polyglot_numpy/README.md` for that story.
