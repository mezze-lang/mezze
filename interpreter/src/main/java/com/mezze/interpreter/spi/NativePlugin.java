package com.mezze.interpreter.spi;

/**
 * Third-party native-handler pack.
 *
 * A {@code NativePlugin} implementation registers one or more record-ABI
 * native handlers with the interpreter's dispatch table by calling
 * {@link com.mezze.interpreter.nodes.builtin.NativeDispatch#overrideHandler}
 * from within {@link #register()}.
 *
 * Discovered via {@link java.util.ServiceLoader#load(Class)} during
 * {@code NativeDispatch}'s static initialization, immediately after the
 * built-in handlers have been registered. Implementations are declared in
 * {@code META-INF/services/com.mezze.interpreter.spi.NativePlugin}.
 *
 * Plugin registration runs once, at class-load time, before any guest code
 * is evaluated — handlers are cached per node once first hit, so overriding
 * a built-in name after execution starts is not guaranteed to take effect.
 */
public interface NativePlugin {
    /** Register handlers via NativeDispatch.overrideHandler(name, handler). */
    void register();

    /** Human-readable name for diagnostics. Defaults to the class name. */
    default String name() {
        return getClass().getName();
    }
}
