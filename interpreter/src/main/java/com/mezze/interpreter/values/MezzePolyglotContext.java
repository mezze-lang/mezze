package com.mezze.interpreter.values;

import com.oracle.truffle.api.interop.TruffleObject;

import java.util.Set;

/**
 * Opaque polyglot Context handle for Mezze — the runtime side of the
 * {@code newtype Context = __NativeContext} stdlib declaration.
 *
 * Phase 1: a lightweight wrapper that captures the languages the caller
 * asked for and the (informational) classpath entries.  Actual evaluation
 * still happens in the enclosing {@code TruffleLanguage.Env} — {@code
 * create_context} does NOT spin up a nested Truffle context yet.  The
 * point of tracking languages here is so eval/binding can enforce that
 * a caller only touches the languages they declared, keeping the API
 * shape stable when nested contexts are introduced later.
 */
public final class MezzePolyglotContext implements TruffleObject {

    private final Set<String> allowedLanguages;
    private final String[] classpath;
    private volatile boolean closed;

    public MezzePolyglotContext(Set<String> allowedLanguages, String[] classpath) {
        this.allowedLanguages = allowedLanguages == null
            ? java.util.Collections.emptySet()
            : java.util.Set.copyOf(allowedLanguages);
        this.classpath = classpath == null ? new String[0] : classpath.clone();
        this.closed = false;
    }

    public boolean isLanguageAllowed(String languageId) {
        // Empty allow-list means "any language available in the enclosing Env".
        return allowedLanguages.isEmpty() || allowedLanguages.contains(languageId);
    }

    public Set<String> getAllowedLanguages() {
        return allowedLanguages;
    }

    public String[] getClasspath() {
        return classpath;
    }

    public boolean isClosed() {
        return closed;
    }

    public void close() {
        this.closed = true;
    }

    // The context is opaque to guest languages — no interop surface beyond
    // TruffleObject membership.  All access goes through the native
    // dispatch (polyglot_context_eval / polyglot_context_binding /
    // polyglot_context_close), so members/isNull/etc. remain UnsupportedMessage
    // by default.

    @Override
    public String toString() {
        return "PolyglotContext(langs=" + allowedLanguages + ", closed=" + closed + ")";
    }
}
