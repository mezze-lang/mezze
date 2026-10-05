package com.mezze.language.typechecker.constraints

import com.mezze.language.ast.*
import com.mezze.language.parsedast.*
import com.mezze.language.typechecker.abilities.BuiltInAbilities
import com.mezze.language.typechecker.context.TypingContext
import com.mezze.language.typechecker.dependencies.DefinitionRef
import com.mezze.language.typechecker.TypeCheckError
import com.mezze.language.ast.SyntaxMetadata
import com.mezze.language.parsedast.diagnostics.ErrorCode

internal fun ExpressionConstraintGenerator.generateBinaryOp(expr: ParsedBinaryOp, context: ConstraintContext): ConstraintResult {
    val op = expr.op.resolved
        ?: throw TypeCheckError(
            "Unknown operator: ${expr.op.symbol}", expr.loc,
            code = ErrorCode.T0401_UNKNOWN_OPERATOR,
            hints = listOf("binary operators desugar to method calls; check that `${expr.op.symbol}` is a known operator"),
        )

    val desugared = ParsedFunctionCall(
        function = ParsedMemberAccess(expr.left, op.method, expr.loc),
        arguments = ParsedRecordLit(listOf(ParsedRecordEntry.Field("r", expr.right, expr.loc)), expr.loc),
        loc = expr.loc
    )

    SyntaxMetadata.markAsOperator(expr.loc, expr.op.symbol)

    return generate(desugared, context)
}

internal fun ExpressionConstraintGenerator.generateIntLiteral(expr: ParsedIntLit, context: ConstraintContext) = ConstraintResult.simple(MezzeType.IntType)
internal fun ExpressionConstraintGenerator.generateNatLiteral(expr: ParsedNatLit, context: ConstraintContext) = ConstraintResult.simple(natLiteralType(context))

internal fun natLiteralType(context: ConstraintContext): MezzeType {
    val decl = context.typeDeclarations["Nat"]
    val newtypeDef = decl?.definition as? ParsedNewtypeDef
    return if (newtypeDef != null) {
        val hash = decl.contentHash
        MezzeType.NewtypeType(
            name = "Nat",
            constructor = newtypeDef.constructor,
            wrappedType = MezzeType.IntType,
            identity = if (hash != null) TypeIdentity.Declared(hash, "Nat") else TypeIdentity.Anonymous,
        )
    } else {
        // Fallback: stdlib not loaded (e.g. tests without prelude).
        MezzeType.NewtypeType(
            name = "Nat",
            constructor = "Nat",
            wrappedType = MezzeType.IntType,
        )
    }
}
internal fun ExpressionConstraintGenerator.generateFloatLiteral(expr: ParsedFloatLit, context: ConstraintContext) = ConstraintResult.simple(MezzeType.FloatType)
internal fun ExpressionConstraintGenerator.generateFractionLiteral(expr: ParsedFracLit, context: ConstraintContext) = ConstraintResult.simple(MezzeType.FracType)

internal fun ExpressionConstraintGenerator.generateStringLiteral(expr: ParsedStringLit, context: ConstraintContext): ConstraintResult {
    val isInterpolated = expr.parts.any { it is ParsedStringPart.Interpolation }
    if (!isInterpolated) return ConstraintResult.simple(MezzeType.StringType)

    val collector = ConstraintCollector()

    var interpolationIndex = 0
    for (part in expr.parts) {
        when (part) {
            is ParsedStringPart.Literal -> {
            }
            is ParsedStringPart.Interpolation -> {
                val exprResult = generate(part.expr, context)
                collector.merge(exprResult)
                collector.constraints.add(
                    Constraint.Equality(
                        left = exprResult.type,
                        right = MezzeType.StringType,
                        context = "String interpolation requires String type",
                        // Point at the interpolated EXPRESSION, not the whole
                        // string literal — so the T0201 squiggle and its
                        // `.to_str {}` quick-fix target just `$( … )`'s inner expr.
                        loc = part.expr.loc,
                        errorContext = ErrorContext.StringInterpolation(interpolationIndex)
                    )
                )
                interpolationIndex++
            }
        }
    }

    return collector.toResult(MezzeType.StringType)
}

