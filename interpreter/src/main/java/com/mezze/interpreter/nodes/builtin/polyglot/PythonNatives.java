package com.mezze.interpreter.nodes.builtin.polyglot;

import com.mezze.interpreter.runtime.MezzeContext;
import com.mezze.interpreter.runtime.MezzePolyglotSupport;
import com.mezze.interpreter.values.MezzeForeign;
import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.interpreter.values.MezzeVariant;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;

/**
 * Python-specific natives — thin sugar over the ambient Truffle Env's
 * GraalPy interpreter.
 *
 * Both {@link #eval} and {@link #importModule} run against the
 * enclosing {@link com.oracle.truffle.api.TruffleLanguage.Env}: the
 * runtime (test harness / CLI JvmLauncher / native launcher) sets up
 * a polyglot Context with GraalPy enabled up-front, and every call in
 * a Mezze program shares that ambient Context. There is no
 * user-facing Context lifecycle in the new stdlib — `impl Python for
 * GraalPy` has no fields.
 *
 * Python's {@code import foo} is a STATEMENT that binds {@code foo}
 * in the module namespace but does not return the module as an
 * expression. We emulate the "import and yield" pattern via
 * {@code importlib.import_module(...)} which is an expression.
 */
public final class PythonNatives {

    private PythonNatives() {}

    @TruffleBoundary
    public static MezzeVariant eval(MezzeRecord args, Node currentNode) {
        String source = PolyglotNatives.getString(args, "source");
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
        String moduleName = PolyglotNatives.getString(args, "name");
        // Validate the module name aggressively — this string gets
        // spliced into a Python expression, so anything with quotes /
        // newlines / backslashes could exit the string literal. Real
        // Python module names are dotted identifiers only.
        if (!isSafeModuleName(moduleName)) {
            return MezzeVariant.err(PolyglotNatives.errorTypeError(
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
