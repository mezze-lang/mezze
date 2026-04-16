package com.mezze.language.typechecker.validation

import com.mezze.language.ast.*
import com.mezze.language.typechecker.TypeCheckError
import com.mezze.language.typechecker.abilities.BuiltInAbilities
import com.mezze.language.ast.PrimitiveTypes

/**
 * Validates ability declarations and implementations.
 *
 * Based on validation logic from OldTypeChecker.kt:13063-13122
 * which implements all 8 validation rules for abilities.
 */
object AbilityValidator {

    /**
     * Validate ability declaration.
     *
     * Checks:
     * 1. No ability-level constraints (use method-level constraints instead)
     * 2. Operation effects are not polymorphic (<...>)
     * 3. Operation effects are not performed (<*>)
     *
     * Ability operations must declare concrete effects because they define a contract.
     * Polymorphic or performed effects would make the contract ambiguous.
     *
     * @param ability The ability declaration to validate
     * @throws TypeCheckError if validation fails
     */
    fun validateAbilityDeclaration(ability: com.mezze.language.parsedast.ParsedAbilityDecl) {
        val sourceLocation = SourceLocation(ability.loc.startLine, ability.loc.startCol, ability.loc.file)
        if (ability.constraints.isNotEmpty() || ability.dependencies.isNotEmpty()) {
            val constraintsList = (ability.constraints.map { "${it.abilityName} ${it.typeParam}" } +
                                   ability.dependencies.map { dep ->
                                       "${dep.abilityName} ${dep.typeArgs.joinToString(" ")}"
                                   })
                .joinToString(", ")

            val suggestion = buildString {
                append("Move constraints to method signatures. For example:\n\n")
                append("  ability ${ability.name} ${ability.typeParams.joinToString(" ")} where {\n")
                for (op in ability.operations) {
                    append("      ${op.name}: ... with $constraintsList -> ...\n")
                }
                append("  }")
            }

            throw TypeCheckError(
                "Ability '${ability.name}' uses ability-level constraints ('with $constraintsList'), " +
                "but only method-level constraints are allowed.\n\n$suggestion",
                sourceLocation
            )
        }

        for (operation in ability.operations) {
            val effects = operation.effects ?: continue
            if (effects.isPolymorphic) {
                throw TypeCheckError(
                    "Ability operation '${operation.name}' in '${ability.name}' cannot have polymorphic effects (<...>). " +
                    "Ability operations must declare concrete effects to define a clear contract. " +
                    "Use specific effects like <Mut> or no effects for pure operations.",
                    sourceLocation
                )
            }
            if (effects.isPerformed) {
                throw TypeCheckError(
                    "Ability operation '${operation.name}' in '${ability.name}' cannot have performed marker (<*>). " +
                    "Ability operations declare what effects they perform, not that effects are handled. " +
                    "The <*> marker is only valid at call sites where effects are handled with 'perform'.",
                    sourceLocation
                )
            }
        }
    }
    /**
     * Validate ability implementation against declaration.
     *
     * Checks:
     * 1. Ability exists
     * 2. Type parameter count matches
     * 3. Associated type consistency:
     *    - If ability declares associated types, impl must provide bindings
     *    - If ability doesn't declare associated types, impl cannot provide bindings
     *    - All declared associated types must have bindings
     *    - No extra bindings not declared in ability
     *
     * @param impl The ability implementation to validate
     * @param abilityDeclarations Map of ability name to declaration
     * @throws TypeCheckError if validation fails
     */
    /**
     * Validate ability implementation (v2 native — works directly on ParsedImplAbilityDecl).
     * No v1 bridge conversion needed.
     */
    fun validateAbilityImplementation(
        impl: com.mezze.language.parsedast.ParsedImplAbilityDecl,
        abilityDeclarations: Map<String, com.mezze.language.parsedast.ParsedAbilityDecl>,
        typeDeclarations: Map<String, com.mezze.language.parsedast.ParsedTypeDecl> = emptyMap()
    ) {
        val sourceLocation = SourceLocation(impl.loc.startLine, impl.loc.startCol, impl.loc.file)

        // Rule 0: Check impl method names don't conflict with target type's record fields.
        validateNoFieldMethodConflict(impl, typeDeclarations, sourceLocation)

        // Inherent impls don't implement an ability — validate target type exists, then return
        if (impl.isInherent) {
            validateInherentImplTargetType(impl, typeDeclarations, sourceLocation)
            return
        }

        val abilityName = impl.abilityName!!  // safe: not inherent
        val abilityQualifier = impl.abilityRef?.qualifier

        // Find the ability declaration
        val qualifiedAbilityName = abilityQualifier?.let { "$it::$abilityName" } ?: abilityName
        val abilityDeclaration = abilityDeclarations[abilityName]
            ?: abilityDeclarations[qualifiedAbilityName]
            ?: throw TypeCheckError(
                "Ability '$qualifiedAbilityName' is not defined or not imported. " +
                "Did you forget to import it with 'use'?",
                sourceLocation
            )

        // Rule 1: Check type parameter count matches
        val concreteAbilityTypeArgCount = impl.abilityTypeArgs
            .count { it !is com.mezze.language.parsedast.ParsedNamedType || !it.name.startsWith("'") }
        val totalTypeParams = concreteAbilityTypeArgCount + impl.targetTypes.size
        if (totalTypeParams != abilityDeclaration.typeParams.size) {
            throw TypeCheckError(
                "Type parameter count mismatch in ability implementation for '${abilityName}': " +
                "ability expects ${abilityDeclaration.typeParams.size} type parameters, " +
                "but implementation provides ${totalTypeParams}",
                sourceLocation
            )
        }

        // Rule 2-5: Validate associated types
        val abilityAssociatedTypes = abilityDeclaration.associatedTypes
        val implAssociatedTypeBindings = impl.associatedTypeBindings

        if (abilityAssociatedTypes.isNotEmpty() && implAssociatedTypeBindings.isEmpty()) {
            val typesList = abilityAssociatedTypes.joinToString(", ")
            throw TypeCheckError(
                "Ability '${abilityName}' declares associated types [$typesList] " +
                "but implementation does not provide bindings. " +
                "Use: impl ${abilityName} ${impl.targetTypes.joinToString(" ") { parsedTypeAnnotationToString(it) }} " +
                "having ${abilityAssociatedTypes.joinToString(", ") { "$it = <Type>" }} where { ... }",
                sourceLocation
            )
        }

        if (abilityAssociatedTypes.isEmpty() && implAssociatedTypeBindings.isNotEmpty()) {
            val bindingsList = implAssociatedTypeBindings.keys.joinToString(", ")
            throw TypeCheckError(
                "Implementation provides associated type bindings [$bindingsList] " +
                "but ability '${abilityName}' does not declare associated types. " +
                "Remove the 'having' clause or add it to the ability declaration.",
                sourceLocation
            )
        }

        val missingBindings = abilityAssociatedTypes.filter { it !in implAssociatedTypeBindings.keys }
        if (missingBindings.isNotEmpty()) {
            throw TypeCheckError(
                "Ability '${abilityName}' declares associated types ${abilityAssociatedTypes.joinToString(", ")} " +
                "but implementation is missing bindings for: ${missingBindings.joinToString(", ")}. " +
                "All associated types must be bound in the implementation.",
                sourceLocation
            )
        }

        val extraBindings = implAssociatedTypeBindings.keys.filter { it !in abilityAssociatedTypes }
        if (extraBindings.isNotEmpty()) {
            throw TypeCheckError(
                "Implementation provides bindings for ${extraBindings.joinToString(", ")} " +
                "but ability '${abilityName}' only declares: ${abilityAssociatedTypes.joinToString(", ")}. " +
                "Remove the extra bindings or add them to the ability declaration.",
                sourceLocation
            )
        }

        // Rule 6: Check all operations are implemented
        val declaredOps = abilityDeclaration.operations.map { it.name }.toSet()
        val implementedOps = impl.operations.map { it.name }.toSet()
        val missingOps = declaredOps - implementedOps

        if (missingOps.isNotEmpty()) {
            val missing = missingOps.joinToString(", ")
            val example = missingOps.first()
            throw TypeCheckError(
                "Implementation of '${abilityName}' is missing required ${if (missingOps.size == 1) "method" else "methods"}: $missing. " +
                "Add: $example = {args} -> ...",
                sourceLocation
            )
        }

        // Rule 7: Check no extra operations
        val extraOps = implementedOps - declaredOps
        if (extraOps.isNotEmpty()) {
            val extra = extraOps.joinToString(", ")
            val extraOpLoc = impl.operations.find { it.name in extraOps }?.loc
            val extraOpLocation = extraOpLoc?.let { SourceLocation(it.startLine, it.startCol, it.file) }
            throw TypeCheckError(
                "Implementation of '${abilityName}' has extra ${if (extraOps.size == 1) "method" else "methods"} not in ability: $extra. " +
                "Remove ${if (extraOps.size == 1) "it" else "them"} or add to the ability declaration.",
                extraOpLocation
            )
        }

        // Rule 8: Validate operation signatures match after type parameter substitution
        validateOperationSignatures(impl, abilityDeclaration, sourceLocation)
    }

