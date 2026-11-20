package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.Timeout.ThreadMode
import java.util.concurrent.TimeUnit

/**
 * Server-Sent Events: `sse { f }` answers a `text/event-stream` response whose
 * events `f` emits through `send`, as it runs.
 *
 * Effects live on arrows, so the producer is a FUNCTION that runs for the life
 * of the connection, not a lazy Iter of events: `sleep`, a DB read, a channel
 * take all happen inside `f`, on the connection thread, where the handlers
 * installed around `serve` are. Live, each event is flushed as it is sent;
 * with no connection the sink buffers and the events become the body, so the
 * same handler is testable without a port.
 */
class HttpSseTest {

    private val head = """
        use std::effects::http::{ Request, Response, Method, request, format_error, body_str, sse, sse_event, sse_format, SseEvent, HttpClient, SimpleHttpClient, HttpDownload, SimpleHttpDownload, Download, with_download, chunks, default_http_config, HttpServer, HelidonHttpServerWith, ServerConfig, route, otherwise, Matched }
        use std::effects::async::{ sleep }
        use std::effects::time::{ Time, SystemTime }
        use std::effects::early_return::{ EarlyReturn, return }
        use std::collections::{ bytes, map, seq }
        use std::str
        use std::int

        let not_found: { req: Request } -> Response = { req } -> {
          status = 404, headers = map::empty {}, body = bytes::from_str { s = "nope" },
          url = req.url, chunks = None,
        }
        let text_of: { resp: Response } -> Str = { resp } -> match body_str { resp = resp } is
          Ok { val } -> val
          Err { error = _ } -> "could not decode"
        let listen_on_any_port: ServerConfig = { port = 0, host = "127.0.0.1", workers = 4, stream_bodies = False, helidon_print_log = Off }
    """.trimIndent()

    private fun url(path: String) =
        "\"http://127.0.0.1:\$(int::to_str { n = h.port })$path\""

    // ==================== the wire form ====================

    @Test
    fun `a data-only event is one data line and a blank line`() {
        assertEquals(
            "data: tick\n\n",
            MezzeTestHelper.run("$head\nsse_format { ev = sse_event { data = \"tick\" } }"),
        )
    }

    @Test
    fun `event name, id and multi-line data are each their own line`() {
        assertEquals(
            "event: update\nid: 7\ndata: a\ndata: b\n\n",
            MezzeTestHelper.run("$head\nsse_format { ev = { data = \"a\\nb\", event = Some { val = \"update\" }, id = Some { val = \"7\" } } }"),
        )
    }

    // ==================== no connection: the events become the body ====================

    @Test
    fun `without a connection sse collects the events into the response body`() {
        val r = MezzeTestHelper.run(
            """
            $head
            let resp = sse { f = { send } -> do
              send { ev = sse_event { data = "a" } }
              send { ev = sse_event { data = "b" } } }
            "$(int::to_str { n = resp.status })|$(match map::get { m = resp.headers, k = "content-type" } is
              Some { val } -> val
              None -> "no-type")|$(text_of { resp = resp })"
            """.trimIndent(),
        )
        assertEquals("200|text/event-stream|data: a\n\ndata: b\n\n", r)
    }

    // ==================== over the wire ====================

    /** A whole-body client sees every event, in order, under the SSE content type. */
    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
    fun `a client receives the events a handler sends`() {
        val r = MezzeTestHelper.run(
            """
            $head
            let app = otherwise {
              routes = route { method = Get, path = "/events", handler = { m } -> sse { f = { send } -> do
                send { ev = sse_event { data = "one" } }
                send { ev = { data = "two", event = Some { val = "named" }, id = None } } } },
              fallback = not_found,
            }
            let go: {} -> Str <*> = {} ->
              perform HelidonHttpServerWith { config = listen_on_any_port } in
                perform SimpleHttpClient in do
                  let h = match HttpServer.serve { handler = app } is
                    Ok { val = hh } -> hh
                    Err { error = _ } -> { stop = {} -> {}, port = 0 - 1 }
                  let out = match request { url = ${url("/events")} }.send {} is
                    Ok { val } -> "$(match map::get { m = val.headers, k = "content-type" } is
                      Some { val = t } -> t
                      None -> "no-type")|$(text_of { resp = val })"
                    Err { error = e } -> format_error { err = e }
                  h.stop {}
                  out
            go {}
            """.trimIndent(),
        )
        assertEquals("text/event-stream|data: one\n\nevent: named\ndata: two\n\n", r)
    }

    /**
     * THE property: an event reaches the client BEFORE the handler returns.
     * The handler sends one event, sleeps, then sends a second; a streaming
     * reader that stops after its first chunk must see exactly the first
     * event. A server that buffered until the handler returned would hand
     * both events over in one chunk.
     */
    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
    fun `an event is flushed to the client before the handler returns`() {
        val r = MezzeTestHelper.run(
            """
            $head
            let app = otherwise {
              routes = route { method = Get, path = "/slow", handler = { m } -> sse { f = { send } -> do
                send { ev = sse_event { data = "first" } }
                sleep { ms = 1500 }
                send { ev = sse_event { data = "second" } } } },
              fallback = not_found,
            }
            let first_chunk: { reader: Download } -> Str <HttpDownload, *> = { reader } ->
              perform EarlyReturn in
                chunks { reader = reader, chunk_bytes = 65536, init = "nothing", f = { acc, x } -> match bytes::to_str { b = x } is
                  Ok { val = t } -> return { val = t }
                  Err { error = _ } -> return { val = "undecodable" } }
            let go: {} -> Str <*> = {} ->
              perform SystemTime in
                perform HelidonHttpServerWith { config = listen_on_any_port } in
                  perform SimpleHttpClient in perform SimpleHttpDownload in do
                    let h = match HttpServer.serve { handler = app } is
                      Ok { val = hh } -> hh
                      Err { error = _ } -> { stop = {} -> {}, port = 0 - 1 }
                    let out = match with_download {
                      req = request { url = ${url("/slow")} }, config = default_http_config,
                      f = { resp, reader } -> first_chunk { reader = reader },
                    } is
                      Ok { val } -> val
                      Err { error = e } -> format_error { err = e }
                    h.stop {}
                    out
            go {}
            """.trimIndent(),
        )
        assertEquals("data: first\n\n", r)
    }
}
