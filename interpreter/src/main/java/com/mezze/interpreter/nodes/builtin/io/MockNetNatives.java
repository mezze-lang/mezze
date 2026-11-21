package com.mezze.interpreter.nodes.builtin.io;

import com.mezze.interpreter.nodes.continuation.TaskMachine;
import com.mezze.interpreter.nodes.continuation.WaitQueue;
import com.mezze.interpreter.runtime.NativeTypeMismatchException;
import com.mezze.interpreter.values.MezzeRecord;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * The in-memory network behind `MockTcp`, `MockTcpServer` and `MockUdp`: one
 * {@link Network} of listeners and UDP sockets, connections that are two
 * {@link Mailbox}es wired to each other, datagrams that land in the bound
 * socket's mailbox or nowhere.
 *
 * <p>The handles implement {@link NetNatives.Conn}, {@link NetNatives.Listener}
 * and {@link NetNatives.UdpSock}, so every op after `connect` / `bind` is the
 * shared native. A wait parks the TASK ({@link TaskMachine#waitOnQueue}) on a
 * carrier and is a monitor wait off one; every state change signals both.
 */
public final class MockNetNatives {

    /** Where mock performers meet: who listens on which address. */
    public static final class Network {
        private final Map<String, MockListener> listeners = new HashMap<>();
        private final Map<String, MockUdp> udp = new HashMap<>();
        private int nextEphemeral = 49152;

        static String key(String host, int port) { return host + ":" + port; }

        synchronized int allocatePort(String host, Map<String, ?> table) {
            for (int tries = 0; tries < 16384; tries++) {
                int p = nextEphemeral++;
                if (nextEphemeral > 65535) nextEphemeral = 49152;
                if (!table.containsKey(key(host, p))) return p;
            }
            throw new IllegalStateException("the mock network ran out of ephemeral ports on " + host);
        }

        synchronized MockListener listener(String host, int port) { return listeners.get(key(host, port)); }
        synchronized MockUdp udp(String host, int port) { return udp.get(key(host, port)); }
    }

    /** A queue one side fills and the other drains, with an end marker. */
    static final class Mailbox<T> {
        private final ArrayDeque<T> items = new ArrayDeque<>();
        private boolean ended;
        final WaitQueue waiters = new WaitQueue();

        void offer(T item) {
            synchronized (this) { items.add(item); notifyAll(); }
            waiters.signal();
        }

        void end() {
            synchronized (this) { ended = true; notifyAll(); }
            waiters.signal();
        }

        synchronized boolean ready() { return !items.isEmpty() || ended; }

        /** Wait until something is queued or the box has ended; false only at the deadline. */
        boolean await(long timeoutMs) {
            BooleanSupplier ready = this::ready;
            if (TaskMachine.onCarrier()) {
                long deadline = timeoutMs > 0 ? System.nanoTime() + timeoutMs * 1_000_000L : 0;
                return TaskMachine.waitOnQueue(waiters, ready, deadline);
            }
            synchronized (this) {
                long deadline = timeoutMs > 0 ? System.nanoTime() + timeoutMs * 1_000_000L : 0;
                while (!ready()) {
                    try {
                        if (deadline == 0) {
                            wait();
                        } else {
                            long left = deadline - System.nanoTime();
                            if (left <= 0) return false;
                            wait(Math.max(1, left / 1_000_000L), (int) (left % 1_000_000L));
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new CancellationException("mock network wait cancelled");
                    }
                }
                return true;
            }
        }

        synchronized T peek() { return items.peek(); }
        synchronized T poll() { return items.poll(); }
        synchronized void replaceHead(T item) { items.poll(); items.addFirst(item); }
        synchronized boolean isEnded() { return ended; }
        synchronized List<T> drain() { List<T> out = new ArrayList<>(items); items.clear(); return out; }
    }

    /** One end of an in-memory connection. */
    static final class MockConn implements NetNatives.Conn {
        final Mailbox<byte[]> inbox = new Mailbox<>();
        final long receiveTimeoutMs;
        volatile MockConn peer;
        volatile boolean closed;
        volatile boolean writeShut;

        MockConn(long receiveTimeoutMs) { this.receiveTimeoutMs = receiveTimeoutMs; }

        @Override
        public byte[] receive(int max) throws NetNatives.NetFailure {
            while (true) {
                if (closed) throw NetNatives.closed();
                if (!inbox.await(receiveTimeoutMs)) throw NetNatives.timeout();
                synchronized (inbox) {
                    byte[] head = inbox.peek();
                    if (head == null) {
                        if (inbox.isEnded()) return null;
                        continue;                       // taken by someone else between the wake and here
                    }
                    if (head.length <= max) { inbox.poll(); return head; }
                    inbox.replaceHead(Arrays.copyOfRange(head, max, head.length));
                    return Arrays.copyOf(head, max);
                }
            }
        }

        @Override
        public void send(byte[] data) throws NetNatives.NetFailure {
            if (closed || writeShut) throw NetNatives.closed();
            MockConn p = peer;
            if (p == null || p.closed) throw NetNatives.closed();
            if (data.length > 0) p.inbox.offer(data.clone());
        }

        @Override
        public void shutdownWrite() throws NetNatives.NetFailure {
            if (closed) throw NetNatives.closed();
            if (writeShut) return;
            writeShut = true;
            MockConn p = peer;
            if (p != null) p.inbox.end();
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            inbox.end();
            MockConn p = peer;
            if (p != null) p.inbox.end();
        }
    }

    /** A bound in-memory listener: connects queue up here until accepted. */
    static final class MockListener implements NetNatives.Listener {
        final Network net;
        final String host;
        final int port;
        final NetNatives.TcpConfig cfg;
        final Mailbox<NetNatives.Accepted> pending = new Mailbox<>();
        volatile boolean closed;

        MockListener(Network net, String host, int port, NetNatives.TcpConfig cfg) {
            this.net = net; this.host = host; this.port = port; this.cfg = cfg;
        }

        @Override
        public NetNatives.Accepted accept() throws NetNatives.NetFailure {
            while (true) {
                if (closed) throw NetNatives.closed();
                pending.await(0);
                NetNatives.Accepted a = pending.poll();
                if (a != null) return a;
                if (pending.isEnded()) throw NetNatives.closed();
            }
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            synchronized (net) { net.listeners.remove(Network.key(host, port), this); }
            pending.end();
            for (NetNatives.Accepted a : pending.drain()) a.conn().close();   // never handed out: the peer sees a close
        }

        @Override
        public int port() { return port; }
    }

    /** A bound in-memory UDP socket. */
    static final class MockUdp implements NetNatives.UdpSock {
        final Network net;
        final String host;
        final int port;
        final long receiveTimeoutMs;
        final Mailbox<NetNatives.Datagram> inbox = new Mailbox<>();
        volatile boolean closed;

        MockUdp(Network net, String host, int port, long receiveTimeoutMs) {
            this.net = net; this.host = host; this.port = port; this.receiveTimeoutMs = receiveTimeoutMs;
        }

        @Override
        public void sendTo(String toHost, int toPort, byte[] data) throws NetNatives.NetFailure {
            if (closed) throw NetNatives.closed();
            MockUdp target = net.udp(toHost, toPort);
            if (target == null || target.closed) return;   // nobody there: dropped, as on a wire
            target.inbox.offer(new NetNatives.Datagram(data.clone(), host, port));
        }

        @Override
        public NetNatives.Datagram recvFrom(int max) throws NetNatives.NetFailure {
            while (true) {
                if (closed) throw NetNatives.closed();
                if (!inbox.await(receiveTimeoutMs)) throw NetNatives.timeout();
                NetNatives.Datagram d = inbox.poll();
                if (d != null) {
                    return d.data().length <= max ? d : new NetNatives.Datagram(Arrays.copyOf(d.data(), max), d.host(), d.port());
                }
                if (inbox.isEnded()) throw NetNatives.closed();
            }
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            synchronized (net) { net.udp.remove(Network.key(host, port), this); }
            inbox.end();
        }

        @Override
        public int port() { return port; }
    }

    // ==================== natives ====================

    static Network netOf(MezzeRecord args) {
        Object o = args.getField("net");
        if (o instanceof Network n) return n;
        throw new NativeTypeMismatchException("mock net performers expect 'net' to be a Network from mock_network");
    }

    /** `__native_mock_net_new {}` → `Network`. */
    @TruffleBoundary
    public static Object newNetwork(MezzeRecord args) {
        return new Network();
    }

    /** `__native_mock_net_connect { net, addr, config }` → `Result TcpConn NetError`. */
    @TruffleBoundary
    public static Object connect(MezzeRecord args) {
        Network net = netOf(args);
        MezzeRecord a = NetNatives.record(args, "addr");
        String host = NetNatives.hostOf(a);
        int port = NetNatives.portOf(a);
        NetNatives.TcpConfig cfg = NetNatives.TcpConfig.of(args);
        MockListener l = net.listener(host, port);
        if (l == null || l.closed) return NetNatives.err(NetNatives.refused(host, port));
        MockConn client = new MockConn(cfg.receiveTimeoutMs());
        MockConn server = new MockConn(l.cfg.receiveTimeoutMs());
        client.peer = server;
        server.peer = client;
        int clientPort = net.allocatePort(host, Map.of());
        l.pending.offer(new NetNatives.Accepted(server, host, clientPort));
        return NetNatives.ok(client);
    }

    /** `__native_mock_net_bind { net, addr, config }` → `Result TcpListener NetError`. */
    @TruffleBoundary
    public static Object bind(MezzeRecord args) {
        Network net = netOf(args);
        MezzeRecord a = NetNatives.record(args, "addr");
        String host = NetNatives.hostOf(a);
        int port = NetNatives.portOf(a);
        NetNatives.TcpConfig cfg = NetNatives.TcpConfig.of(args);
        synchronized (net) {
            if (port == 0) port = net.allocatePort(host, net.listeners);
            String key = Network.key(host, port);
            if (net.listeners.containsKey(key)) {
                return NetNatives.err(NetNatives.bindFailed(host, port, "address already in use on the mock network"));
            }
            MockListener l = new MockListener(net, host, port, cfg);
            net.listeners.put(key, l);
            return NetNatives.ok(l);
        }
    }

    /** `__native_mock_net_udp_bind { net, addr, receive_timeout_ms }` → `Result UdpSocket NetError`. */
    @TruffleBoundary
    public static Object udpBind(MezzeRecord args) {
        Network net = netOf(args);
        MezzeRecord a = NetNatives.record(args, "addr");
        String host = NetNatives.hostOf(a);
        int port = NetNatives.portOf(a);
        long timeout = Math.max(NetNatives.longOf(args, "receive_timeout_ms"), 0);
        synchronized (net) {
            if (port == 0) port = net.allocatePort(host, net.udp);
            String key = Network.key(host, port);
            if (net.udp.containsKey(key)) {
                return NetNatives.err(NetNatives.bindFailed(host, port, "address already in use on the mock network"));
            }
            MockUdp u = new MockUdp(net, host, port, timeout);
            net.udp.put(key, u);
            return NetNatives.ok(u);
        }
    }

    private MockNetNatives() {}
}