internal fun ExpressionConstraintGenerator.generateSeqLiteral(expr: ParsedSeqLit, context: ConstraintContext): ConstraintResult {
    if (expr.elements.isEmpty()) {
        val elementType = MezzeType.VarType(TypeVar.fresh())
        return ConstraintResult.simple(com.mezze.language.ast.BuiltinTypes.seqApp(elementType))
    }

    val elementType = MezzeType.VarType(TypeVar.fresh())
    val collector = ConstraintCollector()

    var elementIndex = 0
    for (element in expr.elements) {
        when (element) {
            is ParsedSeqElement.Regular -> {
                val result = generate(element.expr, context)
                collector.merge(result)
                collector.constraints.add(
                    Constraint.Equality(
                        left = result.type,
                        right = elementType,
                        context = "Seq elements must have same type",
                        loc = element.expr.loc,
                        errorContext = ErrorContext.SeqElement(elementIndex)
                    )
                )
                elementIndex++
            }
            is ParsedSeqElement.Spread -> {
                val result = generate(element.expr, context)
                collector.merge(result)
                collector.constraints.add(
                    Constraint.Equality(
                        left = result.type,
                        right = com.mezze.language.ast.BuiltinTypes.seqApp(elementType),
                        context = "Spread element must be a seq",
                        loc = element.expr.loc,
                        errorContext = ErrorContext.SeqElement(elementIndex)
                    )
                )
                elementIndex++
            }
        }
    }

    return collector.toResult(com.mezze.language.ast.BuiltinTypes.seqApp(elementType))
}