    /**
     * Validate that all operation signatures in the implementation match the declaration
     * after substituting type parameters (v2 native — works on ParsedTypeAnnotation).
     */
    private fun validateOperationSignatures(
        impl: com.mezze.language.parsedast.ParsedImplAbilityDecl,
        abilityDeclaration: com.mezze.language.parsedast.ParsedAbilityDecl,
        implSourceLocation: SourceLocation
    ) {
        // Build type parameter substitution map
        // e.g., for impl Show for Int: 'a -> Int
        val substitution = abilityDeclaration.typeParams.zip(impl.allTypeArgs).toMap()

        for (implOp in impl.operations) {
            val declOp = abilityDeclaration.operations.find { it.name == implOp.name }
                ?: continue  // Already caught by missing/extra operation checks

            // Substitute type parameters in declaration types
            val expectedParamType = substituteTypeParameters(declOp.paramType, substitution)
            val expectedReturnType = substituteTypeParameters(declOp.returnType, substitution)

            // Get actual types from implementation (if provided)
            val actualParamType = implOp.paramType ?: expectedParamType
            val actualReturnType = implOp.returnType ?: expectedReturnType

            val sourceLocation = SourceLocation(implOp.loc.startLine, implOp.loc.startCol, implOp.loc.file)

            // Check for type variables in concrete implementations
            val allowedTypeVars = impl.abilityTypeArgs
                .filterIsInstance<com.mezze.language.parsedast.ParsedNamedType>()
                .filter { it.name.startsWith("'") }
                .map { it.name }
                .toSet()

            if (implOp.paramType != null && hasTypeVariables(actualParamType, allowedTypeVars)) {
                throw TypeCheckError(
                    "Type variables not allowed in ability implementation: " +
                    "operation '${implOp.name}' parameter type contains type variables. " +
                    "All type variables must be replaced with concrete types.",
                    sourceLocation
                )
            }
            if (implOp.returnType != null && hasTypeVariables(actualReturnType, allowedTypeVars)) {
                throw TypeCheckError(
                    "Type variables not allowed in ability implementation: " +
                    "operation '${implOp.name}' return type contains type variables. " +
                    "All type variables must be replaced with concrete types.",
                    sourceLocation
                )
            }

            // Check parameter type matches
            if (!typeAnnotationsMatch(actualParamType, expectedParamType)) {
                throw TypeCheckError(
                    "Wrong parameter type for '${implOp.name}' in impl ${impl.abilityName}: " +
                    "expected ${parsedTypeAnnotationToString(expectedParamType)}, " +
                    "but got ${parsedTypeAnnotationToString(actualParamType)}.",
                    sourceLocation
                )
            }

            // Check return type matches
            if (!typeAnnotationsMatch(actualReturnType, expectedReturnType)) {
                throw TypeCheckError(
                    "Wrong return type for '${implOp.name}' in impl ${impl.abilityName}: " +
                    "expected ${parsedTypeAnnotationToString(expectedReturnType)}, " +
                    "but got ${parsedTypeAnnotationToString(actualReturnType)}.",
                    sourceLocation
                )
            }
        }
    }

