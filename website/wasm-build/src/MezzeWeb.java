import com.mezze.language.parser.MezzeParser;
import com.mezze.language.parsedast.ParsedModule;
import com.mezze.language.typechecker.TypeChecker;
import com.mezze.language.truffleir.FunctionIR;
import com.mezze.interpreter.runtime.MezzeLanguage;
import com.mezze.language.formatter.Formatter;
import com.mezze.language.typechecker.lookup.TypedAstLookup;
import com.mezze.language.typedast.TypedModule;
import com.mezze.language.ast.TypePrinterKt;
import com.mezze.language.typechecker.diagnostics.DiagnosticJson;
import com.mezze.language.typechecker.diagnostics.JsonDiagnosticFormatter;
import com.mezze.language.parser.ParseException;
import org.graalvm.polyglot.*;
import org.graalvm.webimage.api.JS;
import org.graalvm.webimage.api.JSString;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

/**
 * Browser entry point. Installs three globals:
 *   runMezze(src)    -> program output, or a one-line diagnostic
 *   formatMezze(src) -> formatted source
 *
 * @JS.Export is not implemented in Web Image, so globals are installed by
 * assigning a Java lambda onto globalThis. Strings must cross as JSString.
 */
public final class MezzeWeb {

    @JS(args = {"fn"}, value = "globalThis.runMezze = (src) => fn(src);")
    private static native void installRun(Function<JSString, JSString> fn);

    @JS(args = {"fn"}, value = "globalThis.formatMezze = (src) => fn(src);")
    private static native void installFmt(Function<JSString, JSString> fn);

    @JS(args = {"fn"}, value = "globalThis.checkMezze = (src) => JSON.parse(fn(src));")
    private static native void installCheck(Function<JSString, JSString> fn);

    // line/col are 1-based, matching Loc.
    @JS(args = {"fn"}, value = "globalThis.typeAt = (src, line, col) => fn(src + '\\u0000' + line + '\\u0000' + col);")
    private static native void installTypeAt(Function<JSString, JSString> fn);

