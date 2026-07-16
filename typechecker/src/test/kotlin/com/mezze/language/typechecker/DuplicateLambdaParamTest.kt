package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/** #79: a lambda must not list the same parameter name twice. */
class DuplicateLambdaParamTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        System.setProperty("mezze.typecheck.strict", "true")
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun error(src: String): String? {
        val r = typeChecker.checkParsedModule(parser.parseParsedModule(src, "d.mz"), Paths.get("."), enablePrelude = false)
        return when (r) {
            is TypeChecker.TypeCheckResult.Failure -> r.errors.firstOrNull()?.message
            is TypeChecker.TypeCheckResult.Success -> null
        }
    }

    @Test
    fun `untyped duplicate param is rejected`() {
        val msg = error("let f = { x, x } -> x\nf { x = 1 }")
        assertNotNull(msg)
        assertTrue(msg!!.contains("Duplicate parameter 'x'"), "got: $msg")
    }

    @Test
    fun `typed duplicate param is rejected`() {
        val msg = error("let f = { x: Int, x: Int } -> x\nf { x = 1 }")
        assertNotNull(msg)
        assertTrue(msg!!.contains("Duplicate parameter 'x'"), "got: $msg")
    }

    @Test
    fun `distinct params are accepted`() {
        assertNull(error("let f = { x, y } -> x\nf { x = 1, y = 2 }"))
    }

    @Test
    fun `single param is accepted`() {
        assertNull(error("let f = { x } -> x\nf { x = 1 }"))
    }
}
