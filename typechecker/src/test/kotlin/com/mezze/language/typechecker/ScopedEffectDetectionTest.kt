package com.mezze.language.typechecker

import com.mezze.language.ast.*
import com.mezze.language.parsedast.*
import com.mezze.language.typechecker.scope.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for scoped effect detection in impl declarations.
 */
class ScopedEffectDetectionTest {

    private val dummyLoc = Loc("test", 0, 0, 0, 0)

    private fun makeOperation(name: String): ParsedPerformerOp {
        return ParsedPerformerOp(
            name = name,
            paramType = ParsedRecordType(emptyList(), loc = dummyLoc),
            returnType = ParsedRecordType(emptyList(), loc = dummyLoc),
            implementation = ParsedRecordLit(emptyList(), loc = dummyLoc),
            loc = dummyLoc
        )
    }

    @Test
    fun `simple effect with no scope members works unchanged`() {
        val operations = listOf("read", "write", "close").map { makeOperation(it) }

        val info = detectScopedEffectMembers(operations)

        assertFalse(info.isScopedEffect)
        assertNull(info.initializeScope)
        assertNull(info.finalizeScope)
        assertFalse(info.hasFinalizer)
        assertEquals(3, info.regularOperations.size)
        assertEquals(listOf("read", "write", "close"), info.regularOperations.map { it.name })
    }

    @Test
    fun `impl with only initialize_scope is detected`() {
        val operations = listOf("initialize_scope", "query", "execute").map { makeOperation(it) }

        val info = detectScopedEffectMembers(operations)

        assertTrue(info.isScopedEffect)
        assertNotNull(info.initializeScope)
        assertEquals("initialize_scope", info.initializeScope!!.name)
        assertNull(info.finalizeScope)
        assertFalse(info.hasFinalizer)
        assertEquals(2, info.regularOperations.size)
        assertEquals(listOf("query", "execute"), info.regularOperations.map { it.name })
    }

    @Test
    fun `impl with both initialize_scope and finalize_scope is detected`() {
        val operations = listOf("initialize_scope", "finalize_scope", "query").map { makeOperation(it) }

        val info = detectScopedEffectMembers(operations)

        assertTrue(info.isScopedEffect)
        assertNotNull(info.initializeScope)
        assertNotNull(info.finalizeScope)
        assertTrue(info.hasFinalizer)
        assertEquals("initialize_scope", info.initializeScope!!.name)
        assertEquals("finalize_scope", info.finalizeScope!!.name)
        assertEquals(1, info.regularOperations.size)
        assertEquals("query", info.regularOperations[0].name)
    }

    @Test
    fun `finalize_scope without initialize_scope is still detected separately`() {
        val operations = listOf("finalize_scope", "query").map { makeOperation(it) }

        val info = detectScopedEffectMembers(operations)

        assertFalse(info.isScopedEffect) // No initialize_scope means not a scoped effect
        assertNull(info.initializeScope)
        assertNotNull(info.finalizeScope)
        assertTrue(info.hasFinalizer)
        assertEquals(1, info.regularOperations.size)
        assertEquals("query", info.regularOperations[0].name)
    }

    @Test
    fun `operation names similar to scope names are not confused`() {
        val operations = listOf(
            "initialize_scope_data",  // Not initialize_scope
            "finalize_scope_cleanup", // Not finalize_scope
            "init_scope",             // Not initialize_scope
            "scope_initialize"        // Not initialize_scope
        ).map { makeOperation(it) }

        val info = detectScopedEffectMembers(operations)

        assertFalse(info.isScopedEffect)
        assertNull(info.initializeScope)
        assertNull(info.finalizeScope)
        assertEquals(4, info.regularOperations.size)
    }

    @Test
    fun `constants have expected values`() {
        assertEquals("initialize_scope", ScopedEffectConstants.INITIALIZE_SCOPE)
        assertEquals("finalize_scope", ScopedEffectConstants.FINALIZE_SCOPE)
    }

    @Test
    fun `empty impl has no scope members`() {
        val info = detectScopedEffectMembers(emptyList())

        assertFalse(info.isScopedEffect)
        assertNull(info.initializeScope)
        assertNull(info.finalizeScope)
        assertTrue(info.regularOperations.isEmpty())
    }

    // ========================================
    // Signature Validation Tests
    // ========================================

