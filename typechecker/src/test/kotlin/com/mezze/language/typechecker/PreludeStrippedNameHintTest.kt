package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * Issue #220: when a user calls a name like `fold {…}` bare and the
 * typechecker reports it as undefined, the error message should mention
 * that the name is available as a method on stdlib types — pointing the
 * user at `<receiver>.fold {…}` or an explicit qualified import. Without
 * this hint, the prelude's deliberate binding-stripping (to avoid name
 * collisions like option::fold vs seq::fold) feels like a bug.
 */
class PreludeStrippedNameHintTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    /** Run with prelude on, return the thrown error message (or null on success). */
    private fun checkExpectingError(code: String): String? {
        val parsed = parser.parseParsedModule(code, "test.mz")
        return try {
            typeChecker.typeCheck(parsed, Paths.get("."), enablePrelude = true)
            null
        } catch (e: TypeCheckError) {
            e.message
        }
    }

    @Test
    fun `bare fold call gets method-syntax hint`() {
        val msg = checkExpectingError(
            "let r = fold { xs = [1,2,3], init = 0, f = {acc, x} -> acc }"
        ) ?: fail("expected typecheck failure for bare 'fold' call")

        assertTrue(msg.contains("Undefined variable or function: fold"),
            "expected unresolved-name preface, got: $msg")
        assertTrue(msg.contains("available as a method"),
            "expected method-syntax hint, got: $msg")
    }

    @Test
    fun `bare size call gets ability-method hint`() {
        // `size` is now a method on the `Sized` ability — the typechecker
        // recognizes it and suggests Sized.size syntax. This is a stricter
        // hint than the generic "available as a method" carrier list, but
        // it covers the same UX intent.
        val msg = checkExpectingError("let n = size { xs = [1,2,3] }")
            ?: fail("expected typecheck failure for bare 'size' call")
        assertTrue(
            msg.contains("Sized.size") || msg.contains("available as a method"),
            "expected Sized.size hint, got: $msg"
        )
    }

    @Test
    fun `truly unknown name does not get method hint`() {
        // Make sure we don't slap the hint on every unresolved name.
        val msg = checkExpectingError("let r = totally_made_up_function {}")
            ?: fail("expected failure for unknown name")
        assertTrue(msg.contains("Undefined variable or function"))
        assertFalse(msg.contains("available as a method"),
            "should not suggest method syntax for non-stdlib names, got: $msg")
    }
}
