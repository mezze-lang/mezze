package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * When a `[...]` sequence literal is annotated as `List 'a` (or vice versa), the
 * mismatch error should carry a hint pointing at the explicit conversion, since
 * Seq and List are distinct data structures that look interchangeable.
 */
class SeqListMismatchHintTest {

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

    private fun errorFor(src: String): String? {
        val parsed = parser.parseParsedModule(src, "sl.mz")
        val r = typeChecker.checkParsedModule(parsed, Paths.get("."), enablePrelude = false)
        return when (r) {
            is TypeChecker.TypeCheckResult.Failure -> r.errors.firstOrNull()?.message
            is TypeChecker.TypeCheckResult.Success -> null
        }
    }

    @Test
    fun `seq literal annotated as List gets a conversion hint`() {
        val msg = errorFor("let xs: List Int = [1, 2, 3]\nxs")
        assertNotNull(msg, "expected a mismatch error")
        assertTrue(msg!!.contains("Seq"), "message should mention Seq: $msg")
        assertTrue(msg.contains("list::from_seq"), "message should suggest list::from_seq: $msg")
    }

    @Test
    fun `matching Seq annotation still type-checks`() {
        assertNull(errorFor("let xs: [Int] = [1, 2, 3]\nxs"))
    }

    @Test
    fun `unrelated type mismatch does not get the seq hint`() {
        val msg = errorFor("let x: Int = \"s\"\nx")
        assertNotNull(msg)
        assertFalse(msg!!.contains("list::from_seq"), "unrelated mismatch should not carry the Seq hint: $msg")
    }
}
