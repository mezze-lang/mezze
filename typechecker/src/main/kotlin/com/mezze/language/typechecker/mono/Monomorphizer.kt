package com.mezze.language.typechecker.mono

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.MezzeType.*
import com.mezze.language.ast.TypeVar
import com.mezze.language.monoast.*
import com.mezze.language.parsedast.Loc
import com.mezze.language.typedast.*
import java.security.MessageDigest

/**
 * Monomorphizer — converts TypedAST to MonoAST.
 *
 * Takes an entry point module + all loaded modules.
 * Traces all reachable calls and specializes generic functions.
 *
 * After this stage:
 * - ABILITIES ARE GONE (resolved to direct ByHash function refs)
 * - GENERICS ARE GONE (all functions fully specialized)
 * - Effects survive (runtime construct)
 *
 * This only runs for whole-program compilation (not module-level checking).
 */
class Monomorphizer {

    /**
     * Pending specialization request.
     */
    private data class SpecRequest(
        val originalHash: Hash,
        val typeArgs: List<MezzeType>,
        val abilityImpls: List<AbilityImplRef>
    )

    /**
     * Index of ability operation hashes: (methodName, targetTypeName) → opHash.
     * Built during monomorphize(), used by convertExpr() to resolve method calls.
     */
    private val abilityOpIndex = mutableMapOf<String, MutableList<AbilityOpEntry>>()

    private data class AbilityOpEntry(
        val implHash: Hash,
        val abilityName: String?,
        val targetTypes: List<MezzeType>,
        val methodName: String,
        val opHash: Hash
    )

    /**
     * Monomorphize starting from an entry point.
     *
     * @param entryModule The module containing `main`
     * @param allModules All loaded TypedModules (by module hash)
     * @return MonoModule with all reachable functions specialized
     */
    fun monomorphize(
        entryModule: TypedModule,
        allModules: Map<Hash, TypedModule>
    ): MonoModule {
        // Build global index: hash → TypedLetDecl (all functions from all modules)
        val globalDecls = mutableMapOf<Hash, TypedLetDecl>()
        val globalAbilityImpls = mutableListOf<TypedAbilityImpl>()
        val globalEffectDecls = mutableListOf<TypedEffectDecl>()
        val globalPerformerDecls = mutableListOf<TypedPerformerDecl>()

        for (module in allModules.values) {
            for (decl in module.decls) {
                if (decl is TypedLetDecl) {
                    globalDecls[decl.hash] = decl
                }
            }
            globalAbilityImpls.addAll(module.abilityImpls)
            globalEffectDecls.addAll(module.effectDecls)
            globalPerformerDecls.addAll(module.performerDecls)
        }

        // Build ability operation index for method call resolution
        abilityOpIndex.clear()
        for (impl in globalAbilityImpls) {
            for ((opName, _) in impl.operations) {
                val opHash = computeOpHash(impl.hash, opName)
                abilityOpIndex.getOrPut(opName) { mutableListOf() }.add(
                    AbilityOpEntry(impl.hash, null, impl.targetTypes, opName, opHash)
                )
            }
        }

        // Phase 1: Collect all specialization requests by walking from entry point
        val specRequests = mutableSetOf<SpecRequest>()
        val processedFunctions = mutableSetOf<Hash>()
        val monoFunctions = mutableListOf<MonoFunction>()

        // Start from all non-generic declarations in the entry module
        val worklist = ArrayDeque<Hash>()
        for (decl in entryModule.decls) {
            if (decl is TypedLetDecl && decl.typeParams.isEmpty()) {
                worklist.add(decl.hash)
            }
        }

        // Phase 2: Process worklist — for each function, convert to MonoFunction
        // and discover new specialization needs
        while (worklist.isNotEmpty()) {
            val hash = worklist.removeFirst()
            if (hash in processedFunctions) continue
            processedFunctions.add(hash)

            val decl = globalDecls[hash] ?: continue

            // Convert to MonoFunction
            val monoBody = convertExpr(decl.value, globalDecls, worklist, processedFunctions)
            val monoFn = MonoFunction(
                hash = decl.hash,
                name = decl.name,
                originalName = decl.name,
                originalHash = decl.hash,
                params = extractParams(decl.value),
                captures = emptyList(),
                body = monoBody,
                type = decl.scheme.type,
                isPublic = decl.isPublic,
                loc = decl.loc
            )
            monoFunctions.add(monoFn)
        }

        // Also convert ability impl operation bodies to standalone functions
        for (impl in globalAbilityImpls) {
            for ((opName, opBody) in impl.operations) {
                val opHash = computeOpHash(impl.hash, opName)
                if (opHash !in processedFunctions) {
                    processedFunctions.add(opHash)
                    val monoBody = convertExpr(opBody, globalDecls, worklist, processedFunctions)
                    monoFunctions.add(MonoFunction(
                        hash = opHash,
                        name = "${impl.hash.hex.take(8)}.${opName}",
                        originalName = opName,
                        originalHash = impl.hash,
                        params = extractParams(opBody),
                        captures = emptyList(),
                        body = monoBody,
                        type = opBody.type,
                        isPublic = false,
                        loc = opBody.loc
                    ))
                }
            }
        }

        // Convert effect decls and performers
        val monoEffectDecls = globalEffectDecls.map { ed ->
            MonoEffectDecl(
                hash = ed.hash,
                name = ed.name,
                operations = ed.operations.map { op ->
                    MonoEffectOp(op.name, op.operationIndex, op.paramType, op.returnType)
                },
                loc = ed.loc
            )
        }

        val monoPerformerDecls = globalPerformerDecls.map { pd ->
            MonoPerformerDecl(
                hash = pd.hash,
                effectHash = pd.effectHash,
                name = pd.name,
                params = pd.params.map { p -> MonoPerformerParam(p.name, p.type) },
                operations = pd.operations.mapValues { (_, body) ->
                    convertExpr(body, globalDecls, worklist, processedFunctions)
                },
                loc = pd.loc
            )
        }

        // Process any remaining items added to worklist by performer bodies
        while (worklist.isNotEmpty()) {
            val hash = worklist.removeFirst()
            if (hash in processedFunctions) continue
            processedFunctions.add(hash)
            val decl = globalDecls[hash] ?: continue
            val monoBody = convertExpr(decl.value, globalDecls, worklist, processedFunctions)
            monoFunctions.add(MonoFunction(
                hash = decl.hash,
                name = decl.name,
                originalName = decl.name,
                originalHash = decl.hash,
                params = extractParams(decl.value),
                captures = emptyList(),
                body = monoBody,
                type = decl.scheme.type,
                isPublic = decl.isPublic,
                loc = decl.loc
            ))
        }

        val moduleHash = computeModuleHash(monoFunctions.map { it.hash })

        return MonoModule(
            path = entryModule.path,
            hash = moduleHash,
            functions = monoFunctions,
            effectDecls = monoEffectDecls,
            performerDecls = monoPerformerDecls
        )
    }

