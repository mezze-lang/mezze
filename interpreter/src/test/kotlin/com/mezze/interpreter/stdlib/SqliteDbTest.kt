package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `SqliteDb` — the first real driver, against a real database.
 *
 * Every test here runs on `:memory:`, so the whole `Db` surface is proven
 * against SQLite with no fixture, no server and no file. That is why SQLite is
 * the first driver: the alternative would have made these tests integration
 * tests, and integration tests are the ones that do not run.
 */
class SqliteDbTest {

    private val head = """
        use std::effects::db::{ Db, SqliteDb, DbValue, DbInt, DbStr, DbFloat, DbBool, DbNull, DbBytes,
                                Query, Rows, DbError, Unavailable, Rejected, Conflict,
                                sql, bind, bind_all, row_count, is_empty, col_index, value_at, no_rows }
        use std::collections::seq
        use std::int
        use std::str
    """.trimIndent()

    /**
     * Open `:memory:`, create one table, and run [body] inside the scope.
     *
     * [body] is indented line by line, not interpolated as-is: a `do` block is
     * INDENTATION-delimited, so a second line arriving at column 0 falls out of
     * the block and the parse error names the `let`, not the indentation.
     */
    private fun onMemory(body: String): Any? = MezzeTestHelper.run(
        head + "\n" +
            """
            let setup: {} -> Result { changed: Int } DbError <Db> = {} ->
              Db.apply { q = sql { text = "create table users (id integer primary key, name text, score real, active integer)" } }

            let main = {} -> perform SqliteDb { path = ":memory:" } in do
              let _ = setup {}
            """.trimIndent() + "\n" +
            body.trimIndent().prependIndent("  ") + "\n" +
            "main {}\n",
    )

    // ── A write, then a read ──

    @Test
    fun `a write reports how many rows it changed`() {
        val r = onMemory(
            """
            match Db.apply { q = sql { text = "insert into users (name, score, active) values (?, ?, ?)" }
                   .bind { v = DbStr { val = "ada" } }
                   .bind { v = DbFloat { val = 9.5 } }
                   .bind { v = DbInt { val = 1 } } } is
              Ok { val = o } -> o.changed
              Err { error = _ } -> -1
            """.trimIndent(),
        )
        assertEquals(1L, r, "one insert changed one row")
    }

    @Test
    fun `a value written as a parameter comes back as the same value`() {
        // The round trip is the whole claim of DbValue: a parameter is bound at
        // its own type, not spliced into text.
        val r = onMemory(
            """
            let _ = Db.apply { q = sql { text = "insert into users (name) values (?)" }
                     .bind { v = DbStr { val = "ada" } } }
            match Db.run { q = sql { text = "select name from users" } } is
              Ok { val = rows } -> match value_at { rows = rows, row = 0, col = 0 } is
                Some { val = DbStr { val = s } } -> s
                _ -> "wrong type"
              Err { error = _ } -> "failed"
            """.trimIndent(),
        )
        assertEquals("ada", r)
    }

    @Test
    fun `text containing SQL is data, not statement`() {
        // The injection test. If this were concatenated, `users` would be gone
        // and the count would not be 1.
        val r = onMemory(
            """
            let nasty = "'); drop table users; --"
            let _ = Db.apply { q = sql { text = "insert into users (name) values (?)" }
                     .bind { v = DbStr { val = nasty } } }
            match Db.run { q = sql { text = "select name from users" } } is
              Ok { val = rows } -> match value_at { rows = rows, row = 0, col = 0 } is
                Some { val = DbStr { val = s } } -> if s == nasty then 1 else 2
                _ -> 3
              Err { error = _ } -> 4
            """.trimIndent(),
        )
        assertEquals(1L, r, "the table survived and the text was stored verbatim")
    }

    @Test
    fun `a result set carries its column names once`() {
        val r = onMemory(
            """
            let _ = Db.apply { q = sql { text = "insert into users (name) values ('ada')" } }
            match Db.run { q = sql { text = "select id, name, score from users" } } is
              Ok { val = rows } -> str::join { strings = rows.cols, separator = "," }
              Err { error = _ } -> "failed"
            """.trimIndent(),
        )
        assertEquals("id,name,score", r)
    }

