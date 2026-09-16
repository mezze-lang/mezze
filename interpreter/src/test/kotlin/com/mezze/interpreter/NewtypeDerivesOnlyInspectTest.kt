package com.mezze.interpreter

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The design rule: **a newtype derives `Inspect` and NOTHING else.** `Eq`, `Ord`,
 * `Hash`, codecs and every other ability must be a hand-written `impl`.
 *
 * A newtype is a DISTINCT type from what it wraps, so wrapping an `Int` does not
 * make it ordered, comparable or hashable — the author says what those mean.
 * Deriving structurally would silently give the WRAPPED type's semantics, which
 * is the exact family of defects this effort kept finding: BUG 7 (a fused `Min`
 * substituting a primitive `<`), BUG 10 (the `_by` sinks discarding the
 * comparator), BUG 11 (a generic `with Ord` body using the wrapped order). In
 * every one a newtype whose instance disagreed with its payload produced a
 * plausible WRONG ANSWER rather than an error.
 *
 * The rule lives in `DerivabilityRules.canDeriveAbilityStructurally`:
 *
 *     if (isNewtype && !isInspect(abilityHash)) return false
 *
 * The enabled cases below confirm it holds where the ability is reached through
 * an OPERATOR. The disabled ones are BUG 13: the collection natives reach the
 * same abilities WITHOUT going through that predicate, so they compare
 * structurally on a type that has no `Eq` at all.
 */
class NewtypeDerivesOnlyInspectTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private val userId = "newtype UserId = Int"

    // ── The rule, where it holds ──────────────────────────────────────────

    @Test
    fun `Inspect is the one ability a newtype gets for free`() {
        assertEquals("UserId.(1)", MezzeTestHelper.run("$userId\nUserId.(1).to_dbg {}"))
    }

    @Test
    fun `equality is rejected without a hand-written Eq`() {
        val e = runCatching { MezzeTestHelper.run("$userId\nUserId.(1) == UserId.(1)") }.exceptionOrNull()
        assertTrue(
            e?.message?.contains("Cannot use '=='") == true,
            "a newtype must NOT derive Eq; got: ${e?.message}",
        )
    }

    @Test
    fun `ordering is rejected without a hand-written Ord`() {
        val e = runCatching { MezzeTestHelper.run("$userId\nUserId.(1) < UserId.(2)") }.exceptionOrNull()
        assertTrue(
            e?.message?.contains("Cannot use '<'") == true,
            "a newtype must NOT derive Ord; got: ${e?.message}",
        )
    }

    // ── BUG 13: the collections do not honour the rule ────────────────────

    @Test
    @Disabled(
        "BUG 13 — a Set DEDUPES two newtype values with no Eq impl, comparing " +
            "structurally. `==` on the same type is correctly rejected, so the " +
            "collection natives reach Eq without going through " +
            "DerivabilityRules. Needs Set/Map to require `Hash + Eq` (T1.4).",
    )
    fun `a Set rejects a newtype element that has no Eq`() {
        // Today this answers 1: the two values were compared structurally and
        // deduped, using an Eq the author never wrote.
        val e = runCatching {
            MezzeTestHelper.run(
                """
                $userId
                use std::collections::set
                set::from_seq { xs = [UserId.(1), UserId.(1)] }.size {}
                """.trimIndent(),
            )
        }.exceptionOrNull()
        assertTrue(e != null, "a Set of a newtype with no Eq must not typecheck")
    }

    @Test
    @Disabled("BUG 13 — as above: `contains` answers true using an Eq that does not exist.")
    fun `a Set rejects contains on a newtype element that has no Eq`() {
        val e = runCatching {
            MezzeTestHelper.run(
                """
                $userId
                use std::collections::set
                set::from_seq { xs = [UserId.(1)] }.contains { x = UserId.(1) }
                """.trimIndent(),
            )
        }.exceptionOrNull()
        assertTrue(e != null, "Set.contains on a newtype with no Eq must not typecheck")
    }

    @Test
    @Disabled("BUG 13 — as above, for a Map KEY.")
    fun `a Map rejects a newtype key that has no Eq`() {
        val e = runCatching {
            MezzeTestHelper.run(
                """
                $userId
                use std::collections::map
                map::empty {}.insert { k = UserId.(1), v = 1 }.size {}
                """.trimIndent(),
            )
        }.exceptionOrNull()
        assertTrue(e != null, "a Map keyed by a newtype with no Eq must not typecheck")
    }

    @Test
    @Disabled(
        "BUG 13, codec flavour — encoding a newtype with no Encode impl fails at " +
            "RUNTIME (\"Expected record for field 'encode', got: Long\") rather " +
            "than being rejected at compile time. Same rule, different surface: " +
            "the erased payload reaches the codec walk.",
    )
    fun `encoding a newtype without an Encode impl is a compile error not a crash`() {
        val e = runCatching {
            MezzeTestHelper.run(
                """
                $userId
                use std::codec::json
                UserId.(1).encode { config = json::json_default }
                """.trimIndent(),
            )
        }.exceptionOrNull()
        assertTrue(
            e?.message?.contains("TypeCheckError") == true,
            "must be rejected at compile time, not crash at runtime; got: ${e?.message}",
        )
    }
}
