package com.mezze.language.typechecker.validation

import com.mezze.language.ast.*
import com.mezze.language.parsedast.*
import com.mezze.language.parsedast.diagnostics.ErrorCode
import com.mezze.language.typechecker.TypeCheckError
import com.mezze.language.typechecker.WellKnownHashConstants
import com.mezze.language.typechecker.abilities.BuiltInAbilities
import com.mezze.language.typechecker.context.DeclarationRegistry
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.typechecker.solving.UnificationResult
import com.mezze.language.typechecker.solving.unify
import com.mezze.language.typechecker.util.TypeAnnotationConverter

object AbilityValidator {

    // ── #279/F4: built-in OPERATOR ability recognition ───────────────────
    // A method call `x.add {r=y}` on a primitive is folded to a hardware op by
    // the compiler (InlineOps) — bypassing impl dispatch entirely. So a USER
    // ability that reuses a built-in operator's method name (`add`/`eq`/`lt`/…)
    // and is impl'd for a type with that operator would be SILENTLY dead: the
    // built-in wins and the user's method never runs. We forbid the collision at
    // the impl site (below). The reserved names are DERIVED from the built-in
    // operator abilities' own declarations — not a hardcoded list — so they can
    // never drift from the abilities that actually own them.
    //
    // An operator ability is recognised by its content-hash identity (rename-safe,
    // never by name-string matching): NumAdd/NumSub/NumMul/NumDiv/NumMod/Eq/Ord/
    // LogicalAnd/LogicalOr. Note `neg`/`not` are NOT ability methods — they are
    // inherent methods on Int/Bool/Float/Frac — so those collisions are caught by
    // the inherent-method check, not here. See [[project_native_hof_scalar_abi_fusion]]
    // and [[feedback_no_name_string_dispatch_ever]].

    /** True iff [hash] IS a built-in operator ability — the ones whose methods the
     *  compiler inlines on primitives. Identity, never a name. */
    private fun isBuiltinOperatorHash(hash: com.mezze.language.typedast.Hash?): Boolean =
        WellKnownHashConstants.isNumAdd(hash) ||
            WellKnownHashConstants.isNumSub(hash) ||
            WellKnownHashConstants.isNumMul(hash) ||
            WellKnownHashConstants.isNumDiv(hash) ||
            WellKnownHashConstants.isNumMod(hash) ||
            WellKnownHashConstants.isEq(hash) ||
            WellKnownHashConstants.isOrd(hash) ||
            (hash != null && hash == WellKnownHashConstants.ABILITY_LOGICALAND) ||
            (hash != null && hash == WellKnownHashConstants.ABILITY_LOGICALOR)

    /**
     * True iff `abilityName` NAMES a built-in operator ability.
     *
     * A question about a NAME, and the right question in exactly one place: deriving
     * the reserved method names below from the built-in declarations, where names
     * are the input and the declarations are the built-in ones by construction.
     *
     * It is the WRONG question for deciding whether an impl is exempt from the
     * shadowing rule, because a module may declare its own `ability Eq` and this
     * answers for the stdlib's. Use [isBuiltinOperatorHash] with the impl's own
     * declaration there.
     */
    private fun isBuiltinOperatorAbility(abilityName: String): Boolean =
        isBuiltinOperatorHash(WellKnownHashConstants.resolveAbilityHash(abilityName))

    /** Method names owned by the built-in operator abilities, derived from those
     *  abilities' own parsed declarations (e.g. NumAdd→{add}, Eq→{eq,neq},
     *  Ord→{lt,le,gt,ge}). Computed once; the source is the stdlib ability files. */
    private val builtinOperatorMethodNames: Set<String> by lazy {
        BuiltInAbilities.getRawAbilityDeclarations()
            .filterKeys { isBuiltinOperatorAbility(it) }
            .values
            .flatMap { decl -> decl.operations.map { it.name } }
            .toSet()
    }

    fun validateAbilityDeclaration(ability: ParsedAbilityDecl) {
        val sourceLocation = ability.loc
        // Ability-level constraints caught at parse time (P0119) since #212
        for (operation in ability.operations) {
            val effects = operation.effects ?: continue
            if (effects.isPerformed) {
                throw TypeCheckError(
                    "Ability operation '${operation.name}' in '${ability.name}' cannot have performed marker (<*>). " +
                    "Ability operations declare what effects they perform, not that effects are handled. " +
                    "The <*> marker is only valid at call sites where effects are handled with 'perform'.",
                    sourceLocation,
                    code = ErrorCode.V0412_PERFORMED_MARKER_IN_ABILITY_OP,
                )
            }
        }
    }

