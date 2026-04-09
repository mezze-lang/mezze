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

    /** Local variable name → slot index */
    private val localsByName = mutableMapOf<String, Int>()

    /** Current function's param names */
    private var currentParamNames = listOf<String>()

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

        // Add extracted lambdas
        functions.addAll(extractedFunctions)

        return CompileResult(functions, performerRecords.toList())
    }

    // ========================================================================
    // Declaration compilation
    // ========================================================================

    private fun compileLetDecl(decl: TypedLetDecl): FunctionIR {
        files.add(decl.loc.file)

        val value = decl.value
        return if (value is TypedLambda) {
            // Function: unwrap lambda, use its params directly
            compileFunctionBody(
                hash = decl.hash,
                name = decl.name,
                params = value.params,
                body = value.body,
                loc = decl.loc
            )
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
        loc: Loc
    ): FunctionIR {
        // Save and reset state
        val prevParamCount = currentParamCount
        val prevNextLocal = nextLocalSlot
        val prevLocals = localsByName.toMap()
        val prevParamNames = currentParamNames

        currentParamCount = params.size
        nextLocalSlot = params.size  // locals start after params
        localsByName.clear()
        currentParamNames = params.map { it.name }

        // Compile body
        val bodyIR = compileExpr(body)

        // Collect slot info
        val paramSlots = params.map { typeToSlotKind(it.type) }
        val paramNames = params.map { it.name }
        val localCount = nextLocalSlot - params.size
        val localSlots = (0 until localCount).map { SlotKind.OBJECT }
        val localNames = (0 until localCount).map { i ->
            localsByName.entries.find { it.value == params.size + i }?.key ?: "local_$i"
        }

        // Restore state
        currentParamCount = prevParamCount
        nextLocalSlot = prevNextLocal
        localsByName.clear()
        localsByName.putAll(prevLocals)
        currentParamNames = prevParamNames

        return FunctionIR(
            hash = hash,
            name = name,
            qualifiedName = name,
            paramSlots = paramSlots,
            paramNames = paramNames,
            localSlots = localSlots,
            localNames = localNames,
            captureSlots = emptyList(),
            captureNames = emptyList(),
            body = bodyIR,
            sourceFile = loc.file,
            sourceSection = locToSourceInfo(loc)
        )
    }

    private fun compilePerformerDecl(pd: TypedPerformerDecl) {
        val opHashes = mutableListOf<String>()
        val opNames = mutableListOf<String>()

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
                if (binding.depth == 0 && binding.index < currentParamCount) {
                    // Lambda param → ReadLocal at param slot
                    ReadLocalIR(binding.index, typeToSlotKind(expr.type), src)
                } else {
                    // Local variable — check if already allocated
                    val slot = localsByName[expr.name]
                    if (slot != null) {
                        ReadLocalIR(slot, typeToSlotKind(expr.type), src)
                    } else {
                        // Allocate new local
                        val newSlot = allocLocal(expr.name)
                        ReadLocalIR(newSlot, typeToSlotKind(expr.type), src)
                    }
                }
            }
            is BindingRef.Param -> ReadLocalIR(binding.index, typeToSlotKind(expr.type), src)
            is BindingRef.Capture -> ReadCaptureIR(binding.index, typeToSlotKind(expr.type), src)
            is BindingRef.ByHash -> {
                // Function/value reference by hash — depends on context
                // If it's a function type, return a function reference
                // Otherwise, call the thunk to get the value
                val decl = findDecl(binding.hash)
                if (decl != null && decl.value is TypedLambda) {
                    // It's a function — check if it's a local (e.g., let-bound in a block)
                    val slot = localsByName[expr.name]
                    if (slot != null) {
                        ReadLocalIR(slot, SlotKind.OBJECT, src)
                    } else {
                        // Global function reference — emit CallDirect with unit (will be resolved at runtime)
                        CallDirectIR(binding.hash, UnitLitIR(src), false, src)
                    }
                } else {
                    // Value reference
                    val slot = localsByName[expr.name]
                    if (slot != null) {
                        ReadLocalIR(slot, typeToSlotKind(expr.type), src)
                    } else {
                        CallDirectIR(binding.hash, UnitLitIR(src), false, src)
                    }
                }
            }
            is BindingRef.Native -> CallNativeIR(binding.name, UnitLitIR(src), src)
        }
    }

    // ========================================================================
    // Call compilation (with method resolution)
    // ========================================================================

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
                val receiverIR = compileExpr(fn.record)
                // Extract 'r' or 'it' field from arg record for binary ops
                return compileInlineOp(inlineOp, receiverIR, argIR, src)
            }

            // Try ability method resolution
            val opHash = tryResolveAbilityOp(methodName, receiverType)
            if (opHash != null) {
                val receiverIR = compileExpr(fn.record)
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
        }

        // Regular call
        val fnIR = compileExpr(fn)
        if (fn is TypedVar) {
            val binding = fn.binding
            return when (binding) {
                is BindingRef.ByHash -> {
                    val slot = localsByName[fn.name]
                    if (slot != null) {
                        CallIndirectIR(ReadLocalIR(slot, SlotKind.OBJECT, src), argIR, expr.isTailCall, src)
                    } else {
                        CallDirectIR(binding.hash, argIR, expr.isTailCall, src)
                    }
                }
                is BindingRef.Native -> CallNativeIR(binding.name, argIR, src)
                is BindingRef.Local -> {
                    if (binding.depth == 0 && binding.index < currentParamCount) {
                        CallIndirectIR(ReadLocalIR(binding.index, SlotKind.OBJECT, src), argIR, expr.isTailCall, src)
                    } else {
                        val slot = localsByName[fn.name]
                        if (slot != null) {
                            CallIndirectIR(ReadLocalIR(slot, SlotKind.OBJECT, src), argIR, expr.isTailCall, src)
                        } else {
                            CallIndirectIR(fnIR, argIR, expr.isTailCall, src)
                        }
                    }
                }
                is BindingRef.Param -> CallIndirectIR(ReadLocalIR(binding.index, SlotKind.OBJECT, src), argIR, expr.isTailCall, src)
                is BindingRef.Capture -> CallIndirectIR(ReadCaptureIR(binding.index, SlotKind.OBJECT, src), argIR, expr.isTailCall, src)
            }
        }
        return CallIndirectIR(fnIR, argIR, expr.isTailCall, src)
    }

    // ========================================================================
    // Lambda compilation
    // ========================================================================

    private fun compileLambda(expr: TypedLambda, src: SourceInfo): NodeIR {
        // Extract lambda as a separate FunctionIR
        val lambdaHash = com.mezze.language.typechecker.hashing.TypedASTHasher.hashExpr(expr)
        val fnIR = compileFunctionBody(
            hash = lambdaHash,
            name = "lambda",
            params = expr.params,
            body = expr.body,
            loc = expr.loc
        )
        extractedFunctions.add(fnIR)

        // Create MakeLambda with captures
        val captures = expr.captures.mapNotNull { c ->
            val slot = localsByName[c.name]
            if (slot != null) {
                CaptureIR(slot, typeToSlotKind(c.type))
            } else if (c.name in currentParamNames) {
                val paramIdx = currentParamNames.indexOf(c.name)
                CaptureIR(paramIdx, typeToSlotKind(c.type))
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
                        // Let-bound lambda: extract and create MakeLambda
                        compileLambda(stmtValue, locToSourceInfo(stmt.loc))
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
            // Allocate slots for pattern bindings
            val bindings = case.bindings.map { b ->
                val slot = allocLocal(b.name)
                BindingIR(slot, typeToSlotKind(b.type))
            }
            val patternIR = compilePattern(case.patterns.firstOrNull())
            val guardIR = case.guard?.let { compileExpr(it) }
            val bodyIR = compileExpr(case.body)
            CaseIR(patternIR, bindings, guardIR, bodyIR, locToSourceInfo(case.loc))
        }
        return MatchIR(scrutineeIR, cases, src)
    }

    private fun compilePattern(pattern: TypedPattern?): PatternIR {
        if (pattern == null) return PatternIR.Wildcard()
        return when (pattern) {
            is TypedWildcardPattern -> PatternIR.Wildcard()
            is TypedVarPattern -> PatternIR.Wildcard()  // binding handled by case bindings
            is TypedLiteralPattern -> PatternIR.Literal(compileExpr(pattern.value))
            is TypedVariantPattern -> {
                val tag = pattern.tag.hashCode() and 0x7FFFFFFF
                PatternIR.Variant(tag, pattern.tag, emptyList(), emptyList())
            }
            is TypedRecordPattern -> {
                PatternIR.Record(
                    pattern.fields.map { it.name },
                    pattern.fields.map { localsByName[it.name] ?: -1 },
                    pattern.isOpen
                )
            }
            is TypedSeqPattern -> {
                PatternIR.Seq(
                    pattern.elements.map { localsByName[(it as? TypedVarPattern)?.name ?: ""] ?: -1 },
                    if (pattern.rest != null) localsByName[pattern.rest] ?: -1 else -1
                )
            }
        }
    }

    // ========================================================================
    // Inline ops for primitives
    // ========================================================================

    private val inlineableOps = setOf(
        "add", "sub", "mul", "div", "mod",
        "eq", "neq", "lt", "le", "gt", "ge",
        "and", "or"
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
            concreteType is RecordType && targetType is TypeApplication &&
                concreteType.typeName == targetType.constructor -> true
            targetType is VarType -> true
            concreteType is VarType -> true
            else -> false
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    private fun allocLocal(name: String): Int {
        localsByName[name]?.let { return it }
        val slot = nextLocalSlot++
        localsByName[name] = slot
        return slot
    }

    private fun typeToSlotKind(type: MezzeType): SlotKind = when (type) {
        is IntType -> SlotKind.LONG
        is NatType -> SlotKind.LONG
        is FloatType -> SlotKind.DOUBLE
        is BoolType -> SlotKind.BOOLEAN
        else -> SlotKind.OBJECT
    }

    private fun computeOpHash(implHash: Hash, opName: String): Hash {
        val builder = com.mezze.language.ast.hashing.MezzeHashBuilder()
        builder.append(implHash.hex)
        builder.append(opName)
        return Hash(builder.build().toFullHexString())
    }

    private fun locToSourceInfo(loc: Loc): SourceInfo {
        val fileIndex = files.indexOf(loc.file).let { if (it >= 0) it else { files.add(loc.file); files.size - 1 } }
        return SourceInfo(fileIndex, loc.startLine, loc.startCol, loc.endLine, loc.endCol)
    }

    /** Find a TypedLetDecl by hash — used to determine if a ByHash ref is a function */
    private var declIndex: Map<Hash, TypedLetDecl>? = null
    fun setDeclIndex(decls: Map<Hash, TypedLetDecl>) { declIndex = decls }
    private fun findDecl(hash: Hash): TypedLetDecl? = declIndex?.get(hash)
}
