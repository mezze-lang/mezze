package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.Timeout.ThreadMode
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

/**
 * A streamed response body that FAILS part-way does not look complete (BUG 28).
 *
 * `response_file`'s chunk step answered `Done` for two different things — a
 * finished file and a FAILED read:
 *
 *     Err { error = _ } -> Done                                   # the read failed
 *     Ok { val = b } -> if bytes::size { b = b } == 0 then Done    # genuine EOF
 *
 * `Done` sends the terminal zero-length chunk, and for a chunked response that
 * is the ONLY signal the client has that the body is whole. So a download dying
 * at 40 KB of 200 arrived as a 200 carrying a 40 KB body, indistinguishable from
 * a 40 KB file. Silent truncation, with a success on it.
 *
 * `Step` has no failure arm — Yield, Skip, Done — and it is not getting one: it
 * is the core lazy protocol every `iter` user shares, and a `Result` element
 * would make every chunk on the healthy path pay for the exceptional one. The
 * failure travels as the effect it IS, which is what the two sibling streaming
 * paths already do (the inbound reader throws; `with_download` reports from
 * `close`). Three pieces, each measured here:
 *
 *  1. the guest step THROWS instead of answering `Done`;
 *  2. the host drain (`sendChunks`) does NOT close the response on a body
 *     failure — a clean close is a PROMISE that the body is whole — and lets the
 *     failure reach Helidon, which drops the connection. Same conclusion axum
 *     reaches for an errored stream, and for the same reason: once the status
 *     and headers are gone there is nothing else to say;
 *  3. the client names a truncated transfer instead of leaking it. Helidon's
 *     chunked decoder DOES raise on a premature close
 *     (`DataReader$InsufficientDataAvailableException`) but it is a
 *     RuntimeException, so it sailed past `httpStreamRead`'s IOException arm and
 *     out of the guest program as a raw Java exception.
 *
 * WHAT THE TYPE CANNOT SAY, recorded because it is a real limit rather than an
 * oversight: `iter::unfold` attributes a step's effects to the CONSTRUCTION
 * site, so `response_file` declares `Exception having 'error = FsError` even
 * though the throw happens later, at drain, on a server thread. A caller cannot
 * usefully HANDLE it — the handler that returned the response is long gone by
 * then, so the perform arrives at the drain with no handler and the host turns
 * it into the abort. The row says the operation can fail, which is true and
 * worth knowing; it does not promise you can catch it. Recovery is not
 * available at that point to anyone, which is the same position axum is in.
 */
class HttpStreamedBodyFailureTest {

    /** 20 KB, so ten of them are past any buffer and the headers are long gone. */
    private val block = "0123456789abcdefghij".repeat(1000)

    private val head = """
        use std::effects::http::{ Request, Response, Method, Get, HttpClient, SimpleHttpClient, HttpDownload, SimpleHttpDownload, request, format_error, response_stream, with_download, default_http_config }
        use std::effects::http_server::{ HttpServer, HelidonHttpServerWith, ServerConfig }
        use std::effects::http_router::{ route, otherwise, Matched }
        use std::effects::exception::{ throw }
        use std::collections::{ bytes, iter, map, seq }
        use std::str
        use std::int

        let not_found: { req: Request } -> Response = { req } -> {
          status = 404, headers = map::empty {}, body = bytes::from_str { s = "nope" },
          url = req.url, chunks = None,
        }
        let listen_on_any_port: ServerConfig = { port = 0, host = "127.0.0.1", workers = 4, stream_bodies = False }
    """.trimIndent()