    fun validateAbilityImplementation(
        impl: ParsedImplAbilityDecl,
        registry: DeclarationRegistry,
        context: TypingContext? = null,
    ) {
        val abilityDeclarations = registry.abilityDeclarations
        val typeDeclarations = registry.typeDeclarations
        val sourceLocation = impl.loc

        validateNoFieldMethodConflict(impl, typeDeclarations, sourceLocation)
        if (impl.isInherent) {
            validateInherentImplTargetType(impl, typeDeclarations, sourceLocation)
            return
        }

        // #279/F4: a user ability's impl must not shadow a name the target type
        // already resolves through a MORE-AUTHORITATIVE provider — otherwise the
        // method is silently dead (dispatch picks the other one). Two cases:
        //   (a) built-in OPERATOR method name (add/eq/lt/…): the compiler inlines
        //       it on primitives, so the user's method never runs.
        //   (b) an INHERENT-impl method of the target type (fields are already
        //       covered by validateNoFieldMethodConflict / V0419).
        // Exempt the built-in operator abilities THEMSELVES — impl'ing NumAdd/Eq/Ord
        // for your type is the intended way to get `+`/`==`/`<`.
        validateNoBuiltinShadowing(impl, sourceLocation, context, registry)

        val abilityName = impl.abilityName!!
        val abilityQualifier = impl.abilityRef?.qualifier
        val qualifiedAbilityName = abilityQualifier?.let { "$it::$abilityName" } ?: abilityName
        val abilityDeclaration = abilityDeclarations[abilityName]
            ?: abilityDeclarations[qualifiedAbilityName]
            ?: throw TypeCheckError(
                "Ability '$qualifiedAbilityName' is not defined or not imported. " +
                "Did you forget to import it with 'use'?",
                sourceLocation,
                code = ErrorCode.V0401_ABILITY_NOT_DEFINED,
            )

        // Every ability type arg fills one slot — whether concrete (`Str`) or
        // tyvar (`'e`). A tyvar arg is a forall introduction that fills its
        // slot with a fresh unknown, which the impl body typechecks against.
        // Previously this only counted concrete args, which prevented fully
        // generic multi-param impls like `impl Okable 'e for 'a` from
        // validating against `ability Okable 'e for 'a`.
        val totalTypeParams = impl.abilityTypeArgs.size + impl.targetTypes.size
        if (totalTypeParams != abilityDeclaration.typeParams.size) {
            throw TypeCheckError(
                "Type parameter count mismatch in ability implementation for '${abilityName}': " +
                "ability expects ${abilityDeclaration.typeParams.size} type parameters, " +
                "but implementation provides ${totalTypeParams}",
                sourceLocation,
                code = ErrorCode.V0705_TYPE_ARITY_MISMATCH,
            )
        }

        val abilityAssociatedTypes = abilityDeclaration.associatedTypes
        val implAssociatedTypeBindings = impl.associatedTypeBindings

        // Conformance is SEVERAL independent questions about one impl, so answer
        // all of them before reporting. Throwing at the first made a user with
        // three mistakes fix them one compile at a time — and the effect-side twin
        // of this check (EffectValidator.validateEffectImpl) already accumulated,
        // so the two halves of the same rule behaved differently.
        val problems = mutableListOf<TypeCheckError>()

        // The two "none at all" checks are better-worded special cases of the
        // missing/extra checks below, not extra findings. Reporting both would
        // describe ONE mistake twice, so each suppresses its general form.
        val noBindingsAtAll = abilityAssociatedTypes.isNotEmpty() && implAssociatedTypeBindings.isEmpty()
        val bindingsButNoneDeclared = abilityAssociatedTypes.isEmpty() && implAssociatedTypeBindings.isNotEmpty()

        if (noBindingsAtAll) {
            val typesList = abilityAssociatedTypes.joinToString(", ")
            problems.add(TypeCheckError(
                "Ability '${abilityName}' declares associated types [$typesList] " +
                "but implementation does not provide bindings. " +
                "Use: impl ${abilityName} ${impl.targetTypes.joinToString(" ") { parsedTypeAnnotationToString(it) }} " +
                "having ${abilityAssociatedTypes.joinToString(", ") { "$it = <Type>" }} where { ... }",
                sourceLocation,
                code = ErrorCode.V0413_MISSING_ASSOCIATED_TYPE_BINDING,
            ))
        }

        if (bindingsButNoneDeclared) {
            val bindingsList = implAssociatedTypeBindings.keys.joinToString(", ")
            problems.add(TypeCheckError(
                "Implementation provides associated type bindings [$bindingsList] " +
                "but ability '${abilityName}' does not declare associated types. " +
                "Remove the 'having' clause or add it to the ability declaration.",
                sourceLocation,
                code = ErrorCode.V0414_EXTRA_ASSOCIATED_TYPE_BINDING,
            ))
        }

        val missingBindings = abilityAssociatedTypes.filter { it !in implAssociatedTypeBindings.keys }
        if (missingBindings.isNotEmpty() && !noBindingsAtAll) {
            problems.add(TypeCheckError(
                "Ability '${abilityName}' declares associated types ${abilityAssociatedTypes.joinToString(", ")} " +
                "but implementation is missing bindings for: ${missingBindings.joinToString(", ")}. " +
                "All associated types must be bound in the implementation.",
                sourceLocation,
                code = ErrorCode.V0413_MISSING_ASSOCIATED_TYPE_BINDING,
            ))
        }

        val extraBindings = implAssociatedTypeBindings.keys.filter { it !in abilityAssociatedTypes }
        if (extraBindings.isNotEmpty() && !bindingsButNoneDeclared) {
            problems.add(TypeCheckError(
                "Implementation provides bindings for ${extraBindings.joinToString(", ")} " +
                "but ability '${abilityName}' only declares: ${abilityAssociatedTypes.joinToString(", ")}. " +
                "Remove the extra bindings or add them to the ability declaration.",
                sourceLocation,
                code = ErrorCode.V0414_EXTRA_ASSOCIATED_TYPE_BINDING,
            ))
        }

        val declaredOps = abilityDeclaration.operations.map { it.name }.toSet()
        val implementedOps = impl.operations.map { it.name }.toSet()
        val missingOps = declaredOps - implementedOps

        if (missingOps.isNotEmpty()) {
            val missing = missingOps.joinToString(", ")
            val example = missingOps.first()
            problems.add(TypeCheckError(
                "Implementation of '${abilityName}' is missing required ${if (missingOps.size == 1) "method" else "methods"}: $missing. " +
                "Add: $example = {args} -> ...",
                sourceLocation,
                code = ErrorCode.V0404_IMPL_MISSING_METHOD,
            ))
        }

        val extraOps = implementedOps - declaredOps
        if (extraOps.isNotEmpty()) {
            val extra = extraOps.joinToString(", ")
            val extraOpLoc = impl.operations.find { it.name in extraOps }?.loc
            problems.add(TypeCheckError(
                "Implementation of '${abilityName}' has extra ${if (extraOps.size == 1) "method" else "methods"} not in ability: $extra. " +
                "Remove ${if (extraOps.size == 1) "it" else "them"} or add to the ability declaration.",
                extraOpLoc,
                code = ErrorCode.V0405_IMPL_EXTRA_METHOD,
            ))
        }

        // Report everything found, first problem first. The remaining checks are
        // NOT independent of these — signature checking assumes the two op sets
        // line up — so they only run on a conforming shape, exactly as they did
        // when the first mismatch threw.
        if (problems.isNotEmpty()) {
            throw problems.first().let { first ->
                TypeCheckError(
                    first.message, first.loc, first.errorType, first.metadata,
                    first.code, first.hints, first.fixHints,
                    additional = problems.drop(1),
                )
            }
        }

        validateOperationSignatures(impl, abilityDeclaration, sourceLocation, registry)
    }

