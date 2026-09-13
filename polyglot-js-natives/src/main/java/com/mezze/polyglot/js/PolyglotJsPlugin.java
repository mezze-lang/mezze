package com.mezze.polyglot.js;

import com.mezze.interpreter.nodes.builtin.NativeDispatch;
import com.mezze.interpreter.spi.NativePlugin;

/**
 * ServiceLoader-discovered plugin for the `mezze-js` polyglot
 * provider package. Registers JS-specific native handlers with
 * the interpreter's dispatch table:
 *
 *   - {@code __native_js_eval}
 *   - {@code __native_js_import}
 *   - {@code __native_js_require}
 *
 * The interpreter's built-in {@code registerPolyglotHandlers()} still
 * registers these names against its own in-tree {@code JsNatives}
 * during Phase 4 (this rollout). Since {@link NativeDispatch#overrideHandler}
 * runs after built-in registration during class-load-time SPI discovery,
 * the plugin wins — the same handler names route through this jar's
 * copy of the natives. Both paths produce byte-identical behaviour.
 * Phase 6 removes the in-tree registration and this plugin becomes the
 * sole source of these handlers.
 *
 * Loaded by ServiceLoader via
 * {@code META-INF/services/com.mezze.interpreter.spi.NativePlugin}.
 */
public final class PolyglotJsPlugin implements NativePlugin {
    /** Public no-arg constructor required by {@link java.util.ServiceLoader}. */
    public PolyglotJsPlugin() {}

    @Override
    public void register() {
        NativeDispatch.overrideHandler("__native_js_eval",
            (record, callNode) -> JsNatives.eval(record, callNode));
        NativeDispatch.overrideHandler("__native_js_import",
            (record, callNode) -> JsNatives.importModule(record, callNode));
        NativeDispatch.overrideHandler("__native_js_require",
            (record, callNode) -> JsNatives.require(record, callNode));
    }

    @Override
    public String name() {
        return "PolyglotJsPlugin (mezze-js provider)";
    }
}
