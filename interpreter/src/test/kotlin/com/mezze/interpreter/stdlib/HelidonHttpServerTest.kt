package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The server on a REAL socket.
 *
 * Everything else in the server story is exercised without one — routing and
 * handlers are pure, so a `Request` value is the whole test seam. These tests
 * cover the one part that a value cannot stand in for: a request that arrives
 * over TCP, is answered by guest code on a worker thread, and comes back.
 *
 * Each test binds port 0 and reads the chosen port off the handle, so no test
 * reserves a port another one might want.
 */
class HelidonHttpServerTest {

    private val head = """
        use std::effects::http::{ Request, Response, Method, Get, Post, HttpClient, SimpleHttpClient, request, format_error, with_text, with_method, body_str }
        use std::effects::http_server::{ HttpServer, HelidonHttpServerWith, ServerConfig }
        use std::effects::http_router::{ route, or_else, otherwise, Matched }
        use std::collections::map
        use std::collections::bytes
        use std::int

        let reply: { code: Int, text: Str, url: Str } -> Response = { code, text, url } -> {
          status = code, headers = map::empty {}, body = bytes::from_str { s = text }, url = url,
        }
        let not_found: { req: Request } -> Response = { req } ->
          reply { code = 404, text = "nope", url = req.url }
        let text_of: { resp: Response } -> Str = { resp } -> match body_str { resp = resp } is
          Ok { val } -> val
          Err { error = _ } -> "could not decode"
        let body_text: { req: Request } -> Str = { req } -> match req.body is
          Some { val } -> match bytes::to_str { b = val } is
            Ok { val = s } -> s
            Err { error = _ } -> "could not decode"
          None -> "<empty>"
        let listen_on_any_port: ServerConfig = { port = 0, host = "127.0.0.1", workers = 4 }
    """.trimIndent()

    /** `GET` a url and give back the body text, or the error, as a Str. */
    private val fetch = """
        let fetch: { url: Str } -> Str <HttpClient> = { url } ->
          match request { url = url }.send {} is
            Ok { val } -> text_of { resp = val }
            Err { error = e } -> format_error { err = e }
    """.trimIndent()

    private fun url(path: String) =
        "\"http://127.0.0.1:\$(int::to_str { n = h.port })$path\""

    @Test
    fun `a request over TCP reaches the handler and the reply comes back`() {
        val r = MezzeTestHelper.run(
            """
            $head
            $fetch
            let app = otherwise {
              routes = route { method = Get, path = "/users/:id",
                handler = { m } -> reply { code = 200, text = m.param_or { name = "id", default = "?" }, url = m.req.url } },
              fallback = not_found,
            }
            let go: {} -> Str <*> = {} ->
              perform HelidonHttpServerWith { config = listen_on_any_port } in
                perform SimpleHttpClient in do
                  let h = HttpServer.serve { handler = app }
                  let out = fetch { url = ${url("/users/42")} }
                  h.stop {}
                  out
            go {}
            """.trimIndent(),
        )
        assertEquals("42", r, "the path parameter made it through the socket and back")
    }

    @Test
    fun `the OS chose a port and the handle says which`() {
        val r = MezzeTestHelper.run(
            """
            $head
            let app = otherwise {
              routes = route { method = Get, path = "/", handler = { m } -> reply { code = 200, text = "ok", url = m.req.url } },
              fallback = not_found,
            }
            let go: {} -> Int <*> = {} ->
              perform HelidonHttpServerWith { config = listen_on_any_port } in do
                let h = HttpServer.serve { handler = app }
                let p = h.port
                h.stop {}
                p
            go {}
            """.trimIndent(),
        )
        val port = r as Long
        assertTrue(port > 0, "port 0 asks the OS to choose; the handle must report the choice, got $port")
    }

    @Test
    fun `a POST body arrives at the handler`() {
        val r = MezzeTestHelper.run(
            """
            $head
            let app = otherwise {
              routes = route { method = Post, path = "/echo",
                handler = { m } -> reply { code = 200, text = body_text { req = m.req }, url = m.req.url } },
              fallback = not_found,
            }
            let go: {} -> Str <*> = {} ->
              perform HelidonHttpServerWith { config = listen_on_any_port } in
                perform SimpleHttpClient in do
                  let h = HttpServer.serve { handler = app }
                  let sent = request { url = ${url("/echo")} }
                    .with_method { val = Post }
                    .with_text { val = "hello from the client" }
                    .send {}
                  let out = match sent is
                    Ok { val } -> text_of { resp = val }
                    Err { error = e } -> format_error { err = e }
                  h.stop {}
                  out
            go {}
            """.trimIndent(),
        )
        assertEquals("hello from the client", r, "the request body must reach the handler intact")
    }

    @Test
    fun `an unmatched path gets the fallback, with its status`() {
        val r = MezzeTestHelper.run(
            """
            $head
            $fetch
            let app = otherwise {
              routes = route { method = Get, path = "/users/:id",
                handler = { m } -> reply { code = 200, text = "user", url = m.req.url } },
              fallback = not_found,
            }
            let go: {} -> Str <*> = {} ->
              perform HelidonHttpServerWith { config = listen_on_any_port } in
                perform SimpleHttpClient in do
                  let h = HttpServer.serve { handler = app }
                  let out = fetch { url = ${url("/nothing/here")} }
                  h.stop {}
                  out
            go {}
            """.trimIndent(),
        )
        assertEquals("nope", r, "the fallback answers, and its body is what the client receives")
    }

