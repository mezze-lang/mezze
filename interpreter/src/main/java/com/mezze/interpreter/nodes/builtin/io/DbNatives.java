package com.mezze.interpreter.nodes.builtin.io;

import com.mezze.interpreter.runtime.MezzeContext;
import com.mezze.interpreter.runtime.NativeTypeMismatchException;
import com.mezze.interpreter.values.MezzeRecord;
import com.mezze.interpreter.values.MezzeVariant;
import com.mezze.datastructures.seq.MezzeSeq;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.interop.TruffleObject;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * The DATABASE natives — the driver half of `std::effects::db`.
 *
 * SQLite first, through JDBC. SQLite because it needs no server, which makes the
 * first real driver provable in a unit test against `:memory:`; JDBC because one
 * surface reaches every database, so the second driver is a connection string
 * rather than a rewrite.
 *
 * <h2>Everything crossing into Mezze is built here, by hand</h2>
 *
 * A `Rows` is `{ cols: [Str], rows: [[DbValue]] }` and `DbValue` is a closed
 * variant, so this file builds those shapes explicitly. The alternative — handing
 * back a JDBC `ResultSet` as an opaque handle and reading it from Mezze — would
 * put a cursor whose validity depends on statement lifetime into guest hands, and
 * the guest has no way to honour that.
 *
 * <h2>A failure is a value, not an exception</h2>
 *
 * Every operation answers `Result _ DbError`, and the mapping from
 * `SQLException` to a `DbError` constructor is the one judgement this file makes:
 * it is by WHAT THE CALLER CAN DO (retry the connection, fix the query, retry the
 * transaction), not by the driver's error code, because a code is a fact about
 * SQLite and a `DbError` is advice to the program. `json::to_bytes` is the
 * cautionary tale for the other approach: it swallowed failures into an empty
 * answer and a handler replied 200 with a zero-byte body.
 */
public final class DbNatives {

    private DbNatives() {
    }

    /** An open connection, opaque to the guest. */
    public static final class DbHandle implements TruffleObject, AutoCloseable {
        private final Connection conn;
        private volatile boolean closed;

        DbHandle(Connection conn) {
            this.conn = conn;
        }

        /**
         * Registered with the context, so a program that never closes its database
         * still releases the file handle when the context goes. Eleven leaks of
         * exactly this shape were fixed in this codebase; a connection is the same
         * problem as a listening socket.
         */
        @Override
        public void close() {
            if (closed) return;
            closed = true;
            try {
                conn.close();
            } catch (SQLException ignored) {
                // Already gone is the state we wanted.
            }
        }
    }

    // ==================== Variant construction ====================

    /**
     * `getOrCreateTag` and not `computeVariantTag`: both answer the same int, but
     * only the registry records the REVERSE mapping, and without it
     * `getConstructorName` on a variant this file built renders as
     * `Unknown(<tag>)` — which is what `inspect` and an error message print.
     */
    private static int tag(String constructorName) {
        return MezzeVariant.TagRegistry.getGlobal().getOrCreateTag(constructorName);
    }

    private static MezzeVariant noPayload(String ctor) {
        return MezzeVariant.noPayload(MezzeVariant.TagRegistry.getGlobal(), tag(ctor));
    }

    private static MezzeVariant withVal(String ctor, Object value) {
        return MezzeVariant.singlePayloadFused(
            MezzeVariant.TagRegistry.getGlobal(), tag(ctor), value, "val");
    }

    /** `DbError` by what the caller can DO about it — see the class note. */
    private static MezzeVariant dbError(SQLException e, String sql) {
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        String state = e.getSQLState() == null ? "" : e.getSQLState();
        // SQLite's codes: 5 = BUSY, 6 = LOCKED — the transaction can be retried.
        if (e.getErrorCode() == 5 || e.getErrorCode() == 6 || state.startsWith("40")) {
            return MezzeVariant.singlePayload(
                MezzeVariant.TagRegistry.getGlobal(), tag("Conflict"),
                MezzeRecord.fromFields(new String[]{"msg"}, new Object[]{msg}));
        }
        // 08xxx is "connection exception" in SQLSTATE; a file SQLite cannot open
        // reports no usable state, so the message is all there is.
        if (state.startsWith("08") || msg.contains("unable to open database")) {
            return unavailable(msg);
        }
        // Everything else is the statement's fault and will not improve on retry.
        return MezzeVariant.singlePayload(
            MezzeVariant.TagRegistry.getGlobal(), tag("Rejected"),
            MezzeRecord.fromFields(new String[]{"sql", "msg"}, new Object[]{sql, msg}));
    }

