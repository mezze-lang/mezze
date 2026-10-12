package com.mezze.language.typechecker.derive

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.solving.collectFreeTypeVars
import com.mezze.language.parsedast.ParsedNamedType
import com.mezze.language.typechecker.WellKnownHashConstants
import com.mezze.language.typechecker.context.DeclarationRegistry
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.typechecker.util.TypeAnnotationConverter
import com.mezze.language.typechecker.util.TypeAnnotationMatcher
import com.mezze.language.typedast.Hash

/**
 * Whether discharging [abilityName] at [typeArgs] (receiver first) needs a
 * dictionary for one of the [hidden] type variables (by id).
 *
 * The receiver position needs one outright when it IS a hidden variable:
 * dispatch is on it. Inside a type it depends on how the ability is provided
 * there, and both providers are followed:
 *
 *  - DERIVED (tried first, as `AbilityChecker.checkAbilityImplementation`
 *    does): the derivation's own walk over components, from
 *    [canDeriveAbilityStructurally] — so a component counts exactly when the
 *    derivation would ask it for the ability. Derived `Arbitrary` asks every
 *    field; derived `Hash` and `Inspect` are total and ask nothing.
 *  - WRITTEN: the matching impl's `with` clauses, its variables bound the way
 *    impl matching binds them. `[_]`'s `Sized` has none; an impl
 *    `with Arbitrary 'a` passes the question on to `'a`.
 *
 * No provider at all answers false: that is a missing impl, reported
 * elsewhere in its own words. Codec abilities are decided per call site by
 * their own resolver and are not judged here.
 */
internal fun requirementReachesHiddenVar(
    abilityName: String,
    abilityHash: Hash?,
    typeArgs: List<MezzeType>,
    hidden: Set<Int>,
    registry: DeclarationRegistry,
    implsFor: (String, Hash?) -> List<TypingContext.AbilityImplInfo>,
    depth: Int = 0,
): Boolean {
    if (depth > 32) return false
    val receiver = typeArgs.firstOrNull() ?: return false
    val hash = abilityHash ?: registry.resolveAbilityHash(abilityName)
    if (WellKnownHashConstants.isInspect(hash) || WellKnownHashConstants.isCodecAbility(hash)) return false
    if (receiver is MezzeType.VarType) return receiver.variable.id in hidden
    if (typeArgs.none { t -> collectFreeTypeVars(t).any { it.id in hidden } }) return false

    fun reaches(name: String, h: Hash?, args: List<MezzeType>) =
        requirementReachesHiddenVar(name, h, args, hidden, registry, implsFor, depth + 1)

    if (typeArgs.size == 1) {
        var reached = false
        val derivable = canDeriveAbilityStructurally(abilityName, receiver, hash, registry, mutableSetOf()) { name, component, h, _ ->
            if (!reached && reaches(name, h, listOf(component))) reached = true
            true
        }
        if (derivable) return reached
    }

    for (impl in implsFor(abilityName, hash)) {
        val annotations = impl.targetTypeAnnotations ?: continue
        if (annotations.size != typeArgs.size) continue
        val bindings = mutableMapOf<String, MezzeType>()
        val matches = typeArgs.zip(annotations).all { (actual, annotation) ->
            TypeAnnotationMatcher.typeMatchesAnnotation(actual, annotation, registry.typeDeclarations, impl.typeVars, bindings)
        }
        if (!matches) continue
        return impl.operationConstraints.any { constraint ->
            val args = constraint.typeArgs.map { arg ->
                (arg as? ParsedNamedType)?.let { bindings[it.name] }
                    ?: TypeAnnotationConverter.convertWith(arg, config = TypeAnnotationConverter.registryConfig(registry))
            }
            reaches(constraint.abilityName, registry.resolveAbilityHash(constraint.abilityName), args)
        }
    }
    return false
}
