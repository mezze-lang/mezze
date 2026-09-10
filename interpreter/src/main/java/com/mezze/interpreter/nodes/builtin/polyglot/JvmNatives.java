package com.mezze.interpreter.nodes.builtin.polyglot;

import com.mezze.interpreter.runtime.MezzeContext;
import com.mezze.interpreter.values.MezzeForeign;
import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.interpreter.values.MezzeVariant;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleLanguage.Env;
import com.oracle.truffle.api.nodes.Node;

/**
 * JVM host-access natives — bridge to arbitrary Java classes via
 * {@code Env.lookupHostSymbol}.
 *
 * Requires {@code allowAllAccess(true)} (or explicit
 * {@code allowHostClassLookup} + {@code allowHostAccess}) on the enclosing
 * polyglot {@link org.graalvm.polyglot.Context}. Both the shared test
 * engine and CLI JvmLauncher use {@code allowAllAccess(true)}, so this is
 * available out of the box.
 *
 * The returned Foreign wraps the host class as an interop-visible
 * TruffleObject — {@code isInstantiable} is true (call
 * {@code polyglot::instantiate} to build an instance), members are the
 * class's static fields/methods, and methods can be invoked via
 * {@code invoke_member}.
 */
public final class JvmNatives {

    private JvmNatives() {}

    @TruffleBoundary
    public static MezzeVariant lookupClass(MezzeRecord args, Node currentNode) {
        String name = PolyglotNatives.getString(args, "name");
        try {
            Env env = MezzeContext.get(currentNode).getEnv();
            if (!env.isHostLookupAllowed()) {
                return MezzeVariant.err(PolyglotNatives.errorTypeError(
                    "JVM host class lookup is not allowed in this context. " +
                    "Rebuild the polyglot Context with allowHostClassLookup or " +
                    "allowAllAccess."));
            }
            Object hostSym = env.lookupHostSymbol(name);
            if (hostSym == null) {
                return MezzeVariant.err(PolyglotNatives.errorNotFound(name));
            }
            return MezzeVariant.ok(new MezzeForeign(hostSym, "jvm"));
        } catch (Throwable t) {
            return MezzeVariant.err(PolyglotNatives.translateException(t));
        }
    }
}
