package com.mezze.interpreter.spi;

import com.mezze.interpreter.nodes.builtin.NativeDispatch;

/**
 * Package-private smoke-test plugin exercising the {@link NativePlugin}
 * ServiceLoader discovery path. Registered via
 * {@code META-INF/services/com.mezze.interpreter.spi.NativePlugin}; verified
 * by {@code NativePluginTest} in the interpreter test suite.
 *
 * Registers a single unique native, {@code __native_test_plugin_ping},
 * that returns the string {@code "pong"}.
 */
public final class TestNativePlugin implements NativePlugin {
    /** Public no-arg constructor required by {@link java.util.ServiceLoader}. */
    public TestNativePlugin() {}

    @Override
    public void register() {
        NativeDispatch.overrideHandler("__native_test_plugin_ping", (record, callNode) -> "pong");
    }

    @Override
    public String name() {
        return "TestNativePlugin (interpreter SPI smoke test)";
    }
}
