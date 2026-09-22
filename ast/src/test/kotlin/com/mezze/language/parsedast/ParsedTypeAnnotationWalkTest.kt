package com.mezze.language.parsedast

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The traversal contract for [ParsedTypeAnnotation].
 *
 * The cases that matter are the ones hand-rolled recursions got wrong: a
 * function type nests annotations in its effect row and its `with` clause, not
 * just in `paramType`/`returnType`, and a rewriter that stops at the arrow
 * returns those sub-trees untouched while reporting success.
 */
class ParsedTypeAnnotationWalkTest {

    private val loc = Loc("walk-test.mz", 1, 1, 1, 1)

    private fun named(name: String) = ParsedNamedType(name, loc)

    /**
     * `{ x: 'a } with Container 'c having 'item = 'a -> Str <Exception having 'error = 'a>`
     *
     * One written `'a` in four structurally distinct positions.
     */
    private fun fnWithEveryPosition(
        param: ParsedTypeAnnotation = named("'a"),
        typeArg: ParsedTypeAnnotation = named("'c"),
        assocBinding: ParsedTypeAnnotation = named("'a"),
        ret: ParsedTypeAnnotation = named("Str"),
        effectAssoc: ParsedTypeAnnotation = named("'a")
    ) = ParsedFunctionType(
        paramType = ParsedRecordType(listOf(ParsedRecordTypeEntry("x", param, loc)), loc = loc),
        returnType = ret,
        effects = ParsedEffectRow(
            effectAnnotations = listOf(
                ParsedEffectAnnotation("Exception", mapOf("'error" to effectAssoc), loc)
            ),
            loc = loc
        ),
        abilityConstraints = listOf(
            ParsedFnAbilityConstraint(
                abilityName = "Container",
                typeArgs = listOf(typeArg),
                associatedTypeBindings = mapOf("'item" to assocBinding),
                loc = loc
            )
        ),
        loc = loc
    )

    // ---- children() ----

    @Test
    fun `a function type's children include its effect-row and with-clause annotations`() {
        val children = fnWithEveryPosition().children()
        // source order: { params } with Constraints -> Return <Effects>
        assertEquals(
            listOf("{x}", "'c", "'a", "Str", "'a"),
            children.map { if (it is ParsedRecordType) "{x}" else (it as ParsedNamedType).name },
            "children() must descend into abilityConstraints and effects, in surface order"
        )
    }

    @Test
    fun `every nesting variant reports its direct children`() {
        val a = named("A")
        val b = named("B")
        val cases: List<Pair<ParsedTypeAnnotation, List<ParsedTypeAnnotation>>> = listOf(
            named("Int") to emptyList(),
            ParsedQualifiedType(listOf("m"), "T", listOf(a, b), loc) to listOf(a, b),
            ParsedTypeApp("Result", listOf(a, b), loc) to listOf(a, b),
            ParsedRecordType(
                listOf(ParsedRecordTypeEntry("x", a, loc), ParsedRecordTypeEntry("y", b, loc)), loc = loc
            ) to listOf(a, b),
            ParsedVariantType(
                listOf(
                    ParsedVariantTypeCtor("Some", a, loc),
                    ParsedVariantTypeCtor("None", null, loc),
                    ParsedVariantTypeCtor("Other", b, loc)
                ),
                loc = loc
            ) to listOf(a, b),
            ParsedSeqType(a, loc) to listOf(a),
            ParsedFunctionType(a, b, loc = loc) to listOf(a, b)
        )
        for ((annotation, expected) in cases) {
            assertEquals(
                expected, annotation.children(),
                "${annotation::class.simpleName} reported the wrong direct children"
            )
        }
    }

    @Test
    fun `a nullary variant constructor contributes no child`() {
        val v = ParsedVariantType(listOf(ParsedVariantTypeCtor("Red", null, loc)), loc = loc)
        assertTrue(v.children().isEmpty())
    }

    // ---- transformChildren() / transformDeep() ----

    @Test
    fun `transformDeep rewrites a type var in all four function-type positions`() {
        val rewritten = fnWithEveryPosition().transformDeep { ann ->
            if (ann is ParsedNamedType && ann.name == "'a") named("Int") else ann
        } as ParsedFunctionType

        val param = (rewritten.paramType as ParsedRecordType).entries.single().type
        assertEquals("Int", (param as ParsedNamedType).name, "paramType not substituted")
        assertEquals(
            "Int",
            (rewritten.effects!!.effectAnnotations.single().associatedTypes.getValue("'error")
                as ParsedNamedType).name,
            "the effect row's `having` binding was left untouched — the bug this file exists to prevent"
        )
        assertEquals(
            "Int",
            (rewritten.abilityConstraints.single().associatedTypeBindings.getValue("'item")
                as ParsedNamedType).name,
            "the with-clause `having` binding was left untouched"
        )
        // 'c is a different variable and must survive.
        assertEquals("'c", (rewritten.abilityConstraints.single().typeArgs.single() as ParsedNamedType).name)
        assertEquals("Str", (rewritten.returnType as ParsedNamedType).name)
    }

    @Test
    fun `transformDeep reaches arbitrarily deep nesting`() {
        val deep = ParsedSeqType(
            ParsedTypeApp("Option", listOf(ParsedRecordType(listOf(ParsedRecordTypeEntry("v", named("'a"), loc)), loc = loc)), loc),
            loc
        )
        val rewritten = deep.transformDeep { if (it is ParsedNamedType && it.name == "'a") named("Int") else it }
        assertFalse(rewritten.anyDeep { it is ParsedNamedType && it.name == "'a" })
        assertTrue(rewritten.anyDeep { it is ParsedNamedType && it.name == "Int" })
    }

    @Test
    fun `transformChildren is shallow`() {
        val inner = ParsedSeqType(named("'a"), loc)
        val outer = ParsedSeqType(inner, loc)
        val rewritten = outer.transformChildren { if (it is ParsedNamedType) named("Int") else it }
        // The direct child is the inner Seq, not the named type, so nothing changes.
        assertEquals(outer, rewritten)
    }

    @Test
    fun `a leaf transforms to itself, identically`() {
        val leaf = named("Int")
        assertSame(leaf, leaf.transformChildren { named("X") })
    }

    @Test
    fun `an identity transform preserves the whole tree`() {
        val original = fnWithEveryPosition()
        assertEquals(original, original.transformDeep { it })
    }

    // ---- selfAndDescendants() / anyDeep() ----

    @Test
    fun `selfAndDescendants yields the root first and covers every position`() {
        val all = fnWithEveryPosition().selfAndDescendants().toList()
        assertTrue(all.first() is ParsedFunctionType, "the root must come first")
        assertEquals(
            3, all.count { it is ParsedNamedType && it.name == "'a" },
            "all three 'a occurrences must be visible to a deep walk"
        )
    }

    @Test
    fun `anyDeep sees the root itself`() {
        assertTrue(named("Int").anyDeep { it is ParsedNamedType && it.name == "Int" })
    }
}
