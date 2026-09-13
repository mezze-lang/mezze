package com.mezze.polyglot.wasm;

import com.mezze.interpreter.nodes.builtin.NativeDispatch;
import com.mezze.interpreter.spi.NativePlugin;

/**
 * ServiceLoader-discovered plugin for the `mezze-wasm` polyglot
 * provider package. Registers WebAssembly-specific native handlers
 * with the interpreter's dispatch table:
 *
 *   - {@code __native_wasm_load_module}
 *   - {@code __native_wasm_load_file}
 *   - {@code __native_wasm_get_export}
 *   - {@code __native_wasm_memory_read}
 *   - {@code __native_wasm_memory_write}
 *   - {@code __native_wasm_memory_size}
 *   - {@code __native_wasm_from_int}
 *   - {@code __native_wasm_from_float}
 *
 * The interpreter's built-in {@code registerPolyglotHandlers()} still
 * registers these names against its own in-tree {@code WasmNatives}
 * during Phase 6 (this rollout). Since {@link NativeDispatch#overrideHandler}
 * runs after built-in registration during class-load-time SPI discovery,
 * the plugin wins — the same handler names route through this jar's
 * copy of the natives. Both paths produce byte-identical behaviour.
 * Phase 7 removes the in-tree registration and this plugin becomes the
 * sole source of these handlers.
 *
 * Loaded by ServiceLoader via
 * {@code META-INF/services/com.mezze.interpreter.spi.NativePlugin}.
 */
public final class PolyglotWasmPlugin implements NativePlugin {
    /** Public no-arg constructor required by {@link java.util.ServiceLoader}. */
    public PolyglotWasmPlugin() {}

    @Override
    public void register() {
        NativeDispatch.overrideHandler("__native_wasm_load_module",
            (record, callNode) -> WasmNatives.loadModule(record, callNode));
        NativeDispatch.overrideHandler("__native_wasm_load_file",
            (record, callNode) -> WasmNatives.loadFile(record, callNode));
        NativeDispatch.overrideHandler("__native_wasm_get_export",
            (record, callNode) -> WasmNatives.getExport(record));
        NativeDispatch.overrideHandler("__native_wasm_memory_read",
            (record, callNode) -> WasmNatives.memoryRead(record));
        NativeDispatch.overrideHandler("__native_wasm_memory_write",
            (record, callNode) -> WasmNatives.memoryWrite(record));
        NativeDispatch.overrideHandler("__native_wasm_memory_size",
            (record, callNode) -> WasmNatives.memorySize(record));
        NativeDispatch.overrideHandler("__native_wasm_from_int",
            (record, callNode) -> WasmNatives.fromInt(record));
        NativeDispatch.overrideHandler("__native_wasm_from_float",
            (record, callNode) -> WasmNatives.fromFloat(record));
    }

    @Override
    public String name() {
        return "PolyglotWasmPlugin (mezze-wasm provider)";
    }
}
