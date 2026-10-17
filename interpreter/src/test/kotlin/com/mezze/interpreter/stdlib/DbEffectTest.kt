package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * The `Db` effect: three operations, and a query that is a plain value.
 *
 * The point of these tests is that almost none of them need a database. A
 * `Query` is built and inspected by pure code, and `MockDb` answers from a
 * table — the same seam that makes an HTTP handler callable with no socket.
 */
class DbEffectTest {

    private val head = """
        use std::effects::db::{ Db, MockDb, MockDbWith, FailingDb, DbValue, DbInt, DbStr, DbBool, DbNull,
                                Query, Rows, DbError, Unavailable, Rejected,
                                sql, bind, bind_all, row_count, is_empty, col_index, value_at, no_rows }
        use std::collections::seq
        use std::int
    """.trimIndent()

    // ── A query is a value ──

    @Test
    fun `a query with no parameters`() {
        val r = MezzeTestHelper.run(
            """
            $head
            let main = {} -> seq::size { xs = sql { text = "select 1" }.params }
            main {}
            """.trimIndent(),
        )
        assertEquals(0L, r)
    }

    @Test
    fun `bind appends parameters in order`() {
        val r = MezzeTestHelper.run(
            """
            $head
            let main = {} -> do
              let q = sql { text = "select * from users where id = ? and active = ?" }
                .bind { v = DbInt { val = 7 } }
                .bind { v = DbBool { val = True } }
              match seq::get { xs = q.params, i = 0 } is
                Some { val = DbInt { val = n } } -> n
                _ -> -1
            main {}
            """.trimIndent(),
        )
        assertEquals(7L, r, "the first bound parameter should still be first")
    }

    @Test
    fun `a parameter carrying SQL text stays a VALUE`() {
        // The reason DbValue exists: this reaches the driver as a string to
        // bind, not as text spliced into the statement.
        val r = MezzeTestHelper.run(
            """
            $head
            let main = {} -> do
              let q = sql { text = "select * from users where name = ?" }
                .bind { v = DbStr { val = "'); drop table users; --" } }
              match seq::get { xs = q.params, i = 0 } is
                Some { val = DbStr { val = s } } -> s
                _ -> "not a bound string"
            main {}
            """.trimIndent(),
        )
        assertEquals("'); drop table users; --", r)
    }

    @Test
    fun `bind_all appends several, in order`() {
        val r = MezzeTestHelper.run(
            """
            $head
            let main = {} -> seq::size {
              xs = sql { text = "x" }.bind_all { vs = [DbInt { val = 1 }, DbNull, DbInt { val = 3 }] }.params
            }
            main {}
            """.trimIndent(),
        )
        assertEquals(3L, r)
    }

    // ── Reading a result set ──

    @Test
    fun `an empty result set reports itself empty`() {
        val r = MezzeTestHelper.run(
            """
            $head
            let main = {} -> if is_empty { rows = no_rows {} } then 1 else 0
            main {}
            """.trimIndent(),
        )
        assertEquals(1L, r)
    }

    @Test
    fun `a column is found by name and a value by position`() {
        val r = MezzeTestHelper.run(
            """
            $head
            let fixture: Rows = {
              cols = ["id", "name"],
              rows = [[DbInt { val = 1 }, DbStr { val = "ada" }], [DbInt { val = 2 }, DbStr { val = "grace" }]],
            }
            let main = {} -> do
              let at = match col_index { rows = fixture, name = "name" } is
                Some { val = i } -> i
                None -> -1
              match value_at { rows = fixture, row = 1, col = at } is
                Some { val = DbStr { val = s } } -> s
                _ -> "missing"
            main {}
            """.trimIndent(),
        )
        assertEquals("grace", r, "row 1 of the `name` column")
    }

    @Test
    fun `a column that does not exist is None, not a crash`() {
        val r = MezzeTestHelper.run(
            """
            $head
            let fixture: Rows = { cols = ["id"], rows = [[DbInt { val = 1 }]] }
            let main = {} -> match col_index { rows = fixture, name = "nope" } is
              Some { val = i } -> i
              None -> -1
            main {}
            """.trimIndent(),
        )
        assertEquals(-1L, r)
    }

    @Test
    fun `a row index past the end is None, not a crash`() {
        val r = MezzeTestHelper.run(
            """
            $head
            let fixture: Rows = { cols = ["id"], rows = [[DbInt { val = 1 }]] }
            let main = {} -> match value_at { rows = fixture, row = 99, col = 0 } is
              Some { val = _ } -> 1
              None -> 0
            main {}
            """.trimIndent(),
        )
        assertEquals(0L, r)
    }

    // ── The performers ──