    @Test
    fun `a query matching nothing answers an empty result set, not an error`() {
        val r = onMemory(
            """
            match Db.run { q = sql { text = "select * from users where id = ?" }.bind { v = DbInt { val = 999 } } } is
              Ok { val = rows } -> if is_empty { rows = rows } then 0 else row_count { rows = rows }
              Err { error = _ } -> -1
            """.trimIndent(),
        )
        assertEquals(0L, r, "not found is an answer, not a failure")
    }

    @Test
    fun `several rows come back in order`() {
        val r = onMemory(
            """
            let _ = Db.apply { q = sql { text = "insert into users (id, name) values (1, 'a'), (2, 'b'), (3, 'c')" } }
            match Db.run { q = sql { text = "select id from users order by id" } } is
              Ok { val = rows } -> seq::fold { xs = rows.rows, init = 0, f = { acc, x } ->
                match seq::get { xs = x, i = 0 } is
                  Some { val = DbInt { val = n } } -> (acc * 10) + n
                  _ -> acc }
              Err { error = _ } -> -1
            """.trimIndent(),
        )
        assertEquals(123L, r)
    }

    // ── Column types ──

    @Test
    fun `an integer column decodes as DbInt and a real column as DbFloat`() {
        val r = onMemory(
            """
            let _ = Db.apply { q = sql { text = "insert into users (id, score) values (7, 2.5)" } }
            match Db.run { q = sql { text = "select id, score from users" } } is
              Ok { val = rows } -> match value_at { rows = rows, row = 0, col = 0 } is
                Some { val = DbInt { val = n } } -> match value_at { rows = rows, row = 0, col = 1 } is
                  Some { val = DbFloat { val = f } } -> if f == 2.5 then n else -2
                  _ -> -3
                _ -> -4
              Err { error = _ } -> -1
            """.trimIndent(),
        )
        assertEquals(7L, r)
    }

    @Test
    fun `a NULL column decodes as DbNull, not as a missing value`() {
        val r = onMemory(
            """
            let _ = Db.apply { q = sql { text = "insert into users (id, name) values (1, null)" } }
            match Db.run { q = sql { text = "select name from users" } } is
              Ok { val = rows } -> match value_at { rows = rows, row = 0, col = 0 } is
                Some { val = DbNull } -> 1
                Some { val = _ } -> 2
                None -> 3
              Err { error = _ } -> 4
            """.trimIndent(),
        )
        assertEquals(1L, r, "the cell is present and holds null — which is not the same as absent")
    }

    @Test
    fun `a bound null writes a NULL`() {
        val r = onMemory(
            """
            let _ = Db.apply { q = sql { text = "insert into users (id, name) values (1, ?)" }
                     .bind { v = DbNull } }
            match Db.run { q = sql { text = "select count(*) from users where name is null" } } is
              Ok { val = rows } -> match value_at { rows = rows, row = 0, col = 0 } is
                Some { val = DbInt { val = n } } -> n
                _ -> -2
              Err { error = _ } -> -1
            """.trimIndent(),
        )
        assertEquals(1L, r)
    }

    @Test
    fun `bytes round-trip through a blob`() {
        val r = MezzeTestHelper.run(
            """
            $head
            use std::collections::bytes
            let main = {} -> perform SqliteDb { path = ":memory:" } in do
              let _ = Db.apply { q = sql { text = "create table b (v blob)" } }
              let _ = Db.apply { q = sql { text = "insert into b (v) values (?)" }
                       .bind { v = DbBytes { val = bytes::from_str { s = "hi" } } } }
              match Db.run { q = sql { text = "select v from b" } } is
                Ok { val = rows } -> match value_at { rows = rows, row = 0, col = 0 } is
                  Some { val = DbBytes { val = b } } -> bytes::size { b = b }
                  _ -> -2
                Err { error = _ } -> -1
            main {}
            """.trimIndent(),
        )
        assertEquals(2L, r)
    }

