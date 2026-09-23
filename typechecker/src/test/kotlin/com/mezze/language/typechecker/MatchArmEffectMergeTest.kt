package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import kotlin.test.assertTrue

/**
 * Calling the result of a branch must perform whatever any branch's function
 * could perform.
 *
 * `(if c then effectful else pure) {}` is the shape. If the match's result type
 * stays a bare type variable, the call handler's `is FunctionType` effect-harvest
 * is skipped and the selected function's effects are LAUNDERED — a caller typed
 * pure could perform them untracked. `mergeArmFunctionEffects` exists to stop
 * that by surfacing a function type whose row is the UNION of the arms' rows.
 *
 * It approximates: the merged type takes its param/return from the FIRST arm, and
 * keeps only one arm's row variable. The comment argues both are safe because the
 * Equality constraints still unify every arm. These tests check the property that
 * argument is protecting, rather than the argument.
 */
class MatchArmEffectMergeTest {

    private lateinit var parser: MezzeParser

    @BeforeEach
    fun setUp() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
    }

    private fun check(src: String): String? {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        return try {
            TypeChecker().typeCheck(parser.parseParsedModule(src.trimIndent(), "arms.mz"), Paths.get("."))
            null
        } catch (e: Throwable) {
            e.message ?: e::class.simpleName ?: "rejected"
        }
    }

    private val effects = """
        effect Log where {
          log: {} -> {} <Log>
        }
        impl Log for Console where {
          log = {} -> {}
        }
        effect Mark where {
          mark: {} -> {} <Mark>
        }
        impl Mark for Marker where {
          mark = {} -> {}
        }
    """.trimIndent()

    @Test
    fun `an effectful arm is not laundered by a pure annotation`() {
        val msg = check(
            """
            $effects
            let noisy = {} -> Log.log {}
            let quiet = {} -> {}
            let pick : { c: Bool } -> {} = { c } -> (if c then noisy else quiet) {}
            let main = {} -> perform Console in pick { c = True }
        """
        )
        assertTrue(
            msg != null,
            "`pick` is annotated pure but calling the branch result can perform Log — " +
                "the arm's effect was laundered through the match result"
        )
    }

    @Test
    fun `the effectful arm being SECOND is caught too`() {
        // The merge takes its structural template from the FIRST arm. If that
        // choice leaked into which effects survive, putting the pure arm first
        // would hide the other arm's row.
        val msg = check(
            """
            $effects
            let noisy = {} -> Log.log {}
            let quiet = {} -> {}
            let pick : { c: Bool } -> {} = { c } -> (if c then quiet else noisy) {}
            let main = {} -> perform Console in pick { c = True }
        """
        )
        assertTrue(msg != null, "the effect of the SECOND arm was laundered")
    }

    @Test
    fun `two arms with DIFFERENT effects both survive the merge`() {
        // The merge keeps only one arm's row VARIABLE. Two arms carrying two
        // different concrete effects must still union: handling only one of them
        // must not make the expression pure.
        val msg = check(
            """
            $effects
            let noisy = {} -> Log.log {}
            let marked = {} -> Mark.mark {}
            let pick : { c: Bool } -> {} = { c } -> (if c then noisy else marked) {}
            let main = {} -> perform Console in perform Marker in pick { c = True }
        """
        )
        assertTrue(msg != null, "a union of two different arm effects was laundered")
    }

    @Test
    fun `two pure arms stay pure`() {
        // The control: nothing is invented when no arm has effects.
        val msg = check(
            """
            $effects
            let a = {} -> 1
            let b = {} -> 2
            let pick : { c: Bool } -> Int = { c } -> (if c then a else b) {}
            let main = {} -> pick { c = True }
        """
        )
        assertTrue(msg == null, "a branch of two pure functions was rejected: $msg")
    }
}
