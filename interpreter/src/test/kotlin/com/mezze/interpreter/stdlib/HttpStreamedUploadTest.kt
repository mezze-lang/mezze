package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An INBOUND body read a chunk at a time, instead of buffered whole.
 *
 * `Request.chunks` is the mirror of `Response.chunks`, and the mirror is where
 * the work was: a response's chunks are produced by guest code and written out,
 * while a request's arrive on a socket and have to be pulled. The pull is a
 * native `read_chunk` over a single-shot reader, wrapped by `iter::unfold` in
 * `http_server` — which is `response_file`'s shape one direction over, and is in
 * Mezze rather than the native because `IterCursor` casts `unfold`'s step to a
 * MezzeFunction, so a native cannot build that iterator.
 *
 * `stream_bodies` is OFF by default and a whole-server decision. A body can be
 * read once — taking the bytes drains the stream, handing over the stream means
 * the bytes were never taken — so the two shapes are exclusive and the server
 * says which it filled. A per-request size rule would force every handler to
 * read both, and a handler reading only `body` would find `None` exactly for
 * the large uploads it existed to handle.
 *
 * The first two tests need no socket: `incoming_streamed` is the test seam for
 * the streamed shape, and chunk BOUNDARIES are the difficulty of streaming, so
 * a reader that works on one chunk is not yet a reader. The last two put a body
 * over TCP, which is the part a value cannot stand in for.
 */
class HttpStreamedUploadTest {

    private val head = """
        use std::effects::http::{ Request, Response, Method, Get, Post, HttpClient, SimpleHttpClient, request, format_error, with_text, with_method, body_str, body_bytes }
        use std::effects::http_server::{ HttpServer, HelidonHttpServerWith, ServerConfig, incoming, incoming_streamed }
        use std::effects::http_router::{ route, otherwise, Matched }
        use std::collections::map
        use std::collections::bytes
        use std::collections::iter
        use std::collections::seq
        use std::str
        use std::int

        let reply: { code: Int, text: Str, url: Str } -> Response = { code, text, url } -> {
          status = code, headers = map::empty {}, body = bytes::from_str { s = text }, url = url,
          chunks = None,
        }
        let not_found: { req: Request } -> Response = { req } ->
          reply { code = 404, text = "nope", url = req.url }
        let text_of: { resp: Response } -> Str = { resp } -> match body_str { resp = resp } is
          Ok { val } -> val
          Err { error = _ } -> "could not decode"

        ## The chunk SIZES a handler saw, which is what says it streamed.
        let sizes_of: { req: Request } -> Str = { req } -> match req.chunks is
          None -> "buffered"
          Some { val = cs } -> seq::join {
            xs = iter::to_seq { it = iter::map { it = cs, f = { x } -> int::to_str { n = bytes::size { b = x } } } },
            sep = ",",
          }

        ## The whole body, however it arrived.
        let whole_of: { req: Request } -> Str = { req } -> match bytes::to_str { b = body_bytes { req = req } } is
          Ok { val } -> val
          Err { error = _ } -> "could not decode"

        let chunks_of: { parts: [Str] } -> Iter Bytes = { parts } ->
          iter::map { it = iter::from_seq { xs = parts }, f = { x } -> bytes::from_str { s = x } }

        let streaming: ServerConfig = { port = 0, host = "127.0.0.1", workers = 4, stream_bodies = True }
        let buffering: ServerConfig = { port = 0, host = "127.0.0.1", workers = 4, stream_bodies = False }
    """.trimIndent()

    /** A handler walks the chunks it was given, boundaries intact. */
    @Test
    fun `a streamed request is walked a chunk at a time`() {
        assertEquals(
            "3,2,1",
            MezzeTestHelper.run(
                """
                $head
                let req = incoming_streamed {
                  method = Post, url = "/u", chunks = chunks_of { parts = ["abc", "de", "f"] },
                }
                sizes_of { req = req }
                """.trimIndent(),
            ),
        )
    }

    /** `body_bytes` collects a streamed body, and answers a buffered one as-is. */
    @Test
    fun `body_bytes answers either shape`() {
        assertEquals(
            "abcdef|plain|",
            MezzeTestHelper.run(
                """
                $head
                let streamed = incoming_streamed {
                  method = Post, url = "/u", chunks = chunks_of { parts = ["abc", "de", "f"] },
                }
                let buffered = incoming { method = Post, url = "/u" }.with_text { val = "plain" }
                let empty = incoming { method = Get, url = "/u" }
                str::concat {
                  l = str::concat {
                    l = str::concat { l = whole_of { req = streamed }, r = "|" },
                    r = str::concat { l = whole_of { req = buffered }, r = "|" },
                  },
                  r = whole_of { req = empty },
                }
                """.trimIndent(),
            ),
        )
    }