    private fun validateOperationSignatures(
        impl: ParsedImplAbilityDecl,
        abilityDeclaration: ParsedAbilityDecl,
        implSourceLocation: Loc,
        registry: DeclarationRegistry,
    ) {
        val substitution = abilityDeclaration.typeParams.zip(impl.allTypeArgs).toMap()

        for (implOp in impl.operations) {
            val declOp = abilityDeclaration.operations.find { it.name == implOp.name }
                ?: continue

            val opSourceLocation = implOp.loc

            if (implOp.implementation !is ParsedLambda) {
                throw TypeCheckError(
                    "Operation '${implOp.name}' in impl ${impl.abilityName} must be a function (lambda), " +
                    "but got ${implOp.implementation::class.simpleName}. " +
                    "Use: ${implOp.name} = {${if (declOp.paramType is ParsedRecordType) (declOp.paramType as ParsedRecordType).entries.joinToString(", ") { it.name } else "it"}} -> ...",
                    opSourceLocation,
                    code = ErrorCode.V0415_IMPL_OP_MUST_BE_LAMBDA,
                )
            }

            if (declOp.paramType !is ParsedRecordType) {
                throw TypeCheckError(
                    "Ability operation '${declOp.name}' in '${abilityDeclaration.name}' must declare parameters " +
                    "using record syntax: {it: ${parsedTypeAnnotationToString(declOp.paramType)}} -> ${parsedTypeAnnotationToString(declOp.returnType)}. " +
                    "Bare type '${parsedTypeAnnotationToString(declOp.paramType)} -> ...' is not supported — " +
                    "Mezze uses record-based calling convention.",
                    declOp.loc,
                    code = ErrorCode.V0416_ABILITY_OP_RECORD_PARAM_REQUIRED,
                    hints = listOf("declare the param as a record: `{ ... } -> ReturnType`"),
                )
            }

            val expectedParamType = substituteTypeParameters(declOp.paramType, substitution)
            val expectedReturnType = substituteTypeParameters(declOp.returnType, substitution)

            val actualParamType = implOp.paramType ?: expectedParamType
            val actualReturnType = implOp.returnType ?: expectedReturnType

            val sourceLocation = implOp.loc

            // Tyvars in scope for the impl's op signatures: every tyvar that
            // appears in the impl header (both `abilityTypeArgs` and
            // `targetTypes`). The parser flattens both into `forallVars`, so
            // we can just use that. Previously this only pulled from
            // `abilityTypeArgs`, which forced the redundant leading-tyvar
            // form (`impl Ord 'a for Option 'a`) as the only working syntax.
            val allowedTypeVars = impl.forallVars.toSet()

            if (implOp.paramType != null && hasTypeVariables(actualParamType, allowedTypeVars)) {
                throw TypeCheckError(
                    "Type variables not allowed in ability implementation: " +
                    "operation '${implOp.name}' parameter type contains type variables. " +
                    "All type variables must be replaced with concrete types.",
                    sourceLocation,
                    code = ErrorCode.V0417_TYPE_VAR_FORBIDDEN_IN_CONCRETE_IMPL,
                )
            }
            if (implOp.returnType != null && hasTypeVariables(actualReturnType, allowedTypeVars)) {
                throw TypeCheckError(
                    "Type variables not allowed in ability implementation: " +
                    "operation '${implOp.name}' return type contains type variables. " +
                    "All type variables must be replaced with concrete types.",
                    sourceLocation,
                    code = ErrorCode.V0417_TYPE_VAR_FORBIDDEN_IN_CONCRETE_IMPL,
                )
            }

            if (!typeAnnotationsMatchPublic(actualParamType, expectedParamType, registry)) {
                throw TypeCheckError(
                    "Wrong parameter type for '${implOp.name}' in impl ${impl.abilityName}: " +
                    "expected ${parsedTypeAnnotationToString(expectedParamType)}, " +
                    "but got ${parsedTypeAnnotationToString(actualParamType)}.",
                    sourceLocation,
                    code = ErrorCode.T0201_TYPE_MISMATCH,
                )
            }

            if (!typeAnnotationsMatchPublic(actualReturnType, expectedReturnType, registry)) {
                throw TypeCheckError(
                    "Wrong return type for '${implOp.name}' in impl ${impl.abilityName}: " +
                    "expected ${parsedTypeAnnotationToString(expectedReturnType)}, " +
                    "but got ${parsedTypeAnnotationToString(actualReturnType)}.",
                    sourceLocation,
                    code = ErrorCode.T0201_TYPE_MISMATCH,
                )
            }
        }
    }

