package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.parser.MezzeParser
import com.mezze.language.ast.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*

/**
 * The `*` marker may not disappear.
 *
 * `performed` says "this body performed effects and handled them itself", and it
 * is the only thing distinguishing a fully-discharged body from a pure one —
 * their residual effect SETS are both empty. That makes it the disclosure a
 * caller reads to know a function is doing something out of sight, so anywhere
 * it can be silently dropped is a hole, not a rounding error.
 *
 * It used to be dropped by any operator: `discharges {} + 1` inferred as pure
 * where `discharges {}` was `<*>`. `+` is an ability operation, and the three
 * result constructions on that path unioned `effects` and `effectInstances`
 * while forgetting the flag. Effect NAMES propagated correctly throughout, which
 * is exactly why it went unnoticed — every test that watched a named effect
 * pass through an operator kept passing.
 */
class PerformedMarkerPropagationTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private val prelude = """
        use std::effects::log::{ println, Console, Log }

        let discharges = {} -> perform Console in do
          println { msg = "ran" }
          42
    """.trimIndent()

    private fun check(body: String) =
        typeChecker.typeCheck(parser.parseParsedModule("$prelude\n$body", "test.mz"), Paths.get("."))

    private fun rowOf(result: TypeCheckResult, name: String): MezzeType.EffectRow {
        val type = result.getVariableType(name)
        assertNotNull(type, "no type inferred for `$name`")
        assertTrue(type is MezzeType.FunctionType, "`$name` is not a function type: $type")
        val row = (type as MezzeType.FunctionType).effects
        assertNotNull(row, "`$name` has no effect row at all")
        return row!!
    }

    @Test
    fun `the marker survives every operator shape`() {
        val result = check(
            """
            let bare = {} -> discharges {}
            let plus = {} -> discharges {} + 1
            let times = {} -> discharges {} * 2
            let compared = {} -> discharges {} > 0
            let nested = {} -> (discharges {} + 1) * (discharges {} - 1)
            """.trimIndent()
        )
        for (name in listOf("bare", "plus", "times", "compared", "nested")) {
            assertTrue(
                rowOf(result, name).performed,
                "`$name` lost the `*` marker — an operator laundered it",
            )
        }
    }

    /** The marker travels through plain calls too, and keeps travelling. */
    @Test
    fun `the marker survives repeated call hops`() {
        val result = check(
            """
            let hop1 = {} -> discharges {}
            let hop2 = {} -> hop1 {}
            let hop3 = {} -> hop2 {}
            """.trimIndent()
        )
        for (name in listOf("hop1", "hop2", "hop3")) {
            assertTrue(rowOf(result, name).performed, "`$name` lost the `*` marker across a call")
        }
    }

    /** The guard against over-fixing: a NAMED effect must still propagate through
     *  an operator, and a genuinely pure computation must stay pure. */
    @Test
    fun `named effects still propagate and pure stays pure`() {
        val result = check(
            """
            let emits: {} -> Int <Log> = {} -> do
              println { msg = "x" }
              1
            let named_plus = {} -> emits {} + 1
            let pure_math = { a: Int } -> a + 1 * 2
            """.trimIndent()
        )
        val named = rowOf(result, "named_plus")
        assertTrue(named.effects.contains("Log"), "a named effect stopped propagating through `+`")

        val pureType = result.getVariableType("pure_math") as MezzeType.FunctionType
        assertFalse(
            pureType.effects?.performed ?: false,
            "a pure operator chain gained a `*` marker it never earned",
        )
    }
}