    /**
     * Substitute type parameters in a v2 ParsedTypeAnnotation.
     * e.g., {val: 'a} with {'a -> Int} becomes {val: Int}
     */
    private fun substituteTypeParameters(
        annotation: com.mezze.language.parsedast.ParsedTypeAnnotation,
        substitution: Map<String, com.mezze.language.parsedast.ParsedTypeAnnotation>
    ): com.mezze.language.parsedast.ParsedTypeAnnotation {
        return when (annotation) {
            is com.mezze.language.parsedast.ParsedNamedType -> {
                val paramName = annotation.name.removePrefix("'")
                substitution[paramName] ?: substitution["'$paramName"] ?: annotation
            }
            is com.mezze.language.parsedast.ParsedFunctionType -> {
                com.mezze.language.parsedast.ParsedFunctionType(
                    paramType = substituteTypeParameters(annotation.paramType, substitution),
                    returnType = substituteTypeParameters(annotation.returnType, substitution),
                    effects = annotation.effects,
                    abilityConstraints = annotation.abilityConstraints,
                    loc = annotation.loc
                )
            }
            is com.mezze.language.parsedast.ParsedRecordType -> {
                com.mezze.language.parsedast.ParsedRecordType(
                    entries = annotation.entries.map { entry ->
                        com.mezze.language.parsedast.ParsedRecordTypeEntry(
                            name = entry.name,
                            type = substituteTypeParameters(entry.type, substitution),
                            loc = entry.loc
                        )
                    },
                    isOpen = annotation.isOpen,
                    loc = annotation.loc
                )
            }
            is com.mezze.language.parsedast.ParsedVariantType -> {
                com.mezze.language.parsedast.ParsedVariantType(
                    constructors = annotation.constructors.map { ctor ->
                        com.mezze.language.parsedast.ParsedVariantTypeCtor(
                            name = ctor.name,
                            dataType = ctor.dataType?.let { substituteTypeParameters(it, substitution) },
                            loc = ctor.loc
                        )
                    },
                    isOpen = annotation.isOpen,
                    loc = annotation.loc
                )
            }
            is com.mezze.language.parsedast.ParsedTypeApp -> {
                com.mezze.language.parsedast.ParsedTypeApp(
                    constructor = annotation.constructor,
                    args = annotation.args.map { substituteTypeParameters(it, substitution) },
                    loc = annotation.loc
                )
            }
            is com.mezze.language.parsedast.ParsedSeqType -> {
                com.mezze.language.parsedast.ParsedSeqType(
                    elementType = substituteTypeParameters(annotation.elementType, substitution),
                    loc = annotation.loc
                )
            }
            is com.mezze.language.parsedast.ParsedQualifiedType -> {
                com.mezze.language.parsedast.ParsedQualifiedType(
                    modulePath = annotation.modulePath,
                    typeName = annotation.typeName,
                    args = annotation.args.map { substituteTypeParameters(it, substitution) },
                    loc = annotation.loc
                )
            }
        }
    }

