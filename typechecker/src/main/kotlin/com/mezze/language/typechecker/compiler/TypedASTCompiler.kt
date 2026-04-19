package com.mezze.language.typechecker.compiler

import com.mezze.language.ast.Keywords
import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.MezzeType.*
import com.mezze.language.ast.TypeVar
import com.mezze.language.parsedast.Loc
import com.mezze.language.resolvedast.SlotKind
import com.mezze.language.truffleir.*
import com.mezze.language.typedast.*

/**
 * TypedASTCompiler — single-pass compiler from TypedAST to FunctionIR.
 *
 * Replaces Monomorphizer + Resolver + Lowerer with one pass.
 *
 * Design:
 * - Walks TypedExpr directly, emits FunctionIR/NodeIR
 * - Frame layout: params at 0..P-1, locals at P..P+L-1
 * - Stdlib references are CallDirect(hash) — not traced
 * - Method calls resolved to ability op hashes inline
 * - Lambdas extracted as separate FunctionIRs
 */
class TypedASTCompiler(
    /** All ability impls from all visible modules — for method call resolution */
    private val abilityImpls: List<TypedAbilityImpl>,
    /** Pre-lowered stdlib function hashes — calls to these are CallDirect, not traced */
    private val stdlibHashes: Set<String> = emptySet()
) {

    /** Ability operation index: methodName → list of (implHash, targetTypes, opHash) */
    private data class AbilityOp(
        val implHash: Hash,
        val targetTypes: List<MezzeType>,
        val opHash: Hash
    )
    private val abilityOpIndex = mutableMapOf<String, MutableList<AbilityOp>>()

    /** Extracted lambda functions */
    private val extractedFunctions = mutableListOf<FunctionIR>()

    /** Performer records from TypedPerformerDecls */
    private val performerRecords = mutableListOf<PerformerRecord>()

    /** Current function's param count (for Local→Param remapping) */
    private var currentParamCount = 0

    /** Next local slot index (starts after params) */
    private var nextLocalSlot = 0

    /** Local variable name → slot index (current scope) */
    private val localsByName = mutableMapOf<String, Int>()

    /** Scope stack for nested match bindings: each entry maps name → slot */
    private val scopeStack = ArrayDeque<MutableMap<String, Int>>()

    /** Current function's param names */
    private var currentParamNames = listOf<String>()

    /** Capture name → capture index (for the current lambda being compiled) */
    private var currentCaptureNames = mapOf<String, Int>()

    /** Number of captures in the current function — offsets param slots in the frame */
    private var currentCaptureCount = 0

    /** File table for source info */
    private val files = mutableSetOf<String>()

    init {
        // Build ability operation index
        for (impl in abilityImpls) {
            for ((opName, _) in impl.operations) {
                val opHash = computeOpHash(impl.hash, opName)
                abilityOpIndex.getOrPut(opName) { mutableListOf() }.add(
                    AbilityOp(impl.hash, impl.targetTypes, opHash)
                )
            }
        }
    }

    // ========================================================================
    // Public API
    // ========================================================================

    data class CompileResult(
        val functions: List<FunctionIR>,
        val performerRecords: List<PerformerRecord>
    )

    data class PerformerRecord(
        val performerHash: String,
        val effectHash: String,
        val operationHashes: List<String>,
        val operationNames: List<String>
    )

    /**
     * Compile a TypedModule and its imports to FunctionIR list.
     * @param additionalModules imported modules to also compile (non-prelude)
     * @param additionalPerformers performers from imported modules
     */
    fun compile(
        module: TypedModule,
        additionalModules: List<TypedModule> = emptyList(),
        additionalPerformers: List<TypedPerformerDecl> = emptyList()
    ): CompileResult {
        extractedFunctions.clear()
        performerRecords.clear()
        files.clear()

        val functions = mutableListOf<FunctionIR>()
        val compiledHashes = mutableSetOf<String>()

        // Compile entry module declarations
        for (decl in module.decls) {
            if (decl is TypedLetDecl && decl.hash.hex !in stdlibHashes) {
                functions.add(compileLetDecl(decl))
                compiledHashes.add(decl.hash.hex)
            }
        }

        // Compile imported module declarations (non-prelude, non-already-compiled)
        for (importedModule in additionalModules) {
            for (decl in importedModule.decls) {
                if (decl is TypedLetDecl && decl.hash.hex !in stdlibHashes && decl.hash.hex !in compiledHashes) {
                    functions.add(compileLetDecl(decl))
                    compiledHashes.add(decl.hash.hex)
                }
            }
        }

        // Compile performer declarations from entry module + imports
        val allPerformers = module.performerDecls + additionalPerformers
        for (pd in allPerformers) {
            compilePerformerDecl(pd)
        }

        // Compile ability impl operations as standalone functions
        // These are needed for method calls like list.length, result.map, etc.
        val compiledOpHashes = compiledHashes.toMutableSet()
        for (impl in abilityImpls) {
            for ((opName, opBody) in impl.operations) {
                val opHash = computeOpHash(impl.hash, opName)
                if (opHash.hex in compiledOpHashes || opHash.hex in stdlibHashes) continue
                compiledOpHashes.add(opHash.hex)
                currentCaptureNames = emptyMap() // ability ops aren't closures
                if (opBody is TypedLambda) {
                    functions.add(compileFunctionBody(opHash, opName, opBody.params, opBody.body, opBody.loc))
                } else {
                    functions.add(compileFunctionBody(opHash, opName, emptyList(), opBody, opBody.loc))
                }
            }
        }

        // Add extracted lambdas
        functions.addAll(extractedFunctions)

        // Debug dump
        try { java.io.FileWriter("/tmp/typed_compiler_debug.txt").use { fw ->
            fw.write("Functions: ${functions.size}\n")
            for (fn in functions) {
                fw.write("  ${fn.name} (${fn.qualifiedName}) hash=${fn.hash.hex.take(16)} params=${fn.paramNames} locals=${fn.localNames} captures=${fn.captureNames}\n")
                dumpNodeIR(fw, fn.body, "    ")
            }
            fw.write("PerformerRecords: ${performerRecords.size}\n")
            for (pr in performerRecords) {
                fw.write("  performer=${pr.performerHash.take(16)} effect=${pr.effectHash.take(16)} ops=${pr.operationNames}\n")
            }
        } } catch (_: Exception) {}

        // Debug: dump compiled functions
        try { java.io.FileWriter("/tmp/compiler_debug.txt", true).use { fw ->
            fw.write("=== compile() produced ${functions.size} functions ===\n")
            for (fn in functions) {
                fw.write("  ${fn.name} params=${fn.paramNames} locals=${fn.localNames}\n")
            }
        } } catch (_: Exception) {}

        return CompileResult(functions, performerRecords.toList())
    }

    // ========================================================================
    // Declaration compilation
    // ========================================================================

    private fun compileLetDecl(decl: TypedLetDecl): FunctionIR {
        files.add(decl.loc.file)
        // Top-level functions don't have captures — reset capture context
        currentCaptureNames = emptyMap()

        val value = decl.value
        // Debug: check if map is a lambda
        if (decl.name == "map") {
            try { java.io.FileWriter("/tmp/compiler_map_debug.txt", true).use { fw ->
                fw.write("compileLetDecl: name=${decl.name} valueType=${value::class.simpleName} isLambda=${value is TypedLambda}\n")
                if (value is TypedLambda) fw.write("  lambdaParams=${value.params.map{it.name}}\n")
            } } catch (_: Exception) {}
        }
        return if (value is TypedLambda) {
            // Function: unwrap lambda, use its params directly
            compileFunctionBody(
                hash = decl.hash,
                name = decl.name,
                params = value.params,
                body = value.body,
                loc = decl.loc
            )
        } else if (value is TypedVar && value.binding is BindingRef.ByHash) {
            // Value alias: `toggle_bool = toggle`
            // Compile as a thunk that returns the function reference (not CALLS it).
            // The body should be a ReadContextIR or FunctionLookup, not a CallDirect.
            val targetHash = (value.binding as BindingRef.ByHash).hash
            compileFunctionBody(
                hash = decl.hash,
                name = decl.name,
                params = emptyList(),
                body = value, // compileExpr will emit CallDirect(hash, Unit) for ByHash
                loc = decl.loc
            )
            // Note: this still has the call-with-empty-args issue for function aliases.
            // The proper fix needs ReadContextIR or MakeLambdaIR for the target.
        } else {
            // Value (thunk): no params
            compileFunctionBody(
                hash = decl.hash,
                name = decl.name,
                params = emptyList(),
                body = value,
                loc = decl.loc
            )
        }
    }

    private fun compileFunctionBody(
        hash: Hash,
        name: String,
        params: List<TypedParam>,
        body: TypedExpr,
        loc: Loc,
        captureInfo: List<Pair<String, SlotKind>> = emptyList()
    ): FunctionIR {
        // Save and reset state
        val prevParamCount = currentParamCount
        val prevNextLocal = nextLocalSlot
        val prevLocals = localsByName.toMap()
        val prevParamNames = currentParamNames
        val prevCaptureNames = currentCaptureNames
        val prevCaptureCount = currentCaptureCount

        currentParamCount = params.size
        // Locals start after captures + params:
        // Frame layout: [capture0..captureN, param0..paramM, local0..localK]
        val captureCount = captureInfo.size
        currentCaptureCount = captureCount
        nextLocalSlot = captureCount + params.size
        localsByName.clear()
        currentParamNames = params.map { it.name }
        // Note: currentCaptureNames is set by compileLambda BEFORE calling this.
        // For non-lambda functions (called from compileLetDecl), it should be empty.

        // Compile body
        val bodyIR = compileExpr(body)

        // Collect slot info
        val paramSlots = params.map { typeToSlotKind(it.type) }
        val paramNames = params.map { it.name }
        val localCount = nextLocalSlot - captureCount - params.size
        val localSlots = (0 until localCount).map { SlotKind.OBJECT }
        val localNames = (0 until localCount).map { i ->
            localsByName.entries.find { it.value == captureCount + params.size + i }?.key ?: "local_$i"
        }

        // Restore state
        currentParamCount = prevParamCount
        nextLocalSlot = prevNextLocal
        localsByName.clear()
        localsByName.putAll(prevLocals)
        currentCaptureNames = prevCaptureNames
        currentParamNames = prevParamNames
        currentCaptureCount = prevCaptureCount

        val fnIR = FunctionIR(
            hash = hash,
            name = name,
            qualifiedName = name,
            paramSlots = paramSlots,
            paramNames = paramNames,
            localSlots = localSlots,
            localNames = localNames,
            captureSlots = captureInfo.map { it.second },
            captureNames = captureInfo.map { it.first },
            body = bodyIR,
            sourceFile = loc.file,
            sourceSection = locToSourceInfo(loc)
        )
        // Debug: dump map function with FULL match detail
        if (name == "map" && paramNames.contains("result")) {
            try { java.io.FileWriter("/tmp/compiler_map_debug.txt", true).use { fw ->
                fw.write("FN $name params=$paramNames paramSlots=$paramSlots locals=$localNames localSlots=$localSlots\n")
                dumpNodeIRFull(fw, bodyIR, "  ")
            } } catch (_: Exception) {}
        }
        return fnIR
    }

    private fun compilePerformerDecl(pd: TypedPerformerDecl) {
        val opHashes = mutableListOf<String>()
        val opNames = mutableListOf<String>()
        val isParameterized = pd.params.isNotEmpty()

        if (isParameterized) {
            // For parameterized performers (e.g., SeededRandom { seed: Int }),
            // compile a FACTORY FUNCTION that takes the params record and returns
            // a performer record with operation closures that capture the params.
            //
            // The factory function body is:
            //   { seed } -> { initialize_scope = ..., random_float = ..., random_int = ..., ... }
            //
            // Each operation becomes a MakeLambdaIR (closure) that captures the params.
            val performerParams = pd.params.map { com.mezze.language.typedast.TypedParam(it.name, it.type, it.loc) }

            // Save/reset state for the factory function scope
            val prevParamCount = currentParamCount
            val prevNextLocal = nextLocalSlot
            val prevLocals = localsByName.toMap()
            val prevParamNames = currentParamNames
            val prevCaptureNames = currentCaptureNames
            val prevCaptureCount = currentCaptureCount

            currentParamCount = performerParams.size
            currentCaptureCount = 0
            nextLocalSlot = performerParams.size
            localsByName.clear()
            currentParamNames = performerParams.map { it.name }
            currentCaptureNames = emptyMap()

            // Compile each operation as a MakeLambdaIR (closure capturing params)
            val fieldIRs = mutableListOf<FieldIR>()
            for ((opName, opBody) in pd.operations) {
                val opHash = pd.operationHashes[opName] ?: Hash.ZERO
                opHashes.add(opHash.hex)
                opNames.add(opName)

                // Determine operation params and body
                val opParams: List<com.mezze.language.typedast.TypedParam>
                val opBodyExpr: TypedExpr
                if (opBody is TypedLambda) {
                    opParams = opBody.params
                    opBodyExpr = opBody.body
                } else {
                    opParams = emptyList()
                    opBodyExpr = opBody
                }

                // The capture info: each performer param is a capture from the factory scope
                val captureInfo = performerParams.mapIndexed { idx, p ->
                    p.name to typeToSlotKind(p.type)
                }

                // Set currentCaptureNames so compileVar can resolve captures correctly.
                // compileFunctionBody doesn't set this — it's normally set by compileLambda.
                val prevCaptureNames2 = currentCaptureNames
                currentCaptureNames = captureInfo.mapIndexed { idx, (name, _) -> name to idx }.toMap()

                // Compile the operation function with captures
                val fnIR = compileFunctionBody(
                    hash = opHash,
                    name = opName,
                    params = opParams,
                    body = opBodyExpr,
                    loc = opBody.loc,
                    captureInfo = captureInfo
                )
                extractedFunctions.add(fnIR)

                // Restore capture names
                currentCaptureNames = prevCaptureNames2

                // Create MakeLambdaIR that captures the params from the factory scope
                val captures = performerParams.mapIndexed { idx, p ->
                    // The param is at slot idx in the factory function's frame
                    CaptureIR(idx, typeToSlotKind(p.type))
                }
                val lambdaIR = MakeLambdaIR(opHash, captures, locToSourceInfo(opBody.loc))
                fieldIRs.add(FieldIR(opName, lambdaIR))
            }

            // The factory function body: return a record of operation closures
            val recordIR = MakeRecordIR(fieldIRs, locToSourceInfo(pd.loc))

            // Restore state
            currentParamCount = prevParamCount
            nextLocalSlot = prevNextLocal
            localsByName.clear()
            localsByName.putAll(prevLocals)
            currentCaptureNames = prevCaptureNames
            currentParamNames = prevParamNames
            currentCaptureCount = prevCaptureCount

            // Create the factory FunctionIR
            val factoryFnIR = FunctionIR(
                hash = pd.hash,
                name = pd.name,
                qualifiedName = pd.name,
                paramSlots = performerParams.map { typeToSlotKind(it.type) },
                paramNames = performerParams.map { it.name },
                localSlots = emptyList(),
                localNames = emptyList(),
                captureSlots = emptyList(),
                captureNames = emptyList(),
                body = recordIR,
                sourceFile = pd.loc.file,
                sourceSection = locToSourceInfo(pd.loc)
            )
            extractedFunctions.add(factoryFnIR)

            performerRecords.add(PerformerRecord(
                performerHash = pd.hash.hex,
                effectHash = pd.effectHash.hex,
                operationHashes = opHashes,
                operationNames = opNames
            ))
        } else {
            // Non-parameterized performer: compile operations as standalone functions
            for ((opName, opBody) in pd.operations) {
                val opHash = pd.operationHashes[opName] ?: Hash.ZERO
                opHashes.add(opHash.hex)
                opNames.add(opName)

                // Compile operation as standalone function
                if (opBody is TypedLambda) {
                    extractedFunctions.add(compileFunctionBody(
                        hash = opHash,
                        name = opName,
                        params = opBody.params,
                        body = opBody.body,
                        loc = opBody.loc
                    ))
                } else {
                    extractedFunctions.add(compileFunctionBody(
                        hash = opHash,
                        name = opName,
                        params = emptyList(),
                        body = opBody,
                        loc = opBody.loc
                    ))
                }
            }

            performerRecords.add(PerformerRecord(
                performerHash = pd.hash.hex,
                effectHash = pd.effectHash.hex,
                operationHashes = opHashes,
                operationNames = opNames
            ))
        }
    }

    // ========================================================================
    // Expression compilation
    // ========================================================================

    private fun compileExpr(expr: TypedExpr): NodeIR {
        val src = locToSourceInfo(expr.loc)
        return when (expr) {
            // Literals
            is TypedIntLit -> LongLitIR(expr.value, src)
            is TypedNatLit -> LongLitIR(expr.value, src)
            is TypedFloatLit -> DoubleLitIR(expr.value, src)
            is TypedFracLit -> FracLitIR(expr.numerator, expr.denominator, src)
            is TypedBoolLit -> BoolLitIR(expr.value, src)
            is TypedCharLit -> CharLitIR(expr.value, src)
            is TypedStringLit -> {
                if (expr.parts.size == 1 && expr.parts[0] is TypedStringPart.Literal) {
                    StringLitIR((expr.parts[0] as TypedStringPart.Literal).value, src)
                } else {
                    InterpolatedStringIR(expr.parts.map { p ->
                        when (p) {
                            is TypedStringPart.Literal -> StringPartIR.Lit(p.value)
                            is TypedStringPart.Interpolation -> StringPartIR.Expr(compileExpr(p.expr))
                        }
                    }, src)
                }
            }

            // Variables
            is TypedVar -> compileVar(expr, src)

            // Lambda
            is TypedLambda -> compileLambda(expr, src)

            // Function call
            is TypedCall -> compileCall(expr, src)

            // Record
            is TypedRecord -> MakeRecordIR(
                expr.entries.map { entry ->
                    when (entry) {
                        is TypedRecordEntry.Field -> FieldIR(entry.name, compileExpr(entry.value))
                        is TypedRecordEntry.Spread -> FieldIR("__spread__", compileExpr(entry.expr))
                    }
                }, src
            )

            is TypedFieldAccess -> ReadFieldIR(compileExpr(expr.record), expr.field, expr.fieldIndex, src)

            is TypedRecordUpdate -> RecordUpdateIR(
                compileExpr(expr.record),
                expr.updates.map { entry ->
                    when (entry) {
                        is TypedRecordEntry.Field -> FieldIR(entry.name, compileExpr(entry.value))
                        is TypedRecordEntry.Spread -> FieldIR("__spread__", compileExpr(entry.expr))
                    }
                }, src
            )

            // Variant
            is TypedVariantConstruct -> {
                val tag = expr.tag.hashCode() and 0x7FFFFFFF
                MakeVariantIR(tag, expr.tag, expr.payload?.let { compileExpr(it) }, src)
            }

            // Match
            is TypedMatch -> compileMatch(expr, src)

            // If
            is TypedIf -> IfIR(compileExpr(expr.condition), compileExpr(expr.thenBranch), compileExpr(expr.elseBranch), src)

            // Block
            is TypedBlock -> compileBlock(expr, src)

            // Seq
            is TypedSeq -> MakeSeqIR(
                expr.elements.map { elem ->
                    when (elem) {
                        is TypedSeqElement.Regular -> SeqElementIR.Regular(compileExpr(elem.expr))
                        is TypedSeqElement.Spread -> SeqElementIR.Spread(compileExpr(elem.expr))
                    }
                }, src
            )

            // Effects
            is TypedPerform -> HandleIR(
                compileExpr(expr.body),
                expr.handlers.map { h ->
                    HandlerBindIR(h.performerHash, h.effectHash, h.performerName, h.params?.let { compileExpr(it) })
                }, src
            )
            is TypedPerformOp -> PerformOpIR(expr.effectHash, expr.operationName, compileExpr(expr.arg), src)

            // Pipe (should be desugared, but handle gracefully)
            is TypedPipe -> {
                val rightIR = compileExpr(expr.right)
                val leftIR = compileExpr(expr.left)
                CallIndirectIR(rightIR, leftIR, false, src)
            }
        }
    }

    // ========================================================================
    // Variable compilation
    // ========================================================================

    private fun compileVar(expr: TypedVar, src: SourceInfo): NodeIR {
        return when (val binding = expr.binding) {
            is BindingRef.Local -> {
                // "scope" is a runtime-injected variable from HandleNode (scoped effects)
                if (expr.name == "scope") {
                    return ReadContextIR("scope", src)
                }
                // Check if this is a captured variable.
                // For lambdas: depth > 0 means crossing a lambda boundary.
                // For performer operation functions: depth may be 0 but the variable is
                // listed in currentCaptureNames because it's a performer param captured
                // from the factory function's scope.
                val captureIdx = currentCaptureNames[expr.name]
                if (captureIdx != null && (binding.depth > 0 || currentCaptureCount > 0)) {
                    return ReadCaptureIR(captureIdx, typeToSlotKind(expr.type), src)
                }
                // Use depth to find the right scope
                if (binding.depth == 0 && scopeStack.isNotEmpty()) {
                    // Innermost scope (current match bindings)
                    val scope = scopeStack.last()
                    val slot = scope[expr.name]
                    if (slot != null) {
                        return ReadLocalIR(slot, typeToSlotKind(expr.type), src)
                    }
                }
                if (binding.depth > 0 && scopeStack.size > binding.depth) {
                    // Outer scope — look up by depth from the end
                    val scopeIdx = scopeStack.size - 1 - binding.depth
                    if (scopeIdx >= 0) {
                        val scope = scopeStack[scopeIdx]
                        val slot = scope[expr.name]
                        if (slot != null) {
                            return ReadLocalIR(slot, typeToSlotKind(expr.type), src)
                        }
                    }
                }
                // Check if it's a param — params start after captures in the frame
                val paramIdx = currentParamNames.indexOf(expr.name)
                if (paramIdx >= 0) {
                    ReadLocalIR(currentCaptureCount + paramIdx, typeToSlotKind(expr.type), src)
                } else {
                    // Check any scope level
                    val slot = localsByName[expr.name]
                    if (slot != null) {
                        ReadLocalIR(slot, typeToSlotKind(expr.type), src)
                    } else {
                        val newSlot = allocLocal(expr.name)
                        ReadLocalIR(newSlot, typeToSlotKind(expr.type), src)
                    }
                }
            }
            is BindingRef.Param -> {
                // Check if this Param name is actually a capture (e.g., performer params
                // that are compiled as captures of operation functions)
                val captureIdx = currentCaptureNames[expr.name]
                if (captureIdx != null) {
                    ReadCaptureIR(captureIdx, typeToSlotKind(expr.type), src)
                } else {
                    ReadLocalIR(currentCaptureCount + binding.index, typeToSlotKind(expr.type), src)
                }
            }
            is BindingRef.Capture -> ReadCaptureIR(binding.index, typeToSlotKind(expr.type), src)
            is BindingRef.ByHash -> {
                // Check if it's a local variable or param first
                val slot = localsByName[expr.name]
                if (slot != null) {
                    ReadLocalIR(slot, typeToSlotKind(expr.type), src)
                } else {
                    val paramIdx = currentParamNames.indexOf(expr.name)
                    if (paramIdx >= 0) {
                        ReadLocalIR(currentCaptureCount + paramIdx, typeToSlotKind(expr.type), src)
                    } else {
                        // Check captures (for ByHash refs inside lambdas)
                        val captureIdx = currentCaptureNames[expr.name]
                        if (captureIdx != null) {
                            ReadCaptureIR(captureIdx, typeToSlotKind(expr.type), src)
                        } else {
                            // Read the value/function from context by hash.
                            // Both value thunks (trig::pi) and function references (trig::sin) are
                            // registered in context — MonomorphicCallNode handles calling them.
                            ReadContextIR(binding.hash.hex, src)
                        }
                    }
                }
            }
            is BindingRef.Native -> CallNativeIR(binding.name, UnitLitIR(src), src)
        }
    }

    // ========================================================================
    // Call compilation (with method resolution)
    // ========================================================================

    /** Compile a receiver expression for a method call. Global ByHash references
     *  are auto-called (thunk evaluation) since top-level values like `trig::pi`
     *  are compiled as zero-param FunctionIRs that need to be called to get the value. */
    private fun compileReceiverExpr(expr: TypedExpr): NodeIR {
        if (expr is TypedVar && expr.binding is BindingRef.ByHash) {
            val binding = expr.binding as BindingRef.ByHash
            // Check if it's a local/param/capture first
            val slot = localsByName[expr.name]
            if (slot != null) return ReadLocalIR(slot, typeToSlotKind(expr.type), locToSourceInfo(expr.loc))
            val paramIdx = currentParamNames.indexOf(expr.name)
            if (paramIdx >= 0) return ReadLocalIR(currentCaptureCount + paramIdx, typeToSlotKind(expr.type), locToSourceInfo(expr.loc))
            val captureIdx = currentCaptureNames[expr.name]
            if (captureIdx != null) return ReadCaptureIR(captureIdx, typeToSlotKind(expr.type), locToSourceInfo(expr.loc))
            // Global reference — auto-call thunk to get value
            return CallDirectIR(binding.hash, UnitLitIR(locToSourceInfo(expr.loc)), false, locToSourceInfo(expr.loc))
        }
        return compileExpr(expr)
    }

    private fun compileCall(expr: TypedCall, src: SourceInfo): NodeIR {
        val fn = expr.function
        val argIR = compileExpr(expr.arg)

        // Check for method call: receiver.method { args }
        if (fn is TypedFieldAccess) {
            val methodName = fn.field
            val receiverType = fn.record.type

            // Try inline ops for primitives
            val inlineOp = tryInlineOp(methodName, receiverType)
            if (inlineOp != null) {
                val receiverIR = compileReceiverExpr(fn.record)
                // Extract 'r' or 'it' field from arg record for binary ops
                return compileInlineOp(inlineOp, receiverIR, argIR, src)
            }

            // Try ability method resolution (skip VarType — let runtime dispatch handle it)
            val opHash = if (receiverType !is VarType) tryResolveAbilityOp(methodName, receiverType) else null
            if (opHash != null) {
                val receiverIR = compileReceiverExpr(fn.record)
                // Build arg with receiver injected
                val opBody = findAbilityOpBody(methodName, receiverType)
                val receiverParamName = if (opBody is TypedLambda) {
                    opBody.params.firstOrNull()?.name ?: Keywords.IT
                } else Keywords.IT

                // Inject receiver into argument record
                val fields = mutableListOf<FieldIR>()
                if (expr.arg is TypedRecord) {
                    for (entry in (expr.arg as TypedRecord).entries) {
                        if (entry is TypedRecordEntry.Field) {
                            fields.add(FieldIR(entry.name, compileExpr(entry.value)))
                        }
                    }
                }
                if (fields.none { it.name == receiverParamName }) {
                    fields.add(0, FieldIR(receiverParamName, receiverIR))
                }
                val recordIR = MakeRecordIR(fields, src)
                return CallDirectIR(opHash, recordIR, expr.isTailCall, src)
            }

            // VarType receiver — emit dynamic dispatch.
            // Inject receiver as "it" if not already present, then dispatch at runtime.
            if (receiverType is VarType && methodName !in inlineableOps) {
                val opCandidates = abilityOpIndex[methodName]
                if (opCandidates != null && opCandidates.isNotEmpty()) {
                    val receiverIR = compileReceiverExpr(fn.record)
                    // Build arg with receiver injected as "it"
                    val fields = mutableListOf<FieldIR>()
                    if (expr.arg is TypedRecord) {
                        for (entry in (expr.arg as TypedRecord).entries) {
                            if (entry is TypedRecordEntry.Field) {
                                fields.add(FieldIR(entry.name, compileExpr(entry.value)))
                            }
                        }
                    }
                    if (fields.none { it.name == Keywords.IT }) {
                        fields.add(0, FieldIR(Keywords.IT, receiverIR))
                    }
                    val recordIR = MakeRecordIR(fields, src)
                    val hashes = opCandidates.map { it.opHash.hex }
                    val tags = opCandidates.map { op ->
                        op.targetTypes.firstOrNull()?.let { getTypeTag(it) } ?: "Any"
                    }
                    return DynamicMethodCallIR(methodName, receiverIR, recordIR, hashes, tags, src)
                }
            }
        }

        // Regular call
        val fnIR = compileExpr(fn)
        if (fn is TypedVar) {
            val binding = fn.binding
            return when (binding) {
                is BindingRef.ByHash -> {
                    // Always use CallDirectIR for hash-based bindings.
                    // DO NOT check localsByName — a user variable with the same name
                    // (e.g., "let chars = str::chars {s = s}") would shadow the function
                    // reference and read from an uninitialized slot.
                    CallDirectIR(binding.hash, argIR, expr.isTailCall, src)
                }
                is BindingRef.Native -> CallNativeIR(binding.name, argIR, src)
                is BindingRef.Local -> {
                    // Resolve using the same scope logic as compileVar
                    val resolvedSlot = resolveLocalSlot(fn.name, binding.depth)
                    if (resolvedSlot >= 0) {
                        CallIndirectIR(ReadLocalIR(resolvedSlot, SlotKind.OBJECT, src), argIR, expr.isTailCall, src)
                    } else {
                        CallIndirectIR(fnIR, argIR, expr.isTailCall, src)
                    }
                }
                is BindingRef.Param -> CallIndirectIR(ReadLocalIR(currentCaptureCount + binding.index, SlotKind.OBJECT, src), argIR, expr.isTailCall, src)
                is BindingRef.Capture -> CallIndirectIR(ReadCaptureIR(binding.index, SlotKind.OBJECT, src), argIR, expr.isTailCall, src)
            }
        }
        return CallIndirectIR(fnIR, argIR, expr.isTailCall, src)
    }

    // ========================================================================
    // Lambda compilation
    // ========================================================================

    private fun compileLambda(expr: TypedLambda, src: SourceInfo): NodeIR {
        // Build capture name → index map so compileVar can emit ReadCaptureIR
        val captureNameMap = mutableMapOf<String, Int>()
        for ((i, c) in expr.captures.withIndex()) {
            captureNameMap[c.name] = i
        }
        currentCaptureNames = captureNameMap

        // Extract lambda as a separate FunctionIR
        val lambdaHash = com.mezze.language.typechecker.hashing.TypedASTHasher.hashExpr(expr)
        val lambdaCaptureInfo = expr.captures.map { c -> c.name to typeToSlotKind(c.type) }
        val fnIR = compileFunctionBody(
            hash = lambdaHash,
            name = "lambda",
            params = expr.params,
            body = expr.body,
            loc = expr.loc,
            captureInfo = lambdaCaptureInfo
        )
        extractedFunctions.add(fnIR)

        // Create MakeLambda with captures — always use OBJECT kind.
        // The parent frame may have typed slots (LONG, DOUBLE) but the robust
        // readSlotTyped fallback in FunctionDefinitionNode handles type mismatches.
        // Using OBJECT avoids issues with VarType captures that should be LONG.
        val captures = expr.captures.mapNotNull { c ->
            val slot = localsByName[c.name]
            if (slot != null) {
                CaptureIR(slot, SlotKind.OBJECT)
            } else if (c.name in currentParamNames) {
                // Parent param slot is offset by parent's capture count
                val paramIdx = currentParamNames.indexOf(c.name)
                CaptureIR(currentCaptureCount + paramIdx, SlotKind.OBJECT)
            } else null
        }
        return MakeLambdaIR(lambdaHash, captures, src)
    }

    // ========================================================================
    // Block compilation
    // ========================================================================

    private fun compileBlock(expr: TypedBlock, src: SourceInfo): NodeIR {
        val stmts = expr.statements.map { stmt ->
            when (stmt) {
                is TypedStatement.Let -> {
                    val slot = allocLocal(stmt.name)
                    val stmtValue = stmt.value
                    val valueIR = if (stmtValue is TypedLambda) {
                        // Let-bound lambda: check if it matches a top-level function.
                        // If so, reference the top-level function's hash (not a re-hashed copy).
                        val topLevelDecl = declIndex?.values?.find { it.name == stmt.name }
                        if (topLevelDecl != null && topLevelDecl.value is TypedLambda) {
                            // Top-level function — reference it directly (don't re-compile)
                            val captures = stmtValue.captures.mapNotNull { c ->
                                val s = localsByName[c.name]
                                if (s != null) CaptureIR(s, typeToSlotKind(c.type))
                                else if (c.name in currentParamNames) CaptureIR(currentCaptureCount + currentParamNames.indexOf(c.name), typeToSlotKind(c.type))
                                else null
                            }
                            MakeLambdaIR(topLevelDecl.hash, captures, locToSourceInfo(stmt.loc))
                        } else {
                            compileLambda(stmtValue, locToSourceInfo(stmt.loc))
                        }
                    } else {
                        compileExpr(stmtValue)
                    }
                    StmtIR.Let(slot, typeToSlotKind(stmt.type), stmt.name, stmt.contentHash, valueIR, locToSourceInfo(stmt.loc))
                }
                is TypedStatement.Expr -> StmtIR.Expr(compileExpr(stmt.expr), locToSourceInfo(stmt.loc))
            }
        }
        return BlockIR(stmts, compileExpr(expr.result), src)
    }

    // ========================================================================
    // Match compilation
    // ========================================================================

    private fun compileMatch(expr: TypedMatch, src: SourceInfo): NodeIR {
        val scrutineeIR = compileExpr(expr.scrutinee)
        val cases = expr.cases.map { case ->
            // Push a new scope for this match case's bindings
            val caseScope = mutableMapOf<String, Int>()

            // Allocate slots for pattern bindings (always fresh)
            val bindings = case.bindings.map { b ->
                val slot = forceAllocLocal(b.name)
                caseScope[b.name] = slot
                BindingIR(slot, typeToSlotKind(b.type))
            }

            // Push scope so nested expressions use these bindings at depth 0
            scopeStack.addLast(caseScope)

            // Collect nested destructuring info BEFORE compilePattern,
            // so temp slots for nested records are allocated.
            val nestedDestructures = mutableListOf<NestedDestructureInfo>()
            collectNestedDestructuring(case.patterns.firstOrNull(), nestedDestructures)

            val patternIR = compilePattern(case.patterns.firstOrNull())
            var guardIR = case.guard?.let { compileExpr(it) }

            // Compile if-let bindings.
            // Also collect nested destructuring from if-let patterns.
            val ifLetIRs = case.ifLetBindings.map { ifLet ->
                // Collect nested destructuring from if-let patterns too
                collectNestedDestructuring(ifLet.pattern, nestedDestructures)
                val exprIR = compileExpr(ifLet.expr)
                val ifLetPatternIR = compilePattern(ifLet.pattern)
                // Resolve binding slots from the case scope
                val fieldNames = ifLet.bindingNames
                val fieldSlots = fieldNames.map { name -> caseScope[name] ?: -1 }
                IfLetBindingIR(exprIR, ifLetPatternIR, fieldNames, fieldSlots, locToSourceInfo(ifLet.loc))
            }

            var bodyIR = compileExpr(case.body)

            // Wrap guard AND body with nested destructuring let-bindings if needed.
            // For pattern `Some { val = { x = px, y = py } } if x > 0 -> x`:
            //   tempSlot holds the intermediate record {x=10, y=20}
            //   Guard becomes: do { let px = tempSlot.x; let py = tempSlot.y; <originalGuard> }
            //   Body becomes:  do { let px = tempSlot.x; let py = tempSlot.y; <originalBody> }
            // Both share the same frame, so the guard's writes are visible to the body.
            // We wrap both so the variables are available regardless of evaluation order.
            if (nestedDestructures.isNotEmpty()) {
                val destructureStmts = mutableListOf<StmtIR>()
                for (nd in nestedDestructures) {
                    for ((fieldName, bindingSlot) in nd.fieldBindings) {
                        val readTemp = ReadLocalIR(nd.tempSlot, SlotKind.OBJECT, src)
                        val readField = ReadFieldIR(readTemp, fieldName, -1, src)
                        destructureStmts.add(StmtIR.Let(bindingSlot, SlotKind.OBJECT, "__nested_$fieldName", Hash.ZERO, readField, src))
                    }
                }
                // Wrap guard (if present) with destructuring so guard can reference nested vars
                if (guardIR != null) {
                    guardIR = BlockIR(destructureStmts.toList(), guardIR, src)
                }
                // Wrap body too (in case guard doesn't exist, or for independent body access)
                bodyIR = BlockIR(destructureStmts.toList(), bodyIR, src)
            }

            // Pop scope
            scopeStack.removeLast()

            CaseIR(patternIR, bindings, guardIR, bodyIR, locToSourceInfo(case.loc), ifLetIRs)
        }
        return MatchIR(scrutineeIR, cases, src)
    }

    /**
     * Info about a nested record destructuring that needs post-bind processing.
     * tempSlot: the frame slot holding the intermediate record value
     * fieldBindings: list of (fieldName, targetSlot) pairs to extract from the record
     */
    private data class NestedDestructureInfo(
        val tempSlot: Int,
        val fieldBindings: List<Pair<String, Int>>  // (fieldName, targetSlot)
    )

    /**
     * Walk a typed pattern tree and collect any nested record sub-patterns
     * that can't be handled by a flat PatternIR. Allocates temp slots for
     * intermediate records.
     */
    private fun collectNestedDestructuring(pattern: TypedPattern?, out: MutableList<NestedDestructureInfo>) {
        if (pattern == null) return
        when (pattern) {
            is TypedVariantPattern -> {
                val payload = pattern.payload
                if (payload is TypedRecordPattern) {
                    for (fp in payload.fields) {
                        if (fp.pattern is TypedRecordPattern) {
                            // Nested record: { val = { x = px, y = py } }
                            // Allocate temp slot for the intermediate field value
                            val tempSlot = forceAllocLocal("__tmp_${fp.name}")
                            val nestedRecord = fp.pattern as TypedRecordPattern
                            val fieldBindings = nestedRecord.fields.map { innerFp ->
                                val bindingName = if (innerFp.pattern is TypedVarPattern) (innerFp.pattern as TypedVarPattern).name else innerFp.name
                                val slot = scopeStack.lastOrNull()?.get(bindingName)
                                    ?: localsByName[bindingName]
                                    ?: forceAllocLocal(bindingName)
                                Pair(innerFp.name, slot)
                            }
                            out.add(NestedDestructureInfo(tempSlot, fieldBindings))
                        }
                    }
                }
            }
            is TypedRecordPattern -> {
                for (fp in pattern.fields) {
                    if (fp.pattern is TypedRecordPattern) {
                        // Nested record: { config = { port = p, host = h } }
                        val tempSlot = forceAllocLocal("__tmp_${fp.name}")
                        val nestedRecord = fp.pattern as TypedRecordPattern
                        val fieldBindings = nestedRecord.fields.map { innerFp ->
                            val bindingName = if (innerFp.pattern is TypedVarPattern) (innerFp.pattern as TypedVarPattern).name else innerFp.name
                            val slot = scopeStack.lastOrNull()?.get(bindingName)
                                ?: localsByName[bindingName]
                                ?: forceAllocLocal(bindingName)
                            Pair(innerFp.name, slot)
                        }
                        out.add(NestedDestructureInfo(tempSlot, fieldBindings))
                    }
                }
            }
            else -> {} // Other patterns don't have nested records
        }
    }

    private fun compilePattern(pattern: TypedPattern?): PatternIR {
        if (pattern == null) return PatternIR.Wildcard()
        return when (pattern) {
            is TypedWildcardPattern -> PatternIR.Wildcard()
            is TypedVarPattern -> {
                // Bind the scrutinee to the variable's frame slot
                val slot = scopeStack.lastOrNull()?.get(pattern.name)
                    ?: localsByName[pattern.name]
                    ?: -1
                PatternIR.Wildcard(slot)
            }
            is TypedLiteralPattern -> PatternIR.Literal(compileExpr(pattern.value))
            is TypedVariantPattern -> {
                val tag = pattern.tag.hashCode() and 0x7FFFFFFF
                val fieldNames = mutableListOf<String>()
                val fieldSlots = mutableListOf<Int>()
                val payload = pattern.payload
                if (payload is TypedRecordPattern) {
                    for (fp in payload.fields) {
                        fieldNames.add(fp.name)  // record field name (for extraction at runtime)
                        if (fp.pattern is TypedRecordPattern) {
                            // Nested record pattern: bind to temp slot (allocated by collectNestedDestructuring)
                            val tempSlot = localsByName["__tmp_${fp.name}"] ?: forceAllocLocal("__tmp_${fp.name}")
                            fieldSlots.add(tempSlot)
                        } else {
                            // Simple binding: { val = ys } → "ys", { val } → "val"
                            val bindingName = if (fp.pattern is TypedVarPattern) (fp.pattern as TypedVarPattern).name else fp.name
                            val slot = scopeStack.lastOrNull()?.get(bindingName)
                                ?: localsByName[bindingName]
                                ?: forceAllocLocal(bindingName)
                            fieldSlots.add(slot)
                        }
                    }
                } else if (payload is TypedVarPattern) {
                    fieldNames.add(payload.name)
                    val slot = scopeStack.lastOrNull()?.get(payload.name)
                        ?: localsByName[payload.name]
                        ?: forceAllocLocal(payload.name)
                    fieldSlots.add(slot)
                }
                PatternIR.Variant(tag, pattern.tag, fieldNames, fieldSlots)
            }
            is TypedRecordPattern -> {
                // Field names are for runtime extraction from the record.
                // Slots must map to the BINDING name (renamed variable), not the field name.
                // { value = v } → fieldName="value", bindingName="v"
                val fieldNames = pattern.fields.map { it.name }
                val fieldSlots = pattern.fields.map { fp ->
                    if (fp.pattern is TypedRecordPattern) {
                        // Nested record pattern: bind to temp slot (allocated by collectNestedDestructuring)
                        localsByName["__tmp_${fp.name}"] ?: forceAllocLocal("__tmp_${fp.name}")
                    } else {
                        val bindingName = if (fp.pattern is TypedVarPattern) (fp.pattern as TypedVarPattern).name else fp.name
                        scopeStack.lastOrNull()?.get(bindingName)
                            ?: localsByName[bindingName]
                            ?: forceAllocLocal(bindingName)
                    }
                }
                PatternIR.Record(fieldNames, fieldSlots, pattern.isOpen)
            }
            is TypedSeqPattern -> {
                PatternIR.Seq(
                    pattern.elements.map { localsByName[(it as? TypedVarPattern)?.name ?: ""] ?: -1 },
                    if (pattern.rest != null) localsByName[pattern.rest] ?: -1 else -1
                )
            }
            is TypedStringInterpPattern -> {
                val partTypes = mutableListOf<Int>()
                val literals = mutableListOf<String?>()
                // captureSlots is parallel with partTypes: -1 for literals, actual slot for captures
                val captureSlots = mutableListOf<Int>()
                for (part in pattern.parts) {
                    when (part) {
                        is TypedStringPatternPart.Literal -> {
                            partTypes.add(0) // 0 = literal
                            literals.add(part.value)
                            captureSlots.add(-1) // no slot for literal parts
                        }
                        is TypedStringPatternPart.Capture -> {
                            partTypes.add(1) // 1 = capture
                            literals.add(null)
                            val slot = scopeStack.lastOrNull()?.get(part.variableName)
                                ?: localsByName[part.variableName]
                                ?: forceAllocLocal(part.variableName)
                            captureSlots.add(slot)
                        }
                    }
                }
                PatternIR.StringInterp(partTypes, literals, captureSlots)
            }
        }
    }

    // ========================================================================
    // Inline ops for primitives
    // ========================================================================

    private val inlineableOps = setOf(
        "add", "sub", "mul", "div", "mod",
        "eq", "neq", "lt", "le", "gt", "ge",
        "and", "or", "neg", "not"
    )

    private val primitiveTypes = setOf(
        IntType::class, FloatType::class, NatType::class, BoolType::class,
        StringType::class, CharType::class, FracType::class
    )

    private fun tryInlineOp(methodName: String, receiverType: MezzeType): InlineOp? {
        if (methodName !in inlineableOps) return null
        if (receiverType::class !in primitiveTypes) return null
        return when (methodName) {
            "add" -> InlineOp.ADD
            "sub" -> InlineOp.SUB
            "mul" -> InlineOp.MUL
            "div" -> InlineOp.DIV
            "mod" -> InlineOp.MOD
            "eq" -> InlineOp.EQ
            "neq" -> InlineOp.NEQ
            "lt" -> InlineOp.LT
            "le" -> InlineOp.LE
            "gt" -> InlineOp.GT
            "ge" -> InlineOp.GE
            "and" -> InlineOp.AND
            "or" -> InlineOp.OR
            "neg" -> InlineOp.NEG
            "not" -> InlineOp.NOT
            else -> null
        }
    }

    private fun compileInlineOp(op: InlineOp, receiverIR: NodeIR, argIR: NodeIR, src: SourceInfo): NodeIR {
        // Extract the 'r' value from the argument record
        // arg is typically MakeRecordIR({it=receiver, r=value}) or MakeRecordIR({r=value})
        val rightIR = if (argIR is MakeRecordIR) {
            val rField = argIR.fields.find { it.name == "r" }
            rField?.value ?: argIR
        } else {
            argIR
        }
        return InlineBinaryOpIR(op, receiverIR, rightIR, src)
    }

    // ========================================================================
    // Ability method resolution
    // ========================================================================

    private fun tryResolveAbilityOp(methodName: String, receiverType: MezzeType): Hash? {
        val candidates = abilityOpIndex[methodName] ?: return null
        val match = candidates.firstOrNull { op ->
            op.targetTypes.any { typeMatchesTarget(receiverType, it) }
        }
        return match?.opHash
    }

    private fun findAbilityOpBody(methodName: String, receiverType: MezzeType): TypedExpr? {
        for (impl in abilityImpls) {
            if (impl.operations.containsKey(methodName) &&
                impl.targetTypes.any { typeMatchesTarget(receiverType, it) }) {
                return impl.operations[methodName]
            }
        }
        return null
    }

    private fun typeMatchesTarget(concreteType: MezzeType, targetType: MezzeType): Boolean {
        if (concreteType == targetType) return true
        return when {
            concreteType is IntType && targetType is IntType -> true
            concreteType is FloatType && targetType is FloatType -> true
            concreteType is StringType && targetType is StringType -> true
            concreteType is BoolType && targetType is BoolType -> true
            concreteType is NatType && targetType is NatType -> true
            concreteType is CharType && targetType is CharType -> true
            concreteType is FracType && targetType is FracType -> true
            concreteType is TypeApplication && targetType is TypeApplication ->
                concreteType.constructor == targetType.constructor
            concreteType is VariantType && targetType is TypeApplication &&
                concreteType.constructors.size == 1 &&
                concreteType.constructors.keys.first() == targetType.constructor -> true
            concreteType is VariantType && targetType is TypeApplication -> {
                val ctors = concreteType.constructors.keys.sorted().joinToString(",")
                val knownAliases = mapOf(
                    "None,Some" to "Option", "Err,Ok" to "Result",
                    "Cons,Nil" to "List", "False,True" to "Bool"
                )
                concreteType.typeName == targetType.constructor ||
                    knownAliases[ctors] == targetType.constructor
            }
            concreteType is SeqType && targetType is TypeApplication && targetType.constructor == "Seq" -> true
            concreteType is BytesType && targetType is TypeApplication && targetType.constructor == "Bytes" -> true
            concreteType is ListType && targetType is TypeApplication && targetType.constructor == "List" -> true
            concreteType is SetType && targetType is TypeApplication && targetType.constructor == "Set" -> true
            concreteType is MapType && targetType is TypeApplication && targetType.constructor == "Map" -> true
            concreteType is SortedSetType && targetType is TypeApplication && targetType.constructor == "SortedSet" -> true
            concreteType is SortedMapType && targetType is TypeApplication && targetType.constructor == "SortedMap" -> true
            concreteType is RecordType && targetType is TypeApplication &&
                concreteType.typeName == targetType.constructor -> true
            // VarType: don't match — let runtime handle dispatch
            // targetType is VarType -> true
            // concreteType is VarType -> true
            else -> false
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    /** Resolve a local variable to its frame slot using depth + name */
    private fun resolveLocalSlot(name: String, depth: Int): Int {
        // Check scope stack with depth
        if (depth == 0 && scopeStack.isNotEmpty()) {
            val slot = scopeStack.last()[name]
            if (slot != null) return slot
        }
        if (depth > 0 && scopeStack.size > depth) {
            val scopeIdx = scopeStack.size - 1 - depth
            if (scopeIdx >= 0) {
                val slot = scopeStack[scopeIdx][name]
                if (slot != null) return slot
            }
        }
        // Check params — offset by capture count since params start after captures in frame
        val paramIdx = currentParamNames.indexOf(name)
        if (paramIdx >= 0) return currentCaptureCount + paramIdx
        // Check locals
        return localsByName[name] ?: -1
    }

    private fun allocLocal(name: String): Int {
        localsByName[name]?.let { return it }
        val slot = nextLocalSlot++
        localsByName[name] = slot
        return slot
    }

    /** Force-allocate a new slot, even if name already exists (for match bindings) */
    private fun forceAllocLocal(name: String): Int {
        val slot = nextLocalSlot++
        localsByName[name] = slot  // overwrites existing entry
        return slot
    }

    private fun typeToSlotKind(type: MezzeType): SlotKind = when (type) {
        is IntType -> SlotKind.LONG
        is NatType -> SlotKind.LONG
        is FloatType -> SlotKind.DOUBLE
        is BoolType -> SlotKind.BOOLEAN
        else -> SlotKind.OBJECT
    }

    private fun getTypeTag(type: MezzeType): String = when (type) {
        is IntType -> "Int"
        is FloatType -> "Float"
        is StringType -> "Str"
        is BoolType -> "Bool"
        is NatType -> "Nat"
        is CharType -> "Char"
        is FracType -> "Frac"
        is SeqType -> "Seq"
        is BytesType -> "Bytes"
        is TypeApplication -> type.constructor
        is VariantType -> type.typeName ?: type.constructors.keys.sorted().joinToString(",")
        is RecordType -> type.typeName ?: "Record"
        is VarType -> "Any"
        else -> type::class.simpleName ?: "Unknown"
    }

    companion object {
        @JvmStatic
        fun computeOpHash(implHash: Hash, opName: String): Hash {
            val builder = com.mezze.language.ast.hashing.MezzeHashBuilder()
            builder.append(implHash.hex)
            builder.append(opName)
            return Hash(builder.build().toFullHexString())
        }
    }

    private fun locToSourceInfo(loc: Loc): SourceInfo {
        val fileIndex = files.indexOf(loc.file).let { if (it >= 0) it else { files.add(loc.file); files.size - 1 } }
        return SourceInfo(fileIndex, loc.startLine, loc.startCol, loc.endLine, loc.endCol)
    }

    private fun dumpNodeIR(fw: java.io.FileWriter, node: NodeIR, indent: String) {
        when (node) {
            is CallDirectIR -> { fw.write("${indent}CallDirect(${node.targetHash.hex.take(16)} tail=${node.isTailCall})\n"); dumpNodeIR(fw, node.arg, "$indent  arg: ") }
            is CallIndirectIR -> { fw.write("${indent}CallIndirect\n"); dumpNodeIR(fw, node.function, "$indent  fn: "); dumpNodeIR(fw, node.arg, "$indent  arg: ") }
            is CallNativeIR -> { fw.write("${indent}CallNative(${node.name})\n"); dumpNodeIR(fw, node.arg, "$indent  arg: ") }
            is CallLocalIR -> fw.write("${indent}CallLocal(slot=${node.slot})\n")
            is ReadLocalIR -> fw.write("${indent}ReadLocal(slot=${node.slot})\n")
            is ReadCaptureIR -> fw.write("${indent}ReadCapture(idx=${node.index})\n")
            is WriteLocalIR -> { fw.write("${indent}WriteLocal(slot=${node.slot})\n"); dumpNodeIR(fw, node.value, "$indent  ") }
            is ReadContextIR -> fw.write("${indent}ReadContext(${node.name})\n")
            is LongLitIR -> fw.write("${indent}Long(${node.value})\n")
            is DoubleLitIR -> fw.write("${indent}Double(${node.value})\n")
            is BoolLitIR -> fw.write("${indent}Bool(${node.value})\n")
            is StringLitIR -> fw.write("${indent}String(\"${node.value}\")\n")
            is UnitLitIR -> fw.write("${indent}Unit\n")
            is MakeRecordIR -> { fw.write("${indent}MakeRecord(${node.fields.map { it.name }})\n"); for (f in node.fields) dumpNodeIR(fw, f.value, "$indent  ${f.name}: ") }
            is MakeLambdaIR -> fw.write("${indent}MakeLambda(fnHash=${node.functionHash.hex.take(16)} captures=${node.captures.size})\n")
            is MakeVariantIR -> { fw.write("${indent}MakeVariant(${node.tagName})\n"); node.payload?.let { dumpNodeIR(fw, it, "$indent  payload: ") } }
            is InlineBinaryOpIR -> { fw.write("${indent}InlineOp(${node.op})\n"); dumpNodeIR(fw, node.left, "$indent  L: "); dumpNodeIR(fw, node.right, "$indent  R: ") }
            is PerformOpIR -> { fw.write("${indent}PerformOp(${node.operationName} effect=${node.effectHash.hex.take(16)})\n"); dumpNodeIR(fw, node.arg, "$indent  arg: ") }
            is HandleIR -> { fw.write("${indent}Handle(${node.handlers.map { "${it.performerName}:perf=${it.handlerHash.hex.take(16)},eff=${it.effectHash.hex.take(16)}" }})\n"); dumpNodeIR(fw, node.body, "$indent  body: ") }
            is MatchIR -> { fw.write("${indent}Match\n"); dumpNodeIR(fw, node.scrutinee, "$indent  scrut: ") }
            is IfIR -> { fw.write("${indent}If\n"); dumpNodeIR(fw, node.cond, "$indent  cond: "); dumpNodeIR(fw, node.thenBr, "$indent  then: "); dumpNodeIR(fw, node.elseBr, "$indent  else: ") }
            is BlockIR -> { fw.write("${indent}Block(${node.stmts.size} stmts)\n"); for (s in node.stmts) { when (s) { is StmtIR.Let -> { fw.write("$indent  let slot=${s.slot} ${s.name}\n"); dumpNodeIR(fw, s.value, "$indent    ") }; is StmtIR.Expr -> dumpNodeIR(fw, s.node, "$indent  ") } }; dumpNodeIR(fw, node.result, "$indent  result: ") }
            is InterpolatedStringIR -> fw.write("${indent}InterpolatedString(${node.parts.size} parts)\n")
            is ReadFieldIR -> { fw.write("${indent}ReadField(${node.fieldName})\n"); dumpNodeIR(fw, node.record, "$indent  ") }
            else -> fw.write("${indent}${node::class.simpleName}\n")
        }
    }

    private fun dumpNodeIRFull(fw: java.io.FileWriter, node: NodeIR, indent: String) {
        when (node) {
            is MatchIR -> {
                fw.write("${indent}Match\n")
                dumpNodeIRFull(fw, node.scrutinee, "$indent  scrut: ")
                for ((i, c) in node.cases.withIndex()) {
                    fw.write("$indent  case[$i]: pattern=${c.pattern} bindings=${c.bindings.map { "slot=${it.slot}" }}\n")
                    c.guard?.let { dumpNodeIRFull(fw, it, "$indent    guard: ") }
                    dumpNodeIRFull(fw, c.body, "$indent    body: ")
                }
            }
            is CallDirectIR -> { fw.write("${indent}CallDirect(${node.targetHash.hex.take(16)} tail=${node.isTailCall})\n"); dumpNodeIRFull(fw, node.arg, "$indent  arg: ") }
            is CallIndirectIR -> { fw.write("${indent}CallIndirect\n"); dumpNodeIRFull(fw, node.function, "$indent  fn: "); dumpNodeIRFull(fw, node.arg, "$indent  arg: ") }
            is CallNativeIR -> { fw.write("${indent}CallNative(${node.name})\n"); dumpNodeIRFull(fw, node.arg, "$indent  arg: ") }
            is ReadLocalIR -> fw.write("${indent}ReadLocal(slot=${node.slot} kind=${node.kind})\n")
            is WriteLocalIR -> { fw.write("${indent}WriteLocal(slot=${node.slot})\n"); dumpNodeIRFull(fw, node.value, "$indent  ") }
            is MakeRecordIR -> { fw.write("${indent}MakeRecord(${node.fields.map { it.name }})\n"); for (f in node.fields) dumpNodeIRFull(fw, f.value, "$indent  ${f.name}: ") }
            is MakeVariantIR -> { fw.write("${indent}MakeVariant(${node.tagName})\n"); node.payload?.let { dumpNodeIRFull(fw, it, "$indent  payload: ") } }
            is InlineBinaryOpIR -> { fw.write("${indent}InlineOp(${node.op})\n"); dumpNodeIRFull(fw, node.left, "$indent  L: "); dumpNodeIRFull(fw, node.right, "$indent  R: ") }
            is IfIR -> { fw.write("${indent}If\n"); dumpNodeIRFull(fw, node.cond, "$indent  cond: "); dumpNodeIRFull(fw, node.thenBr, "$indent  then: "); dumpNodeIRFull(fw, node.elseBr, "$indent  else: ") }
            is BlockIR -> { fw.write("${indent}Block\n"); for (s in node.stmts) when (s) { is StmtIR.Let -> { fw.write("$indent  let slot=${s.slot} ${s.name}\n"); dumpNodeIRFull(fw, s.value, "$indent    ") }; is StmtIR.Expr -> dumpNodeIRFull(fw, s.node, "$indent  ") }; dumpNodeIRFull(fw, node.result, "$indent  result: ") }
            is LongLitIR -> fw.write("${indent}Long(${node.value})\n")
            is StringLitIR -> fw.write("${indent}String(\"${node.value}\")\n")
            is UnitLitIR -> fw.write("${indent}Unit\n")
            is MakeLambdaIR -> fw.write("${indent}MakeLambda(fn=${node.functionHash.hex.take(16)} captures=${node.captures.size})\n")
            is PerformOpIR -> { fw.write("${indent}PerformOp(${node.operationName})\n"); dumpNodeIRFull(fw, node.arg, "$indent  arg: ") }
            else -> fw.write("${indent}${node::class.simpleName}\n")
        }
    }

    /** Find a TypedLetDecl by hash — used to determine if a ByHash ref is a function */
    private var declIndex: Map<Hash, TypedLetDecl>? = null
    fun setDeclIndex(decls: Map<Hash, TypedLetDecl>) { declIndex = decls }
    private fun findDecl(hash: Hash): TypedLetDecl? = declIndex?.get(hash)
}