    private fun substituteTypeParameters(
        annotation: ParsedTypeAnnotation,
        substitution: Map<String, ParsedTypeAnnotation>
    ): ParsedTypeAnnotation = annotation.substituteTypeVarNames(substitution)

    private fun hasTypeVariables(annotation: ParsedTypeAnnotation, allowedVars: Set<String> = emptySet()): Boolean =
        annotation.hasTypeVarsOutside(allowedVars)

    fun typeAnnotationsMatchPublic(
        a: ParsedTypeAnnotation,
        b: ParsedTypeAnnotation,
        registry: DeclarationRegistry? = null,
    ): Boolean {
        val cache = mutableMapOf<String, TypeVar>()
        val resolveTypeHash: (String) -> com.mezze.language.typedast.Hash? = { name ->
            registry?.lookupType(name)?.let { d ->
                d.identity
            }
        }
        // Both conversions used to be wrapped in `catch (_: Exception) { return false }`
        // — a failure to READ an annotation answering "these two impls do not
        // overlap", which admits the overlapping pair. Measured at ZERO firings
        // across both suites, so there is nothing to answer for.
        val typeA = TypeAnnotationConverter.convert(a, typeVarCache = cache, resolveTypeHash = resolveTypeHash)
        val typeB = TypeAnnotationConverter.convert(b, typeVarCache = cache, resolveTypeHash = resolveTypeHash)
        return unify(typeA, typeB) is UnificationResult.Success
    }

