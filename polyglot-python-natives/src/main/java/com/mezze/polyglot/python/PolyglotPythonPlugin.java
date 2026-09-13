package com.mezze.polyglot.python;

import com.mezze.interpreter.nodes.builtin.NativeDispatch;
import com.mezze.interpreter.spi.NativePlugin;

/**
 * ServiceLoader-discovered plugin for the `mezze-python` polyglot
 * provider package. Registers Python-specific native handlers with
 * the interpreter's dispatch table:
 *
 *   - {@code __native_python_eval}
 *   - {@code __native_python_import_module}
 *
 * The interpreter's built-in {@code registerPolyglotHandlers()} still
 * registers these names against its own in-tree {@code PythonNatives}
 * during Phase 2 (this pilot). Since {@link NativeDispatch#overrideHandler}
 * runs after built-in registration during class-load-time SPI discovery,
 * the plugin wins — the same handler names route through this jar's
 * copy of the natives. Both paths produce byte-identical behaviour.
 * Phase 6 removes the in-tree registration and this plugin becomes the
 * sole source of these handlers.
 *
 * Loaded by ServiceLoader via
 * {@code META-INF/services/com.mezze.interpreter.spi.NativePlugin}.
 */
public final class PolyglotPythonPlugin implements NativePlugin {
    /** Public no-arg constructor required by {@link java.util.ServiceLoader}. */
    public PolyglotPythonPlugin() {}

    @Override
    public void register() {
        NativeDispatch.overrideHandler("__native_python_eval",
            (record, callNode) -> PythonNatives.eval(record, callNode));
        NativeDispatch.overrideHandler("__native_python_import_module",
            (record, callNode) -> PythonNatives.importModule(record, callNode));
    }

    @Override
    public String name() {
        return "PolyglotPythonPlugin (mezze-python provider)";
    }
}
