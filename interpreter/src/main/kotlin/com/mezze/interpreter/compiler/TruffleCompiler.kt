package com.mezze.interpreter.compiler

import com.mezze.interpreter.nodes.*
import com.mezze.interpreter.nodes.control.*
import com.mezze.interpreter.nodes.function.*
import com.mezze.interpreter.nodes.literal.*
import com.mezze.interpreter.nodes.record.*
import com.mezze.interpreter.nodes.seq.SeqAccessNode
import com.mezze.interpreter.nodes.seq.SeqLiteralNode
import com.mezze.interpreter.nodes.variable.*
import com.mezze.interpreter.nodes.variant.MatchNode
import com.mezze.interpreter.nodes.variant.MatchBranchNode
import com.mezze.interpreter.nodes.variant.VariantConstructNode
import com.mezze.interpreter.nodes.effect.HandleNode
import com.mezze.interpreter.nodes.effect.DynamicEffectCallNode
import com.mezze.interpreter.converter.InterpolatedStringNode
import com.mezze.interpreter.nodes.builtin.NativeDispatch
import com.mezze.interpreter.runtime.MezzeLanguage
import com.mezze.language.ast.Keywords
import com.mezze.language.truffleir.*
import com.oracle.truffle.api.frame.FrameDescriptor
import com.oracle.truffle.api.frame.FrameSlotKind
import com.oracle.truffle.api.source.Source
import com.oracle.truffle.api.source.SourceSection

/**
 * TruffleCompiler — converts TruffleIR (FunctionIR/NodeIR) to Truffle AST nodes.
 *
 * Uses exact same factory methods as ASTToTruffleConverterV2 to create
 * optimized Truffle DSL nodes.
 */
