package com.mezze.interpreter.runtime;

import com.oracle.truffle.api.TruffleLanguage.Env;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;

import java.util.Set;

/**
 * Polyglot support for Mezze.
 *
 * Provides access to other GraalVM languages (Python, JavaScript, Ruby, etc.)
 * when running in JVM mode with the required language JARs on the classpath.
 *
 * Note: This does NOT work in native binary mode unless languages are
 * embedded at build time.
 */
public final class MezzePolyglotSupport {

    private final Env env;
    private volatile Set<String> availableLanguages;

    public MezzePolyglotSupport(Env env) {
        this.env = env;
    }

    /**
     * Check if a language is available.
     */
    @TruffleBoundary
    public boolean isLanguageAvailable(String languageId) {
        return getAvailableLanguages().contains(languageId);
    }

    /**
     * Get all available language IDs.
     */
    @TruffleBoundary
    public Set<String> getAvailableLanguages() {
        if (availableLanguages == null) {
            synchronized (this) {
                if (availableLanguages == null) {
                    availableLanguages = env.getPublicLanguages().keySet();
                }
            }
        }
        return availableLanguages;
    }

    /**
     * Import a module from a guest language.
     *
     * @param languageId The language ID (e.g., "python", "js", "ruby")
     * @param moduleName The module to import
     * @return The imported module as a polyglot Value
     */
    @TruffleBoundary
    public Object importModule(String languageId, String moduleName) {
        assertLanguageAvailable(languageId);

        // Use language-specific import mechanism
        String importCode = switch (languageId) {
            case "python" -> "import " + moduleName + "; " + moduleName;
            case "js" -> "require('" + moduleName + "')";
            case "ruby" -> "require '" + moduleName + "'; " + moduleName.replace("/", "::");
            default -> throw new UnsupportedOperationException(
                "Import not supported for language: " + languageId
            );
        };

        return eval(languageId, importCode);
    }

    /**
     * Evaluate code in a guest language.
     *
     * @param languageId The language ID
     * @param code The code to evaluate
     * @return The result as a polyglot Value
     */
    @TruffleBoundary
    public Object eval(String languageId, String code) {
        assertLanguageAvailable(languageId);
        return env.parsePublic(
            com.oracle.truffle.api.source.Source.newBuilder(languageId, code, "<mezze-eval>").build()
        ).call();
    }

    /**
     * Evaluate a binary Source in a guest language — the only route into
     * languages that don't accept a text source, chiefly GraalWasm. The
     * returned Object is whatever the language chooses to represent as the
     * "top level" of a compiled binary module. For {@code wasm} that's the
     * module instance whose members are the WebAssembly exports (functions,
     * memories, globals, tables).
     *
     * @param languageId The language ID (e.g. "wasm")
     * @param bytes The binary payload (owned by the caller — implementations
     *              may wrap it without copying, so callers must not mutate).
     * @param name Human-facing name for stack traces / diagnostics.
     */
    @TruffleBoundary
    public Object evalBytes(String languageId, byte[] bytes, String name) {
        assertLanguageAvailable(languageId);
        String moduleName = name == null || name.isEmpty() ? "<mezze-module>" : name;
        org.graalvm.polyglot.io.ByteSequence seq = org.graalvm.polyglot.io.ByteSequence.create(bytes);
        return env.parsePublic(
            com.oracle.truffle.api.source.Source.newBuilder(languageId, seq, moduleName).build()
        ).call();
    }

    /**
     * Get the top-level bindings (global scope) of a guest language.
     *
     * The previous implementation returned {@code env.getPolyglotBindings()}
     * for every language — that's the SHARED cross-language import/export
     * scope, not the per-language globals.  We now evaluate a
     * language-idiomatic expression that yields the language's top-level
     * namespace, giving callers per-language {@code readMember}/{@code
     * getMembers} access.
     *
     * The result is a TruffleObject whose members are the language's global
     * bindings — readable via {@link InteropLibrary#readMember}.
     */
    @TruffleBoundary
    public Object getBindings(String languageId) {
        assertLanguageAvailable(languageId);
        String globalsExpr = switch (languageId) {
            case "python" -> "__import__('builtins').globals()";
            case "js"     -> "globalThis";
            case "ruby"   -> "self";
            case "wasm"   -> "";  // wasm has no global namespace at this layer
            default -> null;
        };
        if (globalsExpr == null) {
            throw new MezzePolyglotException(
                "No top-scope accessor for language: " + languageId);
        }
        return eval(languageId, globalsExpr);
    }

