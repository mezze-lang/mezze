package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * #78: a record literal must not list the same field name twice. Left unchecked,
 * one value silently wins; with conflicting types the wrong field becomes
 * readable (soundness-adjacent).
 */
class DuplicateRecordFieldTest {

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
    fun `duplicate field same type is rejected`() {
        val msg = error("let r = { a = 1, a = 2 }\nr")
        assertNotNull(msg)
        assertTrue(msg!!.contains("Duplicate field 'a'"), "got: $msg")
    }

    @Test
    fun `duplicate field conflicting type is rejected`() {
        val msg = error("let r = { a = 1, a = \"s\" }\nr")
        assertNotNull(msg)
        assertTrue(msg!!.contains("Duplicate field 'a'"), "got: $msg")
    }

    @Test
    fun `three fields with one duplicate is rejected`() {
        val msg = error("let r = { a = 1, b = 2, a = 3 }\nr")
        assertNotNull(msg)
        assertTrue(msg!!.contains("Duplicate field 'a'"), "got: $msg")
    }

    @Test
    fun `distinct fields are accepted`() {
        assertNull(error("let r = { a = 1, b = 2, c = 3 }\nr"))
    }

    @Test
    fun `single field is accepted`() {
        assertNull(error("let r = { a = 1 }\nr"))
    }
}
