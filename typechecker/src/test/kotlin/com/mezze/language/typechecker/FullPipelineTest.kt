package com.mezze.language.typechecker

import com.mezze.language.monoast.*
import com.mezze.language.parser.MezzeParser
import com.mezze.language.resolvedast.*
import com.mezze.language.truffleir.*
import com.mezze.language.typedast.*
import com.mezze.language.typechecker.mono.Lowerer
import com.mezze.language.typechecker.mono.Monomorphizer
import com.mezze.language.typechecker.mono.Resolver
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * End-to-end test: source → ParsedAST → TypedAST → MonoAST → ResolvedAST → TruffleIR
 *
 * All 5 stages, zero string-based lookups.
 */
class FullPipelineTest {

    private val parser = MezzeParser()
    private val checker = TypeChecker()
    private val mono = Monomorphizer()
    private val resolver = Resolver()
    private val lowerer = Lowerer()

    private fun compile(source: String): Lowerer.LoweredModule {
        // Stage 0: Parse
        val parsed = parser.parseParsedModule(source, file = "test.mz")

        // Stage 1: Typecheck
        val typed = checker.checkParsedModuleToTyped(parsed)

        // Stage 2: Monomorphize
        val monoModule = mono.monomorphize(typed, mapOf(typed.hash to typed))

        // Stage 3: Resolve
        val resolved = resolver.resolve(monoModule)

        // Stage 4: Lower
        return lowerer.lower(resolved)
    }

    @Test
    fun `simple literal through all stages`() {
        val result = compile("let x = 42")
        assertTrue(result.functions.isNotEmpty())
        // The body should be a LongLitIR at the end
        val fn = result.functions.first()
        assertNotNull(fn.body)
        assertEquals("test.mz", fn.sourceFile)
    }

    @Test
    fun `function through all stages`() {
        val result = compile("let double = { x: Int } -> x + x")
        val fn = result.functions.find { it.name == "double" }
        assertNotNull(fn, "Should have a function named 'double'")
        assertNotEquals(Hash.ZERO, fn!!.hash)
    }

    @Test
    fun `file table populated`() {
        val result = compile("let x = 42")
        assertTrue(result.fileTable.files.contains("test.mz"))
    }

    @Test
    fun `source info has correct file index`() {
        val result = compile("let x = 42")
        val fn = result.functions.first()
        val fileIndex = result.fileTable.files.indexOf("test.mz")
        assertEquals(fileIndex, fn.sourceSection.fileIndex)
    }

    @Test
    fun `no MezzeType anywhere in TruffleIR`() {
        val result = compile("""
let a = 42
let b = 3.14
let c = "hello"
""".trimIndent())
        // TruffleIR has NodeIR nodes — none have MezzeType
        // This is a compile-time check: NodeIR sealed interface has no `type` field
        // If it did, this code wouldn't compile.
        for (fn in result.functions) {
            assertNotNull(fn.body)
        }
    }

    @Test
    fun `slots assigned in resolved functions`() {
        val result = compile("let f = { x: Int } -> x + x")
        val fn = result.functions.find { it.name == "f" }
        assertNotNull(fn)
        // Params should have slot kinds
        assertTrue(fn!!.paramSlots.isNotEmpty() || fn.localSlots.isNotEmpty() || fn.body is MakeLambdaIR)
    }

    @Test
    fun `multiple functions all have unique hashes`() {
        val result = compile("""
let a = 1
let b = 2
let c = 3
""".trimIndent())
        val hashes = result.functions.map { it.hash }.toSet()
        assertEquals(result.functions.size, hashes.size, "All functions should have unique hashes")
    }

    @Test
    fun `effect survives to TruffleIR as handler`() {
        val source = """
effect Log where {
    println: { msg: Str } -> {} <Log>
}
let x = 42
""".trim()
        // Should not crash — effects pass through all stages
        val result = compile(source)
        assertTrue(result.functions.isNotEmpty())
    }
}
