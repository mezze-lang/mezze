package com.mezze.polyglot.python;

import com.mezze.interpreter.nodes.builtin.polyglot.PolyglotNatives;
import com.mezze.interpreter.runtime.MezzeContext;
import com.mezze.interpreter.runtime.MezzePolyglotSupport;
import com.mezze.interpreter.values.MezzeForeign;
import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.interpreter.values.MezzeVariant;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;

/**
 * Python-specific natives — thin sugar over the ambient Truffle Env's
 * GraalPy interpreter. Extracted from :interpreter into the standalone
 * `mezze-python` provider package (polyglot-as-a-package, Phase 2 pilot).
 *
 * Both {@link #eval} and {@link #importModule} run against the
 * enclosing {@link com.oracle.truffle.api.TruffleLanguage.Env}: the
 * runtime (test harness / CLI JvmLauncher / native launcher) sets up
 * a polyglot Context with GraalPy enabled up-front, and every call in
 * a Mezze program shares that ambient Context.
 *
 * Python's {@code import foo} is a STATEMENT that binds {@code foo}
 * in the module namespace but does not return the module as an
 * expression. We emulate the "import and yield" pattern via
 * {@code importlib.import_module(...)} which is an expression.
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
public final class PythonNatives {

    private PythonNatives() {}

    @TruffleBoundary
    public static MezzeVariant eval(MezzeRecord args, Node currentNode) {
        String source = getStringField(args, "source");
        try {
            MezzePolyglotSupport support =
                MezzeContext.get(currentNode).getPolyglotSupport();
            Object result = support.eval("python", source);
            return MezzeVariant.ok(new MezzeForeign(result, "python"));
        } catch (Throwable t) {
            return MezzeVariant.err(PolyglotNatives.translateException(t));
        }
    }

    @TruffleBoundary
    public static MezzeVariant importModule(MezzeRecord args, Node currentNode) {
        String moduleName = getStringField(args, "name");
        // Validate the module name aggressively — this string gets
        // spliced into a Python expression, so anything with quotes /
        // newlines / backslashes could exit the string literal. Real
        // Python module names are dotted identifiers only.
        if (!isSafeModuleName(moduleName)) {
            return MezzeVariant.err(makeTypeError(
                "Invalid Python module name: '" + moduleName +
                "'. Expected a dotted identifier (e.g. 'numpy.linalg')."));
        }
        try {
            MezzePolyglotSupport support =
                MezzeContext.get(currentNode).getPolyglotSupport();
            // __import__('a.b.c') returns the *top* package 'a'; the
            // typical idiom to obtain the leaf module is
            //   import a.b.c ; a.b.c
            // but the latter is a statement. Use importlib.import_module
            // which returns the requested (possibly nested) module.
            String expr = "__import__('importlib').import_module('" + moduleName + "')";
            Object result = support.eval("python", expr);
            return MezzeVariant.ok(new MezzeForeign(result, "python"));
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

    private static boolean isSafeModuleName(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')
                || c == '_' || c == '.';
            if (!ok) return false;
        }
        // no leading dot / no doubled dots
        if (s.charAt(0) == '.') return false;
        if (s.contains("..")) return false;
        return true;
    }
}
