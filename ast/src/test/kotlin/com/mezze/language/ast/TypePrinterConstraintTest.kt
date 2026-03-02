package com.mezze.language.ast

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class TypePrinterConstraintTest {

    @Test
    fun `test printTypeWithConstraints with no constraints`() {
        val intType = MezzeType.IntType
        val result = printTypeWithConstraints(intType, emptyList())
        assertEquals("Int", result)
    }

    @Test
    fun `test printTypeWithConstraints with single constraint`() {
        val typeVar = TypeVar("'a")
        val varType = MezzeType.VarType(typeVar)
        val constraints = listOf(
            InspectAbilityConstraint("'a", "NumAdd")
        )

        val result = printTypeWithConstraints(varType, constraints)
        assertEquals("'a with 'a : NumAdd", result)
    }

    // ========================================================================
    // EFFECT ROW PRETTY PRINTING TESTS
    // ========================================================================

    @Test
    fun `test prettyPrintType prints simple effect row`() {
        val effectRow = MezzeType.EffectRow(
            effects = setOf("Log", "State"),
            effectInstances = emptySet()
        )
        val funcType = MezzeType.FunctionType(
            paramType = MezzeType.RecordType(emptyMap()),
            returnType = MezzeType.IntType,
            effects = effectRow
        )

        val result = prettyPrintType(funcType)
        assertTrue(result.contains("Log"))
        assertTrue(result.contains("State"))
        assertTrue(result.contains("<"))
        assertTrue(result.contains(">"))
    }

    @Test
    fun `test prettyPrintType prints effect instance with single associated type`() {
        val effectInstance = MezzeType.EffectInstance(
            name = "State",
            associatedTypes = mapOf("state" to MezzeType.IntType)
        )
        val effectRow = MezzeType.EffectRow(
            effects = emptySet(),
            effectInstances = setOf(effectInstance)
        )
        val funcType = MezzeType.FunctionType(
            paramType = MezzeType.RecordType(emptyMap()),
            returnType = MezzeType.IntType,
            effects = effectRow
        )

        val result = prettyPrintType(funcType)
        assertTrue(result.contains("State"))
        assertTrue(result.contains("having"))
        assertTrue(result.contains("'state"))
        assertTrue(result.contains("Int"))
    }

    @Test
    fun `test prettyPrintType prints effect instance with multiple associated types`() {
        val effectInstance = MezzeType.EffectInstance(
            name = "Generator",
            associatedTypes = mapOf(
                "item" to MezzeType.IntType,
                "result" to MezzeType.StringType
            )
        )
        val effectRow = MezzeType.EffectRow(
            effects = emptySet(),
            effectInstances = setOf(effectInstance)
        )
        val funcType = MezzeType.FunctionType(
            paramType = MezzeType.RecordType(emptyMap()),
            returnType = MezzeType.IntType,
            effects = effectRow
        )

        val result = prettyPrintType(funcType)
        assertTrue(result.contains("Generator"))
        assertTrue(result.contains("having"))
        assertTrue(result.contains("'item"))
        assertTrue(result.contains("Int"))
        assertTrue(result.contains("'result"))
        assertTrue(result.contains("String"))
    }

    @Test
    fun `test prettyPrintType prints mixed simple and annotated effects`() {
        val effectInstance = MezzeType.EffectInstance(
            name = "State",
            associatedTypes = mapOf("state" to MezzeType.IntType)
        )
        val effectRow = MezzeType.EffectRow(
            effects = setOf("Log"),
            effectInstances = setOf(effectInstance)
        )
        val funcType = MezzeType.FunctionType(
            paramType = MezzeType.RecordType(emptyMap()),
            returnType = MezzeType.IntType,
            effects = effectRow
        )

        val result = prettyPrintType(funcType)
        assertTrue(result.contains("Log"))
        assertTrue(result.contains("State"))
        assertTrue(result.contains("having"))
    }

    @Test
    fun `test prettyPrintType prints polymorphic effect row`() {
        val effectRow = MezzeType.EffectRow(
            effects = setOf("Log"),
            effectInstances = emptySet(),
            rowVar = RowVar.fresh()
        )
        val funcType = MezzeType.FunctionType(
            paramType = MezzeType.RecordType(emptyMap()),
            returnType = MezzeType.IntType,
            effects = effectRow
        )

        val result = prettyPrintType(funcType)
        assertTrue(result.contains("Log"))
        assertTrue(result.contains("..."))
    }

    @Test
    fun `test prettyPrintType prints performed effect row marker`() {
        val effectRow = MezzeType.EffectRow(
            effects = setOf("Log"),
            effectInstances = emptySet(),
            performed = true
        )
        val funcType = MezzeType.FunctionType(
            paramType = MezzeType.RecordType(emptyMap()),
            returnType = MezzeType.IntType,
            effects = effectRow
        )

        val result = prettyPrintType(funcType)
        assertTrue(result.contains("Log"))
        assertTrue(result.contains("*"))
    }

    @Test
    fun `test prettyPrintType prints effect instance with complex associated type`() {
        val listStringType = MezzeType.ListType(MezzeType.StringType)
        val effectInstance = MezzeType.EffectInstance(
            name = "State",
            associatedTypes = mapOf("state" to listStringType)
        )
        val effectRow = MezzeType.EffectRow(
            effects = emptySet(),
            effectInstances = setOf(effectInstance)
        )
        val funcType = MezzeType.FunctionType(
            paramType = MezzeType.RecordType(emptyMap()),
            returnType = MezzeType.IntType,
            effects = effectRow
        )

        val result = prettyPrintType(funcType)
        assertTrue(result.contains("State"))
        assertTrue(result.contains("having"))
        assertTrue(result.contains("'state"))
        assertTrue(result.contains("List"))
        assertTrue(result.contains("String"))
    }

    @Test
    fun `test prettyPrintType prints multiple effect instances`() {
        val stateInstance = MezzeType.EffectInstance(
            name = "State",
            associatedTypes = mapOf("state" to MezzeType.IntType)
        )
        val readerInstance = MezzeType.EffectInstance(
            name = "Reader",
            associatedTypes = mapOf("env" to MezzeType.StringType)
        )
        val effectRow = MezzeType.EffectRow(
            effects = emptySet(),
            effectInstances = setOf(stateInstance, readerInstance)
        )
        val funcType = MezzeType.FunctionType(
            paramType = MezzeType.RecordType(emptyMap()),
            returnType = MezzeType.IntType,
            effects = effectRow
        )

        val result = prettyPrintType(funcType)
        assertTrue(result.contains("State"))
        assertTrue(result.contains("'state"))
        assertTrue(result.contains("Reader"))
        assertTrue(result.contains("'env"))
    }
}