internal fun ExpressionConstraintGenerator.generateIdentifier(expr: ParsedIdentifier, context: ConstraintContext): ConstraintResult {
    val lookupKey = BindingKey(qualifier = expr.qualifier?.segments?.lastOrNull(), localName = expr.localName)
    val lookupKeyString = lookupKey.toKeyString()

    return when (val result = context.typingContext.lookup(lookupKey)) {
        is TypingContext.LookupResult.Found -> {
            when (val binding = result.binding) {
                is TypingContext.Binding.Monomorphic -> {
                    val type = binding.type
                    val referencedBinding = DefinitionRef.ByName(binding.sourceModule, lookupKeyString)
                    ConstraintResult(
                        type = type,
                        constraints = emptyList(),
                        usedVariables = setOf(lookupKeyString),
                        referencedBindings = setOf(referencedBinding)
                    )
                }
                is TypingContext.Binding.Polymorphic -> {
                    val instantiation = binding.scheme.instantiate()

                    val abilityConstraints = instantiation.freshConstraints.map { freshConstraint ->
                        // The callee's OWN ability, carried through generalization and
                        // instantiation. The table lookup is the fallback for a
                        // constraint that never had a hash, not the answer.
                        val abilityHash = freshConstraint.abilityHash
                            ?: context.abilityDeclarations[freshConstraint.abilityName]?.contentHash
                        Constraint.AbilityRequired(
                            typeVar = freshConstraint.typeVar,
                            abilityName = freshConstraint.abilityName,
                            abilityHash = abilityHash,
                            typeArgs = freshConstraint.typeArgs,
                            context = "Polymorphic instantiation of '${lookupKeyString}'",
                            loc = expr.loc
                        )
                    }

                    // When a class-param obligation's receiver was concrete at
                    // generalization (`NumAdd Int 'a`, the `Int` from a literal), pin
                    // the freshened receiver var to that concrete type so the
                    // AbilityRequired above sees the real receiver and can REJECT a
                    // bad one — e.g. `f {x = "s"}` must fail on the missing
                    // `NumAdd Int Str` impl, not launder past an unresolved var.
                    val concreteReceiverEqualities = instantiation.freshConstraints.mapNotNull { freshConstraint ->
                        freshConstraint.concreteReceiver?.let { receiver ->
                            Constraint.Equality(
                                left = MezzeType.VarType(freshConstraint.typeVar),
                                right = receiver,
                                context = "Concrete ability receiver for ${freshConstraint.abilityName}",
                                loc = expr.loc
                            )
                        }
                    }

                    // For each instantiated ability obligation carrying associated-type
                    // bindings (`having 'out = 'c`), emit an AssociatedTypeResolution
                    // over the freshened vars. This is what pins a called polymorphic
                    // `+`-fn's result: `my_func {x=5, y=8.6}` resolves `'out` to Float
                    // once the args make `'l`/`'r` concrete. AbilityRequired above still
                    // enforces rejection of unimplemented pairs; this only resolves.
                    val associatedTypeResolutions = instantiation.freshConstraints.mapNotNull { freshConstraint ->
                        if (freshConstraint.associatedTypeBindings.isEmpty()) return@mapNotNull null
                        val assocVars = freshConstraint.associatedTypeBindings.mapNotNull { (name, bound) ->
                            (bound as? MezzeType.VarType)?.let { name.removePrefix("'") to it.variable }
                        }.toMap()
                        if (assocVars.isEmpty()) return@mapNotNull null
                        val classArgVars = (listOf(MezzeType.VarType(freshConstraint.typeVar)) + freshConstraint.typeArgs)
                            .mapNotNull { (it as? MezzeType.VarType)?.variable }
                        Constraint.AssociatedTypeResolution(
                            abilityName = freshConstraint.abilityName,
                            primaryTypeVar = freshConstraint.typeVar,
                            associatedTypeVars = assocVars,
                            classArgTypeVars = classArgVars,
                            abilityHash = context.abilityDeclarations[freshConstraint.abilityName]?.contentHash,
                            context = "Call-site associated type resolution for ${freshConstraint.abilityName}",
                            loc = expr.loc
                        )
                    }

                    val type = instantiation.type
                    val referencedBinding = DefinitionRef.ByName(binding.sourceModule, lookupKeyString)

                    ConstraintResult(
                        type = type,
                        constraints = concreteReceiverEqualities,
                        abilityConstraints = abilityConstraints,
                        associatedTypeConstraints = associatedTypeResolutions,
                        usedVariables = setOf(lookupKeyString),
                        referencedBindings = setOf(referencedBinding)
                    )
                }
            }
        }
        is TypingContext.LookupResult.NotFound -> {

            // #109: `__native_*` intrinsics are stdlib-only. User code that
            // references one falls through to the normal "undefined variable"
            // error path, preventing the type-check bypass (fresh-var fast
            // path) that was silently accepting `__native_str_eq { l = u, r = u }`
            // where u: UserId — leaking nominal opacity through the native
            // boundary. `crash` remains available everywhere (universal panic).
            // #281: recognise the intrinsic names via MagicParams constants
            // (single-sourced) rather than raw string literals, but keep the
            // #109 stdlib-only gate on the native prefix.
            if (expr.localName == com.mezze.language.ast.MagicParams.CRASH ||
                (expr.localName.startsWith(com.mezze.language.ast.MagicParams.NATIVE_PREFIX) && isStdlibModule)) {
                // Polyglot member natives (`__native_polyglot_read_member` etc.)
                // take a foreign value and return `Result <same foreign> InteropError`.
                // The generic native path below hands back a bare fresh var, erasing
                // the foreign's type — which breaks `x.attr{}.call{}` chaining (the
                // next `.attr`/`.as_str` can't resolve its inherent-method dict on a
                // bare var). Give them their REAL signature so the foreign type flows
                // through the chain and it all infers with ZERO annotations in the
                // provider `impl <Lang>Foreign`. See [polyglotNativeSignature].
                // The native's own declared type, read off the `pub let` that wraps
                // it (see `nativeDeclaredTypes`). A fresh variable is what this used
                // to hand back for every native, which is nothing for the solve to
                // work from — and where the native's result does not flow straight
                // out of a typed wrapper, nothing is what the caller kept.
                val declaredNative = nativeDeclaredTypes[expr.localName]
                val polyglotType = polyglotNativeSignature(expr.localName, context)
                val nativeType = declaredNative
                    ?: polyglotType
                    ?: MezzeType.VarType(TypeVar.fresh())
                return ConstraintResult(
                    type = nativeType,
                    constraints = emptyList(),
                    referencedBindings = setOf(DefinitionRef.ByName(null, lookupKeyString))
                )
            }

            val isBuiltInAbility = BuiltInAbilities.isBuiltInAbility(lookupKeyString)
            val isUserDefinedAbility = lookupKeyString in context.abilityNames
            val isEffect = lookupKeyString in context.effectNames

            if (isBuiltInAbility || isUserDefinedAbility || isEffect) {
                val freshType = MezzeType.VarType(TypeVar.fresh())
                return ConstraintResult(
                    type = freshType,
                    constraints = emptyList(),
                    undefinedVariables = listOf(
                        ConstraintResult.UndefinedVariable(
                            name = lookupKeyString,
                            loc = expr.loc,
                            qualifier = expr.qualifier?.let { ModulePath(it.segments) },
                            isQualified = expr.isQualified
                        )
                    )
                )
            }

            if (expr.localName.first().isUpperCase()) {
                val resultType = MezzeType.VarType(TypeVar.fresh())
                val constraints = listOf(
                    Constraint.HasConstructor(
                        variantType = resultType,
                        constructorName = expr.localName,
                        payloadType = null,
                        context = "Variant constructor ${expr.localName}",
                        loc = expr.loc,
                    )
                )
                ConstraintResult(
                    type = resultType,
                    constraints = constraints
                )
            } else {
                val freshType = MezzeType.VarType(TypeVar.fresh())
                ConstraintResult(
                    type = freshType,
                    constraints = emptyList(),
                    undefinedVariables = listOf(
                        ConstraintResult.UndefinedVariable(
                            name = lookupKeyString,
                            loc = expr.loc,
                            qualifier = expr.qualifier?.let { ModulePath(it.segments) },
                            isQualified = expr.isQualified
                        )
                    )
                )
            }
        }
    }
}

