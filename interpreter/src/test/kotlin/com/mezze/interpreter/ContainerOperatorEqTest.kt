package com.mezze.interpreter

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * `==` on a container must answer what `==` on its elements answers.
 *
 * `P` compares MOD 10, so `P.(1) == P.(11)` is true by its own instance and false
 * structurally — which makes "did the container consult the element's instance?"
 * observable rather than a coin flip. Measured before this:
 *
 * | expression                              | answered  | correct |
 * |-----------------------------------------|-----------|---------|
 * | `P.(1) == P.(11)`                       | true      | true    |
 * | seq / list / nonemptylist / set         | true      | true    |
 * | map compared by KEY                     | true      | true    |
 * | **deque**                               | **false** | true    |
 * | **map compared by VALUE**               | **false** | true    |
 * | **sorted_set**                          | **false** | true    |
 * | **sorted_map compared by VALUE**        | **false** | true    |
 *
 * Each wrong one called a native that compared the runtime REPRESENTATION —
 * `MezzeDeque.equals`, `MezzeMap.equals`, `MezzeSortedSet.equals`,
 * `MezzeSortedMap.equals` — which is right only when the element's equality is the
 * derived structural one. A newtype erases to its payload, so the structural
 * compare saw `1` and `11` and said false. No crash: just a wrong answer, which is
 * the worse failure mode, and the reason this suite asserts the RIGHT answers
 * rather than pinning whatever the tree currently does.
 * ORDERING was never affected — `<` on a deque threads the element's comparator,
 * and a sorted container consults the comparator it carries from construction — so
 * the same two values compared correctly under `<` and wrongly under `==`. `Eq` is
 * the counterpart that was missing, and it is asked of `Eq` rather than of the
 * ordering: they are separate abilities, and a type may implement them
 * independently.
 */
class ContainerOperatorEqTest {

    private val modTen = """
        use std::abilities::compare::{ Eq, Ord, Hash }
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
        impl Ord for P where {
          lt = { it, r } -> match it is
            P.(a) -> match r is
              P.(b) -> a % 10 < b % 10,
          le = { it, r } -> match it is
            P.(a) -> match r is
              P.(b) -> a % 10 <= b % 10,
          gt = { it, r } -> match it is
            P.(a) -> match r is
              P.(b) -> a % 10 > b % 10,
          ge = { it, r } -> match it is
            P.(a) -> match r is
              P.(b) -> a % 10 >= b % 10
        }
    """.trimIndent()

    private fun run(tail: String) = MezzeTestHelper.run("$modTen\n$tail")

    @Test
    fun `the element itself`() {
        assertEquals(true, run("P.(1) == P.(11)"))
    }

    @Test
    fun `a deque honours the element's Eq`() {
        assertEquals(
            true,
            run(
                "use std::collections::deque\n" +
                    "deque::from_seq { xs = [P.(1)] } == deque::from_seq { xs = [P.(11)] }",
            ),
        )
    }

    @Test
    fun `a deque that genuinely differs is still unequal`() {
        assertEquals(
            false,
            run(
                "use std::collections::deque\n" +
                    "deque::from_seq { xs = [P.(1)] } == deque::from_seq { xs = [P.(12)] }",
            ),
        )
    }

    @Test
    fun `deque length still decides before any element is compared`() {
        assertEquals(
            false,
            run(
                "use std::collections::deque\n" +
                    "deque::from_seq { xs = [P.(1)] } == deque::from_seq { xs = [P.(11), P.(21)] }",
            ),
        )
    }

    @Test
    fun `a map honours the VALUE's Eq`() {
        assertEquals(
            true,
            run(
                "use std::collections::map\n" +
                    "map::insert { m = map::empty {}, k = 1, v = P.(1) } == " +
                    "map::insert { m = map::empty {}, k = 1, v = P.(11) }",
            ),
        )
    }

    @Test
    fun `a map whose values genuinely differ is still unequal`() {
        assertEquals(
            false,
            run(
                "use std::collections::map\n" +
                    "map::insert { m = map::empty {}, k = 1, v = P.(1) } == " +
                    "map::insert { m = map::empty {}, k = 1, v = P.(12) }",
            ),
        )
    }

    @Test
    fun `a map still honours the KEY's Eq`() {
        assertEquals(
            true,
            run(
                "use std::collections::map\n" +
                    "map::insert { m = map::empty {}, k = P.(1), v = 1 } == " +
                    "map::insert { m = map::empty {}, k = P.(11), v = 1 }",
            ),
        )
    }

    @Test
    fun `a key present in one map and absent from the other is unequal`() {
        assertEquals(
            false,
            run(
                "use std::collections::map\n" +
                    "map::insert { m = map::empty {}, k = 1, v = P.(1) } == " +
                    "map::insert { m = map::empty {}, k = 2, v = P.(1) }",
            ),
        )
    }

    @Test
    fun `a sorted set honours the element's Eq`() {
        assertEquals(
            true,
            run(
                "use std::collections::sorted_set\n" +
                    "sorted_set::from_seq { xs = [P.(1)] } == sorted_set::from_seq { xs = [P.(11)] }",
            ),
        )
    }

    @Test
    fun `a sorted map honours the VALUE's Eq`() {
        assertEquals(
            true,
            run(
                "use std::collections::sorted_map\n" +
                    "sorted_map::insert { m = sorted_map::empty {}, k = 1, v = P.(1) } == " +
                    "sorted_map::insert { m = sorted_map::empty {}, k = 1, v = P.(11) }",
            ),
        )
    }

    @Test
    fun `ordering was never the broken half and still is not`() {
        // `<` consulted the element's comparator all along — the same two values
        // compared correctly under `<` while `==` said false. Pinned so a future
        // change to either cannot quietly diverge from the other.
        assertEquals(
            true,
            run(
                "use std::collections::sorted_set\n" +
                    "sorted_set::from_seq { xs = [P.(1)] } < sorted_set::from_seq { xs = [P.(12)] }",
            ),
        )
        assertEquals(
            true,
            run(
                "use std::collections::deque\n" +
                    "deque::from_seq { xs = [P.(1)] } < deque::from_seq { xs = [P.(12)] }",
            ),
        )
    }

    @Test
    fun `the containers that already honoured it still do`() {
        assertEquals(true, run("[P.(1)] == [P.(11)]"))
        assertEquals(
            true,
            run(
                "use std::collections::list\n" +
                    "list::cons { head = P.(1), tail = list::empty {} } == " +
                    "list::cons { head = P.(11), tail = list::empty {} }",
            ),
        )
        assertEquals(
            true,
            run(
                "use std::collections::set\n" +
                    "set::from_seq { xs = [P.(1)] } == set::from_seq { xs = [P.(11)] }",
            ),
        )
    }

    @Test
    fun `primitive elements are unaffected`() {
        assertEquals(
            true,
            MezzeTestHelper.run(
                "use std::collections::deque\n" +
                    "deque::from_seq { xs = [1, 2] } == deque::from_seq { xs = [1, 2] }",
            ),
        )
        assertEquals(
            false,
            MezzeTestHelper.run(
                "use std::collections::deque\n" +
                    "deque::from_seq { xs = [1, 2] } == deque::from_seq { xs = [1, 3] }",
            ),
        )
        assertEquals(
            true,
            MezzeTestHelper.run(
                "use std::collections::map\n" +
                    "map::insert { m = map::empty {}, k = 1, v = \"a\" } == " +
                    "map::insert { m = map::empty {}, k = 1, v = \"a\" }",
            ),
        )
    }
}
