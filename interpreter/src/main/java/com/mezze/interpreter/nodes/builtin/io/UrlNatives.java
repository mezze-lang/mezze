package com.mezze.interpreter.nodes.builtin.io;

import com.mezze.datastructures.list.MezzeList;
import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.interpreter.values.MezzeVariant;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Structured URL parse/render natives, backed by {@link java.net.URI}.
 *
 * Introduced in Phase 1 of the codec redesign (task #86). Format-neutral —
 * these natives sit under any future URL-form codec ability layer and are
 * not coupled to a specific ability shape.
 *
 * <h3>Mezze surface</h3>
 *
 * <pre>{@code
 * # __native_url_parse : { s: Str } -> Result Url ParseError
 * # __native_url_render : { url: Url } -> Str
 * }</pre>
 *
 * <h3>{@code Url} record shape</h3>
 * <pre>{@code
 * type Url = {
 *   scheme:    Option Str,   ## e.g. Some { val = "https" }
 *   user_info: Option Str,   ## "alice:pw" from "https://alice:pw@host/"
 *   host:      Option Str,   ## "example.com"
 *   port:      Option Int,   ## -1 in URI becomes None
 *   path:      Str,          ## "" when the URI has no path (RFC 3986)
 *   query:     Option Str,   ## the raw query string, no `?`
 *   fragment:  Option Str,   ## the raw fragment, no `#`
 * }
 * }</pre>
 *
 * <h3>{@code ParseError} record shape</h3>
 * Same shape the JSON parse native uses:
 * {@code { path: [Str], reason: Str, input_ref: Option Str }}.
 *
 * <h3>Semantics</h3>
 * <ul>
 *   <li>{@code parse} uses {@link java.net.URI#URI(String)}, which follows
 *       RFC 2396. Absolute and relative URIs both parse; components that
 *       aren't present come back as {@code None}.</li>
 *   <li>An {@code Int} port is used because URI's port is {@code int};
 *       {@code -1} (URI's "no port") is mapped to {@code None} on the
 *       Mezze side.</li>
 *   <li>{@code render} uses {@link java.net.URI#URI(String, String, String,
 *       int, String, String, String)} for a canonical reconstruction. It
 *       accepts all-{@code None} components and produces the corresponding
 *       relative URI.</li>
 * </ul>
 */
public final class UrlNatives {

    private static final String[] URL_FIELDS = {
        "scheme", "user_info", "host", "port", "path", "query", "fragment"
    };

    private UrlNatives() {}

    /**
     * Parse a URL string into the {@code Url} record.
     * <p>Takes {@code { s: Str }}, returns {@code Result Url ParseError}.
     */
    @TruffleBoundary
    public static MezzeVariant urlParse(MezzeRecord args) {
        Object sValue = getField(args, "s");
        if (!(sValue instanceof String s)) {
            return parseError("Expected Str for field 's'", null);
        }
        try {
            URI uri = new URI(s);
            MezzeRecord url = uriToRecord(uri);
            return MezzeVariant.ok(url);
        } catch (URISyntaxException e) {
            String msg = e.getMessage() != null ? e.getMessage() : "Invalid URI syntax";
            return parseError("URL parse error: " + msg, s);
        } catch (RuntimeException e) {
            // URI raises IllegalArgumentException for extreme inputs — treat
            // as a parse failure rather than propagating to the interpreter.
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return parseError("URL parse error: " + msg, s);
        }
    }

    /**
     * Render a {@code Url} record back to a URL string.
     * <p>Takes {@code { url: Url }}, returns {@code Str}.
     */
    @TruffleBoundary
    public static String urlRender(MezzeRecord args) {
        Object urlValue = getField(args, "url");
        if (!(urlValue instanceof MezzeRecord url)) {
            throw new IllegalArgumentException(
                "Expected Url record for field 'url', got: " +
                (urlValue == null ? "null" : urlValue.getClass().getSimpleName()));
        }
        String scheme = optStr(url, "scheme");
        String userInfo = optStr(url, "user_info");
        String host = optStr(url, "host");
        int port = optInt(url, "port");
        String path = strField(url, "path", "");
        String query = optStr(url, "query");
        String fragment = optStr(url, "fragment");
        try {
            URI uri = new URI(scheme, userInfo, host, port, path, query, fragment);
            // toASCIIString() percent-encodes any non-ASCII component the
            // decoded fields carry, giving a wire-safe rendering. Plain
            // toString() leaves non-ASCII characters raw, which is not a
            // valid RFC 3986 URI reference — a downstream HTTP client
            // would then reject the result. Use the ASCII form so
            // parse ∘ render is stable across the wire.
            return uri.toASCIIString();
        } catch (URISyntaxException e) {
            // Round-trip should not fail on a well-formed input; if it does,
            // surface the message so it's not silently swallowed.
            throw new IllegalArgumentException(
                "URL render error: " + e.getMessage(), e);
        }
    }

    // -------- helpers --------

    private static MezzeRecord uriToRecord(URI uri) {
        Object scheme = optStrValue(uri.getScheme());
        Object userInfo = optStrValue(uri.getUserInfo());
        Object host = optStrValue(uri.getHost());
        int p = uri.getPort();
        Object port = (p == -1) ? MezzeVariant.none() : MezzeVariant.some((long) p);
        // URI#getPath() returns "" when absent; keep the Str-with-default
        // shape rather than lifting into Option — matches the task spec.
        String path = uri.getPath();
        if (path == null) path = "";
        Object query = optStrValue(uri.getQuery());
        Object fragment = optStrValue(uri.getFragment());
        return MezzeRecord.fromFields(URL_FIELDS, new Object[]{
            scheme, userInfo, host, port, path, query, fragment
        });
    }

    private static Object optStrValue(String s) {
        return s == null ? MezzeVariant.none() : MezzeVariant.some(s);
    }

    private static MezzeVariant parseError(String reason, String inputRef) {
        MezzeRecord error = MezzeRecord.fromFields(
            new String[]{"path", "reason", "input_ref"},
            new Object[]{
                MezzeList.empty(),
                reason,
                inputRef == null ? MezzeVariant.none() : MezzeVariant.some(inputRef)
            }
        );
        return MezzeVariant.err(error);
    }

    private static Object getField(MezzeRecord r, String name) {
        int idx = r.getFieldIndex(name);
        if (idx < 0) return null;
        return r.getField(idx);
    }

    private static String optStr(MezzeRecord r, String field) {
        Object v = getField(r, field);
        if (v instanceof MezzeVariant mv) {
            if (mv.isNone()) return null;
            if (mv.isSome()) {
                Object inner = mv.getPayloadValue();
                if (inner instanceof MezzeRecord rec) {
                    Object val = rec.getField("val");
                    return val instanceof String s ? s : null;
                }
                return inner instanceof String s ? s : null;
            }
        }
        return v instanceof String s ? s : null;
    }

    private static int optInt(MezzeRecord r, String field) {
        Object v = getField(r, field);
        if (v instanceof MezzeVariant mv) {
            if (mv.isNone()) return -1;
            if (mv.isSome()) {
                Object inner = mv.getPayloadValue();
                if (inner instanceof MezzeRecord rec) {
                    Object val = rec.getField("val");
                    if (val instanceof Long l) return l.intValue();
                    if (val instanceof Integer i) return i;
                }
                if (inner instanceof Long l) return l.intValue();
                if (inner instanceof Integer i) return i;
            }
        }
        if (v instanceof Long l) return l.intValue();
        if (v instanceof Integer i) return i;
        return -1;
    }

    private static String strField(MezzeRecord r, String field, String defaultVal) {
        Object v = getField(r, field);
        return v instanceof String s ? s : defaultVal;
    }
}
