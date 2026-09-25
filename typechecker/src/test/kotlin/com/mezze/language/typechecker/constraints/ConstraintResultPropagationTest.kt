package com.mezze.language.typechecker.constraints

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every field of [ConstraintResult] propagates through a merge.
 *
 * A `ConstraintResult` carries the solver's answers up the expression tree, and
 * it is combined by hand in many places: the collector in `ExpressionConstraints`
 * kept THREE parallel lists — the field declarations, `merge`, and `toResult` —
 * and a field missing from any one of them was dropped with no diagnostic.
 *
 * That was not hypothetical. MEASURED on one test class before this was fixed:
 * `expectedTypeAt` arrived at aggregation with ZERO entries where it should have
 * had 78, and `matchCaseBindings` with 5 where it should have had 6. The first
 * feeds `CompletionEngine.expectedTypeAtCursor`, which returns null on an empty
 * map — so type-directed completion was silently off. A new side table recorded
 * 10 entries and 0 survived, which is what sent me looking.
 *
 * [ConstraintResult.mergeOf] sets all of them, so the check below is a ratchet on
 * IT: add a field to the data class, forget it in `mergeOf`, and this fails by
 * name rather than showing up years later as a feature that quietly does nothing.
 */
class ConstraintResultPropagationTest {

    @Test
    fun `mergeOf assigns every field of ConstraintResult`() {
        // Reflection rather than a hand-written list, because a hand-written list
        // is the exact failure mode being guarded against.
        val declared = declaredFieldNames()
        val assigned = mergeOfAssignedFieldNames()

        val missing = declared - assigned
        assertTrue(
            missing.isEmpty(),
            "ConstraintResult.mergeOf does not assign: ${missing.sorted()}. " +
                "A field mergeOf misses is dropped wherever results are combined, with no error — " +
                "that is how `expectedTypeAt` reached aggregation empty. Add it to mergeOf.",
        )
    }

    /**
     * The data class's own fields, via JAVA reflection — `kotlin-reflect` is not
     * on the test classpath, and a data class's `val`s are backing fields here.
     * Synthetic members (`$stable`, `Companion`) are filtered out.
     */
    private fun declaredFieldNames(): Set<String> =
        ConstraintResult::class.java.declaredFields
            .filterNot { it.isSynthetic || java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .toSet()

    /**
     * The field names `mergeOf` assigns, read off the source.
     *
     * Reading SOURCE is deliberate and is the only honest way to ask this
     * question: the alternative is constructing two results with every field set
     * to a distinguishable non-default and diffing the merge, which needs a
     * hand-written value for each of the 26 fields — the same hand-written list
     * whose drift this test exists to catch.
     */
    private fun mergeOfAssignedFieldNames(): Set<String> {
        val source = locateConstraintSource()
        val body = source.substringAfter("fun mergeOf(").substringBefore("\n        }")
        return Regex("""^\s{12}(\w+)\s*=""", RegexOption.MULTILINE)
            .findAll(body)
            .map { it.groupValues[1] }
            .toSet()
    }

    private fun locateConstraintSource(): String {
        val root = System.getProperty("mezze.project.root")
            ?: error("mezze.project.root is not set; the build sets it for the test task")
        val file = java.io.File(
            root,
            "typechecker/src/main/kotlin/com/mezze/language/typechecker/constraints/Constraint.kt",
        )
        assertTrue(file.exists(), "expected Constraint.kt at ${file.absolutePath}")
        return file.readText()
    }

    @Test
    fun `the reflection and the source agree on how many fields there are`() {
        // Guards the guard: if the reflection or the regex stopped seeing the
        // fields, the test above would pass vacuously by comparing two empty sets.
        // VERIFIED to bite: deleting one assignment from mergeOf fails the test
        // above by name (`does not assign: [callReturnTypes]`).
        val declared = declaredFieldNames()
        assertTrue(declared.size >= 20, "expected ConstraintResult to have many fields, saw ${declared.size}")
        assertTrue("type" in declared && "constraints" in declared, declared.sorted().toString())
        assertEquals(declared, declared intersect mergeOfAssignedFieldNames())
    }
}