    public static String run(String source) {
        // Empty or main-less input crashes deep in the pipeline
        // (ArrayIndexOutOfBounds / "crash: Unhandled exception") because there
        // is no entry function to compile. Catch it here with a useful message.
        if (source == null || source.isBlank()) return "(nothing to run)";
        if (!source.contains("let main")) {
            return "No `main` to run.\n"
                 + "  Define an entry point, for example:\n"
                 + "      let main = {} -> 1";
        }
        PrintStream saved = System.out;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(buf, true, StandardCharsets.UTF_8));
            try {
                ParsedModule m = new MezzeParser().parseParsedModule(source, "/tmp/playground.mz");
                TypeChecker.TypeCheckResult r =
                    new TypeChecker().checkParsedModule(m, Path.of("/tmp"), null, true);
                if (!(r instanceof TypeChecker.TypeCheckResult.Success s)) {
                    return "TYPE ERROR: " + firstMessage(String.valueOf(r));
                }
                List<FunctionIR> fns = s.getLoweredFunctions();
                MezzeLanguage.setFunctionsForDirectExecution(
                    fns, s.getLoweredPerformers(), Path.of("/tmp"), "main");
                try (Context ctx = Context.newBuilder(MezzeLanguage.ID).allowAllAccess(true).build()) {
                    ctx.eval(Source.newBuilder(MezzeLanguage.ID, "# playground", "__pg__.mz")
                             .buildLiteral());
                }
            } finally {
                System.setOut(saved);
            }
            String out = buf.toString(StandardCharsets.UTF_8);
            return out.isEmpty() ? "(no output)" : out;
        } catch (Throwable t) {
            System.setOut(saved);
            String captured = buf.toString(StandardCharsets.UTF_8);
            String prefix = captured.isEmpty() ? "" : captured + "\n";
            String performer = unsupportedPerformer(t);
            if (performer != null) {
                return prefix
                     + "Not supported in the browser: " + performer + "\n"
                     + "  This program typechecks correctly — run it with `mez` to execute it.";
            }
            return prefix + "ERROR: " + String.valueOf(t.getMessage()).split("\n")[0];
        }
    }

    /**
     * Typecheck only — no execution. Returns the same JSON envelope as
     * `mez check --json`: severity, stable error code, location, hints and
     * fixHints. Cheap enough to run on every keystroke for squiggles.
     */
    public static String check(String source) {
        if (source == null || source.isBlank()) {
            return "{\"status\":\"ok\",\"summary\":{\"errors\":0,\"warnings\":0},\"diagnostics\":[]}";
        }
        try {
            ParsedModule m = new MezzeParser().parseParsedModule(source, "/tmp/playground.mz");
            TypeChecker.TypeCheckResult r =
                new TypeChecker().checkParsedModule(m, Path.of("/tmp"), null, true);
            if (r instanceof TypeChecker.TypeCheckResult.Success s) {
                return JsonDiagnosticFormatter.INSTANCE.render(
                    // Pass the typed module so `bindings` is populated:
                    // name + inferred type + location, which drives hover.
                    JsonDiagnosticFormatter.INSTANCE.ok(
                        "playground.mz", null, s.getTypedModule(), s.getWarnings()));
            }
            java.util.List<DiagnosticJson> diags = new java.util.ArrayList<>();
            if (r instanceof TypeChecker.TypeCheckResult.Failure f) {
                for (TypeChecker.TypeCheckError e : f.getErrors()) {
                    diags.add(JsonDiagnosticFormatter.INSTANCE.fromTypeCheckError(e));
                }
            }
            return JsonDiagnosticFormatter.INSTANCE.render(
                JsonDiagnosticFormatter.INSTANCE.errors("playground.mz", null, diags));
        } catch (Throwable t) {
            if (t instanceof ParseException pe) {
                return JsonDiagnosticFormatter.INSTANCE.render(
                    JsonDiagnosticFormatter.INSTANCE.errors("playground.mz", null,
                        java.util.List.of(JsonDiagnosticFormatter.INSTANCE.fromParseException(pe))));
            }
            return "{\"status\":\"ERRORS\",\"diagnostics\":[{\"severity\":\"ERROR\",\"message\":"
                 + jsonStr(String.valueOf(t.getMessage())) + "}]}";
        }
    }

    private static String jsonStr(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\"";
    }

    /**
     * Type at a cursor position — real hover, covering locals, parameters and
     * subexpressions, not just top-level bindings.
     *
     * Reuses TypedAstLookup, the same utility the LSP's hover uses: it walks the
     * TypedAST for the innermost node whose Loc contains (line, col).
     * Returns "name : Type", or "" when nothing resolves there.
     */
    public static String typeAt(String source, int line, int col) {
        if (source == null || source.isBlank()) return "";
        try {
            ParsedModule m = new MezzeParser().parseParsedModule(source, "/tmp/playground.mz");
            TypeChecker.TypeCheckResult r =
                new TypeChecker().checkParsedModule(m, Path.of("/tmp"), null, true);
            if (!(r instanceof TypeChecker.TypeCheckResult.Success s)) return "";
            TypedModule tm = s.getTypedModule();
            if (tm == null) return "";
            TypedAstLookup.LookupResult hit = TypedAstLookup.INSTANCE.findAtPosition(tm, line, col);
            if (hit instanceof TypedAstLookup.LookupResult.Binding b) {
                return b.getName() + " : " + TypePrinterKt.prettyPrintType(b.getType());
            }
            if (hit instanceof TypedAstLookup.LookupResult.Expr e) {
                return TypePrinterKt.prettyPrintType(e.getExpr().getType());
            }
            return "";
        } catch (Throwable t) {
            return "";
        }
    }

    public static String format(String source) {
        try {
            return new Formatter().format(source, null);
        } catch (Throwable t) {
            return source;   // unparseable: leave the user's text alone
        }
    }

    /**
     * Walk the cause chain for BrowserNatives.UnsupportedEffect. Polyglot wraps
     * host exceptions, so the throwable we see is rarely the one thrown; and in
     * a WASM build the wrapper may only carry the message.
     */
    private static String unsupportedPerformer(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof BrowserNatives.UnsupportedEffect u) return u.performer;
            String m = c.getMessage();
            if (m != null && m.contains("UnsupportedEffect")) {
                int i = m.lastIndexOf(':');
                if (i >= 0 && i + 1 < m.length()) return m.substring(i + 1).trim();
            }
        }
        return null;
    }

    /** The Failure toString is enormous; pull out just the diagnostic. */
    private static String firstMessage(String s) {
        int i = s.indexOf("message=");
        if (i < 0) return s.length() > 300 ? s.substring(0, 300) : s;
        int end = s.indexOf(", loc=", i);
        return end > 0 ? s.substring(i + 8, end) : s.substring(i + 8, Math.min(i + 308, s.length()));
    }

    public static void main(String[] args) {
        System.setProperty("polyglot.engine.allowUnsupportedPlatform", "true");
        System.setProperty("polyglot.engine.WarnInterpreterOnly", "false");
        System.setProperty("polyglot.engine.userResourceCache", "/tmp/tcache");
        BrowserOverrides.install();
        // Warm the stdlib so the first user call is fast (~750ms -> ~110ms),
        // and smoke-test the whole pipeline on the way up.
        run("use std::effects::log::{ Console, println }\n"
          + "let main = {} -> perform Console in println { msg = \"Hello, Mezze!\" }\n");
        installRun(js -> JSString.of(run(js.asString())));
        installFmt(js -> JSString.of(format(js.asString())));
        installCheck(js -> JSString.of(check(js.asString())));
        installTypeAt(js -> {
            // src \u0000 line \u0000 col — avoids a multi-arg native signature.
            String[] parts = js.asString().split("\u0000", -1);
            if (parts.length != 3) return JSString.of("");
            try {
                return JSString.of(typeAt(parts[0],
                    Integer.parseInt(parts[1]), Integer.parseInt(parts[2])));
            } catch (NumberFormatException e) { return JSString.of(""); }
        });
        System.out.println("mezze-wasm ready: runMezze(src), formatMezze(src)");
    }
}
