import com.mezze.language.ast.TypePrinterKt;
import com.mezze.language.parser.MezzeParser;
import com.mezze.language.parsedast.ParsedModule;
import com.mezze.language.typechecker.TypeChecker;
import com.mezze.language.typedast.TypedModule;
import com.mezze.language.typechecker.lookup.TypedAstLookup;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Precomputes hover types for the landing page's fixed code samples.
 *
 * Same logic as MezzeWeb.typeAt, run on the JVM at build time: the landing page
 * shows four samples that never change, so shipping a small position -> type map
 * gives the same hovers as the tour without the 55MB runtime.
 *
 * Usage: HoverExtractor <sample.mz>... — prints JSON to stdout.
 */
public final class HoverExtractor {

    public static void main(String[] args) throws Exception {
        StringBuilder out = new StringBuilder("{\n");
        for (int f = 0; f < args.length; f++) {
            String source = Files.readString(Path.of(args[f]));
            String name = Path.of(args[f]).getFileName().toString().replaceAll("\\.mz$", "");
            out.append("  ").append(quote(name)).append(": {\n");

            Map<String, String> hovers = extract(source);
            int i = 0;
            for (Map.Entry<String, String> e : hovers.entrySet()) {
                out.append("    ").append(quote(e.getKey())).append(": ")
                   .append(quote(e.getValue()));
                out.append(++i < hovers.size() ? ",\n" : "\n");
            }
            out.append(f + 1 < args.length ? "  },\n" : "  }\n");
        }
        out.append("}\n");
        System.out.print(out);
    }

    /** Every position that resolves to a type, keyed "line:col". */
    private static Map<String, String> extract(String source) {
        Map<String, String> hovers = new LinkedHashMap<>();
        TypedModule tm = typecheck(source);
        if (tm == null) return hovers;

        String[] lines = source.split("\n", -1);
        for (int line = 1; line <= lines.length; line++) {
            String text = lines[line - 1];
            for (Span span : identifiers(text)) {
                // Probe the middle of the token: findAtPosition wants a
                // position inside the span, and edges are ambiguous.
                int col = span.start + 1 + (span.length() / 2);
                String type = typeAt(tm, line, col);
                if (type.isEmpty()) continue;
                // Record the whole span so hovering anywhere in the word works.
                for (int c = span.start + 1; c <= span.end; c++) {
                    hovers.put(line + ":" + c, type);
                }
            }
        }
        return hovers;
    }

    private static TypedModule typecheck(String source) {
        try {
            ParsedModule m = new MezzeParser().parseParsedModule(source, "/tmp/sample.mz");
            TypeChecker.TypeCheckResult r =
                new TypeChecker().checkParsedModule(m, Path.of("/tmp"), null, true);
            if (!(r instanceof TypeChecker.TypeCheckResult.Success s)) return null;
            return s.getTypedModule();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String typeAt(TypedModule tm, int line, int col) {
        try {
            TypedAstLookup.LookupResult hit =
                TypedAstLookup.INSTANCE.findAtPosition(tm, line, col);
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

    private record Span(int start, int end) {
        int length() { return end - start; }
    }

    /**
     * Word spans in the code, skipping comments and literal string text.
     *
     * Interpolations are code, not text: everything inside $(...) is scanned,
     * because those expressions are typechecked like any other and deserve a
     * hover. Only the literal characters around them are skipped.
     */
    private static List<Span> identifiers(String text) {
        List<Span> spans = new ArrayList<>();
        int hash = commentStart(text);
        String scan = hash >= 0 ? text.substring(0, hash) : text;

        int i = 0;
        boolean inString = false;
        int interpDepth = 0;
        while (i < scan.length()) {
            char c = scan.charAt(i);

            if (c == '"' && interpDepth == 0) {
                inString = !inString;
                i++;
                continue;
            }
            if (inString && interpDepth == 0
                && c == '$' && i + 1 < scan.length() && scan.charAt(i + 1) == '(') {
                interpDepth = 1;
                i += 2;
                continue;
            }
            if (interpDepth > 0) {
                if (c == '(') interpDepth++;
                else if (c == ')') { interpDepth--; i++; continue; }
            }
            // Inside a string but outside an interpolation: literal text.
            if (inString && interpDepth == 0) { i++; continue; }

            if (!(Character.isLetter(c) || c == '_')) { i++; continue; }
            int start = i;
            while (i < scan.length()
                   && (Character.isLetterOrDigit(scan.charAt(i)) || scan.charAt(i) == '_')) i++;
            spans.add(new Span(start, i));
        }
        return spans;
    }

    /** Index of a line comment, ignoring any # that sits inside a string. */
    private static int commentStart(String text) {
        boolean inString = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') inString = !inString;
            else if (c == '#' && !inString) return i;
        }
        return -1;
    }

    private static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"'  -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\t' -> b.append("\\t");
                default   -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }
}
