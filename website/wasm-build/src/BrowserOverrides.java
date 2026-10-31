import com.mezze.interpreter.nodes.builtin.NativeDispatch;
import java.util.ArrayList;

/**
 * Installs the browser native table. Call once before evaluating user code.
 *
 * Policy — FAIL CLOSED:
 *  - HTTP is bridged to synchronous XMLHttpRequest (real requests, CORS-limited).
 *  - Every native on the {@link #ALLOW_PREFIXES} list runs as built: pure value
 *    and data-structure ops, in-memory state, the in-memory / mock / seeded
 *    effect backends, clock *reads*, and console output.
 *  - EVERYTHING ELSE is denied — overridden to throw UnsupportedEffect, which
 *    MezzeWeb turns into "Not supported in the browser: <effect>".
 *
 * The allowlist is the whole point: a new host effect added to the stdlib is
 * denied by default and reports a clean message, rather than falling through to
 * the real host native and crashing the page. The previous model denied a fixed
 * set of prefixes and let everything else through — fail OPEN — so every effect
 * that shipped after it (Db, Env, Os, Signal, Jvm, Polyglot, …) ran a host
 * native that cannot work here. Nothing is silently no-opped either: a host
 * effect either works for real (HTTP) or fails loudly by name.
 */
public final class BrowserOverrides {

    private static boolean installed = false;

    /**
     * Native-name prefixes that are safe to run in the browser. A native whose
     * name starts with any of these runs as built; anything else is denied.
     *
     * The trailing '_' matters: it keeps `mut_` from also matching `mutex_` or
     * `mutation_`, which are classified differently below.
     */
    private static final String[] ALLOW_PREFIXES = {
        // --- console / log output (captured into the output pane) ----------
        "__native_console_", "__native_log_", "__native_debug_", "__native_inspect_",

        // --- pure values & data structures ---------------------------------
        "__native_bool_", "__native_int_", "__native_nat_", "__native_float_",
        "__native_frac_", "__native_char_", "__native_string_", "__native_bytes_",
        "__native_buffer_", "__native_url_", "__native_json_", "__native_codec_",
        "__native_list_", "__native_seq_", "__native_iter_", "__native_map_",
        "__native_set_", "__native_sorted_", "__native_deque_", "__native_nel_",
        "__native_make_", "__native_negate_", "__native_abs_", "__native_reduce_",
        "__native_structural_",

        // --- dates/time as values + clock READS (sleep stays denied) -------
        "__native_datetime_", "__native_day_", "__native_hour_", "__native_minute_",
        "__native_second_", "__native_month_", "__native_year_", "__native_zone_",
        "__native_nanos_", "__native_duration_", "__native_instant_",

        // --- in-memory mutable state (pure, per-context) -------------------
        "__native_mut_", "__native_mutation_", "__native_ref_", "__native_atom_",
        "__native_tvar_", "__native_stm_", "__native_cancellation_",

        // --- in-memory / mock / deterministic / seeded effect backends -----
        // memfs = in-memory filesystem; mock = mock stdin/env/http backends;
        // test = deterministic test clock; the random + sampler families are a
        // pure seeded PRNG, so Random and property-testing run for real.
        "__native_memfs_", "__native_mock_", "__native_test_",
        "__native_random_", "__native_seeded_", "__native_reset_", "__native_update_",
        "__native_draw_", "__native_draws_", "__native_shrink_",
        "__native_covering_", "__native_observe_", "__native_scripted_",

        // --- HTTP client (bridged to sync XHR in install()) ----------------
        "__native_http_request", "__native_http_client_",
    };

    /**
     * Prefix -> the performer name the reader wrote, so the denial message names
     * the effect rather than the raw native. Only needs to cover the host
     * families; a native that matches none of these is named by itself, which
     * is still a clear "not supported" rather than a crash.
     */
    private static final String[][] DENY_LABELS = {
        {"__native_fs_",          "LocalFileSystem"},
        {"__native_stdin_",       "SystemStdin"},
        {"__native_process_",     "SystemProcess"},
        {"__native_env_",         "Env / Os / Args / Cwd"},
        {"__native_db_",          "Db"},
        {"__native_signal_",      "Signal"},
        {"__native_jvm_",         "Jvm"},
        {"__native_polyglot_",    "Polyglot"},
        {"__native_http_server_", "HelidonHttpServer"},
        {"__native_time_",        "SystemTime.sleep"},
        {"__native_async_",       "LoomAsync"},
        {"__native_loom_",        "LoomAsync"},
        {"__native_promise_",     "LoomAsync"},
        {"__native_agent_",       "LoomAsync"},
        {"__native_channel_",     "LoomAsync (channel)"},
        {"__native_latch_",       "LoomAsync (latch)"},
        {"__native_mutex_",       "LoomAsync (mutex)"},
        {"__native_semaphore_",   "LoomAsync (semaphore)"},
        {"__native_waitgroup_",   "LoomAsync (waitgroup)"},
        {"__native_parallel_",    "LoomParallel"},
        {"__native_osthread_",    "LoomParallel"},
        {"__native_comptime_",    "Comptime"},
        {"__native_codebase_",    "Codebase"},
        {"__native_syntax_",      "Codebase"},
    };

    public static void install() {
        if (installed) return;
        installed = true;

        // Real HTTP through sync XHR. The client handles must be overridden
        // too: httpClientCreate builds a Helidon WebClient, which fails to
        // load in the browser BEFORE __native_http_request is ever reached.
        // These names are on the allowlist, so the deny sweep below leaves them.
        NativeDispatch.overrideHandler("__native_http_request",
            (args, call) -> BrowserNatives.httpRequest(args));
        NativeDispatch.overrideHandler("__native_http_client_create",
            (args, call) -> BrowserNatives.httpClientCreate(args));
        NativeDispatch.overrideHandler("__native_http_client_close",
            (args, call) -> BrowserNatives.httpClientClose(args));

        // Deny every native that is not explicitly allowed. Copy the names
        // first: overrideHandler writes back into the same map this iterates.
        for (String name : new ArrayList<>(NativeDispatch.registeredNativeNames())) {
            if (isAllowed(name)) continue;
            final String label = labelFor(name);
            NativeDispatch.overrideHandler(name,
                (args, call) -> BrowserNatives.unsupported(label));
        }
    }

    private static boolean isAllowed(String name) {
        for (String p : ALLOW_PREFIXES) if (name.startsWith(p)) return true;
        return false;
    }

    private static String labelFor(String name) {
        for (String[] d : DENY_LABELS) if (name.startsWith(d[0])) return d[1];
        return name;   // unknown host native: name it rather than crash
    }
}
