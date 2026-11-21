package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.Timeout.ThreadMode
import java.util.concurrent.TimeUnit

/**
 * `std::effects::net`: raw TCP and UDP. Three effects (`Tcp`, `TcpServer`, `Udp`),
 * real sockets (`NativeTcp`, `NativeTcpServer`, `NativeUdp`) and an in-memory
 * `Network` (`MockTcp`, `MockTcpServer`, `MockUdp`) that shares every op after
 * `connect` / `bind` with them; the helpers (`with_conn`, `lines`, `delimited`,
 * `frames`, `serve_on`) are functions over the ops, so one test body runs on
 * either backend.
 *
 * The mock parks the TASK, so a `CoopAsync` test schedules deterministically; the
 * real sockets block the thread, so those tests run under `LoomAsync`.
 */
class NetTest {

    private val head = """
        use std::effects::net::{ Addr, NetError, TcpConfig, default_tcp_config, TcpConn, Tcp, NativeTcp, TcpListener, Accepted, TcpServer, NativeTcpServer, listener_port, UdpSocket, Datagram, Udp, NativeUdp, udp_port, Network, mock_network, MockTcp, MockTcpServer, MockUdp, local, addr_to_str, with_conn, send_str, chunks, delimited, lines, frame, frames, serve_on, serve }
        use std::effects::async::{ Async, LoomAsync, CoopAsync, await }
        use std::collections::bytes
        use std::str
        use std::int
        use std::result

        ## Name a failure.
        let show_err: { e: NetError } -> Str = { e } -> match e is
          NetRefused { addr } -> "refused $(addr_to_str { addr = addr })"
          NetUnresolved { host } -> "unresolved $(host)"
          NetUnreachable { addr } -> "unreachable $(addr_to_str { addr = addr })"
          NetTimeout -> "timeout"
          NetBindFailed { addr, reason } -> "bind failed: $(reason)"
          NetClosed -> "closed"
          NetIo { reason } -> "io: $(reason)"

        let text: { b: Bytes } -> Str = { b } -> result::get_or { result = bytes::to_str { b = b }, def = "?" }

        ## Describe a receive.
        let show: { got: Result (Option Bytes) NetError } -> Str = { got } -> match got is
          Ok { val = Some { val = b } } -> text { b = b }
          Ok { val = None } -> "eof"
          Err { error } -> show_err { e = error }

        ## Describe a send.
        let sent: { r: Result {} NetError } -> Str = { r } -> match r is
          Ok { val = _ } -> "sent"
          Err { error } -> show_err { e = error }

        ## A line-echo handler for `serve_on`: every line comes back prefixed.
        let echo_lines: { conn: TcpConn, peer: Addr } -> {} <Tcp> = { conn, peer } -> do
          let _ = lines { conn = conn, init = {}, f = { acc, x } -> do
            let _ = send_str { conn = conn, s = "echo:$(x)\n" }
            {} }
          {}

        ## Serve `echo_lines` on `l` in a task, run one client against `addr`, close the listener, report both.
        let echo_round_trip: { l: TcpListener, addr: Addr } -> Str <Tcp, TcpServer, Async> = { l, addr } -> do
          let server = Async.spawn { f = {} -> serve_on { listener = l, handler = echo_lines } }
          let out = with_conn { addr = addr, config = default_tcp_config, f = { conn } -> do
            let _ = send_str { conn = conn, s = "one\ntwo\r\n" }
            let _ = Tcp.shutdown_write { conn = conn }
            lines { conn = conn, init = "", f = { acc, x } -> "$(acc)[$(x)]" } }
          TcpServer.close { listener = l }
          let served = match await { p = server } is
            Ok { val = Ok { val = _ } } -> "served"
            Ok { val = Err { error } } -> show_err { e = error }
            Err { error = _ } -> "server task failed"
          match out is
            Ok { val = Ok { val = s } } -> "$(s)|$(served)"
            Ok { val = Err { error } } -> show_err { e = error }
            Err { error } -> show_err { e = error }
    """.trimIndent()

    /** The shared head, then the body at column 1: a body interpolated INTO a raw string keeps its indent and reads as more statements of the head's last `do`. */
    private fun src(body: String): String = head + "\n" + body.trimIndent()

    // ==================== in memory ====================

