# polyglot_numpy — Mezze × NumPy demo

Mezze hands data to numpy, invokes numpy reductions and linear-algebra
ops, and receives real numeric answers back through the InteropLibrary.
No numpy-specific glue in the Mezze runtime — just the generic
`Foreign` surface from `std::polyglot::core`.

## Prerequisites — install numpy into a GraalPy venv

GraalPy 25.0.3 does not ship numpy out of the box. numpy's Meson
build spawns a JVM per compilation task; the default parallel (-j)
setting exhausts memory during the AVX2 dispatch header generation
and the build is OOM-killed. Force the compile jobs to 1 and the
build takes ~25 minutes but succeeds — GraalPy's own patched
`numpy 1.26.4` sdist is applied automatically:

```
just polyglot-setup-python
```

That recipe expands to (from an initialised GraalPy launcher):

```
interpreter/build/graalpy-venv/venv/bin/graalpy -m pip install \
    --no-cache-dir \
    --config-settings=setup-args=-Dblas=none \
    --config-settings=setup-args=-Dlapack=none \
    --config-settings=compile-args=-j1 \
    'numpy==1.26.4'
```

`--config-settings=compile-args=-j1` is the key flag — it caps
Meson/ninja to a single compile job, which keeps peak memory well
under the ~27 GB machine limit. The BLAS/LAPACK opt-outs skip a
few third-party checks that GraalPy doesn't need.

The `MezzeTestHelper.runWithPythonIsolate` helper auto-wires
`python.PythonPath` to
`interpreter/build/graalpy-venv/venv/lib/python3.12/site-packages`
when that path exists, so `NumpyInteropTest` picks it up. It also
enables `python.IsolateNativeModules` so numpy's
`_multiarray_umath.so` can be loaded from more than one Context in
the same JVM.

## Run

```
just mez-run-numpy-demo
```

`just` will build the mez CLI if needed. The demo evaluates numpy
reductions (mean/sum/std), constructor helpers (`arange`), linear
algebra (`dot`), and element-wise arithmetic.

## What's demonstrated

- Import a Python module (`numpy`) into a polyglot Context declared
  by Mezze code — no Java glue.
- Round-trip Python floats through Mezze `Foreign` values, coerce
  back to Mezze `Float` with `.as_float{}`.
- Invoke numpy method dispatch (`.mean()`, `.sum()`) through
  `invoke_member` on a `Foreign`.
- Element-wise numpy addition + reduction over the result.
- Errors (missing modules, wrong methods) propagate as
  `Err InteropError` — the same surface every polyglot native uses.