    @Test
    fun `MockDb answers a read with no rows`() {
        val r = MezzeTestHelper.run(
            """
            $head
            let main = {} -> perform MockDb in
              match Db.run { q = sql { text = "select 1" } } is
                Ok { val = rows } -> row_count { rows = rows }
                Err { error = _ } -> -1
            main {}
            """.trimIndent(),
        )
        assertEquals(0L, r)
    }

    @Test
    fun `MockDbWith answers the query whose text it matches`() {
        val r = MezzeTestHelper.run(
            """
            $head
            let users: Rows = { cols = ["id"], rows = [[DbInt { val = 42 }]] }
            let main = {} -> perform MockDbWith { answers = [{ sql = "from users", rows = users }] } in
              match Db.run { q = sql { text = "select id from users where active = 1" } } is
                Ok { val = rows } -> match value_at { rows = rows, row = 0, col = 0 } is
                  Some { val = DbInt { val = n } } -> n
                  _ -> -1
                Err { error = _ } -> -2
            main {}
            """.trimIndent(),
        )
        assertEquals(42L, r, "the answer keyed on `from users` should serve this query")
    }

    @Test
    fun `MockDbWith answers a query it does not match with no rows`() {
        val r = MezzeTestHelper.run(
            """
            $head
            let users: Rows = { cols = ["id"], rows = [[DbInt { val = 42 }]] }
            let main = {} -> perform MockDbWith { answers = [{ sql = "from users", rows = users }] } in
              match Db.run { q = sql { text = "select * from orders" } } is
                Ok { val = rows } -> row_count { rows = rows }
                Err { error = _ } -> -1
            main {}
            """.trimIndent(),
        )
        assertEquals(0L, r)
    }

    @Test
    fun `a write reports how many rows changed`() {
        val r = MezzeTestHelper.run(
            """
            $head
            let main = {} -> perform MockDb in
              match Db.apply { q = sql { text = "delete from users" } } is
                Ok { val = out } -> out.changed
                Err { error = _ } -> -1
            main {}
            """.trimIndent(),
        )
        assertEquals(0L, r)
    }

    @Test
    fun `tx runs its body and hands back its value`() {
        val r = MezzeTestHelper.run(
            """
            $head
            let main = {} -> perform MockDb in
              match Db.tx { body = {} -> Ok { val = 11 } } is
                Ok { val = n } -> n
                Err { error = _ } -> -1
            main {}
            """.trimIndent(),
        )
        assertEquals(11L, r)
    }

    @Test
    fun `tx propagates a body that fails`() {
        val r = MezzeTestHelper.run(
            """
            $head
            let main = {} -> perform MockDb in
              match Db.tx { body = {} -> Err { error = Conflict { msg = "serialization" } } } is
                Ok { val = _ } -> "committed"
                Err { error = Conflict { msg = m } } -> m
                Err { error = _ } -> "other"
            main {}
            """.trimIndent(),
        )
        assertEquals("serialization", r, "a failing body must not look like a commit")
    }

    @Test
    fun `FailingDb makes the error path a one-line performer swap`() {
        val r = MezzeTestHelper.run(
            """
            $head
            let main = {} -> perform FailingDb { error = Unavailable { msg = "no route to host" } } in
              match Db.run { q = sql { text = "select 1" } } is
                Ok { val = _ } -> "unexpectedly fine"
                Err { error = Unavailable { msg = m } } -> m
                Err { error = _ } -> "other"
            main {}
            """.trimIndent(),
        )
        assertEquals("no route to host", r)
    }

    @Test
    fun `the data path of a route is testable with no database`() {
        // The shape this effect is for: a plain function that takes rows and
        // returns an answer, exercised with a mock and no driver at all.
        val r = MezzeTestHelper.run(
            """
            $head
            ## Pure: builds a query, reads a result set, answers.
            let name_of: { rows: Rows } -> Str = { rows } ->
              match col_index { rows = rows, name = "name" } is
                None -> "no such column"
                Some { val = c } -> match value_at { rows = rows, row = 0, col = c } is
                  Some { val = DbStr { val = s } } -> s
                  _ -> "not found"

            let lookup: { id: Int } -> Query = { id } ->
              sql { text = "select name from users where id = ?" }.bind { v = DbInt { val = id } }

            let users: Rows = { cols = ["name"], rows = [[DbStr { val = "ada" }]] }

            let main = {} -> perform MockDbWith { answers = [{ sql = "from users", rows = users }] } in
              match Db.run { q = lookup { id = 1 } } is
                Ok { val = rows } -> name_of { rows = rows }
                Err { error = _ } -> "query failed"
            main {}
            """.trimIndent(),
        )
        assertEquals("ada", r)
    }
}
