package com.mezze.language.typechecker.constraints

import com.mezze.language.ast.*
import com.mezze.language.parsedast.*
import com.mezze.language.typechecker.abilities.BuiltInAbilities
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.typechecker.context.ContextBuilder
import com.mezze.language.typechecker.TypeCheckError
import com.mezze.language.typechecker.ErrorType
import com.mezze.language.ast.solving.collectFreeTypeVars
import com.mezze.language.parsedast.diagnostics.ErrorCode
import com.mezze.language.typechecker.WellKnownHashConstants
import com.mezze.language.typechecker.util.TypeAnnotationConverter

// ========================================================================
// NEWTYPES — `Email.("foo")` syntax
// ========================================================================

internal fun ExpressionConstraintGenerator.generateNewtypeConstruction(expr: ParsedNewtypeConstruct, context: ConstraintContext): ConstraintResult {
    val typeDecl = context.typeDeclarations[expr.typeName]
    val newtypeDef = typeDecl?.definition as? ParsedNewtypeDef

    if (newtypeDef == null) {
        // Use MAPS (source-of-truth) rather than Set<String> — the latter is
        // frequently empty at expression sites even when the ability/effect
        // is in scope via `use` imports.
        val isAbility = expr.typeName in context.abilityDeclarations ||
            expr.typeName in context.abilityNames ||
            BuiltInAbilities.isBuiltInAbility(expr.typeName)
        val isEffect = expr.typeName in context.effectDeclarations ||
            expr.typeName in context.effectNames
        val variantDecl = typeDecl?.definition as? ParsedVariantDef
        val recordDecl = typeDecl?.definition as? ParsedRecordDef
        val aliasDecl = typeDecl?.definition as? ParsedTypeAliasDef
        val message = when {
            isAbility ->
                "'${expr.typeName}' is an ability, not a newtype. Did you mean an ability method like '${expr.typeName}.<method> { ... }' or a constraint like 'with ${expr.typeName} 'a'?"
            isEffect ->
                "'${expr.typeName}' is an effect, not a newtype. Did you mean 'perform ${expr.typeName} in do { ... }' or an effect op call like '${expr.typeName}.<op> { ... }'?"
            variantDecl != null ->
                "'${expr.typeName}' is a variant type, not a newtype. Construct with '${expr.typeName} { <field> = <value> }'."
            recordDecl != null ->
                "'${expr.typeName}' is a record type, not a newtype. Construct with '${expr.typeName} { <fields> = <values> }'."
            aliasDecl != null ->
                "'${expr.typeName}' is a transparent type alias, not a newtype. Just construct the aliased type directly — the value is interchangeable. If you want a distinct nominal type, declare `newtype ${expr.typeName} = ...` instead of `type`."
            expr.typeName in PrimitiveTypes.allNames ->
                "'${expr.typeName}' is a primitive type, not a newtype. Only newtypes can be constructed with '.(...)'. If you meant a module function, use '${expr.typeName.lowercase()}::<function> { ... }'."
            expr.typeName.firstOrNull()?.isLowerCase() == true ->
                "'${expr.typeName}' starts with a lowercase letter, so it's a value or module — not a type. " +
                "`X.(y)` is newtype construction and only works when X is an uppercase type name. " +
                "Did you mean a module call `${expr.typeName}::<function> { ... }` or an inherent method call `${expr.typeName}.<method> { ... }`?"
            else -> "Unknown type '${expr.typeName}'. Did you forget to import it?"
        }
        throw TypeCheckError(
            message = message,
            loc = expr.loc,
            errorType = ErrorType.UNDEFINED_VARIABLE,
            code = ErrorCode.T0212_NOT_A_NEWTYPE,
        )
    }

    val constructorBindingLu = context.typingContext.lookup(BindingKey.unqualified(newtypeDef.constructor))
    if (constructorBindingLu is TypingContext.LookupResult.NotFound) {
        throw TypeCheckError(
            message = "Cannot construct newtype '${expr.typeName}' — its constructor is private to the defining module. " +
                "Use the module's public API instead.",
            loc = expr.loc,
            errorType = ErrorType.MISSING_FIELD,
            code = ErrorCode.M0803_NOT_EXPORTED,
            hints = listOf(
                "newtype constructors are not exported; look for a `pub` factory function in the defining module"
            ),
        )
    }

    val valueResult = generate(expr.value, context)

    val constructorScheme = (constructorBindingLu as? TypingContext.LookupResult.Found)?.binding
    if (constructorScheme != null) {
        val instantiated = when (constructorScheme) {
            is TypingContext.Binding.Polymorphic -> constructorScheme.scheme.instantiate().type
            is TypingContext.Binding.Monomorphic -> constructorScheme.type
        }
        val fnType = instantiated as? MezzeType.FunctionType
        if (fnType != null) {
            val constraints = valueResult.constraints + listOf(
                Constraint.Equality(
                    left = valueResult.type,
                    right = fnType.paramType,
                    context = "Newtype construction ${expr.typeName}.(...)",
                    loc = expr.loc,
                )
            )
            return ConstraintResult(
                type = fnType.returnType,
                constraints = constraints,
                effects = valueResult.effects,
                effectInstances = valueResult.effectInstances,
                performed = valueResult.performed,
                abilityConstraints = valueResult.abilityConstraints,
                associatedTypeConstraints = valueResult.associatedTypeConstraints,
                undefinedVariables = valueResult.undefinedVariables,
                usedVariables = valueResult.usedVariables,
                referencedBindings = valueResult.referencedBindings,
                methodCallResolutions = valueResult.methodCallResolutions,
                callReturnTypes = valueResult.callReturnTypes,
            )
        }
    }

    val newtypeType = MezzeType.NewtypeType(
        name = expr.typeName,
        constructor = newtypeDef.constructor,
        wrappedType = valueResult.type,
    )
    return ConstraintResult(
        type = newtypeType,
        constraints = valueResult.constraints,
        effects = valueResult.effects,
        effectInstances = valueResult.effectInstances,
        performed = valueResult.performed,
        abilityConstraints = valueResult.abilityConstraints,
        associatedTypeConstraints = valueResult.associatedTypeConstraints,
        undefinedVariables = valueResult.undefinedVariables,
        usedVariables = valueResult.usedVariables,
        referencedBindings = valueResult.referencedBindings,
        methodCallResolutions = valueResult.methodCallResolutions,
        callReturnTypes = valueResult.callReturnTypes,
    )
}

