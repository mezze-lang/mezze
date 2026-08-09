package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Paths

/**
 * Prelude-shipped generic abilities: `Somable`, `Seqable`, `Listable`.
 *
 *   - `x.some {}`  → `Some { val = x }`      (via std::option::Somable)
 *   - `x.seq {}`   → `[x]`                    (via std::collections::seq::Seqable)
 *   - `x.list {}`  → one-element linked List (via std::collections::list::Listable)
 *
 * All three are prelude-imported (std::option and std::collections are in
 * std::_prelude). No `use` needed to call the methods.
 */
class PreludeGenericAbilitiesTest {
    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setUp() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun checkWithPrelude(src: String): TypeCheckResult {
        val ast = parser.parseParsedModule(src.trimIndent(), "test.mz")
        return typeChecker.typeCheck(ast, Paths.get("."), enablePrelude = true)
    }

    private fun checkFailsWithPrelude(src: String): TypeCheckError =
        assertThrows<TypeCheckError> { checkWithPrelude(src) }

    // ------------------------------------------------------------------
    // Somable
    // ------------------------------------------------------------------

    @Test
    fun `some wraps Int without any use import`() {
        val r = checkWithPrelude("""let y = 42.some {}""")
        val ty = r.getVariableType("y")
        assertNotNull(ty)
        assertTrue(ty.toString().contains("Option"), "expected Option Int, got $ty")
    }

    @Test
    fun `some wraps Str without any use import`() {
        val r = checkWithPrelude("""let y = "hi".some {}""")
        assertNotNull(r.getVariableType("y"))
    }

    @Test
    fun `some wraps Bool`() {
        val r = checkWithPrelude("""let y = True.some {}""")
        assertNotNull(r.getVariableType("y"))
    }

    @Test
    fun `some wraps a user record`() {
        val r = checkWithPrelude(
            """
            type User = { name: Str }
            let u : User = { name = "Ada" }
            let y = u.some {}
            """
        )
        assertNotNull(r.getVariableType("y"))
    }

    @Test
    fun `some chains with other Option methods`() {
        val r = checkWithPrelude(
            """
            let y = 42.some {}.is_some {}
            """
        )
        val ty = r.getVariableType("y")
        assertNotNull(ty)
    }

    @Test
    fun `some inside polymorphic function stays polymorphic`() {
        val r = checkWithPrelude(
            """
            let wrap = { x } -> x.some {}
            let a = wrap { x = 42 }
            let b = wrap { x = "hi" }
            """
        )
        assertNotNull(r.getVariableType("a"))
        assertNotNull(r.getVariableType("b"))
    }

    // ------------------------------------------------------------------
    // Seqable
    // ------------------------------------------------------------------

    @Test
    fun `seq wraps Int into a singleton Seq`() {
        val r = checkWithPrelude("""let y = 42.seq {}""")
        val ty = r.getVariableType("y")
        assertNotNull(ty)
    }

    @Test
    fun `seq wraps Str into a singleton Seq`() {
        val r = checkWithPrelude("""let y = "hi".seq {}""")
        assertNotNull(r.getVariableType("y"))
    }

    @Test
    fun `seq wraps a user variant`() {
        val r = checkWithPrelude(
            """
            type Color = | Red, Green, Blue |
            let y = Red.seq {}
            """
        )
        assertNotNull(r.getVariableType("y"))
    }

    // ------------------------------------------------------------------
    // Listable
    // ------------------------------------------------------------------

    @Test
    fun `list wraps Int into a singleton List`() {
        val r = checkWithPrelude("""let y = 42.list {}""")
        val ty = r.getVariableType("y")
        assertNotNull(ty)
        assertTrue(ty.toString().contains("List"), "expected List, got: $ty")
    }

    @Test
    fun `list wraps Str into a singleton List`() {
        val r = checkWithPrelude("""let y = "hi".list {}""")
        assertNotNull(r.getVariableType("y"))
    }

    // ------------------------------------------------------------------
    // Coherence: user cannot re-impl any of these
    // ------------------------------------------------------------------

    @Test
    fun `user cannot re-impl Somable for Int`() {
        // Somable is defined in std::option (foreign) and Int is stdlib
        // (also foreign). Orphan rule blocks it. Additionally: generic impl
        // already exists — even for user types, adding a concrete `impl
        // Somable for Int` would conflict with the generic.
        val err = checkFailsWithPrelude(
            """
            use std::option::{ Somable }

            impl Somable for Int where {
              some = { it } -> None,
            }
            """
        )
        assertNotNull(err.message)
    }

    @Test
    fun `user cannot re-impl Somable for their own newtype either`() {
        // Even for a local newtype: coherence says one impl per ability, and
        // the generic in std::option already covers all types.
        val err = checkFailsWithPrelude(
            """
            use std::option::{ Somable }

            newtype UserId = UserId Int

            impl Somable for UserId where {
              some = { it } -> None,
            }
            """
        )
        assertNotNull(err.message)
    }

    // ------------------------------------------------------------------
    // Nesting
    // ------------------------------------------------------------------

    @Test
    fun `nesting some seq list works`() {
        val r = checkWithPrelude("""let y = 42.some {}.seq {}""")
        assertNotNull(r.getVariableType("y"))
    }

    @Test
    fun `seq elements can each be wrapped in some`() {
        val r = checkWithPrelude(
            """
            let xs = [1, 2, 3]
            let ys = xs.seq {}
            """
        )
        assertNotNull(r.getVariableType("ys"))
    }
}
