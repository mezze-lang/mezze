import com.mezze.language.parser.MezzeParser;
import com.mezze.language.parsedast.ParsedModule;
import com.mezze.language.typechecker.TypeChecker;
import com.mezze.language.truffleir.FunctionIR;
import com.mezze.interpreter.runtime.MezzeLanguage;
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
 * Browser entry point. Installs globalThis.runMezze(source) -> output string.
 *
 * NOTE: @JS.Export is NOT implemented in Web Image yet (see the API guide), so
 * the supported pattern is a @JS native method that assigns a Java lambda onto
 * the JS global scope.
 *
 * The WASM module is built ONCE and served as a static asset; user source
 * arrives at runtime, so a run costs ~110ms rather than a rebuild.
 */
public final class MezzeWeb {

    @JS(args = {"fn"}, value = "globalThis.runMezze = (src) => fn(src);")
    private static native void install(Function<JSString, JSString> fn);

    public static String run(String source) {
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
            return (captured.isEmpty() ? "" : captured + "\n")
                 + "ERROR: " + String.valueOf(t.getMessage()).split("\n")[0];
        }
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
        run("let main = {} -> 1\n");          // warm the stdlib
        install(js -> JSString.of(run(js.asString())));
        System.out.println("mezze-wasm ready: call runMezze(source)");
    }
}
