package com.mezze.interpreter

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * `group_by` buckets under the KEY's hand-written `Eq`/`Hash` (BUG 50, old
 * series — the three `group_by`s were the part left open).
 *
 * They grouped through a `LinkedHashMap` keyed by Java `equals`, so two keys the
 * author's `Eq` calls equal made two groups. The key function below maps
 * 10, 11, 20, 21 to points sharing an `x` in pairs: TWO groups under the author's
 * instance, four structurally.
 */
class GroupByEvidenceTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun eval(source: String): Any = MezzeTestHelper.run(source.trimIndent())

    private val instance = """
        use std::collections::list
        use std::collections::seq
        use std::collections::set
        use std::collections::map
        use std::abilities::compare::{ Eq, Ord, Hash }
        newtype P = { x: Int, y: Int }
        let px: { p: P } -> Int = { p } -> match p is
          P.({ x, y }) -> x
        impl Hash for P where {
          hash = { it } -> px { p = it }
        }
        impl Eq for P where {
          eq = { it, r } -> px { p = it } == px { p = r },
          neq = { it, r } -> if px { p = it } == px { p = r } then False else True,
        }
        let key: { n: Int } -> P = { n } -> P.({ x = n / 10, y = n })
    """

    @Test
    fun `seq group_by makes one group per key the author calls equal`() = assertEquals(
        "2:[10, 11]|[20, 21]",
        eval(
            """
            $instance
            let gs = seq::group_by { xs = [10, 11, 20, 21], f = { x } -> key { n = x } }
            let shown = seq::map { xs = gs, f = { x } -> "${'$'}(x.values.to_dbg {})" }
            "${'$'}(seq::size { xs = gs }.to_str {}):${'$'}(seq::join { xs = shown, sep = "|" })"
            """,
        ),
    )

    @Test
    fun `list group_by honours the key's instance`() = assertEquals(
        2L,
        eval(
            """
            $instance
            list::size { xs = list::group_by { xs = list::from_seq { xs = [10, 11, 20, 21] }, f = { x } -> key { n = x } } }
            """,
        ),
    )

    @Test
    fun `set group_by honours the key's instance`() = assertEquals(
        2L,
        eval(
            """
            $instance
            list::size { xs = set::group_by { s = set::from_seq { xs = [10, 11, 20, 21] }, f = { val } -> key { n = val } } }
            """,
        ),
    )

    @Test
    fun `a structural key still groups structurally`() = assertEquals(
        3L,
        eval(
            """
            $instance
            seq::size { xs = seq::group_by { xs = [1, 2, 1, 3], f = { x } -> x } }
            """,
        ),
    )
    /** The fourth sibling: the result is keyed by the VALUES, so the value's
     *  instance decides what one group is. */
    @Test
    fun `map group_by_value honours the value's instance`() = assertEquals(
        2L,
        eval(
            """
            $instance
            let m0 = map::empty {}
            let m1 = map::insert { m = m0, k = 1, v = key { n = 10 } }
            let m2 = map::insert { m = m1, k = 2, v = key { n = 11 } }
            let m3 = map::insert { m = m2, k = 3, v = key { n = 20 } }
            let m4 = map::insert { m = m3, k = 4, v = key { n = 21 } }
            map::size { m = map::group_by_value { m = m4 } }
            """,
        ),
    )
}