    fun validateOrphanRules(
        impl: ParsedImplAbilityDecl,
        localAbilityNames: Set<String>,
        localTypeNames: Set<String>,
        importedAbilityNames: Set<String>,
        importedTypeNames: Set<String>
    ) {
        val sourceLocation = impl.loc

        if (impl.isInherent) {
            validateInherentImplLocality(impl, localTypeNames, sourceLocation)
            return
        }

        val abilityName = impl.abilityName!!  // safe: not inherent

        val isAbilityLocal = localAbilityNames.contains(abilityName)
        val isAbilityImported = importedAbilityNames.contains(abilityName)

        if (!isAbilityLocal && !isAbilityImported) {
            return
        }

        if (isAbilityLocal) {
            return
        }

        val concreteAbilityTypeArgs = impl.abilityTypeArgs
            .filter { it !is ParsedNamedType || !it.name.startsWith("'") }
        val targetTypeNames = (concreteAbilityTypeArgs + impl.targetTypes).map { getTypeNameFromParsedAnnotation(it) }

        val hasLocalTargetType = targetTypeNames.any { typeName ->
            localTypeNames.contains(typeName)
        }

        if (hasLocalTargetType) {
            return
        }

        val typesList = targetTypeNames.joinToString(", ")
        throw TypeCheckError(
            "Orphan impl: cannot implement imported ability '$abilityName' for types [$typesList] " +
            "in this module. An impl must be in the same module as either the ability declaration " +
            "or at least one of the target types. Move this impl to the module where '$abilityName' " +
            "or one of [$typesList] is defined.",
            sourceLocation,
            code = ErrorCode.V0406_ORPHAN_INSTANCE,
            hints = listOf("define the impl in the same module as either the ability or the type"),
        )
    }

    private fun getTypeNameFromParsedAnnotation(annotation: ParsedTypeAnnotation): String {
        return when (annotation) {
            is ParsedNamedType -> annotation.name.removePrefix("'")
            is ParsedTypeApp -> annotation.constructor
            is ParsedQualifiedType -> annotation.typeName
            is ParsedRecordType -> "Record"
            is ParsedVariantType -> "Variant"
            is ParsedFunctionType -> "Function"
            is ParsedSeqType -> "Seq"
        }
    }

    /**
     * Collect every nominal type name mentioned anywhere inside a parsed
     * type annotation — outermost constructor AND every constructor in
     * the type-argument tree. Used by the inherent-impl locality check
     * so `impl [Promise 'a]` can live in Promise's module (Promise
     * appears in the receiver tree even though the outermost constructor
     * is Seq).
     *
     * Type variables (`'a`, `'b`) are excluded — they aren't nominal
     * declarations. Structural placeholders (`"Record"`, `"Variant"`,
     * `"Function"`, `"Seq"`) are still included so the check still
     * applies to bare `impl [Int]`, `impl {x: Int}`, etc.
     */
    private fun collectAllTypeNames(annotation: ParsedTypeAnnotation): Set<String> {
        val out = LinkedHashSet<String>()
        fun walk(a: ParsedTypeAnnotation) {
            when (a) {
                is ParsedNamedType -> {
                    // Type variables are prefixed with `'`; skip.
                    if (a.name.startsWith("'")) return
                    out.add(a.name)
                }
                is ParsedTypeApp -> {
                    out.add(a.constructor)
                    a.args.forEach { walk(it) }
                }
                is ParsedQualifiedType -> {
                    out.add(a.typeName)
                    a.args.forEach { walk(it) }
                }
                is ParsedSeqType -> {
                    out.add("Seq")
                    walk(a.elementType)
                }
                is ParsedRecordType -> {
                    out.add("Record")
                    a.entries.forEach { walk(it.type) }
                }
                is ParsedVariantType -> {
                    out.add("Variant")
                    a.constructors.forEach { ctor ->
                        ctor.dataType?.let { walk(it) }
                    }
                }
                is ParsedFunctionType -> {
                    out.add("Function")
                    walk(a.paramType)
                    walk(a.returnType)
                }
            }
        }
        walk(annotation)
        return out
    }

    // #212 removed that field — the parser now rejects ability-level
    // `with` clauses with P0119_ABILITY_LEVEL_CONSTRAINT — the
    // dependency cycle check is no longer reachable. Removed.

