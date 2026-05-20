package com.mezze.language.typechecker.constraints

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.RowVar
import com.mezze.language.ast.TypeVar
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Targeted tests for the `synthetic` parameter on `removeEffectsFromType`.
 *
 * The function lives in ExpressionConstraintGenerator (internal so test can call it).
 *
 * Behavior:
 *   - Default `synthetic = false` keeps the existing behavior: marks the result type
 *     with `performed = true` (which surfaces as `<*>` on bare values).
 *   - `synthetic = true` (used by the do-bind desugar's synthetic perform) does NOT
 *     mark the result as performed. Keeps `<*>` out of enclosing function signatures.
 */
class RemoveEffectsSyntheticTest {

    private val gen = ExpressionConstraintGenerator()

    private fun fnTypeWithEffect(effect: String): MezzeType.FunctionType =
        MezzeType.FunctionType(
            paramType = MezzeType.RecordType(emptyMap(), null),
            returnType = MezzeType.IntType,
            effects = MezzeType.EffectRow.simple(setOf(effect), null, false),
        )

    private fun suspendedWithEffect(effect: String): MezzeType.SuspendedType =
        MezzeType.SuspendedType(
            valueType = MezzeType.IntType,
            effects = MezzeType.EffectRow.simple(setOf(effect), null, false),
        )

    @Test
    fun `default synthetic=false sets performed=true on FunctionType`() {
        val fn = fnTypeWithEffect("Exception")
        val result = gen.removeEffectsFromType(fn, setOf("Exception"))
        assertTrue(result is MezzeType.FunctionType)
        val resultFn = result as MezzeType.FunctionType
        assertTrue(resultFn.effects!!.performed, "Default (synthetic=false) must set performed=true")
        assertEquals(emptySet<String>(), resultFn.effects!!.effects, "Effect must be removed")
    }

    @Test
    fun `synthetic=true keeps performed=false on FunctionType`() {
        val fn = fnTypeWithEffect("Exception")
        val result = gen.removeEffectsFromType(fn, setOf("Exception"), synthetic = true)
        val resultFn = result as MezzeType.FunctionType
        assertFalse(resultFn.effects!!.performed, "synthetic=true must NOT set performed")
        assertEquals(emptySet<String>(), resultFn.effects!!.effects, "Effect must still be removed")
    }

    @Test
    fun `default synthetic=false sets performed=true on SuspendedType`() {
        val s = suspendedWithEffect("Exception")
        val result = gen.removeEffectsFromType(s, setOf("Exception"))
        val resultSus = result as MezzeType.SuspendedType
        assertTrue(resultSus.effects.performed, "Default must set performed=true")
    }

    @Test
    fun `synthetic=true keeps performed=false on SuspendedType`() {
        val s = suspendedWithEffect("Exception")
        val result = gen.removeEffectsFromType(s, setOf("Exception"), synthetic = true)
        val resultSus = result as MezzeType.SuspendedType
        assertFalse(resultSus.effects.performed, "synthetic=true must NOT set performed")
    }

    @Test
    fun `synthetic=true preserves other effects`() {
        // Function with <Log, Exception> — synthetic perform handles Exception only.
        // Log must survive. `performed` must stay false.
        val fn = MezzeType.FunctionType(
            paramType = MezzeType.RecordType(emptyMap(), null),
            returnType = MezzeType.IntType,
            effects = MezzeType.EffectRow.simple(setOf("Log", "Exception"), null, false),
        )
        val result = gen.removeEffectsFromType(fn, setOf("Exception"), synthetic = true)
        val resultFn = result as MezzeType.FunctionType
        assertEquals(setOf("Log"), resultFn.effects!!.effects, "Log must survive")
        assertFalse(resultFn.effects!!.performed, "synthetic must keep performed=false")
    }

    @Test
    fun `synthetic=true preserves polymorphic row variable`() {
        val rowVar = RowVar.fresh()
        val fn = MezzeType.FunctionType(
            paramType = MezzeType.RecordType(emptyMap(), null),
            returnType = MezzeType.IntType,
            effects = MezzeType.EffectRow.simple(setOf("Exception"), rowVar, false),
        )
        val result = gen.removeEffectsFromType(fn, setOf("Exception"), synthetic = true)
        val resultFn = result as MezzeType.FunctionType
        assertEquals(rowVar, resultFn.effects!!.rowVar, "Polymorphic row var must be preserved")
        assertFalse(resultFn.effects!!.performed)
    }
}