    private static MezzeVariant unavailable(String msg) {
        return MezzeVariant.singlePayload(
            MezzeVariant.TagRegistry.getGlobal(), tag("Unavailable"),
            MezzeRecord.fromFields(new String[]{"msg"}, new Object[]{msg}));
    }

    /** One column value as a `DbValue`. */
    private static Object dbValue(ResultSet rs, int col, int sqlType) throws SQLException {
        Object raw = rs.getObject(col);
        if (raw == null) return noPayload("DbNull");
        return switch (sqlType) {
            case java.sql.Types.INTEGER, java.sql.Types.BIGINT, java.sql.Types.SMALLINT,
                 java.sql.Types.TINYINT -> withVal("DbInt", rs.getLong(col));
            case java.sql.Types.FLOAT, java.sql.Types.REAL, java.sql.Types.DOUBLE,
                 java.sql.Types.NUMERIC, java.sql.Types.DECIMAL -> withVal("DbFloat", rs.getDouble(col));
            case java.sql.Types.BOOLEAN, java.sql.Types.BIT -> withVal("DbBool", rs.getBoolean(col));
            case java.sql.Types.BLOB, java.sql.Types.BINARY, java.sql.Types.VARBINARY,
                 java.sql.Types.LONGVARBINARY -> withVal("DbBytes",
                     new BytesNatives.MezzeBytes(rs.getBytes(col)));
            // SQLite is dynamically typed: a column declared INTEGER can hand back
            // a string. Fall back on the VALUE's Java type rather than the column's
            // declared one, which is why this is not an error case.
            default -> {
                if (raw instanceof Long l) yield withVal("DbInt", l);
                else if (raw instanceof Integer i) yield withVal("DbInt", i.longValue());
                else if (raw instanceof Double d) yield withVal("DbFloat", d);
                else if (raw instanceof Float f) yield withVal("DbFloat", (double) f);
                else if (raw instanceof Boolean b) yield withVal("DbBool", b);
                else if (raw instanceof byte[] bs) yield withVal("DbBytes", new BytesNatives.MezzeBytes(bs));
                else yield withVal("DbStr", String.valueOf(raw));
            }
        };
    }

    /** Bind one `DbValue` to a placeholder. */
    private static void bind(PreparedStatement ps, int index, Object value) throws SQLException {
        if (!(value instanceof MezzeVariant v)) {
            throw new NativeTypeMismatchException(
                "Expected a DbValue parameter, got: "
                + (value == null ? "null" : value.getClass().getSimpleName()));
        }
        String ctor = v.getConstructorName();
        Object payload = "DbNull".equals(ctor) ? null : v.getPayloadFieldByName("val");
        switch (ctor) {
            case "DbNull" -> ps.setNull(index, java.sql.Types.NULL);
            case "DbInt" -> ps.setLong(index, ((Number) payload).longValue());
            case "DbFloat" -> ps.setDouble(index, ((Number) payload).doubleValue());
            case "DbBool" -> ps.setBoolean(index, (Boolean) payload);
            case "DbStr" -> ps.setString(index, String.valueOf(payload));
            case "DbBytes" -> {
                if (payload instanceof BytesNatives.MezzeBytes mb) ps.setBytes(index, mb.getData());
                else throw new NativeTypeMismatchException("DbBytes did not carry bytes");
            }
            default -> throw new NativeTypeMismatchException(
                "Not a DbValue constructor: '" + ctor + "'");
        }
    }

    private static void bindAll(PreparedStatement ps, Object params) throws SQLException {
        if (params instanceof MezzeSeq seq) {
            int n = (int) seq.size();
            for (int i = 0; i < n; i++) bind(ps, i + 1, seq.get(i));
            return;
        }
        if (params instanceof Object[] arr) {
            for (int i = 0; i < arr.length; i++) bind(ps, i + 1, arr[i]);
            return;
        }
        throw new NativeTypeMismatchException(
            "Expected 'params' to be a seq of DbValue, got: "
            + (params == null ? "null" : params.getClass().getSimpleName()));
    }