    /**
     * Check if a v2 ParsedTypeAnnotation contains type variables.
     */
    private fun hasTypeVariables(annotation: com.mezze.language.parsedast.ParsedTypeAnnotation, allowedVars: Set<String> = emptySet()): Boolean {
        return when (annotation) {
            is com.mezze.language.parsedast.ParsedNamedType -> {
                val isTypeVar = annotation.name.startsWith("'") || annotation.name.first().isLowerCase()
                isTypeVar && annotation.name !in allowedVars
            }
            is com.mezze.language.parsedast.ParsedFunctionType -> {
                hasTypeVariables(annotation.paramType, allowedVars) || hasTypeVariables(annotation.returnType, allowedVars)
            }
            is com.mezze.language.parsedast.ParsedRecordType -> {
                annotation.entries.any { hasTypeVariables(it.type, allowedVars) }
            }
            is com.mezze.language.parsedast.ParsedVariantType -> {
                annotation.constructors.any { it.dataType?.let { dt -> hasTypeVariables(dt, allowedVars) } == true }
            }
            is com.mezze.language.parsedast.ParsedTypeApp -> {
                annotation.args.any { hasTypeVariables(it, allowedVars) }
            }
            is com.mezze.language.parsedast.ParsedSeqType -> hasTypeVariables(annotation.elementType, allowedVars)
            is com.mezze.language.parsedast.ParsedQualifiedType -> annotation.args.any { hasTypeVariables(it, allowedVars) }
        }
    }

