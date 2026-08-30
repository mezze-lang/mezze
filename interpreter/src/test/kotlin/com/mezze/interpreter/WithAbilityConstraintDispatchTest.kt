package com.mezze.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * H4: a `with Ability 'a`-constrained function crashed at runtime with an uncached
 * CallIndirect (UnsupportedSpecializationException) when the ability had ≥2 impls —
 * even at a concrete, unambiguous call site — while working with a single impl.
 *
 * Root cause: a nominal record-type construction (`Ticket { n = 5 }`) passed into a
 * generic `{ x: 'a }` param left its result type an unsolved VarType (HasConstructor
 * only resolves variant/newtype constructors, never a record-type name). The argument
 * record's field type stayed a var, so monomorphization couldn't derive concrete
 * typeArgs, never resolved the `with Total 'a` constraint to an impl, and left
 * `x.total {}` as an indirect call. One impl was rescued by a sole-candidate fallback;
 * two impls were not.
 *
 * Fix: pin a record-type construction's result to its declared type at constraint
 * generation, so the concrete receiver type flows through and mono selects the right
 * impl by identity.
 */
class WithAbilityConstraintDispatchTest {

    private val prelude = """
ability Total for 'a where { total: { it: 'a } -> Int }
type Ticket = { n: Int }
type Order = { m: Int }
impl Total for Ticket where { total = { it } -> it.n }
impl Total for Order where { total = { it } -> it.m }
let dispatch: { x: 'a } with Total 'a -> Int = { x } -> x.total {}
""".trimIndent()

    /** The two-impl case that used to crash: concrete Ticket receiver runs Ticket's impl. */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun twoImplsConcreteTicketReceiver() {
        val src = prelude + "\n" +
            "let main = {} -> dispatch { x = Ticket { n = 5 } }\n" +
            "main {}\n"
        val r = MezzeTestHelper.run(src)
        assertEquals(5L, MezzeTestHelper.asLong(r))
    }

    /** Dispatch CORRECTNESS: the same `dispatch` on an Order runs Order's OWN impl. */
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun twoImplsConcreteOrderReceiver() {
        val src = prelude + "\n" +
            "let main = {} -> dispatch { x = Order { m = 42 } }\n" +
            "main {}\n"
        val r = MezzeTestHelper.run(src)
        assertEquals(42L, MezzeTestHelper.asLong(r))
    }
}
