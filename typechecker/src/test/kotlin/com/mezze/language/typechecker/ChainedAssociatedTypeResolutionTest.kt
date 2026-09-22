package com.mezze.language.typechecker

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.prettyPrintType
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * A `having 'out` result fed into ANOTHER `having 'out` operation must still resolve.
 *
 * The class-param numeric abilities (`NumAdd 'r for 'l having 'out`) resolve their
 * associated result through a deferred [Constraint.AssociatedTypeResolution]. When the
 * OUTER call's class-arg tuple is the INNER call's not-yet-resolved `'out`, the outer
 * resolution defers — and `finalizeDeferredAbilities` used to make a SINGLE pass over a
 * snapshot, so the outer was never retried once the inner resolved. Every binding below
 * contains only Int literals and must infer `Int`; before the fix the nested ones came
 * back as a bare type variable.
 *
 * `resolveDeferredInherentMethods` already ran to a fixpoint for exactly this reason
 * ("to_str only becomes concrete after abs resolves"); the ability path did not.
 */
class ChainedAssociatedTypeResolutionTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setUp() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun assertInfersInt(label: String, source: String, binding: String) {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        typeChecker = TypeChecker()
        val module = parser.parseParsedModule(source.trimIndent(), "chained.mz")
        val result = typeChecker.typeCheck(module, Paths.get("."))
        val t = result.getVariableType(binding)
        assertTrue(
            t is MezzeType.IntType,
            "$label: `$binding` should infer Int (every operand is an Int literal) " +
                "but inferred ${t?.let { prettyPrintType(it) } ?: "<none>"}"
        )
    }

    private val helpers = """
        let add_ten = { x } -> x + 10
        let square = { x } -> x * x
    """.trimIndent()

    // ---- the bug: a having-'out result feeding another having-'out operation ----

    @Test
    fun `nested call of two having-out helpers resolves to Int`() {
        assertInfersInt(
            "nested", """
            $helpers
            let r = square { x = add_ten { x = 5 } }
        """, "r"
        )
    }

    @Test
    fun `three-level having-out chain resolves to Int`() {
        assertInfersInt(
            "three-level", """
            $helpers
            let r = square { x = square { x = add_ten { x = 5 } } }
        """, "r"
        )
    }

    @Test
    fun `two pipe steps through having-out helpers resolve to Int`() {
        assertInfersInt(
            "two pipes", """
            $helpers
            let r = 5 |> add_ten { x = @ } |> square { x = @ }
        """, "r"
        )
    }

    @Test
    fun `arithmetic piped into a having-out helper resolves to Int`() {
        assertInfersInt(
            "arith piped", """
            $helpers
            let r = 5 + 3 |> square { x = @ }
        """, "r"
        )
    }

    // ---- controls: these already worked, and must keep working ----

    @Test
    fun `control - let-bound intermediate resolves to Int`() {
        assertInfersInt(
            "let-bound", """
            $helpers
            let a = add_ten { x = 5 }
            let r = square { x = a }
        """, "r"
        )
    }

    @Test
    fun `control - single pipe step resolves to Int`() {
        assertInfersInt(
            "single pipe", """
            $helpers
            let r = 5 |> square { x = @ }
        """, "r"
        )
    }

    @Test
    fun `control - direct literal arithmetic resolves to Int`() {
        assertInfersInt("direct", "let r = 1 + 2 * 3", "r")
        assertInfersInt("nested parens", "let r = (1 + 2) * (3 + 4)", "r")
    }

    @Test
    fun `control - outer is a plain operator resolves to Int`() {
        assertInfersInt(
            "operator outer", """
            $helpers
            let r = add_ten { x = 5 } * 2
        """, "r"
        )
    }

    @Test
    fun `control - an annotated helper still resolves to Int`() {
        assertInfersInt(
            "annotated inner", """
            let add_ten : { x: Int } -> Int = { x } -> x + 10
            let square = { x } -> x * x
            let r = square { x = add_ten { x = 5 } }
        """, "r"
        )
    }

    // ---- the feature the chain must not break: heterogeneous numerics ----

    @Test
    fun `control - Float mixing is unaffected`() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        typeChecker = TypeChecker()
        val module = parser.parseParsedModule(
            """
            let a = 1 + 2.0
            let b = 2.0 + 1
            """.trimIndent(), "chained.mz"
        )
        val result = typeChecker.typeCheck(module, Paths.get("."))
        for (n in listOf("a", "b")) {
            val t = result.getVariableType(n)
            assertTrue(
                t is MezzeType.FloatType,
                "$n should widen to Float, got ${t?.let { prettyPrintType(it) } ?: "<none>"}"
            )
        }
    }
}
