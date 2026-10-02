package com.mezze.interpreter.codec

import com.mezze.interpreter.MezzeTestHelper
import com.mezze.interpreter.values.MezzeVariant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Container shapes through the first-party JVM polyglot codec.
 *
 * `std::codec::jvm` shipped only the SCALAR half — `EncodeScalar` /
 * `EncodeOption` and their decode mirrors — so `[1, 2, 3].to_jvm {}` had no
 * `EncodeSequence` to auto-derive through and failed to compile. That is the same
 * gap the third-party `mezze-python` / `mezze-js` providers have, and it is the
 * reason a Mezze seq reaches a guest by being PRINTED INTO GUEST SOURCE and
 * parsed back: ~19 us fixed plus ~0.085 us per element, measured in
 * bench/cases/polyglot_seq_crossing.
 *
 * The runtime half was never the blocker — `__native_<lang>_make_list` /
 * `make_dict` build an interop VIEW over the Mezze values rather than copying,
 * and NativeDispatch registers them for every guest language from one generic
 * implementation. Only the codec impls that call them were missing.
 *
 * These tests pin the container shapes end to end: encode builds the view,
 * decode reads it back, and a round-trip returns the original Mezze value.
 */
class JvmCodecContainerTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source)

    private fun assertOk(result: Any): MezzeVariant {
        assertTrue(result is MezzeVariant, "Expected MezzeVariant, got $result (${result.javaClass})")
        val v = result as MezzeVariant
        assertTrue(v.isOk, "Expected Ok variant, got tag=${v.tag}")
        return v
    }

    // ==================== encode: list ====================

    @Test
    fun `seq of Int encodes to a JvmForeign with array elements`() {
        val result = eval(
            """
            use std::codec::jvm::{ ToJvm }
            use std::polyglot::jvm::{ JvmForeign }
            let f = [1, 2, 3].to_jvm {}
            f.len {}
            """.trimIndent()
        )
        val v = assertOk(result)
        assertEquals(3L, v.getPayloadFieldByName("val"))
    }

    @Test
    fun `an encoded seq reads back element by element`() {
        val result = eval(
            """
            use std::codec::jvm::{ ToJvm }
            use std::polyglot::jvm::{ JvmForeign }
            let f = [10, 20, 30].to_jvm {}
            f.at { index = 1 }.int {}
            """.trimIndent()
        )
        val v = assertOk(result)
        assertEquals(20L, v.getPayloadFieldByName("val"))
    }

    // ==================== decode: list ====================

    @Test
    fun `a seq of Int round-trips through the JVM codec`() {
        val result = eval(
            """
            use std::codec::jvm::{ ToJvm, FromJvm }
            use std::polyglot::jvm::{ JvmForeign }
            use std::collections::seq
            let f = [7, 8, 9].to_jvm {}
            match f.from_jvm {} is
              Ok { val = back } -> Ok { val = seq::sum { xs = back } }
              Err { error = _ } -> Ok { val = 0 - 1 }
            """.trimIndent()
        )
        val v = assertOk(result)
        assertEquals(24L, v.getPayloadFieldByName("val"))
    }

    @Test
    fun `a nested seq encodes as a view of views`() {
        val result = eval(
            """
            use std::codec::jvm::{ ToJvm }
            use std::polyglot::jvm::{ JvmForeign }
            let f = [[1, 2], [3, 4, 5]].to_jvm {}
            f.at { index = 1 }.len {}
            """.trimIndent()
        )
        val v = assertOk(result)
        assertEquals(3L, v.getPayloadFieldByName("val"))
    }

    // ==================== records ====================

    @Test
    fun `a record encodes to a hash-shaped Foreign keyed by field name`() {
        val result = eval(
            """
            use std::codec::jvm::{ ToJvm }
            use std::polyglot::jvm::{ JvmForeign }
            let f = { count = 42, label = "hi" }.to_jvm {}
            f.key { of = "count" }.int {}
            """.trimIndent()
        )
        val v = assertOk(result)
        assertEquals(42L, v.getPayloadFieldByName("val"))
    }

    @Test
    fun `a record round-trips through the JVM codec`() {
        val result = eval(
            """
            use std::codec::jvm::{ ToJvm, FromJvm }
            use std::polyglot::jvm::{ JvmForeign }
            pub type Point = { x: Int, y: Int }
            let f = { x = 3, y = 4 }.to_jvm {}
            let back: Result Point DecodeError = f.from_jvm {}
            match back is
              Ok { val = p } -> Ok { val = p.x * 10 + p.y }
              Err { error = _ } -> Ok { val = 0 - 1 }
            """.trimIndent()
        )
        val v = assertOk(result)
        assertEquals(34L, v.getPayloadFieldByName("val"))
    }

    // ==================== maps ====================

    @Test
    fun `a map encodes to a hash-shaped Foreign with one entry per key`() {
        val result = eval(
            """
            use std::codec::jvm::{ ToJvm }
            use std::polyglot::jvm::{ JvmForeign }
            use std::collections::map
            let m = map::insert { m = map::insert { m = map::empty {}, k = "a", v = 1 }, k = "b", v = 2 }
            let f = m.to_jvm {}
            f.len {}
            """.trimIndent()
        )
        val v = assertOk(result)
        assertEquals(2L, v.getPayloadFieldByName("val"))
    }

    @Test
    fun `an encoded map reads an entry back by key`() {
        val result = eval(
            """
            use std::codec::jvm::{ ToJvm }
            use std::polyglot::jvm::{ JvmForeign }
            use std::collections::map
            let m = map::insert { m = map::empty {}, k = "a", v = 7 }
            let f = m.to_jvm {}
            f.key { of = "a" }.int {}
            """.trimIndent()
        )
        val v = assertOk(result)
        assertEquals(7L, v.getPayloadFieldByName("val"))
    }

    /**
     * Decoding INTO a `Map` — the walker decoded the entries but nothing rebuilt
     * the Map from them, so the returned value's runtime shape was not its
     * declared type. JSON surfaced that as a decode `Err`; JvmC got one rung
     * further and reached `map::size` holding the entry seq, failing "Expected
     * MezzeMap for field 'm'". Same missing step, two symptoms, every format.
     * There was no Map-decode test in the suite at all, which is why it stood.
     */
    @Test
    fun `a map round-trips through the JSON codec`() {
        val result = eval(
            """
            use std::codec::json::{ ToJson, FromJson }
            use std::collections::map
            let m = map::insert { m = map::insert { m = map::empty {}, k = "a", v = 1 }, k = "b", v = 2 }
            let j = m.to_json {}
            let back: Result (Map Str Int) DecodeError = j.from_json {}
            match back is
              Ok { val = m2 } -> Ok { val = map::size { m = m2 } }
              Err { error = _ } -> Ok { val = 0 - 1 }
            """.trimIndent()
        )
        val v = assertOk(result)
        assertEquals(2L, v.getPayloadFieldByName("val"))
    }

    /** The rebuilt Map must be a real Map — its entries readable by key, not
     *  merely countable. */
    @Test
    fun `a decoded map reads its values back by key`() {
        val result = eval(
            """
            use std::codec::json::{ ToJson, FromJson }
            use std::collections::map
            let m = map::insert { m = map::insert { m = map::empty {}, k = "a", v = 10 }, k = "b", v = 20 }
            let j = m.to_json {}
            let back: Result (Map Str Int) DecodeError = j.from_json {}
            match back is
              Ok { val = m2 } -> match map::get { m = m2, k = "b" } is
                Some { val = n } -> Ok { val = n }
                None -> Ok { val = 0 - 1 }
              Err { error = _ } -> Ok { val = 0 - 2 }
            """.trimIndent()
        )
        val v = assertOk(result)
        assertEquals(20L, v.getPayloadFieldByName("val"))
    }

    @Test
    fun `a map round-trips through the JVM codec`() {
        val result = eval(
            """
            use std::codec::jvm::{ ToJvm, FromJvm }
            use std::polyglot::jvm::{ JvmForeign }
            use std::collections::map
            let m = map::insert { m = map::insert { m = map::empty {}, k = "a", v = 1 }, k = "b", v = 2 }
            let f = m.to_jvm {}
            let back: Result (Map Str Int) DecodeError = f.from_jvm {}
            match back is
              Ok { val = m2 } -> Ok { val = map::size { m = m2 } }
              Err { error = _ } -> Ok { val = 0 - 1 }
            """.trimIndent()
        )
        val v = assertOk(result)
        assertEquals(2L, v.getPayloadFieldByName("val"))
    }

    // ==================== two derivations in one module ====================

    /**
     * SOUNDNESS: two seq derivations in one module must not collide.
     *
     * Before the fix, encoding `[Int]` and then `[Str]` in one module gave
     * `[1,2,3]` and then EMPTY; reversing the order mirrored it exactly, so any
     * derivation after the first was wrong. On the JVM codec the same program
     * died with "Expected Long for field 'val'" — `from_int` handed a Str.
     * Silent for JSON, fatal for JvmC.
     *
     * Cause was in `MonoTypeOps.typeMatchesTarget`: the TypeApplication arm
     * compared CONSTRUCTORS only, and `Seq Int` and `Seq Str` share one. Every
     * parameterized impl of an ability was therefore interchangeable with every
     * other, and whichever was tested first answered for both. Not codec-specific
     * — it applied to any `impl … for C 'a` pair.
     *
     * Found by running a real multi-shape program through the CLI: every codec
     * test in the suite encodes ONE shape per module, so nothing compared two.
     */
    @Test
    fun `two seq derivations in one module do not collide`() {
        val result = eval(
            """
            use std::codec::json::{ ToJson, to_str }
            let ints: [Int] = [1, 2, 3]
            let strs: [Str] = ["a", "b"]
            "$(to_str { json = ints.to_json {} })|$(to_str { json = strs.to_json {} })"
            """.trimIndent()
        )
        assertEquals("[1,2,3]|[\"a\",\"b\"]", result)
    }

    /** Three element types, and the reverse order — the shape that showed the
     *  defect was order-dependent rather than "one derivation per module". */
    @Test
    fun `three seq derivations in reverse order each use their own encoder`() {
        val result = eval(
            """
            use std::codec::json::{ ToJson, to_str }
            let strs: [Str] = ["a", "b"]
            let ints: [Int] = [1, 2, 3]
            let bools: [Bool] = [True, False]
            "$(to_str { json = strs.to_json {} })|$(to_str { json = ints.to_json {} })|$(to_str { json = bools.to_json {} })"
            """.trimIndent()
        )
        assertEquals("[\"a\",\"b\"]|[1,2,3]|[true,false]", result)
    }

    // ==================== options ====================

    /**
     * The SAME encode, inside `perform … in do`.
     *
     * Every other Option test here runs at the top level of a module and passes.
     * Running the identical code through the real CLI — where it lives in a
     * `perform Console in do` — it died with "Expected Long for field 'val'",
     * with the element type written out as `[Option Int]` at the binding. So the
     * effect/do context, not the spelling, is what changes which encoder the
     * walker picks. A handler must not change what a value encodes as.
     */
    @Test
    fun `a seq of Option encodes the same inside a perform-do block`() {
        val result = eval(
            """
            use std::effects::log::{ Log, Console, info }
            use std::codec::jvm::{ ToJvm, FromJvm }
            use std::polyglot::jvm::{ JvmForeign }
            use std::collections::seq

            let main = {} -> perform Console in do
              info { msg = "encoding" }
              let opts: [Option Int] = [Some { val = 10 }, None, Some { val = 20 }]
              let f = opts.to_jvm {}
              let back: Result [Option Int] DecodeError = f.from_jvm {}
              match back is
                Ok { val = xs } -> Ok { val = seq::size { xs = xs } }
                Err { error = _ } -> Ok { val = 0 - 1 }
            main {}
            """.trimIndent()
        )
        val v = assertOk(result)
        assertEquals(3L, v.getPayloadFieldByName("val"))
    }

    /**
     * Same value as the test below, but LET-BOUND before `.to_jvm {}` instead of
     * written inline at the call. Spelling a value into a variable must not
     * change how it encodes.
     */
    @Test
    fun `a let-bound seq of Option encodes the same as an inline one`() {
        val result = eval(
            """
            use std::codec::jvm::{ ToJvm, FromJvm }
            use std::polyglot::jvm::{ JvmForeign }
            use std::collections::seq
            let opts = [Some { val = 10 }, None, Some { val = 20 }]
            let f = opts.to_jvm {}
            let back: Result [Option Int] DecodeError = f.from_jvm {}
            match back is
              Ok { val = xs } -> Ok { val = seq::size { xs = xs } }
              Err { error = _ } -> Ok { val = 0 - 1 }
            """.trimIndent()
        )
        val v = assertOk(result)
        assertEquals(3L, v.getPayloadFieldByName("val"))
    }

    /**
     * Cross-check against a format that has had containers all along. If JSON
     * shows the same shape, an Option inside a container is mis-encoded for
     * EVERY format and the defect is in the shared walker, not in JvmC.
     */
    @Test
    fun `an Option inside a seq encodes through encode_option, not as a tagged variant`() {
        val result = eval(
            """
            use std::codec::json::{ ToJson, to_str }
            let j = [Some { val = 5 }, None].to_json {}
            to_str { json = j }
            """.trimIndent()
        )
        assertEquals("[5,null]", result)
    }

    /**
     * Isolates the runtime from the codec: a null put into a list view must
     * still read back as null. If this fails, `decode_option` cannot tell None
     * from a present value and every Option in a container decodes as its
     * inner type.
     */
    @Test
    fun `a null survives a round trip through a list view`() {
        val result = eval(
            """
            use std::polyglot::jvm::{ jvm_list, from_int, null_val }
            let l = jvm_list { elems = [from_int { val = 1 }, null_val {}] }
            l.at { index = 1 }.is_null {}
            """.trimIndent()
        )
        val v = assertOk(result)
        assertEquals(true, v.getPayloadFieldByName("val"))
    }

    @Test
    fun `a seq of Option round-trips, preserving None`() {
        val result = eval(
            """
            use std::codec::jvm::{ ToJvm, FromJvm }
            use std::polyglot::jvm::{ JvmForeign }
            use std::collections::seq
            let or_else: { o: Option Int, d: Int } -> Int = { o, d } -> match o is
              Some { val = n } -> n
              None -> d
            let f = [Some { val = 5 }, None, Some { val = 7 }].to_jvm {}
            let back: Result [Option Int] DecodeError = f.from_jvm {}
            match back is
              Ok { val = xs } -> Ok {
                val = seq::sum { xs = seq::map { xs = xs, f = { x } -> or_else { o = x, d = 100 } } },
              }
              Err { error = e } -> Ok { val = e.message }
            """.trimIndent()
        )
        val v = assertOk(result)
        assertEquals(112L, v.getPayloadFieldByName("val"))
    }
}