    private fun makeOperationWithTypes(
        name: String,
        paramType: ParsedTypeAnnotation?,
        returnType: ParsedTypeAnnotation?
    ): ParsedPerformerOp {
        return ParsedPerformerOp(
            name = name,
            paramType = paramType,
            returnType = returnType,
            implementation = ParsedRecordLit(emptyList(), loc = dummyLoc),
            loc = dummyLoc
        )
    }

    private fun emptyRecord(): ParsedRecordType = ParsedRecordType(emptyList(), loc = dummyLoc)

    private fun recordWithFields(vararg fields: String): ParsedRecordType =
        ParsedRecordType(fields.map { ParsedRecordTypeEntry(it, ParsedNamedType("Int", dummyLoc), dummyLoc) }, loc = dummyLoc)

    @Test
    fun `initialize_scope with empty params and record return passes validation`() {
        val operation = makeOperationWithTypes(
            "initialize_scope",
            paramType = emptyRecord(),
            returnType = recordWithFields("conn", "pool")
        )

        val errors = validateInitializeScopeSignature(operation, hasFinalizeScope = true)

        assertTrue(errors.isEmpty(), "Expected no errors but got: $errors")
    }

    @Test
    fun `initialize_scope with non-empty params fails validation`() {
        val operation = makeOperationWithTypes(
            "initialize_scope",
            paramType = recordWithFields("x"),
            returnType = recordWithFields("conn")
        )

        val errors = validateInitializeScopeSignature(operation, hasFinalizeScope = true)

        assertEquals(1, errors.size)
        assertTrue(errors[0] is ScopedEffectValidationError.InitializeScopeWrongParams)
        assertTrue(errors[0].message.contains("wrong signature"))
    }

    @Test
    fun `initialize_scope with non-record return fails validation`() {
        val operation = makeOperationWithTypes(
            "initialize_scope",
            paramType = emptyRecord(),
            returnType = ParsedNamedType("Int", dummyLoc)
        )

        val errors = validateInitializeScopeSignature(operation, hasFinalizeScope = true)

        assertEquals(1, errors.size)
        assertTrue(errors[0] is ScopedEffectValidationError.InitializeScopeMustReturnRecord)
        assertTrue(errors[0].message.contains("must return a record"))
    }

    @Test
    fun `initialize_scope without finalize_scope fails validation`() {
        val operation = makeOperationWithTypes(
            "initialize_scope",
            paramType = emptyRecord(),
            returnType = recordWithFields("conn")
        )

        val errors = validateInitializeScopeSignature(operation, hasFinalizeScope = false)

        assertEquals(1, errors.size)
        assertTrue(errors[0] is ScopedEffectValidationError.InitializeScopeWithoutFinalize)
        assertTrue(errors[0].message.contains("requires finalize_scope"))
    }

    @Test
    fun `initialize_scope with empty record return passes`() {
        val operation = makeOperationWithTypes(
            "initialize_scope",
            paramType = emptyRecord(),
            returnType = emptyRecord()
        )

        val errors = validateInitializeScopeSignature(operation, hasFinalizeScope = true)

        assertTrue(errors.isEmpty())
    }

    @Test
    fun `finalize_scope with empty params and empty return passes validation`() {
        val operation = makeOperationWithTypes(
            "finalize_scope",
            paramType = emptyRecord(),
            returnType = emptyRecord()
        )

        val errors = validateFinalizeScopeSignature(operation, hasInitializeScope = true)

        assertTrue(errors.isEmpty(), "Expected no errors but got: $errors")
    }

    @Test
    fun `finalize_scope without initialize_scope fails validation`() {
        val operation = makeOperationWithTypes(
            "finalize_scope",
            paramType = emptyRecord(),
            returnType = emptyRecord()
        )

        val errors = validateFinalizeScopeSignature(operation, hasInitializeScope = false)

        assertEquals(1, errors.size)
        assertTrue(errors[0] is ScopedEffectValidationError.FinalizeScopeWithoutInitialize)
        assertTrue(errors[0].message.contains("requires initialize_scope"))
    }

    @Test
    fun `finalize_scope with non-empty params fails validation`() {
        val operation = makeOperationWithTypes(
            "finalize_scope",
            paramType = recordWithFields("x"),
            returnType = emptyRecord()
        )

        val errors = validateFinalizeScopeSignature(operation, hasInitializeScope = true)

        assertEquals(1, errors.size)
        assertTrue(errors[0] is ScopedEffectValidationError.FinalizeScopeWrongParams)
    }