    /**
     * Look up a single top-level binding by name in a guest language.
     * Returns the value or throws {@link UnknownIdentifierException} if the
     * name is not bound.
     */
    @TruffleBoundary
    public Object getBinding(String languageId, String name)
            throws UnknownIdentifierException, UnsupportedMessageException {
        Object bindings = getBindings(languageId);
        InteropLibrary lib = InteropLibrary.getUncached(bindings);
        if (!lib.isMemberReadable(bindings, name)) {
            throw UnknownIdentifierException.create(name);
        }
        return lib.readMember(bindings, name);
    }

    /**
     * Per-context execution policy — the sandbox knobs a `GraalXWith` handler
     * carries. Mirrors the subset of {@code TruffleContext.Builder} access grants
     * (see #polyglot context-management design). An inner context can restrict
     * BELOW its {@code allowAllAccess(true)} parent, so this is real enforcement.
     */
    public record ExecConfig(
        boolean allowIo, boolean allowNative, boolean allowCreateThread,
        boolean allowCreateProcess, boolean allowHostClassLoading,
        boolean allowHostLookup, boolean allowPolyglotAccess) {
        /** All-permissive — matches the ambient context's allowAllAccess(true). */
        public static ExecConfig permissive() {
            return new ExecConfig(true, true, true, true, true, true, true);
        }
    }

    /**
     * Open a fresh INNER context for {@code languageId} configured by {@code cfg}.
     * Isolated globals, and access restricted per {@code cfg} regardless of the
     * permissive parent. The caller owns the returned handle and must
     * {@link com.oracle.truffle.api.TruffleContext#close()} it (the scoped
     * {@code GraalXWith} handler does this in {@code finalize_scope}).
     */
    @TruffleBoundary
    public com.oracle.truffle.api.TruffleContext openContext(String languageId, ExecConfig cfg) {
        assertLanguageAvailable(languageId);
        // inheritAllAccess(true): inherit the parent's privileges (incl. access to
        // the language itself and its resource loading) as the baseline, then the
        // explicit allow*(…) below selectively DENY — that's the sandbox. Starting
        // from inheritAllAccess(false) denies the language too ("not available").
        return env.newInnerContextBuilder(languageId)
            .inheritAllAccess(true)
            .allowIO(cfg.allowIo())
            .allowNativeAccess(cfg.allowNative())
            .allowCreateThread(cfg.allowCreateThread())
            .allowCreateProcess(cfg.allowCreateProcess())
            .allowHostClassLoading(cfg.allowHostClassLoading())
            .allowHostClassLookup(cfg.allowHostLookup())
            .allowPolyglotAccess(cfg.allowPolyglotAccess())
            .build();
    }

    /**
     * Evaluate {@code code} inside {@code ctx} and return the result's display
     * string, produced BEFORE leaving the context — a String crosses the context
     * boundary safely, whereas a live guest object is bound to {@code ctx} and
     * would die when it closes. This is the enforcement-proof path (config
     * restrictions surface as a thrown PolyglotException here); the full
     * error-carrying verb set that keeps a foreign alive across ops is layered on
     * top separately.
     */
    @TruffleBoundary
    public String evalToStringIn(com.oracle.truffle.api.TruffleContext ctx, String languageId,
                                 String code, com.oracle.truffle.api.nodes.Node node)
            throws UnsupportedMessageException {
        // Do NOT enter `ctx` first — evalPublic enters the inner context itself and
        // requires the PARENT (creator) context to be the current one, which it
        // already is (we're running inside Mezze's context). The result is coerced
        // to a String here so it crosses back safely.
        Object result = ctx.evalPublic(node, com.oracle.truffle.api.source.Source
            .newBuilder(languageId, code, "<mezze-sandbox>").build());
        InteropLibrary lib = InteropLibrary.getUncached(result);
        if (lib.isString(result)) return lib.asString(result);
        Object display = lib.toDisplayString(result);
        return InteropLibrary.getUncached(display).asString(display);
    }

    private void assertLanguageAvailable(String languageId) {
        if (!isLanguageAvailable(languageId)) {
            throw new MezzePolyglotException(
                "Language '" + languageId + "' is not available.\n" +
                "Available languages: " + getAvailableLanguages() + "\n\n" +
                "To use " + languageId + ", run Mezze in JVM mode with the language JAR:\n" +
                "  java -cp mez.jar:" + languageId + ".jar com.mezze.language.cli.MainKt exec <file>"
            );
        }
    }

    /**
     * Exception thrown when polyglot operations fail.
     */
    public static final class MezzePolyglotException extends RuntimeException {
        public MezzePolyglotException(String message) {
            super(message);
        }

        public MezzePolyglotException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