    @Test
    fun `connecting where nothing listens is refused`() {
        val r = MezzeTestHelper.run(src(
            """
            let net = mock_network {}
            perform MockTcp { net = net } in
              match Tcp.connect { addr = local { port = 9 }, config = default_tcp_config } is
                Ok { val = _ } -> "connected?"
                Err { error } -> show_err { e = error }
            """,
        ))
        assertEquals("refused 127.0.0.1:9", r)
    }

    /** Both ends in one thread: chunking by `max_bytes`, half-close, and what each side sees after a close. */
    @Test
    fun `a connection chunks receives, half-closes, and refuses sends after a close`() {
        val r = MezzeTestHelper.run(src(
            """
            let net = mock_network {}
            perform MockTcp { net = net } in perform MockTcpServer { net = net } in
              match TcpServer.bind { addr = local { port = 1 }, config = default_tcp_config } is
                Err { error } -> show_err { e = error }
                Ok { val = l } -> match Tcp.connect { addr = local { port = 1 }, config = default_tcp_config } is
                  Err { error } -> show_err { e = error }
                  Ok { val = c } -> match TcpServer.accept { listener = l } is
                    Err { error } -> show_err { e = error }
                    Ok { val = a } -> do
                      let _ = send_str { conn = c, s = "hello world" }
                      let first = show { got = Tcp.receive { conn = a.conn, max_bytes = 5 } }
                      let second = show { got = Tcp.receive { conn = a.conn, max_bytes = 100 } }
                      let _ = Tcp.shutdown_write { conn = c }
                      let third = show { got = Tcp.receive { conn = a.conn, max_bytes = 100 } }
                      let _ = send_str { conn = a.conn, s = "still open" }
                      let back = show { got = Tcp.receive { conn = c, max_bytes = 100 } }
                      let after_shut = sent { r = send_str { conn = c, s = "late" } }
                      Tcp.close { conn = a.conn }
                      let peer_closed = show { got = Tcp.receive { conn = c, max_bytes = 100 } }
                      let send_closed = sent { r = send_str { conn = a.conn, s = "x" } }
                      TcpServer.close { listener = l }
                      "$(first)|$(second)|$(third)|$(back)|$(after_shut)|$(peer_closed)|$(send_closed)|$(a.peer.host)"
            """,
        ))
        assertEquals("hello| world|eof|still open|closed|eof|closed|127.0.0.1", r)
    }

    /** The whole server story under the deterministic scheduler: `serve_on` in a task, a client folding `lines`. */
    @Test
    fun `an echo server and its client interleave under CoopAsync`() {
        val r = MezzeTestHelper.run(src(
            """
            let net = mock_network {}
            perform MockTcp { net = net } in perform MockTcpServer { net = net } in perform CoopAsync in
              match TcpServer.bind { addr = local { port = 7000 }, config = default_tcp_config } is
                Err { error } -> show_err { e = error }
                Ok { val = l } -> echo_round_trip { l = l, addr = local { port = 7000 } }
            """,
        ))
        assertEquals("[echo:one][echo:two]|served", r)
    }

    /** A receive that runs out answers `NetTimeout` and the connection stays usable; the park has a deadline on a carrier too. */
    @Test
    fun `a receive timeout leaves the connection usable, on and off a carrier`() {
        val body = """
            let quick: TcpConfig = { connect_timeout_ms = 1000, receive_timeout_ms = 20, no_delay = True }
            let net = mock_network {}
            let go: {} -> Str <Tcp, TcpServer> = {} ->
              match TcpServer.bind { addr = local { port = 2 }, config = quick } is
                Err { error } -> show_err { e = error }
                Ok { val = l } -> match Tcp.connect { addr = local { port = 2 }, config = quick } is
                  Err { error } -> show_err { e = error }
                  Ok { val = c } -> match TcpServer.accept { listener = l } is
                    Err { error } -> show_err { e = error }
                    Ok { val = a } -> do
                      let waited = show { got = Tcp.receive { conn = c, max_bytes = 10 } }
                      let _ = send_str { conn = a.conn, s = "ok" }
                      let got = show { got = Tcp.receive { conn = c, max_bytes = 10 } }
                      TcpServer.close { listener = l }
                      "$(waited)|$(got)"
        """.trimIndent()
        val offCarrier = MezzeTestHelper.run("$head\n$body\nperform MockTcp { net = net } in perform MockTcpServer { net = net } in go {}")
        assertEquals("timeout|ok", offCarrier)
        val onCarrier = MezzeTestHelper.run("$head\n$body\nperform MockTcp { net = net } in perform MockTcpServer { net = net } in perform CoopAsync in go {}")
        assertEquals("timeout|ok", onCarrier)
    }

