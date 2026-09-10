package com.mezze.interpreter.nodes.builtin.polyglot;

import com.mezze.datastructures.seq.MezzeSeq;
import com.mezze.interpreter.runtime.MezzeContext;
import com.mezze.interpreter.runtime.MezzePolyglotSupport;
import com.mezze.interpreter.runtime.NativeTypeMismatchException;
import com.mezze.interpreter.values.MezzeForeign;
import com.mezze.interpreter.values.MezzePolyglotContext;
import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.interpreter.values.MezzeVariant;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Context lifecycle natives — create/close/eval/binding.
 *
 * Phase 1 note: a MezzePolyglotContext is a lightweight allow-list; eval
 * happens in the enclosing {@link com.oracle.truffle.api.TruffleLanguage.Env}.
 * We keep the create/close pair so callers can express intent, and so that
 * a later Phase can swap in a real nested {@code TruffleContext} without
 * touching stdlib callers.
 */
public final class PolyglotContextNatives {

    private PolyglotContextNatives() {}

    @TruffleBoundary
    public static MezzePolyglotContext create(MezzeRecord args) {
        Set<String> langs = getStringSeq(args, "languages");
        String[] cp = toStringArray(getStringSeq(args, "classpath"));
        return new MezzePolyglotContext(langs, cp);
    }

    @TruffleBoundary
    public static MezzeRecord close(MezzeRecord args) {
        MezzePolyglotContext ctx = PolyglotNatives.getContext(args, "it");
        ctx.close();
        return MezzeRecord.empty();
    }

    @TruffleBoundary
    public static MezzeVariant eval(MezzeRecord args, Node currentNode) {
        MezzePolyglotContext ctx = PolyglotNatives.getContext(args, "it");
        if (ctx.isClosed()) {
            return MezzeVariant.err(
                PolyglotNatives.errorTypeError("Context is closed"));
        }
        String language = PolyglotNatives.getString(args, "language");
        String code = PolyglotNatives.getString(args, "code");
        if (!ctx.isLanguageAllowed(language)) {
            return MezzeVariant.err(PolyglotNatives.errorTypeError(
                "Language '" + language + "' not allowed by this Context. " +
                "Allowed: " + ctx.getAllowedLanguages()));
        }
        try {
            MezzePolyglotSupport support =
                MezzeContext.get(currentNode).getPolyglotSupport();
            Object result = support.eval(language, code);
            return MezzeVariant.ok(new MezzeForeign(result, language));
        } catch (Throwable t) {
            return MezzeVariant.err(PolyglotNatives.translateException(t));
        }
    }

    @TruffleBoundary
    public static MezzeVariant binding(MezzeRecord args, Node currentNode) {
        MezzePolyglotContext ctx = PolyglotNatives.getContext(args, "it");
        if (ctx.isClosed()) {
            return MezzeVariant.err(
                PolyglotNatives.errorTypeError("Context is closed"));
        }
        String language = PolyglotNatives.getString(args, "language");
        String name = PolyglotNatives.getString(args, "name");
        if (!ctx.isLanguageAllowed(language)) {
            return MezzeVariant.err(PolyglotNatives.errorTypeError(
                "Language '" + language + "' not allowed by this Context."));
        }
        try {
            MezzePolyglotSupport support =
                MezzeContext.get(currentNode).getPolyglotSupport();
            Object result = support.getBinding(language, name);
            return MezzeVariant.ok(new MezzeForeign(result, language));
        } catch (Throwable t) {
            return MezzeVariant.err(PolyglotNatives.translateException(t));
        }
    }

    // ==================== helpers ====================

    private static Set<String> getStringSeq(MezzeRecord r, String name) {
        Object v = PolyglotNatives.getField(r, name);
        LinkedHashSet<String> out = new LinkedHashSet<>();
        Object[] raw;
        if (v instanceof MezzeSeq seq) {
            raw = seq.toArray();
        } else if (v instanceof Object[] arr) {
            raw = arr;
        } else {
            throw new NativeTypeMismatchException(
                "Expected Seq of Str for field '" + name + "'");
        }
        for (Object x : raw) {
            if (x instanceof String s) out.add(s);
        }
        return out;
    }

    private static String[] toStringArray(Set<String> s) {
        return s.toArray(new String[0]);
    }
}