class TruffleCompiler(
    private val language: MezzeLanguage
) {
    private val sourceCache = mutableMapOf<String, Source>()
    private val functionRegistry = mutableMapOf<com.mezze.language.typedast.Hash, FunctionIR>()

    /**
     * Set of function hashes currently being compiled. Used to detect
     * recursive references in MakeLambdaIR (e.g., a lambda's body that
     * contains a MakeLambdaIR referencing the lambda itself). Without
     * this guard, we'd infinitely recurse during compilation.
     */
    private val compilingFunctions = mutableSetOf<com.mezze.language.typedast.Hash>()

    /** Register all FunctionIRs so MakeLambdaIR can find them */
    fun registerFunctions(functions: List<FunctionIR>) {
        for (fn in functions) {
            functionRegistry[fn.hash] = fn
        }
    }

    /**
     * Compile a FunctionIR to a root node.
     * Uses MezzeFunctionRootNode which handles record argument unpacking —
     * the single record arg is destructured into named parameter slots.
     *
     * If the body is MakeLambdaIR, unwrap it: use the inner function's body
     * and frame descriptor instead. This avoids creating a closure wrapper
     * that would return a MezzeFunction instead of executing the body.
     */
    fun compile(ir: FunctionIR): com.oracle.truffle.api.nodes.RootNode {
        val source = getOrCreateSource(ir.sourceFile)
        val frameDescriptor = buildFrameDescriptor(ir)
        val bodyNode = compileNode(ir.body, source)

        val section = createSourceSection(source, ir.sourceSection)

        if (ir.paramSlots.isNotEmpty() || ir.captureSlots.isNotEmpty()) {
            // Function has parameters and/or captures — use MezzeFunctionRootNode.
            // MezzeFunctionRootNode handles:
            //   1. Binding capture values from args[0..captureCount-1] to frame slots
            //   2. Unpacking the record arg from args[captureCount] to named param slots
            // Frame layout: [capture0..captureN, param0..paramM, local0..localK]
            //
            // closureSlots here are just placeholder indices [0..captureCount-1] since
            // MezzeFunctionRootNode only uses closureSlots.length for closureCount.
            // The actual capture slot values are used by FunctionDefinitionNode to read
            // from the parent frame — not needed here.
            val captureCount = ir.captureSlots.size
            val closureSlotIndices = IntArray(captureCount) { it }
            // Build slot types for frame binding: [captures..., params...]
            // This tells MezzeFunctionRootNode.bindSlot how to write each value (Long/Double/Bool/Object)
            val slotTypes = IntArray(captureCount + ir.paramSlots.size)
            for (i in ir.captureSlots.indices) {
                slotTypes[i] = slotKindToInt(ir.captureSlots[i])
            }
            for (i in ir.paramSlots.indices) {
                slotTypes[captureCount + i] = slotKindToInt(ir.paramSlots[i])
            }
            val rootNode = MezzeFunctionRootNode(
                language,
                frameDescriptor,
                bodyNode,
                ir.name,
                ir.paramNames.toTypedArray(),
                closureSlotIndices,
                slotTypes
            )
            if (section != null) rootNode.setSourceSection(section)
            return rootNode
        } else {
            // No parameters and no captures — use MezzeRootNode (entry functions, thunks)
            val rootNode = MezzeRootNode(
                language,
                frameDescriptor,
                bodyNode,
                ir.name,
                0,
                0,
                IntArray(0)
            )
            if (section != null) rootNode.setSourceSection(section)
            return rootNode
        }
    }

    /**
     * Compile a NodeIR to a MezzeNode and attach its source section.
     *
     * Every NodeIR carries `src: SourceInfo` (file, line, col span). We
     * convert that to a Truffle [SourceSection] and stamp it on the
     * resulting MezzeNode so Chrome Inspector breakpoints, the CPU
     * sampler, coverage, and JFR can all attribute work to the right
     * source line. Without this, every Truffle tool sees Mezze code as
     * one undifferentiated blob.
     */
    fun compileNode(node: NodeIR, source: Source): MezzeNode {
        val mezzeNode = compileNodeImpl(node, source)
        val section = createSourceSection(source, node.src)
        if (section != null) mezzeNode.setSourceSection(section)
        return mezzeNode
    }

    private fun compileNodeImpl(node: NodeIR, source: Source): MezzeNode {
        return when (node) {
            // === Literals ===
            is LongLitIR -> IntLiteralNode(node.value)
            is DoubleLitIR -> FloatLiteralNode(node.value)
            is BoolLitIR -> BoolLiteralNode(node.value)
            is StringLitIR -> StringLiteralNode(node.value)
            is UnitLitIR -> UnitLiteralNode()
            is CharLitIR -> IntLiteralNode(node.value.code.toLong())
            is FracLitIR -> FracLiteralNode(node.numerator, node.denominator)
            is InterpolatedStringIR -> {
                val partNodes = node.parts.map { part ->
                    when (part) {
                        is StringPartIR.Lit -> StringLiteralNode(part.value) as MezzeNode
                        is StringPartIR.Expr -> compileNode(part.node, source)
                    }
                }.toTypedArray()
                InterpolatedStringNode(partNodes)
            }

            // === Variables ===
            is ReadLocalIR -> ReadLocalNode(node.slot, slotKindToInt(node.kind))
            is ReadGlobalByHashIR -> {
                // Read a global definition by content hash. Auto-evaluates value thunks.
                com.mezze.interpreter.nodes.ReadGlobalByHashNode(node.hash)
            }
            is ReadGlobalFnByHashIR -> {
                // Read a function from the global registry by content hash WITHOUT auto-evaluating
                // thunks. Used for dictionary passing to inject ability op functions as hidden params.
                com.mezze.interpreter.nodes.ReadGlobalFnByHashNode(node.hash)
            }
            is ReadScopeIR -> {
                // Read a runtime-injected scope variable (e.g., `scope` from HandleNode) by name.
                com.mezze.interpreter.nodes.ReadScopeNode(node.varName)
            }
            is WriteLocalIR -> {
                val valueNode = compileNode(node.value, source)
                WriteLocalNode.create(valueNode, node.slot, slotKindToInt(node.kind))
            }
            is ReadCaptureIR -> ReadLocalNode(node.index, slotKindToInt(node.kind))
            is ReadArgIR -> ReadArgumentNode(node.index)

            // === Calls ===
            is CallDirectIR -> {
                val argNodes = node.args.map { compileNode(it, source) }
                if (!node.isScalarABI) {
                    // Record ABI: a single MezzeRecord arg. Existing fast path,
                    // including partial-application detection at the receiver.
                    require(argNodes.size == 1) {
                        "Record-ABI CallDirectIR must have exactly 1 arg (the record); got ${argNodes.size}"
                    }
                    val argNode = argNodes[0]
                    if (node.isTailCall) {
                        val fnNode = FunctionLookupNode(node.targetHash)
                        TailCallNode.create(fnNode, arrayOf(argNode))
                    } else {
                        MonomorphicCallNode(node.targetHash, node.targetHash.toString(), argNode)
                    }
                } else {
                    // Scalar ABI: positional values in callee param order, no record allocation.
                    val argsArray = argNodes.toTypedArray()
                    if (node.isTailCall) {
                        com.mezze.interpreter.nodes.function.TailCallDirectScalarNode(
                            node.targetHash, node.targetHash.toString(), argsArray
                        )
                    } else {
                        com.mezze.interpreter.nodes.function.CallDirectScalarNode(
                            node.targetHash, node.targetHash.toString(), argsArray
                        )
                    }
                }
            }
            is com.mezze.language.truffleir.PartialApplyDirectIR -> {
                // Same runtime path as CallDirectIR with an incomplete record arg —
                // InvokeNode/MonomorphicCallNode detect partial application and
                // return a closure capturing the partial fields. We materialize the
                // partial fields into a MakeRecordIR and reuse the call machinery.
                val recordIR = com.mezze.language.truffleir.MakeRecordIR(node.partialFields, node.src, node.typeTag)
                val argNode = compileNode(recordIR, source)
                if (node.isTailCall) {
                    val fnNode = FunctionLookupNode(node.targetHash)
                    TailCallNode.create(fnNode, arrayOf(argNode))
                } else {
                    MonomorphicCallNode(node.targetHash, node.targetHash.toString(), argNode)
                }
            }
            is CallLocalIR -> {
                val fnNode = ReadLocalNode(node.slot, ReadLocalNode.KIND_OBJECT)
                val argNode = compileNode(node.arg, source)
                if (node.isTailCall) {
                    TailCallNode.create(fnNode, arrayOf(argNode))
                } else {
                    InvokeNode.create(fnNode, arrayOf(argNode))
                }
            }
            is CallNativeIR -> {
                val argNode = compileNode(node.arg, source)
                // Use dedicated Truffle nodes for continuation primitives
                // (matching main branch's convertNativeFunctionCall)
                when (node.name) {
                    "__native_continuation_delimit" ->
                        com.mezze.interpreter.nodes.continuation.ContinuationDelimitNode.create()
                    "__native_continuation_activate" ->
                        com.mezze.interpreter.nodes.continuation.ContinuationActivateNode.create(argNode)
                    "__native_continuation_capture" ->
                        com.mezze.interpreter.nodes.continuation.ContinuationCaptureNode.create(argNode)
                    "__native_continuation_suspend" ->
                        com.mezze.interpreter.nodes.continuation.ContinuationSuspendNode.create()
                    "__native_continuation_suspend_with" ->
                        com.mezze.interpreter.nodes.continuation.ContinuationSuspendWithNode(argNode)
                    "__native_continuation_resume" ->
                        com.mezze.interpreter.nodes.continuation.ContinuationResumeNode.create(argNode)
                    "__native_continuation_abort" ->
                        com.mezze.interpreter.nodes.continuation.ContinuationAbortNode.create(argNode)
                    "__native_continuation_extract_suspended" ->
                        com.mezze.interpreter.nodes.continuation.ContinuationExtractSuspendedNode.create(argNode)
                    "__native_continuation_deactivate" ->
                        com.mezze.interpreter.nodes.continuation.ContinuationDeactivateNode.create(argNode)
                    "__native_continuation_run_suspendable" ->
                        com.mezze.interpreter.nodes.continuation.ContinuationRunSuspendableNode.create(argNode)
                    "__native_continuation_suspend_and_resume" ->
                        com.mezze.interpreter.nodes.continuation.ContinuationSuspendAndResumeNode.create(argNode)
                    "__native_continuation_get_collected" ->
                        com.mezze.interpreter.nodes.continuation.ContinuationGetCollectedNode.create(argNode)
                    "__native_continuation_take_collected" ->
                        com.mezze.interpreter.nodes.continuation.ContinuationTakeCollectedNode.create(argNode)
                    "__native_continuation_collected_count" ->
                        com.mezze.interpreter.nodes.continuation.ContinuationCollectedCountNode.create(argNode)
                    "__native_continuation_snapshot" ->
                        com.mezze.interpreter.nodes.continuation.ContinuationSnapshotNode.create(argNode)
                    "__native_continuation_restore" ->
                        com.mezze.interpreter.nodes.continuation.ContinuationRestoreNode.create(argNode)
                    "__native_continuation_is_serializable" ->
                        com.mezze.interpreter.nodes.continuation.ContinuationIsSerializableNode.create(argNode)
                    else -> NativeDispatch.create(node.name, argNode)
                }
            }
            is CallIndirectIR -> {
                // Check for inline operator pattern: CallIndirect(ReadField(receiver, "add"), {r=...})
                val inlineNode = tryCompileInlineOp(node, source)
                if (inlineNode != null) {
                    inlineNode
                } else {
                    // General indirect call
                    val fnNode = compileNode(node.function, source)
                    val argNode = compileNode(node.arg, source)
                    InvokeNode.create(fnNode, arrayOf(argNode))
                }
            }
            is com.mezze.language.truffleir.CallIndirectScalarIR -> {
                // N-arg indirect call (HOF callback fast path). InvokeScalarNNode
                // inline-caches the function value, computes a caller→callee
                // permutation when N >= 2 (param order may differ from caller's
                // field order), and uses typed-LONG/DOUBLE/MIXED payload when
                // the cached callee has primitive params.
                require(node.args.size == node.argFieldNames.size && node.args.isNotEmpty()) {
                    "CallIndirectScalarIR args/argFieldNames must be same non-empty size"
                }
                val fnNode = compileNode(node.function, source)
                val argNodes = node.args.map { compileNode(it, source) }.toTypedArray()
                com.mezze.interpreter.nodes.function.InvokeScalarNNode(
                    fnNode, argNodes, node.argFieldNames.toTypedArray()
                )
            }
            is InlineBinaryOpIR -> {
                val leftNode = compileNode(node.left, source)
                val rightNode = compileNode(node.right, source)
                compileInlineOp(node.op, leftNode, rightNode)
            }

            is com.mezze.language.truffleir.SpecializedCallIR -> {
                val argNodes = node.args.map { compileNode(it, source) }.toTypedArray()
                compileSpecializedOp(node.op, argNodes)
            }

            // === Records ===
            is MakeRecordIR -> {
                val hasSpreads = node.fields.any { it.name == "__spread__" }
                if (hasSpreads) {
                    // Record has spread entries — use RecordSpreadNode
                    val spreadNodes = node.fields
                        .filter { it.name == "__spread__" }
                        .map { compileNode(it.value, source) }
                        .toTypedArray()
                    val explicitFields = node.fields.filter { it.name != "__spread__" }
                    val fieldNames = explicitFields.map { it.name }.toTypedArray()
                    val fieldNodes = explicitFields.map { compileNode(it.value, source) }.toTypedArray()
                    RecordSpreadNode.create(spreadNodes, fieldNames, fieldNodes)
                } else {
                    val fieldNames = node.fields.map { it.name }.toTypedArray()
                    val fieldNodes = node.fields.map { compileNode(it.value, source) }.toTypedArray()
                    RecordLiteralNode.allObjects(fieldNames, fieldNodes)
                }
            }
            is ReadFieldIR -> {
                val recordNode = compileNode(node.record, source)
                RecordFieldAccessNode.createByName(recordNode, node.fieldName)
            }
            is RecordUpdateIR -> {
                // Record update: spread the original + override fields
                val recordNode = compileNode(node.record, source)
                val fieldNames = node.updates.map { it.name }.toTypedArray()
                val fieldNodes = node.updates.map { compileNode(it.value, source) }.toTypedArray()
                RecordSpreadNode.create(arrayOf(recordNode), fieldNames, fieldNodes)
            }

            // === Variants ===
            is MakeVariantIR -> {
                val payloadNode = node.payload?.let { compileNode(it, source) }
                VariantConstructNode.create(node.tag, node.tagName, payloadNode)
            }
            is MatchIR -> {
                val scrutineeNode = compileNode(node.scrutinee, source)
                var wildcardIndex = -1

                val branches = node.cases.mapIndexed { idx, case ->
                    val bodyNode = compileNode(case.body, source)
                    val guardNode = case.guard?.let { compileNode(it, source) }

                    // Compile if-let bindings into IfLetBindingNode instances
                    val ifLetNodes = case.ifLetBindings.map { ilb ->
                        val exprNode = compileNode(ilb.expr, source)
                        when (val ilPat = ilb.pattern) {
                            is PatternIR.Variant -> {
                                com.mezze.interpreter.nodes.control.IfLetBindingNode.variantPattern(
                                    exprNode, ilPat.tag,
                                    ilPat.fieldNames.toTypedArray(),
                                    ilPat.fieldSlots.toIntArray()
                                )
                            }
                            is PatternIR.Record -> {
                                com.mezze.interpreter.nodes.control.IfLetBindingNode.recordPattern(
                                    exprNode,
                                    ilPat.fieldNames.toTypedArray(),
                                    ilPat.fieldSlots.toIntArray()
                                )
                            }
                            else -> {
                                // Wildcard/literal patterns: always match, bind to first slot if available
                                val slot = if (ilb.fieldSlots.isNotEmpty()) ilb.fieldSlots[0] else -1
                                com.mezze.interpreter.nodes.control.IfLetBindingNode.wildcardPattern(exprNode, slot)
                            }
                        }
                    }.toTypedArray()
                    val hasGuardOrIfLet = guardNode != null || ifLetNodes.isNotEmpty()

                    when (val pat = case.pattern) {
                        is PatternIR.Wildcard -> {
                            wildcardIndex = idx
                            if (hasGuardOrIfLet) {
                                MatchBranchNode.wildcardWithGuard(
                                    if (pat.slot >= 0) pat.slot else -1, bodyNode, guardNode, ifLetNodes)
                            } else if (pat.slot >= 0) {
                                MatchBranchNode.wildcard(pat.slot, bodyNode)
                            } else {
                                MatchBranchNode.wildcardNoBinding(bodyNode)
                            }
                        }
                        is PatternIR.Variant -> {
                            if (hasGuardOrIfLet) {
                                MatchBranchNode.variantPatternWithGuard(
                                    pat.tag, pat.fieldNames.toTypedArray(),
                                    pat.fieldSlots.toIntArray(), bodyNode, guardNode, ifLetNodes)
                            } else {
                                MatchBranchNode.variantPatternWithFieldNames(
                                    pat.tag, pat.fieldNames.toTypedArray(),
                                    pat.fieldSlots.toIntArray(), bodyNode)
                            }
                        }
                        is PatternIR.Literal -> {
                            val v = pat.value
                            val litValue: Any = when (v) {
                                is LongLitIR -> v.value
                                is DoubleLitIR -> v.value
                                is BoolLitIR -> v.value
                                is StringLitIR -> v.value
                                else -> 0L
                            }
                            if (hasGuardOrIfLet) {
                                MatchBranchNode.literalPatternWithGuard(litValue, bodyNode, guardNode, ifLetNodes)
                            } else {
                                MatchBranchNode.literalPattern(litValue, bodyNode)
                            }
                        }
                        is PatternIR.Seq -> {
                            MatchBranchNode.seqPattern(
                                pat.elementSlots.toIntArray(),
                                pat.restSlot,
                                pat.restSlot >= 0,
                                bodyNode
                            )
                        }
                        is PatternIR.Record -> {
                            if (hasGuardOrIfLet) {
                                MatchBranchNode.recordPatternWithGuard(
                                    pat.fieldNames.toTypedArray(),
                                    pat.fieldSlots.toIntArray(),
                                    pat.isOpen,
                                    bodyNode, guardNode, ifLetNodes)
                            } else {
                                MatchBranchNode.recordPattern(
                                    pat.fieldNames.toTypedArray(),
                                    pat.fieldSlots.toIntArray(),
                                    pat.isOpen,
                                    bodyNode)
                            }
                        }
                        is PatternIR.StringInterp -> {
                            if (hasGuardOrIfLet) {
                                MatchBranchNode.stringPatternWithGuard(
                                    pat.partTypes.toIntArray(),
                                    pat.literals.toTypedArray(),
                                    pat.captureSlots.toIntArray(),
                                    bodyNode, guardNode, ifLetNodes)
                            } else {
                                MatchBranchNode.stringPattern(
                                    pat.partTypes.toIntArray(),
                                    pat.literals.toTypedArray(),
                                    pat.captureSlots.toIntArray(),
                                    bodyNode)
                            }
                        }
                    }
                }.toTypedArray()
                MatchNode(scrutineeNode, branches, wildcardIndex)
            }

            // === Control flow ===
            is IfIR -> {
                val condNode = compileNode(node.cond, source)
                val thenNode = compileNode(node.thenBr, source)
                val elseNode = compileNode(node.elseBr, source)
                IfNode.create(condNode, thenNode, elseNode)
            }
            is BlockIR -> {
                val allNodes = mutableListOf<MezzeNode>()
                for (stmt in node.stmts) {
                    when (stmt) {
                        is StmtIR.Let -> {
                            val valueNode = compileNode(stmt.value, source)
                            // WriteLocalWithContextNode registers in both frame AND global context
                            // This ensures local functions are findable by MonomorphicCallNode via hash
                            allNodes.add(WriteLocalWithContextNode.create(
                                valueNode, stmt.slot, slotKindToInt(stmt.kind),
                                stmt.name, stmt.contentHash
                            ))
                        }
                        is StmtIR.Expr -> allNodes.add(compileNode(stmt.node, source))
                    }
                }
                allNodes.add(compileNode(node.result, source))
                BlockNode(allNodes.toTypedArray())
            }

            // === Sequences ===
            is MakeSeqIR -> {
                val hasSpreads = node.elements.any { it is SeqElementIR.Spread }
                if (hasSpreads) {
                    // Use spread-aware node when there are ...spread elements
                    val elementNodes = node.elements.map { elem ->
                        when (elem) {
                            is SeqElementIR.Regular -> compileNode(elem.node, source)
                            is SeqElementIR.Spread -> compileNode(elem.node, source)
                        }
                    }.toTypedArray()
                    val isSpread = node.elements.map { it is SeqElementIR.Spread }.toBooleanArray()
                    com.mezze.interpreter.nodes.seq.SeqSpreadLiteralNode.create(elementNodes, isSpread)
                } else {
                    val elementNodes = node.elements.map { elem ->
                        when (elem) {
                            is SeqElementIR.Regular -> compileNode(elem.node, source)
                            is SeqElementIR.Spread -> compileNode(elem.node, source)
                        }
                    }.toTypedArray()
                    SeqLiteralNode(elementNodes)
                }
            }
            is SeqGetIR -> {
                val seqNode = compileNode(node.seq, source)
                val indexNode = compileNode(node.index, source)
                SeqAccessNode.create(seqNode, indexNode)
            }

            // === Closures ===
            is MakeLambdaIR -> {
                // Look up the function body from the registry and compile it
                val fnIR = functionRegistry[node.functionHash]
                val closureSlots = node.captures.map { it.slot }.toIntArray()
                val closureKinds = node.captures.map { slotKindToInt(it.kind) }.toIntArray()

                // RECURSIVE LAMBDA GUARD: if we're already compiling this function
                // (i.e., this MakeLambdaIR appears inside the function's own body —
                // a recursive reference from a do-block recursive let), don't
                // recursively compile. Use the runtime hash lookup path instead.
                val isRecursiveSelfRef = node.functionHash in compilingFunctions

                if (fnIR != null && !isRecursiveSelfRef) {
                    // Compile the lambda's body from its FunctionIR
                    val fnSource = getOrCreateSource(fnIR.sourceFile)
                    compilingFunctions.add(node.functionHash)
                    val bodyNode = try {
                        compileNode(fnIR.body, fnSource)
                    } finally {
                        compilingFunctions.remove(node.functionHash)
                    }
                    val innerFd = buildFrameDescriptor(fnIR)
                    FunctionDefinitionNode(
                        fnIR.name,
                        node.functionHash,
                        closureSlots,
                        closureKinds,
                        fnIR.paramNames.toTypedArray(),
                        bodyNode,
                        innerFd,
                        null
                    )
                } else {
                    // Fallback: lookup by hash at runtime
                    // Used for: (1) function not yet registered, (2) recursive self-references
                    val bodyNode = MonomorphicCallNode(node.functionHash, node.functionHash.toString(), UnitLiteralNode())
                    val innerFd = FrameDescriptor.newBuilder().build()
                    FunctionDefinitionNode(
                        node.functionHash.toString(),
                        node.functionHash,
                        closureSlots,
                        closureKinds,
                        emptyArray(),
                        bodyNode,
                        innerFd,
                        null
                    )
                }
            }

            // === Effects ===
            is HandleIR -> {
                val bodyNode = compileNode(node.body, source)
                if (node.handlers.isNotEmpty()) {
                    // Nest HandleNodes for multiple handlers: outermost handler wraps
                    // the next handler which wraps ... which wraps the body.
                    // Process from last to first so the first handler is outermost.
                    var currentBody: MezzeNode = bodyNode
                    for (handler in node.handlers.reversed()) {
                        val contentHashes = arrayOf(handler.handlerHash, handler.effectHash)
                        val handlerNode: MezzeNode = if (handler.params != null) {
                            val paramsNode = compileNode(handler.params!!, source)
                            HandleNode.createParameterizedHandlerLookup(-1, handler.handlerHash, handler.performerName, paramsNode)
                        } else {
                            HandleNode.createHandlerLookup(-1, handler.handlerHash, handler.performerName)
                        }
                        currentBody = HandleNode(
                            -1,                     // no local slot
                            handler.performerName,
                            contentHashes,
                            handlerNode,
                            currentBody,
                            -1,     // no scope slot
                            null,   // no initialize_scope
                            null,   // no finalize_scope
                            false,  // no initialize
                            false   // no finalize
                        )
                    }
                    currentBody
                } else {
                    bodyNode
                }
            }
            is PerformOpIR -> {
                // Effect operation — runtime dispatch via DynamicEffectCallNode
                val argNode = compileNode(node.arg, source)
                DynamicEffectCallNode.create(
                    node.operationName,     // operation name for handler record lookup
                    node.operationName,     // operation name
                    argNode,
                    node.effectHash         // effect content hash
                )
            }
            is DictMethodCallIR -> {
                // Pure dictionary call: read impl function from dict slot, call it directly.
                // No runtime type-tag matching. The typechecker resolved everything statically.
                val argNode = compileNode(node.arg, source)
                com.mezze.interpreter.nodes.function.DictMethodCallNode.create(
                    node.dictSlot,
                    argNode
                )
            }
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    /**
     * Create a SourceSection from SourceInfo for debugger/profiler integration.
     * Returns null if the source info has invalid coordinates (e.g., synthetic nodes).
     *
     * Uses Source.createSection(startLine, startColumn, endLine, endColumn) which
     * maps the function's source range so that Chrome DevTools and other Truffle
     * tools can display source locations in stack traces and profiler output.
     */
    private fun createSourceSection(source: Source, info: SourceInfo): SourceSection? {
        return try {
            if (info.startLine <= 0 || info.startCol <= 0) return null
            // Truffle's createSection uses 1-based lines and columns
            // Calculate character length from line/column range
            val startOffset = source.getLineStartOffset(info.startLine) + (info.startCol - 1)
            val endLine = if (info.endLine > 0) info.endLine else info.startLine
            val endCol = if (info.endCol > 0) info.endCol else info.startCol
            val endOffset = source.getLineStartOffset(endLine) + (endCol - 1)
            val length = (endOffset - startOffset).coerceAtLeast(1)
            source.createSection(startOffset, length)
        } catch (_: Exception) {
            // Source may be empty or coordinates out of range for synthetic/generated code
            null
        }
    }

    private fun buildFrameDescriptor(ir: FunctionIR): FrameDescriptor {
        // Frame layout must match MezzeFunctionRootNode's runtime layout:
        //   [capture0..captureN, param0..paramM, local0..localK]
        // Captures are bound first (closure values), then params (from record unpacking),
        // then locals (from let bindings in the body).
        val builder = FrameDescriptor.newBuilder()
        for (i in ir.captureSlots.indices) {
            builder.addSlot(slotKindToFrameKind(ir.captureSlots[i]), ir.captureNames.getOrElse(i) { "capture_$i" }, null)
        }
        for (i in ir.paramSlots.indices) {
            builder.addSlot(slotKindToFrameKind(ir.paramSlots[i]), ir.paramNames.getOrElse(i) { "param_$i" }, null)
        }
        for (i in ir.localSlots.indices) {
            builder.addSlot(slotKindToFrameKind(ir.localSlots[i]), ir.localNames.getOrElse(i) { "local_$i" }, null)
        }
        return builder.build()
    }

    private fun slotKindToFrameKind(kind: SlotKind): FrameSlotKind = when (kind) {
        SlotKind.LONG -> FrameSlotKind.Long
        SlotKind.DOUBLE -> FrameSlotKind.Double
        SlotKind.BOOLEAN -> FrameSlotKind.Boolean
        SlotKind.OBJECT -> FrameSlotKind.Object
    }

    private fun slotKindToInt(kind: SlotKind): Int = when (kind) {
        SlotKind.LONG -> ReadLocalNode.KIND_LONG
        SlotKind.DOUBLE -> ReadLocalNode.KIND_DOUBLE
        SlotKind.BOOLEAN -> ReadLocalNode.KIND_BOOLEAN
        SlotKind.OBJECT -> ReadLocalNode.KIND_OBJECT
    }

    fun getOrCreateSourcePublic(file: String): Source = getOrCreateSource(file)

    /**
     * Try to compile an indirect call as an inline operator.
     * Pattern: CallIndirect(ReadField(receiver, "add"), MakeRecord({r = ...}))
     */
    private fun tryCompileInlineOp(node: CallIndirectIR, source: Source): MezzeNode? {
        val fn = node.function
        if (fn !is ReadFieldIR) return null

        val opName = fn.fieldName
        val argRecord = node.arg
        if (argRecord !is MakeRecordIR) return null

        // Extract the receiver (left operand)
        val receiverNode = compileNode(fn.record, source)

        // Unary operators: pattern is CallIndirect(ReadField(x, "neg"), MakeRecord({it = x}))
        // The Keywords.IT field contains the operand. Compile to native function call with
        // the correct argument record.
        val itField = argRecord.fields.find { it.name == Keywords.IT }
        if (itField != null) {
            when (opName) {
                "neg" -> {
                    // -x → __native_int_neg {n = x} (or __native_float_neg for Float)
                    val nativeArg = compileNode(MakeRecordIR(
                        listOf(FieldIR("n", itField.value)), node.src), source)
                    return NativeDispatch.create("__native_int_neg", nativeArg)
                }
                "not" -> {
                    // !x → __native_bool_not {b = x}
                    val nativeArg = compileNode(MakeRecordIR(
                        listOf(FieldIR("b", itField.value)), node.src), source)
                    return NativeDispatch.create("__native_bool_not", nativeArg)
                }
                Keywords.TO_DBG_STR -> {
                    // x.to_dbg_str {} → DirectInspectNode(x) (built-in Inspect ability)
                    return com.mezze.interpreter.nodes.inline.DirectInspectNode.create(compileNode(itField.value, source))
                }
            }
        }

        // Binary operators: extract right operand from Keywords.R field
        val rightField = argRecord.fields.find { it.name == Keywords.R }
        val rightNode = rightField?.let { compileNode(it.value, source) }

        if (rightNode == null) return null

        // Map binary operator name to inline node
        return when (opName) {
            "add" -> com.mezze.interpreter.nodes.inline.DirectAddNode.create(receiverNode, rightNode)
            "sub" -> com.mezze.interpreter.nodes.inline.DirectSubtractNode.create(receiverNode, rightNode)
            "mul" -> com.mezze.interpreter.nodes.inline.DirectMultiplyNode.create(receiverNode, rightNode)
            "div" -> com.mezze.interpreter.nodes.inline.DirectDivideNode.create(receiverNode, rightNode)
            "mod" -> com.mezze.interpreter.nodes.inline.DirectModuloNode.create(receiverNode, rightNode)
            "eq" -> com.mezze.interpreter.nodes.inline.DirectEqualNode.create(receiverNode, rightNode)
            "neq" -> com.mezze.interpreter.nodes.inline.DirectNotEqualNode.create(receiverNode, rightNode)
            "lt" -> com.mezze.interpreter.nodes.inline.DirectLessThanNode.create(receiverNode, rightNode)
            "le" -> com.mezze.interpreter.nodes.inline.DirectLessThanOrEqualNode.create(receiverNode, rightNode)
            "gt" -> com.mezze.interpreter.nodes.inline.DirectGreaterThanNode.create(receiverNode, rightNode)
            "ge" -> com.mezze.interpreter.nodes.inline.DirectGreaterThanOrEqualNode.create(receiverNode, rightNode)
            "and" -> com.mezze.interpreter.nodes.inline.DirectAndNode.create(receiverNode, rightNode)
            "or" -> com.mezze.interpreter.nodes.inline.DirectOrNode.create(receiverNode, rightNode)
            else -> null
        }
    }

    private fun compileInlineOp(op: InlineOp, left: MezzeNode, right: MezzeNode): MezzeNode {
        return when (op) {
            InlineOp.ADD -> com.mezze.interpreter.nodes.inline.DirectAddNode.create(left, right)
            InlineOp.SUB -> com.mezze.interpreter.nodes.inline.DirectSubtractNode.create(left, right)
            InlineOp.MUL -> com.mezze.interpreter.nodes.inline.DirectMultiplyNode.create(left, right)
            InlineOp.DIV -> com.mezze.interpreter.nodes.inline.DirectDivideNode.create(left, right)
            InlineOp.MOD -> com.mezze.interpreter.nodes.inline.DirectModuloNode.create(left, right)
            InlineOp.EQ -> com.mezze.interpreter.nodes.inline.DirectEqualNode.create(left, right)
            InlineOp.NEQ -> com.mezze.interpreter.nodes.inline.DirectNotEqualNode.create(left, right)
            InlineOp.LT -> com.mezze.interpreter.nodes.inline.DirectLessThanNode.create(left, right)
            InlineOp.LE -> com.mezze.interpreter.nodes.inline.DirectLessThanOrEqualNode.create(left, right)
            InlineOp.GT -> com.mezze.interpreter.nodes.inline.DirectGreaterThanNode.create(left, right)
            InlineOp.GE -> com.mezze.interpreter.nodes.inline.DirectGreaterThanOrEqualNode.create(left, right)
            InlineOp.AND -> com.mezze.interpreter.nodes.inline.DirectAndNode.create(left, right)
            InlineOp.OR -> com.mezze.interpreter.nodes.inline.DirectOrNode.create(left, right)
            InlineOp.NEG -> com.mezze.interpreter.nodes.inline.DirectSubtractNode.create(IntLiteralNode(0), left)
            InlineOp.NOT -> com.mezze.interpreter.nodes.inline.DirectEqualNode.create(left, BoolLiteralNode(false))
        }
    }

    /**
     * Maps SpecializedOp enum values to specialized Truffle nodes.
     * Each case is wired one category at a time as part of the DS-Wire effort.
     *
     * TODO: Wire each SpecializedOp to its corresponding pre-built Truffle node.
     * For now, throw — the typechecker only emits ops we've wired.
     */
    private fun compileSpecializedOp(
        op: com.mezze.language.truffleir.SpecializedOp,
        args: Array<MezzeNode>
    ): MezzeNode {
        return when (op) {
            // Phase 2 (Map) — wired below as we add them
            else -> throw IllegalStateException("SpecializedOp not yet wired: $op")
        }
    }

    private fun getOrCreateSource(file: String): Source {
        return sourceCache.getOrPut(file) {
            try {
                val content = java.io.File(file).readText()
                Source.newBuilder("mezze", content, file).build()
            } catch (_: Exception) {
                Source.newBuilder("mezze", "", file).build()
            }
        }
    }
}