    /** Length-prefixed frames reassembled across arbitrary chunk boundaries; a cut-off frame is an error, not silence. */
    @Test
    fun `frames survive odd chunking and a cut-off frame is reported`() {
        val r = MezzeTestHelper.run(src(
            """
            let net = mock_network {}
            let all = bytes::concat { b1 = frame { data = bytes::from_str { s = "alpha" } }, b2 = frame { data = bytes::from_str { s = "beta" } } }
            let collect: { conn: TcpConn } -> Str <Tcp> = { conn } ->
              match frames { conn = conn, init = "", f = { acc, x } -> "$(acc)[$(text { b = x })]" } is
                Ok { val = s } -> s
                Err { error } -> show_err { e = error }
            perform MockTcp { net = net } in perform MockTcpServer { net = net } in
              match TcpServer.bind { addr = local { port = 3 }, config = default_tcp_config } is
                Err { error } -> show_err { e = error }
                Ok { val = l } -> do
                  let whole = match Tcp.connect { addr = local { port = 3 }, config = default_tcp_config } is
                    Err { error } -> show_err { e = error }
                    Ok { val = c } -> match TcpServer.accept { listener = l } is
                      Err { error } -> show_err { e = error }
                      Ok { val = a } -> do
                        let _ = Tcp.send { conn = a.conn, data = bytes::take { b = all, n = 3 } }
                        let _ = Tcp.send { conn = a.conn, data = bytes::slice { b = all, start = 3, end = 10 } }
                        let _ = Tcp.send { conn = a.conn, data = bytes::drop { b = all, n = 10 } }
                        let _ = Tcp.shutdown_write { conn = a.conn }
                        collect { conn = c }
                  let cut = match Tcp.connect { addr = local { port = 3 }, config = default_tcp_config } is
                    Err { error } -> show_err { e = error }
                    Ok { val = c } -> match TcpServer.accept { listener = l } is
                      Err { error } -> show_err { e = error }
                      Ok { val = a } -> do
                        let _ = Tcp.send { conn = a.conn, data = bytes::take { b = all, n = 6 } }
                        let _ = Tcp.shutdown_write { conn = a.conn }
                        collect { conn = c }
                  TcpServer.close { listener = l }
                  "$(whole)|$(cut)"
            """,
        ))
        assertEquals("[alpha][beta]|io: the peer closed inside a frame, 6 byte(s) in", r)
    }

    /** `delimited` with a multi-byte delimiter, the unterminated tail delivered; `lines` turning a bad byte into `NetIo`. */
    @Test
    fun `delimited frames and lines handle tails and bad bytes`() {
        val r = MezzeTestHelper.run(src(
            """
            let net = mock_network {}
            let pair: { l: TcpListener, port: Int } -> Option { c: TcpConn, a: TcpConn } <Tcp, TcpServer> = { l, port } ->
              match Tcp.connect { addr = local { port = port }, config = default_tcp_config } is
                Err { error = _ } -> None
                Ok { val = c } -> match TcpServer.accept { listener = l } is
                  Err { error = _ } -> None
                  Ok { val = a } -> Some { val = { c = c, a = a.conn } }
            perform MockTcp { net = net } in perform MockTcpServer { net = net } in
              match TcpServer.bind { addr = local { port = 4 }, config = default_tcp_config } is
                Err { error } -> show_err { e = error }
                Ok { val = l } -> do
                  let framed = match pair { l = l, port = 4 } is
                    None -> "no pair"
                    Some { val = p } -> do
                      let _ = send_str { conn = p.a, s = "a::bb::ccc" }
                      let _ = Tcp.shutdown_write { conn = p.a }
                      match delimited { conn = p.c, delim = bytes::from_str { s = "::" }, init = "", f = { acc, x } -> "$(acc)<$(text { b = x })>" } is
                        Ok { val = s } -> s
                        Err { error } -> show_err { e = error }
                  let bad = match pair { l = l, port = 4 } is
                    None -> "no pair"
                    Some { val = p } -> do
                      let _ = send_str { conn = p.a, s = "fine\n" }
                      let _ = Tcp.send { conn = p.a, data = bytes::from_seq { arr = [255, 254, 10] } }
                      let _ = send_str { conn = p.a, s = "after\n" }
                      let _ = Tcp.shutdown_write { conn = p.a }
                      match lines { conn = p.c, init = "", f = { acc, x } -> "$(acc)<$(x)>" } is
                        Ok { val = s } -> s
                        Err { error = NetIo { reason } } -> if str::starts_with { s = reason, prefix = "a line is not UTF-8" } then "bad utf-8" else reason
                        Err { error } -> show_err { e = error }
                  TcpServer.close { listener = l }
                  "$(framed)|$(bad)"
            """,
        ))
        assertEquals("<a><bb><ccc>|bad utf-8", r)
    }

