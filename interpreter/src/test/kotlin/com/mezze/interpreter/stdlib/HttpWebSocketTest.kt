package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.Timeout.ThreadMode
import java.util.concurrent.TimeUnit

/**
 * WebSockets. One `WebSocket` effect (send / receive / close) serves both
 * sides and the in-memory `socket_pair`; `SocketClient.connect` opens a client
 * socket; a server endpoint is `router.websocket { path, handler }`, registered
 * through `HttpServer.serve_routes` because Helidon negotiates the upgrade
 * before any HTTP route runs.
 *
 * Effects live on arrows, so a handler is a function that runs for the life of
 * the connection — `messages` folds over what arrives — on a Truffle-owned
 * thread with the handlers in force at `serve_routes`.
 */
class HttpWebSocketTest {

    private val head = """
        use std::effects::http::{ Request, Response, Method, request, Message, Socket, SocketError, WebSocket, NativeWebSocket, SocketClient, HelidonSocketClient, MockSocketClient, socket_pair, messages, router, HttpServer, HelidonHttpServerWith, ServerConfig, Matched, default_http_config }
        use std::collections::{ bytes, map, seq }
        use std::str
        use std::int

        let not_found: { req: Request } -> Response = { req } -> {
          status = 404, headers = map::empty {}, body = bytes::from_str { s = "nope" },
          url = req.url, chunks = None,
        }
        let listen_on_any_port: ServerConfig = { port = 0, host = "127.0.0.1", workers = 4, stream_bodies = False, helidon_print_log = Off }

        ## Describe what `receive` answered.
        let show: { got: Option Message } -> Str = { got } -> match got is
          Some { val = Text { text } } -> "text:$(text)"
          Some { val = Binary { bytes = b } } -> "binary:$(int::to_str { n = bytes::size { b = b } })"
          None -> "closed"
    """.trimIndent()

    // ==================== in memory ====================

    @Test
    fun `a socket pair delivers what the other end sends, then closes`() {
        val r = MezzeTestHelper.run(
            """
            $head
            perform NativeWebSocket in do
              let p = socket_pair {}
              WebSocket.send { socket = p.a, msg = Text { text = "hi" } }
              WebSocket.send { socket = p.a, msg = Binary { bytes = bytes::from_str { s = "xyz" } } }
              let first = show { got = WebSocket.receive { socket = p.b } }
              let second = show { got = WebSocket.receive { socket = p.b } }
              WebSocket.close { socket = p.a }
              let after = show { got = WebSocket.receive { socket = p.b } }
              let refused = match WebSocket.send { socket = p.b, msg = Text { text = "late" } } is
                Ok { ... } -> "sent"
                Err { error = SocketClosed } -> "refused"
                Err { error = SocketConnectFailed { ... } } -> "odd"
              "$(first)|$(second)|$(after)|$(refused)"
            """.trimIndent(),
        )
        assertEquals("text:hi|binary:3|closed|refused", r)
    }

    /** A client under test is handed one end of a pair; the test drives the other. */
    @Test
    fun `a mock client connects to a prepared socket`() {
        val r = MezzeTestHelper.run(
            """
            $head
            let greet: {} -> Str <SocketClient, WebSocket> = {} ->
              match SocketClient.connect { url = "ws://example.test/chat", config = default_http_config } is
                Err { ... } -> "connect failed"
                Ok { val = s } -> do
                  WebSocket.send { socket = s, msg = Text { text = "hello" } }
                  show { got = WebSocket.receive { socket = s } }
            perform NativeWebSocket in do
              let p = socket_pair {}
              WebSocket.send { socket = p.b, msg = Text { text = "welcome" } }
              let out = perform MockSocketClient { socket = p.a } in greet {}
              "$(out)|$(show { got = WebSocket.receive { socket = p.b } })"
            """.trimIndent(),
        )
        assertEquals("text:welcome|text:hello", r)
    }

