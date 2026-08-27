# Mezze in the browser — working WASM build

`globalThis.runMezze(source)` → program output (or the compiler diagnostic) as a
string. Verified end to end:

```
[arbitrary#1] (747ms) hello from a web page
[arbitrary#2] (127ms) sq(12) = 144
[type-error ] ( 30ms) TYPE ERROR: Cannot use '+' with Str, Int. To enable this,
                      implement: impl NumAdd for Str where { ... }
                      Available impls: impl NumAdd for Int, Int; ...
```

Parse, typecheck, lower, Bytecode-DSL compile, and execute — all in WebAssembly,
no server.

## Using it from a page

```html
<script type="module">
  await import('./mezzeweb.js');       // installs globalThis.runMezze
  const out = runMezze(`
use std::effects::log::{ Console, println }
let main = {} -> perform Console in println { msg = "hi" }
`);
  document.querySelector('#out').textContent = out;
</script>
```

Serve `mezzeweb.js` + `mezzeweb.js.wasm` side by side.

## Size and speed

| | |
|---|---|
| `mezzeweb.js` (wrapper) | 112 KB |
| `mezzeweb.js.wasm` | 58 MB raw / **15.9 MB gzipped** |
| first `runMezze` | ~750 ms (stdlib warm-up) |
| subsequent runs | **~110-130 ms** |
| typecheck-only error | ~30 ms |

Build once, serve as a static asset. Per-run cost is ~110 ms — the 2.5-minute
figure is the *build*, which happens at deploy time, not per program.

Serve the `.wasm` with gzip/brotli — uncompressed it is 58 MB. Consider a
loading state on first paint.

## Build

```sh
docker pull container-registry.oracle.com/graalvm/native-image:25i3   # Oracle GraalVM 25.3.4.1
# Binaryen >= 119 (linux-aarch64) on PATH inside the container

javac --add-modules org.graalvm.webimage.api -cp "$CP" -d classes src/MezzeWeb.java

native-image --tool:svm-wasm --macro:truffle-svm \
  -H:+UnlockExperimentalVMOptions \
  -H:+EnableFallbackCompilation \
  -H:IncludeResources=".*\.mz$|.*stdlib-snapshot\.cbor$" \
  --initialize-at-build-time=com.mezze,kotlin,kotlinx \
  -cp "<jars>:classes:snap:stdres:<subs>" MezzeWeb -o mezzeweb
```

Classpath needs:
- unshaded module jars (NOT `mezze-runtime.jar` — the shadow jar flattens the
  `org.graalvm.polyglot` module descriptor and native-image rejects it)
- `snap/` — `META-INF/mezze/stdlib-snapshot.cbor`, **matching the jars exactly**
- `stdres/` — stdlib `.mz` under a `stdlib/` prefix (`ModuleResolver` looks for
  `stdlib/std/...`, the jar stores `std/...`)
- the three substitutions in `src/`

## The three substitutions (Web Image bugs)

| file | works around |
|---|---|
| `AruSub.java` | `AtomicReferenceFieldUpdater.newUpdater` NPEs — breaks every `@Serializable` class AND `MezzeBytecodeRootNodeGen` |
| `SandboxSub.java` | Web Image emits **invalid WASM** for `SandboxInstrument.checkAstDepth` (the only invalid function out of 43,014) |
| lazy substitution | Kotlin `PUBLICATION` lazy (used by kotlinx-serialization codegen) |

All three are worth reporting to `oracle/graal`.

## Notes / gotchas

- `@JS.Export` is **not implemented** in Web Image. Use `@JS` to assign onto
  `globalThis` (see `MezzeWeb.install`).
- Strings must cross the boundary as `JSString`, not `java.lang.String` — a raw
  `Function<String,String>` fails with `Uncaught internal error: [Function: Java Proxy]`.
- Node needs `--experimental-wasm-exnref` (WebAssembly 3.0 exception handling).
  **Browser support for WASM 3.0 GC + exnref is NOT yet verified** — test before
  committing to this path.
- Effects: `Console` works (`System.out` → `console.log`). Filesystem and stdin
  need swapping to the existing `MemoryFilesystemNatives` / `MockStdinNatives`
  in `NativeDispatch`'s handler map. Network has no browser path. Async /
  Parallel / STM are untested (Loom).
