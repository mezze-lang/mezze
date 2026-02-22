package com.mezze.language.typechecker.validation

import com.mezze.language.ast.*
import com.mezze.language.typechecker.TypeCheckError
import com.mezze.language.typechecker.abilities.BuiltInAbilities

/**
 * Validates that declarations are not duplicated within a program.
 *
 * Implements Bug #9 validation checks:
 * - Bug #9a: Duplicate variable declarations in same scope
 * - Bug #9b: Duplicate effect declarations
 * - Bug #9c: Duplicate effect handler/impl declarations
 * - Bug #9d: Duplicate ability declarations
 * - Bug #9e: Duplicate ability implementations for same type
 *
 * Also validates:
 * - Duplicate type declarations
 * - Duplicate constructor names across types
 *
 * Based on validation logic from OldTypeChecker.kt:839-1143
 */
object DuplicateDeclarationValidator {
    /**
     * Validate all duplicate declarations in a program.
     * This should be called BEFORE context building.
     *
     * @param program The program to validate
     * @throws TypeCheckError if any duplicate declarations are found
     */
    fun validateProgram(program: Program) {
        validateDuplicateTypes(program)
        validateDuplicateConstructors(program)
        validateNewtypeConstructors(program)
        validateFreeTypeVariables(program)
        validateDuplicateEffects(program)
        validateDuplicatePerformers(program)
        validateDuplicateAbilities(program)
        validateDuplicateAbilityImpls(program)
        validateDuplicateVariables(program)
        validateNoPubOnAlwaysPublicDeclarations(program)
    }

    /**
     * Validate that pub is not used on abilities, effects, and their impls.
     * These are always public by default - the pub keyword is unnecessary.
     */
    private fun validateNoPubOnAlwaysPublicDeclarations(program: Program) {
        for (item in program.items) {
            when (item) {
                is AbilityDeclaration -> {
                    if (item.visibility == Visibility.PUBLIC) {
                        throw TypeCheckError(
                            "The 'pub' keyword is not needed for ability '${item.name}'. " +
                            "Abilities are always public by default. Remove the 'pub' keyword.",
                            item.sourceLocation
                        )
                    }
                }
                is EffectDeclaration -> {
                    if (item.visibility == Visibility.PUBLIC) {
                        throw TypeCheckError(
                            "The 'pub' keyword is not needed for effect '${item.name}'. " +
                            "Effects are always public by default. Remove the 'pub' keyword.",
                            item.sourceLocation
                        )
                    }
                }
                // Note: ImplDeclaration and ImplAbilityDeclaration don't have visibility fields
                // in the AST, so they already can't have pub keyword
                else -> {}
            }
        }
    }

    /**
     * Validate no duplicate type declarations (OldTypeChecker.kt:974-976)
     */
    private fun validateDuplicateTypes(program: Program) {
        val declaredTypes = mutableSetOf<String>()

        for (item in program.items.filterIsInstance<TypeDeclaration>()) {
            if (declaredTypes.contains(item.name)) {
                throw TypeCheckError(
                    "Duplicate type declaration: type '${item.name}' is already declared",
                    item.sourceLocation
                )
            }
            declaredTypes.add(item.name)
        }
    }

    /**
     * Built-in variant constructors that cannot be redefined in OTHER types.
     * These are constructors from built-in types like Option (Some/None) and Result (Ok/Err).
     *
     * Users CAN redefine the built-in type itself (e.g., `type Option = | Some, None |`)
     * but CANNOT use these constructors in a differently-named type
     * (e.g., `type MyOption = | Some, None |` is NOT allowed).
     */
    private val BUILTIN_CONSTRUCTORS = mapOf(
        // Option type constructors
        "Some" to "Option",
        "None" to "Option",
        // Result type constructors
        "Ok" to "Result",
        "Err" to "Result"
        // Note: True/False are literals, not variant constructors
    )

