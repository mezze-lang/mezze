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

    // ── …and through GENERIC callers, which nothing pinned before ─────────
    //
    // Every case above calls the container at a CONCRETE element type. The
    // interesting question is what happens when the element is a type variable
    // all the way down, because then the instance cannot be chosen at the call —
    // it has to arrive with the dictionary.
    //
    // known-bugs.md recorded that as still open on the strength of an
    // implementation attempt that was built and REVERTED: having the stdlib pass
    // the element's closures for EVERY type, which made `Set Int` instance-backed
    // and reordered its rendering. The goal that attempt was chasing is met by a
    // different mechanism — `with Eq, Hash` on the operations plus the evidence
    // natives — and these tests say so, so the claim cannot drift again.

    /** One generic layer: the helper is generic, the call site is concrete. */
    @Test
    fun `a generic caller honours the element's Eq`() {
        assertEquals(
            1L,
            run(
                """
                use std::collections::set
                let add_both: { s: Set 'a, x: 'a, y: 'a } with Eq 'a, Hash 'a -> Set 'a =
                  { s, x, y } -> set::insert { s = set::insert { s = s, x = x }, x = y }
                set::size { s = add_both { s = set::empty {}, x = P.(1), y = P.(11) } }
                """.trimIndent(),
            ),
        )
    }

    /** TWO generic layers, so the element is a variable in both frames. */
    @Test
    fun `two generic layers still honour the element's Eq`() {
        assertEquals(
            1L,
            run(
                """
                use std::collections::set
                let build: { xs: ['a] } with Eq 'a, Hash 'a -> Set 'a = { xs } -> set::from_seq { xs = xs }
                let count: { xs: ['a] } with Eq 'a, Hash 'a -> Int = { xs } -> set::size { s = build { xs = xs } }
                count { xs = [P.(1), P.(11), P.(21)] }
                """.trimIndent(),
            ),
        )
    }

    /** The SAME generic helper at a primitive must keep the primitive answer.
     *  This is the half the reverted attempt broke, and it is why the fix could
     *  never be "always pass the closures". */
    @Test
    fun `the same generic helper keeps distinct primitives distinct`() {
        assertEquals(
            3L,
            run(
                """
                use std::collections::set
                let build: { xs: ['a] } with Eq 'a, Hash 'a -> Set 'a = { xs } -> set::from_seq { xs = xs }
                let count: { xs: ['a] } with Eq 'a, Hash 'a -> Int = { xs } -> set::size { s = build { xs = xs } }
                count { xs = [1, 2, 3] }
                """.trimIndent(),
            ),
        )
    }

    /** A generic MAP key, two layers deep. */
    @Test
    fun `a generic Map caller honours the key's Eq`() {
        assertEquals(
            1L,
            run(
                """
                use std::collections::map
                let put2: { m: Map 'k Int, a: 'k, b: 'k } with Eq 'k, Hash 'k -> Map 'k Int =
                  { m, a, b } -> map::insert { m = map::insert { m = m, k = a, v = 1 }, k = b, v = 2 }
                let keyed: { a: 'k, b: 'k } with Eq 'k, Hash 'k -> Int =
                  { a, b } -> map::size { m = put2 { m = map::empty {}, a = a, b = b } }
                keyed { a = P.(1), b = P.(11) }
                """.trimIndent(),
            ),
        )
    }
}
