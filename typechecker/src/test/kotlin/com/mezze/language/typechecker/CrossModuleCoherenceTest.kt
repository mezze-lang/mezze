package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Paths

/**
 * Cross-module coherence — imports must NOT silently overlap with local decls.
 *
 * Root bug (2026-08-09): DuplicateDeclarationValidator runs on module.decls
 * only. ImportContextMerger calls extendWithImpl for imports with zero
 * overlap check. User can silently:
 *   - Re-`impl Eq for Int` on top of stdlib's version
 *   - Re-`impl Int where { abs = ... }` overriding stdlib inherent methods
 *
 * These tests lock in the FIXED behavior — task #100.
 */
class CrossModuleCoherenceTest {
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
    // Ability impls
    // ------------------------------------------------------------------

    @Test
    fun `user cannot re-impl Eq for Int when stdlib already impls it`() {
        // std::abilities::compare::Eq is in prelude
        // std::int has impl Eq for Int
        // User's `impl Eq for Int` here must be rejected as overlapping.
        val err = checkFailsWithPrelude(
            """
            use std::abilities::compare::{ Eq }

            impl Eq for Int where {
              eq  = { it, r } -> False,
              neq = { it, r } -> True,
            }
            """
        )
        val msg = err.message!!
        // Either: overlap detected explicitly, OR orphan rule blocked it
        // (both are correct rejections — orphan is the stronger Rust rule).
        assertTrue(
            msg.contains("Overlapping impl") || msg.contains("already") || msg.contains("Orphan impl"),
            "expected overlap/orphan error, got: $msg"
        )
        assertTrue(msg.contains("Int"), "expected reference to Int, got: $msg")
    }

    @Test
    fun `user cannot re-impl Ord for Int when stdlib already impls it`() {
        val err = checkFailsWithPrelude(
            """
            use std::abilities::compare::{ Ord }

            impl Ord for Int where {
              lt = { it, r } -> False,
              le = { it, r } -> False,
              gt = { it, r } -> False,
              ge = { it, r } -> False,
            }
            """
        )
        assertTrue(
            err.message!!.contains("Overlapping impl") || err.message!!.contains("already") ||
                err.message!!.contains("Orphan impl"),
            "expected overlap/orphan error, got: ${err.message}"
        )
    }

    // ------------------------------------------------------------------
    // Inherent method impls
    // ------------------------------------------------------------------

    @Test
    fun `user cannot re-impl Int abs when stdlib already impls it`() {
        // std::int has impl Int where { abs = ..., is_even = ..., ... }
        val err = checkFailsWithPrelude(
            """
            impl Int where {
              abs = { it } -> 999,
            }
            """
        )
        val msg = err.message!!
        assertTrue(
            msg.contains("Overlapping") || msg.contains("Duplicate") ||
                msg.contains("already") || msg.contains("inherent impl") || msg.contains("Cannot define"),
            "expected overlap/duplicate/orphan error, got: $msg"
        )
    }

    // ------------------------------------------------------------------
    // Non-overlap should still work
    // ------------------------------------------------------------------

    @Test
    fun `user CAN impl Eq for a new user type`() {
        // Sanity: coherence check must not over-reject.
        val r = checkWithPrelude(
            """
            use std::abilities::compare::{ Eq }

            type UserId = { val: Int }

            impl Eq for UserId where {
              eq  = { it, r } -> it.val == r.val,
              neq = { it, r } -> it.val != r.val,
            }
            """
        )
        assertNotNull(r)
    }

    @Test
    fun `user CAN add a new inherent method to a user type`() {
        val r = checkWithPrelude(
            """
            type Box = { val: Int }

            impl Box where {
              double = { it } -> { val = it.val * 2 },
            }
            """
        )
        assertNotNull(r)
    }

    // ------------------------------------------------------------------
    // Extended cross-module scenarios
    // ------------------------------------------------------------------

