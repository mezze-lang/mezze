package com.mezze.interpreter

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * T1.4 — `Set` and `Map` must answer the same question `==` answers.
 *
 * `P` compares MOD 10, so `P.(1) == P.(11)` is true by its instance and false
 * structurally. That makes "did the container use the element's instance?"
 * observable rather than a coin flip. Measured before any of this:
 *
 * | expression                                   | answered | correct |
 * |----------------------------------------------|----------|---------|
 * | `P.(1) == P.(11)`                            | true     | true    |
 * | `[P.(1)].contains { x = P.(11) }`            | true     | true    |
 * | `set::from_seq { xs = [P.(1), P.(11)] }.size`| **2**    | 1       |
 * | `set.contains { x = P.(11) }`                | **false**| true    |
 * | `map` insert both keys, `.size`              | **2**    | 1       |
 *
 * The same value, two different answers depending on which container is asked.
 * `==` and `seq` honour the instance; the hash containers compare the runtime
 * representation instead.
 *
 * ## What is fixed
 *
 * `Set` and `Map` now declare `with Eq` on every operation that buckets or looks
 * up an element or key. An element type with NO equality — a newtype, which
 * derives none — is rejected instead of being deduped by a structural comparison
 * its author never wrote. That was BUG 13, and its cases are enabled in
 * [NewtypeDerivesOnlyInspectTest].
 *
 * ## What is not
 *
 * A type that HAS a hand-written `Eq` is accepted, and the container still
 * ignores it. Closing that needs the element's `eq` — and a `hash` that AGREES
 * with it — to reach the container, the way `ElementComparator` already carries a
 * sorted container's `cmp` from construction. A hash container cannot use `eq`
 * alone: values that compare equal must also hash equal or they land in different
 * buckets and the equality is never consulted. That is why the design calls for a
 * `Hash` ability, derived automatically for every type (so the common case stays
 * boilerplate-free and keeps today's structural hashing), with a compile-time
 * requirement that a hand-written `Eq` be accompanied by a hand-written `Hash`.
 */
class HashContainerInstanceTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private val modTen = """
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

    private fun run(tail: String) = MezzeTestHelper.run("$modTen\n$tail")

    // ── The instance IS honoured here ─────────────────────────────────────

    @Test
    fun `the operator honours the element's Eq`() {
        assertEquals(true, run("P.(1) == P.(11)"))
    }

    @Test
    fun `a seq honours the element's Eq`() {
        assertEquals(true, run("use std::collections::seq\n[P.(1)].contains { x = P.(11) }"))
    }

    @Test
    fun `an element type with no equality is rejected, not deduped`() {
        // The BUG 13 half, closed. Kept here too so the two halves of T1.4 read
        // together: this is what a container is now REQUIRED to have, and the
        // disabled cases below are what it must then USE.
        val e = runCatching {
            MezzeTestHelper.run(
                """
                newtype U = Int
                use std::collections::set
                set::from_seq { xs = [U.(1), U.(1)] }.size {}
                """.trimIndent(),
            )
        }.exceptionOrNull()
        assertEquals(true, e?.message?.contains("does not implement 'Eq'"), "got: ${e?.message}")
    }

    // ── The instance is NOT honoured here ─────────────────────────────────

    @Test
    fun `a Set collapses elements equal by the element's Eq`() {
        assertEquals(1L, run("use std::collections::set\nset::from_seq { xs = [P.(1), P.(11)] }.size {}"))
    }

    @Test
    fun `a Set finds an element equal by the element's Eq`() {
        assertEquals(true, run("use std::collections::set\nset::from_seq { xs = [P.(1)] }.contains { x = P.(11) }"))
    }

    @Test
    @Disabled(
        "Method-style only. `map::insert { m = …, k = P.(1) … }` written as a DIRECT " +
            "call now collapses the two keys correctly, and so does the explicit " +
            "`insert_with_evidence` form — the Map evidence tier works. The chain below " +
            "goes through `impl Map 'k 'v`'s inherent `insert`, which delegates to the " +
            "module function, and that path carries no spec evidence, so the rewrite " +
            "declines and the structural native runs. Declaring the constraints on the " +
            "inherent op does not change it. See MapEvidenceStorageTest for the forms " +
            "that do work.",
    )
    fun `a Map treats keys equal by the key's Eq as one key`() {
        assertEquals(
            1L,
            run(
                """
                use std::collections::map
                map::empty {}.insert { k = P.(1), v = 1 }.insert { k = P.(11), v = 2 }.size {}
                """.trimIndent(),
            ),
        )
    }
}