internal fun ExpressionConstraintGenerator.generateVariantConstruction(expr: ParsedVariantConstruct, context: ConstraintContext): ConstraintResult {

    val constructorBinding = context.typingContext.lookup(BindingKey.unqualified(expr.constructor))

    // Newtype migration diagnostic: `Email { value = "hi" }` (old record-wrap syntax)
    // is no longer supported. Newtype constructors use `Email.("hi")` syntax now.
    val newtypeDeclForName = context.typeDeclarations.values.find {
        it.definition is ParsedNewtypeDef && (it.definition as ParsedNewtypeDef).constructor == expr.constructor
    }
    if (newtypeDeclForName != null) {
        if (constructorBinding is TypingContext.LookupResult.NotFound) {
            throw TypeCheckError(
                message = "Cannot construct newtype '${expr.constructor}' — its constructor is private to the defining module. " +
                    "Use the module's public API instead.",
                loc = expr.loc,
                errorType = ErrorType.MISSING_FIELD,
                code = ErrorCode.M0803_NOT_EXPORTED,
                hints = listOf(
                    "newtype constructors are not exported; look for a `pub` factory function in the defining module"
                ),
            )
        }
        // In the defining module, refuse old syntax and point to `.()`.
        throw TypeCheckError(
            message = "Newtype '${expr.constructor}' is not a record. Construct with '${expr.constructor}.(...)' — e.g., ${expr.constructor}.(<value>). Field-record construction was replaced with the .() syntax.",
            loc = expr.loc,
            errorType = ErrorType.TYPE_MISMATCH,
            code = ErrorCode.T0212_NOT_A_NEWTYPE,
        )
    }

    val isBuiltInAbility = BuiltInAbilities.isBuiltInAbility(expr.constructor)
    val isUserDefinedAbility = expr.constructor in context.abilityNames
    val isEffect = expr.constructor in context.effectNames

    if ((isBuiltInAbility || isUserDefinedAbility || isEffect) && expr.value == null) {
        val freshType = MezzeType.VarType(TypeVar.fresh())
        return ConstraintResult(
            type = freshType,
            constraints = emptyList(),
            undefinedVariables = listOf(
                ConstraintResult.UndefinedVariable(
                    name = expr.constructor,
                    loc = expr.loc
                )
            )
        )
    }

    val resultType = MezzeType.VarType(TypeVar.fresh())

    val valueExpr = expr.value
    if (valueExpr == null) {
        val constraints = listOf(
            Constraint.HasConstructor(
                variantType = resultType,
                constructorName = expr.constructor,
                payloadType = null,
                context = "Variant construction ${expr.constructor}",
                loc = expr.loc,
            )
        )

        return ConstraintResult(
            type = resultType,
            constraints = constraints,
            // #182: stash the loc → inferred-type entry so TypedAstBuilder
            // can recover the post-solve variant type. Without this it
            // falls back to its `contextType` default (a *shared*
            // TypeVar("unknown", 0)), and two no-payload variants in the
            // same record literal (e.g. `{ method = Get, body = None }`)
            // end up with the same TyVar instance — the solver pins one
            // but the other carries the unresolved alias and breaks
            // downstream unifications.
            callReturnTypes = mapOf(expr.loc to resultType)
        )
    } else {
        val payloadResult = generate(valueExpr, context)

        // #209: field-type constraints emitted for a NEWTYPE construction (below).
        // Variant constructors get these from HasConstructor solving; newtypes did
        // not, so `Meters { val = "boom" }` (Str into an Int newtype) type-checked.
        val newtypeFieldConstraints = mutableListOf<Constraint>()

        if (valueExpr is ParsedRecordLit && context.typeDeclarations.isNotEmpty()) {
            for ((typeName, typeDecl) in context.typeDeclarations) {
                val newtypeDef = typeDecl.definition as? ParsedNewtypeDef
                if (newtypeDef != null && newtypeDef.constructor == expr.constructor) {
                    val literalFields = valueExpr.entries.filterIsInstance<ParsedRecordEntry.Field>()
                    val providedType = payloadResult.type as? MezzeType.RecordType
                    when (val wrapped = newtypeDef.wrappedType) {
                        is ParsedRecordType -> {
                            // Record-newtype (`Point { x: Int, y: Int }`): field names
                            // are meaningful — pin each provided field to its declared
                            // type. (Name arity is left to the existing record-shape
                            // machinery; here we only enforce the field types, which
                            // was the missing check that let `Point { x = "no" }` pass.)
                            for (entry in wrapped.entries) {
                                val expectedFieldType = try { TypeAnnotationConverter.convert(entry.type, resolveTypeHash = { name -> registry?.lookupType(name)?.let { d -> d.contentHash ?: com.mezze.language.parsedast.ParsedDeclHasher.hashTypeDecl(d) } }) } catch (_: Exception) { null }
                                val actualFieldType = providedType?.fields?.get(entry.name)
                                if (expectedFieldType != null && actualFieldType != null) {
                                    newtypeFieldConstraints.add(Constraint.Equality(
                                        left = actualFieldType, right = expectedFieldType,
                                        context = "Newtype ${expr.constructor} field '${entry.name}'", loc = expr.loc,
                                    ))
                                }
                            }
                        }
                        else -> {
                            // Scalar-newtype (`Meters Int`): the payload is a single
                            // wrapped value, conventionally spelled `{ val = … }` OR
                            // `{ value = … }` (both are in active use). Enforce the
                            // wrapped TYPE on whatever single field was provided,
                            // without pinning the field NAME — so `Meters { val =
                            // "boom" }` (Str into an Int newtype) is now rejected.
                            val expectedFieldType = try { TypeAnnotationConverter.convert(wrapped, resolveTypeHash = { name -> registry?.lookupType(name)?.let { d -> d.contentHash ?: com.mezze.language.parsedast.ParsedDeclHasher.hashTypeDecl(d) } }) } catch (_: Exception) { null }
                            val soleField = literalFields.singleOrNull()
                            val actualFieldType = soleField?.let { providedType?.fields?.get(it.name) }
                            if (expectedFieldType != null && actualFieldType != null) {
                                newtypeFieldConstraints.add(Constraint.Equality(
                                    left = actualFieldType, right = expectedFieldType,
                                    context = "Newtype ${expr.constructor} wrapped value", loc = expr.loc,
                                ))
                            }
                        }
                    }
                }
                if (typeDecl.definition is ParsedVariantDef) {
                    val variantDef = typeDecl.definition as ParsedVariantDef
                    val constructor = variantDef.constructors.find { it.name == expr.constructor }

                    if (constructor != null && constructor.dataType is ParsedRecordType) {
                        val expectedRecordType = constructor.dataType as ParsedRecordType
                        val isOpen = expectedRecordType.isOpen

                        val literalFields = valueExpr.entries.filterIsInstance<ParsedRecordEntry.Field>().map { it.name }.toSet()
                        val expectedFields = expectedRecordType.entries.map { it.name }.toSet()

                        if (!isOpen) {
                            val extraFields = literalFields - expectedFields
                            if (extraFields.isNotEmpty()) {
                                throw TypeCheckError(
                                    message = "Variant constructor ${expr.constructor} expects exact fields ${expectedFields.sorted().joinToString(", ")} " +
                                            "but got extra field(s): ${extraFields.sorted().joinToString(", ")}",
                                    loc = expr.loc,
                                    errorType = ErrorType.TYPE_MISMATCH,
                                    code = ErrorCode.T0204_VARIANT_CONSTRUCTOR_ARITY,
                                    hints = listOf("remove: ${extraFields.sorted().joinToString(", ")}"),
                                )
                            }

                            val missingFields = expectedFields - literalFields
                            if (missingFields.isNotEmpty()) {
                                throw TypeCheckError(
                                    message = "Variant constructor ${expr.constructor} expects fields ${expectedFields.sorted().joinToString(", ")} " +
                                            "but missing field(s): ${missingFields.sorted().joinToString(", ")}",
                                    loc = expr.loc,
                                    errorType = ErrorType.MISSING_FIELD,
                                    code = ErrorCode.T0204_VARIANT_CONSTRUCTOR_ARITY,
                                    hints = listOf("add: ${missingFields.sorted().joinToString(", ")}"),
                                )
                            }
                        }
                    }
                }
            }
        }

        val constraints = mutableListOf<Constraint>()
        constraints.addAll(payloadResult.constraints)
        constraints.addAll(newtypeFieldConstraints)
        constraints.add(
            Constraint.HasConstructor(
                variantType = resultType,
                constructorName = expr.constructor,
                payloadType = payloadResult.type,
                context = "Variant construction ${expr.constructor}",
                loc = expr.loc,
            )
        )

        return ConstraintResult(
            type = resultType,
            constraints = constraints,
            effects = payloadResult.effects,
            effectInstances = payloadResult.effectInstances,
            undefinedVariables = payloadResult.undefinedVariables,
            usedVariables = payloadResult.usedVariables,
            abilityConstraints = payloadResult.abilityConstraints,
            referencedBindings = payloadResult.referencedBindings,
            methodCallResolutions = payloadResult.methodCallResolutions,
            // #182: stash the loc → inferred-type entry so TypedAstBuilder
            // can recover the post-solve variant type. See no-payload
            // branch above for the full rationale.
            callReturnTypes = payloadResult.callReturnTypes + (expr.loc to resultType)
        )
    }
}

