package com.mezze.interpreter.nodes.builtin.polyglot;

import com.mezze.interpreter.runtime.MezzeContext;
import com.mezze.interpreter.runtime.MezzePolyglotSupport;
import com.mezze.interpreter.values.MezzeForeign;
import com.mezze.interpreter.values.MezzePolyglotContext;
import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.interpreter.values.MezzeVariant;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.nodes.Node;

/**
 * Python-specific natives — thin sugar over PolyglotContextNatives.
 *
 * The heavy lifting lives in {@link PolyglotNatives} (member/array/execute
 * ops on the Foreign wrapper) and {@link PolyglotContextNatives} (eval /
 * binding through the enclosing Truffle Env). Python-specific idioms we
 * cannot express directly through {@code Context.eval("python", ...)}
 * belong here.
 *
 * Currently the only genuine Python-shaped op is {@code import_module}:
 * Python's {@code import foo} is a statement that binds {@code foo} in
 * the module namespace but does not return the module as an expression.
 * We emulate the "import and yield" pattern via
 * {@code __import__('foo')} which is an expression, avoiding the
 * import-statement / expression-result impedance.
 */
public final class PythonNatives {

    private PythonNatives() {}

    @TruffleBoundary
    public static MezzeVariant importModule(MezzeRecord args, Node currentNode) {
        MezzePolyglotContext ctx = PolyglotNatives.getContext(args, "context");
        if (ctx.isClosed()) {
            return MezzeVariant.err(
                PolyglotNatives.errorTypeError("Context is closed"));
        }
        if (!ctx.isLanguageAllowed("python")) {
            return MezzeVariant.err(PolyglotNatives.errorTypeError(
                "Language 'python' not allowed by this Context. " +
                "Allowed: " + ctx.getAllowedLanguages()));
        }
        String moduleName = PolyglotNatives.getString(args, "name");
        // Validate the module name aggressively — this string gets
        // spliced into a Python expression, so anything with quotes /
        // newlines / backslashes could exit the string literal.
        // Real Python module names are dotted identifiers only.
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
            // but the latter is a statement.  Use importlib.import_module
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