    /**
     * Two abilities in one scope may not declare the same operation name.
     *
     * `Weigh.rank` and `Price.rank` side by side made every `x.rank {}` ambiguous
     * and every generic `with Weigh 'a, Price 'a` body depend on which dictionary a
     * method name resolved to. That ambiguity was patched site by site (BUG 9);
     * the rule removes it at the source: an operation name means one ability in a
     * scope. The same ability reached twice — imported and re-exported, say — is
     * one declaration (same hash), not a clash.
     */
    fun validateNoSharedOperationNames(
        local: Map<String, ParsedAbilityDecl>,
        imported: Map<String, ParsedAbilityDecl>,
    ) {
        val byOp = LinkedHashMap<String, MutableList<ParsedAbilityDecl>>()
        val seen = HashSet<Any>()
        for (decl in imported.values + local.values) {
            if (!seen.add(decl.contentHash ?: decl)) continue
            for (op in decl.operations) byOp.getOrPut(op.name) { mutableListOf() }.add(decl)
        }
        for ((op, decls) in byOp) {
            if (decls.size < 2) continue
            // Report where the user can act: a declaration in this module.
            val at = decls.lastOrNull { it in local.values } ?: decls.last()
            val others = decls.filter { it !== at }.joinToString(", ") { it.name }
            throw TypeCheckError(
                "Ability '${at.name}' declares operation '$op', which ability $others in scope " +
                    "already declares. An operation name must mean one ability in a scope — rename one " +
                    "of the operations, or do not import both abilities into this module.",
                loc = at.loc,
                code = ErrorCode.V0701_DUPLICATE_DECLARATION,
            )
        }
    }

    fun validateNameCollisions(
        registry: DeclarationRegistry
    ) {
        val abilityDeclarations = registry.abilityDeclarations
        val typeDeclarations = registry.typeDeclarations
        val effectDeclarations = registry.effectDeclarations
        for ((typeName, typeDecl) in typeDeclarations) {
            if (BuiltInAbilities.isBuiltInAbility(typeName)) {
                throw TypeCheckError(
                    "Cannot define type '$typeName': name conflicts with built-in ability '$typeName'. " +
                    "Types and built-in abilities must have unique names.",
                    loc = typeDecl.loc,
                    code = ErrorCode.V0701_DUPLICATE_DECLARATION,
                )
            }
        }
        for ((abilityName, abilityDecl) in abilityDeclarations) {
            if (typeDeclarations.containsKey(abilityName)) {
                throw TypeCheckError(
                    "Cannot define type '$abilityName': name conflicts with ability '$abilityName'. " +
                    "Abilities and types must have unique names.",
                    loc = typeDeclarations[abilityName]?.loc ?: abilityDecl.loc,
                    code = ErrorCode.V0701_DUPLICATE_DECLARATION,
                )
            }
        }
        for ((abilityName, abilityDecl) in abilityDeclarations) {
            if (effectDeclarations.containsKey(abilityName)) {
                throw TypeCheckError(
                    "Cannot define effect '$abilityName': name conflicts with ability '$abilityName'. " +
                    "Abilities and effects must have unique names.",
                    loc = effectDeclarations[abilityName]?.loc ?: abilityDecl.loc,
                    code = ErrorCode.V0701_DUPLICATE_DECLARATION,
                )
            }
        }
        for ((typeName, typeDecl) in typeDeclarations) {
            if (effectDeclarations.containsKey(typeName)) {
                throw TypeCheckError(
                    "Cannot define type '$typeName': name conflicts with effect '$typeName'. " +
                    "Types and effects must have unique names.",
                    loc = typeDecl.loc,
                    code = ErrorCode.V0701_DUPLICATE_DECLARATION,
                )
            }
        }
        for ((typeName, typeDecl) in typeDeclarations) {
            val definition = typeDecl.definition
            if (definition is ParsedVariantDef) {
                for (constructor in definition.constructors) {
                    val constructorName = constructor.name
                    if (BuiltInAbilities.isBuiltInAbility(constructorName)) {
                        throw TypeCheckError(
                            "Cannot define variant constructor '$constructorName' in type '$typeName': " +
                            "name conflicts with built-in ability '$constructorName'. " +
                            "Variant constructors and abilities must have unique names.",
                            loc = constructor.loc,
                            code = ErrorCode.V0701_DUPLICATE_DECLARATION,
                        )
                    }
                    if (abilityDeclarations.containsKey(constructorName)) {
                        throw TypeCheckError(
                            "Cannot define variant constructor '$constructorName' in type '$typeName': " +
                            "name conflicts with ability '$constructorName'. " +
                            "Variant constructors and abilities must have unique names.",
                            loc = constructor.loc,
                            code = ErrorCode.V0701_DUPLICATE_DECLARATION,
                        )
                    }
                    if (effectDeclarations.containsKey(constructorName)) {
                        throw TypeCheckError(
                            "Cannot define variant constructor '$constructorName' in type '$typeName': " +
                            "name conflicts with effect '$constructorName'. " +
                            "Variant constructors and effects must have unique names.",
                            loc = constructor.loc,
                            code = ErrorCode.V0701_DUPLICATE_DECLARATION,
                        )
                    }
                }
            }
        }
    }

