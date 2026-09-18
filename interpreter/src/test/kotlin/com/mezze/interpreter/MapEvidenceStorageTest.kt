package com.mezze.interpreter

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * A Map buckets by the KEY's own instance — the Set work, applied to keys.
 *
 * `P` compares and hashes MOD 10, so `P.(1)` and `P.(11)` are one key by its
 * instance and two by their representation. A newtype is erased, so the structural
 * tiers see the payloads `1` and `11` and keep two entries where the key type says
 * there is one.
 *
 * The compiler picks the evidence form from the key type, at the impl, once — the
 * same decision the Set ops take. It reaches a DIRECT call (`map::insert { m = … }`)
 * but not yet a method-style chain (`m.insert { … }`), which goes through
 * `impl Map 'k 'v`'s inherent op and carries no spec evidence; that case stays
 * disabled in HashContainerInstanceTest with the reason recorded.
 */
class MapEvidenceStorageTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private val head = """
        use std::collections::map
        use std::abilities::compare::{ Eq, Hash }
        newtype P = Int
        impl Hash for P where {
          hash = { it } -> match it is P.(a) -> a % 10
        }
        impl Eq for P where {
          eq = { it, r } -> match it is
            P.(a) -> match r is
              P.(b) -> a % 10 == b % 10,
          neq = { it, r } -> match it is
            P.(a) -> match r is
              P.(b) -> a % 10 != b % 10
        }
    """.trimIndent()

    private fun run(body: String) = MezzeTestHelper.run("$head\n$body")

    @Test
    fun `two keys equal by the key's Eq are one entry`() {
        assertEquals(
            1L,
            run(
                """
                let m0 = map::insert { m = map::empty {}, k = P.(1), v = 1 }
                let m1 = map::insert { m = m0, k = P.(11), v = 2 }
                m1.size {}
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a lookup finds a key equal by the key's Eq`() {
        assertEquals(
            true,
            run(
                """
                let m0 = map::insert { m = map::empty {}, k = P.(1), v = 7 }
                map::contains_key { m = m0, k = P.(21) }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `keys that genuinely differ are still two entries`() {
        // The instance must be ASKED, not assumed to say yes.
        assertEquals(
            2L,
            run(
                """
                let m0 = map::insert { m = map::empty {}, k = P.(1), v = 1 }
                let m1 = map::insert { m = m0, k = P.(2), v = 2 }
                m1.size {}
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `the structural path is untouched`() {
        // Keys whose `Eq` is derived keep the specialised tiers.
        assertEquals(
            2L,
            MezzeTestHelper.run(
                """
                use std::collections::map
                let m0 = map::insert { m = map::empty {}, k = "a", v = 1 }
                let m1 = map::insert { m = m0, k = "b", v = 2 }
                m1.size {}
                """.trimIndent(),
            ),
        )
    }
}
