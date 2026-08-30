package com.mezze.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * Regression for BUG B / #333.
 *
 * A user ability method call `x.total {}` inside a GENERIC ability-bounded helper
 * (`let apply = { x: 'a } -> x.total {}`) crashed at interpreter runtime with
 * `Field not found: total` when the ability had >= 2 impls, while working with a
 * single impl.
 *
 * Root cause: a record literal flowing into the generic `x: 'a` param carries
 * `TypeIdentity.Anonymous`. Monomorphization's ability-constraint resolution matched
 * impl targets by nominal identity, so the identity-less record never matched the
 * nominal `impl Priced for Item` target and the impl ref was dropped — leaving
 * `x.total {}` un-rewritten (a raw field read). With a single impl a downstream
 * sole-candidate fallback rescued it; with two impls it declined and the read crashed.
 *
 * Fix: mono recovers the nominal impl by structurally matching the anonymous record
 * against the impls' DECLARED record shapes, gated on EXACTLY ONE impl of the ability
 * claiming the receiver's shape — so dispatch is provably to the right impl, never an
 * arbitrary choice.
 */
class AbilityMultiImplGenericDispatchTest {

    private val prelude = """
type Item = { name: Str, price: Int, qty: Int }
type Ticket = { id: Int }
ability Priced for 'a where { total: { it: 'a } -> Int }
impl Priced for Item where { total = { it } -> it.price * it.qty }
impl Priced for Ticket where { total = { it } -> it.id }
let apply = { x: 'a } -> x.total {}
""".trimIndent()

    /** The single-impl case has always worked — keep it as a guard. */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun oneImplGenericDispatch() {
        val src = """
type Item = { name: Str, price: Int, qty: Int }
ability Priced for 'a where { total: { it: 'a } -> Int }
impl Priced for Item where { total = { it } -> it.price * it.qty }
let apply = { x: 'a } -> x.total {}
let main = {} -> apply { x = { name = "x", price = 10, qty = 2 } }
main {}
""".trimIndent()
        val r = MezzeTestHelper.runWithOutput(src)
        assertEquals(20L, MezzeTestHelper.asLong(r.value()))
    }

    /** The bug: two impls of `Priced`, dispatched through the generic `apply`. */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun twoImplGenericDispatchToItem() {
        val src = prelude + "\n" +
            "let main = {} -> apply { x = { name = \"x\", price = 10, qty = 2 } }\n" +
            "main {}\n"
        val r = MezzeTestHelper.runWithOutput(src)
        // Item's impl: price * qty = 10 * 2 = 20.
        assertEquals(20L, MezzeTestHelper.asLong(r.value()))
    }

    /**
     * Proves dispatch CORRECTNESS, not just non-crashing: the same generic `apply`
     * called on a Ticket must run Ticket's OWN impl (return its id), not Item's.
     */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun twoImplGenericDispatchToTicket() {
        val src = prelude + "\n" +
            "let main = {} -> apply { x = { id = 7 } }\n" +
            "main {}\n"
        val r = MezzeTestHelper.runWithOutput(src)
        // Ticket's impl: id = 7.
        assertEquals(7L, MezzeTestHelper.asLong(r.value()))
    }
}
