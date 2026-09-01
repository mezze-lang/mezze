package com.mezze.interpreter

import com.mezze.interpreter.values.MezzeRecord
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Regression tests for #399: the runtime unit value is unified onto the shared
 * empty-record singleton (MezzeUnit was deleted). Before the fix the interpreter
 * kept two representations of type `{}` — the empty-record from a `{}` literal and
 * the unit returned by effectful natives / statements — which diverged:
 *   - `emptyRecord == unit` returned False (should be True)
 *   - `emptyRecord != unit` returned True  (should be False)
 *   - `{...unit}` threw "Cannot spread non-record value" (should yield `{}`)
 *   - `unit.to_dbg {}` renders `{}`.
 *
 * All four are pinned here so the divergence cannot return.
 */
class UnitEmptyRecordUnificationTest {

    @Test
    fun `unit from effectful native compares, spreads, and renders as empty record`() {
        // `u` is the unit returned by the effectful native `println`; `e` is the
        // empty-record literal. Both are type `{}` and must now behave identically.
        val source = """
            use std::effects::log::{println, Console}

            perform Console in do
                let e = {}
                let u = println { msg = "producing unit" }
                let eq = e == u
                let ne = e != u
                let spread = { ...u }
                let dbg = u.to_dbg {}
                { eq = eq, ne = ne, spread = spread, dbg = dbg }
        """.trimIndent()

        val result = MezzeTestHelper.run(source)
        assertTrue(result is MezzeRecord, "expected a record result, got $result")
        val rec = result as MezzeRecord

        assertEquals(true, rec.getField("eq"), "empty-record == unit must be True")
        assertEquals(false, rec.getField("ne"), "empty-record != unit must be False")

        val spread = rec.getField("spread")
        assertTrue(spread is MezzeRecord, "spread of unit must be a record (no crash), got $spread")
        assertEquals(0, (spread as MezzeRecord).fieldCount, "spread of unit must be {}")

        assertEquals("{}", rec.getField("dbg"), "unit.to_dbg must render {}")
    }

    @Test
    fun `pure empty-record literal still spreads and compares`() {
        val source = """
            let a = {}
            let b = {}
            let eq = a == b
            let spread = { ...a }
            { eq = eq, spread = spread }
        """.trimIndent()

        val result = MezzeTestHelper.run(source)
        assertTrue(result is MezzeRecord, "expected a record result, got $result")
        val rec = result as MezzeRecord
        assertEquals(true, rec.getField("eq"), "empty == empty must be True")
        val spread = rec.getField("spread")
        assertTrue(spread is MezzeRecord, "spread of {} must be a record")
        assertEquals(0, (spread as MezzeRecord).fieldCount, "spread of {} must be {}")
    }
}