internal fun ExpressionConstraintGenerator.generatePipePlaceholder(expr: ParsedPipePlaceholder, context: ConstraintContext): ConstraintResult {
    throw TypeCheckError(
        "'@' can only be used inside a pipe expression (|>).\n\n" +
        "  hint: Use '@' to refer to the piped value:\n" +
        "        value |> func { arg = @ }",
        expr.loc,
        code = ErrorCode.T0402_PIPE_PLACEHOLDER_MISUSE,
        hints = listOf("`@` is only valid in pipe rhs: `x |> f { arg = @ }`"),
    )
}

internal fun ExpressionConstraintGenerator.generatePipeExpression(expr: ParsedPipe, context: ConstraintContext): ConstraintResult {
    throw TypeCheckError(
        "Internal error: PipeExpression was not desugared before type checking. " +
        "This is a compiler bug.",
        expr.loc,
        code = ErrorCode.I0001_UNKNOWN_TYPECHECK_FAILURE,
    )
}

/**
 * Synthesized type for a polyglot member native. Consulted in the `__native_*` fast
 * path (the NotFound branch above), which otherwise types every intrinsic as a bare
 * fresh var. Giving THESE a real signature is what lets `x.attr{}.call{}.…` chains
 * INFER: the FOREIGN-KINDED handle flows through each step, so the next inherent
 * method resolves its dispatch dict WITHOUT any return-type annotation in the
 * provider (`impl PyForeign` / `impl JvmForeign`). Two shapes:
 *
 *  - PRODUCER member natives (read_member / invoke_member / execute / instantiate /
 *    read_array / write_array) yield the SAME foreign handle `'F` BARE — current
 *    core.mz declares them `-> 'f`, the error-carrying-foreign model. (They used to
 *    be typed `-> Result 'F InteropError`, the pre-redesign shape; under the #22
 *    foreign-kind gate that now launders a handle into a `Result`, so it is wrong.)
 *  - COERCION natives (`as_string`/`as_long`/`as_double`/`as_boolean`) CROSS to a
 *    concrete-primitive `Result`, error arm pinned to `InteropError` (not a free
 *    var) so a generic combinator can chain past the coercion — `.as_str{}.get_or{}`
 *    resolves `Result.get_or` on a concrete `Result Str` instead of `Result 'a`.
 *
 * The param record is OPEN (rowVar) so the other, type-irrelevant args (`name`,
 * `args`, `index`, …) unify freely; only `val: 'F` and the return are pinned.
 * `null` for any other native (generic fresh-var path).
 *
 * The fallible COERCION natives (`as_string`/`as_long`/`as_double`/`as_boolean`)
 * return a Result of a CONCRETE primitive, not `'F`. Pinning those (`.as_str{}` →
 * `Result Str InteropError`) is what lets a GENERIC combinator chain PAST a coercion
 * — `x.as_str{}.get_or{def="…"}` — resolve `Result.get_or` on a concrete `Result Str`
 * instead of an unsolved `Result 'a` → runtime DictFallback. (`is_null`/`has_member`
 * return a BARE Bool, not a Result, so they stay on the generic fresh-var path.)
 */
