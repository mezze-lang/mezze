import com.mezze.interpreter.nodes.builtin.NativeDispatch;

/**
 * Installs the browser native table. Call once before evaluating user code.
 *
 * Policy:
 *  - HTTP is bridged to synchronous XMLHttpRequest (real requests, CORS-limited).
 *  - Everything else that touches the host throws UnsupportedEffect carrying
 *    ONLY the performer name; MezzeWeb turns that into a one-line message.
 *  - Nothing is silently no-opped. `LocalFileSystem` used to "succeed" against a
 *    filesystem that does not exist, which is the failure mode this prevents.
 */
public final class BrowserOverrides {

    private static boolean installed = false;

    public static void install() {
        if (installed) return;
        installed = true;

        // Real HTTP through sync XHR. The client handles must be overridden
        // too: httpClientCreate builds a Helidon WebClient, which fails to
        // load in the browser BEFORE __native_http_request is ever reached.
        NativeDispatch.overrideHandler("__native_http_request",
            (args, call) -> BrowserNatives.httpRequest(args));
        NativeDispatch.overrideHandler("__native_http_client_create",
            (args, call) -> BrowserNatives.httpClientCreate(args));
        NativeDispatch.overrideHandler("__native_http_client_close",
            (args, call) -> BrowserNatives.httpClientClose(args));

        // Host-touching natives -> named failure. Prefix -> performer the user wrote.
        deny("__native_fs_",        "LocalFileSystem");
        deny("__native_stdin_",     "SystemStdin");
        deny("__native_process_",   "SystemProcess");
        deny("__native_async_",     "LoomAsync");
        deny("__native_parallel_",  "LoomParallel");
        deny("__native_time_sleep", "SystemTime.sleep");
        deny("__native_http_server_", "HelidonHttpServer");
    }

    /** Deny every registered native starting with {@code prefix}. */
    private static void deny(String prefix, String performer) {
        for (String name : NativeDispatch.registeredNativeNames()) {
            if (name.startsWith(prefix)) {
                NativeDispatch.overrideHandler(name,
                    (args, call) -> BrowserNatives.unsupported(performer));
            }
        }
    }
}
