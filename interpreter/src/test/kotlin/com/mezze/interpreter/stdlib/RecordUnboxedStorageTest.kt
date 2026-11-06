package com.mezze.interpreter.stdlib

import com.mezze.interpreter.MezzeTestHelper
import com.mezze.interpreter.values.MezzeRecord
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * A single-field record must store an `Int` in the shape's `long[]`, not as a
 * boxed `Long` in `objectFields`.
 *
 * WHAT THESE ASSERT, AND WHY IT IS NOT A STYLE PREFERENCE. `MezzeRecord` has
 * always had unboxed `long[]` / `double[]` storage and `ShapeCache` has always
 * had `getOrCreateSingleLong`, and until this change nothing ever called it:
 * `MakeSingleFieldRecord` had one `Object` specialization, so EVERY `Int` field
 * of EVERY record in the language was a boxed `Long`. `{ r = i }` cost three
 * allocations — the box, the `Object[1]`, the record — where two suffice, and a
 * two-field variant payload cost five objects against one for a single-field
 * constructor. Counted as young-GC pauses, that was 38 against 33 for the same
 * loop; see PERF-LOG and `bench/cases/surface`.
 *
 * These fail without the fix: `getLongFieldCount()` reads 0 and
 * `getObjectFieldCount()` reads 1, which is the reverse of every assertion
 * below.
 */
class RecordUnboxedStorageTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun record(source: String): MezzeRecord {
        val result = MezzeTestHelper.run(source)
        assertTrue(result is MezzeRecord, "expected a record, got ${result?.javaClass?.name}: $result")
        return result as MezzeRecord
    }

    @Test
    fun `an Int field is stored unboxed in the long array`() {
        val r = record("{ r = 42 }")
        assertEquals(1, r.longFieldCount, "the Int should occupy the long[] slot")
        assertEquals(0, r.objectFieldCount, "nothing should be boxed into objectFields")
        assertEquals(0, r.doubleFieldCount)
        assertEquals(42L, r.getField("r"), "and it must still read back by name")
    }

    @Test
    fun `a Float field is stored unboxed in the double array`() {
        val r = record("{ x = 2.5 }")
        assertEquals(1, r.doubleFieldCount, "the Float should occupy the double[] slot")
        assertEquals(0, r.objectFieldCount)
        assertEquals(0, r.longFieldCount)
        assertEquals(2.5, r.getField("x"))
    }

    @Test
    fun `a Str field still uses object storage`() {
        val r = record("""{ s = "hi" }""")
        assertEquals(1, r.objectFieldCount, "a Str has no unboxed slot to go in")
        assertEquals(0, r.longFieldCount)
        assertEquals("hi", r.getField("s"))
    }

    /**
     * The field is read back through the ordinary path, by name, from the
     * unboxed slot. This is the half that would silently return garbage if the
     * read side did not switch on the shape's storage kind — it does, in
     * `MezzeRecord.getField` and in `ReadFieldOrMethod.doRecordCached`.
     */
    @Test
    fun `an unboxed field reads back through a normal field access`() {
        assertEquals(42L, MezzeTestHelper.run("""
            use std::int
            let rec = { r = 42 }
            rec.r
        """.trimIndent()))
    }

    @Test
    fun `an unboxed field survives arithmetic on the way out`() {
        assertEquals(84L, MezzeTestHelper.run("""
            let rec = { r = 42 }
            rec.r * 2
        """.trimIndent()))
    }

    @Test
    fun `an unboxed Float field reads back and computes`() {
        assertEquals(5.0, MezzeTestHelper.run("""
            let rec = { x = 2.5 }
            rec.x * 2.0
        """.trimIndent()))
    }

    /**
     * ONE SITE, TWO VALUE TYPES. A polymorphic constructor called with an Int
     * and then a Str must not thrash between two shapes: the `Object`
     * specialization `replaces` the typed ones, so the site settles on the
     * object layout and both calls keep working. Equality is by field NAME
     * through `getField`, so layout never leaks into behaviour.
     */
    @Test
    fun `a site that sees two value types falls back and stays correct`() {
        assertEquals("42|hi", MezzeTestHelper.run("""
            use std::str
            use std::int
            let wrap = { v } -> { val = v }
            let a = wrap { v = 42 }
            let b = wrap { v = "hi" }
            str::concat { l = int::to_str { n = a.val }, r = str::concat { l = "|", r = b.val } }
        """.trimIndent()))
    }

    /**
     * Records with the same field names and values are equal regardless of
     * which array holds them — `equals` iterates names through `getField`.
     * Without that, introducing a second layout would have split equality.
     */
    @Test
    fun `records are equal across storage layouts`() {
        assertEquals(true, MezzeTestHelper.run("""
            let typed = { r = 42 }
            let viaPoly = { v } -> { r = v }
            typed == viaPoly { v = 42 }
        """.trimIndent()))
    }
}