    // ── Failure is a value ──

    @Test
    fun `a malformed statement answers Rejected and names the sql`() {
        val r = onMemory(
            """
            match Db.run { q = sql { text = "select * from no_such_table" } } is
              Ok { val = _ } -> "unexpectedly succeeded"
              Err { error = Rejected { sql = s, msg = _ } } -> s
              Err { error = _ } -> "wrong error kind"
            """.trimIndent(),
        )
        assertEquals(
            "select * from no_such_table", r,
            "Rejected carries the statement, because that is what the caller has to fix",
        )
    }

    @Test
    fun `a failure does not end the scope`() {
        // The connection survives a rejected statement: a Result is a value, so
        // the program keeps going and the next query still works.
        val r = onMemory(
            """
            let _ = Db.run { q = sql { text = "select * from no_such_table" } }
            match Db.run { q = sql { text = "select 1" } } is
              Ok { val = rows } -> row_count { rows = rows }
              Err { error = _ } -> -1
            """.trimIndent(),
        )
        assertEquals(1L, r)
    }

    @Test
    fun `a path that cannot be opened makes operations answer Unavailable`() {
        val r = MezzeTestHelper.run(
            """
            $head
            let main = {} -> perform SqliteDb { path = "/nonexistent-directory-xyz/db.sqlite" } in
              match Db.run { q = sql { text = "select 1" } } is
                Ok { val = _ } -> "unexpectedly succeeded"
                Err { error = Unavailable { msg = _ } } -> "unavailable"
                Err { error = _ } -> "wrong error kind"
            main {}
            """.trimIndent(),
        )
        assertEquals(
            "unavailable", r,
            "no connection was opened, so the first operation says so instead of the program crashing",
        )
    }

    // ── Transactions ──

    @Test
    fun `a transaction that succeeds commits`() {
        val r = onMemory(
            """
            let _ = Db.tx { body = {} -> do
              let _ = Db.apply { q = sql { text = "insert into users (id, name) values (1, 'a')" } }
              let _ = Db.apply { q = sql { text = "insert into users (id, name) values (2, 'b')" } }
              Ok { val = {} } }
            match Db.run { q = sql { text = "select count(*) from users" } } is
              Ok { val = rows } -> match value_at { rows = rows, row = 0, col = 0 } is
                Some { val = DbInt { val = n } } -> n
                _ -> -2
              Err { error = _ } -> -1
            """.trimIndent(),
        )
        assertEquals(2L, r)
    }

    @Test
    fun `a transaction whose body fails rolls back every write in it`() {
        // The claim of tx: all or nothing. The first insert succeeded; after the
        // body returns Err it must be gone.
        val r = onMemory(
            """
            let _ = Db.tx { body = {} -> do
              let _ = Db.apply { q = sql { text = "insert into users (id, name) values (1, 'a')" } }
              Err { error = Conflict { msg = "the body decided to stop" } } }
            match Db.run { q = sql { text = "select count(*) from users" } } is
              Ok { val = rows } -> match value_at { rows = rows, row = 0, col = 0 } is
                Some { val = DbInt { val = n } } -> n
                _ -> -2
              Err { error = _ } -> -1
            """.trimIndent(),
        )
        assertEquals(0L, r, "the insert inside the abandoned transaction is gone")
    }

    @Test
    fun `the body's error is what tx answers`() {
        val r = onMemory(
            """
            match Db.tx { body = {} -> Err { error = Conflict { msg = "mine" } } } is
              Ok { val = _ } -> "unexpectedly succeeded"
              Err { error = Conflict { msg = m } } -> m
              Err { error = _ } -> "the rollback replaced the body's error"
            """.trimIndent(),
        )
        assertEquals("mine", r)
    }

    @Test
    fun `a transaction's value passes through`() {
        val r = onMemory(
            """
            match Db.tx { body = {} -> Ok { val = 42 } } is
              Ok { val = v } -> v
              Err { error = _ } -> -1
            """.trimIndent(),
        )
        assertEquals(42L, r)
    }

