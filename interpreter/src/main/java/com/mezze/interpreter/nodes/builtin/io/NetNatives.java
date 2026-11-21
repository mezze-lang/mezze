package com.mezze.interpreter.nodes.builtin.io;

import com.mezze.interpreter.runtime.MezzeContext;
import com.mezze.interpreter.runtime.NativeTypeMismatchException;
import com.mezze.interpreter.values.MezzeGuestException;
import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.interpreter.values.MezzeVariant;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.ConnectException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.PortUnreachableException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Arrays;

/**
 * `std::effects::net`: raw TCP and UDP over the JDK's sockets, and the connection,
 * listener and datagram ops that are SHARED with the in-memory network
 * ({@link MockNetNatives}).
 *
 * <p>One shape, two backends. A guest `TcpConn` is a {@link Conn}, a `TcpListener`
 * a {@link Listener}, a `UdpSocket` a {@link UdpSock}; the shared natives
 * (`__native_net_conn_*`, `__native_net_listener_*`, `__native_net_udp_*`) call
 * the interface, so what the handle IS decides the behaviour — never a name.
 * The real backend blocks the calling thread (a virtual thread under
 * `LoomAsync`); the mock backend parks the task.
 *
 * <p>Failures become a guest `NetError` by the exception's CLASS, never by its
 * message: {@link ConnectException} is `NetRefused`, {@link SocketTimeoutException}
 * `NetTimeout`, an unresolved address `NetUnresolved`, {@link NoRouteToHostException}
 * `NetUnreachable`, {@link BindException} `NetBindFailed` (with the JDK's reason,
 * since the class alone does not tell "in use" from "not permitted"), an
 * operation on a handle this side closed `NetClosed`, and anything else `NetIo`.
 */
public final class NetNatives {

    static final MezzeVariant.TagRegistry REG = MezzeVariant.TagRegistry.getGlobal();
    static int tag(String ctor) { return REG.getOrCreateTag(ctor); }

    // ==================== the shapes both backends implement ====================

    /** One TCP connection. */
    public interface Conn {
        /** At most {@code max} bytes, blocking until some arrive; {@code null} once the peer closed its side. */
        byte[] receive(int max) throws NetFailure;
        void send(byte[] data) throws NetFailure;
        void shutdownWrite() throws NetFailure;
        void close();
    }

    /** A listening TCP socket. */
    public interface Listener {
        /** The next connection, blocking. */
        Accepted accept() throws NetFailure;
        void close();
        int port();
    }

    /** What {@link Listener#accept()} hands back. */
    public record Accepted(Conn conn, String host, int port) {}

    /** A bound UDP socket. */
    public interface UdpSock {
        void sendTo(String host, int port, byte[] data) throws NetFailure;
        /** The next datagram, cut to {@code max} bytes, blocking. */
        Datagram recvFrom(int max) throws NetFailure;
        void close();
        int port();
    }

    /** One datagram and its sender. */
    public record Datagram(byte[] data, String host, int port) {}

    /** A failure already mapped to its guest `NetError`. */
    public static final class NetFailure extends Exception {
        final MezzeVariant error;
        NetFailure(MezzeVariant error) { super(null, null, false, false); this.error = error; }
    }

    // ==================== guest values ====================

    static MezzeRecord addr(String host, int port) {
        return MezzeRecord.fromFields(new String[]{"host", "port"}, new Object[]{host, (long) port});
    }

    static MezzeVariant withAddr(String ctor, String host, int port) {
        return MezzeVariant.singlePayload(REG, tag(ctor),
            MezzeRecord.fromFields(new String[]{"addr"}, new Object[]{addr(host, port)}));
    }

