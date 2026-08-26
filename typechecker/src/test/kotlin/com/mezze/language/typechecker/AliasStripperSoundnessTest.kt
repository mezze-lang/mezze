package com.mezze.language.typechecker

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.MezzeType.*
import com.mezze.language.ast.TypeIdentity
import com.mezze.language.ast.TypeVar
import com.mezze.language.typechecker.solving.AliasStripper
import com.mezze.language.typechecker.context.DeclarationRegistry
import com.mezze.language.parsedast.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertEquals

/**
 * Regression tests for AliasStripper effect and ability constraint stripping.
 * These verify that alias types inside effect rows and ability constraints
 * are properly stripped when the alias name is out of scope.
 */
class AliasStripperSoundnessTest {

    private val testLoc = Loc("test.mz", 1, 1, 1, 1)

    private val colorVariantDef = ParsedVariantDef(
        constructors = listOf(
            ParsedVariantCtorDef("Red", null, loc = testLoc),
            ParsedVariantCtorDef("Green", null, loc = testLoc),
            ParsedVariantCtorDef("Blue", null, loc = testLoc),
        ),
        loc = testLoc
    )
    private val colorDecl = ParsedTypeDecl(
        name = "Color",
        typeParams = emptyList(),
        definition = colorVariantDef,
        visibility = Visibility.PUBLIC,
        loc = testLoc
    )
    private val typeDecls = mapOf("Color" to colorDecl)
    private val inScope = emptySet<String>()

    @Test
    fun `effect associated types inside FunctionType are stripped`() {
        val colorType = VariantType(
            constructors = mapOf("Red" to null, "Green" to null, "Blue" to null),
            identity = TypeIdentity.Named("Color")
        )
        val effectInstance = EffectInstance(
            name = "State",
            associatedTypes = mapOf("'state" to colorType)
        )
        val effectRow = EffectRow(
            effects = emptySet(),
            effectInstances = setOf(effectInstance)
        )
        val fnType = FunctionType(
            paramType = RecordType(fields = emptyMap()),
            returnType = IntType,
            effects = effectRow
        )

        val stripped = AliasStripper.strip(fnType, inScope, DeclarationRegistry.from(typeDecls, emptyMap(), emptyMap()))

        assertTrue(stripped is FunctionType)
        val strippedFn = stripped as FunctionType
        assertNotNull(strippedFn.effects)
        val strippedInstance = strippedFn.effects!!.effectInstances.first()
        val strippedState = strippedInstance.associatedTypes["'state"]
        assertTrue(strippedState is VariantType)
        assertEquals(null, (strippedState as VariantType).identity.displayNameOrNull(),
            "Color alias should be stripped from effect associated type when not in scope")
    }

    @Test
    fun `effect associated types inside SuspendType are stripped`() {
        val colorType = VariantType(
            constructors = mapOf("Red" to null, "Green" to null, "Blue" to null),
            identity = TypeIdentity.Named("Color")
        )
        val effectInstance = EffectInstance(
            name = "State",
            associatedTypes = mapOf("'state" to colorType)
        )
        val effectRow = EffectRow(
            effects = emptySet(),
            effectInstances = setOf(effectInstance)
        )
        val suspType = SuspendType(
            valueType = IntType,
            effects = effectRow
        )

        val stripped = AliasStripper.strip(suspType, inScope, DeclarationRegistry.from(typeDecls, emptyMap(), emptyMap()))

        assertTrue(stripped is SuspendType)
        val strippedSusp = stripped as SuspendType
        val strippedInstance = strippedSusp.effects.effectInstances.first()
        val strippedState = strippedInstance.associatedTypes["'state"]
        assertTrue(strippedState is VariantType)
        assertEquals(null, (strippedState as VariantType).identity.displayNameOrNull(),
            "Color alias should be stripped from suspended effect associated type when not in scope")
    }

    @Test
    fun `ability constraint typeArgs inside FunctionType are stripped`() {
        val colorType = VariantType(
            constructors = mapOf("Red" to null, "Green" to null, "Blue" to null),
            identity = TypeIdentity.Named("Color")
        )
        val constraint = FunctionAbilityConstraint(
            abilityName = "Eq",
            typeArgs = listOf(colorType)
        )
        val fnType = FunctionType(
            paramType = RecordType(fields = mapOf("x" to VarType(TypeVar.fresh()))),
            returnType = BoolType,
            abilityConstraints = listOf(constraint)
        )

        val stripped = AliasStripper.strip(fnType, inScope, DeclarationRegistry.from(typeDecls, emptyMap(), emptyMap()))

        assertTrue(stripped is FunctionType)
        val strippedFn = stripped as FunctionType
        assertEquals(1, strippedFn.abilityConstraints.size)
        val strippedArg = strippedFn.abilityConstraints[0].typeArgs[0]
        assertTrue(strippedArg is VariantType)
        assertEquals(null, (strippedArg as VariantType).identity.displayNameOrNull(),
            "Color alias should be stripped from ability constraint typeArgs when not in scope")
    }

    @Test
    fun `in-scope aliases are preserved in effects and ability constraints`() {
        val inScopeSet = setOf("Color")
        val colorType = VariantType(
            constructors = mapOf("Red" to null, "Green" to null, "Blue" to null),
            identity = TypeIdentity.Named("Color")
        )
        val effectInstance = EffectInstance(
            name = "State",
            associatedTypes = mapOf("'state" to colorType)
        )
        val effectRow = EffectRow(
            effects = emptySet(),
            effectInstances = setOf(effectInstance)
        )
        val fnType = FunctionType(
            paramType = RecordType(fields = emptyMap()),
            returnType = IntType,
            effects = effectRow,
            abilityConstraints = listOf(
                FunctionAbilityConstraint(abilityName = "Eq", typeArgs = listOf(colorType))
            )
        )

        val stripped = AliasStripper.strip(fnType, inScopeSet, DeclarationRegistry.from(typeDecls, emptyMap(), emptyMap()))

        assertTrue(stripped is FunctionType)
        val strippedFn = stripped as FunctionType
        val strippedInstance = strippedFn.effects!!.effectInstances.first()
        val strippedState = strippedInstance.associatedTypes["'state"]
        assertTrue(strippedState is VariantType)
        assertEquals("Color", (strippedState as VariantType).identity.displayNameOrNull(),
            "Color alias should be preserved when in scope")

        val strippedConstraintArg = strippedFn.abilityConstraints[0].typeArgs[0]
        assertTrue(strippedConstraintArg is VariantType)
        assertEquals("Color", (strippedConstraintArg as VariantType).identity.displayNameOrNull(),
            "Color alias should be preserved in ability constraints when in scope")
    }
}
