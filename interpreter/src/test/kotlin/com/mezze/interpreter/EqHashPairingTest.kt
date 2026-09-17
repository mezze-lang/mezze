package com.mezze.interpreter

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `Eq` and `Hash` are ONE decision: written together, or neither.
 *
 * Every hash container buckets by `hash` and then compares by `eq`. If the two
 * disagree, two values that ARE equal land in different buckets, the equality is
 * never consulted, and the container silently keeps both. So the dangerous
 * combination is not a MISSING ability — `Hash` is derived for every type,
 * including newtypes, so nothing is ever missing — it is a CUSTOM one paired with
 * a DERIVED one, and it is dangerous in both directions:
 *
 *  - a hand-written `Eq` left with the derived structural hash, and
 *  - a hand-written `Hash` left with the derived structural equality.
 *
 * Both are rejected. The first by an explicit pairing check, the second by the
 * coherence rule (which, for a `Hash` impl, asks whether the type's `Eq` is the
 * derived one rather than whether `Hash` is — `Hash` derives universally, so
 * asking about itself would forbid every hand-written one).
 *
 * This is what makes "derive `Hash` for everything" safe. A newtype gets a hash
 * for free and is usable as a `Map` key with no boilerplate, and the only way to
 * end up with a hash that disagrees with its equality is to write one, which the
 * compiler refuses.
 */
class EqHashPairingTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private val modTenEq = """
        eq = { it, r } -> match it is
          P.(a) -> match r is
            P.(b) -> a % 10 == b % 10,
        neq = { it, r } -> match it is
          P.(a) -> match r is
            P.(b) -> a % 10 != b % 10
    """.trimIndent()

    @Test
    fun `a hand-written Eq without a Hash is rejected`() {
        val e = runCatching {
            MezzeTestHelper.run(
                """
                use std::abilities::compare::{ Eq }
                newtype P = Int
                impl Eq for P where {
                  $modTenEq
                }
                P.(1) == P.(11)
                """.trimIndent(),
            )
        }.exceptionOrNull()
        assertTrue(
            e?.message?.contains("writes its own `Eq` but not a `Hash`") == true,
            "expected the pairing error; got: ${e?.message}",
        )
    }

    @Test
    fun `a hand-written Eq with an agreeing Hash is accepted`() {
        assertEquals(
            true,
            MezzeTestHelper.run(
                """
                use std::abilities::compare::{ Eq, Hash }
                newtype P = Int
                impl Eq for P where {
                  $modTenEq
                }
                impl Hash for P where {
                  hash = { it } -> match it is
                    P.(a) -> a % 10
                }
                P.(1) == P.(11)
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a type that writes neither is untouched`() {
        // The common case: no boilerplate, and the derived pair agrees with itself.
        assertEquals("U.(1)", MezzeTestHelper.run("newtype U = Int\nU.(1).to_dbg {}"))
    }

    @Test
    fun `Hash is derived for every shape, including a newtype`() {
        val h = "use std::abilities::compare::{ Eq, Hash }"
        assertEquals(true, MezzeTestHelper.run("$h\n(5).hash {} == (5).hash {}"))
        assertEquals(false, MezzeTestHelper.run("$h\n(5).hash {} == (6).hash {}"))
        assertEquals(true, MezzeTestHelper.run("$h\n{ a = 1, b = \"x\" }.hash {} == { a = 1, b = \"x\" }.hash {}"))
        assertEquals(true, MezzeTestHelper.run("$h\nnewtype M = Int\nM.(5).hash {} == M.(5).hash {}"))
    }

    @Test
    fun `a newtype's derived hash agrees with its derived equality`() {
        // A newtype is erased to its payload at runtime, so the structural hash of
        // `M.(5)` IS the structural hash of `5`. That is exactly the pairing the
        // derived `Eq` would make — which is why deriving both is safe, and why
        // overriding only one is not.
        assertEquals(
            true,
            MezzeTestHelper.run("use std::abilities::compare::{ Hash }\nnewtype M = Int\nM.(5).hash {} == (5).hash {}"),
        )
    }

    @Test
    fun `a user Hash impl overrides the derived one`() {
        assertEquals(
            42L,
            MezzeTestHelper.run(
                """
                use std::abilities::compare::{ Eq, Hash }
                newtype M = Int
                impl Eq for M where {
                  eq = { it, r } -> match it is
                    M.(a) -> match r is
                      M.(b) -> a == b,
                  neq = { it, r } -> match it is
                    M.(a) -> match r is
                      M.(b) -> a != b
                }
                impl Hash for M where {
                  hash = { it } -> 42
                }
                M.(5).hash {}
                """.trimIndent(),
            ),
        )
    }
}