    private fun validateInherentImplTargetType(
        impl: ParsedImplAbilityDecl,
        typeDeclarations: Map<String, ParsedTypeDecl>,
        sourceLocation: Loc
    ) {
        for (targetType in impl.targetTypes) {
            val typeName = getTypeNameFromParsedAnnotation(targetType)

            if (PrimitiveTypes.isPrimitiveName(typeName)) continue

            if (typeName in BuiltinTypes.coreTypeNames) continue

            if (!typeDeclarations.containsKey(typeName)) {
                throw TypeCheckError(
                    "Cannot define inherent impl for unknown type '$typeName'. " +
                    "The type must be declared before implementing methods on it.",
                    sourceLocation,
                    code = ErrorCode.V0418_INHERENT_IMPL_ON_UNDEFINED_TYPE,
                )
            }
        }
    }

    private fun validateInherentImplLocality(
        impl: ParsedImplAbilityDecl,
        localTypeNames: Set<String>,
        sourceLocation: Loc
    ) {
        for (targetType in impl.targetTypes) {
            // Locality rule for inherent impls: at least ONE nominal
            // type mentioned anywhere in the receiver tree must be
            // locally declared. This lets a module attach methods to
            // parametric containers (e.g. `impl [Promise 'a]` in the
            // module that declares Promise) — the outermost constructor
            // (`Seq`) is not local, but Promise is, and that's the
            // interesting locality.
            val mentioned = collectAllTypeNames(targetType)
            val hasLocal = mentioned.any { name -> localTypeNames.contains(name) }
            if (!hasLocal) {
                val outerName = getTypeNameFromParsedAnnotation(targetType)
                throw TypeCheckError(
                    "Cannot define inherent impl for type '$outerName' in this module. " +
                    "Inherent impls can only be defined where at least one of the " +
                    "types mentioned in the receiver is defined. " +
                    "Move this impl to the module where one of [${mentioned.joinToString(", ")}] " +
                    "is declared.",
                    sourceLocation,
                    code = ErrorCode.V0406_ORPHAN_INSTANCE,
                    hints = listOf(
                        "define the impl in the same module as one of the receiver's nominal types",
                    ),
                )
            }
        }
    }

    /**
     * #279/F4: reject an ability impl whose method name is silently shadowed by a
     * more-authoritative provider on the target type — so the method can never run.
     *
     * Two shadow sources (fields are handled separately by [validateNoFieldMethodConflict]):
     *   (a) a built-in OPERATOR method name (add/sub/mul/div/mod/eq/neq/lt/le/gt/ge/
     *       and/or): the compiler folds `x.<op> {r=y}` on a primitive to a hardware
     *       op, bypassing dispatch — so `impl UserAbility for Int { add = … }` never
     *       runs. Only checked when the impl's OWN ability is not itself a built-in
     *       operator ability (impl'ing NumAdd/Eq/Ord for your type is the intended way).
     *   (b) an INHERENT-impl method of the target type (incl. imported/prelude ones,
     *       e.g. `neg`/`not` on Int/Bool): the inherent method wins, so the ability's
     *       same-named method is dead. Looked up through the parent-walking context
     *       so cross-module inherent impls are seen (impls are globally coherent).
     */
    private fun validateNoBuiltinShadowing(
        impl: ParsedImplAbilityDecl,
        sourceLocation: Loc,
        context: TypingContext?,
        registry: DeclarationRegistry,
    ) {
        val abilityName = impl.abilityName ?: return
        val implMethodNames = impl.operations.map { it.name }

        // (a) built-in operator collision — skip when the impl IS a builtin operator
        // ability. Asked of the ability THIS impl names in THIS module, by content
        // hash: looking the name up in the well-known table instead answered for the
        // stdlib's `Eq` whenever a module declared an `Eq` of its own, so a locally
        // declared `ability Eq for 'a where { eq: {it:'a, r:'a} -> Str }` was waved
        // through — and then captured every `==` in the module, which returned a
        // `Str`. The exemption exists for the built-in operator abilities; a
        // same-named ability that is not one of them is exactly what the rule is
        // here to catch.
        if (!isBuiltinOperatorHash(registry.resolveAbilityHash(abilityName))) {
            val opClash = implMethodNames.firstOrNull { it in builtinOperatorMethodNames }
            if (opClash != null) {
                // Two different mistakes reach here, and they need different advice.
                // An ability under an unrelated name has simply picked a reserved
                // method name. An ability under a BUILT-IN OPERATOR'S name has
                // redeclared that ability with a different signature — renaming the
                // method is not the fix, matching the built-in (or picking another
                // ability name) is.
                val shadowsBuiltinName = isBuiltinOperatorAbility(abilityName)
                throw TypeCheckError(
                    if (shadowsBuiltinName)
                        "Ability '$abilityName' is declared here with a different signature than the " +
                            "built-in '$abilityName', so it is a DIFFERENT ability that happens to share " +
                            "the name — but `$opClash` is the built-in's operator method, and a `$opClash` " +
                            "call still compiles to the built-in operator. This impl's '$opClash' would " +
                            "never run. Either declare '$abilityName' to match the built-in, or give this " +
                            "ability a name of its own."
                    else
                        "Ability '$abilityName' declares method '$opClash', which is a built-in " +
                            "operator method. A `$opClash` call is compiled to the built-in operator, so " +
                            "this impl's '$opClash' would never run (it is silently shadowed). Rename the " +
                            "method to a name that is not a built-in operator.",
                    sourceLocation,
                    code = ErrorCode.V0420_ABILITY_METHOD_SHADOWS_BUILTIN_OPERATOR,
                )
            }
        }

        // (b) inherent-method collision on the target type (global, via parent chain).
        if (context != null) {
            for (targetType in impl.targetTypes) {
                val typeName = getTypeNameFromParsedAnnotation(targetType)
                val clash = implMethodNames.firstOrNull { name ->
                    context.findAnonymousMethod(typeName, name) != null
                }
                if (clash != null) {
                    throw TypeCheckError(
                        "Ability '$abilityName' declares method '$clash' for type '$typeName', but " +
                            "'$typeName' already has an inherent method '$clash'. The inherent method " +
                            "takes precedence, so this impl's '$clash' would never run. Rename the " +
                            "ability method or the inherent method.",
                        sourceLocation,
                        code = ErrorCode.V0420_ABILITY_METHOD_SHADOWS_BUILTIN_OPERATOR,
                    )
                }
            }
        }
    }

