package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * Recursive / dependent ability usage:
 *   - an impl body that invokes the SAME ability on a wrapped sub-value,
 *   - a method-level dependent constraint (`… with Eq 'item …`) on an ability
 *     that ALSO carries a `having` associated type — the two features combined.
 *
 * ([MethodLevelAbilityConstraintTest] covers method-level constraints on their
 *  own; this adds the recursion + the dependent-constraint × associated-type mix.)
 */
class RecursiveAbilityConstraintTest {
    private lateinit var parser: MezzeParser
    private lateinit var tc: TypeChecker

    @BeforeEach fun s() {
        ModuleResolver.clearCache(); TypeChecker.clearCache()
        parser = MezzeParser(); tc = TypeChecker()
    }

    private fun err(b: String): String? =
        try { tc.typeCheck(parser.parseParsedModule(b, "t.mz"), Paths.get("."), enablePrelude = true); null }
        catch (e: TypeCheckError) { e.message }

    @Test
    fun `an impl body may invoke the same ability on a wrapped value`() {
        assertNull(err("""
            ability Dbg for 'a where { dbg: { it: 'a } -> Str }
            impl Dbg for Int where { dbg = { it } -> "n" }
            type IntBox = | IntBox { inner: Int } |
            impl Dbg for IntBox where {
              dbg = { it } -> match it is IntBox { inner } -> inner.dbg {}
            }
            pub let f = {} -> (IntBox { inner = 5 }).dbg {}
        """.trimIndent()), "an impl calling `.dbg {}` on its wrapped Int must typecheck")
    }

    @Test
    fun `method-level dependent constraint on a having-ability, concrete receiver`() {
        assertNull(err("""
            ability Box for 'c having 'item where { peek: { it: 'c } with Eq 'item -> 'item }
            impl Box for [Int] having 'item = Int where { peek = { it } -> 0 }
            pub let f = {} -> ([1]).peek {}
        """.trimIndent()), "Int has Eq, so peek's `with Eq 'item` is satisfied")
    }

    @Test
    fun `method-level dependent constraint on a having-ability, generic receiver`() {
        assertNull(err("""
            ability Box for 'c having 'item where { peek: { it: 'c } with Eq 'item -> 'item }
            impl Box for [Int] having 'item = Int where { peek = { it } -> 0 }
            pub let f = { xs } -> xs.peek {}
        """.trimIndent()), "a generic peek call carrying the Box + dependent Eq constraint must typecheck")
    }

    @Test
    fun `dependent constraint unsatisfied is rejected`() {
        // `Sink` has no `Eq` impl; peek's `with Eq 'item` cannot be satisfied.
        val e = err("""
            ability Box for 'c having 'item where { peek: { it: 'c } with Eq 'item -> 'item }
            type Sink = | Sink {} |
            impl Box for [Sink] having 'item = Sink where { peek = { it } -> Sink {} }
            pub let f = {} -> ([Sink {}]).peek {}
        """.trimIndent())
        assertNotNull(e, "Sink has no Eq, so peek's dependent constraint must fail")
    }
}
