package com.mezze.interpreter

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `==` belongs to the built-in `Eq`, and naming your ability `Eq` does not make it
 * that one.
 *
 * The operator desugars to a `.eq` call, and the compiler folds `.eq` to its inline
 * comparison — so any ability declaring a method called `eq` would have that method
 * silently never run. A rule already rejected that, but it granted an exemption to
 * "the built-in operator abilities", and it decided membership by looking the
 * ability's NAME up in the well-known table. So a module declaring its own
 * `ability Eq` was handed the exemption meant for the stdlib's, regardless of what
 * it actually declared:
 *
 *     ability Eq for 'a where { eq: { it: 'a, r: 'a } -> Str }
 *     impl Eq for P where { eq = { it, r } -> "hijacked" }
 *     a == a                                    # measured: "hijacked", a Str
 *
 * Membership is decided by the declaration's content hash now, so a same-named
 * ability that is not the built-in one is caught by the rule it was escaping.
 */
class OperatorAbilityIdentityTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun errorFrom(src: String): String? =
        runCatching { MezzeTestHelper.run(src) }.exceptionOrNull()?.message

    @Test
    fun `an ability named Eq that is not Eq cannot capture the operator`() {
        val e = errorFrom(
            """
            ability Eq for 'a where { eq: { it: 'a, r: 'a } -> Str }
            type P = { x: Int }
            impl Eq for P where { eq = { it, r } -> "hijacked" }
            let a: P = { x = 1 }
            a == a
            """.trimIndent(),
        )
        assertTrue(
            e?.contains("is a DIFFERENT ability that happens to share the name") == true,
            "expected the divergent-redeclaration error; got: $e",
        )
    }

    @Test
    fun `an ability under an unrelated name still gets the plain message`() {
        // Same rule, different mistake: nothing is being redeclared, a reserved
        // method name was simply reused. The advice differs accordingly.
        val e = errorFrom(
            """
            ability Weird for 'a where { eq: { it: 'a, r: 'a } -> Str }
            type P = { x: Int }
            impl Weird for P where { eq = { it, r } -> "captured" }
            let a: P = { x = 1 }
            a == a
            """.trimIndent(),
        )
        assertTrue(
            e?.contains("Rename the method to a name that is not a built-in operator") == true,
            "expected the reserved-method-name error; got: $e",
        )
    }

    @Test
    fun `the built-in Eq still owns the operator`() {
        // The exemption still does its job for the ability it exists for: a record
        // of comparable fields derives `Eq` and `==` answers with it.
        assertEquals(true, MezzeTestHelper.run("type P = { x: Int }\nlet a: P = { x = 1 }\na == a"))
        assertEquals(
            false,
            MezzeTestHelper.run("type P = { x: Int }\nlet a: P = { x = 1 }\nlet b: P = { x = 2 }\na == b"),
        )
    }

    @Test
    fun `a module redeclaring Eq identically is still the built-in Eq`() {
        // Identity is the content hash, so a byte-for-byte redeclaration IS the
        // built-in — it keeps the exemption, and a newtype impl under it works.
        assertEquals(
            true,
            MezzeTestHelper.run(
                """
                ability Eq for 'a where {
                    eq: {it: 'a, r: 'a} -> Bool,
                    neq: {it: 'a, r: 'a} -> Bool
                }
                ability Hash for 'a where { hash: {it: 'a} -> Int }
                newtype Id = Int
                impl Hash for Id where {
                    hash: {it: Id} -> Int = {it} ->
                        match it is
                            Id.(n) -> n
                }
                impl Eq for Id where {
                    eq: {it: Id, r: Id} -> Bool = {it, r} -> True,
                    neq: {it: Id, r: Id} -> Bool = {it, r} -> False
                }
                Id.(1) == Id.(2)
                """.trimIndent(),
            ),
        )
    }
}