    private fun validateNoFieldMethodConflict(
        impl: ParsedImplAbilityDecl,
        typeDeclarations: Map<String, ParsedTypeDecl>,
        sourceLocation: Loc
    ) {
        if (typeDeclarations.isEmpty()) return

        val implMethodNames = impl.operations.map { it.name }.toSet()
        if (implMethodNames.isEmpty()) return

        for (targetType in impl.targetTypes) {
            val typeName = getTypeNameFromParsedAnnotation(targetType)
            val typeDecl = typeDeclarations[typeName] ?: continue
            val definition = typeDecl.definition

            if (definition is ParsedRecordDef) {
                val fieldNames = definition.fields.map { it.name }.toSet()
                val conflicts = implMethodNames.intersect(fieldNames)
                if (conflicts.isNotEmpty()) {
                    val conflictList = conflicts.joinToString(", ") { "'$it'" }
                    throw TypeCheckError(
                        "Cannot implement ${if (impl.isInherent) "methods" else "ability '${impl.abilityName}'"} " +
                            "for type '$typeName': method names $conflictList conflict with fields " +
                            "already defined on '$typeName'. Rename the fields or the ability methods to avoid ambiguity.",
                        sourceLocation,
                        code = ErrorCode.V0419_IMPL_METHOD_FIELD_CONFLICT,
                    )
                }
            }
        }
    }

    private fun parsedTypeAnnotationToString(annotation: ParsedTypeAnnotation): String {
        return when (annotation) {
            is ParsedNamedType -> annotation.name
            is ParsedFunctionType -> {
                "${parsedTypeAnnotationToString(annotation.paramType)} -> ${parsedTypeAnnotationToString(annotation.returnType)}"
            }
            is ParsedRecordType -> {
                val fields = annotation.entries.joinToString(", ") { "${it.name}: ${parsedTypeAnnotationToString(it.type)}" }
                "{$fields}"
            }
            is ParsedVariantType -> {
                val ctors = annotation.constructors.joinToString(", ") {
                    val dataType = it.dataType
                    if (dataType != null) "${it.name} ${parsedTypeAnnotationToString(dataType)}"
                    else it.name
                }
                "| $ctors |"
            }
            is ParsedTypeApp -> {
                if (annotation.args.isEmpty()) annotation.constructor
                else "${annotation.constructor} ${annotation.args.joinToString(" ") { parsedTypeAnnotationToString(it) }}"
            }
            is ParsedSeqType -> "[${parsedTypeAnnotationToString(annotation.elementType)}]"
            is ParsedQualifiedType -> {
                val qualifiedName = annotation.modulePath.joinToString("::") + "::" + annotation.typeName
                if (annotation.args.isEmpty()) qualifiedName
                else "$qualifiedName ${annotation.args.joinToString(" ") { parsedTypeAnnotationToString(it) }}"
            }
        }
    }
}
