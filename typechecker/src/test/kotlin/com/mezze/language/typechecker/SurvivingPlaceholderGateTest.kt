package com.mezze.language.typechecker

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.TypeVar
import com.mezze.language.parsedast.Loc
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typedast.BindingRef
import com.mezze.language.typedast.Hash
import com.mezze.language.typedast.TypeScheme
import com.mezze.language.typedast.TypedLetDecl
import com.mezze.language.typedast.TypedModule
import com.mezze.language.typedast.TypedVar
import com.mezze.language.typechecker.validation.ElaborationInvariants
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * No placeholder reaches the emitted tree.
 *
 * The placeholder census counts MINTS — about 37,000 across `:typechecker:test`,
 * of which some 30,000 are `__native_*` intrinsics. That number invites the
 * conclusion that thirty thousand types are unknown in the compiled output. They
 * are not. A mint is repaired if anything downstream supplies the type, and for
 * an intrinsic something always does: `CallEmit` retypes an undetermined callee
 * from the call's own argument record and solved return, which is exactly the
 * information a native call has.
 *
 * MEASURED: 25,458 nodes walked across `:typechecker:test`, ZERO carrying a
 * placeholder. So the right question about a placeholder is not how many are
 * minted but whether any SURVIVES, and the answer today is none — which makes
 * this an invariant to hold rather than a population to shrink. It is the
 * property A.2 names as its endpoint, already true for this half.
 *
 * It was also completely unguarded. Disabling that one retyping rule puts 1,564
 * survivors into the tree, every one a `TypedVar`, and NOTHING in either suite
 * fails. A load-bearing rule with no test is one refactor away from being
 * removed as dead — so the last test here removes it in spirit and checks that
 * the guard notices.
 */
class SurvivingPlaceholderGateTest {

    private lateinit var parser: MezzeParser

    @BeforeEach
    fun setUp() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
    }

    /**
     * Programs chosen to REACH the position, not to be small. A native call is
     * the shape under test, and the arithmetic in every one of these bottoms out
     * in `__native_int_add` and its siblings inside the stdlib's impls.
     */
    private val programs = listOf(
        "arithmetic through the numeric impls" to """
            let total = { a: Int, b: Int } -> (a + b) * 2 - 1
            total { a = 3, b = 4 }
        """,
        "floats and comparison" to """
            let pick = { a: Float, b: Float } -> if a < b then a else b
            pick { a = 1.5, b = 2.5 }
        """,
        "a seq fold over the stdlib" to """
            use std::collections::seq
            let xs: [Int] = [3, 1, 2]
            xs.sort {}.fold { init = 0, f = { acc, x } -> acc + x }
        """,
        "an ability impl whose body is arithmetic" to """
            ability Area for 'a where { area: { it: 'a } -> Int }
            type Sq = { s: Int }
            impl Area for Sq where { area = { it } -> it.s * it.s }
            Area.area { it = { s = 4 } }
        """,
        "division, modulo and equality" to """
            let classify = { n: Int } -> if n % 2 == 0 then n / 2 else n * 3 + 1
            classify { n = 7 }
        """,
    )

    /**
     * Every module this program compiled, not just the one it wrote.
     *
     * This matters and was got wrong first time: `a + b` puts no native call in
     * the user's own declaration — it dispatches to an impl in `std/int.mz`, and
     * THAT is where `__native_int_add` is referenced. Checking only the user
     * module made the whole gate vacuous: it passed unchanged with the repair it
     * exists to protect switched off.
     */
    private fun survivorsIn(src: String): List<ElaborationInvariants.Finding> {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        val result = TypeChecker()
            .typeCheck(parser.parseParsedModule(src.trimIndent(), "surv.mz"), Paths.get("."), enablePrelude = true)
        val modules = listOfNotNull(result.typedModule) + result.loadedModules.values
        check(modules.isNotEmpty()) { "expected at least one emitted module" }
        return modules.flatMap { ElaborationInvariants.check(it) }
            .filter { it.kind == ElaborationInvariants.Kind.SURVIVING_PLACEHOLDER }
    }

    @Test
    fun `no emitted node carries a type the compiler gave up on`() {
        for ((label, src) in programs) {
            val survivors = survivorsIn(src)
            assertEquals(
                emptyList(), survivors,
                "$label: a placeholder reached the emitted tree. It is NOT ordinary polymorphism — " +
                    "anything downstream that reads a type variable as 'the specialization decides' " +
                    "will guess from it. Supply the type at the position named, or, if the position " +
                    "genuinely cannot know it, say why here.",
            )
        }
    }

    @Test
    fun `the guard reports a placeholder when there IS one`() {
        // Guards the guard, and this file needs it more than most: an empty
        // result means "nothing survived" only if the check would have SPOKEN
        // had something survived.
        //
        // Asserting "some finding of any kind appears" was tried and is wrong —
        // these programs are clean, so zero findings is the correct answer for
        // them and proves nothing either way. Hand a tree that definitely holds a
        // placeholder instead, and require it to be named.
        val placeholder = MezzeType.VarType(TypeVar.unknown("a deliberate probe"))
        val loc = Loc("probe.mz", 1, 1, 1, 1)
        val module = TypedModule(
            path = "probe.mz",
            hash = Hash.ZERO,
            imports = emptyList(),
            exports = emptyMap(),
            abilityDecls = emptyList(),
            abilityImpls = emptyList(),
            effectDecls = emptyList(),
            performerDecls = emptyList(),
            dependencies = emptyList(),
            decls = listOf(
                TypedLetDecl(
                    hash = Hash.ZERO,
                    name = "gave_up",
                    typeParams = emptyList(),
                    value = TypedVar("x", BindingRef.Local(0, 0), placeholder, loc),
                    scheme = TypeScheme(emptyList(), emptyList(), placeholder),
                    isPublic = false,
                    loc = loc,
                ),
            ),
        )

        val found = ElaborationInvariants.check(module)
            .filter { it.kind == ElaborationInvariants.Kind.SURVIVING_PLACEHOLDER }
        assertTrue(
            found.isNotEmpty(),
            "the check did not report a placeholder that is plainly there, so the " +
                "zero in the test above means nothing",
        )
        assertEquals("let:gave_up", found.single().where)
    }
}