    static NetFailure refused(String host, int port) { return new NetFailure(withAddr("NetRefused", host, port)); }
    static NetFailure unreachable(String host, int port) { return new NetFailure(withAddr("NetUnreachable", host, port)); }
    static NetFailure unresolved(String host) {
        return new NetFailure(MezzeVariant.singlePayload(REG, tag("NetUnresolved"),
            MezzeRecord.fromFields(new String[]{"host"}, new Object[]{host})));
    }
    static NetFailure timeout() { return new NetFailure(MezzeVariant.noPayload(REG, tag("NetTimeout"))); }
    static NetFailure closed() { return new NetFailure(MezzeVariant.noPayload(REG, tag("NetClosed"))); }
    static NetFailure bindFailed(String host, int port, String reason) {
        return new NetFailure(MezzeVariant.singlePayload(REG, tag("NetBindFailed"),
            MezzeRecord.fromFields(new String[]{"addr", "reason"}, new Object[]{addr(host, port), reason})));
    }
    static NetFailure io(Throwable e) {
        String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return new NetFailure(MezzeVariant.singlePayload(REG, tag("NetIo"),
            MezzeRecord.fromFields(new String[]{"reason"}, new Object[]{reason})));
    }

    static MezzeVariant ok(Object v) { return MezzeVariant.ok(v); }
    static MezzeVariant err(NetFailure f) { return MezzeVariant.err(f.error); }
    static final MezzeVariant UNIT_OK = MezzeVariant.ok(MezzeRecord.empty());

    // ==================== guest arguments ====================

    static MezzeRecord record(MezzeRecord args, String field) {
        Object o = args.getField(field);
        if (o instanceof MezzeRecord r) return r;
        throw new NativeTypeMismatchException("net natives expect '" + field + "' to be a record");
    }

    static String hostOf(MezzeRecord addr) {
        Object o = addr.getField("host");
        if (o instanceof String s) return s;
        throw new NativeTypeMismatchException("an Addr's 'host' must be Str");
    }

    static int portOf(MezzeRecord addr) {
        long p = longOf(addr, "port");
        if (p < 0 || p > 65535) throw new MezzeGuestException("a port is 0..65535; got " + p);
        return (int) p;
    }

    static long longOf(MezzeRecord r, String field) {
        Object o = r.getField(field);
        if (o instanceof Long l) return l;
        if (o instanceof Integer i) return i.longValue();
        throw new NativeTypeMismatchException("net natives expect '" + field + "' to be Int");
    }

    static boolean boolOf(MezzeRecord r, String field) {
        Object o = r.getField(field);
        if (o instanceof Boolean b) return b;
        throw new NativeTypeMismatchException("net natives expect '" + field + "' to be Bool");
    }

    static byte[] bytesOf(MezzeRecord args, String field) {
        Object o = args.getField(field);
        if (o instanceof BytesNatives.MezzeBytes b) return b.getData();
        throw new NativeTypeMismatchException("net natives expect '" + field + "' to be Bytes");
    }

    /** A positive byte count; a guest asking for zero or fewer bytes has a bug worth naming. */
    static int maxBytesOf(MezzeRecord args) {
        long m = longOf(args, "max_bytes");
        if (m <= 0) throw new MezzeGuestException("max_bytes must be positive; got " + m);
        return (int) Math.min(m, 1 << 24);
    }

    static Conn connOf(MezzeRecord args) {
        Object o = args.getField("conn");
        if (o instanceof Conn c) return c;
        throw new NativeTypeMismatchException("Tcp ops expect a TcpConn from connect or accept");
    }

    static Listener listenerOf(MezzeRecord args) {
        Object o = args.getField("listener");
        if (o instanceof Listener l) return l;
        throw new NativeTypeMismatchException("TcpServer ops expect a TcpListener from bind");
    }

    static UdpSock udpOf(MezzeRecord args) {
        Object o = args.getField("sock");
        if (o instanceof UdpSock u) return u;
        throw new NativeTypeMismatchException("Udp ops expect a UdpSocket from bind");
    }

    /** A `TcpConfig` record's three fields, with the stdlib defaults for a field left out. */
    record TcpConfig(int connectTimeoutMs, int receiveTimeoutMs, boolean noDelay) {
        static TcpConfig of(MezzeRecord args) {
            MezzeRecord c = record(args, "config");
            return new TcpConfig(
                (int) Math.min(Math.max(longOf(c, "connect_timeout_ms"), 0), Integer.MAX_VALUE),
                (int) Math.min(Math.max(longOf(c, "receive_timeout_ms"), 0), Integer.MAX_VALUE),
                boolOf(c, "no_delay"));
        }
    }

