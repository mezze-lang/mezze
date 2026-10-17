package com.mezze.interpreter.codec

import com.mezze.interpreter.MezzeTestHelper
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `QueryC` — a record to a URL query and back.
 *
 * The format's representation is a flat list of pairs, and one rule decides
 * everything: a pair's key is relative to where it sits, so a scalar has no key
 * and a record names its children. These tests pin the three shapes that rule
 * produces — a scalar field, a repeated key, a bracketed nesting — and the
 * round trip through each.
 */
class QueryCodecTest {

    private val head = """
        use std::codec::query::{ QueryC, ToQuery, FromQuery, query_default, render_pairs, parse_pairs }
        use std::codec::core::{ DecodeError }
    """.trimIndent()

    @Test
    fun `a flat record renders as k equals v pairs`() {
        val r = MezzeTestHelper.run("""
            $head
            { page = 2, sort = "name" }.query_string {}
        """.trimIndent())
        // Field order follows the record's own, which is the declaration order.
        assertEquals("page=2&sort=name", r)
    }

    @Test
    fun `a sequence field becomes a repeated key`() {
        val r = MezzeTestHelper.run("""
            $head
            { tags = ["a", "b"] }.query_string {}
        """.trimIndent())
        assertEquals("tags=a&tags=b", r, "every element kept the empty key, so the record named them all alike")
    }

    @Test
    fun `a nested record becomes a bracketed key`() {
        val r = MezzeTestHelper.run("""
            $head
            { user = { name = "ada" } }.query_string {}
        """.trimIndent())
        assertEquals("user%5Bname%5D=ada", r, "the bracket convention, percent-encoded by render_pairs")
    }

    @Test
    fun `a value that needs escaping is escaped`() {
        val r = MezzeTestHelper.run("""
            $head
            { q = "ada lovelace & co" }.query_string {}
        """.trimIndent())
        assertEquals("q=ada%20lovelace%20%26%20co", r)
    }

    @Test
    fun `a flat record round-trips`() {
        val r = MezzeTestHelper.run("""
            $head
            use std::int
            let back: Result { page: Int, sort: Str } DecodeError =
              { page = 2, sort = "name" }.query_string {}.from_query {}
            match back is
              Ok { val } -> "$(int::to_str { n = val.page })-$(val.sort)"
              Err { error = e } -> e.message
        """.trimIndent())
        assertEquals("2-name", r)
    }

    @Test
    fun `a repeated key round-trips into a sequence`() {
        val r = MezzeTestHelper.run("""
            $head
            use std::str
            let back: Result { tags: [Str] } DecodeError = "tags=a&tags=b&tags=c".from_query {}
            match back is
              Ok { val } -> str::join { strings = val.tags, separator = "-" }
              Err { error = e } -> e.message
        """.trimIndent())
        assertEquals("a-b-c", r)
    }

    @Test
    fun `a bracketed key round-trips into a nested record`() {
        val r = MezzeTestHelper.run("""
            $head
            let back: Result { user: { name: Str } } DecodeError = "user[name]=ada".from_query {}
            match back is
              Ok { val } -> val.user.name
              Err { error = e } -> e.message
        """.trimIndent())
        assertEquals("ada", r)
    }

    @Test
    fun `an absent optional field decodes as None, and an empty one as the empty string`() {
        val r = MezzeTestHelper.run("""
            $head
            let absent: Result { sort: Option Str } DecodeError = "page=2".from_query {}
            let empty: Result { sort: Option Str } DecodeError = "sort=".from_query {}
            let show = { r: Result { sort: Option Str } DecodeError } -> match r is
              Ok { val } -> match val.sort is
                Some { val = s } -> "some($(s))"
                None -> "none"
              Err { error = e } -> e.message
            "$(show { r = absent })/$(show { r = empty })"
        """.trimIndent())
        assertEquals("none/some()", r,
            "`field=` mentioned the field and means the empty string; a query that never mentioned it is None")
    }

    @Test
    fun `a leading question mark is allowed`() {
        val r = MezzeTestHelper.run("""
            $head
            let back: Result { page: Int } DecodeError = "?page=7".from_query {}
            use std::int
            match back is
              Ok { val } -> int::to_str { n = val.page }
              Err { error = e } -> e.message
        """.trimIndent())
        assertEquals("7", r, "a target taken straight off the wire still works")
    }

    @Test
    fun `a field the query never sent is a decode error naming the field`() {
        val r = MezzeTestHelper.run("""
            $head
            let back: Result { page: Int } DecodeError = "sort=name".from_query {}
            match back is
              Ok { val } -> "decoded anyway"
              Err { error = e } -> e.message
        """.trimIndent())
        assertTrue(
            (r as String) != "decoded anyway",
            "a missing Int must fail rather than default; got: $r",
        )
    }

    @Test
    fun `a value that is not an integer says so`() {
        val r = MezzeTestHelper.run("""
            $head
            let back: Result { page: Int } DecodeError = "page=later".from_query {}
            match back is
              Ok { val } -> "decoded anyway"
              Err { error = e } -> "$(e.message):$(e.expected)"
        """.trimIndent())
        assertEquals("Expected integer:Int", r)
    }
}