    /**
     * Check if two v2 ParsedTypeAnnotations match structurally.
     */
    private fun typeAnnotationsMatch(a: com.mezze.language.parsedast.ParsedTypeAnnotation, b: com.mezze.language.parsedast.ParsedTypeAnnotation): Boolean {
        return when {
            a is com.mezze.language.parsedast.ParsedNamedType && b is com.mezze.language.parsedast.ParsedNamedType -> {
                a.name == b.name
            }
            a is com.mezze.language.parsedast.ParsedFunctionType && b is com.mezze.language.parsedast.ParsedFunctionType -> {
                typeAnnotationsMatch(a.paramType, b.paramType) &&
                typeAnnotationsMatch(a.returnType, b.returnType)
            }
            a is com.mezze.language.parsedast.ParsedRecordType && b is com.mezze.language.parsedast.ParsedRecordType -> {
                a.entries.size == b.entries.size &&
                a.entries.zip(b.entries).all { (aEntry, bEntry) ->
                    aEntry.name == bEntry.name && typeAnnotationsMatch(aEntry.type, bEntry.type)
                }
            }
            a is com.mezze.language.parsedast.ParsedTypeApp && b is com.mezze.language.parsedast.ParsedTypeApp -> {
                a.constructor == b.constructor &&
                a.args.size == b.args.size &&
                a.args.zip(b.args).all { (aArg, bArg) -> typeAnnotationsMatch(aArg, bArg) }
            }
            a is com.mezze.language.parsedast.ParsedSeqType && b is com.mezze.language.parsedast.ParsedSeqType -> {
                typeAnnotationsMatch(a.elementType, b.elementType)
            }
            a is com.mezze.language.parsedast.ParsedVariantType && b is com.mezze.language.parsedast.ParsedVariantType -> {
                a.constructors.size == b.constructors.size &&
                a.constructors.zip(b.constructors).all { (aCtor, bCtor) ->
                    aCtor.name == bCtor.name &&
                    ((aCtor.dataType == null && bCtor.dataType == null) ||
                     (aCtor.dataType != null && bCtor.dataType != null &&
                      typeAnnotationsMatch(aCtor.dataType!!, bCtor.dataType!!)))
                }
            }
            a is com.mezze.language.parsedast.ParsedQualifiedType && b is com.mezze.language.parsedast.ParsedQualifiedType -> {
                a.modulePath == b.modulePath &&
                a.typeName == b.typeName &&
                a.args.size == b.args.size &&
                a.args.zip(b.args).all { (aArg, bArg) -> typeAnnotationsMatch(aArg, bArg) }
            }
            else -> false
        }
    }

    /**
     * Validate orphan rules for ability implementation.
     *
     * An impl is an "orphan" if:
     * - The ability is NOT defined in the current module, AND
     * - NONE of the target types are defined in the current module
     *
     * Following Rust/Roc coherence rules:
     * - impl Ability for Type must be in the same module as either the ability or one of the types
     * - This prevents third-party modules from defining impls for types they don't own
     *
     * @param impl The ability implementation to validate
     * @param localAbilityNames Set of ability names defined in the current module
     * @param localTypeNames Set of type names defined in the current module
     * @param importedAbilityNames Set of ability names imported from other modules
     * @param importedTypeNames Set of type names imported from other modules
     * @throws TypeCheckError if the impl is an orphan
     */
    /**
     * Validate orphan rules for ability implementation (v2 native).
     */
    fun validateOrphanRules(
        impl: com.mezze.language.parsedast.ParsedImplAbilityDecl,
        localAbilityNames: Set<String>,
        localTypeNames: Set<String>,
        importedAbilityNames: Set<String>,
        importedTypeNames: Set<String>
    ) {
        val sourceLocation = SourceLocation(impl.loc.startLine, impl.loc.startCol, impl.loc.file)

        // Inherent impls: target type must be defined locally
        if (impl.isInherent) {
            validateInherentImplLocality(impl, localTypeNames, sourceLocation)
            return
        }

        val abilityName = impl.abilityName!!  // safe: not inherent

        val isAbilityLocal = localAbilityNames.contains(abilityName)
        val isAbilityImported = importedAbilityNames.contains(abilityName)

        if (!isAbilityLocal && !isAbilityImported) {
            return  // Let validateAbilityImplementation handle "ability not found"
        }

        if (isAbilityLocal) {
            return  // Valid: impl is in same module as ability declaration
        }

        // Ability is imported - check if any target type is local
        val concreteAbilityTypeArgs = impl.abilityTypeArgs
            .filter { it !is com.mezze.language.parsedast.ParsedNamedType || !it.name.startsWith("'") }
        val targetTypeNames = (concreteAbilityTypeArgs + impl.targetTypes).map { getTypeNameFromParsedAnnotation(it) }

        val hasLocalTargetType = targetTypeNames.any { typeName ->
            localTypeNames.contains(typeName)
        }

        if (hasLocalTargetType) {
            return  // Valid: impl is in same module as at least one target type
        }

        val typesList = targetTypeNames.joinToString(", ")
        throw TypeCheckError(
            "Orphan impl: cannot implement imported ability '$abilityName' for types [$typesList] " +
            "in this module. An impl must be in the same module as either the ability declaration " +
            "or at least one of the target types. Move this impl to the module where '$abilityName' " +
            "or one of [$typesList] is defined.",
            sourceLocation
        )
    }