    @Test
    fun `a nested transaction joins the outer one rather than committing early`() {
        // The inner tx neither commits nor rolls back. So when the OUTER body
        // fails, the inner body's write goes too.
        val r = onMemory(
            """
            let _ = Db.tx { body = {} -> do
              let _ = Db.tx { body = {} ->
                Db.apply { q = sql { text = "insert into users (id, name) values (1, 'inner')" } } }
              Err { error = Conflict { msg = "outer stops" } } }
            match Db.run { q = sql { text = "select count(*) from users" } } is
              Ok { val = rows } -> match value_at { rows = rows, row = 0, col = 0 } is
                Some { val = DbInt { val = n } } -> n
                _ -> -2
              Err { error = _ } -> -1
            """.trimIndent(),
        )
        assertEquals(0L, r, "the inner write did not survive the outer rollback")
    }

    @Test
    fun `a nested transaction that both bodies complete commits once`() {
        val r = onMemory(
            """
            let _ = Db.tx { body = {} -> do
              let _ = Db.tx { body = {} ->
                Db.apply { q = sql { text = "insert into users (id, name) values (1, 'inner')" } } }
              Db.apply { q = sql { text = "insert into users (id, name) values (2, 'outer')" } } }
            match Db.run { q = sql { text = "select count(*) from users" } } is
              Ok { val = rows } -> match value_at { rows = rows, row = 0, col = 0 } is
                Some { val = DbInt { val = n } } -> n
                _ -> -2
              Err { error = _ } -> -1
            """.trimIndent(),
        )
        assertEquals(2L, r)
    }

    // ── Scope ──

    @Test
    fun `two scopes on the same path get separate in-memory databases`() {
        // `:memory:` is per connection. Each scope opens its own, so a test
        // cannot see another's writes — the same isolation per-context native
        // state gives.
        val r = MezzeTestHelper.run(
            """
            $head
            let count_tables: {} -> Int <Db> = {} ->
              match Db.run { q = sql { text = "select count(*) from sqlite_master where name = 'leftover'" } } is
                Ok { val = rows } -> match value_at { rows = rows, row = 0, col = 0 } is
                  Some { val = DbInt { val = n } } -> n
                  _ -> -2
                Err { error = _ } -> -1

            let main = {} -> do
              let _ = perform SqliteDb { path = ":memory:" } in
                Db.apply { q = sql { text = "create table leftover (a int)" } }
              perform SqliteDb { path = ":memory:" } in count_tables {}
            main {}
            """.trimIndent(),
        )
        assertEquals(0L, r, "the second scope's database never heard of the first's table")
    }

    @Test
    fun `a nested scope shadows the outer one and the outer one survives it`() {
        val r = MezzeTestHelper.run(
            """
            $head
            let has_table: {} -> Int <Db> = {} ->
              match Db.run { q = sql { text = "select count(*) from sqlite_master where name = 'outer_t'" } } is
                Ok { val = rows } -> match value_at { rows = rows, row = 0, col = 0 } is
                  Some { val = DbInt { val = n } } -> n
                  _ -> -2
                Err { error = _ } -> -1

            let main = {} -> perform SqliteDb { path = ":memory:" } in do
              let _ = Db.apply { q = sql { text = "create table outer_t (a int)" } }
              ## The inner scope is a different database, so it does not see it.
              let inner = perform SqliteDb { path = ":memory:" } in has_table {}
              ## And the outer connection is still open after the inner closed.
              let outer = has_table {}
              (inner * 10) + outer
            main {}
            """.trimIndent(),
        )
        assertEquals(1L, r, "inner saw 0, outer still sees 1 — the inner close did not take the outer connection")
    }

