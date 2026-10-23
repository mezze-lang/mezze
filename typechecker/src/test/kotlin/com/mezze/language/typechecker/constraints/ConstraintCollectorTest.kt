package com.mezze.language.typechecker.constraints

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.RowVar
import com.mezze.language.parsedast.Loc
import com.mezze.language.typechecker.dependencies.DefinitionRef
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/**
 * A collector carries every field of every result merged into it. It names each
 * field by hand, so a field added to [ConstraintResult] and not to the collector
 * would be silently dropped at every expression boundary — this test is what
 * notices.
 */
class ConstraintCollectorTest {

    private val loc = Loc("t.mz", 1, 1, 1, 2)
    private val other = Loc("t.mz", 2, 1, 2, 2)

    /** A result with every field set to something other than its default. */
    private val full = ConstraintResult(
        type = MezzeType.IntType,
        constraints = listOf(Constraint.Equality(MezzeType.IntType, MezzeType.IntType)),
        effects = setOf("Log"),
        effectInstances = setOf(MezzeType.EffectInstance("Log", emptyMap())),
        performed = true,
        effectRowVars = setOf(RowVar("r")),
        abilityConstraints = listOf(Constraint.AbilityRequired(com.mezze.language.ast.TypeVar("'a", 0, 1), "Show")),
        associatedTypeConstraints = listOf(Constraint.AssociatedTypeResolution("NumAdd", com.mezze.language.ast.TypeVar("'b", 0, 2), emptyMap())),
        undefinedVariables = listOf(ConstraintResult.UndefinedVariable("x", loc)),
        duplicateBindings = listOf(ConstraintResult.DuplicateBinding("y", loc)),
        tailCalls = setOf(loc),
        usedVariables = setOf("z"),
        unusedVariables = listOf(ConstraintResult.UnusedVariable("u", loc)),
        unusedHandlers = listOf(ConstraintResult.UnusedHandler("P", "E", loc)),
        patternWarnings = listOf("w"),
        referencedBindings = setOf(DefinitionRef.ByName(null, "f")),
        methodCallResolutions = mapOf((loc to "m") to ConstraintResult.MethodCallInfo(null, "m", null, "it")),
        callReturnTypes = mapOf(loc to MezzeType.IntType),
        expectedTypeAt = mapOf(loc to MezzeType.StringType),
        matchCaseBindings = mapOf(loc to mapOf("v" to MezzeType.IntType)),
        matchScrutineeTypes = mapOf(loc to MezzeType.FloatType),
        pendingClosureArgEffects = listOf(ConstraintResult.PendingClosureHarvest(MezzeType.IntType, setOf("Log"))),
        doBindingEffects = listOf(ConstraintResult.DoBindingEffect(MezzeType.IntType, setOf("Log"))),
        doBindingSolvedTypes = mapOf(loc to MezzeType.IntType),
        performFullyDischarged = mapOf(loc to true),
        lambdaParamTypes = mapOf(other to mapOf("p" to MezzeType.IntType)),
    )

    private val fields = ConstraintResult::class.java.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
        .onEach { it.isAccessible = true }

    @Test
    fun `the sample sets every field`() {
        val empty = ConstraintResult(type = MezzeType.IntType, constraints = emptyList())
        for (f in fields) {
            if (f.name == "type") continue
            assertNotEquals(f.get(empty), f.get(full), "add a value for ${f.name} to the sample — and to ConstraintCollector")
        }
    }

    @Test
    fun `a merged result comes back whole`() {
        val collector = ExpressionConstraintGenerator().ConstraintCollector()
        collector.merge(full)
        val out = collector.toResult(MezzeType.IntType)
        for (f in fields) {
            // A block's own do-bindings are its own; a child's do not cross into it.
            if (f.name == "doBindingEffects") continue
            assertEquals(f.get(full), f.get(out), "ConstraintCollector dropped ${f.name}")
        }
    }
}