    // ==================== Scopes ====================

    /**
     * How long an operation waits for the connection before giving up.
     *
     * A single-connection database serializes, so waiting is normal and a wait
     * is not a failure. But waiting FOREVER is: a transaction whose body never
     * finishes would pin every other caller, and on a server that means a thread
     * pool that never recovers. Ten seconds is the same figure
     * `HttpServerNatives.ADMIT_WAIT_MS` uses for admission, for the same reason:
     * long enough that real contention is invisible, short enough that a stuck
     * transaction reports `Timeout` instead of hanging the process.
     */
    private static final long LOCK_WAIT_MS = 10_000;

    /**
     * One open database — the thing a `perform SqliteDb … in` scope is.
     *
     * ONE CONNECTION, shared by every thread in the scope, because that is what
     * the scope MEANS. A connection per thread was the first design and it is
     * wrong for the case that matters: `:memory:` is per connection, so a
     * handler thread opening its own would query a different, empty database and
     * answer 0 rows with no error at all. Better to serialize than to answer
     * confidently from the wrong database.
     *
     * Serializing is also less costly than it sounds — SQLite serializes writes
     * at the file level regardless — and the fix for the cost is a POOL of
     * scopes (W-D4), not a connection per thread.
     *
     * A `ReentrantLock` and not `synchronized` because it gives all three things
     * this needs from one object: cross-thread exclusion, SAME-thread
     * reentrancy (so a query inside a transaction, and a nested `tx`, are not
     * self-deadlocks), and a bounded wait.
     */
    static final class Scope {
        private final String path;
        private final DbHandle handle;
        private final java.util.concurrent.locks.ReentrantLock lock =
            new java.util.concurrent.locks.ReentrantLock();

        Scope(String path, DbHandle handle) {
            this.path = path;
            this.handle = handle;
        }

