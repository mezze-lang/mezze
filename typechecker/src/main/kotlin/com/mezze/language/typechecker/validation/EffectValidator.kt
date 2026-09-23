package com.mezze.language.typechecker.validation

import com.mezze.language.parsedast.diagnostics.ErrorCode
import com.mezze.language.parsedast.ParsedEffectDecl
import com.mezze.language.parsedast.ParsedPerformerDecl
import com.mezze.language.ast.ScopedEffectConstants
import com.mezze.language.parsedast.*
import com.mezze.language.typechecker.context.TypingContext

class EffectValidator(
    private val context: TypingContext
) {
        fun validateEffectDeclaration(effectDecl: ParsedEffectDecl): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()

        val operationNames = effectDecl.operations.map { it.name }
        val duplicates = operationNames.groupingBy { it }.eachCount().filter { it.value > 1 }

        for ((name, count) in duplicates) {
            errors.add(
                ValidationError.StructuralError(
                    reason = "Operation '$name' is defined $count times in effect '${effectDecl.name}'. Each operation must have a unique name.",
                    loc = effectDecl.loc,
                )
            )
        }

        return errors
    }

        fun validateEffectImpl(
        implDecl: ParsedPerformerDecl,
        effectDecl: ParsedEffectDecl
    ): List<ValidationError> {
        val errors = mutableListOf<ValidationError>()

        val effectAssociatedTypes = effectDecl.associatedTypes
        val implAssociatedTypeBindings = implDecl.associatedTypeBindings

        if (effectAssociatedTypes.isNotEmpty() && implAssociatedTypeBindings.isEmpty()) {
            val typesList = effectAssociatedTypes.joinToString(", ")
            errors.add(
                ValidationError.StructuralError(
                    reason = "Effect '${implDecl.effect.localName}' declares associated types [$typesList] " +
                            "but implementation '${implDecl.performerName}' does not provide bindings. " +
                            "Use: impl ${implDecl.effect.localName} for ${implDecl.performerName} having ${effectAssociatedTypes.joinToString(", ") { "$it = <Type>" }} where { ... }",
                    loc = implDecl.loc,
                    code = ErrorCode.V0413_MISSING_ASSOCIATED_TYPE_BINDING,
                )
            )
        }

        val missingBindings = effectAssociatedTypes.filter { it !in implAssociatedTypeBindings.keys }
        if (missingBindings.isNotEmpty()) {
            errors.add(
                ValidationError.StructuralError(
                    reason = "Effect '${implDecl.effect.localName}' declares associated types ${effectAssociatedTypes.joinToString(", ")} " +
                            "but implementation '${implDecl.performerName}' is missing bindings for: ${missingBindings.joinToString(", ")}. " +
                            "All associated types must be bound in the implementation.",
                    loc = implDecl.loc,
                    code = ErrorCode.V0413_MISSING_ASSOCIATED_TYPE_BINDING,
                )
            )
        }

        val extraBindings = implAssociatedTypeBindings.keys.filter { it !in effectAssociatedTypes }
        if (extraBindings.isNotEmpty()) {
            val declaredPart = if (effectAssociatedTypes.isEmpty()) {
                "does not declare any associated types"
            } else {
                "only declares: ${effectAssociatedTypes.joinToString(", ")}"
            }
            errors.add(
                ValidationError.StructuralError(
                    reason = "Implementation '${implDecl.performerName}' provides bindings for ${extraBindings.joinToString(", ")} " +
                            "but effect '${implDecl.effect.localName}' $declaredPart. " +
                            "Remove the extra bindings or add them to the effect declaration.",
                    loc = implDecl.loc,
                    code = ErrorCode.V0414_EXTRA_ASSOCIATED_TYPE_BINDING,
                )
            )
        }

        val implementedOps = implDecl.operations.map { it.name }.toSet()

        val requiredOps = effectDecl.operations.map { it.name }.toSet()

        val missingOps = requiredOps - implementedOps
        if (missingOps.isNotEmpty()) {
            val missing = missingOps.joinToString(", ")
            val example = missingOps.first()
            errors.add(
                ValidationError.StructuralError(
                    reason = "Implementation '${implDecl.performerName}' for effect '${implDecl.effect.localName}' " +
                            "is missing required ${if (missingOps.size == 1) "operation" else "operations"}: $missing. " +
                            "Add: $example = {args} -> ...",
                    loc = implDecl.loc,
                    code = ErrorCode.V0404_IMPL_MISSING_METHOD,
                )
            )
        }

        val scopedEffectNames = setOf(ScopedEffectConstants.INITIALIZE_SCOPE, ScopedEffectConstants.FINALIZE_SCOPE)
        val extraOps = (implementedOps - requiredOps) - scopedEffectNames
        if (extraOps.isNotEmpty()) {
            val extra = extraOps.joinToString(", ")
            errors.add(
                ValidationError.StructuralError(
                    reason = "Implementation '${implDecl.performerName}' for effect '${implDecl.effect.localName}' " +
                            "has extra ${if (extraOps.size == 1) "operation" else "operations"} not in effect: $extra. " +
                            "Remove ${if (extraOps.size == 1) "it" else "them"} or add to the effect declaration.",
                    loc = implDecl.loc,
                    code = ErrorCode.V0405_IMPL_EXTRA_METHOD,
                )
            )
        }

        for (implOp in implDecl.operations) {
            val declOp = effectDecl.operations.find { it.name == implOp.name } ?: continue
            if (implOp.paramType != null) {
                val expectedParam = substituteAssociatedTypes(declOp.paramType, implAssociatedTypeBindings)
                if (!AbilityValidator.typeAnnotationsMatchPublic(implOp.paramType!!, expectedParam)) {
                    errors.add(
                        ValidationError.StructuralError(
                            reason = "Wrong parameter type for operation '${implOp.name}' in performer '${implDecl.performerName}': " +
                                    "expected ${formatAnnotation(expectedParam)}, but got ${formatAnnotation(implOp.paramType!!)}.",
                            loc = implOp.loc,
                        )
                    )
                }
            }
            if (implOp.returnType != null) {
                val expectedReturn = substituteAssociatedTypes(declOp.returnType, implAssociatedTypeBindings)
                if (!AbilityValidator.typeAnnotationsMatchPublic(implOp.returnType!!, expectedReturn)) {
                    errors.add(
                        ValidationError.StructuralError(
                            reason = "Wrong return type for operation '${implOp.name}' in performer '${implDecl.performerName}': " +
                                    "expected ${formatAnnotation(expectedReturn)}, but got ${formatAnnotation(implOp.returnType!!)}.",
                            loc = implOp.loc,
                        )
                    )
                }
            }
        }

        return errors
    }

    private fun substituteAssociatedTypes(
        annotation: ParsedTypeAnnotation,
        bindings: Map<String, ParsedTypeAnnotation>
    ): ParsedTypeAnnotation {
        return when (annotation) {
            is ParsedNamedType -> {
                if (annotation.name.startsWith("'") && bindings.containsKey(annotation.name)) {
                    bindings[annotation.name]!!
                } else annotation
            }
            is ParsedFunctionType -> ParsedFunctionType(
                paramType = substituteAssociatedTypes(annotation.paramType, bindings),
                returnType = substituteAssociatedTypes(annotation.returnType, bindings),
                effects = annotation.effects,
                abilityConstraints = annotation.abilityConstraints,
                loc = annotation.loc
            )
            is ParsedRecordType -> ParsedRecordType(
                entries = annotation.entries.map { entry ->
                    ParsedRecordTypeEntry(
                        name = entry.name,
                        type = substituteAssociatedTypes(entry.type, bindings),
                        loc = entry.loc
                    )
                },
                isOpen = annotation.isOpen,
                loc = annotation.loc
            )
            is ParsedTypeApp -> ParsedTypeApp(
                constructor = annotation.constructor,
                args = annotation.args.map { substituteAssociatedTypes(it, bindings) },
                loc = annotation.loc
            )
            is ParsedSeqType -> ParsedSeqType(
                elementType = substituteAssociatedTypes(annotation.elementType, bindings),
                loc = annotation.loc
            )
            else -> annotation
        }
    }

    private fun formatAnnotation(annotation: ParsedTypeAnnotation): String {
        return when (annotation) {
            is ParsedNamedType -> annotation.name
            is ParsedRecordType ->
                "{ ${annotation.entries.joinToString(", ") { "${it.name}: ${formatAnnotation(it.type)}" }} }"
            is ParsedFunctionType ->
                "${formatAnnotation(annotation.paramType)} -> ${formatAnnotation(annotation.returnType)}"
            is ParsedTypeApp ->
                "${annotation.constructor} ${annotation.args.joinToString(" ") { formatAnnotation(it) }}"
            is ParsedSeqType ->
                "[${formatAnnotation(annotation.elementType)}]"
            else -> annotation.toString()
        }
    }
}
