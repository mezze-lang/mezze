package com.mezze.language.typechecker

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.RowVar
import com.mezze.language.ast.solving.closeGeneratedRowVars
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Property tests for the ONE labeled-row rule that records and variants
 * genuinely share (S4.3 / #268 investigation): the drop-and-close policy in
 * [closeGeneratedRowVars], now routed through the single `closeLabeledRowVar`
 * helper. These assert the close DECISION is symmetric across the two
 * mechanisms — a generated tail closes, a user-specified tail stays open, and a
 * `mustBeOpenRowVars` tail stays open — for BOTH records and variants
 * identically.
 *
 * Effects are intentionally NOT covered here: effect rows do not participate in
 * [closeGeneratedRowVars] at all — they ride on `type.effects` (a set-row with
 * its own bind/subtract machinery), which is precisely why the three mechanisms
 * cannot share one RowVar close rule. The record+variant symmetry is the shared
 * surface; effects are the divergent one.
 */
class SharedLabeledRowCloseTest {

    // --- records ---

    @Test
    fun `open record with a generated tail closes`() {
        val gen = RowVar.fresh()
        val rec = MezzeType.RecordType(fields = mapOf("x" to MezzeType.IntType), rowVar = gen)
        val closed = closeGeneratedRowVars(rec) as MezzeType.RecordType
        assertNull(closed.rowVar, "generated record tail must close to null")
        assertEquals(rec.fields, closed.fields, "fields must be preserved unchanged")
    }

    @Test
    fun `open record with a user-specified tail stays open`() {
        val user = RowVar.userOpen()
        val rec = MezzeType.RecordType(fields = mapOf("x" to MezzeType.IntType), rowVar = user)
        val out = closeGeneratedRowVars(rec) as MezzeType.RecordType
        assertEquals(user, out.rowVar, "user-specified record tail must stay open")
    }

    @Test
    fun `open record with a generated tail in mustBeOpen stays open`() {
        val gen = RowVar.fresh()
        val rec = MezzeType.RecordType(fields = mapOf("x" to MezzeType.IntType), rowVar = gen)
        val out = closeGeneratedRowVars(rec, mustBeOpenRowVars = setOf(gen)) as MezzeType.RecordType
        assertEquals(gen, out.rowVar, "generated record tail listed as must-be-open must stay open")
    }

    @Test
    fun `closed record stays closed`() {
        val rec = MezzeType.RecordType(fields = mapOf("x" to MezzeType.IntType), rowVar = null)
        val out = closeGeneratedRowVars(rec) as MezzeType.RecordType
        assertNull(out.rowVar, "closed record stays closed")
    }

    // --- variants ---

    @Test
    fun `open variant with a generated tail closes`() {
        val gen = RowVar.fresh()
        val v = MezzeType.VariantType(constructors = mapOf("Red" to null), rowVar = gen)
        val closed = closeGeneratedRowVars(v) as MezzeType.VariantType
        assertNull(closed.rowVar, "generated variant tail must close to null")
        assertEquals(v.constructors, closed.constructors, "constructors must be preserved unchanged")
    }

    @Test
    fun `open variant with a user-specified tail stays open`() {
        val user = RowVar.userOpen()
        val v = MezzeType.VariantType(constructors = mapOf("Red" to null), rowVar = user)
        val out = closeGeneratedRowVars(v) as MezzeType.VariantType
        assertEquals(user, out.rowVar, "user-specified variant tail must stay open")
    }

    @Test
    fun `open variant with a generated tail in mustBeOpen stays open`() {
        val gen = RowVar.fresh()
        val v = MezzeType.VariantType(constructors = mapOf("Red" to null), rowVar = gen)
        val out = closeGeneratedRowVars(v, mustBeOpenRowVars = setOf(gen)) as MezzeType.VariantType
        assertEquals(gen, out.rowVar, "generated variant tail listed as must-be-open must stay open")
    }

    @Test
    fun `closed variant stays closed`() {
        val v = MezzeType.VariantType(constructors = mapOf("Red" to null), rowVar = null)
        val out = closeGeneratedRowVars(v) as MezzeType.VariantType
        assertNull(out.rowVar, "closed variant stays closed")
    }

    // --- symmetry: same tail, same decision, both mechanisms ---

    @Test
    fun `record and variant close the same generated tail identically`() {
        val gen = RowVar.fresh()
        val rec = closeGeneratedRowVars(
            MezzeType.RecordType(fields = mapOf("x" to MezzeType.IntType), rowVar = gen)
        ) as MezzeType.RecordType
        val v = closeGeneratedRowVars(
            MezzeType.VariantType(constructors = mapOf("Red" to null), rowVar = gen)
        ) as MezzeType.VariantType
        assertEquals(rec.rowVar, v.rowVar, "record and variant must reach the SAME close decision for the same tail")
    }

    @Test
    fun `record and variant keep the same must-be-open tail identically`() {
        val gen = RowVar.fresh()
        val rec = closeGeneratedRowVars(
            MezzeType.RecordType(fields = mapOf("x" to MezzeType.IntType), rowVar = gen),
            mustBeOpenRowVars = setOf(gen)
        ) as MezzeType.RecordType
        val v = closeGeneratedRowVars(
            MezzeType.VariantType(constructors = mapOf("Red" to null), rowVar = gen),
            mustBeOpenRowVars = setOf(gen)
        ) as MezzeType.VariantType
        assertEquals(rec.rowVar, v.rowVar, "record and variant must reach the SAME keep-open decision for the same tail")
        assertEquals(gen, rec.rowVar)
    }
}