internal fun ExpressionConstraintGenerator.generateLambda(expr: ParsedLambda, context: ConstraintContext): ConstraintResult {

    // unwrapExpected peels a SuspendType wrapper so an effectful expected
    // function type still drives the param/return push (otherwise the cast below
    // would miss and silently fall back to bottom-up inference).
    val expectedFunctionType = unwrapExpected(context.expectedType) as? MezzeType.FunctionType
    val expectedParamType = expectedFunctionType?.paramType as? MezzeType.RecordType

    val paramNames: List<String>
    val paramFields: Map<String, MezzeType>
    val isOpenPattern: Boolean

    when (val params = expr.params) {
        is ParsedLambdaParams.Untyped -> {
            paramNames = params.names
            isOpenPattern = params.isOpen

            paramFields = if (expectedParamType != null) {
                paramNames.associateWith { name ->
                    expectedParamType.fields[name] ?: MezzeType.VarType(TypeVar.fresh())
                }
            } else {
                paramNames.associateWith { MezzeType.VarType(TypeVar.fresh()) }
            }
        }
        is ParsedLambdaParams.Typed -> {
            isOpenPattern = false
            paramNames = params.fields.map { it.name }
            paramFields = params.fields.associate { entry ->
                entry.name to convertParsedTypeAnnotation(entry.type)
            }
        }
    }

    // #79: reject a parameter name that appears more than once (`{ x, x } -> x`).
    // Otherwise the associateWith/associate above silently collapses it and the
    // binding is ambiguous.
    val seenParams = HashSet<String>()
    for (name in paramNames) {
        if (!seenParams.add(name)) {
            throw TypeCheckError(
                message = "Duplicate parameter '$name' in lambda. Each parameter must have a distinct name.",
                loc = expr.loc,
                errorType = ErrorType.TYPE_MISMATCH,
                code = ErrorCode.T0305_DUPLICATE_LAMBDA_PARAM,
            )
        }
    }

    val rowVar = when {
        expectedParamType?.rowVar != null -> expectedParamType.rowVar
        isOpenPattern -> RowVar.fresh()
        else -> null
    }
    val paramType = MezzeType.RecordType(
        fields = paramFields,
        rowVar = rowVar
    )

    val extendedContext = paramFields.entries.fold(context) { ctx, (name, type) ->
        ctx.extend(name, type)
    }
        // #86: open a fresh function scope seeded with this lambda's params, so
        // a do-block `let` in the body that reuses a param name is a same-scope
        // re-bind (T0306), while shadowing an outer name stays legal.
        .withFreshScope(paramNames.toSet())

    val bodyContext = if (expectedFunctionType != null) {
        extendedContext.withExpectedType(expectedFunctionType.returnType).inTailPosition()
    } else {
        extendedContext.inTailPosition()
    }
    val bodyResult = generate(expr.body, bodyContext)

    val (mergedEffectInstances, effectMergeConstraints) = mergeEffectInstances(bodyResult.effectInstances, expr.body.loc)

    // #256: if the body calls a callback param whose effect row is open (`<r>`),
    // that row var flows out via bodyResult.effectRowVars. The lambda's OWN return
    // row must carry it, tying param row to return row (`{f: ..<r>} -> _ <r>`) —
    // real row polymorphism, matching how stdlib writes `spawn: {f: {}->'a <...>}
    // -> Promise 'a <Async, ...>` by hand. Without this, an un-annotated wrapper
    // (`launder`) infers a pure return and silently launders the callback's
    // effects (bug #256). We reuse the SAME var (must match the param); the single
    // distinct-var case is #256 and the common case. If the body has several
    // distinct open tails, the effect row can hold only one; we take the first and
    // the rest still contribute their concrete effect NAMES via the paths above,
    // so no named effect is lost (a fully-polymorphic 2nd tail is the rare gap).
    //
    // ONLY for UN-annotated lambdas. When the lambda has an explicit annotation
    // (`with_rollback : {f: {} -> Int <Rollback, ...>} -> Int <Db, ...>`), the
    // return row is fixed by the annotation and the strict declared-callback-row
    // check runs at the call site via unifyEffectRows/stampCaughtOnCallbackRows —
    // injecting the body's row var here would disturb that reconciliation and let
    // a callback that fails to perform a declared effect slip through
    // (CallbackContravariantRowTest). The launder hole is specifically the
    // inference (no-annotation) case.
    val bodyRowVar = if (expectedFunctionType == null) bodyResult.effectRowVars.firstOrNull() else null
    val effectRow = if (bodyResult.effects.isNotEmpty() || mergedEffectInstances.isNotEmpty() ||
        bodyResult.performed || bodyRowVar != null) {
        MezzeType.EffectRow(
            effects = bodyResult.effects,
            effectInstances = mergedEffectInstances,
            rowVar = bodyRowVar,
            performed = bodyResult.performed
        )
    } else {
        null
    }

    val effectAssociatedTypeConstraints = mutableListOf<Constraint>()
    val expectedEffects = expectedFunctionType?.effects
    if (expectedEffects != null && expectedEffects.effectInstances.isNotEmpty()) {
        val bodyInstances = bodyResult.effectInstances.associateBy { it.name }
        for (annotationInstance in expectedEffects.effectInstances) {
            val bodyInstance = bodyInstances[annotationInstance.name]
            if (bodyInstance != null) {
                for ((assocName, annotationType) in annotationInstance.associatedTypes) {
                    val normalizedName = assocName.removePrefix("'")
                    val bodyType = bodyInstance.associatedTypes[normalizedName]
                        ?: bodyInstance.associatedTypes[assocName]
                    if (bodyType != null) {
                        effectAssociatedTypeConstraints.add(
                            Constraint.Equality(
                                left = annotationType,
                                right = bodyType,
                                context = "Effect associated type '$assocName' in ${annotationInstance.name}",
                                loc = expr.loc,
                            )
                        )
                    }
                }
            }
        }
    }

    val returnType = when (val bodyType = bodyResult.type) {
        is MezzeType.SuspendType -> bodyType.valueType
        else -> bodyType
    }

    //    (#218) can resolve them at the call site. Without this, an unannotated
    //    lambda whose body uses an ability method (e.g. `w == h` desugaring
    //    to `w.eq {r = h}`) loses the `<Eq 'v>` constraint at the arrow,
    //    leaving MonoDriver.resolveAbilityConstraints with nothing to walk,
    //    so the specialized blob's body keeps the unresolved `'v` and the
    //    runtime crashes at __native_int_eq with l=null.
    // The lambda's own constraints take precedence since they're explicitly specified
    val abilityConstraints = when {
        expr.abilityConstraints.isNotEmpty() -> {
            expr.abilityConstraints.map { constraint ->
                MezzeType.FunctionAbilityConstraint(
                    abilityName = constraint.abilityName,
                    typeArgs = constraint.typeArgs.map { typeAnn ->
                        convertParsedTypeAnnotation(typeAnn)
                    },
                    abilityHash = context.abilityDeclarations[constraint.abilityName]?.contentHash
                        ?: registry?.resolveAbilityHash(constraint.abilityName)
                )
            }
        }
        expectedFunctionType?.abilityConstraints?.isNotEmpty() == true -> expectedFunctionType.abilityConstraints
        else -> bodyResult.abilityConstraints
            .distinctBy { it.abilityName to it.typeVar.id }
            .map { ab ->
                // Carry the associated-type link (e.g. `having 'out = 'c`) onto the
                // stored constraint so the call site can re-emit an
                // AssociatedTypeResolution against the freshened `'out`. Without
                // this, an inferred `{x,y}->x+y` stores `with NumAdd 'r 'l` but
                // forgets that its return var IS the `'out`, so calling it leaves
                // the result unresolved (`ans : 'a`). We match a body
                // AssociatedTypeResolution to this constraint by ability + primary
                // type var (the receiver `'l`).
                val bindings = bodyResult.associatedTypeConstraints
                    .filter { it.abilityName == ab.abilityName && it.primaryTypeVar.id == ab.typeVar.id }
                    .flatMap { res -> res.associatedTypeVars.entries }
                    .associate { (name, assocVar) -> name to (MezzeType.VarType(assocVar) as MezzeType) }
                // In a FunctionAbilityConstraint the receiver IS typeArgs[0] —
                // ImportContextMerger and ConstraintGenerator both derive
                // `typeParam` from it and keep it in the list. Internally
                // Constraint.AbilityRequired stores it separately in `typeVar`,
                // so it has to be put back — but only when the source did not
                // already carry it. A constraint that came from instantiating a
                // declared `with Priced 'a` arrives with the receiver still in
                // typeArgs, and prepending unconditionally printed the receiver
                // twice: `with Priced 'a 'a` for a one-parameter ability.
                // Arity is the reliable test, not variable identity: an
                // instantiated constraint has a FRESHENED receiver var, so
                // comparing it against typeArgs[0] only works when the two have
                // since been unified. The ability declaration says how many
                // arguments it takes, and typeArgs already being that long means
                // the receiver is in there.
                val declaredArity = context.abilityDeclarations[ab.abilityName]
                    ?.typeParams?.size
                val alreadyLed = declaredArity != null && ab.typeArgs.size >= declaredArity
                val receiver: MezzeType = MezzeType.VarType(ab.typeVar)
                MezzeType.FunctionAbilityConstraint(
                    abilityName = ab.abilityName,
                    typeArgs = if (alreadyLed) ab.typeArgs else listOf(receiver) + ab.typeArgs,
                    associatedTypeBindings = bindings,
                    abilityHash = ab.abilityHash
                )
            }
    }

    val abilityAssociatedTypeConstraints = mutableListOf<Constraint>()
    val expectedAbilityConstraints = expectedFunctionType?.abilityConstraints ?: emptyList()
    for (annotationConstraint in expectedAbilityConstraints) {
        if (annotationConstraint.associatedTypeBindings.isNotEmpty()) {
            val matchingBodyConstraints = bodyResult.associatedTypeConstraints.filter {
                it.abilityName == annotationConstraint.abilityName
            }

            for (bodyConstraint in matchingBodyConstraints) {
                for ((assocName, annotationType) in annotationConstraint.associatedTypeBindings) {
                    val normalizedName = assocName.removePrefix("'")
                    val bodyTypeVar = bodyConstraint.associatedTypeVars[normalizedName]
                        ?: bodyConstraint.associatedTypeVars[assocName]

                    if (bodyTypeVar != null) {
                        abilityAssociatedTypeConstraints.add(
                            Constraint.Equality(
                                left = annotationType,
                                right = MezzeType.VarType(bodyTypeVar),
                                context = "Ability associated type '$assocName' in ${annotationConstraint.abilityName}",
                                loc = expr.loc,
                            )
                        )
                    }
                }
            }
        }
    }

    val functionType = MezzeType.FunctionType(
        paramType = paramType,
        returnType = returnType,
        effects = effectRow,
        abilityConstraints = abilityConstraints
    )

    // #84 linter: a lambda parameter that the body never references is unused.
    // `_`-prefixed names are intentionally ignored (the convention already used
    // for match bindings), and are never flagged.
    val unusedParams = paramNames.filter { name ->
        !name.startsWith("_") && name !in bodyResult.usedVariables
    }.map { name ->
        ConstraintResult.UnusedVariable(name = name, loc = expr.loc)
    }

    // Option B / #377: when the lambda's expected type declares an effect
    // row, the body is allowed to bind values-carrying-that-effect
    // internally — the row promises those effects propagate up. Stamp the
    // row onto body constraints so ConstraintSolver.solveEquality's
    // strictEffectRows check can distinguish these legitimate in-body uses
    // from real leaks. Mirrors ExprPerformConstraints.generatePerform's
    // stamping; composes with it (each accumulator unions into the set).
    // Effects seed for stamping body constraints (Option B / #377).
    // Prefer the ambient expected row (annotated or ability-signature-derived).
    // Fall back to what the body itself uses — since Mezze row-polymorphic
    // inference will propagate the body's effects out to the enclosing
    // function's declared row, using them here is equivalent for the
    // "value with declared-effect is fine to bind inside body" scenario.
    val annotatedEffects = expectedFunctionType?.effects?.allEffectNames ?: emptySet()
    val declaredEffects = annotatedEffects + bodyResult.effects +
        bodyResult.effectInstances.map { it.name }.toSet()
    val stampedBodyConstraints = if (declaredEffects.isEmpty()) {
        bodyResult.constraints
    } else {
        bodyResult.constraints.map { c ->
            if (c is Constraint.Equality) {
                c.copy(activeDischargedEffects = c.activeDischargedEffects + declaredEffects)
            } else {
                c
            }
        }
    }
    // Propagate the body's associated-type resolutions to the enclosing binding
    // so the inferred function's ability `having` bindings can be populated. Any
    // ability method call in the body (e.g. `x.produce{}`, `x.first_key{}`) whose
    // receiver stays generic contributes a `having` binding to this function's
    // signature — see AbilityHavingParityTest.
    return ConstraintResult(
        type = functionType,
        constraints = stampedBodyConstraints + effectAssociatedTypeConstraints + abilityAssociatedTypeConstraints + effectMergeConstraints,
        abilityConstraints = bodyResult.abilityConstraints,
        associatedTypeConstraints = bodyResult.associatedTypeConstraints,
        undefinedVariables = bodyResult.undefinedVariables,
        tailCalls = bodyResult.tailCalls,
        usedVariables = bodyResult.usedVariables,
        unusedVariables = bodyResult.unusedVariables + unusedParams,
        patternWarnings = bodyResult.patternWarnings,
        referencedBindings = bodyResult.referencedBindings,
        methodCallResolutions = bodyResult.methodCallResolutions,
        callReturnTypes = bodyResult.callReturnTypes,
        matchCaseBindings = bodyResult.matchCaseBindings,
    )
}

