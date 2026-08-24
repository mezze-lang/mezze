package com.mezze.language.typechecker.validation

import com.mezze.language.ast.BuiltinTypes
import com.mezze.language.parsedast.*
import com.mezze.language.typechecker.TypeCheckError
import com.mezze.language.typechecker.abilities.BuiltInAbilities
import com.mezze.language.parsedast.diagnostics.ErrorCode
import com.mezze.language.ast.ScopedEffectConstants

object DuplicateDeclarationValidator {
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

        fun validateDecls(decls: List<ParsedDecl>) {
        val syntheticModule = ParsedModule("<validation>", decls)
        validateModule(syntheticModule)
    }

    /**
     * Cross-module coherence for GENERIC impls only:
     *   - If an imported module has a generic `impl X for 'a`, the local
     *     module cannot add a concrete `impl X for T` for any T.
     *   - If the local module has a generic `impl X for 'a`, no concrete
     *     imported impl of X should exist (rare — would be flagged locally
     *     by the same rule on the imported side).
     *
     * This complements the Rust-style orphan rule (V0406) — orphan blocks
     * "foreign ability + foreign type" but does NOT block "foreign generic
     * + local newtype" — which would silently break the coherence invariant
     * that generic impls block all specialization forever.
     *
     * Non-generic overlap (concrete + concrete for same T) is already
     * caught by the orphan rule.
     */
    fun validateGenericAgainstImportedImpls(
        localImpls: List<ParsedImplAbilityDecl>,
        importedImpls: Map<com.mezze.language.typedast.Hash, ParsedImplAbilityDecl>
    ) {
        val importedGenericByAbility = mutableMapOf<String, ParsedImplAbilityDecl>()
        val importedConcreteByAbility = mutableMapOf<String, MutableList<ParsedImplAbilityDecl>>()
        for ((_, imp) in importedImpls) {
            if (imp.abilityName == null || imp.isInherent) continue
            if (isGenericReceiver(imp)) {
                importedGenericByAbility[imp.abilityName!!] = imp
            } else {
                importedConcreteByAbility.getOrPut(imp.abilityName!!) { mutableListOf() }.add(imp)
            }
        }

        for (local in localImpls) {
            if (local.abilityName == null || local.isInherent) continue
            val abilityName = local.abilityName!!
            val importedGeneric = importedGenericByAbility[abilityName]

            when {
                // local concrete vs imported generic → conflict
                importedGeneric != null && !isGenericReceiver(local) -> {
                    throw genericVsConcreteOverlapError(abilityName, importedGeneric, local)
                }
                // local generic vs imported concrete → conflict
                isGenericReceiver(local) -> {
                    val importedConcrete = importedConcreteByAbility[abilityName]?.firstOrNull()
                    if (importedConcrete != null) {
                        throw genericVsConcreteOverlapError(abilityName, local, importedConcrete)
                    }
                    // local generic vs imported generic → duplicate generic
                    if (importedGeneric != null) {
                        throw TypeCheckError(
                            "Duplicate generic impl: `impl $abilityName for 'a` is already declared at ${locString(importedGeneric.loc)} (imported into this module). " +
                            "Only one generic impl per ability is allowed reachable in a compilation.",
                            local.loc,
                            code = ErrorCode.V0407_OVERLAPPING_INSTANCE,
                        )
                    }
                }
            }
        }
    }

    private fun validateDuplicateTypes(module: ParsedModule) =
        validateNoDuplicates(
            module.decls.filterIsInstance<ParsedTypeDecl>(),
            { it.name }, { it.loc },
            ErrorCode.V0701_DUPLICATE_DECLARATION
        ) { "Duplicate type declaration: type '$it' is already declared" }

        private val RUNTIME_RESERVED_NAMES = setOf(
        ScopedEffectConstants.ABORT,
        ScopedEffectConstants.RESUME,
        ScopedEffectConstants.SCOPE
    )

    private fun rejectReservedName(name: String, loc: Loc) {
        if (name in RUNTIME_RESERVED_NAMES) {
            throw TypeCheckError(
                "Name '$name' is reserved by the effect-handler runtime. " +
                "It's bound inside op handler bodies (call site of " +
                "`abort {val = ...}` / `resume {val = ...}` / `scope`) and " +
                "cannot be used as a user-defined binding.",
                loc
            )
        }
    }

