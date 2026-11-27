package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.Timeout.ThreadMode
import java.util.concurrent.TimeUnit

/**
 * The SSE client: `events { req, config, init, f }` folds `f` over the events
 * of a `text/event-stream` response AS THEY ARRIVE.
 *
 * It is a fold over `chunks`, so it performs `HttpDownload` and needs no effect
 * of its own: SSE is exactly "a streamed download" plus a parser. Blocks are
 * split on the bytes and decoded whole, so a chunk boundary inside a multi-byte
 * character cannot corrupt an event. Early stop is the consumer's `return`.
 */
class HttpSseClientTest {

    private val head = """
        use std::effects::http::{ Request, Response, Method, request, format_error, sse, sse_event, sse_parse, SseEvent, events, HttpClient, SimpleHttpClient, HttpDownload, SimpleHttpDownload, default_http_config, HttpServer, HelidonHttpServerWith, ServerConfig, route, otherwise, Matched }
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
        let listen_on_any_port: ServerConfig = { port = 0, host = "127.0.0.1", workers = 4, stream_bodies = False, helidon_print_log = Off }
        let show: { ev: SseEvent } -> Str = { ev } -> "[$(match ev.event is
          Some { val } -> val
          None -> "-"):$(ev.data)]"
    """.trimIndent()

    private fun url(path: String) =
        "\"http://127.0.0.1:\$(int::to_str { n = h.port })$path\""

    // ==================== the parser ====================

    @Test
    fun `data lines join with newlines and the field space is syntax`() {
        assertEquals(
            "[update:a\nb]|9",
            MezzeTestHelper.run(
                "$head\nlet ev = sse_parse { block = \"event: update\\nid: 9\\ndata: a\\ndata:b\" }\n" +
                    "\"$(show { ev = ev })|$(match ev.id is\n  Some { val } -> val\n  None -> \"none\")\"",
            ),
        )
    }

    @Test
    fun `comments and unknown fields are ignored`() {
        assertEquals(
            "[-:x]",
            MezzeTestHelper.run("$head\nshow { ev = sse_parse { block = \": keep-alive\\nretry: 3000\\ndata: x\" } }"),
        )
    }

    // ==================== over the wire, against the server side ====================

    /** Every event the server sends, in order, including a multi-line and a named one. */
    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
    fun `events folds over every event the server sends`() {
        val r = MezzeTestHelper.run(
            """
            $head
            let app = otherwise {
              routes = route { method = Get, path = "/events", handler = { m } -> sse { f = { send } -> do
                send { ev = sse_event { data = "one" } }
                send { ev = sse_event { data = "two\nlines" } }
                send { ev = { data = "three", event = Some { val = "named" }, id = Some { val = "3" } } } } },
              fallback = not_found,
            }
            let go: {} -> Str <*> = {} ->
              perform HelidonHttpServerWith { config = listen_on_any_port } in
                perform SimpleHttpClient in perform SimpleHttpDownload in do
                  let h = match HttpServer.serve { handler = app } is
                    Ok { val = hh } -> hh
                    Err { ... } -> { stop = {} -> {}, port = 0 - 1 }
                  let out = match events {
                    req = request { url = ${url("/events")} }, config = default_http_config,
                    init = "", f = { acc, ev } -> "$(acc)$(show { ev = ev })",
                  } is
                    Ok { val } -> val
                    Err { error = e } -> format_error { err = e }
                  h.stop {}
                  out
            go {}
            """.trimIndent(),
        )
        assertEquals("[-:one][-:two\nlines][named:three]", r)
    }

    /**
     * Events are folded as they ARRIVE: the consumer returns after the first
     * one while the server is still sleeping before its second. A client that
     * waited for the whole body would answer both events, or time out.
     */
    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
    fun `the consumer can stop after the first event while the server is still sending`() {
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
            let go: {} -> Str <*> = {} ->
              perform SystemTime in
                perform HelidonHttpServerWith { config = listen_on_any_port } in
                  perform SimpleHttpClient in perform SimpleHttpDownload in do
                    let h = match HttpServer.serve { handler = app } is
                      Ok { val = hh } -> hh
                      Err { ... } -> { stop = {} -> {}, port = 0 - 1 }
                    let out = match perform EarlyReturn in events {
                      req = request { url = ${url("/slow")} }, config = default_http_config,
                      init = "", f = { acc, ev } -> return { val = Ok { val = show { ev = ev } } },
                    } is
                      Ok { val } -> val
                      Err { error = e } -> format_error { err = e }
                    h.stop {}
                    out
            go {}
            """.trimIndent(),
        )
        assertEquals("[-:first]", r)
    }
}