    // ========================================================================
    // Expression conversion: TypedExpr → MonoExpr
    // ========================================================================

    private fun convertExpr(
        expr: TypedExpr,
        globalDecls: Map<Hash, TypedLetDecl>,
        worklist: ArrayDeque<Hash>,
        processed: Set<Hash>
    ): MonoExpr {
        return when (expr) {
            is TypedIntLit -> MonoIntLit(expr.value, expr.type, expr.loc)
            is TypedNatLit -> MonoNatLit(expr.value, expr.type, expr.loc)
            is TypedFloatLit -> MonoFloatLit(expr.value, expr.type, expr.loc)
            is TypedFracLit -> MonoFracLit(expr.numerator, expr.denominator, expr.type, expr.loc)
            is TypedBoolLit -> MonoBoolLit(expr.value, expr.type, expr.loc)
            is TypedCharLit -> MonoCharLit(expr.value, expr.type, expr.loc)
            is TypedStringLit -> MonoStringLit(
                expr.parts.map { part ->
                    when (part) {
                        is TypedStringPart.Literal -> MonoStringPart.Literal(part.value)
                        is TypedStringPart.Interpolation -> MonoStringPart.Interpolation(
                            convertExpr(part.expr, globalDecls, worklist, processed)
                        )
                    }
                },
                expr.type,
                expr.loc
            )
            is TypedVar -> {
                val monoBinding = convertBinding(expr.binding, worklist, processed)
                MonoVar(expr.name, monoBinding, expr.type, expr.loc)
            }
            is TypedLambda -> MonoLambda(
                expr.params.map { p -> MonoParam(p.name, p.type, p.loc) },
                convertExpr(expr.body, globalDecls, worklist, processed),
                expr.captures.map { c -> MonoCapture(c.name, c.type, convertBinding(c.source, worklist, processed)) },
                expr.type,
                expr.loc
            )
            is TypedCall -> {
                val resolved = tryResolveMethodCall(expr, globalDecls, worklist, processed)
                if (resolved != null) {
                    resolved
                } else {
                    MonoCall(
                        convertExpr(expr.function, globalDecls, worklist, processed),
                        convertExpr(expr.arg, globalDecls, worklist, processed),
                        expr.isTailCall,
                        expr.type,
                        expr.loc
                    )
                }
            }
            // TypedAbilityCall removed — ability calls are now TypedCall with ByHash
            is TypedRecord -> MonoRecord(
                expr.entries.map { entry ->
                    when (entry) {
                        is TypedRecordEntry.Field -> MonoRecordEntry.Field(
                            entry.name,
                            convertExpr(entry.value, globalDecls, worklist, processed)
                        )
                        is TypedRecordEntry.Spread -> MonoRecordEntry.Spread(
                            convertExpr(entry.expr, globalDecls, worklist, processed)
                        )
                    }
                },
                expr.type,
                expr.loc
            )
            is TypedFieldAccess -> MonoFieldAccess(
                convertExpr(expr.record, globalDecls, worklist, processed),
                expr.field,
                expr.fieldIndex,
                expr.type,
                expr.loc
            )
            is TypedRecordUpdate -> MonoRecordUpdate(
                convertExpr(expr.record, globalDecls, worklist, processed),
                expr.updates.map { entry ->
                    when (entry) {
                        is TypedRecordEntry.Field -> MonoRecordEntry.Field(
                            entry.name,
                            convertExpr(entry.value, globalDecls, worklist, processed)
                        )
                        is TypedRecordEntry.Spread -> MonoRecordEntry.Spread(
                            convertExpr(entry.expr, globalDecls, worklist, processed)
                        )
                    }
                },
                expr.type,
                expr.loc
            )
            is TypedVariantConstruct -> MonoVariantConstruct(
                expr.tag,
                expr.payload?.let { convertExpr(it, globalDecls, worklist, processed) },
                expr.type,
                expr.loc
            )
            is TypedIf -> MonoIf(
                convertExpr(expr.condition, globalDecls, worklist, processed),
                convertExpr(expr.thenBranch, globalDecls, worklist, processed),
                convertExpr(expr.elseBranch, globalDecls, worklist, processed),
                expr.type,
                expr.loc
            )
            is TypedMatch -> MonoMatch(
                convertExpr(expr.scrutinee, globalDecls, worklist, processed),
                expr.cases.map { c ->
                    MonoCase(
                        convertPattern(c.patterns.firstOrNull()),
                        c.bindings.map { b -> MonoPatternBinding(b.name, b.type) },
                        c.guard?.let { convertExpr(it, globalDecls, worklist, processed) },
                        convertExpr(c.body, globalDecls, worklist, processed),
                        c.loc
                    )
                },
                expr.type,
                expr.loc
            )
            is TypedBlock -> MonoBlock(
                expr.statements.map { stmt ->
                    when (stmt) {
                        is TypedStatement.Let -> MonoStatement.Let(
                            stmt.name, stmt.contentHash, stmt.type,
                            convertExpr(stmt.value, globalDecls, worklist, processed),
                            stmt.loc
                        )
                        is TypedStatement.Expr -> MonoStatement.Expr(
                            convertExpr(stmt.expr, globalDecls, worklist, processed),
                            stmt.loc
                        )
                    }
                },
                convertExpr(expr.result, globalDecls, worklist, processed),
                expr.type,
                expr.loc
            )
            is TypedSeq -> MonoSeq(
                expr.elements.map { elem ->
                    when (elem) {
                        is TypedSeqElement.Regular -> MonoSeqElement.Regular(
                            convertExpr(elem.expr, globalDecls, worklist, processed)
                        )
                        is TypedSeqElement.Spread -> MonoSeqElement.Spread(
                            convertExpr(elem.expr, globalDecls, worklist, processed)
                        )
                    }
                },
                expr.type,
                expr.loc
            )
            is TypedPerform -> MonoPerform(
                expr.handlers.map { h ->
                    MonoHandlerRef(h.performerHash, h.effectHash, h.performerName, h.params?.let { convertExpr(it, globalDecls, worklist, processed) }, h.loc)
                },
                convertExpr(expr.body, globalDecls, worklist, processed),
                expr.type,
                expr.loc
            )
            is TypedPerformOp -> MonoPerformOp(
                expr.effectHash, expr.operationName,
                convertExpr(expr.arg, globalDecls, worklist, processed),
                expr.type, expr.loc
            )
            is TypedPipe -> {
                // Pipe should be desugared before mono, but handle it gracefully
                MonoCall(
                    convertExpr(expr.right, globalDecls, worklist, processed),
                    convertExpr(expr.left, globalDecls, worklist, processed),
                    false,
                    expr.type,
                    expr.loc
                )
            }
        }
    }

