# polyglot_numpy — Mezze × NumPy demo

Mezze hands data to numpy, invokes numpy reductions and linear-algebra
ops, and receives real numeric answers back through the InteropLibrary.
No numpy-specific glue in the Mezze runtime — just the generic
`Foreign` surface from `std::polyglot::core`.

## Prerequisites — install numpy into a GraalPy venv

GraalPy 25.0.3 does not include numpy out of the box. numpy also
cannot be built from source under GraalPy today (the Meson AVX2
loop-generation pass fails). Install a prebuilt patched wheel via
the GraalPy patch repo:

```
# From the interpreter build tree, or your own venv:
$JAVA_HOME/bin/graalpy -m venv /path/to/venv
/path/to/venv/bin/graalpy -m ensurepip
/path/to/venv/bin/graalpy -m pip install 'numpy==1.26.4'
# then export or hand -Dpython.PythonPath=/path/to/venv/lib/python3.12/site-packages
```

The MezzeTestHelper auto-wires `python.PythonPath` to
`interpreter/build/graalpy-venv/venv/lib/python3.12/site-packages`
when that path exists, so `NumpyInteropTest` picks it up.

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
