package com.mezze.language.typechecker.validation

import com.mezze.language.ast.BuiltinTypes
import com.mezze.language.parsedast.*
import com.mezze.language.typechecker.TypeCheckError
import com.mezze.language.typechecker.abilities.BuiltInAbilities

/**
 * Validates that declarations are not duplicated within a parsed module.
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
 * Works directly on v2 ParsedDecl types (no v1 conversion).
 */
object DuplicateDeclarationValidator {
    /**
     * Validate all duplicate declarations in a parsed module.
     * This should be called BEFORE context building.
     *
     * @param module The parsed module to validate
     * @throws TypeCheckError if any duplicate declarations are found
     */
    fun validateModule(module: ParsedModule) {
        validateDuplicateTypes(module)
        validateDuplicateConstructors(module)
        validateNewtypeConstructors(module)
        validateFreeTypeVariables(module)
        validateDuplicateEffects(module)
        validateDuplicatePerformers(module)
        validateDuplicateAbilities(module)
        validateDuplicateAbilityImpls(module)
        validateDuplicateInherentMethods(module)
        validateDuplicateVariables(module)
    }

    /**
     * Validate using a list of resolved declarations.
     * Used by ContextBuilder which merges parsed ability impls and performer decls
     * before calling validation.
     */
    fun validateDecls(decls: List<ParsedDecl>) {
        val syntheticModule = ParsedModule("<validation>", decls)
        validateModule(syntheticModule)
    }

    /**
     * Validate no duplicate type declarations
     */
    private fun validateDuplicateTypes(module: ParsedModule) {
        val declaredTypes = mutableSetOf<String>()

        for (item in module.decls.filterIsInstance<ParsedTypeDecl>()) {
            if (declaredTypes.contains(item.name)) {
                throw TypeCheckError(
                    "Duplicate type declaration: type '${item.name}' is already declared",
                    item.loc
                )
            }
            declaredTypes.add(item.name)
        }
    }

    /**
     * Built-in variant constructors that cannot be redefined in OTHER types.
     */
    private val BUILTIN_CONSTRUCTORS = mapOf(
        BuiltinTypes.SOME to BuiltinTypes.OPTION,
        BuiltinTypes.NONE to BuiltinTypes.OPTION,
        BuiltinTypes.OK to BuiltinTypes.RESULT,
        BuiltinTypes.ERR to BuiltinTypes.RESULT
    )

    /**
     * Validate no duplicate constructor names across types.
     * Also validates that user-defined constructors don't conflict with built-in constructors.
     */
    private fun validateDuplicateConstructors(module: ParsedModule) {
        val constructorToTypeMap = mutableMapOf<String, String>()

        for (typeDecl in module.decls.filterIsInstance<ParsedTypeDecl>()) {
            val definition = typeDecl.definition
            when (definition) {
                is ParsedVariantDef -> {
                    for (constructor in definition.constructors) {
                        val constructorName = constructor.name

                        val builtinType = BUILTIN_CONSTRUCTORS[constructorName]
                        if (builtinType != null && typeDecl.name != builtinType) {
                            throw TypeCheckError(
                                "Cannot redefine built-in constructor: '$constructorName' is already defined " +
                                "in built-in type '$builtinType'. Use the built-in '$builtinType' type or choose a different constructor name.",
                                typeDecl.loc
                            )
                        }

                        val existingType = constructorToTypeMap[constructorName]
                        if (existingType != null) {
                            throw TypeCheckError(
                                "Duplicate constructor name: constructor '$constructorName' is already defined " +
                                "in type '$existingType', cannot reuse in type '${typeDecl.name}'",
                                typeDecl.loc
                            )
                        }
                        constructorToTypeMap[constructorName] = typeDecl.name
                    }
                }
                is ParsedNewtypeDef -> {
                    val constructorName = definition.constructor

                    val builtinType = BUILTIN_CONSTRUCTORS[constructorName]
                    if (builtinType != null && typeDecl.name != builtinType) {
                        throw TypeCheckError(
                            "Cannot redefine built-in constructor: '$constructorName' is already defined " +
                            "in built-in type '$builtinType'. Use the built-in '$builtinType' type or choose a different constructor name.",
                            typeDecl.loc
                        )
                    }

                    val existingType = constructorToTypeMap[constructorName]
                    if (existingType != null) {
                        throw TypeCheckError(
                            "Duplicate constructor name: constructor '$constructorName' is already defined " +
                            "in type '$existingType', cannot reuse in type '${typeDecl.name}'",
                            typeDecl.loc
                        )
                    }
                    constructorToTypeMap[constructorName] = typeDecl.name
                }
                is ParsedRecordDef -> {
                    // Records don't have constructors in the same way
                }
            }
        }
    }

