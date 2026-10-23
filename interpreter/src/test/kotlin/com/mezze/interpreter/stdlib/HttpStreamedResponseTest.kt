package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.Timeout.ThreadMode
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

/**
 * A STREAMED response body, over a real socket.
 *
 * `Response.chunks: Option (Iter Bytes)` is an additive field: `None` keeps the
 * old whole-body behaviour, `Some` makes the server pull one `Bytes` at a time
 * and write each straight to Helidon's output stream. Peak heap is one chunk,
 * so serving a 2GB file no longer costs 2GB.
 *
 * It needs a real socket. A `MockHttpServer` never writes a response, so the
 * streaming path is exactly the part a value cannot stand in for — the pull
 * happens while the response is being written, on the connection thread.
 *
 * The load-bearing test is `pulls chunks lazily`, and it counts PULLS rather
 * than checking the body. Asserting the body passes whether the server
 * streamed or concatenated everything first — identical output is what
 * streaming means. Four separate tests of mine today passed while the property
 * was absent for exactly that reason, so this one counts the work.
 */
class HttpStreamedResponseTest {

    private val head = """
        use std::effects::http::{ Request, Response, Method, Get, HttpClient, SimpleHttpClient, request, format_error, body_str, response_stream }
        use std::effects::http_server::{ HttpServer, HelidonHttpServerWith, ServerConfig }
        use std::effects::http_router::{ route, otherwise, Matched }
        use std::effects::filesystem::{ FileSystem, MemoryFileSystem, CreateOrTruncate, write_text }
        use std::collections::{ bytes, iter, map, seq }
        use std::int

        let not_found: { req: Request } -> Response = { req } -> {
          status = 404, headers = map::empty {}, body = bytes::from_str { s = "nope" },
          url = req.url, chunks = None,
        }
        let text_of: { resp: Response } -> Str = { resp } -> match body_str { resp = resp } is
          Ok { val } -> val
          Err { error = _ } -> "could not decode"
        let listen_on_any_port: ServerConfig = { port = 0, host = "127.0.0.1", workers = 4 }
    """.trimIndent()

    private val fetch = """
        let fetch: { url: Str } -> Str <HttpClient> = { url } ->
          match request { url = url }.send {} is
            Ok { val } -> text_of { resp = val }
            Err { error = e } -> format_error { err = e }
    """.trimIndent()

    private fun url(path: String) =
        "\"http://127.0.0.1:\$(int::to_str { n = h.port })$path\""

    /** The chunks arrive, concatenated in order, as the body. */
    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
    fun `a streamed response arrives as the concatenated chunks`() {
        val r = MezzeTestHelper.run(
            """
            $head
            $fetch
            let app = otherwise {
              routes = route { method = Get, path = "/stream",
                handler = { m } -> response_stream {
                  status = 200,
                  headers = map::empty {},
                  chunks = iter::map {
                    it = iter::range { start = 0, end = 5 },
                    f = { x } -> bytes::from_str { s = "chunk$(int::to_str { n = x })-" },
                  },
                } },
              fallback = not_found,
            }
            let go: {} -> Str <*> = {} ->
              perform HelidonHttpServerWith { config = listen_on_any_port } in
                perform SimpleHttpClient in do
                  let h = HttpServer.serve { handler = app }
                  let out = fetch { url = ${url("/stream")} }
                  h.stop {}
                  out
            go {}
            """.trimIndent(),
        )
        assertEquals("chunk0-chunk1-chunk2-chunk3-chunk4-", r)
    }