        /** Take the connection, or `null` if the wait ran out. */
        private boolean acquire() {
            try {
                return lock.tryLock(LOCK_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        private void release() {
            if (lock.isHeldByCurrentThread()) lock.unlock();
        }
    }

    /**
     * The live scopes of one context.
     *
     * PER CONTEXT, because static state in a native crosses Contexts on one
     * Engine — three bugs of that exact shape were fixed in this tree, and a
     * database file shared between two unrelated programs is the worst of them.
     *
     * TWO VIEWS of the same scopes, and both are needed:
     *
     *   `entered`  per thread, innermost last. This is what `perform` nesting
     *              means on the thread that wrote it, and it is what makes two
     *              threads each opening their own scope independent.
     *
     *   `live`     every open scope, in open order. This is what a thread that
     *              never ran a `perform` reads — a server handler, which runs on
     *              a thread Helidon owns and inherits the handler CHAIN but not
     *              any thread-local state the chain was built beside.
     *
     * Without `live`, the one case the whole effect exists for — a route
     * querying a database — answered `Unavailable` on every request.
     */
    static final class DbScopes implements AutoCloseable {
        private final ThreadLocal<ArrayList<Scope>> entered =
            ThreadLocal.withInitial(ArrayList::new);
        private final List<Scope> live =
            java.util.Collections.synchronizedList(new ArrayList<>());

        void push(Scope s) {
            entered.get().add(s);
            live.add(s);
        }

        Scope pop() {
            ArrayList<Scope> stack = entered.get();
            if (stack.isEmpty()) return null;
            Scope s = stack.remove(stack.size() - 1);
            live.remove(s);
            return s;
        }

        @Override
        public void close() {
            synchronized (live) {
                for (Scope s : live) s.handle.close();
                live.clear();
            }
        }
    }

    @TruffleBoundary
    private static DbScopes scopes() {
        return MezzeContext.get(null).nativeState(DbScopes.class, DbScopes::new);
    }

    /**
     * Which database an operation on this thread runs against.
     *
     * The lexically enclosing scope, if this thread entered one. Otherwise the
     * single live scope, which is the server case. If several are live and this
     * thread entered none, that is NOT guessed at: `null` comes back and the
     * caller reports it, naming the ambiguity. Picking one would mean a request
     * silently reading whichever database happened to be opened first.
     */
    private static Scope current() {
        DbScopes all = scopes();
        ArrayList<Scope> stack = all.entered.get();
        if (!stack.isEmpty()) return stack.get(stack.size() - 1);
        synchronized (all.live) {
            return all.live.size() == 1 ? all.live.get(0) : null;
        }
    }

    /** Why `current()` answered nothing — the count is the whole diagnosis. */
    private static MezzeVariant noScope() {
        int n;
        DbScopes all = scopes();
        synchronized (all.live) {
            n = all.live.size();
        }
        if (n == 0) {
            return MezzeVariant.err(unavailable(
                "no database is open here — a Db operation ran outside the scope "
                + "of the performer that opens the connection"));
        }
        return MezzeVariant.err(unavailable(
            "this thread did not enter a database scope, and " + n + " are open, "
            + "so there is no one database this operation could mean — open the "
            + "scope on this thread, or arrange for only one to be open"));
    }

    private static MezzeVariant timedOut() {
        return MezzeVariant.err(MezzeVariant.singlePayload(
            MezzeVariant.TagRegistry.getGlobal(), tag("Timeout"),
            MezzeRecord.fromFields(new String[]{"ms"}, new Object[]{LOCK_WAIT_MS})));
    }

    // ==================== Natives ====================

    /**
     * Open a SQLite database and push it onto this thread's scope stack.
     * `":memory:"` gives a private in-process one, which is what a test wants.
     *
     * The handle NEVER crosses into Mezze. A guest that held one could use it
     * after its scope closed, and there is no way for the guest to know it had;
     * keeping it here means the only reachable connection is the open one.
     */
    @TruffleBoundary
    public static Object dbSqliteScopeOpen(MezzeRecord args) {
        String path = getString(args, "path");
        try {
            Connection conn = DriverManager.getConnection("jdbc:sqlite:" + path);
            scopes().push(new Scope(path, new DbHandle(conn)));
            return MezzeVariant.ok(MezzeRecord.empty());
        } catch (SQLException e) {
            return MezzeVariant.err(dbError(e, ""));
        }
    }

    /**
     * Pop this thread's innermost scope and close its connection.
     *
     * Idempotent, and the context's dispose hook closes anything still open, so
     * a program that fails inside a scope does not leak the file handle.
     */
    @TruffleBoundary
    public static MezzeRecord dbScopeClose(MezzeRecord args) {
        Scope s = scopes().pop();
        if (s != null) s.handle.close();
        return MezzeRecord.empty();
    }

    /** Run a query and build the whole result set. */
    @TruffleBoundary
    public static Object dbQuery(MezzeRecord args) {
        Scope scope = current();
        if (scope == null) return noScope();
        String sql = getString(args, "sql");
        DbHandle h = scope.handle;
        if (h.closed) return MezzeVariant.err(unavailable("this database is already closed"));
        if (!scope.acquire()) return timedOut();
        try (PreparedStatement ps = h.conn.prepareStatement(sql)) {
            bindAll(ps, getField(args, "params"));
            try (ResultSet rs = ps.executeQuery()) {
                return MezzeVariant.ok(readRows(rs));
            }
        } catch (SQLException e) {
            return MezzeVariant.err(dbError(e, sql));
        } finally {
            scope.release();
        }
    }

    /**
     * A whole `ResultSet` as a `Rows`.
     *
     * The column types are read ONCE from the metadata rather than per cell: the
     * declared type cannot change between rows, and `getColumnType` is a lookup
     * a driver is free to make expensive.
     */
    private static MezzeRecord readRows(ResultSet rs) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int width = meta.getColumnCount();
        Object[] cols = new Object[width];
        int[] types = new int[width];
        for (int c = 1; c <= width; c++) {
            cols[c - 1] = meta.getColumnLabel(c);
            types[c - 1] = meta.getColumnType(c);
        }
        List<Object> rows = new ArrayList<>();
        while (rs.next()) {
            Object[] cells = new Object[width];
            for (int c = 1; c <= width; c++) cells[c - 1] = dbValue(rs, c, types[c - 1]);
            rows.add(MezzeSeq.fromArray(cells));
        }
        return MezzeRecord.fromFields(
            new String[]{"cols", "rows"},
            new Object[]{MezzeSeq.fromArray(cols), MezzeSeq.fromArray(rows.toArray())});
    }

    /** Run a statement and report how many rows it changed. */
    @TruffleBoundary
    public static Object dbExec(MezzeRecord args) {
        Scope scope = current();
        if (scope == null) return noScope();
        String sql = getString(args, "sql");
        DbHandle h = scope.handle;
        if (h.closed) return MezzeVariant.err(unavailable("this database is already closed"));
        if (!scope.acquire()) return timedOut();
        try (PreparedStatement ps = h.conn.prepareStatement(sql)) {
            bindAll(ps, getField(args, "params"));
            int changed = ps.executeUpdate();
            return MezzeVariant.ok(MezzeRecord.fromFields(
                new String[]{"changed"}, new Object[]{(long) changed}));
        } catch (SQLException e) {
            return MezzeVariant.err(dbError(e, sql));
        } finally {
            scope.release();
        }
    }

    /**
     * Begin / commit / roll back, as three natives rather than one that takes the
     * body: the body is GUEST code, and a native that called it would have to run
     * guest code from a `@TruffleBoundary`. The stdlib's `tx` sequences these
     * instead, which also keeps the body's effect row visible to the typechecker.
     *
     * Nesting answers `{ nested = True }` and opens nothing. SQLite has
     * SAVEPOINT, but a nested `tx` that could roll back independently would mean
     * an inner failure leaving an outer transaction half-applied, and the
     * effect's promise is that a `tx` body commits together or not at all. So the
     * inner `tx` joins the outer one, and the stdlib's `tx` reads this flag to
     * know not to commit what it did not begin.
     *
     * A transaction HOLDS the scope's lock from `begin` until `commit` or
     * `rollback`, which is what makes it a transaction with more than one thread
     * in the scope: another thread's query would otherwise run inside it and see
     * writes that may yet be rolled back. Nesting is read off the lock's hold
     * count rather than the connection's autocommit flag — the flag is shared by
     * every thread, so it said "nested" to a second thread that was merely
     * concurrent.
     */
    @TruffleBoundary
    public static Object dbBegin(MezzeRecord args) {
        Scope scope = current();
        if (scope == null) return noScope();
        if (!scope.acquire()) return timedOut();
        // Held more than once means this thread is already inside a transaction:
        // release the extra hold straight away, since no inner commit will.
        if (scope.lock.getHoldCount() > 1) {
            scope.release();
            return MezzeVariant.ok(MezzeRecord.fromFields(
                new String[]{"nested"}, new Object[]{true}));
        }
        try {
            scope.handle.conn.setAutoCommit(false);
            return MezzeVariant.ok(MezzeRecord.fromFields(
                new String[]{"nested"}, new Object[]{false}));
        } catch (SQLException e) {
            scope.release();
            return MezzeVariant.err(dbError(e, "BEGIN"));
        }
    }

    @TruffleBoundary
    public static Object dbCommit(MezzeRecord args) {
        Scope scope = current();
        if (scope == null) return noScope();
        try {
            scope.handle.conn.commit();
            scope.handle.conn.setAutoCommit(true);
            return MezzeVariant.ok(MezzeRecord.empty());
        } catch (SQLException e) {
            return MezzeVariant.err(dbError(e, "COMMIT"));
        } finally {
            scope.release();
        }
    }

    /**
     * Roll back, and report nothing.
     *
     * Answers `{}` rather than a `Result`: this runs when the body ALREADY
     * failed, and the caller is about to return that failure. A rollback error
     * reported here would replace the reason the transaction was abandoned with
     * a fact about the abandoning, which is strictly less useful.
     */
    @TruffleBoundary
    public static MezzeRecord dbRollback(MezzeRecord args) {
        Scope scope = current();
        if (scope == null) return MezzeRecord.empty();
        try {
            scope.handle.conn.rollback();
            scope.handle.conn.setAutoCommit(true);
        } catch (SQLException ignored) {
            // See above.
        } finally {
            scope.release();
        }
        return MezzeRecord.empty();
    }

    // ==================== Argument helpers ====================

    private static Object getField(MezzeRecord args, String fieldName) {
        Object value = args.getField(fieldName);
        if (value == null) {
            throw new NativeTypeMismatchException("Missing field '" + fieldName + "'");
        }
        return value;
    }

    private static String getString(MezzeRecord args, String fieldName) {
        Object value = getField(args, fieldName);
        if (value instanceof String s) return s;
        throw new NativeTypeMismatchException("Expected Str for field '" + fieldName + "'");
    }
}