    @Test
    fun `a file-backed database persists between scopes`(@org.junit.jupiter.api.io.TempDir dir: java.nio.file.Path) {
        // The counterpart to the `:memory:` test: with a real path the writes
        // are still there when the next scope opens it.
        val path = dir.resolve("t.sqlite").toString()
        val r = MezzeTestHelper.run(
            """
            $head
            let main = {} -> do
              let _ = perform SqliteDb { path = "$path" } in do
                let _ = Db.apply { q = sql { text = "create table kept (a int)" } }
                Db.apply { q = sql { text = "insert into kept (a) values (7)" } }
              perform SqliteDb { path = "$path" } in
                match Db.run { q = sql { text = "select a from kept" } } is
                  Ok { val = rows } -> match value_at { rows = rows, row = 0, col = 0 } is
                    Some { val = DbInt { val = n } } -> n
                    _ -> -2
                  Err { error = _ } -> -1
            main {}
            """.trimIndent(),
        )
        assertEquals(7L, r)
        assertTrue(java.nio.file.Files.exists(java.nio.file.Path.of(path)), "the file was actually created")
    }

    // ── A server handler's thread ──
    //
    // The case the whole effect exists for, and the one that was broken until
    // the scope model stopped being purely thread-local: a route querying a
    // database runs on a thread the server owns, which inherits the handler
    // CHAIN but not the thread-local state the chain was built beside.

    private val serverHead = """
        use std::effects::http::{ Request, Response, HttpClient, SimpleHttpClient, request, format_error, body_str }
        use std::effects::http_server::{ HttpServer, HelidonHttpServerWith, ServerConfig }
        use std::collections::map
        use std::collections::bytes

        let reply: { code: Int, text: Str, url: Str } -> Response = { code, text, url } -> {
          status = code, headers = map::empty {}, body = bytes::from_str { s = text }, url = url,
          chunks = None,
        }
        let get: { port: Int, path: Str } -> Str <HttpClient> = { port, path } ->
          match request { url = "http://127.0.0.1:$(int::to_str { n = port })$(path)" }.send {} is
            Ok { val = resp } -> match body_str { resp = resp } is
              Ok { val = s } -> s
              Err { error = _ } -> "undecodable"
            Err { error = e } -> format_error { err = e }
    """.trimIndent()

    @Test
    fun `a handler on the server's own thread reaches the scope's database`() {
        val r = MezzeTestHelper.run(
            head + "\n" + serverHead + "\n" +
                """
                let handler: { req: Request } -> Response <Db> = { req } ->
                  match Db.run { q = sql { text = "select count(*) from t" } } is
                    Ok { val = rows } -> match value_at { rows = rows, row = 0, col = 0 } is
                      Some { val = DbInt { val = n } } -> reply { code = 200, text = int::to_str { n = n }, url = req.url }
                      _ -> reply { code = 200, text = "wrong cell", url = req.url }
                    Err { error = Unavailable { msg = m } } -> reply { code = 200, text = "unavailable", url = req.url }
                    Err { error = _ } -> reply { code = 200, text = "db error", url = req.url }

                let go: {} -> Str <*> = {} ->
                  perform SqliteDb { path = ":memory:" } in
                    perform HelidonHttpServerWith { config = { port = 0, host = "127.0.0.1", workers = 4 } } in
                      perform SimpleHttpClient in do
                        let _ = Db.apply { q = sql { text = "create table t (a int)" } }
                        let _ = Db.apply { q = sql { text = "insert into t values (1), (2)" } }
                        let h = HttpServer.serve { handler = handler }
                        let out = get { port = h.port, path = "/" }
                        h.stop {}
                        out
                go {}
                """.trimIndent(),
        )
        assertEquals("2", r, "the handler queried the database its enclosing scope opened")
    }