    /**
     * Validate no duplicate constructor names across types (OldTypeChecker.kt:1009-1067)
     * Also validates that user-defined constructors don't conflict with built-in constructors
     * (unless the user is redefining the built-in type itself).
     */
    private fun validateDuplicateConstructors(program: Program) {
        val constructorToTypeMap = mutableMapOf<String, String>()

        for (typeDecl in program.items.filterIsInstance<TypeDeclaration>()) {
            val definition = typeDecl.definition
            when (definition) {
                is VariantDefinition -> {
                    for (constructor in definition.constructors) {
                        val constructorName = constructor.name

                        // Check against built-in constructors, but allow redefining the same type
                        // e.g., `type Option = | Some, None |` is OK, but `type MyOption = | Some, None |` is NOT
                        val builtinType = BUILTIN_CONSTRUCTORS[constructorName]
                        if (builtinType != null && typeDecl.name != builtinType) {
                            throw TypeCheckError(
                                "Cannot redefine built-in constructor: '$constructorName' is already defined " +
                                "in built-in type '$builtinType'. Use the built-in '$builtinType' type or choose a different constructor name.",
                                typeDecl.sourceLocation
                            )
                        }

                        val existingType = constructorToTypeMap[constructorName]
                        if (existingType != null) {
                            throw TypeCheckError(
                                "Duplicate constructor name: constructor '$constructorName' is already defined " +
                                "in type '$existingType', cannot reuse in type '${typeDecl.name}'",
                                typeDecl.sourceLocation
                            )
                        }
                        constructorToTypeMap[constructorName] = typeDecl.name
                    }
                }
                is NewtypeDefinition -> {
                    val constructorName = definition.constructor

                    // Check against built-in constructors
                    val builtinType = BUILTIN_CONSTRUCTORS[constructorName]
                    if (builtinType != null && typeDecl.name != builtinType) {
                        throw TypeCheckError(
                            "Cannot redefine built-in constructor: '$constructorName' is already defined " +
                            "in built-in type '$builtinType'. Use the built-in '$builtinType' type or choose a different constructor name.",
                            typeDecl.sourceLocation
                        )
                    }

                    val existingType = constructorToTypeMap[constructorName]
                    if (existingType != null) {
                        throw TypeCheckError(
                            "Duplicate constructor name: constructor '$constructorName' is already defined " +
                            "in type '$existingType', cannot reuse in type '${typeDecl.name}'",
                            typeDecl.sourceLocation
                        )
                    }
                    constructorToTypeMap[constructorName] = typeDecl.name
                }
                is RecordDefinition -> {
                    // Records don't have constructors in the same way
                }
            }
        }
    }

    /**
     * Validate newtype constructor naming conventions (OldTypeChecker.kt:1517-1530)
     *
     * - Constructor must start with an uppercase letter
     * - Constructor cannot collide with built-in ability names
     */
    private fun validateNewtypeConstructors(program: Program) {
        for (typeDecl in program.items.filterIsInstance<TypeDeclaration>()) {
            val definition = typeDecl.definition
            if (definition is NewtypeDefinition) {
                val constructorName = definition.constructor

                // Validate constructor naming convention (must start with uppercase)
                if (!constructorName.first().isUpperCase()) {
                    throw TypeCheckError(
                        "Newtype constructor '$constructorName' must start with an uppercase letter",
                        typeDecl.sourceLocation
                    )
                }

                // Validate constructor doesn't collide with built-in ability names
                if (BuiltInAbilities.isBuiltInAbility(constructorName)) {
                    throw TypeCheckError(
                        "Newtype constructor '$constructorName' is already used by a built-in ability",
                        typeDecl.sourceLocation
                    )
                }
            }
        }
    }

    /**
     * Validate that type declarations don't have free (undeclared) type variables.
     *
     * For example:
     * - `type Color 'a = | Red, Blue { n: 'a } |` is VALID ('a is declared)
     * - `type Color = | Red, Blue { n: 'a } |` is INVALID ('a is free/undeclared)
     *
     * Free type variables in type definitions are confusing because:
     * 1. The type appears monomorphic but has polymorphic fields
     * 2. Different usages can unify the free variable to different types
     * 3. It's unclear what type the field should have
     */
    private fun validateFreeTypeVariables(program: Program) {
        for (typeDecl in program.items.filterIsInstance<TypeDeclaration>()) {
            // typeParams are stored WITH the apostrophe prefix (e.g., "'a")
            val declaredParams = typeDecl.typeParams.map { it.removePrefix("'") }.toSet()
            val usedTypeVarsWithLocation = collectTypeVariablesWithLocation(typeDecl.definition)

            val freeTypeVars = usedTypeVarsWithLocation.filter { it.first !in declaredParams }
            if (freeTypeVars.isNotEmpty()) {
                val freeVarList = freeTypeVars.map { it.first }.distinct().sorted().joinToString(", ") { "'$it" }
                val suggestion = if (freeTypeVars.map { it.first }.distinct().size == 1) {
                    "Add it as a type parameter: type ${typeDecl.name} '${freeTypeVars.first().first} = ..."
                } else {
                    val params = freeTypeVars.map { it.first }.distinct().sorted().joinToString(" ") { "'$it" }
                    "Add them as type parameters: type ${typeDecl.name} $params = ..."
                }
                // Use the source location of the first free type variable if available
                val errorLocation = freeTypeVars.firstNotNullOfOrNull { it.second } ?: typeDecl.sourceLocation
                throw TypeCheckError(
                    "Type '${typeDecl.name}' has undeclared type variable(s): $freeVarList. $suggestion",
                    errorLocation
                )
            }
        }
    }