    // ==================== shared ops: dispatch on the handle ====================

    /** `__native_net_conn_receive { conn, max_bytes }` → `Result (Option Bytes) NetError`. */
    @TruffleBoundary
    public static Object connReceive(MezzeRecord args) {
        Conn c = connOf(args);
        int max = maxBytesOf(args);
        try {
            byte[] got = c.receive(max);
            return ok(got == null ? MezzeVariant.none() : MezzeVariant.some(new BytesNatives.MezzeBytes(got)));
        } catch (NetFailure f) {
            return err(f);
        }
    }

    /** `__native_net_conn_send { conn, data }` → `Result {} NetError`. */
    @TruffleBoundary
    public static Object connSend(MezzeRecord args) {
        Conn c = connOf(args);
        byte[] data = bytesOf(args, "data");
        try {
            c.send(data);
            return UNIT_OK;
        } catch (NetFailure f) {
            return err(f);
        }
    }

    /** `__native_net_conn_shutdown_write { conn }` → `Result {} NetError`. */
    @TruffleBoundary
    public static Object connShutdownWrite(MezzeRecord args) {
        try {
            connOf(args).shutdownWrite();
            return UNIT_OK;
        } catch (NetFailure f) {
            return err(f);
        }
    }

    /** `__native_net_conn_close { conn }`. Idempotent. */
    @TruffleBoundary
    public static Object connClose(MezzeRecord args) {
        connOf(args).close();
        return MezzeRecord.empty();
    }

    /** `__native_net_listener_accept { listener }` → `Result Accepted NetError`. */
    @TruffleBoundary
    public static Object listenerAccept(MezzeRecord args) {
        try {
            Accepted a = listenerOf(args).accept();
            return ok(MezzeRecord.fromFields(new String[]{"conn", "peer"}, new Object[]{a.conn(), addr(a.host(), a.port())}));
        } catch (NetFailure f) {
            return err(f);
        }
    }

    /** `__native_net_listener_close { listener }`. Idempotent. */
    @TruffleBoundary
    public static Object listenerClose(MezzeRecord args) {
        listenerOf(args).close();
        return MezzeRecord.empty();
    }

    /** `__native_net_listener_port { listener }` → `Int`. */
    @TruffleBoundary
    public static Object listenerPort(MezzeRecord args) {
        return (long) listenerOf(args).port();
    }

    /** `__native_net_udp_send_to { sock, addr, data }` → `Result {} NetError`. */
    @TruffleBoundary
    public static Object udpSendTo(MezzeRecord args) {
        UdpSock u = udpOf(args);
        MezzeRecord to = record(args, "addr");
        byte[] data = bytesOf(args, "data");
        try {
            u.sendTo(hostOf(to), portOf(to), data);
            return UNIT_OK;
        } catch (NetFailure f) {
            return err(f);
        }
    }

    /** `__native_net_udp_recv_from { sock, max_bytes }` → `Result Datagram NetError`. */
    @TruffleBoundary
    public static Object udpRecvFrom(MezzeRecord args) {
        UdpSock u = udpOf(args);
        int max = maxBytesOf(args);
        try {
            Datagram d = u.recvFrom(max);
            return ok(MezzeRecord.fromFields(new String[]{"data", "from"},
                new Object[]{new BytesNatives.MezzeBytes(d.data()), addr(d.host(), d.port())}));
        } catch (NetFailure f) {
            return err(f);
        }
    }

    /** `__native_net_udp_close { sock }`. Idempotent. */
    @TruffleBoundary
    public static Object udpClose(MezzeRecord args) {
        udpOf(args).close();
        return MezzeRecord.empty();
    }