    /**
     * Over TCP, with the server told to stream: the handler sees CHUNKS.
     *
     * This asserts the chunk SIZES, not the reassembled text. `body_bytes`
     * answers either shape, so a test that read it would pass against a
     * buffering server too and prove nothing — the first version of this test
     * did exactly that. `sizes_of` reports "buffered" unless `chunks` is really
     * filled, so only a streamed body can produce a number here.
     */
    @Test
    fun `a posted body arrives as chunks when the server streams`() {
        assertEquals(
            "19",
            MezzeTestHelper.run(
                """
                $head
                let app = otherwise {
                  routes = route { method = Post, path = "/u",
                    handler = { m } -> reply { code = 200, text = sizes_of { req = m.req }, url = m.req.url } },
                  fallback = not_found,
                }
                let go: {} -> Str <*> = {} ->
                  perform HelidonHttpServerWith { config = streaming } in
                    perform SimpleHttpClient in do
                      let h = HttpServer.serve { handler = app }
                      let out = match request { url = "http://127.0.0.1:$(int::to_str { n = h.port })/u" }
                        .with_method { val = Post }
                        .with_text { val = "hello over the wire" }
                        .send {} is
                          Ok { val } -> text_of { resp = val }
                          Err { error = e } -> format_error { err = e }
                      h.stop {}
                      out
                go {}
                """.trimIndent(),
            ),
        )
    }

    /**
     * A handler may hand its request's chunks straight on as the response's.
     *
     * This is the pass-through an upload proxy wants: nothing is buffered at
     * either end, and the peak cost is one chunk no matter how big the body is.
     * The response's chunks are walked AFTER the handler has returned, which
     * looks like it could not work — Helidon owns the connection's content.
     * It does: the content stays readable while the response is written.
     *
     * I wrote a guard against this first, on the assumption that a late read
     * would quietly answer nothing and send an empty 200. The measurement said
     * otherwise at every size, so the guard only removed the capability and is
     * gone. Three sizes here because one small body proves little — a body
     * larger than a socket buffer is where a lifecycle assumption would break.
     */
    @Test
    fun `a handler may pass the request body straight through`() {
        val block = "0123456789abcdefghij"
        for (reps in listOf(1, 2000, 20000)) {
            val sent = block.repeat(reps)
            assertEquals(
                "${sent.length}",
                MezzeTestHelper.run(
                    """
                    $head
                    let app = otherwise {
                      routes = route { method = Post, path = "/u",
                        handler = { m } -> match m.req.chunks is
                          None -> reply { code = 500, text = "not streamed", url = m.req.url }
                          Some { val = cs } -> {
                            status = 200, headers = map::empty {},
                            body = bytes::from_str { s = "" }, url = m.req.url,
                            chunks = Some { val = cs },
                          } },
                      fallback = not_found,
                    }
                    let go: {} -> Str <*> = {} ->
                      perform HelidonHttpServerWith { config = streaming } in
                        perform SimpleHttpClient in do
                          let h = HttpServer.serve { handler = app }
                          let out = match request { url = "http://127.0.0.1:$(int::to_str { n = h.port })/u" }
                            .with_method { val = Post }
                            .with_text { val = "$sent" }
                            .send {} is
                              Ok { val } -> int::to_str { n = str::size { s = text_of { resp = val } } }
                              Err { error = e } -> format_error { err = e }
                          h.stop {}
                          out
                    go {}
                    """.trimIndent(),
                ).toString(),
                "a ${sent.length}-byte body must come back whole through the pass-through",
            )
        }
    }

    /** And with the default server, the same handler still finds `body`. */
    @Test
    fun `the default server still buffers into body`() {
        assertEquals(
            "buffered",
            MezzeTestHelper.run(
                """
                $head
                let app = otherwise {
                  routes = route { method = Post, path = "/u",
                    handler = { m } -> reply { code = 200, text = sizes_of { req = m.req }, url = m.req.url } },
                  fallback = not_found,
                }
                let go: {} -> Str <*> = {} ->
                  perform HelidonHttpServerWith { config = buffering } in
                    perform SimpleHttpClient in do
                      let h = HttpServer.serve { handler = app }
                      let out = match request { url = "http://127.0.0.1:$(int::to_str { n = h.port })/u" }
                        .with_method { val = Post }
                        .with_text { val = "hello over the wire" }
                        .send {} is
                          Ok { val } -> text_of { resp = val }
                          Err { error = e } -> format_error { err = e }
                      h.stop {}
                      out
                go {}
                """.trimIndent(),
            ),
        )
    }
}