    /** Closing a listener while a task waits in `accept` ends that wait with `NetClosed`, deterministically. */
    @Test
    fun `closing the listener ends a parked accept`() {
        val r = MezzeTestHelper.run(src(
            """
            let net = mock_network {}
            perform MockTcp { net = net } in perform MockTcpServer { net = net } in perform CoopAsync in
              match TcpServer.bind { addr = local { port = 5 }, config = default_tcp_config } is
                Err { error } -> show_err { e = error }
                Ok { val = l } -> do
                  let waiter = Async.spawn { f = {} -> match TcpServer.accept { listener = l } is
                    Ok { val = _ } -> "accepted?"
                    Err { error } -> show_err { e = error } }
                  TcpServer.close { listener = l }
                  match await { p = waiter } is
                    Ok { val = s } -> s
                    Err { error = _ } -> "task failed"
            """,
        ))
        assertEquals("closed", r)
    }

    @Test
    fun `datagrams reach a bound socket, vanish otherwise, and time out`() {
        val r = MezzeTestHelper.run(src(
            """
            let net = mock_network {}
            let show_dg: { r: Result Datagram NetError, from_port: Int } -> Str = { r, from_port } -> match r is
              Ok { val = d } -> "$(text { b = d.data }) from $(if d.from.port == from_port then "a" else "?")"
              Err { error } -> show_err { e = error }
            perform MockUdp { net = net } in
              match Udp.bind { addr = local { port = 0 }, receive_timeout_ms = 20 } is
                Err { error } -> show_err { e = error }
                Ok { val = a } -> match Udp.bind { addr = local { port = 0 }, receive_timeout_ms = 20 } is
                  Err { error } -> show_err { e = error }
                  Ok { val = b } -> do
                    let _ = Udp.send_to { sock = a, addr = local { port = udp_port { sock = b } }, data = bytes::from_str { s = "ping" } }
                    let got = show_dg { r = Udp.recv_from { sock = b, max_bytes = 100 }, from_port = udp_port { sock = a } }
                    let _ = Udp.send_to { sock = b, addr = local { port = udp_port { sock = a } }, data = bytes::from_str { s = "pong!" } }
                    let cut = show_dg { r = Udp.recv_from { sock = a, max_bytes = 4 }, from_port = udp_port { sock = b } }
                    let nobody = sent { r = Udp.send_to { sock = a, addr = local { port = 9 }, data = bytes::from_str { s = "lost" } } }
                    let waited = show_dg { r = Udp.recv_from { sock = a, max_bytes = 100 }, from_port = 0 }
                    let distinct = if udp_port { sock = a } == udp_port { sock = b } then "same port" else "two ports"
                    Udp.close { sock = a }
                    Udp.close { sock = b }
                    "$(got)|$(cut)|$(nobody)|$(waited)|$(distinct)"
            """,
        ))
        assertEquals("ping from a|pong from a|sent|timeout|two ports", r)
    }

