package com.mezze.polyglot.js;

import com.mezze.interpreter.nodes.builtin.polyglot.PolyglotNatives;
import com.mezze.interpreter.runtime.MezzeContext;
import com.mezze.interpreter.runtime.MezzePolyglotSupport;
import com.mezze.interpreter.values.MezzeForeign;
import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.interpreter.values.MezzeVariant;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;

/**
 * JS-specific natives — thin sugar over the ambient Truffle Env's
 * GraalJS interpreter. Extracted from :interpreter into the standalone
 * `mezze-js` provider package (polyglot-as-a-package, Phase 4).
 *
 * `eval`, `require`, and `import` all run against the enclosing
 * {@link com.oracle.truffle.api.TruffleLanguage.Env}: the runtime
 * (test harness / CLI JvmLauncher / native launcher) sets up a
 * polyglot Context with GraalJS enabled up-front, and every call in
 * a Mezze program shares that ambient Context. There is no
 * user-facing Context lifecycle in the new stdlib — `impl Js for
 * GraalJs` has no fields.
 *
 * Module systems:
 *   - `eval` — evaluate an arbitrary JS expression / statement.
 *              Uses GraalJS's default source parser; global scope
 *              (`Math`, `JSON`, `Date`, `Array`, …) is visible.
 *   - `require` — CommonJS `require(...)`. Requires the enclosing
 *                 Context to have been built with
 *                 `js.commonjs-require = true` — the manifest reader
 *                 turns this on automatically when
 *                 `[polyglot.js]` declares `node_modules`. Uses
 *                 GraalJS's built-in CJS loader.
 *   - `import` — ESM dynamic import. Returns a Promise; the current
 *                implementation performs a `Promise.resolve` await
 *                via a synchronous helper so the caller receives the
 *                resolved module object.  For simple scripts the
 *                same effect is available through `eval` with the
 *                `import()` expression.
 *
 * A small handful of low-level field/error helpers is inlined here
 * (rather than reused from {@code PolyglotNatives}) because those
 * helpers are package-private and this class lives outside the
 * {@code interpreter.nodes.builtin.polyglot} package. Behavior is
 * identical to the {@code errorTypeError(String)} /
 * {@code getString(MezzeRecord, String)} in that package —
 * matched intentionally so both registration paths surface the same
 * error variants at runtime. {@link PolyglotNatives#translateException}
 * is already public and shared.
 */
public final class JsNatives {

    private JsNatives() {}

    @TruffleBoundary
    public static MezzeVariant eval(MezzeRecord args, Node currentNode) {
        String source = getStringField(args, "source");
        try {
            MezzePolyglotSupport support =
                MezzeContext.get(currentNode).getPolyglotSupport();
            Object result = support.eval("js", source);
            return MezzeVariant.ok(new MezzeForeign(result, "js"));
        } catch (Throwable t) {
            return MezzeVariant.err(PolyglotNatives.translateException(t));
        }
    }

    @TruffleBoundary
    public static MezzeVariant require(MezzeRecord args, Node currentNode) {
        String moduleName = getStringField(args, "module");
        // Validate the module name aggressively — this string gets
        // spliced into a JS `require(...)` expression. Real Node
        // module names / paths use a well-bounded character set.
        if (!isSafeModuleName(moduleName)) {
            return MezzeVariant.err(makeTypeError(
                "Invalid JS module name: '" + moduleName +
                "'. Expected a bare module id, relative path, or scoped @scope/name."));
        }
        try {
            MezzePolyglotSupport support =
                MezzeContext.get(currentNode).getPolyglotSupport();
            String expr = "require('" + moduleName + "')";
            Object result = support.eval("js", expr);
            return MezzeVariant.ok(new MezzeForeign(result, "js"));
        } catch (Throwable t) {
            return MezzeVariant.err(PolyglotNatives.translateException(t));
        }
    }

    @TruffleBoundary
    public static MezzeVariant importModule(MezzeRecord args, Node currentNode) {
        String moduleName = getStringField(args, "module");
        if (!isSafeModuleName(moduleName)) {
            return MezzeVariant.err(makeTypeError(
                "Invalid JS module name: '" + moduleName +
                "'. Expected a bare module id, relative path, or scoped @scope/name."));
        }
        try {
            MezzePolyglotSupport support =
                MezzeContext.get(currentNode).getPolyglotSupport();
            // Dynamic ESM import — returns a Promise. Await via an IIFE
            // pattern that finishes synchronously under GraalJS's fetch
            // runner: the language uses cooperative task-queue draining
            // during the same call, so the Promise resolves before we
            // return from eval.
            String expr =
                "(async () => await import('" + moduleName + "'))()" +
                ".then(m => m).catch(e => { throw e; })";
            Object result = support.eval("js", expr);
            return MezzeVariant.ok(new MezzeForeign(result, "js"));
        } catch (Throwable t) {
            return MezzeVariant.err(PolyglotNatives.translateException(t));
        }
    }

    // ---- Inlined helpers (see class javadoc) ----------------------------

    private static String getStringField(MezzeRecord r, String name) {
        int idx = r.getFieldIndex(name);
        if (idx < 0) {
            throw new IllegalArgumentException("Missing field '" + name + "'");
        }
        Object v = r.getField(idx);
        if (v instanceof String s) return s;
        throw new IllegalArgumentException("Expected String for field '" + name + "'");
    }

    @TruffleBoundary
    private static MezzeVariant makeTypeError(String message) {
        MezzeRecord payload =
            MezzeRecord.fromFields(new String[]{"message"}, new Object[]{message});
        int tag = MezzeVariant.TagRegistry.getGlobal().getOrCreateTag("TypeError");
        return MezzeVariant.singlePayload(MezzeVariant.TagRegistry.getGlobal(), tag, payload);
    }

    /**
     * Allowed characters in a JS module name we're going to splice into
     * a `require('...')` / `import('...')` expression. Deliberately
     * strict to prevent quote-breakout: no single quotes, no double
     * quotes, no backslashes, no newlines. Path components use
     * letter/digit + a small set of separators.
     */
    private static boolean isSafeModuleName(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')
                || c == '_' || c == '-' || c == '.'
                || c == '/' || c == '@';
            if (!ok) return false;
        }
        return true;
    }
}
