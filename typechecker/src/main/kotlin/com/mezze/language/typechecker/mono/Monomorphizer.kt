package com.mezze.language.typechecker.mono

import com.mezze.language.ast.Keywords
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

    /** Index from operation hash → AbilityOpEntry for worklist-based compilation */
    private val abilityOpByHash = mutableMapOf<Hash, AbilityOpEntry>()

    /** Hashes of functions already pre-lowered — don't add to worklist */
    private var skipHashes: Set<String> = emptySet()

    /** When > 0, Local(depth=0) in convertBinding means Param (inside unwrapped lambda body) */
    private var unwrappedLambdaParamCount = 0

    private data class AbilityOpEntry(
        val implHash: Hash,
        val abilityName: String?,
        val targetTypes: List<MezzeType>,
        val methodName: String,
        val opHash: Hash,
        val opBody: TypedExpr  // The operation's expression (typically a lambda)
    )

    /**
     * Monomorphize starting from an entry point.
     *
     * @param entryModule The module containing `main`
     * @param allModules All loaded TypedModules (by module hash)
     * @return MonoModule with all reachable functions specialized
     */
    /**
     * @param preloweredHashes Hashes of functions already pre-lowered (e.g., stdlib).
     *   These are skipped during monomorphization — they exist in the pre-lowered cache.
     */
    fun monomorphize(
        entryModule: TypedModule,
        allModules: Map<Hash, TypedModule>,
        preloweredHashes: Set<String> = emptySet()
    ): MonoModule {
        skipHashes = preloweredHashes
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
        abilityOpByHash.clear()
        for (impl in globalAbilityImpls) {
            for ((opName, opBody) in impl.operations) {
                val opHash = computeOpHash(impl.hash, opName)
                val entry = AbilityOpEntry(impl.hash, null, impl.targetTypes, opName, opHash, opBody)
                abilityOpIndex.getOrPut(opName) { mutableListOf() }.add(entry)
                abilityOpByHash[opHash] = entry
            }
        }

        val processedFunctions = mutableSetOf<Hash>()
        val monoFunctions = mutableListOf<MonoFunction>()

        // Start from all non-generic declarations in the entry module
        val worklist = ArrayDeque<Hash>()
        for (decl in entryModule.decls) {
            if (decl is TypedLetDecl && decl.typeParams.isEmpty()) {
                worklist.add(decl.hash)
            }
        }

        // Process worklist — trace all reachable functions
        while (worklist.isNotEmpty()) {
            val hash = worklist.removeFirst()
            if (hash in processedFunctions) continue
            processedFunctions.add(hash)

            val decl = globalDecls[hash]
            if (decl != null) {
                // Skip functions that are already pre-lowered (stdlib)
                if (hash.hex in preloweredHashes) continue

                // When the decl value is a lambda, use its params directly
                // and convert the lambda's BODY — not the lambda itself.
                // This avoids the duplicate: MonoFunction(params=[x,y], body=MonoLambda(params=[x,y], body=...))
                val (monoParams, monoBody) = if (decl.value is TypedLambda) {
                    val lambda = decl.value as TypedLambda
                    val params = lambda.params.map { p -> MonoParam(p.name, p.type, p.loc) }
                    // Set flag so Local(depth=0) in the body maps to Param
                    val prevParamCount = unwrappedLambdaParamCount
                    unwrappedLambdaParamCount = params.size
                    val body = convertExpr(lambda.body, globalDecls, worklist, processedFunctions)
                    unwrappedLambdaParamCount = prevParamCount
                    params to body
                } else {
                    emptyList<MonoParam>() to convertExpr(decl.value, globalDecls, worklist, processedFunctions)
                }
                val monoFn = MonoFunction(
                    hash = decl.hash,
                    name = decl.name,
                    originalName = decl.name,
                    originalHash = decl.hash,
                    params = monoParams,
                    captures = emptyList(),
                    body = monoBody,
                    type = decl.scheme.type,
                    isPublic = decl.isPublic,
                    loc = decl.loc
                )
                monoFunctions.add(monoFn)
                continue
            }

            // Ability operation referenced by hash — compile as standalone function
            val opEntry = abilityOpByHash[hash]
            if (opEntry != null) {
                if (hash.hex in preloweredHashes) continue
                val opBody = opEntry.opBody
                val (opParams, opMonoBody) = if (opBody is TypedLambda) {
                    val params = opBody.params.map { p -> MonoParam(p.name, p.type, p.loc) }
                    val prevPC = unwrappedLambdaParamCount
                    unwrappedLambdaParamCount = params.size
                    val body = convertExpr(opBody.body, globalDecls, worklist, processedFunctions)
                    unwrappedLambdaParamCount = prevPC
                    params to body
                } else {
                    emptyList<MonoParam>() to convertExpr(opBody, globalDecls, worklist, processedFunctions)
                }
                val monoFn = MonoFunction(
                    hash = opEntry.opHash,
                    name = opEntry.methodName,
                    originalName = opEntry.methodName,
                    originalHash = opEntry.opHash,
                    params = opParams,
                    captures = emptyList(),
                    body = opMonoBody,
                    type = if (opBody is TypedLambda) opBody.type else MezzeType.VarType(TypeVar("unknown")),
                    isPublic = false,
                    loc = opBody.loc
                )
                monoFunctions.add(monoFn)
                continue
            }

            // Unknown hash — performer or unresolved reference
        }

        // Ability impl operations are converted on-demand when referenced
        // from the entry point's reachable functions (via tryResolveMethodCall).
        // Don't eagerly convert ALL operations — that pulls in the entire stdlib.

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

        // Collect performer hashes actually referenced in the processed functions
        val referencedPerformerHashes = mutableSetOf<Hash>()
        fun collectPerformerRefs(expr: MonoExpr) {
            when (expr) {
                is MonoPerform -> {
                    for (h in expr.handlers) referencedPerformerHashes.add(h.performerHash)
                    collectPerformerRefs(expr.body)
                }
                is MonoCall -> { collectPerformerRefs(expr.function); collectPerformerRefs(expr.arg) }
                is MonoLambda -> collectPerformerRefs(expr.body)
                is MonoBlock -> { for (s in expr.statements) { when (s) { is MonoStatement.Let -> collectPerformerRefs(s.value); is MonoStatement.Expr -> collectPerformerRefs(s.expr) } }; collectPerformerRefs(expr.result) }
                is MonoIf -> { collectPerformerRefs(expr.condition); collectPerformerRefs(expr.thenBranch); collectPerformerRefs(expr.elseBranch) }
                is MonoMatch -> { collectPerformerRefs(expr.scrutinee); for (c in expr.cases) { c.guard?.let { collectPerformerRefs(it) }; collectPerformerRefs(c.body) } }
                is MonoRecord -> for (e in expr.entries) { when (e) { is MonoRecordEntry.Field -> collectPerformerRefs(e.value); is MonoRecordEntry.Spread -> collectPerformerRefs(e.expr) } }
                is MonoFieldAccess -> collectPerformerRefs(expr.record)
                is MonoRecordUpdate -> { collectPerformerRefs(expr.record); for (e in expr.updates) { when (e) { is MonoRecordEntry.Field -> collectPerformerRefs(e.value); is MonoRecordEntry.Spread -> collectPerformerRefs(e.expr) } } }
                is MonoSeq -> for (e in expr.elements) { when (e) { is MonoSeqElement.Regular -> collectPerformerRefs(e.expr); is MonoSeqElement.Spread -> collectPerformerRefs(e.expr) } }
                is MonoVariantConstruct -> expr.payload?.let { collectPerformerRefs(it) }
                is MonoPerformOp -> collectPerformerRefs(expr.arg)
                is MonoStringLit -> for (p in expr.parts) { if (p is MonoStringPart.Interpolation) collectPerformerRefs(p.expr) }
                else -> {} // literals, vars — no sub-expressions
            }
        }
        for (fn in monoFunctions) collectPerformerRefs(fn.body)

        // Only process performers that are actually referenced
        val monoPerformerDecls = globalPerformerDecls.filter { it.hash in referencedPerformerHashes }.map { pd ->
            val opBodies = mutableMapOf<String, MonoExpr>()
            val opParams = mutableMapOf<String, List<MonoParam>>()
            for ((opName, body) in pd.operations) {
                if (body is TypedLambda) {
                    val pList = body.params.map { p -> MonoParam(p.name, p.type, p.loc) }
                    opParams[opName] = pList
                    val prevPC = unwrappedLambdaParamCount
                    unwrappedLambdaParamCount = pList.size
                    opBodies[opName] = convertExpr(body.body, globalDecls, worklist, processedFunctions)
                    unwrappedLambdaParamCount = prevPC
                } else {
                    opParams[opName] = emptyList()
                    opBodies[opName] = convertExpr(body, globalDecls, worklist, processedFunctions)
                }
            }
            MonoPerformerDecl(
                hash = pd.hash,
                effectHash = pd.effectHash,
                name = pd.name,
                params = pd.params.map { p -> MonoPerformerParam(p.name, p.type) },
                operations = opBodies,
                operationParams = opParams,
                operationHashes = pd.operationHashes,
                loc = pd.loc
            )
        }

        // Process any remaining items added to worklist by performer bodies
        while (worklist.isNotEmpty()) {
            val hash = worklist.removeFirst()
            if (hash in processedFunctions) continue
            processedFunctions.add(hash)
            val decl = globalDecls[hash] ?: continue
            if (hash.hex in preloweredHashes) continue
            val (monoParams2, monoBody2) = if (decl.value is TypedLambda) {
                val lambda = decl.value as TypedLambda
                val params = lambda.params.map { p -> MonoParam(p.name, p.type, p.loc) }
                val prevParamCount = unwrappedLambdaParamCount
                unwrappedLambdaParamCount = params.size
                val body = convertExpr(lambda.body, globalDecls, worklist, processedFunctions)
                unwrappedLambdaParamCount = prevParamCount
                params to body
            } else {
                emptyList<MonoParam>() to convertExpr(decl.value, globalDecls, worklist, processedFunctions)
            }
            monoFunctions.add(MonoFunction(
                hash = decl.hash,
                name = decl.name,
                originalName = decl.name,
                originalHash = decl.hash,
                params = monoParams2,
                captures = emptyList(),
                body = monoBody2,
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
                val monoBinding = convertBinding(expr.binding, globalDecls, worklist, processed)
                MonoVar(expr.name, monoBinding, expr.type, expr.loc)
            }
            is TypedLambda -> MonoLambda(
                expr.params.map { p -> MonoParam(p.name, p.type, p.loc) },
                convertExpr(expr.body, globalDecls, worklist, processed),
                expr.captures.map { c -> MonoCapture(c.name, c.type, convertBinding(c.source, globalDecls, worklist, processed)) },
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
            is TypedVariantConstruct -> {
                // Optimize Bool variant constructors to native booleans.
                // True/False with no payload are always booleans regardless of type annotation.
                if (expr.tag == "True" && expr.payload == null) {
                    MonoBoolLit(true, MezzeType.BoolType, expr.loc)
                } else if (expr.tag == "False" && expr.payload == null) {
                    MonoBoolLit(false, MezzeType.BoolType, expr.loc)
                } else {
                    MonoVariantConstruct(
                        expr.tag,
                        expr.payload?.let { convertExpr(it, globalDecls, worklist, processed) },
                        expr.type,
                        expr.loc
                    )
                }
            }
            is TypedIf -> MonoIf(
                convertExpr(expr.condition, globalDecls, worklist, processed),
                convertExpr(expr.thenBranch, globalDecls, worklist, processed),
                convertExpr(expr.elseBranch, globalDecls, worklist, processed),
                expr.type,
                expr.loc
            )
            is TypedMatch -> MonoMatch(
                convertExpr(expr.scrutinee, globalDecls, worklist, processed),
                expr.cases.flatMap { c ->
                    val bindings = c.bindings.map { b -> MonoPatternBinding(b.name, b.type) }
                    val guard = c.guard?.let { convertExpr(it, globalDecls, worklist, processed) }
                    val body = convertExpr(c.body, globalDecls, worklist, processed)
                    // Flatten or-patterns: Red | Blue -> body  becomes two MonoCases
                    if (c.patterns.size <= 1) {
                        listOf(MonoCase(convertPattern(c.patterns.firstOrNull()), bindings, guard, body, c.loc))
                    } else {
                        c.patterns.map { p ->
                            MonoCase(convertPattern(p), bindings, guard, body, c.loc)
                        }
                    }
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
            is TypedPerform -> {
                // Add performer hashes to worklist so they get compiled as FunctionIR
                for (h in expr.handlers) {
                    if (h.performerHash.hex.isNotEmpty() && h.performerHash !in processed) {
                        worklist.add(h.performerHash)
                    }
                    // Also try to find performer by name in globalDecls
                    for ((hash, decl) in globalDecls) {
                        if (decl.name == h.performerName && hash !in processed) {
                            worklist.add(hash)
                        }
                    }
                }
                MonoPerform(
                expr.handlers.map { h ->
                    MonoHandlerRef(h.performerHash, h.effectHash, h.performerName, h.params?.let { convertExpr(it, globalDecls, worklist, processed) }, h.loc)
                },
                convertExpr(expr.body, globalDecls, worklist, processed),
                expr.type,
                expr.loc
            )
            }
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
        globalDecls: Map<Hash, TypedLetDecl>,
        worklist: ArrayDeque<Hash>,
        processed: Set<Hash>
    ): MonoBinding {
        return when (binding) {
            is BindingRef.Local -> {
                // Inside an unwrapped lambda body, depth=0 references are lambda params
                if (unwrappedLambdaParamCount > 0 && binding.depth == 0 && binding.index < unwrappedLambdaParamCount) {
                    MonoBinding.Param(binding.index)
                } else {
                    MonoBinding.Local(binding.depth, binding.index)
                }
            }
            is BindingRef.Param -> MonoBinding.Param(binding.index)
            is BindingRef.Capture -> MonoBinding.Capture(binding.index)
            is BindingRef.ByHash -> {
                if (binding.hash !in processed && binding.hash.hex !in skipHashes) {
                    worklist.add(binding.hash)
                }
                // Check if the declaration's value is a lambda — if so, it's a function ref
                // (creates closure); otherwise it's a value ref (read directly)
                val decl = globalDecls[binding.hash]
                if (decl != null && decl.value is TypedLambda) {
                    MonoBinding.FunctionRef(MonoFunctionRef.ByHash(binding.hash))
                } else {
                    MonoBinding.ValueRef(binding.hash)
                }
            }
            is BindingRef.Native -> MonoBinding.FunctionRef(MonoFunctionRef.Native(binding.name))
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

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

        // Skip method resolution for VarType — let TruffleCompiler handle it.
        if (receiverType is MezzeType.VarType) return null

        // For primitive types, only skip methods that have TruffleCompiler inline ops.
        // Methods like to_dbg_str, encode, abs etc. need ability impl resolution.
        // Methods handled by TruffleCompiler inline ops on primitives — skip monomorphizer.
        // Includes built-in ability methods (to_dbg_str from Inspect).
        val inlineOpNames = setOf("add", "sub", "mul", "div", "mod", "eq", "neq",
            "lt", "le", "gt", "ge", "neg", "not", "and", "or",
            "to_dbg_str")
        if (methodName in inlineOpNames &&
            (receiverType is MezzeType.IntType || receiverType is MezzeType.FloatType ||
             receiverType is MezzeType.StringType || receiverType is MezzeType.BoolType ||
             receiverType is MezzeType.NatType || receiverType is MezzeType.CharType ||
             receiverType is MezzeType.FracType)) return null


        // Try to find matching impl by receiver type.
        val matchingEntry = candidates.firstOrNull { entry ->
            entry.targetTypes.any { targetType -> typeMatchesTarget(receiverType, targetType) }
        }

        if (matchingEntry != null) {
            // Emit a direct call to the operation function by its content hash.
            // The operation is compiled as a standalone MonoFunction (via abilityOpByHash).
            // No inlining, no body peeking — just: call(opHash, {receiver + args}).
            val opHash = matchingEntry.opHash
            if (opHash !in processed) worklist.add(opHash)

            val opBody = matchingEntry.opBody
            val receiverParamName = if (opBody is TypedLambda) {
                opBody.params.firstOrNull()?.name ?: Keywords.IT
            } else Keywords.IT

            // Build argument record: inject receiver from field access
            val callerFields = mutableMapOf<String, TypedExpr>()
            if (expr.arg is TypedRecord) {
                for (entry in (expr.arg as TypedRecord).entries) {
                    if (entry is TypedRecordEntry.Field) {
                        callerFields[entry.name] = entry.value
                    }
                }
            }
            if (fn is TypedFieldAccess && receiverParamName !in callerFields) {
                callerFields[receiverParamName] = fn.record
            }

            val monoArg = if (callerFields.isNotEmpty()) {
                MonoRecord(
                    callerFields.map { (name, value) ->
                        MonoRecordEntry.Field(name, convertExpr(value, globalDecls, worklist, processed))
                    },
                    expr.arg.type,
                    expr.arg.loc
                )
            } else {
                convertExpr(expr.arg, globalDecls, worklist, processed)
            }

            return MonoCall(
                MonoVar(methodName, MonoBinding.FunctionRef(MonoFunctionRef.ByHash(opHash)), expr.function.type, expr.function.loc),
                monoArg,
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
            // VariantType with single constructor matching TypeApplication name
            // e.g., Money { cents = 500 } produces VariantType({Money: {cents: Int}})
            // and impl target is TypeApplication("Money", [])
            concreteType is MezzeType.VariantType && targetType is MezzeType.TypeApplication &&
                concreteType.constructors.size == 1 &&
                concreteType.constructors.keys.first() == targetType.constructor -> true
            // VariantType with multiple constructors matching TypeApplication by typeName or known aliases
            concreteType is MezzeType.VariantType && targetType is MezzeType.TypeApplication -> {
                // Match Option=[Some,None], Result=[Ok,Err], List=[Nil,Cons], Bool=[True,False]
                val ctors = concreteType.constructors.keys.sorted().joinToString(",")
                val knownAliases = mapOf(
                    "None,Some" to "Option",
                    "Err,Ok" to "Result",
                    "Cons,Nil" to "List",
                    "False,True" to "Bool"
                )
                concreteType.typeName == targetType.constructor ||
                    knownAliases[ctors] == targetType.constructor
            }
            // SeqType matches TypeApplication("Seq")
            concreteType is MezzeType.SeqType && targetType is MezzeType.TypeApplication &&
                targetType.constructor == "Seq" -> true
            // BytesType matches TypeApplication("Bytes")
            concreteType is MezzeType.BytesType && targetType is MezzeType.TypeApplication &&
                targetType.constructor == "Bytes" -> true
            // RecordType with typeName matching TypeApplication name
            concreteType is MezzeType.RecordType && targetType is MezzeType.TypeApplication &&
                concreteType.typeName == targetType.constructor -> true
            // VarType on EITHER side matches anything (generic impl or unresolved receiver)
            targetType is MezzeType.VarType -> true
            concreteType is MezzeType.VarType -> true
            else -> false
        }
    }

    /** Public access for PrecompiledStdlib to compute ability operation hashes */
    fun computeOpHashPublic(implHash: Hash, opName: String): Hash = computeOpHash(implHash, opName)

    /** Public access for PrecompiledStdlib to convert TypedExpr to MonoExpr */
    fun convertExprPublic(expr: TypedExpr, globalDecls: Map<Hash, TypedLetDecl>, paramCount: Int = 0): MonoExpr {
        val worklist = ArrayDeque<Hash>()
        val processed = mutableSetOf<Hash>()
        val prev = unwrappedLambdaParamCount
        unwrappedLambdaParamCount = paramCount
        val result = convertExpr(expr, globalDecls, worklist, processed)
        unwrappedLambdaParamCount = prev
        return result
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
