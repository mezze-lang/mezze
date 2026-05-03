package com.mezze.language.typechecker

import com.mezze.language.ast.MezzeType
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * Locks in: when production callers (CLI commands, LSP) typecheck with
 * the prelude enabled, the typechecker reaches user code and binding
 * types are concrete (not free type variables).
 *
 * Production callers — `mez check`, `mez dev`, `mez build`, `mez init`,
 * and the LSP's analyzeDocument — pass `enablePrelude = true`. Without
 * this, hover/inlay/check would render plain bindings as `'a`. Tests in
 * this file mirror that production setup.
 *
 * The `@Disabled` tests document the cross-module inherent-impl
 * inference bug tracked by issue #213. They will be enabled once the
 * inferred return type from a stdlib impl operation is plumbed through
 * the import boundary (today the importer rebuilds the methodType from
 * ParsedImplAbilityDecl with fresh TypeVars, dropping the body's
 * solved return type).
 */
class PreludeAlwaysOnTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun checkWithPrelude(code: String): TypeCheckResult {
        val parsed = parser.parseParsedModule(code, "test.mz")
        return typeChecker.typeCheck(parsed, Paths.get("."), enablePrelude = true)
    }

    private fun assertConcrete(result: TypeCheckResult, varName: String): MezzeType {
        val actual = result.getVariableType(varName)
            ?: fail("expected variable '$varName' to be type-checked; got null")
        assertFalse(actual is MezzeType.VarType,
            "expected '$varName' to have a concrete type, got VarType '${(actual as? MezzeType.VarType)?.variable?.name}'. Full: $actual")
        return actual
    }

    // ---- Plain literal bindings — work today ----

    @Test
    fun `Plain Int literal binding`() {
        val r = checkWithPrelude("let x = 5")
        assertEquals(MezzeType.IntType, assertConcrete(r, "x"))
    }

    @Test
    fun `Plain Str literal binding`() {
        val r = checkWithPrelude("""let s = "hello"""")
        assertEquals(MezzeType.StringType, assertConcrete(r, "s"))
    }

    @Test
    fun `Seq literal binding produces concrete element type`() {
        val r = checkWithPrelude("let xs = [1, 2, 3]")
        val actual = assertConcrete(r, "xs")
        assertTrue(actual is MezzeType.SeqType, "expected SeqType, got $actual")
        assertEquals(MezzeType.IntType, (actual as MezzeType.SeqType).elementType)
    }

    @Test
    fun `Record literal binding`() {
        val r = checkWithPrelude("""let r = { a = 1, b = "hi" }""")
        val actual = assertConcrete(r, "r")
        assertTrue(actual is MezzeType.RecordType, "expected RecordType, got $actual")
        val fields = (actual as MezzeType.RecordType).fields
        assertEquals(MezzeType.IntType, fields["a"])
        assertEquals(MezzeType.StringType, fields["b"])
    }

    @Test
    fun `Use std import does not leave binding as VarType`() {
        val r = checkWithPrelude("""
            use std::int
            let x = 42
        """.trimIndent())
        assertEquals(MezzeType.IntType, assertConcrete(r, "x"))
    }

    // ---- Inherent-impl method calls on stdlib types ----
    // Disabled until issue #213 lands. They define the target shape: when
    // the prelude loads `impl Int where { abs, ... }` from std::int, calling
    // `5.abs {}` should report Int, not 'a.

    @Test
    fun `Int abs returns concrete type`() {
        val r = checkWithPrelude("let n = 5.abs {}")
        assertEquals(MezzeType.IntType, assertConcrete(r, "n"))
    }

    @Test
    fun `Int is_even returns concrete type`() {
        val r = checkWithPrelude("let b = 4.is_even {}")
        assertEquals(MezzeType.BoolType, assertConcrete(r, "b"))
    }

    @Test
    fun `Str to_upper returns concrete type`() {
        val r = checkWithPrelude("""let u = "hello".to_upper {}""")
        assertEquals(MezzeType.StringType, assertConcrete(r, "u"))
    }

    @Test
    fun `Str length returns concrete type`() {
        val r = checkWithPrelude("""let n = "hi".size {}""")
        assertEquals(MezzeType.IntType, assertConcrete(r, "n"))
    }

    @Test
    fun `Seq length returns concrete type`() {
        val r = checkWithPrelude("let n = [1, 2, 3].size {}")
        assertEquals(MezzeType.IntType, assertConcrete(r, "n"))
    }

    @Test
    fun `Seq is_empty returns concrete type`() {
        val r = checkWithPrelude("let b = [1, 2, 3].is_empty {}")
        assertEquals(MezzeType.BoolType, assertConcrete(r, "b"))
    }

    @Test
    fun `Multi-line dot chain on Seq produces concrete type`() {
        val r = checkWithPrelude("""
            let nums = [1, 2, 3, 4, 5]
            let n = nums
                .size {}
        """.trimIndent())
        assertEquals(MezzeType.IntType, assertConcrete(r, "n"))
    }

    @Test
    fun `Seq first on Int seq returns Option Int`() {
        // The receiver-driven path: `[1,2,3].first {}` on a generic
        // `impl Seq 'a where { first }` should pin `'a := Int` at this
        // call site and produce `Option Int`.
        val r = checkWithPrelude("let h = [1, 2, 3].first {}")
        val actual = r.getVariableType("h")
            ?: fail("expected 'h' to be type-checked; got null")
        assertFalse(actual is MezzeType.VarType,
            "expected 'h' concrete, got VarType. Full: $actual")
        assertTrue(actual is MezzeType.TypeApplication,
            "expected TypeApplication, got ${actual::class.simpleName}: $actual")
        val app = actual as MezzeType.TypeApplication
        assertEquals("Option", app.constructor)
        assertEquals(listOf<MezzeType>(MezzeType.IntType), app.args,
            "expected Option [Int], got ${app.args}")
    }
}