    /** `messages` folds until the peer closes. */
    @Test
    fun `messages folds every message until the peer closes`() {
        val r = MezzeTestHelper.run(
            """
            $head
            perform NativeWebSocket in do
              let p = socket_pair {}
              WebSocket.send { socket = p.a, msg = Text { text = "a" } }
              WebSocket.send { socket = p.a, msg = Text { text = "b" } }
              WebSocket.close { socket = p.a }
              messages { socket = p.b, init = "", f = { acc, msg } -> match msg is
                Text { text } -> "$(acc)$(text)"
                Binary { ... } -> "$(acc)?" }
            """.trimIndent(),
        )
        assertEquals("ab", r)
    }

    // ==================== over the wire ====================

    /** An echo endpoint: the server folds incoming messages and sends each back; the client round-trips text and binary. */
    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
    fun `a client round-trips text and binary through a server echo endpoint`() {
        val r = MezzeTestHelper.run(
            """
            $head
            let app = router { path = "" }.websocket { path = "/echo", handler = { m, socket } ->
              messages { socket = socket, init = {}, f = { acc, msg } -> do
                WebSocket.send { socket = socket, msg = msg }
                {} } }
            let go: {} -> Str <*> = {} ->
              perform NativeWebSocket in
                perform HelidonHttpServerWith { config = listen_on_any_port } in
                  perform HelidonSocketClient in do
                    let h = match HttpServer.serve_routes { routes = app.table {}, fallback = not_found } is
                      Ok { val = hh } -> hh
                      Err { ... } -> { stop = {} -> {}, port = 0 - 1 }
                    let out = match SocketClient.connect { url = "ws://127.0.0.1:$(int::to_str { n = h.port })/echo", config = default_http_config } is
                      Err { error = SocketConnectFailed { reason, ... } } -> "connect failed: $(reason)"
                      Err { error = SocketClosed } -> "closed"
                      Ok { val = s } -> do
                        WebSocket.send { socket = s, msg = Text { text = "ping" } }
                        let first = show { got = WebSocket.receive { socket = s } }
                        WebSocket.send { socket = s, msg = Binary { bytes = bytes::from_str { s = "abc" } } }
                        let second = show { got = WebSocket.receive { socket = s } }
                        WebSocket.close { socket = s }
                        "$(first)|$(second)"
                    h.stop {}
                    out
            go {}
            """.trimIndent(),
        )
        assertEquals("text:ping|binary:3", r)
    }

    /** The handler sees the handshake: the request target and an upgrade header. */
    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
    fun `the handler receives the handshake request`() {
        val r = MezzeTestHelper.run(
            """
            $head
            let app = router { path = "/ws" }.websocket { path = "/who", handler = { m, socket } -> do
              WebSocket.send { socket = socket, msg = Text { text = "$(m.req.url)|$(match map::get { m = m.req.headers, k = "upgrade" } is
                Some { val } -> str::to_lower { s = val }
                None -> "no-upgrade-header")" } }
              {} }
            let go: {} -> Str <*> = {} ->
              perform NativeWebSocket in
                perform HelidonHttpServerWith { config = listen_on_any_port } in
                  perform HelidonSocketClient in do
                    let h = match HttpServer.serve_routes { routes = app.table {}, fallback = not_found } is
                      Ok { val = hh } -> hh
                      Err { ... } -> { stop = {} -> {}, port = 0 - 1 }
                    let out = match SocketClient.connect { url = "ws://127.0.0.1:$(int::to_str { n = h.port })/ws/who", config = default_http_config } is
                      Err { ... } -> "connect failed"
                      Ok { val = s } -> do
                        let got = show { got = WebSocket.receive { socket = s } }
                        WebSocket.close { socket = s }
                        got
                    h.stop {}
                    out
            go {}
            """.trimIndent(),
        )
        assertEquals("text:/ws/who|websocket", r)
    }
}