    @Test
    fun `a query string does not stop a route from matching`() {
        val r = MezzeTestHelper.run(
            """
            $head
            $fetch
            let app = otherwise {
              routes = route { method = Get, path = "/users/:id",
                handler = { m } -> reply { code = 200, text = m.param_or { name = "id", default = "?" }, url = m.req.url } },
              fallback = not_found,
            }
            let go: {} -> Str <*> = {} ->
              perform HelidonHttpServerWith { config = listen_on_any_port } in
                perform SimpleHttpClient in do
                  let h = HttpServer.serve { handler = app }
                  let out = fetch { url = ${url("/users/7?page=2&sort=name")} }
                  h.stop {}
                  out
            go {}
            """.trimIndent(),
        )
        assertEquals("7", r, "the query belongs to the target, not to the path the router matches")
    }

    @Test
    fun `the handler runs with the effects installed at serve`() {
        // The handler does not run on the thread that called `serve` — it runs
        // on a worker. So the handler chain in force at `serve` has to be
        // carried across, or an effect a handler performs finds no performer.
        // `Reader` is the cheapest proof: the value the handler returns can only
        // come from the frame the test installed.
        val r = MezzeTestHelper.run(
            """
            $head
            $fetch
            use std::effects::reader::{ Reader, WithReader }
            let app = otherwise {
              routes = route { method = Get, path = "/whoami",
                handler = { m } -> reply { code = 200, text = Reader.ask {}, url = m.req.url } },
              fallback = not_found,
            }
            let go: {} -> Str <*> = {} ->
              perform WithReader { val = "tenant-9" } in
                perform HelidonHttpServerWith { config = listen_on_any_port } in
                  perform SimpleHttpClient in do
                    let h = HttpServer.serve { handler = app }
                    let out = fetch { url = ${url("/whoami")} }
                    h.stop {}
                    out
            go {}
            """.trimIndent(),
        )
        assertEquals(
            "tenant-9", r,
            "a handler must find the performers installed around `serve` — the worker thread restores that chain",
        )
    }

    @Test
    fun `a query the client encoded is the query the server reads`() {
        // `with_query`'s QList renders a repeated key; `query_all` collects
        // one. Nothing else holds the two halves of the story to the same
        // convention, and a convention only one side follows is a bug waiting
        // for a user.
        val r = MezzeTestHelper.run(
            """
            $head
            use std::effects::http::{ with_query, ToQuery }
            use std::str
            let app = otherwise {
              routes = route { method = Get, path = "/q",
                handler = { m } -> reply { code = 200,
                  text = str::join { strings = m.query_all { name = "tags" }, separator = "-" },
                  url = m.req.url } },
              fallback = not_found,
            }
            let go: {} -> Str <*> = {} ->
              perform HelidonHttpServerWith { config = listen_on_any_port } in
                perform SimpleHttpClient in do
                  let h = HttpServer.serve { handler = app }
                  let sent = request { url = ${url("/q")} }
                    .with_query { params = { tags = ["a", "b"] } }
                    .send {}
                  let out = match sent is
                    Ok { val } -> text_of { resp = val }
                    Err { error = e } -> format_error { err = e }
                  h.stop {}
                  out
            go {}
            """.trimIndent(),
        )
        assertEquals("a-b", r, "the client's repeated key is what the server's query_all collects")
    }

    @Test
    fun `a record goes out as a query and comes back as the same record`() {
        // The whole story in one test: the client encodes a record into the
        // query, Helidon carries it, the router hands the handler the target,
        // and the handler decodes it back into a record of the same shape. The
        // only thing naming that shape is the annotation on the `let`.
        val r = MezzeTestHelper.run(
            """
            $head
            use std::effects::http::{ with_query, ToQuery }
            use std::effects::http_router::{ FromQuery }
            use std::codec::core::{ DecodeError }
            let app = otherwise {
              routes = route { method = Get, path = "/search", handler = { m } -> do
                let q: Result { q: Str, page: Int } DecodeError = m.query_as {}
                match q is
                  Ok { val } -> reply { code = 200,
                    text = "$(val.q)/$(int::to_str { n = val.page })", url = m.req.url }
                  Err { error = e } -> reply { code = 400, text = e.message, url = m.req.url } },
              fallback = not_found,
            }
            let go: {} -> Str <*> = {} ->
              perform HelidonHttpServerWith { config = listen_on_any_port } in
                perform SimpleHttpClient in do
                  let h = HttpServer.serve { handler = app }
                  let sent = request { url = ${url("/search")} }
                    .with_query { params = { q = "mezze", page = 3 } }
                    .send {}
                  let out = match sent is
                    Ok { val } -> text_of { resp = val }
                    Err { error = e } -> format_error { err = e }
                  h.stop {}
                  out
            go {}
            """.trimIndent(),
        )
        assertEquals("mezze/3", r, "one record shape, encoded by the client and decoded by the server")
    }

    @Test
    fun `two requests in a row both get answered`() {
        // One request proves the path works once. A second proves the worker
        // went back for more rather than retiring after its first job.
        val r = MezzeTestHelper.run(
            """
            $head
            $fetch
            let app = otherwise {
              routes = route { method = Get, path = "/n/:v",
                handler = { m } -> reply { code = 200, text = m.param_or { name = "v", default = "?" }, url = m.req.url } },
              fallback = not_found,
            }
            let go: {} -> Str <*> = {} ->
              perform HelidonHttpServerWith { config = listen_on_any_port } in
                perform SimpleHttpClient in do
                  let h = HttpServer.serve { handler = app }
                  let a = fetch { url = ${url("/n/one")} }
                  let b = fetch { url = ${url("/n/two")} }
                  h.stop {}
                  "${'$'}(a)-${'$'}(b)"
            go {}
            """.trimIndent(),
        )
        assertEquals("one-two", r)
    }
}
