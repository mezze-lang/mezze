package com.mezze.interpreter.nodes.continuation;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Reflective shim into {@code jdk.internal.vm.Continuation} for the
 * effect-handler runtime.
 *
 * <p>Same surface we'd build for a production Loom integration, but kept
 * minimal here: we only need {@code newScope}, {@code newContinuation},
 * {@code run}, {@code yield}, {@code isDone}.
 *
 * <p><b>Requires</b> the JVM to be launched with
 * {@code --add-opens=java.base/jdk.internal.vm=ALL-UNNAMED}. Test
 * configuration adds this; production CLI configuration would too.
 */
public final class LoomSupport {

    private LoomSupport() {}

    private static final Class<?> SCOPE_CLASS;
    private static final Class<?> CONT_CLASS;
    private static final MethodHandle SCOPE_CTOR;
    private static final MethodHandle CONT_CTOR;
    private static final MethodHandle CONT_RUN;
    private static final MethodHandle CONT_IS_DONE;
    private static final MethodHandle CONT_YIELD;

    private static final boolean SUPPORTED;
    private static final Throwable INIT_ERROR;

    static {
        Class<?> scope = null;
        Class<?> cont = null;
        MethodHandle scopeCtor = null;
        MethodHandle contCtor = null;
        MethodHandle contRun = null;
        MethodHandle contIsDone = null;
        MethodHandle contYield = null;
        Throwable err = null;

        try {
            scope = Class.forName("jdk.internal.vm.ContinuationScope");
            cont = Class.forName("jdk.internal.vm.Continuation");

            MethodHandles.Lookup lookup = MethodHandles.lookup();

            Constructor<?> sc = scope.getDeclaredConstructor(String.class);
            sc.setAccessible(true);
            scopeCtor = lookup.unreflectConstructor(sc);

            Constructor<?> cc = cont.getDeclaredConstructor(scope, Runnable.class);
            cc.setAccessible(true);
            contCtor = lookup.unreflectConstructor(cc);

            Method runM = cont.getDeclaredMethod("run");
            runM.setAccessible(true);
            contRun = lookup.unreflect(runM);

            Method doneM = cont.getDeclaredMethod("isDone");
            doneM.setAccessible(true);
            contIsDone = lookup.unreflect(doneM);

            Method yieldM = cont.getDeclaredMethod("yield", scope);
            yieldM.setAccessible(true);
            contYield = lookup.unreflect(yieldM);
        } catch (Throwable t) {
            err = t;
        }

        SCOPE_CLASS = scope;
        CONT_CLASS = cont;
        SCOPE_CTOR = scopeCtor;
        CONT_CTOR = contCtor;
        CONT_RUN = contRun;
        CONT_IS_DONE = contIsDone;
        CONT_YIELD = contYield;
        SUPPORTED = err == null;
        INIT_ERROR = err;
    }

    public static boolean isSupported() { return SUPPORTED; }

    public static Throwable getInitError() { return INIT_ERROR; }

    public static Object newScope(String name) {
        ensureSupported();
        try { return SCOPE_CTOR.invoke(name); }
        catch (Throwable t) { throw rethrow(t); }
    }

    public static Object newContinuation(Object scope, Runnable body) {
        ensureSupported();
        try { return CONT_CTOR.invoke(scope, body); }
        catch (Throwable t) { throw rethrow(t); }
    }

    @TruffleBoundary
    public static void run(Object continuation) {
        ensureSupported();
        try { CONT_RUN.invoke(continuation); }
        catch (Throwable t) { throw rethrow(t); }
    }

    public static boolean isDone(Object continuation) {
        ensureSupported();
        try { return (boolean) CONT_IS_DONE.invoke(continuation); }
        catch (Throwable t) { throw rethrow(t); }
    }

    @TruffleBoundary
    public static boolean yield(Object scope) {
        ensureSupported();
        try { return (boolean) CONT_YIELD.invoke(scope); }
        catch (Throwable t) { throw rethrow(t); }
    }

    private static void ensureSupported() {
        if (!SUPPORTED) {
            throw new UnsupportedOperationException(
                "Loom continuations not available. Need JDK 21+ with "
                + "`--add-opens=java.base/jdk.internal.vm=ALL-UNNAMED`. "
                + "Initial error: " + INIT_ERROR, INIT_ERROR);
        }
    }

    private static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException re) return re;
        if (t instanceof Error e) throw e;
        return new RuntimeException(t);
    }
}