internal fun ExpressionConstraintGenerator.mergeEffectInstances(
    instances: Set<MezzeType.EffectInstance>,
    loc: Loc? = null,
): Pair<Set<MezzeType.EffectInstance>, List<Constraint>> {
    if (instances.isEmpty()) {
        return Pair(emptySet(), emptyList())
    }

    val groupedByName = instances.groupBy { it.name }

    val mergedInstances = mutableSetOf<MezzeType.EffectInstance>()
    val constraints = mutableListOf<Constraint>()

    for ((effectName, instancesForEffect) in groupedByName) {
        if (instancesForEffect.size == 1) {
            mergedInstances.add(instancesForEffect.first())
        } else {
            val allKeys = instancesForEffect.flatMap { it.associatedTypes.keys }.toSet()

            val mergedAssocTypes = mutableMapOf<String, MezzeType>()
            for (key in allKeys) {
                val typesForKey = instancesForEffect.mapNotNull { it.associatedTypes[key] }

                if (typesForKey.isEmpty()) {
                    continue
                } else if (typesForKey.size == 1) {
                    mergedAssocTypes[key] = typesForKey.first()
                } else {
                    val mergedType = mergeAssociatedTypes(typesForKey, constraints, loc)
                    mergedAssocTypes[key] = mergedType
                }
            }

            mergedInstances.add(MezzeType.EffectInstance(effectName, mergedAssocTypes))
        }
    }

    return Pair(mergedInstances, constraints)
}

