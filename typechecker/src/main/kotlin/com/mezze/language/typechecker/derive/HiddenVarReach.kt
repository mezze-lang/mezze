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
 * The receiver position needs one when it IS a hidden variable AND the ability
 * can dispatch with no value of that type in hand. An operation taking the
 * value (`it`) can only ever run on a value that exists, and nothing can
 * produce a value of a type nothing determines except an operation that takes
 * none — which is itself such a demand, and judged. So `NumAdd`, `Eq` or a
 * user's `{ it: 'a } -> …` at a hidden variable is unreachable code, not a
 * missing dictionary (`process { v = Red }` never reaches its `Blue` arm's
 * `*`), while `Default`, `Arbitrary` and `Zero` are exactly the danger.
 *
 * Inside a type it depends on how the ability is provided there, and both
 * providers are followed:
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
    if (receiver is MezzeType.VarType) {
        return receiver.variable.id in hidden && dispatchesWithoutAValue(abilityName, hash, registry)
    }
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

/**
 * Whether [abilityName] has an operation dispatched WITHOUT a value of its
 * class type: one whose parameter record has no `it` (the same signal the
 * static-op call path keys on). An ability the registry cannot find answers
 * true, so an unknown is judged rather than waved through.
 */
private fun dispatchesWithoutAValue(abilityName: String, hash: Hash?, registry: DeclarationRegistry): Boolean {
    val decl = registry.lookupAbility(abilityName, hash) ?: return true
    return decl.operations.any { op ->
        val params = op.paramType as? com.mezze.language.parsedast.ParsedRecordType ?: return@any true
        params.entries.none { it.name == com.mezze.language.ast.Keywords.IT }
    }
}
