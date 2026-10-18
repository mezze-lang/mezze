package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * A result set decodes into records through the CODEC, not a bespoke mapper.
 *
 * `RowC` is a `Format` with Tier-2 impls, like `JsonC` and `QueryC`, so
 * derivation, nesting and `Option` handling come from the same machinery that
 * already serves JSON and query strings. `'in = Rows` at every level — the whole
 * set, one row, one cell are all `Rows` — so descending is projection.
 */
class RowCodecTest {

    private val head = """
        use std::effects::db::{ Db, MockDbWith, DbValue, DbInt, DbStr, DbBool, DbNull, DbFloat,
                                Rows, RowC, row_c, sql, first_row, no_rows, value_at, col_index }
        use std::codec::core::{ Decode, DecodeError }
        use std::collections::seq
        use std::int
    """.trimIndent()

    @Test
    fun `one row decodes into a record`() {
        val r = MezzeTestHelper.run(
            """
            $head
            let users: Rows = {
              cols = ["id", "name"],
              rows = [[DbInt { val = 7 }, DbStr { val = "ada" }]],
            }
            let main = {} -> match first_row { rows = users } is
              None -> "no rows"
              Some { val = row } -> do
                let decoded: Result { id: Int, name: Str } DecodeError = row.decode { config = row_c }
                match decoded is
                  Ok { val = u } -> "$(int::to_str { n = u.id }):$(u.name)"
                  Err { error = e } -> "failed: $(e.message)"
            main {}
            """.trimIndent(),
        )
        assertEquals("7:ada", r)
    }

    @Test
    fun `a whole result set decodes into a seq of records`() {
        val r = MezzeTestHelper.run(
            """
            $head
            let users: Rows = {
              cols = ["id", "name"],
              rows = [
                [DbInt { val = 1 }, DbStr { val = "ada" }],
                [DbInt { val = 2 }, DbStr { val = "grace" }],
              ],
            }
            let main = {} -> do
              let decoded: Result [{ id: Int, name: Str }] DecodeError = users.decode { config = row_c }
              match decoded is
                Ok { val = us } -> match seq::get { xs = us, i = 1 } is
                  Some { val = u } -> u.name
                  None -> "missing"
                Err { error = e } -> "failed: $(e.message)"
            main {}
            """.trimIndent(),
        )
        assertEquals("grace", r, "the second row's name")
    }

    @Test
    fun `a NULL column decodes as None`() {
        val r = MezzeTestHelper.run(
            """
            $head
            let rows: Rows = { cols = ["id", "nickname"], rows = [[DbInt { val = 1 }, DbNull]] }
            let main = {} -> match first_row { rows = rows } is
              None -> "no rows"
              Some { val = row } -> do
                let decoded: Result { id: Int, nickname: Option Str } DecodeError = row.decode { config = row_c }
                match decoded is
                  Ok { val = u } -> match u.nickname is
                    None -> "none"
                    Some { val = s } -> s
                  Err { error = e } -> "failed: $(e.message)"
            main {}
            """.trimIndent(),
        )
        assertEquals("none", r, "a NULL column is an absent value")
    }

    @Test
    fun `a column the query never selected also decodes as None`() {
        // Absent and NULL answer the same question: is there a value here.
        val r = MezzeTestHelper.run(
            """
            $head
            let rows: Rows = { cols = ["id"], rows = [[DbInt { val = 1 }]] }
            let main = {} -> match first_row { rows = rows } is
              None -> "no rows"
              Some { val = row } -> do
                let decoded: Result { id: Int, nickname: Option Str } DecodeError = row.decode { config = row_c }
                match decoded is
                  Ok { val = u } -> match u.nickname is
                    None -> "none"
                    Some { val = s } -> s
                  Err { error = e } -> "failed: $(e.message)"
            main {}
            """.trimIndent(),
        )
        assertEquals("none", r)
    }

    @Test
    fun `a type mismatch is a Decode error that names both sides`() {
        val r = MezzeTestHelper.run(
            """
            $head
            let rows: Rows = { cols = ["id"], rows = [[DbStr { val = "not a number" }]] }
            let main = {} -> match first_row { rows = rows } is
              None -> "no rows"
              Some { val = row } -> do
                let decoded: Result { id: Int } DecodeError = row.decode { config = row_c }
                match decoded is
                  Ok { val = _ } -> "wrongly accepted"
                  Err { error = e } -> "$(e.expected)|$(match e.found is
                    Some { val = f } -> f
                    None -> "?")"
            main {}
            """.trimIndent(),
        )
        assertEquals("Int|a string", r, "the error should name what was wanted and what was there")
    }

    @Test
    fun `an integer column reads as a Float, because a database may widen`() {
        val r = MezzeTestHelper.run(
            """
            $head
            let rows: Rows = { cols = ["ratio"], rows = [[DbInt { val = 3 }]] }
            let main = {} -> match first_row { rows = rows } is
              None -> -1.0
              Some { val = row } -> do
                let decoded: Result { ratio: Float } DecodeError = row.decode { config = row_c }
                match decoded is
                  Ok { val = v } -> v.ratio
                  Err { error = _ } -> -1.0
            main {}
            """.trimIndent(),
        )
        assertEquals(3.0, r)
    }

    @Test
    fun `an integer column reads as a Bool, because SQLite has no boolean`() {
        val r = MezzeTestHelper.run(
            """
            $head
            let rows: Rows = { cols = ["active"], rows = [[DbInt { val = 1 }]] }
            let main = {} -> match first_row { rows = rows } is
              None -> "no rows"
              Some { val = row } -> do
                let decoded: Result { active: Bool } DecodeError = row.decode { config = row_c }
                match decoded is
                  Ok { val = v } -> if v.active then "true" else "false"
                  Err { error = e } -> "failed: $(e.message)"
            main {}
            """.trimIndent(),
        )
        assertEquals("true", r)
    }

    @Test
    fun `end to end — a query, a mock, and typed records out`() {
        // What the effect is for, end to end, with no driver.
        val r = MezzeTestHelper.run(
            """
            $head
            let users: Rows = {
              cols = ["id", "name", "active"],
              rows = [
                [DbInt { val = 1 }, DbStr { val = "ada" }, DbInt { val = 1 }],
                [DbInt { val = 2 }, DbStr { val = "grace" }, DbInt { val = 0 }],
              ],
            }
            let main = {} -> perform MockDbWith { answers = [{ sql = "from users", rows = users }] } in
              match Db.run { q = sql { text = "select id, name, active from users" } } is
                Err { error = _ } -> "query failed"
                Ok { val = rows } -> do
                  let decoded: Result [{ id: Int, name: Str, active: Bool }] DecodeError = rows.decode { config = row_c }
                  match decoded is
                    Err { error = e } -> "decode failed: $(e.message)"
                    Ok { val = us } -> seq::fold {
                      xs = us,
                      init = "",
                      f = { acc, x } -> "$(acc)$(x.name)=$(if x.active then "on" else "off") ",
                    }
            main {}
            """.trimIndent(),
        )
        assertEquals("ada=on grace=off ", r)
    }
}