internal fun ExpressionConstraintGenerator.mergeAssociatedTypes(
    types: List<MezzeType>,
    constraints: MutableList<Constraint>,
    loc: Loc? = null,
): MezzeType {
    val variantTypes = types.filterIsInstance<MezzeType.VariantType>()

    if (variantTypes.size == types.size && variantTypes.isNotEmpty()) {
        val mergedConstructors = mutableMapOf<String, MezzeType?>()

        for (variant in variantTypes) {
            for ((constructorName, payloadType) in variant.constructors) {
                val existingPayload = mergedConstructors[constructorName]
                if (existingPayload == null && constructorName !in mergedConstructors) {
                    mergedConstructors[constructorName] = payloadType
                } else if (payloadType != null && existingPayload != null) {
                    val mergedPayloadVar = MezzeType.VarType(TypeVar.fresh())
                    constraints.add(
                        Constraint.Equality(
                            left = existingPayload,
                            right = mergedPayloadVar,
                            context = "Merging variant constructor '$constructorName' payload",
                            loc = loc,
                        )
                    )
                    constraints.add(
                        Constraint.Equality(
                            left = payloadType,
                            right = mergedPayloadVar,
                            context = "Merging variant constructor '$constructorName' payload",
                            loc = loc,
                        )
                    )
                    mergedConstructors[constructorName] = mergedPayloadVar
                }
                else if (payloadType != null && existingPayload == null) {
                    mergedConstructors[constructorName] = payloadType
                }
            }
        }

        return MezzeType.VariantType(
            constructors = mergedConstructors,
            rowVar = null
        )
    }

    val mergedTypeVar = MezzeType.VarType(TypeVar.fresh())
    for (typeForKey in types) {
        constraints.add(
            Constraint.Equality(
                left = typeForKey,
                right = mergedTypeVar,
                context = "Merging effect associated types from branches",
                isMatchCaseContext = true,
                loc = loc,
            )
        )
    }
    return mergedTypeVar
}