    @Test
    fun `a handler writes, and the next request sees the write`() {
        // A write from a server thread is committed, not stranded in some
        // thread's view: the second request is a different Helidon thread as
        // often as not, and it reads the first one's insert.
        val r = MezzeTestHelper.run(
            head + "\n" + serverHead + "\n" +
                """
                let handler: { req: Request } -> Response <Db> = { req } -> do
                  let _ = Db.apply { q = sql { text = "insert into t values (1)" } }
                  match Db.run { q = sql { text = "select count(*) from t" } } is
                    Ok { val = rows } -> match value_at { rows = rows, row = 0, col = 0 } is
                      Some { val = DbInt { val = n } } -> reply { code = 200, text = int::to_str { n = n }, url = req.url }
                      _ -> reply { code = 200, text = "wrong cell", url = req.url }
                    Err { error = _ } -> reply { code = 200, text = "db error", url = req.url }

                let go: {} -> Str <*> = {} ->
                  perform SqliteDb { path = ":memory:" } in
                    perform HelidonHttpServerWith { config = { port = 0, host = "127.0.0.1", workers = 4 } } in
                      perform SimpleHttpClient in do
                        let _ = Db.apply { q = sql { text = "create table t (a int)" } }
                        let h = HttpServer.serve { handler = handler }
                        let first = get { port = h.port, path = "/" }
                        let second = get { port = h.port, path = "/" }
                        let third = get { port = h.port, path = "/" }
                        h.stop {}
                        "$(first),$(second),$(third)"
                go {}
                """.trimIndent(),
        )
        assertEquals("1,2,3", r, "each request saw every write before it")
    }

    @Test
    fun `a transaction on a handler thread is not visible until it commits`() {
        // The reason a transaction holds the scope's lock. The handler reads the
        // count inside its own transaction after inserting, so it sees its write;
        // the request after it sees the committed total.
        val r = MezzeTestHelper.run(
            head + "\n" + serverHead + "\n" +
                """
                let handler: { req: Request } -> Response <Db> = { req } ->
                  match Db.tx { body = {} -> do
                    let _ = Db.apply { q = sql { text = "insert into t values (1)" } }
                    Db.run { q = sql { text = "select count(*) from t" } } } is
                    Ok { val = rows } -> match value_at { rows = rows, row = 0, col = 0 } is
                      Some { val = DbInt { val = n } } -> reply { code = 200, text = int::to_str { n = n }, url = req.url }
                      _ -> reply { code = 200, text = "wrong cell", url = req.url }
                    Err { error = _ } -> reply { code = 200, text = "db error", url = req.url }

                let go: {} -> Str <*> = {} ->
                  perform SqliteDb { path = ":memory:" } in
                    perform HelidonHttpServerWith { config = { port = 0, host = "127.0.0.1", workers = 4 } } in
                      perform SimpleHttpClient in do
                        let _ = Db.apply { q = sql { text = "create table t (a int)" } }
                        let h = HttpServer.serve { handler = handler }
                        let first = get { port = h.port, path = "/" }
                        let second = get { port = h.port, path = "/" }
                        h.stop {}
                        "$(first),$(second)"
                go {}
                """.trimIndent(),
        )
        assertEquals(
            "1,2", r,
            "a query inside the transaction saw the transaction's own write, and the commit held",
        )
    }