    /**
     * Serve a body that yields [good] chunks of 20 KB and then throws, and
     * report what the client makes of it.
     */
    private fun overTheWire(good: Int): String = MezzeTestHelper.run(
        """
        $head
        let blk: Bytes = bytes::from_str { s = "$block" }
        let failing: { b: Bytes } -> Iter Bytes <Exception having 'error = Str> = { b } ->
          iter::unfold {
            state = 0,
            step = { s } -> if s >= $good then throw { error = "disk died" }
              else Yield { val = b, next = s + 1 },
          }
        let app = otherwise {
          routes = route { method = Get, path = "/big", handler = { m } -> response_stream {
            status = 200, headers = map::empty {}, chunks = failing { b = blk },
          } },
          fallback = not_found,
        }
        let go: {} -> Str <*> = {} ->
          perform HelidonHttpServerWith { config = listen_on_any_port } in
            perform SimpleHttpClient in perform SimpleHttpDownload in do
              let h = HttpServer.serve { handler = app }
              let out = match with_download {
                req = request { url = str::concat { l = "http://127.0.0.1:", r = str::concat {
                  l = int::to_str { n = h.port }, r = "/big" } } },
                config = default_http_config,
                f = { resp } -> str::concat {
                  l = str::concat { l = "status=", r = int::to_str { n = resp.status } },
                  r = str::concat { l = " bytes=", r = int::to_str { n = iter::fold {
                    it = match resp.chunks is
                      None -> iter::from_seq { xs = [] }
                      Some { val = cs } -> cs,
                    init = 0, f = { acc, x } -> acc + bytes::size { b = x },
                  } } },
                },
              } is
                Ok { val = s } -> str::concat { l = "ok ", r = s }
                Err { error = e } -> str::concat { l = "ERR:", r = format_error { err = e } }
              h.stop {}
              out
        go {}
        """.trimIndent(),
    ).toString()

    /**
     * THE case: 200 KB already on the wire, so no status is available any more.
     *
     * The client must NOT come back with a complete-looking short body. Before
     * the fix this answered `ok status=200 bytes=200000` — a success, with the
     * terminal chunk sent, for a body that died.
     */
    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
    fun `a body that fails after the headers are gone is reported, not completed`() {
        val out = overTheWire(good = 10)
        assertTrue(out.startsWith("ERR:"), "a failed body must not answer Ok; got: $out")
        assertTrue(
            out.contains("ended early"),
            "the failure must name the truncation rather than leak a Java exception; got: $out",
        )
    }

    /**
     * The same failure BEFORE anything flushed, where Helidon can still choose a
     * status. A 500 is the right answer and the client sees it; what matters is
     * that it is not a 200.
     */
    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
    fun `a body that fails before flushing becomes a 500`() {
        val out = overTheWire(good = 0)
        assertTrue(!out.contains("status=200"), "a failed body must not answer 200; got: $out")
    }

    /**
     * CONTROL: a healthy stream of the same shape still completes, so the fix
     * buys the failure case without costing the ordinary one.
     */
    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
    fun `a healthy stream still completes`() {
        val out = MezzeTestHelper.run(
            """
            $head
            let blk: Bytes = bytes::from_str { s = "$block" }
            let app = otherwise {
              routes = route { method = Get, path = "/big", handler = { m } -> response_stream {
                status = 200, headers = map::empty {},
                chunks = iter::from_seq { xs = [blk, blk, blk, blk, blk, blk, blk, blk, blk, blk] },
              } },
              fallback = not_found,
            }
            let go: {} -> Str <*> = {} ->
              perform HelidonHttpServerWith { config = listen_on_any_port } in
                perform SimpleHttpClient in perform SimpleHttpDownload in do
                  let h = HttpServer.serve { handler = app }
                  let out = match with_download {
                    req = request { url = str::concat { l = "http://127.0.0.1:", r = str::concat {
                      l = int::to_str { n = h.port }, r = "/big" } } },
                    config = default_http_config,
                    f = { resp } -> iter::fold {
                      it = match resp.chunks is
                        None -> iter::from_seq { xs = [] }
                        Some { val = cs } -> cs,
                      init = 0, f = { acc, x } -> acc + bytes::size { b = x },
                    },
                  } is
                    Ok { val = n } -> str::concat { l = "ok:", r = int::to_str { n = n } }
                    Err { error = e } -> str::concat { l = "ERR:", r = format_error { err = e } }
                  h.stop {}
                  out
            go {}
            """.trimIndent(),
        ).toString()
        assertTrue(out == "ok:200000", "a healthy 200 KB stream must still add up; got: $out")
    }
}