internal fun ExpressionConstraintGenerator.generateFunctionCall(expr: ParsedFunctionCall, context: ConstraintContext): ConstraintResult {

    // VALIDATION: Reject spread-only records in function calls (Bug #10 Extension)
    // Valid: func { x = 1, y = 2 } - explicit fields
    // Valid: func { ...k, x = 1 } - spread with explicit fields
    // Invalid: func { ...k } - spread-only
    if (expr.arguments is ParsedRecordLit) {
        val recordLiteral = expr.arguments as ParsedRecordLit
        val hasExplicitFields = recordLiteral.entries.any { it is ParsedRecordEntry.Field }
        val hasSpreadEntries = recordLiteral.entries.any { it is ParsedRecordEntry.Spread }

        if (hasSpreadEntries && !hasExplicitFields) {
            val functionName = when (expr.function) {
                is ParsedIdentifier -> (expr.function as ParsedIdentifier).localName
                else -> "function"
            }

            val spreadEntry = recordLiteral.entries.filterIsInstance<ParsedRecordEntry.Spread>().first()
            val spreadVarName = when (spreadEntry.expr) {
                is ParsedIdentifier -> (spreadEntry.expr as ParsedIdentifier).localName
                else -> "record"
            }

            throw TypeCheckError(
                message = "cannot use spread-only records in function call to '$functionName'. " +
                        "Spread variable: '$spreadVarName'. " +
                        "Use explicit named arguments instead, e.g., { x = $spreadVarName.x, y = $spreadVarName.y }",
                loc = expr.loc,
                errorType = ErrorType.TYPE_MISMATCH,
                metadata = mapOf(
                    "functionName" to functionName,
                    "spreadVariable" to spreadVarName
                ),
                code = ErrorCode.V0708_SPREAD_ONLY_RECORD_FORBIDDEN,
                hints = listOf("write each field explicitly: `{ x = $spreadVarName.x, y = $spreadVarName.y }`"),
            )
        }
    }

    if (expr.function is ParsedMemberAccess) {
        val memberAccess = expr.function as ParsedMemberAccess
        val receiverName = when (memberAccess.receiver) {
            is ParsedIdentifier -> (memberAccess.receiver as ParsedIdentifier).localName
            is ParsedVariantConstruct -> (memberAccess.receiver as ParsedVariantConstruct).constructor
            else -> null
        }
        val isAbilityOrEffect = receiverName != null && receiverName.first().isUpperCase() &&
            (context.typingContext.getAbilityOperationType(receiverName) != null ||
             context.typingContext.getEffectOperationType(receiverName) != null ||
             context.performerToEffect.containsKey(receiverName) ||
             BuiltInAbilities.isBuiltInAbility(receiverName))

        if (isAbilityOrEffect && receiverName != null) {
            val isUserAbility = context.typingContext.getAbilityOperationType(receiverName) != null
            val isBuiltInAb = BuiltInAbilities.isBuiltInAbility(receiverName)
            if ((isUserAbility || isBuiltInAb) && expr.arguments is ParsedRecordLit) {
                val argRecord = expr.arguments as ParsedRecordLit
                val itEntry = argRecord.entries
                    .filterIsInstance<ParsedRecordEntry.Field>()
                    .find { it.name == Keywords.IT }
                val itReceiver: ParsedExpr? = itEntry?.let {
                    it.value ?: ParsedIdentifier(
                        qualifier = null,
                        localName = Keywords.IT,
                        loc = it.loc
                    )
                }
                if (itReceiver != null) {
                    val remainingFields = argRecord.entries.filter { e ->
                        e !is ParsedRecordEntry.Field || e.name != Keywords.IT
                    }
                    val syntheticArgs = ParsedRecordLit(remainingFields, argRecord.loc)
                    val syntheticReceiverAccess = ParsedMemberAccess(
                        receiver = itReceiver,
                        member = memberAccess.member,
                        loc = memberAccess.loc
                    )
                    val syntheticCall = ParsedFunctionCall(
                        function = syntheticReceiverAccess,
                        arguments = syntheticArgs,
                        loc = expr.loc
                    )
                    val qualifiedContext = context.copy(
                        annotationAbilityNames = setOf(receiverName)
                    )
                    val methodResult = tryGenerateMethodCall(
                        syntheticCall,
                        syntheticReceiverAccess,
                        qualifiedContext
                    )
                    if (methodResult != null) {
                        return methodResult
                    }
                }
            }
        }

        val isUnknownUppercaseName = !isAbilityOrEffect &&
            memberAccess.receiver is ParsedVariantConstruct &&
            context.typingContext.lookup(receiverName ?: "") is TypingContext.LookupResult.NotFound

        if (!isAbilityOrEffect && !isUnknownUppercaseName) {
            val methodCallResult = tryGenerateMethodCall(expr, memberAccess, context)
            if (methodCallResult != null) {
                return methodCallResult
            }
        }
    }

    val functionResult = generate(expr.function, context.notInTailPosition())

    // Bidirectional: the callee's type is known before its arguments are
    // generated, so push its parameter type down as the expected type of the
    // argument record. Because the argument is a record literal, generateRecordLiteral
    // then fans each param field type into the matching argument field — a lambda
    // argument gets its param/return types top-down instead of fresh vars. We only
    // push when the callee is a concrete FunctionType with a RecordType parameter;
    // otherwise (polymorphic/unresolved callee) we fall back to pure inference and
    // let the post-hoc Equality constraints below reconcile types as before.
    val calleeParamType = (unwrapExpected(functionResult.type) as? MezzeType.FunctionType)?.paramType as? MezzeType.RecordType
    val argContext = context.notInTailPosition()
    val argumentResult = generateWithExpected(expr.arguments, calleeParamType, argContext)

    val isSelfTailCall = context.inTailPosition &&
        context.enclosingFunctionName != null &&
        expr.function is ParsedIdentifier &&
        (expr.function as ParsedIdentifier).qualifier == null &&
        (expr.function as ParsedIdentifier).localName == context.enclosingFunctionName

    val tailCallLocation = if (isSelfTailCall) expr.loc else null

    val functionType = functionResult.type
    val argumentType = argumentResult.type

    if (functionType is MezzeType.FunctionType && argumentType is MezzeType.RecordType) {
        val funcParamType = functionType.paramType
        if (funcParamType is MezzeType.RecordType) {
            val expectedFields = funcParamType.fields.keys
            val providedFields = argumentType.fields.keys
            val missingFields = expectedFields - providedFields
            val extraFields = providedFields - expectedFields

            if (extraFields.isNotEmpty() && funcParamType.rowVar == null) {
                val functionName = when (expr.function) {
                    is ParsedIdentifier -> (expr.function as ParsedIdentifier).localName
                    else -> "function"
                }
                throw TypeCheckError(
                    message = "Extra field(s) '${extraFields.joinToString(", ")}' in call to '$functionName'. " +
                            "Function expects fields: ${expectedFields.sorted().joinToString(", ")}",
                    loc = expr.loc,
                    errorType = ErrorType.TYPE_MISMATCH,
                    code = ErrorCode.T0203_RECORD_EXTRA_FIELDS,
                )
            }

            if (missingFields.isNotEmpty()) {
                val collector = ConstraintCollector()
                collector.merge(functionResult)
                collector.merge(argumentResult)

                for (fieldName in providedFields) {
                    val expectedType = funcParamType.fields[fieldName]
                    val providedType = argumentType.fields[fieldName]
                    if (expectedType != null && providedType != null) {
                        collector.constraints.add(
                            Constraint.Equality(
                                left = providedType,
                                right = expectedType,
                                context = "Partial application field $fieldName",
                                loc = expr.loc
                            )
                        )
                    }
                }

                val remainingFields = funcParamType.fields.filterKeys { it in missingFields }
                val remainingParamType = MezzeType.RecordType(
                    fields = remainingFields,
                    rowVar = funcParamType.rowVar
                )

                val remainingTypeVars = collectFreeTypeVars(remainingParamType) + collectFreeTypeVars(functionType.returnType)
                val filteredAbilityConstraints = mutableListOf<MezzeType.FunctionAbilityConstraint>()
                val concreteAbilityChecks = mutableListOf<Constraint.AbilityRequired>()

                for (constraint in functionType.abilityConstraints) {
                    val hasPolymorphicArg = constraint.typeArgs.any { typeArg ->
                        typeArg is MezzeType.VarType && typeArg.variable in remainingTypeVars
                    }
                    if (hasPolymorphicArg) {
                        filteredAbilityConstraints.add(constraint)
                    } else {
                        for (typeArg in constraint.typeArgs) {
                            if (typeArg is MezzeType.VarType) {
                                concreteAbilityChecks.add(
                                    Constraint.AbilityRequired(
                                        typeVar = typeArg.variable,
                                        abilityName = constraint.abilityName,
                                        abilityHash = constraint.abilityHash,
                                        typeArgs = constraint.typeArgs,
                                        context = "Partial application: ability constraint on fixed type variable",
                                        loc = expr.loc
                                    )
                                )
                            }
                        }
                    }
                }

                val partialResult = MezzeType.FunctionType(
                    paramType = remainingParamType,
                    returnType = functionType.returnType,
                    effects = functionType.effects,
                    abilityConstraints = filteredAbilityConstraints
                )

                collector.abilityConstraints.addAll(concreteAbilityChecks)
                collector.callReturnTypes[expr.loc] = partialResult
                return collector.toResult(partialResult)
            }
        }
    }

    if (functionType is MezzeType.FunctionType) {
        val effectRow = functionType.effects
        if (effectRow != null && (effectRow.effects.isNotEmpty() || effectRow.effectInstances.isNotEmpty() || effectRow.rowVar != null || effectRow.performed)) {
            val collector = ConstraintCollector()
            collector.merge(functionResult)
            collector.merge(argumentResult)

            val functionName = when (expr.function) {
                is ParsedIdentifier -> (expr.function as ParsedIdentifier).localName
                else -> null
            }
            collector.constraints.add(
                Constraint.Equality(
                    left = argumentResult.type,
                    right = functionType.paramType,
                    context = "Function application argument",
                    loc = expr.loc,
                    errorContext = ErrorContext.FunctionArgument(
                        functionName = functionName,
                        argumentName = extractArgumentName(expr.arguments),
                        parameterIndex = 0
                    )
                )
            )

            // #312: when the function's effect row has a row var (e.g. spawn declared
            // `<Async, ...>`), harvest effects from any closure-typed argument fields.
            // Those effects flow through the row var to the surrounding scope. We do this
            // as a *side-channel* read here (no Substitution involvement) because mixing
            // row var bindings into the substitution machinery interacts badly with type
            // var unification and broke 12 interp tests in the previous attempt.
            //
            // Row subtraction: effects on the DECLARED callback row that
            // aren't on the outer op's return row are marked as
            // caughtByAncestor. These are effects the enclosing function
            // catches (e.g., spawn catches Exception). Filter them from
            // the harvested effects — they should NOT flow to the caller.
            val caughtByOp = collectCaughtByAncestor(functionType.paramType)
            val rowVarEffects: Set<String> = if (effectRow.rowVar != null) {
                collectClosureArgEffects(argumentResult.type) - caughtByOp
            } else emptySet()
            if (caughtByOp.isNotEmpty()) {
                collector.effects.removeAll(caughtByOp)
            }
            collector.effects.addAll(effectRow.effects + rowVarEffects)
            collector.effectInstances.addAll(effectRow.effectInstances)
            // #256: the callee's own effect row var flows out to the enclosing
            // scope. Carrying it lets an un-annotated wrapper fn (`launder`) infer
            // an OPEN return row instead of silently dropping the callback's tail.
            effectRow.rowVar?.let { collector.effectRowVars.add(it) }
            collector.performed = collector.performed || effectRow.performed

            if (tailCallLocation != null) collector.tailCalls.add(tailCallLocation)

            if (functionType.abilityConstraints.isNotEmpty()) {
                val methodLevelConstraints = functionType.abilityConstraints.mapNotNull { constraint ->
                    val constrainedType = constraint.typeArgs.firstOrNull()
                    if (constrainedType is MezzeType.VarType) {
                        Constraint.AbilityRequired(
                            typeVar = constrainedType.variable,
                            abilityName = constraint.abilityName,
                            abilityHash = context.abilityDeclarations[constraint.abilityName]?.contentHash,
                            typeArgs = constraint.typeArgs.drop(1),
                            context = "Method-level constraint: ${constraint.abilityName} on ${constrainedType.variable.name}",
                            loc = expr.loc
                        )
                    } else null
                }
                collector.abilityConstraints.addAll(methodLevelConstraints)
            }

            val (resultType, _, _) = wrapCallResultWithEffects(functionType.returnType, effectRow)

            collector.callReturnTypes[expr.loc] = resultType
            return collector.toResult(resultType)
        }
    }

    val returnType: MezzeType = run {
        val ft = functionResult.type
        if (ft is MezzeType.FunctionType) {
            val freeVars = collectFreeTypeVars(ft.returnType)
            if (freeVars.isEmpty()) ft.returnType
            else MezzeType.VarType(TypeVar.fresh())
        } else MezzeType.VarType(TypeVar.fresh())
    }

    val collector = ConstraintCollector()
    collector.merge(functionResult)
    collector.merge(argumentResult)

    val functionName = when (expr.function) {
        is ParsedIdentifier -> (expr.function as ParsedIdentifier).localName
        else -> null
    }
    collector.constraints.add(
        Constraint.Equality(
            left = MezzeType.FunctionType(
                paramType = argumentResult.type,
                returnType = returnType
            ),
            right = functionResult.type,
            context = "Function application",
            loc = expr.loc,
            errorContext = ErrorContext.FunctionArgument(
                functionName = functionName,
                argumentName = extractArgumentName(expr.arguments),
                parameterIndex = 0
            )
        )
    )

    // #312: when calling an effect operation (functionResult.effects is
    // non-empty — set by tryGenerateEffectOperationCall), the operation's
    // declared signature may have a polymorphic effect row (`<Effect, ...>`)
    // for inputs like `f: {} -> 'a <...>`. Pulling effects out of any
    // closure-typed argument fields here surfaces them at the call site
    // — `Async.spawn { f = effectful }` ends up performing `<Async, …f's effects>`.
    // Pure side-channel: argument types are inspected, no Substitution touched.
    //
    // Row subtraction: effects declared on the callback row but not on
    // the outer op's return row are caught by the op (e.g., spawn catches
    // Exception). Filter them out — they don't flow to the caller.
    val caughtByOp2 = if (functionResult.type is MezzeType.FunctionType) {
        collectCaughtByAncestor((functionResult.type as MezzeType.FunctionType).paramType)
    } else emptySet()
    val rowVarEffects = if (functionResult.effects.isNotEmpty())
        collectClosureArgEffects(argumentResult.type) - caughtByOp2
    else emptySet()
    if (functionResult.type is MezzeType.FunctionType) {
        val fnType = functionResult.type as MezzeType.FunctionType
        collector.effects.addAll(fnType.effects?.effects ?: emptySet())
        // #256: calling a value whose effect row has a row var (e.g. a callback
        // PARAM `f: {..} -> _ <r>`) flows that row var out. Carrying it here lets
        // the enclosing un-annotated wrapper infer an OPEN return row and tie its
        // param row to its return row, instead of silently inferring pure and
        // laundering the callback's real effects. A named-effect handler
        // (caughtByOp2) does NOT discharge an unknown polymorphic tail, so this
        // propagates regardless of caughtByOp2.
        fnType.effects?.rowVar?.let { collector.effectRowVars.add(it) }
        collector.performed = collector.performed || (fnType.effects?.performed ?: false)
    }
    // Row subtraction extends to any effects that flowed up via
    // collector.merge(argumentResult): caught effects must not reach caller.
    if (caughtByOp2.isNotEmpty()) {
        collector.effects.removeAll(caughtByOp2)
    }
    collector.effects.addAll(rowVarEffects)

    if (functionResult.type is MezzeType.FunctionType) {
        val funcType = functionResult.type as MezzeType.FunctionType
        if (funcType.abilityConstraints.isNotEmpty()) {
            val methodLevelConstraints = funcType.abilityConstraints.mapNotNull { constraint ->
                val constrainedType = constraint.typeArgs.firstOrNull()
                if (constrainedType is MezzeType.VarType) {
                    Constraint.AbilityRequired(
                        typeVar = constrainedType.variable,
                        abilityName = constraint.abilityName,
                        abilityHash = context.abilityDeclarations[constraint.abilityName]?.contentHash,
                        typeArgs = constraint.typeArgs.drop(1),
                        context = "Method-level constraint: ${constraint.abilityName} on ${constrainedType.variable.name}",
                        loc = expr.loc
                    )
                } else null
            }
            collector.abilityConstraints.addAll(methodLevelConstraints)

            // Re-emit an AssociatedTypeResolution for each called-fn ability
            // obligation that carries associated-type bindings (`having 'out = 'c`).
            // The AbilityRequired above only *checks* the obligation; it never pins
            // the associated `'out` var to the matched impl's `having` type. So a
            // call `my_func {x=5, y=8.6}` where `my_func : … with NumAdd 'r 'l
            // having 'out = 'c -> 'c` would leave the result var `'c` unresolved
            // (`ans : 'a`). This mirrors the direct-`+` path in
            // ExprMethodConstraints; solveAssociatedTypeResolution then matches the
            // impl by the full class-arg tuple and unifies `'out` with `Float`.
            // Rejection of unimplemented pairs (e.g. Int+Str) stays with the
            // unchanged AbilityRequired — this constraint only resolves, never
            // accepts (it defers when no impl matches).
            val associatedTypeResolutions = funcType.abilityConstraints.mapNotNull { constraint ->
                if (constraint.associatedTypeBindings.isEmpty()) return@mapNotNull null
                val primary = (constraint.typeArgs.firstOrNull() as? MezzeType.VarType)?.variable
                    ?: return@mapNotNull null
                val assocVars = constraint.associatedTypeBindings.mapNotNull { (name, bound) ->
                    (bound as? MezzeType.VarType)?.let { name to it.variable }
                }.toMap()
                if (assocVars.isEmpty()) return@mapNotNull null
                val classArgVars = constraint.typeArgs.mapNotNull { (it as? MezzeType.VarType)?.variable }
                Constraint.AssociatedTypeResolution(
                    abilityName = constraint.abilityName,
                    primaryTypeVar = primary,
                    associatedTypeVars = assocVars,
                    classArgTypeVars = classArgVars,
                    context = "Call-site associated type resolution for ${constraint.abilityName}",
                    loc = expr.loc
                )
            }
            collector.associatedTypeConstraints.addAll(associatedTypeResolutions)
        }
    }

    if (tailCallLocation != null) collector.tailCalls.add(tailCallLocation)

    collector.callReturnTypes[expr.loc] = returnType
    return collector.toResult(returnType)
}