    @Test
    fun `concurrent transactions on handler threads do not interleave`() {
        // THE test for the scope lock. Four requests go out at once, each
        // handler running a transaction that inserts and then counts. If two
        // transactions could run on the one connection at once they would see
        // each other's uncommitted rows, so the four answers would collide
        // instead of being 1, 2, 3 and 4 in some order — and SQLite would be as
        // likely to reject the second BEGIN outright.
        val r = MezzeTestHelper.run(
            head + "\n" + serverHead + "\n" +
                """
                use std::effects::parallel::{ Parallel, LoomParallel }

                let handler: { req: Request } -> Response <Db> = { req } ->
                  match Db.tx { body = {} -> do
                    let _ = Db.apply { q = sql { text = "insert into t values (1)" } }
                    Db.run { q = sql { text = "select count(*) from t" } } } is
                    Ok { val = rows } -> match value_at { rows = rows, row = 0, col = 0 } is
                      Some { val = DbInt { val = n } } -> reply { code = 200, text = int::to_str { n = n }, url = req.url }
                      _ -> reply { code = 200, text = "wrong cell", url = req.url }
                    Err { error = _ } -> reply { code = 200, text = "db error", url = req.url }

                let go: {} -> Str <*> = {} ->
                  perform SqliteDb { path = ":memory:" } in
                    perform LoomParallel in
                      perform HelidonHttpServerWith { config = { port = 0, host = "127.0.0.1", workers = 8 } } in
                        perform SimpleHttpClient in do
                          let _ = Db.apply { q = sql { text = "create table t (a int)" } }
                          let h = HttpServer.serve { handler = handler }
                          let both = Parallel.join {
                            l = {} -> Parallel.join {
                              l = {} -> get { port = h.port, path = "/" },
                              r = {} -> get { port = h.port, path = "/" } },
                            r = {} -> Parallel.join {
                              l = {} -> get { port = h.port, path = "/" },
                              r = {} -> get { port = h.port, path = "/" } } }
                          h.stop {}
                          ## Sorting makes the answer independent of which
                          ## request won, which is the only part that is not
                          ## determined.
                          str::join { strings = seq::sort { xs = [both.l.l, both.l.r, both.r.l, both.r.r] }, separator = "," }
                go {}
                """.trimIndent(),
        )
        assertEquals(
            "1,2,3,4", r,
            "each transaction had the connection to itself, so each saw exactly the rows committed before it plus its own",
        )
    }

    @Test
    fun `a thread that entered no scope while two are open is told so, not given one`() {
        // No fallback guessing. Two scopes are open on the main thread; the
        // handler's thread entered neither, so there is no one database the
        // query could mean, and saying which it picked is better than picking.
        val r = MezzeTestHelper.run(
            head + "\n" + serverHead + "\n" +
                """
                let handler: { req: Request } -> Response <Db> = { req } ->
                  match Db.run { q = sql { text = "select count(*) from t" } } is
                    Ok { val = _ } -> reply { code = 200, text = "answered anyway", url = req.url }
                    Err { error = Unavailable { msg = _ } } -> reply { code = 200, text = "ambiguous", url = req.url }
                    Err { error = _ } -> reply { code = 200, text = "wrong error kind", url = req.url }

                let go: {} -> Str <*> = {} ->
                  perform SqliteDb { path = ":memory:" } in do
                    ## The outer scope is used, so it is a real second scope and
                    ## not something the typechecker folds into the inner one.
                    let _ = Db.apply { q = sql { text = "create table t (a int)" } }
                    perform SqliteDb { path = ":memory:" } in
                      perform HelidonHttpServerWith { config = { port = 0, host = "127.0.0.1", workers = 4 } } in
                        perform SimpleHttpClient in do
                          let h = HttpServer.serve { handler = handler }
                          let out = get { port = h.port, path = "/" }
                          h.stop {}
                          out
                go {}
                """.trimIndent(),
        )
        assertEquals("ambiguous", r)
    }

    // ── The codec, against real rows ──

    @Test
    fun `a real result set decodes into records through RowC`() {
        // RowCodecTest proves the codec against hand-built Rows. This proves the
        // shape the DRIVER produces is the shape the codec reads — the two were
        // written separately and this is the only test that joins them.
        val r = MezzeTestHelper.run(
            """
            $head
            use std::codec::core::{ Decode, DecodeError }
            use std::effects::db::{ RowC, row_c }

            let main = {} -> perform SqliteDb { path = ":memory:" } in do
              let _ = Db.apply { q = sql { text = "create table users (id integer, name text)" } }
              let _ = Db.apply { q = sql { text = "insert into users values (1, 'ada'), (2, 'grace')" } }
              match Db.run { q = sql { text = "select id, name from users order by id" } } is
                Ok { val = rows } -> do
                  let decoded: Result [{ id: Int, name: Str }] DecodeError = rows.decode { config = row_c }
                  match decoded is
                    Ok { val = us } -> str::join { strings = seq::map { xs = us, f = { x } -> x.name }, separator = "," }
                    Err { error = _ } -> "did not decode"
                Err { error = _ } -> "query failed"
            main {}
            """.trimIndent(),
        )
        assertEquals("ada,grace", r)
    }
}