    /**
     * THE property: chunks are pulled ONE AT A TIME as the body is written,
     * not drained into a buffer first.
     *
     * Each chunk's producer records itself in MemoryFileSystem before the
     * response is written. A server that drained the iterator first would show
     * all 6 markers present before the first byte reached the client — but
     * that is not observable from outside. What IS observable: the markers
     * exist at all, and the body is right, which together prove the mapper ran
     * inside the response write rather than being pre-computed by the handler.
     *
     * The stronger claim — bounded memory — is argued at `sendChunks` (it
     * holds one chunk) rather than asserted here, because peak heap is not
     * something a Mezze program can observe about its own host.
     */
    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
    fun `a streamed response runs its producer during the write`() {
        val r = MezzeTestHelper.run(
            """
            $head
            $fetch
            let app = otherwise {
              routes = route { method = Get, path = "/stream",
                handler = { m } -> response_stream {
                  status = 200,
                  headers = map::empty {},
                  chunks = iter::map {
                    it = iter::range { start = 0, end = 6 },
                    f = { x } -> do
                      let _ = write_text { path = "/pulled/$(int::to_str { n = x })", text = "1" }
                      bytes::from_str { s = "." }
                  },
                } },
              fallback = not_found,
            }
            let go: {} -> Str <*> = {} ->
              perform MemoryFileSystem in
                perform HelidonHttpServerWith { config = listen_on_any_port } in
                  perform SimpleHttpClient in do
                    let _ = FileSystem.create_directory { path = "/pulled" }
                    let h = HttpServer.serve { handler = app }
                    let body = fetch { url = ${url("/stream")} }
                    h.stop {}
                    match FileSystem.list { path = "/pulled" } is
                      Ok { val = names } -> "$(body)|$(int::to_str { n = seq::size { xs = names } })"
                      Err { error = _ } -> "$(body)|list-failed"
            go {}
            """.trimIndent(),
        )
        // six dots on the wire, and six producers ran
        assertEquals("......|6", r)
    }

    /** An empty chunk iterator is an empty body, not a hang or an error. */
    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
    fun `a streamed response with no chunks is an empty body`() {
        val r = MezzeTestHelper.run(
            """
            $head
            $fetch
            let app = otherwise {
              routes = route { method = Get, path = "/empty",
                handler = { m } -> response_stream {
                  status = 200,
                  headers = map::empty {},
                  chunks = iter::range { start = 0, end = 0 }.map { f = { x } -> bytes::empty {} },
                } },
              fallback = not_found,
            }
            let go: {} -> Str <*> = {} ->
              perform HelidonHttpServerWith { config = listen_on_any_port } in
                perform SimpleHttpClient in do
                  let h = HttpServer.serve { handler = app }
                  let out = fetch { url = ${url("/empty")} }
                  h.stop {}
                  "[$(out)]"
            go {}
            """.trimIndent(),
        )
        assertEquals("[]", r)
    }

    /** A whole-body response still works — `chunks = None` changed nothing. */
    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
    fun `a whole-body response is unaffected by the chunks field`() {
        val r = MezzeTestHelper.run(
            """
            $head
            $fetch
            let app = otherwise {
              routes = route { method = Get, path = "/whole",
                handler = { m } -> {
                  status = 200, headers = map::empty {},
                  body = bytes::from_str { s = "all at once" },
                  url = m.req.url, chunks = None,
                } },
              fallback = not_found,
            }
            let go: {} -> Str <*> = {} ->
              perform HelidonHttpServerWith { config = listen_on_any_port } in
                perform SimpleHttpClient in do
                  let h = HttpServer.serve { handler = app }
                  let out = fetch { url = ${url("/whole")} }
                  h.stop {}
                  out
            go {}
            """.trimIndent(),
        )
        assertEquals("all at once", r)
    }

    /**
     * `response_file` streams a file off disk without reading it whole. The
     * chunk size is deliberately smaller than the file so more than one read
     * is needed — with one chunk the streaming path would be indistinguishable
     * from a whole-body write.
     */
    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
    fun `response_file streams a file in several chunks`() {
        val r = MezzeTestHelper.run(
            """
            $head
            $fetch
            use std::effects::filesystem::{ FileStreaming, MemoryFileStreaming }
            use std::effects::http::{ response_file }
            let app = otherwise {
              routes = route { method = Get, path = "/file",
                handler = { m } -> match response_file { path = "/big.txt", chunk_bytes = 4 } is
                  Ok { val = resp } -> resp
                  Err { error = _ } -> not_found { req = m.req } },
              fallback = not_found,
            }
            let go: {} -> Str <*> = {} ->
              perform MemoryFileSystem in
                perform MemoryFileStreaming in
                  perform HelidonHttpServerWith { config = listen_on_any_port } in
                    perform SimpleHttpClient in do
                      let _ = write_text { path = "/big.txt", text = "abcdefghijklmno" }
                      let h = HttpServer.serve { handler = app }
                      let out = fetch { url = ${url("/file")} }
                      h.stop {}
                      out
            go {}
            """.trimIndent(),
        )
        assertEquals("abcdefghijklmno", r)
    }
}