    /**
     * Extract type name from a v2 ParsedTypeAnnotation for orphan rule checking.
     */
    private fun getTypeNameFromParsedAnnotation(annotation: com.mezze.language.parsedast.ParsedTypeAnnotation): String {
        return when (annotation) {
            is com.mezze.language.parsedast.ParsedNamedType -> annotation.name.removePrefix("'")
            is com.mezze.language.parsedast.ParsedTypeApp -> annotation.constructor
            is com.mezze.language.parsedast.ParsedQualifiedType -> annotation.typeName
            is com.mezze.language.parsedast.ParsedRecordType -> "Record"
            is com.mezze.language.parsedast.ParsedVariantType -> "Variant"
            is com.mezze.language.parsedast.ParsedFunctionType -> "Function"
            is com.mezze.language.parsedast.ParsedSeqType -> "Seq"
        }
    }

    /**
     * Validate ability dependencies to detect circular dependencies.
     *
     * Checks:
     * 1. No self-dependencies (ability cannot depend on itself)
     * 2. No circular dependencies (A -> B -> A)
     *
     * @param abilityDeclaration The ability declaration to validate
     * @param abilityDeclarations Map of ability name to declaration for dependency lookup
     * @throws TypeCheckError if circular dependency detected
     */
    fun validateAbilityDependencies(
        abilityDeclaration: com.mezze.language.parsedast.ParsedAbilityDecl,
        abilityDeclarations: Map<String, com.mezze.language.parsedast.ParsedAbilityDecl>
    ) {
        // Get dependencies from ability declaration
        val dependencies = abilityDeclaration.dependencies

        // Check for self-dependency
        if (dependencies.any { it.abilityName == abilityDeclaration.name }) {
            throw TypeCheckError(
                "Ability '${abilityDeclaration.name}' cannot depend on itself. " +
                "Remove the self-dependency from the 'requires' clause."
            )
        }

        // Check for circular dependencies using DFS
        val visited = mutableSetOf<String>()
        val recursionStack = mutableSetOf<String>()

        fun detectCycle(abilityName: String): Boolean {
            if (recursionStack.contains(abilityName)) {
                // Found a cycle
                return true
            }

            if (visited.contains(abilityName)) {
                // Already explored this path, no cycle
                return false
            }

            visited.add(abilityName)
            recursionStack.add(abilityName)

            // Check dependencies
            val currentAbility = abilityDeclarations[abilityName]
            if (currentAbility != null) {
                for (dep in currentAbility.dependencies) {
                    if (detectCycle(dep.abilityName)) {
                        return true
                    }
                }
            }

            recursionStack.remove(abilityName)
            return false
        }

        // Start cycle detection from each dependency
        for (dep in dependencies) {
            recursionStack.clear()
            recursionStack.add(abilityDeclaration.name) // Start from current ability

            if (detectCycle(dep.abilityName)) {
                throw TypeCheckError(
                    "Circular dependency detected in ability '${abilityDeclaration.name}'. " +
                    "Ability '${dep.abilityName}' (directly or indirectly) depends on '${abilityDeclaration.name}'."
                )
            }
        }
    }