    @Test
    fun `finalize_scope with non-unit return fails validation`() {
        val operation = makeOperationWithTypes(
            "finalize_scope",
            paramType = emptyRecord(),
            returnType = ParsedNamedType("Bool", dummyLoc)
        )

        val errors = validateFinalizeScopeSignature(operation, hasInitializeScope = true)

        assertEquals(1, errors.size)
        assertTrue(errors[0] is ScopedEffectValidationError.FinalizeScopeMustReturnUnit)
        assertTrue(errors[0].message.contains("must return {}"))
    }

    @Test
    fun `validateScopedEffectSignatures validates all members`() {
        val initOp = makeOperationWithTypes(
            "initialize_scope",
            paramType = recordWithFields("x"),  // Wrong - should be empty
            returnType = ParsedNamedType("Int", dummyLoc)  // Wrong - should be record
        )
        val finalOp = makeOperationWithTypes(
            "finalize_scope",
            paramType = recordWithFields("y"),  // Wrong - should be empty
            returnType = recordWithFields("z")  // Wrong - should be empty
        )

        val info = ScopedEffectInfo(
            initializeScope = initOp,
            finalizeScope = finalOp,
            regularOperations = emptyList()
        )

        val errors = validateScopedEffectSignatures(info)

        assertEquals(4, errors.size)
        assertTrue(errors.any { it is ScopedEffectValidationError.InitializeScopeWrongParams })
        assertTrue(errors.any { it is ScopedEffectValidationError.InitializeScopeMustReturnRecord })
        assertTrue(errors.any { it is ScopedEffectValidationError.FinalizeScopeWrongParams })
        assertTrue(errors.any { it is ScopedEffectValidationError.FinalizeScopeMustReturnUnit })
    }

    // ========================================
    // Scope Type Extraction Tests
    // ========================================

    private fun simpleTypeConverter(annotation: ParsedTypeAnnotation): MezzeType {
        return when (annotation) {
            is ParsedRecordType -> {
                val fields = annotation.entries.associate { entry ->
                    entry.name to MezzeType.IntType
                }
                MezzeType.RecordType(fields)
            }
            is ParsedNamedType -> {
                when (annotation.name) {
                    "Int" -> MezzeType.IntType
                    "Str" -> MezzeType.StringType
                    "Bool" -> MezzeType.BoolType
                    else -> MezzeType.VarType(TypeVar.fresh())
                }
            }
            else -> MezzeType.VarType(TypeVar.fresh())
        }
    }

    @Test
    fun `extractScopeTypeFromAnnotation extracts record type`() {
        val operation = makeOperationWithTypes(
            "initialize_scope",
            paramType = emptyRecord(),
            returnType = recordWithFields("pool", "conn")
        )

        val scopeType = extractScopeTypeFromAnnotation(operation, ::simpleTypeConverter)

        assertNotNull(scopeType)
        assertTrue(scopeType is MezzeType.RecordType)
        val recordType = scopeType as MezzeType.RecordType
        assertTrue("pool" in recordType.fields)
        assertTrue("conn" in recordType.fields)
    }

    @Test
    fun `extractScopeTypeFromAnnotation returns null for non-record type`() {
        val operation = makeOperationWithTypes(
            "initialize_scope",
            paramType = emptyRecord(),
            returnType = ParsedNamedType("Int", dummyLoc)
        )

        val scopeType = extractScopeTypeFromAnnotation(operation, ::simpleTypeConverter)

        assertNull(scopeType)
    }

    @Test
    fun `extractScopeTypeFromAnnotation returns null when no return type annotation`() {
        val operation = makeOperationWithTypes(
            "initialize_scope",
            paramType = emptyRecord(),
            returnType = null
        )

        val scopeType = extractScopeTypeFromAnnotation(operation, ::simpleTypeConverter)

        assertNull(scopeType)
    }

    @Test
    fun `withScopeType creates new info with scope type`() {
        val info = ScopedEffectInfo(
            initializeScope = makeOperation("initialize_scope"),
            finalizeScope = null,
            regularOperations = emptyList(),
            scopeType = null
        )

        val scopeType = MezzeType.RecordType(mapOf("x" to MezzeType.IntType))
        val updated = info.withScopeType(scopeType)

        assertNull(info.scopeType)
        assertNotNull(updated.scopeType)
        assertEquals(scopeType, updated.scopeType)
    }
}