    /**
     * Validate newtype constructor naming conventions.
     */
    private fun validateNewtypeConstructors(module: ParsedModule) {
        for (typeDecl in module.decls.filterIsInstance<ParsedTypeDecl>()) {
            val definition = typeDecl.definition
            if (definition is ParsedNewtypeDef) {
                val constructorName = definition.constructor

                if (!constructorName.first().isUpperCase()) {
                    throw TypeCheckError(
                        "Newtype constructor '$constructorName' must start with an uppercase letter",
                        typeDecl.loc
                    )
                }

                if (BuiltInAbilities.isBuiltInAbility(constructorName)) {
                    throw TypeCheckError(
                        "Newtype constructor '$constructorName' is already used by a built-in ability",
                        typeDecl.loc
                    )
                }
            }
        }
    }

    /**
     * Validate that type declarations don't have free (undeclared) type variables.
     */
    private fun validateFreeTypeVariables(module: ParsedModule) {
        for (typeDecl in module.decls.filterIsInstance<ParsedTypeDecl>()) {
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
                val errorLocation = freeTypeVars.firstNotNullOfOrNull { it.second } ?: typeDecl.loc
                throw TypeCheckError(
                    "Type '${typeDecl.name}' has undeclared type variable(s): $freeVarList. $suggestion",
                    errorLocation
                )
            }
        }
    }

    /**
     * Collect all type variable names used in a type definition, with their source locations.
     */
    private fun collectTypeVariablesWithLocation(definition: ParsedTypeDef): List<Pair<String, Loc?>> {
        return when (definition) {
            is ParsedVariantDef -> {
                definition.constructors.flatMap { ctor ->
                    ctor.dataType?.let { collectTypeVariablesFromParsedAnnotation(it) } ?: emptyList()
                }
            }
            is ParsedRecordDef -> {
                definition.fields.flatMap { field ->
                    collectTypeVariablesFromParsedAnnotation(field.type)
                }
            }
            is ParsedNewtypeDef -> {
                collectTypeVariablesFromParsedAnnotation(definition.wrappedType)
            }
        }
    }

    /**
     * Collect all type variable names with their source locations from a parsed type annotation.
     */
    private fun collectTypeVariablesFromParsedAnnotation(annotation: ParsedTypeAnnotation): List<Pair<String, Loc?>> {
        return when (annotation) {
            is ParsedNamedType -> {
                if (annotation.name.startsWith("'")) {
                    listOf(annotation.name.removePrefix("'") to annotation.loc)
                } else if (annotation.name.first().isLowerCase()) {
                    listOf(annotation.name to annotation.loc)
                } else {
                    emptyList()
                }
            }
            is ParsedTypeApp -> {
                annotation.args.flatMap { collectTypeVariablesFromParsedAnnotation(it) }
            }
            is ParsedFunctionType -> {
                collectTypeVariablesFromParsedAnnotation(annotation.paramType) +
                    collectTypeVariablesFromParsedAnnotation(annotation.returnType)
            }
            is ParsedRecordType -> {
                annotation.entries.flatMap { entry ->
                    collectTypeVariablesFromParsedAnnotation(entry.type)
                }
            }
            is ParsedVariantType -> {
                annotation.constructors.flatMap { ctor ->
                    ctor.dataType?.let { collectTypeVariablesFromParsedAnnotation(it) } ?: emptyList()
                }
            }
            is ParsedSeqType -> {
                collectTypeVariablesFromParsedAnnotation(annotation.elementType)
            }
            is ParsedQualifiedType -> {
                annotation.args.flatMap { collectTypeVariablesFromParsedAnnotation(it) }
            }
        }
    }

    /**
     * Validate no duplicate effect declarations
     */
    private fun validateDuplicateEffects(module: ParsedModule) {
        val declaredEffects = mutableSetOf<String>()

        for (effect in module.decls.filterIsInstance<ParsedEffectDecl>()) {
            if (declaredEffects.contains(effect.name)) {
                throw TypeCheckError(
                    "Duplicate effect declaration: effect '${effect.name}' is already defined",
                    effect.loc
                )
            }
            declaredEffects.add(effect.name)
        }
    }

    /**
     * Validate no duplicate performer/handler declarations
     */
    private fun validateDuplicatePerformers(module: ParsedModule) {
        val declaredPerformers = mutableSetOf<String>()

        for (impl in module.decls.filterIsInstance<ParsedPerformerDecl>()) {
            if (declaredPerformers.contains(impl.performerName)) {
                throw TypeCheckError(
                    "Duplicate effect handler declaration: performer '${impl.performerName}' is already defined",
                    impl.loc
                )
            }
            declaredPerformers.add(impl.performerName)
        }
    }

    /**
     * Validate no duplicate ability declarations
     */
    private fun validateDuplicateAbilities(module: ParsedModule) {
        val declaredAbilities = mutableSetOf<String>()

        for (ability in module.decls.filterIsInstance<ParsedAbilityDecl>()) {
            if (declaredAbilities.contains(ability.name)) {
                throw TypeCheckError(
                    "Duplicate ability declaration: ability '${ability.name}' is already defined",
                    ability.loc
                )
            }
            declaredAbilities.add(ability.name)
        }
    }

    /**
     * Validate no duplicate ability implementations for same type
     */
    private fun validateDuplicateAbilityImpls(module: ParsedModule) {
        val abilityImplKeys = mutableSetOf<String>()

        for (impl in module.decls.filterIsInstance<ParsedImplAbilityDecl>()) {
            val abilityTypeArgNames = impl.abilityTypeArgs.map { parsedTypeAnnotationToString(it) }
            val targetTypeNames = impl.targetTypes.map { parsedTypeAnnotationToString(it) }
            val allTypeNames = abilityTypeArgNames + targetTypeNames
            val implLabel = impl.abilityName ?: "inherent"
            val key = "${implLabel}_${allTypeNames.joinToString("_")}"

            if (abilityImplKeys.contains(key)) {
                throw TypeCheckError(
                    "Duplicate ${if (impl.isInherent) "inherent" else "ability"} implementation: '${implLabel}' is already implemented " +
                    "for type(s) ${allTypeNames.joinToString(", ")}",
                    impl.loc
                )
            }
            abilityImplKeys.add(key)
        }
    }

    /**
     * Validate no duplicate method names across inherent impls on the same type.
     */
    private fun validateDuplicateInherentMethods(module: ParsedModule) {
        val methodsByType = mutableMapOf<String, MutableMap<String, Loc?>>()

        for (impl in module.decls.filterIsInstance<ParsedImplAbilityDecl>()) {
            if (!impl.isInherent) continue

            val targetTypeNames = impl.targetTypes.map { parsedTypeAnnotationToString(it) }
            val typeKey = targetTypeNames.joinToString("_")

            val existingMethods = methodsByType.getOrPut(typeKey) { mutableMapOf() }

            for (op in impl.operations) {
                val existing = existingMethods[op.name]
                if (existing != null) {
                    throw TypeCheckError(
                        "Duplicate method '${op.name}' in inherent impl for type(s) ${targetTypeNames.joinToString(", ")}. " +
                        "Each method name must be unique across all inherent impls for a type.",
                        op.loc
                    )
                }
                existingMethods[op.name] = op.loc
            }
        }
    }

    /**
     * Validate no duplicate value-namespace declarations.
     *
     * Value namespace includes:
     * - `let x` bindings
     * - performer names (e.g. `impl Eff for Performer`) — they're invokable
     *   as handles in `perform Performer in ...` so they're value-level
     * - declared variant + newtype constructors are also in value namespace,
     *   but their cross-collision with let/performer is enforced via
     *   `validateDuplicateConstructors` (constructors globally unique) plus
     *   the type-namespace checks. We just need to make sure let and
     *   performer don't collide with each other or with constructors here.
     *
     * #311.
     */
    private fun validateDuplicateVariables(module: ParsedModule) {
        // Pre-collect performer names so we can cross-check against lets.
        val performerNames = module.decls
            .filterIsInstance<ParsedPerformerDecl>()
            .map { it.performerName }
            .toSet()

        // Pre-collect declared constructor names (variant + newtype) so let
        // bindings can't shadow a constructor that's also in value ns.
        val constructorNames = mutableSetOf<String>()
        for (typeDecl in module.decls.filterIsInstance<ParsedTypeDecl>()) {
            when (val def = typeDecl.definition) {
                is ParsedVariantDef -> def.constructors.forEach { constructorNames.add(it.name) }
                is ParsedNewtypeDef -> constructorNames.add(def.constructor)
                else -> {}  // record defs have no constructor in value ns
            }
        }

        val declaredVariables = mutableSetOf<String>()

        for (item in module.decls.filterIsInstance<ParsedLetDecl>()) {
            val boundNames = item.pattern.extractBindings()

            for (name in boundNames) {
                if (declaredVariables.contains(name)) {
                    throw TypeCheckError(
                        "Duplicate variable declaration: '$name' is already defined in this scope",
                        item.value.loc
                    )
                }
                if (name in performerNames) {
                    throw TypeCheckError(
                        "Name '$name' collides with an effect performer of the same name. " +
                            "Performers and let bindings share the value namespace; rename one.",
                        item.value.loc
                    )
                }
                if (name in constructorNames) {
                    throw TypeCheckError(
                        "Name '$name' collides with a variant or newtype constructor of the same name. " +
                            "Constructors and let bindings share the value namespace; rename one.",
                        item.value.loc
                    )
                }
                declaredVariables.add(name)
            }
        }
    }

    /**
     * Convert parsed type annotation to string for duplicate checking.
     */
    fun parsedTypeAnnotationToString(annotation: ParsedTypeAnnotation): String {
        return when (annotation) {
            is ParsedNamedType -> annotation.name
            is ParsedFunctionType -> "Function"
            is ParsedTypeApp -> {
                val args = annotation.args.joinToString("_") { parsedTypeAnnotationToString(it) }
                if (args.isEmpty()) annotation.constructor else "${annotation.constructor}_$args"
            }
            is ParsedRecordType -> {
                val fieldNames = annotation.entries.map { it.name }.sorted().joinToString("_")
                if (fieldNames.isEmpty()) "Record" else "Record_$fieldNames"
            }
            is ParsedVariantType -> {
                val constructorNames = annotation.constructors.map { it.name }.sorted().joinToString("_")
                if (constructorNames.isEmpty()) "Variant" else "Variant_$constructorNames"
            }
            is ParsedSeqType -> "Array_${parsedTypeAnnotationToString(annotation.elementType)}"
            is ParsedQualifiedType -> {
                val modulePart = annotation.modulePath.joinToString("_")
                val args = annotation.args.joinToString("_") { parsedTypeAnnotationToString(it) }
                if (args.isEmpty()) "${modulePart}_${annotation.typeName}" else "${modulePart}_${annotation.typeName}_$args"
            }
        }
    }
}