    /** `__native_net_udp_port { sock }` → `Int`. */
    @TruffleBoundary
    public static Object udpPort(MezzeRecord args) {
        return (long) udpOf(args).port();
    }

    // ==================== the real backend ====================

    /** `__native_tcp_connect { addr, config }` → `Result TcpConn NetError`. */
    @TruffleBoundary
    public static Object tcpConnect(MezzeRecord args) {
        MezzeRecord a = record(args, "addr");
        String host = hostOf(a);
        int port = portOf(a);
        TcpConfig cfg = TcpConfig.of(args);
        Socket s = new Socket();
        try {
            InetSocketAddress target = new InetSocketAddress(host, port);
            if (target.isUnresolved()) throw unresolved(host);
            s.setTcpNoDelay(cfg.noDelay());
            s.setSoTimeout(cfg.receiveTimeoutMs());
            s.connect(target, cfg.connectTimeoutMs());
            return ok(new SocketConn(s));
        } catch (NetFailure f) {
            closeQuietly(s);
            return err(f);
        } catch (ConnectException e) {
            closeQuietly(s);
            return err(refused(host, port));
        } catch (SocketTimeoutException e) {
            closeQuietly(s);
            return err(timeout());
        } catch (NoRouteToHostException e) {
            closeQuietly(s);
            return err(unreachable(host, port));
        } catch (UnknownHostException e) {
            closeQuietly(s);
            return err(unresolved(host));
        } catch (IOException | IllegalArgumentException e) {
            closeQuietly(s);
            return err(io(e));
        }
    }

    /** `__native_tcp_bind { addr, config }` → `Result TcpListener NetError`. Registered for dispose with the context. */
    @TruffleBoundary
    public static Object tcpBind(MezzeRecord args) {
        MezzeRecord a = record(args, "addr");
        String host = hostOf(a);
        int port = portOf(a);
        TcpConfig cfg = TcpConfig.of(args);
        ServerSocket ss = null;
        try {
            InetSocketAddress at = new InetSocketAddress(host, port);
            if (at.isUnresolved()) throw unresolved(host);
            ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(at);
            ServerListener l = new ServerListener(ss, cfg);
            MezzeContext.get(null).registerForDispose(l);
            return ok(l);
        } catch (NetFailure f) {
            closeQuietly(ss);
            return err(f);
        } catch (BindException e) {
            closeQuietly(ss);
            return err(bindFailed(host, port, e.getMessage() == null ? "bind failed" : e.getMessage()));
        } catch (IOException | IllegalArgumentException e) {
            closeQuietly(ss);
            return err(io(e));
        }
    }