        private val BUILTIN_CONSTRUCTORS = mapOf(
        BuiltinTypes.SOME to BuiltinTypes.OPTION,
        BuiltinTypes.NONE to BuiltinTypes.OPTION,
        BuiltinTypes.OK to BuiltinTypes.RESULT,
        BuiltinTypes.ERR to BuiltinTypes.RESULT
    )

        private fun validateDuplicateConstructors(module: ParsedModule) {
        val constructorToTypeMap = mutableMapOf<String, String>()
        // Structural shape → first alias name, for CLOSED record aliases. Because
        // records are structural, two closed aliases with an identical
        // field-name+type set are the SAME type wearing two names (no added
        // safety, and it makes structural→alias inference ambiguous). Reject the
        // second, mirroring the variant duplicate-constructor rule. The nominal
        // "two distinct single-field types" case is what `newtype` is for.
        val recordShapeToType = mutableMapOf<String, String>()

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
                                typeDecl.loc,
                                code = ErrorCode.V0701_DUPLICATE_DECLARATION,
                            )
                        }

                        val existingType = constructorToTypeMap[constructorName]
                        if (existingType != null) {
                            throw TypeCheckError(
                                "Duplicate constructor name: constructor '$constructorName' is already defined " +
                                "in type '$existingType', cannot reuse in type '${typeDecl.name}'",
                                typeDecl.loc,
                                code = ErrorCode.V0701_DUPLICATE_DECLARATION,
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
                            typeDecl.loc,
                            code = ErrorCode.V0701_DUPLICATE_DECLARATION,
                        )
                    }

                    val existingType = constructorToTypeMap[constructorName]
                    if (existingType != null) {
                        throw TypeCheckError(
                            "Duplicate constructor name: constructor '$constructorName' is already defined " +
                            "in type '$existingType', cannot reuse in type '${typeDecl.name}'",
                            typeDecl.loc,
                            code = ErrorCode.V0701_DUPLICATE_DECLARATION,
                        )
                    }
                    constructorToTypeMap[constructorName] = typeDecl.name
                }
                is ParsedRecordDef -> {
                    // Only CLOSED records collide by shape — an OPEN record
                    // (`{ a, b, ... }`) admits extra fields, so two open aliases
                    // with the same declared fields are genuinely different types.
                    if (!definition.isOpen) {
                        val shapeKey = definition.fields
                            .map { it.name to parsedTypeAnnotationToString(it.type) }
                            .sortedBy { it.first }
                            .joinToString(",") { "${it.first}:${it.second}" }
                        val existingType = recordShapeToType[shapeKey]
                        if (existingType != null && existingType != typeDecl.name) {
                            throw TypeCheckError(
                                "Record alias '${typeDecl.name}' has the same shape as '$existingType'. " +
                                "Records are structural, so identical closed record aliases are the same type " +
                                "with two names.",
                                typeDecl.loc,
                                code = ErrorCode.V0701_DUPLICATE_DECLARATION,
                                hints = listOf(
                                    "reuse '$existingType' instead, or if you need two DISTINCT types with the " +
                                    "same fields use a `newtype`"
                                ),
                            )
                        }
                        recordShapeToType[shapeKey] = typeDecl.name
                    }
                }
                is ParsedNewtypeDef -> { /* handled separately in validateNewtypeConstructors */ }
                is ParsedTypeAliasDef -> { /* transparent alias — no ctors/fields to dup-check */ }
            }
        }
    }

        private fun validateNewtypeConstructors(module: ParsedModule) {
        // Uppercase-first rule for ALL type-namespace names: types, newtypes,
        // abilities, effects, and variant constructors. Types are always
        // written capitalised at their use sites, so a lowercase decl name
        // means the value never resolves against a type slot and produces a
        // confusing "Unknown type '<name>'" downstream. Reject at decl time
        // with a rename hint.
        fun requireUppercase(kind: String, name: String, loc: com.mezze.language.parsedast.Loc) {
            if (name.isEmpty()) return
            if (!name.first().isUpperCase()) {
                val capitalised = name.replaceFirstChar { it.uppercase() }
                // Context: what and why. Direction: exact rename to apply.
                throw TypeCheckError(
                    "$kind '$name' must start with an uppercase letter — types, abilities, effects, and variant constructors are always capitalised. Rename to '$capitalised'.",
                    loc,
                    code = ErrorCode.V0707_CONSTRUCTOR_MUST_BE_UPPERCASE,
                    hints = listOf("rename `$name` to `$capitalised`"),
                )
            }
        }

        for (typeDecl in module.decls.filterIsInstance<ParsedTypeDecl>()) {
            requireUppercase("Type", typeDecl.name, typeDecl.loc)

            val definition = typeDecl.definition
            if (definition is ParsedNewtypeDef) {
                val constructorName = definition.constructor

                if (!constructorName.first().isUpperCase()) {
                    throw TypeCheckError(
                        "Newtype constructor '$constructorName' must start with an uppercase letter",
                        typeDecl.loc,
                        code = ErrorCode.V0707_CONSTRUCTOR_MUST_BE_UPPERCASE,
                        hints = listOf("rename to `${constructorName.replaceFirstChar { it.uppercase() }}`"),
                    )
                }

                if (BuiltInAbilities.isBuiltInAbility(constructorName)) {
                    throw TypeCheckError(
                        "Newtype constructor '$constructorName' is already used by a built-in ability",
                        typeDecl.loc,
                        code = ErrorCode.V0701_DUPLICATE_DECLARATION,
                        hints = listOf("pick a different name — `$constructorName` is reserved for a built-in ability"),
                    )
                }
            }

            if (definition is ParsedVariantDef) {
                for (ctor in definition.constructors) {
                    requireUppercase("Variant constructor", ctor.name, ctor.loc)
                }
            }
        }

        for (abilityDecl in module.decls.filterIsInstance<ParsedAbilityDecl>()) {
            requireUppercase("Ability", abilityDecl.name, abilityDecl.loc)
        }

        for (effectDecl in module.decls.filterIsInstance<ParsedEffectDecl>()) {
            requireUppercase("Effect", effectDecl.name, effectDecl.loc)
        }
    }

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
                    errorLocation,
                    code = ErrorCode.T0301_UNBOUND_TYPE_VAR,
                    hints = listOf("add an explicit type annotation to fix the type"),
                )
            }
        }

        // Same check for ability method signatures. Bound-tyvars set:
        //   ability.typeParams      (includes the receiver — parser flattens)
        //   ability.associatedTypes (declared via `having 'x`)
        //   this op's with-constraint tyargs (`with X 'y` introduces 'y)
        // Anything else in the op's param/return/effect row is a "magic tyvar"
        // that gets silently generalized per call — the exact pattern that
        // bit codec's `Encode for 'a where { encode : ... -> Result 'out ... }`.
        for (abilityDecl in module.decls.filterIsInstance<ParsedAbilityDecl>()) {
            val abilityLevelBinders = (abilityDecl.typeParams + abilityDecl.associatedTypes)
                .map { it.removePrefix("'") }
                .toSet()
            for (op in abilityDecl.operations) {
                val opLevelBinders = op.abilityConstraints
                    .flatMap { it.typeArgs }
                    .flatMap { collectTypeVariablesFromParsedAnnotation(it) }
                    .map { it.first }
                    .toSet()
                val bound = abilityLevelBinders + opLevelBinders

                val used = mutableListOf<Pair<String, Loc?>>()
                used.addAll(collectTypeVariablesFromParsedAnnotation(op.paramType))
                used.addAll(collectTypeVariablesFromParsedAnnotation(op.returnType))
                // NB: effects rows are excluded — the row-var machinery handles
                // those separately (`<...>` etc).

                val freeVars = used.filter { it.first !in bound }
                if (freeVars.isNotEmpty()) {
                    val freeVarList = freeVars.map { it.first }.distinct().sorted()
                        .joinToString(", ") { "'$it" }
                    val loc = freeVars.firstNotNullOfOrNull { it.second } ?: op.loc
                    val suggestion = if (freeVars.map { it.first }.distinct().size == 1) {
                        val name = freeVars.first().first
                        "Add it as a parameter of ability '${abilityDecl.name}' " +
                            "(e.g. `ability ${abilityDecl.name} '$name for '${abilityDecl.receiverTypeParam?.removePrefix("'") ?: "a"} where { ... }`), " +
                            "as an associated type (`having '$name`), " +
                            "or introduce it in a per-op constraint (`with Something '$name`)."
                    } else {
                        val names = freeVars.map { it.first }.distinct().sorted()
                            .joinToString(" ") { "'$it" }
                        "Add them as parameters of ability '${abilityDecl.name}', " +
                            "as associated types (`having $names`), or via per-op `with` constraints."
                    }
                    throw TypeCheckError(
                        "Ability '${abilityDecl.name}' operation '${op.name}' has undeclared type variable(s): $freeVarList. $suggestion",
                        loc,
                        code = ErrorCode.T0301_UNBOUND_TYPE_VAR,
                        hints = listOf("every tyvar in an ability method must be bound at the ability level or by a per-op `with` constraint"),
                    )
                }
            }
        }
    }

    private fun collectTypeVariablesWithLocation(definition: ParsedTypeDef): List<Pair<String, Loc?>> = when (definition) {
        is ParsedVariantDef -> definition.constructors.flatMap { ctor ->
            ctor.dataType?.let { collectTypeVariablesFromParsedAnnotation(it) } ?: emptyList()
        }
        is ParsedRecordDef -> definition.fields.flatMap { collectTypeVariablesFromParsedAnnotation(it.type) }
        is ParsedNewtypeDef -> collectTypeVariablesFromParsedAnnotation(definition.wrappedType)
        is ParsedTypeAliasDef -> collectTypeVariablesFromParsedAnnotation(definition.aliasedType)
    }

    private fun collectTypeVariablesFromParsedAnnotation(annotation: ParsedTypeAnnotation): List<Pair<String, Loc?>> = when (annotation) {
        is ParsedNamedType -> when {
            annotation.name.startsWith("'") -> listOf(annotation.name.removePrefix("'") to annotation.loc)
            annotation.name.first().isLowerCase() -> listOf(annotation.name to annotation.loc)
            else -> emptyList()
        }
        is ParsedTypeApp -> annotation.args.flatMap { collectTypeVariablesFromParsedAnnotation(it) }
        is ParsedFunctionType -> collectTypeVariablesFromParsedAnnotation(annotation.paramType) +
            collectTypeVariablesFromParsedAnnotation(annotation.returnType)
        is ParsedRecordType -> annotation.entries.flatMap { collectTypeVariablesFromParsedAnnotation(it.type) }
        is ParsedVariantType -> annotation.constructors.flatMap { ctor ->
            ctor.dataType?.let { collectTypeVariablesFromParsedAnnotation(it) } ?: emptyList()
        }
        is ParsedSeqType -> collectTypeVariablesFromParsedAnnotation(annotation.elementType)
        is ParsedQualifiedType -> annotation.args.flatMap { collectTypeVariablesFromParsedAnnotation(it) }
    }

    private fun validateDuplicateEffects(module: ParsedModule) =
        validateNoDuplicates(
            module.decls.filterIsInstance<ParsedEffectDecl>(),
            { it.name }, { it.loc },
            ErrorCode.V0701_DUPLICATE_DECLARATION
        ) { "Duplicate effect declaration: effect '$it' is already defined" }

    private fun validateDuplicatePerformers(module: ParsedModule) =
        validateNoDuplicates(
            module.decls.filterIsInstance<ParsedPerformerDecl>(),
            { it.performerName }, { it.loc },
            ErrorCode.V0701_DUPLICATE_DECLARATION
        ) { "Duplicate effect handler declaration: performer '$it' is already defined" }

    private fun validateDuplicateAbilities(module: ParsedModule) =
        validateNoDuplicates(
            module.decls.filterIsInstance<ParsedAbilityDecl>(),
            { it.name }, { it.loc },
            ErrorCode.V0403_DUPLICATE_ABILITY_DECL
        ) { "Duplicate ability declaration: ability '$it' is already defined" }

        private fun validateDuplicateAbilityImpls(module: ParsedModule) {
        val firstImplByKey = mutableMapOf<String, ParsedImplAbilityDecl>()

        for (impl in module.decls.filterIsInstance<ParsedImplAbilityDecl>()) {
            val targetTypeNames = impl.targetTypes.map { parsedTypeAnnotationToString(it) }
            val implLabel = impl.abilityName ?: "inherent"
            // Overlap key = ability + the STRUCTURE of its type args. It must NOT
            // include the impl bodies (two impls of the same ability+types overlap
            // regardless of body — that's the coherence rule), and it must NOT be a
            // lossy underscore-join: `parsedTypeAnnotationToString` flattens nested
            // type-app args with `_`, so the separator BETWEEN target types was
            // indistinguishable from the one INSIDE a nested application — e.g.
            // `Conv for Foo [Bar]` (1 target) and `Conv for Foo Array Bar` (3 targets)
            // both rendered "Foo_Array_Bar" and collided. `structuralTypeKey`
            // length-delimits each type so distinct shapes stay distinct.
            val key = "${implLabel}::" +
                impl.abilityTypeArgs.joinToString(",") { structuralTypeKey(it) } + "|" +
                impl.targetTypes.joinToString(",") { structuralTypeKey(it) }

            val firstOccurrence = firstImplByKey[key]
            if (firstOccurrence != null) {
                if (!impl.isInherent && isGenericReceiver(impl) && isGenericReceiver(firstOccurrence)) {
                    throw TypeCheckError(
                        "Duplicate generic impl: `impl $implLabel for 'a` is already declared at ${locString(firstOccurrence.loc)}. " +
                        "Only one generic impl per ability is allowed — a generic impl already covers every type.",
                        impl.loc,
                        code = ErrorCode.V0407_OVERLAPPING_INSTANCE,
                    )
                }
                throw TypeCheckError(
                    "Duplicate ${if (impl.isInherent) "inherent" else "ability"} impl: `$implLabel` is already implemented " +
                    "for ${targetTypeNames.joinToString(" ")} at ${locString(firstOccurrence.loc)}.",
                    impl.loc,
                    code = ErrorCode.V0407_OVERLAPPING_INSTANCE,
                )
            }
            firstImplByKey[key] = impl
        }

        validateGenericVsConcrete(module)
    }

    /**
     * Strict coherence for generic impls: within a single module, a generic
     * `impl X for 'a` and any concrete `impl X for T` cannot coexist. This
     * matches Rust's non-overlapping-instances rule — dispatch would be
     * ambiguous otherwise.
     *
     * Only same-module conflicts are checked here; cross-module coherence
     * (generic in std::option + user's concrete `impl Somable for MyType`)
     * belongs in ContextBuilder where cross-module impls converge.
     */
    private fun validateGenericVsConcrete(module: ParsedModule) {
        val implsByAbility: Map<String, List<ParsedImplAbilityDecl>> = module.decls
            .filterIsInstance<ParsedImplAbilityDecl>()
            .filterNot { it.isInherent }
            .filter { it.abilityName != null }
            .groupBy { it.abilityName!! }

        for ((abilityName, impls) in implsByAbility) {
            val generic = impls.firstOrNull { isGenericReceiver(it) }
            if (generic == null) continue

            val concrete = impls.firstOrNull { it !== generic && !isGenericReceiver(it) }
            if (concrete != null) {
                throw genericVsConcreteOverlapError(abilityName, generic, concrete)
            }
        }
    }

    private fun genericVsConcreteOverlapError(
        abilityName: String,
        generic: ParsedImplAbilityDecl,
        concrete: ParsedImplAbilityDecl,
    ): TypeCheckError {
        // Anchor the error on whichever impl comes second in the file — that's
        // typically the "newly-added" one from the user's perspective.
        val (first, second) = if (concrete.loc.startLine < generic.loc.startLine) {
            concrete to generic
        } else {
            generic to concrete
        }
        val secondIsGeneric = second === generic

        val genericSig = "impl $abilityName for 'a"
        val concreteReceiver = concrete.targetTypes.joinToString(" ") { parsedTypeAnnotationToString(it) }
        val concreteSig = "impl $abilityName for $concreteReceiver"

        val secondSig = if (secondIsGeneric) genericSig else concreteSig
        val firstSig = if (secondIsGeneric) concreteSig else genericSig

        val msg = buildString {
            append("Overlapping impl: `$secondSig` conflicts with `$firstSig` ")
            append("(declared at ${locString(first.loc)}).")
            appendLine()
            appendLine()
            append("A generic impl (`$genericSig`) already covers every type, ")
            append("including `$concreteReceiver`. Only one of the two is allowed — Mezze does not support impl specialization.")
            appendLine()
            appendLine()
            append("Fixes:")
            appendLine()
            append("  • If you want a custom `$abilityName` for `$concreteReceiver` only: wrap it in a newtype")
            appendLine()
            append("      `newtype My$concreteReceiver = My$concreteReceiver $concreteReceiver`")
            appendLine()
            append("      `impl $abilityName for My$concreteReceiver where { ... }`")
            appendLine()
            append("  • If you want the same behavior everywhere: remove `$concreteSig` and keep the generic.")
            appendLine()
            append("  • If you want per-type behavior for all types: remove `$genericSig` and add concrete impls one-by-one.")
        }

        return TypeCheckError(msg, second.loc, code = ErrorCode.V0407_OVERLAPPING_INSTANCE)
    }

    private fun locString(loc: Loc): String = "${loc.file}:${loc.startLine}:${loc.startCol}"

    private fun isGenericReceiver(impl: ParsedImplAbilityDecl): Boolean {
        val targets = impl.targetTypes
        if (targets.size != 1) return false
        val target = targets.first()
        return target is ParsedNamedType && target.name.startsWith("'")
    }

    /**
     * An unambiguous, length-delimited rendering of a type annotation's STRUCTURE
     * (constructor + arg count + recursively each arg). Unlike
     * [parsedTypeAnnotationToString], the `(arity:...)` delimiters make the boundary
     * between distinct target types distinguishable from args inside one nested
     * application, so `Foo [Bar]` and `Foo Array Bar` get different keys.
     */
    private fun structuralTypeKey(ann: ParsedTypeAnnotation): String = when (ann) {
        is ParsedTypeApp -> "${ann.constructor}(${ann.args.size}:${ann.args.joinToString(",") { structuralTypeKey(it) }})"
        is ParsedQualifiedType -> "${ann.modulePath.joinToString("::")}::${ann.typeName}(${ann.args.size}:${ann.args.joinToString(",") { structuralTypeKey(it) }})"
        is ParsedNamedType -> "N:${ann.name}"
        is ParsedSeqType -> "Seq(${structuralTypeKey(ann.elementType)})"
        else -> "raw:${parsedTypeAnnotationToString(ann)}"
    }

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
                        op.loc,
                        code = ErrorCode.V0701_DUPLICATE_DECLARATION,
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
        val performerNames = module.decls
            .filterIsInstance<ParsedPerformerDecl>()
            .map { it.performerName }
            .toSet()

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
                rejectReservedName(name, item.value.loc)
                if (name in declaredVariables) {
                    throw TypeCheckError(
                        "Duplicate variable declaration: '$name' is already defined in this scope",
                        item.value.loc,
                        code = ErrorCode.V0701_DUPLICATE_DECLARATION,
                    )
                }
                if (name in performerNames) {
                    throw TypeCheckError(
                        "Name '$name' collides with an effect performer of the same name. " +
                            "Performers and let bindings share the value namespace; rename one.",
                        item.value.loc,
                        code = ErrorCode.V0701_DUPLICATE_DECLARATION,
                    )
                }
                if (name in constructorNames) {
                    throw TypeCheckError(
                        "Name '$name' collides with a variant or newtype constructor of the same name. " +
                            "Constructors and let bindings share the value namespace; rename one.",
                        item.value.loc,
                        code = ErrorCode.V0701_DUPLICATE_DECLARATION,
                    )
                }
                declaredVariables.add(name)
            }
        }

        for (perfDecl in module.decls.filterIsInstance<ParsedPerformerDecl>()) {
            rejectReservedName(perfDecl.performerName, perfDecl.loc)
        }
    }

    private inline fun <T> validateNoDuplicates(
        items: List<T>,
        name: (T) -> String,
        loc: (T) -> Loc,
        code: ErrorCode,
        message: (String) -> String
    ) {
        val seen = mutableSetOf<String>()
        for (item in items) {
            val n = name(item)
            if (!seen.add(n)) throw TypeCheckError(message(n), loc(item), code = code)
        }
    }

    fun parsedTypeAnnotationToString(annotation: ParsedTypeAnnotation): String = when (annotation) {
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