    /**
     * Collect all type variable names used in a type definition, with their source locations.
     *
     * All type variables must be declared as type parameters - no implicit
     * rank-2 polymorphism. This follows OCaml and Roc's approach.
     */
    private fun collectTypeVariablesWithLocation(definition: TypeDefinition): List<Pair<String, SourceLocation?>> {
        return when (definition) {
            is VariantDefinition -> {
                definition.constructors.flatMap { ctor ->
                    ctor.dataType?.let { collectTypeVariablesWithLocationFromAnnotation(it) } ?: emptyList()
                }
            }
            is RecordDefinition -> {
                definition.fields.flatMap { field ->
                    collectTypeVariablesWithLocationFromAnnotation(field.type)
                }
            }
            is NewtypeDefinition -> {
                collectTypeVariablesWithLocationFromAnnotation(definition.wrappedType)
            }
        }
    }

    /**
     * Collect all type variable names used in a type definition (without location).
     * Kept for backward compatibility.
     */
    private fun collectTypeVariablesInDefinition(definition: TypeDefinition): Set<String> {
        return collectTypeVariablesWithLocation(definition).map { it.first }.toSet()
    }

    /**
     * Collect all type variable names with their source locations from a type annotation.
     * Note: Most type annotations don't have source locations, so we return null for those.
     */
    private fun collectTypeVariablesWithLocationFromAnnotation(annotation: TypeAnnotation): List<Pair<String, SourceLocation?>> {
        return when (annotation) {
            is IdentifierTypeAnnotation -> {
                // Type variables start with apostrophe or lowercase
                if (annotation.name.startsWith("'")) {
                    listOf(annotation.name.removePrefix("'") to annotation.sourceLocation)
                } else if (annotation.name.first().isLowerCase()) {
                    listOf(annotation.name to annotation.sourceLocation)
                } else {
                    emptyList()
                }
            }
            is TypeApplicationAnnotation -> {
                annotation.args.flatMap { collectTypeVariablesWithLocationFromAnnotation(it) }
            }
            is FunctionTypeAnnotation -> {
                collectTypeVariablesWithLocationFromAnnotation(annotation.paramType) +
                    collectTypeVariablesWithLocationFromAnnotation(annotation.returnType) +
                    (annotation.effects?.let { collectTypeVariablesWithLocationFromAnnotation(it) } ?: emptyList())
            }
            is RecordTypeAnnotation -> {
                annotation.entries.flatMap { entry ->
                    collectTypeVariablesWithLocationFromAnnotation(entry.type)
                }
            }
            is VariantTypeAnnotation -> {
                annotation.constructors.flatMap { ctor ->
                    ctor.dataType?.let { collectTypeVariablesWithLocationFromAnnotation(it) } ?: emptyList()
                }
            }
            is ArrayTypeAnnotation -> {
                collectTypeVariablesWithLocationFromAnnotation(annotation.elementType)
            }
            is EffectRowAnnotation -> {
                // Effect row annotations might contain type variables in their effect parameters
                emptyList()
            }
            is QualifiedTypeAnnotation -> {
                annotation.args.flatMap { collectTypeVariablesWithLocationFromAnnotation(it) }
            }
        }
    }

    /**
     * Collect all type variable names used in a type annotation.
     * All type variables found must be declared as type parameters.
     */
    private fun collectTypeVariablesInAnnotation(annotation: TypeAnnotation): Set<String> {
        return when (annotation) {
            is IdentifierTypeAnnotation -> {
                // Type variables start with apostrophe or lowercase
                if (annotation.name.startsWith("'")) {
                    setOf(annotation.name.removePrefix("'"))
                } else if (annotation.name.first().isLowerCase()) {
                    setOf(annotation.name)
                } else {
                    emptySet()
                }
            }
            is TypeApplicationAnnotation -> {
                annotation.args.flatMap { collectTypeVariablesInAnnotation(it) }.toSet()
            }
            is FunctionTypeAnnotation -> {
                collectTypeVariablesInAnnotation(annotation.paramType) +
                    collectTypeVariablesInAnnotation(annotation.returnType) +
                    (annotation.effects?.let { collectTypeVariablesInAnnotation(it) } ?: emptySet())
            }
            is RecordTypeAnnotation -> {
                annotation.entries.flatMap { entry ->
                    collectTypeVariablesInAnnotation(entry.type)
                }.toSet()
            }
            is VariantTypeAnnotation -> {
                annotation.constructors.flatMap { ctor ->
                    ctor.dataType?.let { collectTypeVariablesInAnnotation(it) } ?: emptySet()
                }.toSet()
            }
            is ArrayTypeAnnotation -> {
                collectTypeVariablesInAnnotation(annotation.elementType)
            }
            is EffectRowAnnotation -> {
                // Effect row annotations might contain type variables in their effect parameters
                emptySet()
            }
            is QualifiedTypeAnnotation -> {
                annotation.args.flatMap { collectTypeVariablesInAnnotation(it) }.toSet()
            }
        }
    }

