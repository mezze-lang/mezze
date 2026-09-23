package com.mezze.language.typechecker

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.PrimitiveTypes
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A comparison yields a `Bool`, and comparing two different types does not
 * typecheck.
 *
 * This was a debug script whose catch block ended with the comment
 * "Don't rethrow - just print for debugging". It could not fail under any
 * circumstances: a typechecker that rejected `1 == 1` would have passed it.
 */
class SimpleComparisonTest {

    private lateinit var parser: MezzeParser

    @BeforeEach
    fun setUp() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
    }

    private fun typeOf(source: String, binding: String): MezzeType? {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        val result = TypeChecker().typeCheck(
            parser.parseParsedModule(source.trimIndent(), "test.mz"), Paths.get(".")
        )
        return result.getVariableType(binding)
    }

    private fun rejects(source: String): Boolean = try {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        TypeChecker().typeCheck(parser.parseParsedModule(source.trimIndent(), "test.mz"), Paths.get("."))
        false
    } catch (e: Throwable) {
        true
    }

    @Test
    fun `comparing two Ints yields a Bool`() {
        val t = typeOf("let same = 1 == 1\nsame", "same")
        assertNotNull(t, "`same` has no inferred type")
        assertTrue(
            t is MezzeType.BoolType || PrimitiveTypes.isBoolVariant(t!!),
            "a comparison must yield Bool in either of its representations, got $t"
        )
    }

    @Test
    fun `ordering comparisons also yield a Bool`() {
        val t = typeOf("let less = 1 < 2\nless", "less")
        assertTrue(
            t is MezzeType.BoolType || PrimitiveTypes.isBoolVariant(t!!),
            "`<` must yield Bool, got $t"
        )
    }

    @Test
    fun `comparing an Int with a Str does not typecheck`() {
        // The control: without it, the tests above pass against a typechecker
        // that accepts anything and calls the result Bool.
        if (!rejects("let bad = 1 == \"one\"\nbad")) {
            fail("`1 == \"one\"` typechecked — a comparison across two different types was accepted")
        }
    }
}
