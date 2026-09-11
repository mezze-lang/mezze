# polyglot_wasm — Mezze × WebAssembly

Mezze drives GraalWasm end-to-end: compile a hand-assembled wasm
module in-memory, call its exported functions, and marshal bytes
through its linear memory.

## Run

```
just mez-run-wasm-demo
```

or

```
mez exec examples/polyglot_wasm/src/app.mz
```

## What's different about wasm

Unlike the JS / Python / JVM polyglot demos, wasm's interop model is
narrow by design:

- **Only numeric primitives cross the value boundary** — i32, i64,
  f32, f64. There is no native representation of strings, bools,
  records, lists, or maps in the wasm value model.
- **Complex data lives in linear memory** — a wasm module owns a
  byte-addressable region (`memory`); the caller writes structured
  data into it and passes the integer offset to a wasm function.
  Modules that expose their own memory typically also expose an
  `alloc` / `dealloc` pair for buffer lifecycle.
- **Functions are namespaced exports** — every callable is a member
  of the module instance's `exports` namespace, keyed by name.
- **No source-level `eval`** — a wasm module is a compiled binary
  blob. The caller supplies the bytes (from a file, HTTP fetch,
  in-memory assembly, or an embedded literal like this demo).

The Mezze API mirrors those constraints. `std::polyglot::wasm` gives
you:

- `Wasm.load_module { bytes, name }` / `Wasm.load_file { path, name }`
  — compile + instantiate + return the exports namespace as a
  `WasmForeign`.
- `WasmForeign.get_export { name }` / `.call { args }` / `.call_export
  { name, args }` — look up + invoke exports.
- `WasmForeign.memory_read` / `.memory_write` / `.memory_size` — the
  byte-oriented linear-memory surface.
- `WasmForeign.as_int` / `.as_float` / `.is_null` / `.type_name` —
  the small subset of universal InteropLibrary operations that makes
  sense for wasm.
- `from_int` / `from_float` — lift a Mezze scalar to an i32 / f64
  argument.

## What this demo does

1. Assembles an 85-byte wasm module in memory (hex → `Bytes`), then
   loads it via `Wasm.load_module`. The module exports:
   - `add(i32, i32) -> i32`
   - `mul(i32, i32) -> i32`
   - `sub(i32, i32) -> i32`
   - `memory` (1 page = 64 KiB of linear memory)

2. Invokes each arithmetic export directly, then composes them:
   `mul(add(3, 4), sub(10, 3)) = 7 * 7 = 49`.

3. Grabs the `memory` export and writes the bytes for `"Hi, WASM!"`
   at offset 0, then reads them back to prove the round-trip.

## `[polyglot.wasm]`

Enabling wasm on a Mezze project is presence-only:

```toml
[polyglot.wasm]

[polyglot.wasm.options]
# Optional GraalWasm Context options — none required for basic use.
```

The runtime enables the `wasm` language on the Truffle Context iff
`[polyglot.wasm]` is present in the project's `Mezze.toml` (walked up
from the entry file's directory).