    /**
     * Validate no duplicate effect declarations (Bug #9b, OldTypeChecker.kt:880-904)
     */
    private fun validateDuplicateEffects(program: Program) {
        val declaredEffects = mutableSetOf<String>()

        for (effect in program.items.filterIsInstance<EffectDeclaration>()) {
            if (declaredEffects.contains(effect.name)) {
                throw TypeCheckError(
                    "Duplicate effect declaration: effect '${effect.name}' is already defined",
                    effect.sourceLocation
                )
            }
            declaredEffects.add(effect.name)
        }
    }

    /**
     * Validate no duplicate performer/handler declarations (Bug #9c, OldTypeChecker.kt:839-876)
     */
    private fun validateDuplicatePerformers(program: Program) {
        val declaredPerformers = mutableSetOf<String>()

        for (impl in program.items.filterIsInstance<ImplDeclaration>()) {
            if (declaredPerformers.contains(impl.performerName)) {
                throw TypeCheckError(
                    "Duplicate effect handler declaration: performer '${impl.performerName}' is already defined",
                    impl.sourceLocation
                )
            }
            declaredPerformers.add(impl.performerName)
        }
    }

    /**
     * Validate no duplicate ability declarations (Bug #9d, OldTypeChecker.kt:907-931)
     */
    private fun validateDuplicateAbilities(program: Program) {
        val declaredAbilities = mutableSetOf<String>()

        for (ability in program.items.filterIsInstance<AbilityDeclaration>()) {
            if (declaredAbilities.contains(ability.name)) {
                throw TypeCheckError(
                    "Duplicate ability declaration: ability '${ability.name}' is already defined",
                    ability.sourceLocation
                )
            }
            declaredAbilities.add(ability.name)
        }
    }

    /**
     * Validate no duplicate ability implementations (Bug #9e, OldTypeChecker.kt:933-968)
     *
     * Two ability implementations are considered duplicates if they implement
     * the same ability for the same type(s).
     */
    private fun validateDuplicateAbilityImpls(program: Program) {
        val abilityImplKeys = mutableSetOf<String>()

        for (impl in program.items.filterIsInstance<ImplAbilityDeclaration>()) {
            // Create a key from ability name + target type names
            val targetTypeNames = impl.targetTypes.map { typeAnnotationToString(it) }
            val key = "${impl.abilityName}_${targetTypeNames.joinToString("_")}"

            if (abilityImplKeys.contains(key)) {
                throw TypeCheckError(
                    "Duplicate ability implementation: '${impl.abilityName}' is already implemented " +
                    "for type(s) ${targetTypeNames.joinToString(", ")}",
                    impl.sourceLocation
                )
            }
            abilityImplKeys.add(key)
        }
    }

    /**
     * Validate no duplicate variable declarations (Bug #9a, OldTypeChecker.kt:1123-1143)
     */
    private fun validateDuplicateVariables(program: Program) {
        val declaredVariables = mutableSetOf<String>()

        for (item in program.items.filterIsInstance<LetBinding>()) {
            // Extract all variable names from the pattern using the AST utility
            val boundNames = item.pattern.extractBindings()

            for (name in boundNames) {
                if (declaredVariables.contains(name)) {
                    throw TypeCheckError(
                        "Duplicate variable declaration: '$name' is already defined in this scope",
                        item.expression.sourceLocation
                    )
                }
                declaredVariables.add(name)
            }
        }
    }

    /**
     * Convert type annotation to string for duplicate checking.
     * This doesn't need to be perfect, just unique enough to distinguish types.
     */
    private fun typeAnnotationToString(annotation: TypeAnnotation): String {
        return when (annotation) {
            is IdentifierTypeAnnotation -> annotation.name.removePrefix("'")
            is TypeApplicationAnnotation -> {
                val args = annotation.args.joinToString("_") { typeAnnotationToString(it) }
                if (args.isEmpty()) annotation.constructor else "${annotation.constructor}_$args"
            }
            is FunctionTypeAnnotation -> "Function"
            is RecordTypeAnnotation -> "Record"
            is VariantTypeAnnotation -> "Variant"
            is ArrayTypeAnnotation -> "Array_${typeAnnotationToString(annotation.elementType)}"
            is EffectRowAnnotation -> "EffectRow"
            is QualifiedTypeAnnotation -> {
                val modulePart = annotation.modulePath.joinToString("_")
                val args = annotation.args.joinToString("_") { typeAnnotationToString(it) }
                if (args.isEmpty()) "${modulePart}_${annotation.typeName}" else "${modulePart}_${annotation.typeName}_$args"
            }
        }
    }
}
