import com.mezze.datastructures.map.MezzeMap;
import com.mezze.interpreter.nodes.builtin.io.BytesNatives;
import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.interpreter.values.MezzeVariant;
import org.graalvm.webimage.api.JS;
import org.graalvm.webimage.api.JSString;
import java.nio.charset.StandardCharsets;

/**
 * Browser replacements for host-touching natives.
 *
 * Two jobs:
 *  1. HTTP via synchronous XMLHttpRequest — `IONatives.httpRequest` is
 *     synchronous (request record in, response out) and sync XHR is the only
 *     browser API with that shape. No Worker, no SharedArrayBuffer, no
 *     COOP/COEP headers, no change to Mezze's Http effect.
 *  2. `unsupported(...)` — the single throw used for every native that cannot
 *     work here, carrying ONLY the performer name.
 */
public final class BrowserNatives {

    /** Thrown for any effect impl that cannot run in the browser. */
    public static final class UnsupportedEffect extends RuntimeException {
        public final String performer;
        public UnsupportedEffect(String performer) {
            super(performer);           // message is just the impl name
            this.performer = performer;
        }
    }

    public static Object unsupported(String performer) {
        throw new UnsupportedEffect(performer);
    }

    // ---- HTTP via sync XHR ---------------------------------------------

    /**
     * Returns "status\nbody", or "0\n<error>" when the request could not be
     * made at all (CORS, DNS, offline). Sync XHR blocks the calling thread,
     * which is acceptable behind a Run button.
     */
    @JS(args = {"method", "url", "body"}, value =
        "try {" +
        // JSString values arrive as objects, not JS primitives — String() them
        // or x.open() receives "[object Object]" and every request fails.
        "  const m = String(method), u = String(url), b = String(body);" +
        "  if (typeof XMLHttpRequest === 'undefined')" +
        "    return '0\\nno XMLHttpRequest in this environment';" +
        "  const x = new XMLHttpRequest();" +
        "  x.open(m, u, false);" +
        "  x.send(b === '' ? null : b);" +
        "  return x.status + '\\n' + x.responseText;" +
        "} catch (e) { return '0\\n' + (e && e.message ? e.message : String(e)); }")
    private static native JSString xhr(JSString method, JSString url, JSString body);

    /**
     * Browser HTTP client handle. The JVM native builds a Helidon WebClient,
     * which cannot load here — and it fails at CREATE time, before any request
     * is made. There is no connection to pool with XHR, so the handle is inert.
     */
    public static Object httpClientCreate(MezzeRecord args) {
        return MezzeRecord.fromFields(new String[]{"browser"}, new Object[]{Boolean.TRUE});
    }

    /** Nothing to close — XHR holds no connection. */
    public static Object httpClientClose(MezzeRecord args) {
        return MezzeRecord.fromFields(new String[]{}, new Object[]{});
    }

    /** Drop-in for `__native_http_request` in a browser build. */
    public static Object httpRequest(MezzeRecord args) {
        Object reqObj = args.getField("req");
        if (!(reqObj instanceof MezzeRecord req))
            throw new RuntimeException("Expected 'req' to be a record");

        String url = String.valueOf(req.getField("url"));
        Object m = req.getField("method");
        String method = (m instanceof MezzeVariant mv) ? mv.getConstructorName() : String.valueOf(m);
        if (method == null || method.isEmpty()) method = "GET";
        method = method.toUpperCase();

        Object b = req.getField("body");
        String bodyStr = "";
        if (b instanceof BytesNatives.MezzeBytes mb) bodyStr = new String(mb.getData(), StandardCharsets.UTF_8);

        String raw = xhr(JSString.of(method), JSString.of(url), JSString.of(bodyStr)).asString();
        int nl = raw.indexOf('\n');
        long status = 0;
        String respBody = raw;
        if (nl >= 0) {
            try { status = Long.parseLong(raw.substring(0, nl)); } catch (NumberFormatException ignored) {}
            respBody = raw.substring(nl + 1);
        }

        // Must be Ok-WRAPPED, exactly as IONatives does via createOk(...).
        // Returning a bare record gives "Non-exhaustive match" at the call site.
        return MezzeVariant.ok(MezzeRecord.fromFields(
            new String[]{"status", "headers", "body", "url"},
            new Object[]{status, MezzeMap.empty(),
                         new BytesNatives.MezzeBytes(respBody.getBytes(StandardCharsets.UTF_8)), url}));
    }
}