    /**
     * Validate name collisions between abilities, types, and effects.
     *
     * Checks:
     * 1. Ability names don't collide with type names
     * 2. Ability names don't collide with effect names
     * 3. Variant constructor names from abilities don't collide with other constructors
     *
     * @param abilityDeclarations Map of ability name to declaration
     * @param typeDeclarations Map of type name to declaration
     * @param effectDeclarations Map of effect name to declaration
     * @throws TypeCheckError if name collision detected
     */
    fun validateNameCollisions(
        abilityDeclarations: Map<String, *>,
        typeDeclarations: Map<String, com.mezze.language.parsedast.ParsedTypeDecl>,
        effectDeclarations: Map<String, *>
    ) {
        for (typeName in typeDeclarations.keys) {
            if (BuiltInAbilities.isBuiltInAbility(typeName)) {
                throw TypeCheckError(
                    "Cannot define type '$typeName': name conflicts with built-in ability '$typeName'. " +
                    "Types and built-in abilities must have unique names."
                )
            }
        }
        for (abilityName in abilityDeclarations.keys) {
            if (typeDeclarations.containsKey(abilityName)) {
                throw TypeCheckError(
                    "Cannot define type '$abilityName': name conflicts with ability '$abilityName'. " +
                    "Abilities and types must have unique names."
                )
            }
        }
        for (abilityName in abilityDeclarations.keys) {
            if (effectDeclarations.containsKey(abilityName)) {
                throw TypeCheckError(
                    "Cannot define effect '$abilityName': name conflicts with ability '$abilityName'. " +
                    "Abilities and effects must have unique names."
                )
            }
        }
        for (typeName in typeDeclarations.keys) {
            if (effectDeclarations.containsKey(typeName)) {
                throw TypeCheckError(
                    "Cannot define type '$typeName': name conflicts with effect '$typeName'. " +
                    "Types and effects must have unique names."
                )
            }
        }
        // Check for variant constructor name collisions with abilities and effects
        for ((typeName, typeDecl) in typeDeclarations) {
            val definition = typeDecl.definition
            if (definition is com.mezze.language.parsedast.ParsedVariantDef) {
                for (constructor in definition.constructors) {
                    val constructorName = constructor.name
                    if (BuiltInAbilities.isBuiltInAbility(constructorName)) {
                        throw TypeCheckError(
                            "Cannot define variant constructor '$constructorName' in type '$typeName': " +
                            "name conflicts with built-in ability '$constructorName'. " +
                            "Variant constructors and abilities must have unique names."
                        )
                    }
                    if (abilityDeclarations.containsKey(constructorName)) {
                        throw TypeCheckError(
                            "Cannot define variant constructor '$constructorName' in type '$typeName': " +
                            "name conflicts with ability '$constructorName'. " +
                            "Variant constructors and abilities must have unique names."
                        )
                    }
                    if (effectDeclarations.containsKey(constructorName)) {
                        throw TypeCheckError(
                            "Cannot define variant constructor '$constructorName' in type '$typeName': " +
                            "name conflicts with effect '$constructorName'. " +
                            "Variant constructors and effects must have unique names."
                        )
                    }
                }
            }
        }
    }

    /**
     * Validate that an inherent impl's target type exists (v2 native).
     */
    private fun validateInherentImplTargetType(
        impl: com.mezze.language.parsedast.ParsedImplAbilityDecl,
        typeDeclarations: Map<String, com.mezze.language.parsedast.ParsedTypeDecl>,
        sourceLocation: SourceLocation
    ) {
        for (targetType in impl.targetTypes) {
            val typeName = getTypeNameFromParsedAnnotation(targetType)

            // Primitive types always exist
            if (PrimitiveTypes.isPrimitiveName(typeName)) continue

            // Built-in compound types always exist
            if (typeName in BuiltinTypes.coreTypeNames) continue

            // User-defined type must be declared
            if (!typeDeclarations.containsKey(typeName)) {
                throw TypeCheckError(
                    "Cannot define inherent impl for unknown type '$typeName'. " +
                    "The type must be declared before implementing methods on it.",
                    sourceLocation
                )
            }
        }
    }