private fun ExpressionConstraintGenerator.polyglotNativeSignature(
    name: String,
    context: ConstraintContext,
): MezzeType.FunctionType? {
    // Foreign in `val` → Result <same foreign> InteropError.
    val foreignReturning = setOf(
        "__native_polyglot_read_member",
        "__native_polyglot_invoke_member",
        "__native_polyglot_execute",
        "__native_polyglot_instantiate",
        "__native_polyglot_read_array",
        "__native_polyglot_write_array",
    )
    // Foreign in `val` → Result <concrete primitive> InteropError. ONLY the
    // fallible coercions belong here — `is_null`/`has_member` return a BARE Bool
    // (not a Result), so they keep the generic fresh-var path.
    val coercionReturn: Map<String, MezzeType> = mapOf(
        "__native_polyglot_as_string" to MezzeType.StringType,
        "__native_polyglot_as_long" to MezzeType.IntType,
        "__native_polyglot_as_double" to MezzeType.FloatType,
        "__native_polyglot_as_boolean" to PrimitiveTypes.BOOL_TYPE,
    )
    val isForeign = name in foreignReturning
    if (!isForeign && name !in coercionReturn) return null

    // #22: the `val` foreign and (for producers) the result are FOREIGN-KINDED — the
    // same opaque-handle kind as the `Polyglot` effect's `having 'f`, so a producer
    // native result is gated against structural types in unification just like the op.
    val f = MezzeType.VarType(TypeVar.foreignKinded())  // the foreign handle 'F (the `val` arg)
    val param = MezzeType.RecordType(
        fields = mapOf("val" to f),
        rowVar = RowVar.fresh(),                         // open: other args unify freely
    )
    // A PRODUCER member native yields the bare error-carrying foreign (current
    // core.mz declares `read_member … -> 'f`, NOT a `Result`); returning `Result 'F`
    // here — the pre-error-carrying-foreign shape — laundered the handle and is what
    // the foreign-kind gate now forbids. A COERCION crosses to a concrete primitive
    // `Result`, pinned so a generic combinator can chain past it (`.as_str{}.get_or{}`).
    if (isForeign) {
        return MezzeType.FunctionType(paramType = param, returnType = f)
    }
    // Concrete InteropError when its decl is in scope (it always is where the
    // polyglot natives are used — the provider `use`s std::polyglot::core), else
    // a fresh var so this never fails a build that references the native oddly.
    val e: MezzeType =
        if (context.typeDeclarations.containsKey("InteropError"))
            com.mezze.language.typechecker.util.WellKnownTypeApp.of("InteropError")
        else MezzeType.VarType(TypeVar.fresh())
    val ret = com.mezze.language.typechecker.util.WellKnownTypeApp.result(coercionReturn.getValue(name), e)
    return MezzeType.FunctionType(paramType = param, returnType = ret)
}