    @Test
    fun `user cannot re-impl Sized for Str when stdlib already impls it`() {
        val err = checkFailsWithPrelude(
            """
            use std::abilities::sized::{ Sized }

            impl Sized for Str where {
              size     = { it } -> 0,
              is_empty = { it } -> True,
            }
            """
        )
        val msg = err.message!!
        assertTrue(
            msg.contains("Overlapping") || msg.contains("Orphan") || msg.contains("already"),
            "expected overlap/orphan error, got: $msg"
        )
    }

    @Test
    fun `user cannot re-impl Str inherent to_upper`() {
        val err = checkFailsWithPrelude(
            """
            impl Str where {
              to_upper = { it } -> "override",
            }
            """
        )
        val msg = err.message!!
        assertTrue(
            msg.contains("Overlapping") || msg.contains("Orphan") ||
                msg.contains("Duplicate") || msg.contains("Cannot define"),
            "expected overlap/orphan error, got: $msg"
        )
    }

    @Test
    fun `user CAN impl a stdlib ability for their newtype`() {
        // Newtype workaround for the orphan-rule case.
        val r = checkWithPrelude(
            """
            use std::abilities::compare::{ Eq }

            newtype UserId = Int

            impl Eq for UserId where {
              eq  = { it, r } -> True,
              neq = { it, r } -> False,
            }
            """
        )
        assertNotNull(r)
    }

    @Test
    fun `user CAN define a NEW ability for a stdlib type`() {
        // Ability declared locally + Int is foreign, but ability being local
        // means the orphan rule is satisfied.
        val r = checkWithPrelude(
            """
            ability Describe for 'a where { describe : { it: 'a } -> Str }

            impl Describe for Int where { describe = { it } -> "an int" }

            let s = 42.describe {}
            """
        )
        assertNotNull(r.getVariableType("s"))
    }

    @Test
    fun `user CAN define a NEW ability and impl it for own type`() {
        val r = checkWithPrelude(
            """
            type Box = { val: Int }
            ability Peek for 'a where { peek : { it: 'a } -> Int }

            impl Peek for Box where { peek = { it } -> it.val }

            let b : Box = { val = 7 }
            let n = b.peek {}
            """
        )
        assertNotNull(r.getVariableType("n"))
    }

    @Test
    fun `user CAN add a generic impl for a locally-declared ability`() {
        // Generic impl for a local ability — both ability and impl are local,
        // no orphan concern. Should just work.
        val r = checkWithPrelude(
            """
            ability MyWrap for 'a where { mw : { it: 'a } -> Option 'a }
            impl MyWrap for 'a where { mw = { it } -> Some { val = it } }

            let a = 42.mw {}
            let b = "hi".mw {}
            """
        )
        assertNotNull(r.getVariableType("a"))
        assertNotNull(r.getVariableType("b"))
    }

    @Test
    fun `user cannot add generic impl for stdlib ability - orphan rule`() {
        // Even generic impls are orphan-checked. Since Eq is a foreign ability
        // and 'a is not a local type, this must be rejected.
        val err = checkFailsWithPrelude(
            """
            use std::abilities::compare::{ Eq }

            impl Eq for 'a where {
              eq  = { it, r } -> True,
              neq = { it, r } -> False,
            }
            """
        )
        assertNotNull(err.message)
    }

    @Test
    fun `dispatch across imports resolves stdlib impl correctly`() {
        // Sanity check: normal usage — stdlib's Eq for Int works via .eq.
        val r = checkWithPrelude(
            """
            let a = 1 == 1
            let b = 1 == 2
            """
        )
        assertNotNull(r.getVariableType("a"))
        assertNotNull(r.getVariableType("b"))
    }

    @Test
    fun `no coherence issue when user defines totally unrelated ability`() {
        val r = checkWithPrelude(
            """
            ability Greet for 'a where { greet : { it: 'a } -> Str }
            type Person = { name: Str }
            impl Greet for Person where { greet = { it } -> it.name }

            let p : Person = { name = "Ada" }
            let g = p.greet {}
            """
        )
        assertNotNull(r.getVariableType("g"))
    }
}