    /**
     * Validate that an inherent impl is defined in the same module as the target type (v2 native).
     */
    private fun validateInherentImplLocality(
        impl: com.mezze.language.parsedast.ParsedImplAbilityDecl,
        localTypeNames: Set<String>,
        sourceLocation: SourceLocation
    ) {
        for (targetType in impl.targetTypes) {
            val typeName = getTypeNameFromParsedAnnotation(targetType)

            if (!localTypeNames.contains(typeName)) {
                throw TypeCheckError(
                    "Cannot define inherent impl for type '$typeName' in this module. " +
                    "Inherent impls can only be defined where the type is defined. " +
                    "Move this impl to the module where '$typeName' is declared.",
                    sourceLocation
                )
            }
        }
    }

    /**
     * Validate that impl method names don't conflict with fields on the target type (v2 native).
     */
    private fun validateNoFieldMethodConflict(
        impl: com.mezze.language.parsedast.ParsedImplAbilityDecl,
        typeDeclarations: Map<String, com.mezze.language.parsedast.ParsedTypeDecl>,
        sourceLocation: SourceLocation
    ) {
        if (typeDeclarations.isEmpty()) return

        val implMethodNames = impl.operations.map { it.name }.toSet()
        if (implMethodNames.isEmpty()) return

        for (targetType in impl.targetTypes) {
            val typeName = getTypeNameFromParsedAnnotation(targetType)
            val typeDecl = typeDeclarations[typeName] ?: continue
            val definition = typeDecl.definition

            // Only record types have fields to conflict with
            if (definition is com.mezze.language.parsedast.ParsedRecordDef) {
                val fieldNames = definition.fields.map { it.name }.toSet()
                val conflicts = implMethodNames.intersect(fieldNames)
                if (conflicts.isNotEmpty()) {
                    val conflictList = conflicts.joinToString(", ") { "'$it'" }
                    throw TypeCheckError(
                        "Cannot implement ${if (impl.isInherent) "methods" else "ability '${impl.abilityName}'"} " +
                            "for type '$typeName': method names $conflictList conflict with fields " +
                            "already defined on '$typeName'. Rename the fields or the ability methods to avoid ambiguity.",
                        sourceLocation
                    )
                }
            }
        }
    }

    /**
     * Convert a v2 ParsedTypeAnnotation to string for error messages.
     */
    private fun parsedTypeAnnotationToString(annotation: com.mezze.language.parsedast.ParsedTypeAnnotation): String {
        return when (annotation) {
            is com.mezze.language.parsedast.ParsedNamedType -> annotation.name
            is com.mezze.language.parsedast.ParsedFunctionType -> {
                "${parsedTypeAnnotationToString(annotation.paramType)} -> ${parsedTypeAnnotationToString(annotation.returnType)}"
            }
            is com.mezze.language.parsedast.ParsedRecordType -> {
                val fields = annotation.entries.joinToString(", ") { "${it.name}: ${parsedTypeAnnotationToString(it.type)}" }
                "{$fields}"
            }
            is com.mezze.language.parsedast.ParsedVariantType -> {
                val ctors = annotation.constructors.joinToString(", ") {
                    val dataType = it.dataType
                    if (dataType != null) "${it.name} ${parsedTypeAnnotationToString(dataType)}"
                    else it.name
                }
                "| $ctors |"
            }
            is com.mezze.language.parsedast.ParsedTypeApp -> {
                if (annotation.args.isEmpty()) annotation.constructor
                else "${annotation.constructor} ${annotation.args.joinToString(" ") { parsedTypeAnnotationToString(it) }}"
            }
            is com.mezze.language.parsedast.ParsedSeqType -> "[${parsedTypeAnnotationToString(annotation.elementType)}]"
            is com.mezze.language.parsedast.ParsedQualifiedType -> {
                val qualifiedName = annotation.modulePath.joinToString("::") + "::" + annotation.typeName
                if (annotation.args.isEmpty()) qualifiedName
                else "$qualifiedName ${annotation.args.joinToString(" ") { parsedTypeAnnotationToString(it) }}"
            }
        }
    }
}