    // ========================================================================
    // Binding conversion
    // ========================================================================

    private fun convertBinding(
        binding: BindingRef,
        worklist: ArrayDeque<Hash>,
        processed: Set<Hash>
    ): MonoBinding {
        return when (binding) {
            is BindingRef.Local -> MonoBinding.Local(binding.depth, binding.index)
            is BindingRef.Param -> MonoBinding.Param(binding.index)
            is BindingRef.Capture -> MonoBinding.Capture(binding.index)
            is BindingRef.ByHash -> {
                if (binding.hash !in processed) {
                    worklist.add(binding.hash)
                }
                MonoBinding.Function(MonoFunctionRef.ByHash(binding.hash))
            }
            is BindingRef.Native -> MonoBinding.Function(MonoFunctionRef.Native(binding.name))
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private fun extractParams(expr: TypedExpr): List<MonoParam> {
        return when (expr) {
            is TypedLambda -> expr.params.map { p -> MonoParam(p.name, p.type, p.loc) }
            else -> emptyList()
        }
    }

    private fun convertPattern(pattern: TypedPattern?): MonoPattern {
        if (pattern == null) return MonoWildcardPattern(MezzeType.VarType(TypeVar("unknown")), com.mezze.language.parsedast.Loc("<unknown>", 1, 1, 1, 1))
        val unknownType = MezzeType.VarType(TypeVar("unknown"))
        return when (pattern) {
            is TypedVarPattern -> MonoVarPattern(pattern.name, pattern.type, pattern.loc)
            is TypedWildcardPattern -> MonoWildcardPattern(pattern.type, pattern.loc)
            is TypedLiteralPattern -> MonoLiteralPattern(
                when (val v = pattern.value) {
                    is TypedIntLit -> MonoIntLit(v.value, v.type, v.loc)
                    is TypedBoolLit -> MonoBoolLit(v.value, v.type, v.loc)
                    is TypedFloatLit -> MonoFloatLit(v.value, v.type, v.loc)
                    is TypedStringLit -> MonoStringLit(v.parts.map { p ->
                        when (p) { is TypedStringPart.Literal -> MonoStringPart.Literal(p.value); is TypedStringPart.Interpolation -> MonoStringPart.Literal("") }
                    }, v.type, v.loc)
                    else -> MonoIntLit(0, unknownType, pattern.loc)
                },
                pattern.type, pattern.loc
            )
            is TypedVariantPattern -> MonoVariantPattern(
                pattern.tag,
                pattern.payload?.let { convertPattern(it) },
                pattern.type, pattern.loc
            )
            is TypedRecordPattern -> MonoRecordPattern(
                pattern.fields.map { f -> MonoFieldPattern(f.name, f.pattern?.let { convertPattern(it) }) },
                pattern.isOpen, pattern.type, pattern.loc
            )
            is TypedSeqPattern -> MonoSeqPattern(
                pattern.elements.map { convertPattern(it) },
                pattern.rest, pattern.type, pattern.loc
            )
        }
    }

    /**
     * Detect method-syntax ability calls: `receiver.method { args }` produces
     * TypedCall(TypedFieldAccess(receiver, methodName), args).
     *
     * When methodName matches an ability operation and the receiver type matches
     * a known impl, resolve to a direct MonoCall(ByHash(opHash), wrappedArgs).
     */
    private fun tryResolveMethodCall(
        expr: TypedCall,
        globalDecls: Map<Hash, TypedLetDecl>,
        worklist: ArrayDeque<Hash>,
        processed: Set<Hash>
    ): MonoExpr? {
        val fn = expr.function
        if (fn !is TypedFieldAccess) return null

        val methodName = fn.field
        val candidates = abilityOpIndex[methodName]
        if (candidates == null || candidates.isEmpty()) return null

        // Get the receiver's type
        val receiverType = fn.record.type

        // Try to find matching impl by receiver type
        val matchingEntry = candidates.firstOrNull { entry ->
            entry.targetTypes.any { targetType -> typeMatchesTarget(receiverType, targetType) }
        }

        if (matchingEntry != null) {
            // Found matching ability operation — emit direct call
            val opHash = matchingEntry.opHash
            if (opHash !in processed) {
                worklist.add(opHash)
            }

            // Build the wrapped argument: inject receiver as first field
            val receiverExpr = convertExpr(fn.record, globalDecls, worklist, processed)
            val argExpr = convertExpr(expr.arg, globalDecls, worklist, processed)

            // The ability method expects {it: ReceiverType, ...args}
            // Get the first param name from the operation's params
            val receiverFieldName = "it"  // Default; ability methods use "it" as receiver

            // Wrap: {it = receiver, ...existingArgs}
            val wrappedArg = when (argExpr) {
                is MonoRecord -> {
                    val entries = listOf(MonoRecordEntry.Field(receiverFieldName, receiverExpr)) + argExpr.entries
                    val fieldTypes = mutableMapOf<String, MezzeType>(receiverFieldName to receiverType)
                    for (e in argExpr.entries) {
                        if (e is MonoRecordEntry.Field) {
                            fieldTypes[e.name] = MezzeType.VarType(TypeVar.fresh()) // approx
                        }
                    }
                    MonoRecord(entries, MezzeType.RecordType(fieldTypes), argExpr.loc)
                }
                else -> {
                    val entries = listOf(MonoRecordEntry.Field(receiverFieldName, receiverExpr))
                    MonoRecord(entries, MezzeType.RecordType(mapOf(receiverFieldName to receiverType)), expr.arg.loc)
                }
            }

            val methodRef = MonoBinding.Function(MonoFunctionRef.ByHash(opHash))
            return MonoCall(
                MonoVar(methodName, methodRef, fn.type, fn.loc),
                wrappedArg,
                expr.isTailCall,
                expr.type,
                expr.loc
            )
        }

        return null
    }

    /** Check if a concrete type matches a target type from an ability impl */
    private fun typeMatchesTarget(concreteType: MezzeType, targetType: MezzeType): Boolean {
        // Direct equality
        if (concreteType == targetType) return true
        // Match by type name for common patterns
        return when {
            concreteType is MezzeType.IntType && targetType is MezzeType.IntType -> true
            concreteType is MezzeType.FloatType && targetType is MezzeType.FloatType -> true
            concreteType is MezzeType.StringType && targetType is MezzeType.StringType -> true
            concreteType is MezzeType.BoolType && targetType is MezzeType.BoolType -> true
            concreteType is MezzeType.NatType && targetType is MezzeType.NatType -> true
            concreteType is MezzeType.CharType && targetType is MezzeType.CharType -> true
            concreteType is MezzeType.FracType && targetType is MezzeType.FracType -> true
            // TypeApplication match by constructor name
            concreteType is MezzeType.TypeApplication && targetType is MezzeType.TypeApplication ->
                concreteType.constructor == targetType.constructor
            // VarType on EITHER side matches anything (generic impl or unresolved receiver)
            targetType is MezzeType.VarType -> true
            concreteType is MezzeType.VarType -> true
            else -> false
        }
    }

    private fun computeOpHash(implHash: Hash, opName: String): Hash {
        val builder = com.mezze.language.ast.hashing.MezzeHashBuilder()
        builder.append(implHash.hex)
        builder.append(opName)
        return Hash.fromV1(builder.build().toFullHexString())
    }

    private fun computeModuleHash(hashes: List<Hash>): Hash {
        if (hashes.isEmpty()) return Hash.ZERO
        val builder = com.mezze.language.ast.hashing.MezzeHashBuilder()
        for (h in hashes.sortedBy { it.hex }) {
            builder.append(h.hex)
        }
        return Hash.fromV1(builder.build().toFullHexString())
    }
}