    @Test
    fun `binding a port twice fails and names the address`() {
        val r = MezzeTestHelper.run(src(
            """
            let net = mock_network {}
            perform MockTcpServer { net = net } in
              match TcpServer.bind { addr = local { port = 6 }, config = default_tcp_config } is
                Err { error } -> show_err { e = error }
                Ok { val = l } -> do
                  let again = match TcpServer.bind { addr = local { port = 6 }, config = default_tcp_config } is
                    Ok { val = _ } -> "bound twice?"
                    Err { error = NetBindFailed { addr, reason = _ } } -> "bind failed on $(addr_to_str { addr = addr })"
                    Err { error } -> show_err { e = error }
                  TcpServer.close { listener = l }
                  let after = match TcpServer.bind { addr = local { port = 6 }, config = default_tcp_config } is
                    Ok { val = l2 } -> do
                      TcpServer.close { listener = l2 }
                      "rebound"
                    Err { error } -> show_err { e = error }
                  "$(again)|$(after)"
            """,
        ))
        assertEquals("bind failed on 127.0.0.1:6|rebound", r)
    }

    // ==================== over real sockets ====================

    /** The same `echo_round_trip` as the mock test, on loopback: port 0, the kernel's port read back. */
    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
    fun `an echo server and its client round-trip over loopback`() {
        val r = MezzeTestHelper.run(src(
            """
            perform NativeTcp in perform NativeTcpServer in perform LoomAsync in
              match TcpServer.bind { addr = local { port = 0 }, config = default_tcp_config } is
                Err { error } -> show_err { e = error }
                Ok { val = l } -> echo_round_trip { l = l, addr = local { port = listener_port { listener = l } } }
            """,
        ))
        assertEquals("[echo:one][echo:two]|served", r)
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
    fun `a real connect to a port nobody listens on is refused`() {
        val r = MezzeTestHelper.run(src(
            """
            perform NativeTcp in perform NativeTcpServer in
              match TcpServer.bind { addr = local { port = 0 }, config = default_tcp_config } is
                Err { error } -> show_err { e = error }
                Ok { val = l } -> do
                  let port = listener_port { listener = l }
                  TcpServer.close { listener = l }
                  match Tcp.connect { addr = local { port = port }, config = default_tcp_config } is
                    Ok { val = _ } -> "connected?"
                    Err { error } -> show_err { e = error }
            """,
        )) as String
        assertTrue(r.startsWith("refused 127.0.0.1:"), r)
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
    fun `closing a real listener ends a blocked accept`() {
        val r = MezzeTestHelper.run(src(
            """
            perform NativeTcp in perform NativeTcpServer in perform LoomAsync in
              match TcpServer.bind { addr = local { port = 0 }, config = default_tcp_config } is
                Err { error } -> show_err { e = error }
                Ok { val = l } -> do
                  let waiter = Async.spawn { f = {} -> match TcpServer.accept { listener = l } is
                    Ok { val = _ } -> "accepted?"
                    Err { error } -> show_err { e = error } }
                  TcpServer.close { listener = l }
                  match await { p = waiter } is
                    Ok { val = s } -> s
                    Err { error = _ } -> "task failed"
            """,
        ))
        assertEquals("closed", r)
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS, threadMode = ThreadMode.SEPARATE_THREAD)
    fun `real datagrams round-trip on loopback and time out`() {
        val r = MezzeTestHelper.run(src(
            """
            let show_dg: { r: Result Datagram NetError, from_port: Int } -> Str = { r, from_port } -> match r is
              Ok { val = d } -> "$(text { b = d.data }) from $(if d.from.port == from_port then "a" else "?")"
              Err { error } -> show_err { e = error }
            perform NativeUdp in
              match Udp.bind { addr = local { port = 0 }, receive_timeout_ms = 200 } is
                Err { error } -> show_err { e = error }
                Ok { val = a } -> match Udp.bind { addr = local { port = 0 }, receive_timeout_ms = 200 } is
                  Err { error } -> show_err { e = error }
                  Ok { val = b } -> do
                    let _ = Udp.send_to { sock = a, addr = local { port = udp_port { sock = b } }, data = bytes::from_str { s = "ping" } }
                    let got = show_dg { r = Udp.recv_from { sock = b, max_bytes = 100 }, from_port = udp_port { sock = a } }
                    let waited = show_dg { r = Udp.recv_from { sock = a, max_bytes = 100 }, from_port = 0 }
                    Udp.close { sock = a }
                    Udp.close { sock = b }
                    "$(got)|$(waited)"
            """,
        ))
        assertEquals("ping from a|timeout", r)
    }
}
