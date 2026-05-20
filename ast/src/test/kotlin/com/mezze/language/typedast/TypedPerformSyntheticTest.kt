package com.mezze.language.typedast

import com.mezze.language.ast.MezzeType
import com.mezze.language.parsedast.Loc
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for the `synthetic` flag on TypedPerform.
 *
 * Default is `false` (user-written perform — keeps existing behavior).
 * Set to `true` by the do-bind desugar to suppress `<*>` on enclosing function signatures.
 */
class TypedPerformSyntheticTest {

    private val loc = Loc(file = "test", startLine = 1, startCol = 1, endLine = 1, endCol = 1)

    private fun mkPerform(synthetic: Boolean = false): TypedPerform =
        TypedPerform(
            handlers = emptyList(),
            body = TypedIntLit(42, MezzeType.IntType, loc),
            type = MezzeType.IntType,
            loc = loc,
            synthetic = synthetic
        )

    @Test
    fun `default synthetic is false`() {
        val p = mkPerform()
        assertFalse(p.synthetic, "Default `synthetic` must be false to preserve existing behavior")
    }

    @Test
    fun `synthetic = true is settable`() {
        val p = mkPerform(synthetic = true)
        assertTrue(p.synthetic)
    }

    // Note: TypedPerform round-trip serialization is blocked by a pre-existing
    // kotlinx-serialization issue (the `type` field on TypedExpr conflicts with
    // JSON's class discriminator). Synthetic-specific serialization is covered
    // indirectly by the typechecker integration tests in later phases.

    @Test
    fun `synthetic does not collide with equality of structurally same perform`() {
        // Two performs that are structurally the same except for `synthetic`
        // should compare unequal — important for hashing and caching.
        val a = mkPerform(synthetic = false)
        val b = mkPerform(synthetic = true)
        assertEquals(false, a == b, "synthetic should be part of equality")
    }
}
