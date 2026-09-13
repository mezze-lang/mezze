# polyglot_numpy_js_chart — Mezze × NumPy × asciichart

A tiny end-to-end pipeline that shows off the *polyglot-as-a-package*
model: two provider packages (`mezze-python`, `mezze-js`) declared in
`Mezze.toml` compose into one Mezze program that hosts both GraalPy
and GraalJS in the same JVM, driven by ordinary Truffle interop.

## What it does

1. **NumPy side** — draws 1 000 samples from `N(0, 1)` (seed = 42),
   computes `mean` and `std`, and bins the samples into a 20-bucket
   `numpy.histogram`. The whole tuple is serialised to a compact
   JSON string on the Python side.
2. **JS side** — GraalJS `require('asciichart')` — the CommonJS
   module is provided by an npm install run by `mez sync` — and
   `asciichart.plot(counts, { height: 12 })` renders the histogram
   to a Unicode line chart. The chart string is handed back to
   Mezze, which prints it.

The two ends are wired together with **one string**: JSON is a
subset of JavaScript object literal syntax, so `stats = <JSON>` in
the JS eval source is legal and needs no parser on the guest side.

## Run

```
# clean-machine bootstrap — 26 maven artifacts (via Aether), 2
# provider packages fetched from GitHub, 1 npm install (asciichart):
devenv shell -- bash -c "cd examples/polyglot_numpy_js_chart && \
    ../../cli/build/install/mez/bin/mez sync"

# then run — reduces + charts in ~1 s on the JVM:
devenv shell -- bash -c "cd examples/polyglot_numpy_js_chart && \
    ../../cli/build/install/mez/bin/mez exec src/app.mz"
```

Expected stdout (seed = 42 → reproducible byte-for-byte):

```
== Mezze x NumPy x asciichart — polyglot pipeline ==

[1] numpy: sampling N(0, 1), 1000 points, seed=42
    mean = -0.028891550995946837
    std  = 0.988722355396508
    histogram: 20 bins (147-byte JSON payload)

[2] asciichart (via GraalJS `require('asciichart')`):

     160.00 ┼          ╭╮
     146.67 ┤          ││
     ...
       0.00 ┼──╯             ╰──
```

## Prerequisites

- **numpy in a GraalPy venv** — GraalPy 25.0.3 does not carry numpy
  by default, and its Meson build is memory-hungry. In this repo
  `just polyglot-setup-python` primes a shared venv at
  `interpreter/build/graalpy-venv/venv/`; `Mezze.toml` points
  `python_path` at that venv's site-packages. `mez sync` will also
  attempt a per-project `graalpy -m pip install --target .deps/python
  numpy` from the `mezze-python` provider template — that succeeds
  only on machines with `graalpy` on `PATH`, so the `python_path`
  fallback is what makes the demo work on this dev environment.
- **asciichart via npm** — installed automatically by `mez sync`
  under `.deps/node/node_modules/node_modules/`. `[polyglot.js]
  node_modules` points GraalJS's CommonJS resolver at that
  directory.

## Files

- `Mezze.toml` — two `[dependencies]` + two `[polyglot.<lang>]`
  sections. The dependency keys use the underscore form
  (`mezze_python`, `mezze_js`) because the module resolver looks
  for `.deps/mezze/<key>-*/`, and consumer code writes
  `use mezze_python::...` / `use mezze_js::...`.
- `src/app.mz` — one `main` that performs `Console`, `GraalPy`, and
  `GraalJs` and drives the pipeline. Every op returns
  `Result <Lang>Foreign InteropError`; failures map to a single
  labelled `println`.