    /** `__native_udp_bind { addr, receive_timeout_ms }` → `Result UdpSocket NetError`. Registered for dispose with the context. */
    @TruffleBoundary
    public static Object udpBind(MezzeRecord args) {
        MezzeRecord a = record(args, "addr");
        String host = hostOf(a);
        int port = portOf(a);
        int timeout = (int) Math.min(Math.max(longOf(args, "receive_timeout_ms"), 0), Integer.MAX_VALUE);
        DatagramSocket ds = null;
        try {
            InetSocketAddress at = new InetSocketAddress(host, port);
            if (at.isUnresolved()) throw unresolved(host);
            ds = new DatagramSocket(null);
            ds.setReuseAddress(true);
            ds.bind(at);
            ds.setSoTimeout(timeout);
            DatagramSock u = new DatagramSock(ds);
            MezzeContext.get(null).registerForDispose(u);
            return ok(u);
        } catch (NetFailure f) {
            closeQuietly(ds);
            return err(f);
        } catch (BindException e) {
            closeQuietly(ds);
            return err(bindFailed(host, port, e.getMessage() == null ? "bind failed" : e.getMessage()));
        } catch (IOException | IllegalArgumentException e) {
            closeQuietly(ds);
            return err(io(e));
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try { c.close(); } catch (IOException ignored) { /* already failing */ }
    }

    /** A connected `java.net.Socket`. */
    static final class SocketConn implements Conn {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;
        private volatile boolean closed;
        private volatile boolean writeShut;

        SocketConn(Socket socket) throws IOException {
            this.socket = socket;
            this.in = socket.getInputStream();
            this.out = socket.getOutputStream();
        }

        @Override
        public byte[] receive(int max) throws NetFailure {
            if (closed) throw closed();
            byte[] buf = new byte[max];
            int n;
            try {
                n = in.read(buf, 0, max);
            } catch (SocketTimeoutException e) {
                throw timeout();
            } catch (IOException e) {
                if (closed) throw closed();   // `close` from another task unblocked this read
                throw io(e);
            }
            if (n < 0) return null;
            return n == max ? buf : Arrays.copyOf(buf, n);
        }

        @Override
        public void send(byte[] data) throws NetFailure {
            if (closed || writeShut) throw closed();
            try {
                out.write(data);
                out.flush();
            } catch (IOException e) {
                if (closed) throw closed();
                throw io(e);
            }
        }

        @Override
        public void shutdownWrite() throws NetFailure {
            if (closed) throw closed();
            if (writeShut) return;
            writeShut = true;
            try {
                socket.shutdownOutput();
            } catch (IOException e) {
                throw io(e);
            }
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            closeQuietly(socket);
        }
    }

    /** A bound `ServerSocket`; accepted connections get the listener's config. */
    static final class ServerListener implements Listener, AutoCloseable {
        private final ServerSocket server;
        private final TcpConfig cfg;
        private volatile boolean closed;

        ServerListener(ServerSocket server, TcpConfig cfg) {
            this.server = server;
            this.cfg = cfg;
        }

        @Override
        public Accepted accept() throws NetFailure {
            if (closed) throw closed();
            Socket s;
            try {
                s = server.accept();
            } catch (IOException e) {
                if (closed) throw closed();   // `close` from another task unblocked this accept
                throw io(e);
            }
            try {
                s.setTcpNoDelay(cfg.noDelay());
                s.setSoTimeout(cfg.receiveTimeoutMs());
                InetSocketAddress peer = (InetSocketAddress) s.getRemoteSocketAddress();
                String host = peer == null ? "" : peer.getAddress().getHostAddress();
                int port = peer == null ? 0 : peer.getPort();
                return new Accepted(new SocketConn(s), host, port);
            } catch (IOException e) {
                closeQuietly(s);
                throw io(e);
            }
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            closeQuietly(server);
        }

        @Override
        public int port() { return server.getLocalPort(); }
    }

    /** A bound `DatagramSocket`. */
    static final class DatagramSock implements UdpSock, AutoCloseable {
        private final DatagramSocket socket;
        private volatile boolean closed;

        DatagramSock(DatagramSocket socket) { this.socket = socket; }

        @Override
        public void sendTo(String host, int port, byte[] data) throws NetFailure {
            if (closed) throw closed();
            InetAddress to;
            try {
                to = InetAddress.getByName(host);
            } catch (UnknownHostException e) {
                throw unresolved(host);
            }
            try {
                socket.send(new DatagramPacket(data, data.length, to, port));
            } catch (PortUnreachableException e) {
                throw unreachable(host, port);
            } catch (IOException | IllegalArgumentException e) {
                if (closed) throw closed();
                throw io(e);
            }
        }

        @Override
        public Datagram recvFrom(int max) throws NetFailure {
            if (closed) throw closed();
            DatagramPacket p = new DatagramPacket(new byte[max], max);
            try {
                socket.receive(p);
            } catch (SocketTimeoutException e) {
                throw timeout();
            } catch (PortUnreachableException e) {
                throw io(e);
            } catch (IOException e) {
                if (closed) throw closed();
                throw io(e);
            }
            byte[] data = Arrays.copyOf(p.getData(), p.getLength());
            InetAddress from = p.getAddress();
            return new Datagram(data, from == null ? "" : from.getHostAddress(), p.getPort());
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            socket.close();
        }

        @Override
        public int port() { return socket.getLocalPort(); }
    }

    private NetNatives() {}
}
