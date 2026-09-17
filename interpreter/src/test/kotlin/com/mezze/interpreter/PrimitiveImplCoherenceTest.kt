package com.mezze.interpreter

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a user module gets when it writes an impl for a primitive: an error, every
 * time, whichever ability it names.
 *
 * The orphan rule answers this one, and it answers it first — an impl must live in
 * the module that declares either the ability or one of the target types, and a
 * user module declares neither `Eq` nor `Int`. So the owner's rule ("a
 * hand-written `impl Eq` on a compiler-derived type is an error") is already
 * satisfied here, and the duplicate-instance rule only has work to do inside the
 * primitive's HOME module — see `PrimitiveHomeModuleCoherenceTest` in the
 * typechecker suite for that half.
 *
 * Worth pinning from this side anyway: this is the guarantee a user actually
 * experiences, and it is the reason a silently-ignored `impl Eq for Int` was never
 * reachable from user code even before the rule was extended.
 */
class PrimitiveImplCoherenceTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun errorFrom(src: String): String? =
        runCatching { MezzeTestHelper.run(src) }.exceptionOrNull()?.message

    @Test
    fun `a user module cannot implement Eq for a primitive`() {
        val e = errorFrom(
            """
            use std::abilities::compare::{ Eq }
            impl Eq for Int where {
              eq = { it, r } -> True,
              neq = { it, r } -> False
            }
            (1) == (2)
            """.trimIndent(),
        )
        assertTrue(
            e?.contains("Orphan impl: cannot implement imported ability 'Eq' for types [Int]") == true,
            "expected the orphan-impl error; got: $e",
        )
    }

    @Test
    fun `a user module cannot implement Hash for a primitive either`() {
        val e = errorFrom(
            """
            use std::abilities::compare::{ Hash }
            impl Hash for Str where {
              hash = { it } -> 0
            }
            "a"
            """.trimIndent(),
        )
        assertTrue(
            e?.contains("Orphan impl: cannot implement imported ability 'Hash' for types [Str]") == true,
            "expected the orphan-impl error; got: $e",
        )
    }

    @Test
    fun `a newtype over a primitive is untouched by the rule`() {
        // The exclusion the whole design rests on: a newtype is a type the module
        // DOES declare, it derives no `Eq`, and so its own is the one instance.
        assertEquals(
            true,
            MezzeTestHelper.run(
                """
                use std::abilities::compare::{ Eq, Hash }
                newtype Id = Int
                impl Hash for Id where {
                  hash = { it } -> match it is Id.(n) -> n
                }
                impl Eq for Id where {
                  eq = { it, r } -> True,
                  neq = { it, r } -> False
                }
                Id.(1) == Id.(2)
                """.trimIndent(),
            ),
        )
    }
}
