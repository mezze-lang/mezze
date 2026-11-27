package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.Timeout.ThreadMode
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A response body read a chunk at a time by the CLIENT.
 *
 * The last of the four streaming directions. Outbound response chunks and
 * inbound request chunks are the server's two; this is the client's download,
 * and before it a client response always arrived with `chunks = None` and the
 * whole body already in memory — `response.as(byte[].class)`.
 *
 * `HttpDownload` is a SEPARATE effect from `HttpClient`, the way
 * `FileStreaming` is separate from `Filesystem`: it holds a connection open
 * across several operations, and a caller that only sends requests should not
 * have to grant it.
 *
 * `close` is not optional here. The server's inbound reader could be left
 * alone because Helidon keeps the request content readable on its own account;
 * a client's connection is ours, so `with_download` scopes it and a read after
 * that scope reports the failure BY NAME rather than answering empty — the
 * discipline `with_lines` settled for files.
 *
 * These tests measure laziness and lifetime, not the body. As
 * `HttpStreamedResponseTest` says of its own subject: asserting the body passes
 * whether the thing streamed or buffered first, because identical output is
 * what streaming means.
 */
class HttpStreamedDownloadTest {

    /** 20 KB, so ten of them exceed one 64 KiB read and `take` can stop short. */
    private val block = "0123456789abcdefghij".repeat(1000)

    private val head = """
        use std::effects::http::{ Request, Response, Method, request, format_error, body_str, response_stream, HttpClient, SimpleHttpClient, HttpDownload, SimpleHttpDownload, Download, with_download, chunks, default_http_config, HttpServer, HelidonHttpServerWith, ServerConfig, route, otherwise, Matched }
        use std::collections::{ bytes, iter, map, seq }
        use std::effects::early_return::{ EarlyReturn, return }
        use std::int

        let not_found: { req: Request } -> Response = { req } -> {
          status = 404, headers = map::empty {}, body = bytes::from_str { s = "nope" },
          url = req.url, chunks = None,
        }
        let listen_on_any_port: ServerConfig = { port = 0, host = "127.0.0.1", workers = 4, stream_bodies = False, helidon_print_log = Off }

        ## A 200 KB body as ten 20 KB chunks, from a seq so the element type is
        ## Bytes throughout — `iter::map` from a range of Int into Bytes hits a
        ## separate specialisation bug in `to_seq`.
        let big_chunks: { b: Bytes } -> Iter Bytes = { b } ->
          iter::from_seq { xs = [b, b, b, b, b, b, b, b, b, b] }

        ## Drain the download through `chunks`; the reads happen inside this call.
        let total_of: { reader: Download } -> Int <HttpDownload> = { reader } ->
          chunks { reader = reader, chunk_bytes = 65536, init = 0, f = { acc, x } -> acc + bytes::size { b = x } }

        ## Stop after the first chunk: the consumer returns out of the fold.
        let first_chunk_of: { reader: Download } -> Int <HttpDownload, *> = { reader } ->
          perform EarlyReturn in
            chunks { reader = reader, chunk_bytes = 65536, init = 0 - 1, f = { acc, x } -> return { val = bytes::size { b = x } } }
    """.trimIndent()

    /** Serve a streamed 200 KB body, then run `body` against it. */
    private fun overTheWire(expr: String): Any = MezzeTestHelper.run(
        head + "\n" + """
        let blk: Bytes = bytes::from_str { s = "$block" }
        let app = otherwise {
          routes = route { method = Get, path = "/big", handler = { m } -> response_stream {
            status = 200, headers = map::empty {}, chunks = big_chunks { b = blk },
          } },
          fallback = not_found,
        }
        let go: {} -> Str <*> = {} ->
          perform HelidonHttpServerWith { config = listen_on_any_port } in
            perform SimpleHttpClient in perform SimpleHttpDownload in do
              let h = match HttpServer.serve { handler = app } is
                Ok { val = hh } -> hh
                Err { ... } -> { stop = {} -> {}, port = 0 - 1 }
              let out = $expr
              h.stop {}
              out
        go {}
        """.trimIndent(),
    )

    private val urlOfBig =
        """request { url = "http://127.0.0.1:$(int::to_str { n = h.port })/big" }"""

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
    fun `a drained download adds up to the whole body`() {
        assertEquals(
            "200000",
            overTheWire(
                """match with_download {
                     req = $urlOfBig, config = default_http_config,
                     f = { resp, reader } -> total_of { reader = reader },
                   } is
                     Ok { val = n } -> int::to_str { n = n }
                     Err { error = e } -> format_error { err = e }"""
            ).toString(),
        )
    }

    /**
     * The load-bearing one: stopping after ONE chunk reads one chunk.
     *
     * A client that buffered the body first would answer 200000 here too, so
     * this is what says the body was never collected. 64 KiB is the read size,
     * so a first chunk is at most that and the body is 200 KB.
     */
    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
    fun `a download can stop after one chunk`() {
        val n = overTheWire(
            """match with_download {
                 req = $urlOfBig, config = default_http_config,
                 f = { resp, reader } -> first_chunk_of { reader = reader },
               } is
                 Ok { val = n } -> int::to_str { n = n }
                 Err { error = e } -> format_error { err = e }"""
        ).toString().toIntOrNull()
        assertTrue(
            n != null && n in 1..65536,
            "one chunk must be between 1 byte and the 64 KiB read size, not the whole " +
                "200 KB body; got $n",
        )
    }

    /**
     * The head arrives before the body is touched, which is half the reason to
     * stream: a caller can decide on the status or the content type first.
     */
    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
    fun `the status is readable without reading the body`() {
        assertEquals(
            "200",
            overTheWire(
                """match with_download {
                     req = $urlOfBig, config = default_http_config,
                     f = { resp, reader } -> resp.status,
                   } is
                     Ok { val = n } -> int::to_str { n = n }
                     Err { error = e } -> format_error { err = e }"""
            ).toString(),
        )
    }

    /**
     * A reader that ESCAPES the scope reads nothing.
     *
     * `read_chunk` reports the closure as a named `HttpConnectionFailed`, and
     * `chunks` ends the walk on any error, so the caller sees an empty walk
     * rather than a failure — pinned as it IS, not as it ought to be. What the
     * test is worth: an escaped reader does not claim bytes it never read. A
     * zero is a poor answer; a wrong length would be a dangerous one.
     */
    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
    fun `a reader escaping the scope reads nothing`() {
        val out = overTheWire(
            """match with_download {
                 req = $urlOfBig, config = default_http_config,
                 f = { resp, reader } -> reader,
               } is
                 Ok { val = escaped } -> int::to_str { n = total_of { reader = escaped } }
                 Err { error = e } -> format_error { err = e }"""
        ).toString()
        assertEquals(
            "0", out,
            "an escaped reader must not claim bytes it never read; the native reports " +
                "the closure and `chunks` turns that into an end, so nothing is handed out",
        )
    }
}
