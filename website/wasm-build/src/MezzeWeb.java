import com.mezze.language.parser.MezzeParser;
import com.mezze.language.parsedast.ParsedModule;
import com.mezze.language.typechecker.TypeChecker;
import com.mezze.language.truffleir.FunctionIR;
import com.mezze.interpreter.runtime.MezzeLanguage;
import com.mezze.language.formatter.Formatter;
import com.mezze.language.formatter.WidthOverride;
import com.mezze.language.langservice.lookup.TypedAstLookup;
import com.mezze.language.typedast.TypedModule;
import com.mezze.language.ast.TypePrinterKt;
import com.mezze.language.langservice.diagnostics.DiagnosticJson;
import com.mezze.language.langservice.diagnostics.JsonDiagnosticFormatter;
import com.mezze.language.langservice.printing.ErrorFormatter;
import com.mezze.language.langservice.printing.ParseErrorFormatter;
import com.mezze.language.langservice.hover.HoverInfo;
import com.mezze.language.langservice.completion.CompletionEngine;
import com.mezze.language.langservice.completion.CompletionContext;
import com.mezze.language.langservice.completion.CompletionCandidate;
import com.mezze.language.store.InMemoryMezzeStore;
import com.mezze.language.typechecker.TypeCheckOutputKt;
import com.mezze.language.typechecker.SourceContext;
import com.mezze.language.parsedast.Loc;
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

    // src \u0000 cols — same NUL convention as typeAt, so the native signature
    // stays single-argument. cols is optional and defaults to the CLI width.
    @JS(args = {"fn"}, value = "globalThis.formatMezze = (src, cols) => fn(src + '\\u0000' + (cols || 0));")
    private static native void installFmt(Function<JSString, JSString> fn);

    @JS(args = {"fn"}, value = "globalThis.checkMezze = (src) => JSON.parse(fn(src));")
    private static native void installCheck(Function<JSString, JSString> fn);

    // line/col are 1-based, matching Loc.
    @JS(args = {"fn"}, value = "globalThis.typeAt = (src, line, col) => fn(src + '\\u0000' + line + '\\u0000' + col);")
    private static native void installTypeAt(Function<JSString, JSString> fn);

    // line/col are 1-based here too. Returns a JSON array of candidates.
    @JS(args = {"fn"}, value = "globalThis.completeMezze = (src, line, col) => JSON.parse(fn(src + '\\u0000' + line + '\\u0000' + col));")
    private static native void installComplete(Function<JSString, JSString> fn);


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
                    new TypeChecker(true).checkParsedModule(m, Path.of("/tmp"), null, true);
                if (!(r instanceof TypeChecker.TypeCheckResult.Success s)) {
                    return "TYPE ERROR: " + firstMessage(String.valueOf(r));
                }
                List<FunctionIR> fns = s.getLoweredFunctions();
                // The entry is identified by CONTENT HASH, not name. Resolve
                // `main`'s hash from the lowered functions; passing the literal
                // "main" would blow up in Hash's hex validation.
                String entryHash = null;
                for (FunctionIR fn : fns) {
                    if ("main".equals(fn.getName())) { entryHash = fn.getHash().getHex(); break; }
                }
                MezzeLanguage.setFunctionsForDirectExecution(
                    fns, s.getLoweredPerformers(), Path.of("/tmp"), entryHash);
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
                new TypeChecker(true).checkParsedModule(m, Path.of("/tmp"), null, true);
            if (r instanceof TypeChecker.TypeCheckResult.Success s) {
                return JsonDiagnosticFormatter.INSTANCE.render(
                    // Pass the typed module so `bindings` is populated:
                    // name + inferred type + location, which drives hover.
                    JsonDiagnosticFormatter.INSTANCE.ok(
                        "playground.mz", null, s.getTypedModule(), s.getWarnings()));
            }
            java.util.List<DiagnosticJson> diags = new java.util.ArrayList<>();
            java.util.List<String> pretty = new java.util.ArrayList<>();
            if (r instanceof TypeChecker.TypeCheckResult.Failure f) {
                for (TypeChecker.TypeCheckError e : f.getErrors()) {
                    diags.add(JsonDiagnosticFormatter.INSTANCE.fromTypeCheckError(e));
                    // The CLI-s own rendering, reused rather than reimplemented:
                    // error code, --> location, source snippet with a caret,
                    // hints and notes. colorize=false: the pane is plain text
                    // and colours itself by severity.
                    // The parser is handed /tmp/playground.mz so module
                    // resolution has a real path; strip it back to the bare
                    // name the reader typed into.
                    pretty.add(ErrorFormatter.INSTANCE.format(e, source, false)
                        .replace("/tmp/playground.mz", "playground.mz"));
                }
            }
            return withPretty(
                JsonDiagnosticFormatter.INSTANCE.render(
                    JsonDiagnosticFormatter.INSTANCE.errors("playground.mz", null, diags)),
                pretty);
        } catch (Throwable t) {
            if (t instanceof ParseException pe) {
                DiagnosticJson dj = JsonDiagnosticFormatter.INSTANCE.fromParseException(pe);
                return withPretty(
                    JsonDiagnosticFormatter.INSTANCE.render(
                        JsonDiagnosticFormatter.INSTANCE.errors("playground.mz", null,
                            java.util.List.of(dj))),
                    java.util.List.of(
                        ParseErrorFormatter.INSTANCE.format(pe)
                            .replace("/tmp/playground.mz", "playground.mz")));
            }
            // Never surface a bare "null": an exception with no message (e.g.
            // IllegalThreadStateException) left the playground showing "error at ? null",
            // which is impossible to diagnose from the page. Fall back to the class name.
            String msg = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
            return "{\"status\":\"ERRORS\",\"diagnostics\":[{\"severity\":\"ERROR\",\"message\":"
                 + jsonStr(msg) + "}]}";
        }
    }

    /**
     * Splice a "pretty" array of CLI-rendered errors into the check envelope.
     *
     * Appended as a sibling of "diagnostics" rather than a field inside each
     * one, so the structured JSON stays byte-identical to `mez check --json`.
     * Index i of "pretty" corresponds to index i of "diagnostics".
     */
    private static String withPretty(String jsonEnvelope, java.util.List<String> pretty) {
        if (pretty.isEmpty()) return jsonEnvelope;
        int close = jsonEnvelope.lastIndexOf('}');
        if (close < 0) return jsonEnvelope;
        StringBuilder sb = new StringBuilder(jsonEnvelope.substring(0, close));
        sb.append(",\"pretty\":[");
        for (int i = 0; i < pretty.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(jsonText(pretty.get(i)));
        }
        return sb.append("]}").toString();
    }


    /** JSON string escaper that preserves newlines — snippets are multi-line. */
    private static String jsonText(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 32) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append("\"").toString();
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
            TypeChecker tc = new TypeChecker(true);
            TypeChecker.TypeCheckResult r = tc.checkParsedModule(m, Path.of("/tmp"), null, true);

            // Both outcomes carry a typed module, so hover keeps working on
            // code that does not currently typecheck — which is exactly when a
            // reader is most likely to be hovering.
            com.mezze.language.typechecker.TypeCheckResult hoverResult =
                TypeCheckOutputKt.toHoverResult(r);

            HoverInfo.Context ctx = new HoverInfo.Context(
                source,
                hoverResult,
                tc.getTypeAliases(),
                m,
                hoverResult,   // lastSuccessful: single-shot, so the same result
                null,          // selfUri: no document to link back to
                null,          // resolveFileUri: nothing on disk in a browser
                null,          // docFromStore: no project store
                null);         // storeAbilityDecls: likewise

            // HoverInfo is 0-based (LSP Position); typeAt is called with the
            // 1-based Loc convention the editor uses.
            String md = HoverInfo.INSTANCE.at(ctx, line - 1, col - 1);
            return md == null ? "" : md;
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * Format, wrapping to the width the reader actually has.
     *
     * The CLI formatter is fixed at 100 columns by design, and stays that way.
     * In a browser the editor pane can be much narrower or wider than that, so
     * the playground measures its own width and passes it here. Only where
     * lines break changes; every other style rule is the same.
     *
     * @param cols target line width; 0 or less keeps the standard 100.
     */
    public static String format(String source, int cols) {
        try {
            Formatter f = cols > 0
                ? new Formatter(new WidthOverride(cols))
                : new Formatter();
            return f.format(source, null);
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

    /**
     * Completion candidates at a position, as a JSON array.
     *
     * The engine lives in :langservice and knows nothing about lsp4j or about
     * this bridge, so the work here is building its context and turning its
     * candidates into JSON. Fields the browser cannot use are dropped:
     * additionalEdits and resolveData both assume a workspace on disk.
     */
    /**
     * Module paths for import completion, read once from stdlib/MANIFEST.txt.
     *
     * The engine asks the store for these. PrecompiledStdlib.stdlibTypedModules
     * only holds PRELUDE modules (27 of 65), so std::effects::*, std::math::*
     * and std::codec::* were invisible to `use ` completion. The manifest lists
     * every stdlib file and ships in the same jar, so it is the honest source
     * for "what could I import".
     */
    private static InMemoryMezzeStore moduleStore;

    /**
     * The most recent typecheck that produced a usable typed module.
     *
     * The engine takes a `lastSuccessful` for exactly this: the buffer being
     * completed is mid-edit, so its own typecheck fails and carries no types
     * for the locals in scope. Handing it the same failed result twice meant
     * dot completion worked on top-level bindings (whose types survive a
     * failed check) and returned nothing for anything inside a do block.
     */
    private static com.mezze.language.typechecker.TypeCheckResult lastGood;

    private static synchronized InMemoryMezzeStore moduleStore() {
        if (moduleStore != null) return moduleStore;
        InMemoryMezzeStore st = new InMemoryMezzeStore();
        try (var in = MezzeWeb.class.getResourceAsStream("/stdlib/MANIFEST.txt")) {
            if (in != null) {
                String all = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                for (String raw : all.split("\n")) {
                    String f = raw.trim();
                    if (!f.endsWith(".mz")) continue;
                    // build/resources/main/std/effects/log.mz -> std::effects::log
                    int i = f.indexOf("/std/");
                    String rel = i >= 0 ? f.substring(i + 1) : f;
                    String path = rel.substring(0, rel.length() - 3).replace("/", "::");
                    if (path.endsWith("_prelude")) continue;
                    st.putModule(path, f, "");
                }
            }
        } catch (Throwable ignored) {
            // No manifest: import completion falls back to typed modules.
        }
        moduleStore = st;
        return st;
    }

    /**
     * Names a module exports, for `use a::b::{ … }` completion.
     *
     * Read from the module's own source, which ships in the jar beside the
     * manifest. The completion engine has no path for this — its import
     * regex stops at `::{` — and the alternatives are worse: the precompiled
     * types cover only the prelude, and a store lookup needs a project.
     *
     * Returns null when the module is unknown, so the caller can fall back.
     */
    private static java.util.List<String> moduleExports(String modulePath) {
        String res = "/stdlib/" + modulePath.replace("::", "/") + ".mz";
        try (var in = MezzeWeb.class.getResourceAsStream(res)) {
            if (in == null) return null;
            String src = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            var out = new java.util.ArrayList<String>();
            for (String raw : src.split("\n")) {
                String t = raw.trim();
                if (!t.startsWith("pub ")) continue;
                String rest = t.substring(4).trim();
                // pub let / type / newtype / effect / ability NAME …
                for (String kw : new String[]{"let ", "type ", "newtype ", "effect ", "ability "}) {
                    if (!rest.startsWith(kw)) continue;
                    String name = rest.substring(kw.length()).trim();
                    int end = 0;
                    while (end < name.length()
                           && (Character.isLetterOrDigit(name.charAt(end)) || name.charAt(end) == '_')) end++;
                    if (end > 0) out.add(name.substring(0, end));
                    break;
                }
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Completion inside `use a::b::{ … }`, which the engine does not handle.
     *
     * Returns null when the cursor is not in such a block, so `complete`
     * carries on to the engine.
     */
    private static String selectiveImport(String source, int line, int col) {
        String[] lines = source.split("\n", -1);
        if (line - 1 < 0 || line - 1 >= lines.length) return null;
        String cur = lines[line - 1];
        String upto = cur.substring(0, Math.min(col - 1, cur.length()));

        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("^\\s*(?:pub\\s+)?use\\s+([\\w:]+)::\\{([^}]*)$")
            .matcher(upto);
        if (!m.find()) return null;

        String modulePath = m.group(1);
        String inside = m.group(2);
        // What is already typed after the last comma, and what is already
        // listed — an import should not offer a name twice.
        int lastComma = inside.lastIndexOf(',');
        String typed = inside.substring(lastComma + 1).trim();
        var already = new java.util.HashSet<String>();
        for (String part : inside.split(",")) {
            String t = part.trim();
            if (!t.isEmpty()) already.add(t);
        }
        already.remove(typed);

        java.util.List<String> names = moduleExports(modulePath);
        // An unknown module means an empty list, not a fall-through: inside
        // `::{ … }` the engine has no candidates and answers with keywords,
        // which is worse than nothing.
        if (names == null) return "[]";

        int startCol = col - 1 - typed.length();
        StringBuilder out = new StringBuilder("[");
        int n = 0;
        for (String name : names) {
            if (!name.startsWith(typed) || already.contains(name)) continue;
            if (n++ > 0) out.append(',');
            out.append('{')
               .append("\"label\":").append(jsonOrNull(name)).append(',')
               .append("\"kind\":\"Variable\",")
               .append("\"detail\":").append(jsonOrNull(modulePath)).append(',')
               .append("\"insertText\":").append(jsonOrNull(name)).append(',')
               .append("\"snippet\":false,")
               .append("\"sortText\":").append(jsonOrNull(name)).append(',')
               .append("\"edit\":{\"startLine\":").append(line - 1)
               .append(",\"startCol\":").append(startCol)
               .append(",\"endLine\":").append(line - 1)
               .append(",\"endCol\":").append(col - 1)
               .append(",\"newText\":").append(jsonOrNull(name)).append('}')
               .append('}');
        }
        return out.append(']').toString();
    }

    /**
     * Completion after a qualified prefix in an expression: `map::`, `list::f`.
     *
     * Resolved against the imported module's exports, the same source the
     * selective-import path uses. Without this the engine treats `map::` as
     * an ordinary identifier position and answers with variant constructors.
     *
     * Returns null when the cursor is not after such a prefix.
     */
    private static String qualifiedName(String source, int line, int col) {
        String[] lines = source.split("\n", -1);
        if (line - 1 < 0 || line - 1 >= lines.length) return null;
        String cur = lines[line - 1];
        String upto = cur.substring(0, Math.min(col - 1, cur.length()));
        if (upto.stripLeading().startsWith("use ")) return null;  // handled elsewhere

        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("([a-z_][\\w]*)::([\\w]*)$").matcher(upto);
        if (!m.find()) return null;
        String alias = m.group(1);
        String typed = m.group(2);

        // The alias is whatever the file imported: `use std::collections::map`
        // brings `map` into scope for `map::empty`.
        String modulePath = null;
        for (String l : lines) {
            java.util.regex.Matcher im = java.util.regex.Pattern
                .compile("^\\s*(?:pub\\s+)?use\\s+([\\w:]+)\\s*$").matcher(l);
            if (im.find() && im.group(1).endsWith("::" + alias)) { modulePath = im.group(1); break; }
        }
        if (modulePath == null) return null;

        java.util.List<String> names = moduleExports(modulePath);
        if (names == null) return "[]";

        int startCol = col - 1 - typed.length();
        StringBuilder out = new StringBuilder("[");
        int n = 0;
        for (String name : names) {
            if (!name.startsWith(typed)) continue;
            if (n++ > 0) out.append(',');
            out.append('{')
               .append("\"label\":").append(jsonOrNull(name)).append(',')
               .append("\"kind\":\"Function\",")
               .append("\"detail\":").append(jsonOrNull(modulePath)).append(',')
               .append("\"insertText\":").append(jsonOrNull(name)).append(',')
               .append("\"snippet\":false,")
               .append("\"sortText\":").append(jsonOrNull(name)).append(',')
               .append("\"edit\":{\"startLine\":").append(line - 1)
               .append(",\"startCol\":").append(startCol)
               .append(",\"endLine\":").append(line - 1)
               .append(",\"endCol\":").append(col - 1)
               .append(",\"newText\":").append(jsonOrNull(name)).append('}')
               .append('}');
        }
        return out.append(']').toString();
    }

    public static String complete(String source, int line, int col) {
        if (source == null) return "[]";
        // Handled here rather than by the engine, whose import regex stops
        // at `::{` and would fall through to keywords.
        String selective = selectiveImport(source, line, col);
        if (selective != null) return selective;
        String qualified = qualifiedName(source, line, col);
        if (qualified != null) return qualified;
        try {
            MezzeParser parser = new MezzeParser();
            // parseWithRecovery, not parseParsedModule: the buffer being
            // completed is mid-edit by definition — a dangling `.` or a
            // half-typed `use` is a hard parse error, and the strict parser
            // would throw before the engine ever sees it.
            ParsedModule m = parser.parseWithRecovery(source, "/tmp/playground.mz").getModule();
            TypeChecker tc = new TypeChecker();

            // The typecheck itself can THROW rather than return a Failure:
            // `use std::ef` raises ModuleNotFound while the reader is still
            // typing the path. Completion still has useful context without it
            // (keywords, the module list, bindings from the parse), so a throw
            // must not lose the whole request.
            com.mezze.language.typechecker.TypeCheckResult res = null;
            try {
                TypeChecker.TypeCheckResult r =
                    tc.checkParsedModule(m, Path.of("/tmp"), null, true);
                res = TypeCheckOutputKt.toHoverResult(r);
                // Only a SUCCESS is worth remembering. A Failure also carries a
                // typedModule, so caching on that overwrote the good one with
                // the broken buffer on the very first request and the fallback
                // had nothing better to offer.
                if (r instanceof TypeChecker.TypeCheckResult.Success) lastGood = res;
            } catch (Throwable ignored) {
                // res stays null; the engine treats it as "no type info yet".
            }

            CompletionContext ctx = new CompletionContext(
                "file:///tmp/playground.mz",
                source,
                line - 1,      // engine is 0-based, the editor is 1-based
                col - 1,
                res,
                tc.getTypeAliases(),
                m,
                lastGood != null ? lastGood : res,   // lastSuccessful
                moduleStore(), // module paths for `use ` completion
                parser,        // strip-dot recovery reparses with these
                tc,
                null,          // workspaceRoot: nothing on disk
                (kotlin.jvm.functions.Function1<String, java.nio.file.Path>) uri -> null,
                (kotlin.jvm.functions.Function1<String, String>) use -> use,
                (kotlin.jvm.functions.Function1<String, kotlin.Unit>) msg -> kotlin.Unit.INSTANCE);

            List<CompletionCandidate> items = CompletionEngine.INSTANCE.complete(ctx);

            StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < items.size(); i++) {
                CompletionCandidate c = items.get(i);
                if (i > 0) out.append(',');
                out.append('{')
                   .append("\"label\":").append(jsonOrNull(c.getLabel())).append(',')
                   .append("\"kind\":").append(jsonOrNull(c.getKind().name())).append(',')
                   .append("\"detail\":").append(jsonOrNull(c.getDetail())).append(',')
                   .append("\"insertText\":").append(jsonOrNull(
                       c.getInsertText() == null ? c.getLabel() : c.getInsertText())).append(',')
                   .append("\"snippet\":").append(c.getSnippet()).append(',')
                   .append("\"sortText\":").append(jsonOrNull(c.getSortText())).append(',')
                   // The engine knows exactly which span an item replaces —
                   // for a module path that is the whole `std::eff` typed so
                   // far, not just the last identifier. Without this the
                   // editor has to guess, and guessing on an identifier
                   // boundary leaves `use std::std::effects::http`.
                   .append("\"edit\":").append(editJson(c.getTextEdit()))
                   .append('}');
            }
            return out.append(']').toString();
        } catch (Throwable t) {
            return "[]";
        }
    }

    /** A candidate's replacement range, 0-based and half-open, or null. */
    private static String editJson(com.mezze.language.langservice.completion.NeutralTextEdit e) {
        if (e == null) return "null";
        return "{\"startLine\":" + e.getStartLine()
             + ",\"startCol\":" + e.getStartCol()
             + ",\"endLine\":" + e.getEndLine()
             + ",\"endCol\":" + e.getEndCol()
             + ",\"newText\":" + jsonOrNull(e.getNewText()) + "}";
    }

    /** Like jsonStr, but a null value becomes the JSON literal null. */
    private static String jsonOrNull(String v) {
        if (v == null) return "null";
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < v.length(); i++) {
            char ch = v.charAt(i);
            switch (ch) {
                case '"':  b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (ch < 0x20) b.append(String.format("\\u%04x", (int) ch));
                    else b.append(ch);
            }
        }
        return b.append('"').toString();
    }

    public static void main(String[] args) {
        // This is the single-threaded GraalVM web image: a platform thread cannot
        // be started (even one small probe thread can start, but the real parse/
        // check pools with their large stacks throw IllegalThreadStateException).
        // Tell MezzeExecutors to run everything inline here. Set before any
        // typecheck so the pools never attempt a thread.
        System.setProperty("mezze.wasm.singleThreaded", "true");
        System.setProperty("polyglot.engine.allowUnsupportedPlatform", "true");
        System.setProperty("polyglot.engine.WarnInterpreterOnly", "false");
        System.setProperty("polyglot.engine.userResourceCache", "/tmp/tcache");
        BrowserOverrides.install();
        // Warm the stdlib so the first user call is fast (~750ms -> ~110ms),
        // and smoke-test the whole pipeline on the way up.
        run("use std::effects::log::{ Console, println }\n"
          + "let main = {} -> perform Console in println { msg = \"Hello, Mezze!\" }\n");
        installRun(js -> JSString.of(run(js.asString())));
        installFmt(js -> {
            // src \u0000 cols — same convention as typeAt.
            String[] parts = js.asString().split("\u0000", -1);
            String src = parts[0];
            int cols = 0;
            if (parts.length > 1) {
                try { cols = Integer.parseInt(parts[1]); }
                catch (NumberFormatException e) { cols = 0; }
            }
            return JSString.of(format(src, cols));
        });
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
        installComplete(js -> {
            String[] parts = js.asString().split("\u0000", -1);
            if (parts.length != 3) return JSString.of("[]");
            try {
                return JSString.of(complete(parts[0],
                    Integer.parseInt(parts[1]), Integer.parseInt(parts[2])));
            } catch (NumberFormatException e) { return JSString.of("[]"); }
        });
        System.out.println("mezze-wasm ready: runMezze(src), formatMezze(src), completeMezze(src, line, col)");
    }
}
