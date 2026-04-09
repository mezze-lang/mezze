package com.mezze.interpreter.v2.compiler

import com.mezze.interpreter.v2.nodes.*
import com.mezze.interpreter.v2.nodes.control.*
import com.mezze.interpreter.v2.nodes.function.*
import com.mezze.interpreter.v2.nodes.literal.*
import com.mezze.interpreter.v2.nodes.record.*
import com.mezze.interpreter.v2.nodes.seq.SeqAccessNode
import com.mezze.interpreter.v2.nodes.seq.SeqLiteralNode
import com.mezze.interpreter.v2.nodes.variable.*
import com.mezze.interpreter.v2.nodes.variant.MatchNode
import com.mezze.interpreter.v2.nodes.variant.MatchBranchNode
import com.mezze.interpreter.v2.nodes.variant.VariantConstructNode
import com.mezze.interpreter.v2.nodes.effect.HandleNode
import com.mezze.interpreter.v2.nodes.effect.DynamicEffectCallNode
import com.mezze.interpreter.v2.converter.InterpolatedStringNode
import com.mezze.interpreter.v2.nodes.builtin.NativeDispatch
import com.mezze.interpreter.v2.runtime.MezzeLanguage
import com.mezze.language.resolvedast.SlotKind
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
    private val functionRegistry = mutableMapOf<String, FunctionIR>()

    /** Register all FunctionIRs so MakeLambdaIR can find them */
    fun registerFunctions(functions: List<FunctionIR>) {
        for (fn in functions) {
            functionRegistry[fn.hash.hex] = fn
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

        if (ir.paramSlots.isNotEmpty() && ir.captureSlots.isEmpty()) {
            // Function has parameters and NO captures — use MezzeFunctionRootNode
            // for record unpacking. Params go to slots 0..P-1 (matches frame descriptor).
            val rootNode = MezzeFunctionRootNode(
                language,
                frameDescriptor,
                bodyNode,
                ir.name,
                ir.paramNames.toTypedArray(),
                IntArray(0),  // no closure slots
                IntArray(0)
            )
            if (section != null) rootNode.setSourceSection(section)
            return rootNode
        } else {
            // No parameters — use MezzeRootNode (entry functions, thunks)
            val rootNode = MezzeRootNode(
                language,
                frameDescriptor,
                bodyNode,
                ir.name,
                ir.captureSlots.size,
                ir.paramSlots.size,
                IntArray(0)
            )
            if (section != null) rootNode.setSourceSection(section)
            return rootNode
        }
    }

    /**
     * Compile a NodeIR to a MezzeNode.
     */
    fun compileNode(node: NodeIR, source: Source): MezzeNode {
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
            is ReadContextIR -> {
                // Read a named value from the MezzeContext (used for `scope` in handler operations)
                com.mezze.interpreter.v2.nodes.ReadContextNode(node.name)
            }
            is WriteLocalIR -> {
                val valueNode = compileNode(node.value, source)
                WriteLocalNode.create(valueNode, node.slot, slotKindToInt(node.kind))
            }
            is ReadCaptureIR -> ReadLocalNode(node.index, slotKindToInt(node.kind))
            is ReadArgIR -> ReadArgumentNode(node.index)

            // === Calls ===
            is CallDirectIR -> {
                val argNode = compileNode(node.arg, source)
                if (node.isTailCall) {
                    // For tail calls, resolve the function without calling it.
                    // TailCallNode will throw TailCallException with the function's CallTarget.
                    val fnNode = FunctionLookupNode(node.targetHash.hex)
                    TailCallNode.create(fnNode, arrayOf(argNode))
                } else {
                    MonomorphicCallNode(node.targetHash.hex, node.targetHash.hex, argNode, false)
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
                NativeDispatch.create(node.name, argNode)
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
            is InlineBinaryOpIR -> {
                val leftNode = compileNode(node.left, source)
                val rightNode = compileNode(node.right, source)
                compileInlineOp(node.op, leftNode, rightNode)
            }

            // === Records ===
            is MakeRecordIR -> {
                val fieldNames = node.fields.map { it.name }.toTypedArray()
                val fieldNodes = node.fields.map { compileNode(it.value, source) }.toTypedArray()
                RecordLiteralNode.allObjects(fieldNames, fieldNodes)
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
                    when (val pat = case.pattern) {
                        is PatternIR.Wildcard -> {
                            wildcardIndex = idx
                            if (guardNode != null) {
                                MatchBranchNode.wildcardWithGuard(
                                    if (pat.slot >= 0) pat.slot else -1, bodyNode, guardNode, emptyArray())
                            } else if (pat.slot >= 0) {
                                MatchBranchNode.wildcard(pat.slot, bodyNode)
                            } else {
                                MatchBranchNode.wildcardNoBinding(bodyNode)
                            }
                        }
                        is PatternIR.Variant -> {
                            if (guardNode != null) {
                                MatchBranchNode.variantPatternWithGuard(
                                    pat.tag, pat.fieldNames.toTypedArray(),
                                    pat.fieldSlots.toIntArray(), bodyNode, guardNode, emptyArray())
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
                            if (guardNode != null) {
                                MatchBranchNode.literalPatternWithGuard(litValue, bodyNode, guardNode, emptyArray())
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
                            if (guardNode != null) {
                                MatchBranchNode.recordPatternWithGuard(
                                    pat.fieldNames.toTypedArray(),
                                    pat.fieldSlots.toIntArray(),
                                    pat.isOpen,
                                    bodyNode, guardNode, emptyArray())
                            } else {
                                MatchBranchNode.recordPattern(
                                    pat.fieldNames.toTypedArray(),
                                    pat.fieldSlots.toIntArray(),
                                    pat.isOpen,
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
                                stmt.name, stmt.contentHash.hex
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
                val elementNodes = node.elements.map { elem ->
                    when (elem) {
                        is SeqElementIR.Regular -> compileNode(elem.node, source)
                        is SeqElementIR.Spread -> compileNode(elem.node, source) // TODO: seq spread
                    }
                }.toTypedArray()
                SeqLiteralNode(elementNodes)
            }
            is SeqGetIR -> {
                val seqNode = compileNode(node.seq, source)
                val indexNode = compileNode(node.index, source)
                SeqAccessNode.create(seqNode, indexNode)
            }

            // === Closures ===
            is MakeLambdaIR -> {
                // Look up the function body from the registry and compile it
                val fnIR = functionRegistry[node.functionHash.hex]
                val closureSlots = node.captures.map { it.slot }.toIntArray()
                val closureKinds = node.captures.map { slotKindToInt(it.kind) }.toIntArray()

                if (fnIR != null) {
                    // Compile the lambda's body from its FunctionIR
                    val fnSource = getOrCreateSource(fnIR.sourceFile)
                    val bodyNode = compileNode(fnIR.body, fnSource)
                    val innerFd = buildFrameDescriptor(fnIR)
                    FunctionDefinitionNode(
                        fnIR.name,
                        node.functionHash.hex,
                        closureSlots,
                        closureKinds,
                        fnIR.paramNames.toTypedArray(),
                        bodyNode,
                        innerFd,
                        null
                    )
                } else {
                    // Fallback: lookup by hash at runtime
                    val bodyNode = MonomorphicCallNode(node.functionHash.hex, node.functionHash.hex, UnitLiteralNode())
                    val innerFd = FrameDescriptor.newBuilder().build()
                    FunctionDefinitionNode(
                        node.functionHash.hex,
                        node.functionHash.hex,
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
                    val handler = node.handlers.first()
                    val contentHashes = arrayOf(handler.handlerHash.hex, handler.effectHash.hex)
                    // HandlerLookupNode: tries content hash first, then performer name.
                    // For both parameterized and non-parameterized handlers, lookup returns
                    // either a MezzeRecord (performer record) or MezzeFunction (closure factory).
                    // HandlerLookupNode handles MezzeFunction → MezzeRecord evaluation internally.
                    val handlerNode: MezzeNode = HandleNode.createHandlerLookup(-1, handler.handlerHash.hex, handler.performerName)
                    HandleNode(
                        -1,                     // no local slot
                        arrayOf(handler.effectHash.hex),  // effect hashes for context registration
                        handler.performerName,
                        contentHashes,
                        handlerNode,
                        bodyNode,
                        -1,     // no scope slot
                        null,   // no initialize_scope
                        null,   // no finalize_scope
                        false,  // no initialize
                        false   // no finalize
                    )
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
                    node.effectHash.hex     // effect content hash
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
        // Declare typed slot kinds so Truffle/Graal can specialize frame access.
        // ReadLocalNode/WriteLocalNode catch FrameSlotTypeException and fall back
        // to getValue() if the runtime type doesn't match the declared kind.
        val builder = FrameDescriptor.newBuilder()
        for (i in ir.paramSlots.indices) {
            builder.addSlot(slotKindToFrameKind(ir.paramSlots[i]), ir.paramNames.getOrElse(i) { "param_$i" }, null)
        }
        for (i in ir.localSlots.indices) {
            builder.addSlot(slotKindToFrameKind(ir.localSlots[i]), ir.localNames.getOrElse(i) { "local_$i" }, null)
        }
        for (i in ir.captureSlots.indices) {
            builder.addSlot(slotKindToFrameKind(ir.captureSlots[i]), ir.captureNames.getOrElse(i) { "capture_$i" }, null)
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
        // The "it" field contains the operand. Compile to native function call with the
        // correct argument record.
        val itField = argRecord.fields.find { it.name == "it" }
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
                "to_dbg_str" -> {
                    // x.to_dbg_str {} → DirectInspectNode(x) (built-in Inspect ability)
                    return com.mezze.interpreter.v2.nodes.inline.DirectInspectNode.create(compileNode(itField.value, source))
                }
            }
        }

        // Binary operators: extract 'r' field (right operand)
        val rightField = argRecord.fields.find { it.name == "r" }
        val rightNode = rightField?.let { compileNode(it.value, source) }

        if (rightNode == null) return null

        // Map binary operator name to inline node
        return when (opName) {
            "add" -> com.mezze.interpreter.v2.nodes.inline.DirectAddNode.create(receiverNode, rightNode)
            "sub" -> com.mezze.interpreter.v2.nodes.inline.DirectSubtractNode.create(receiverNode, rightNode)
            "mul" -> com.mezze.interpreter.v2.nodes.inline.DirectMultiplyNode.create(receiverNode, rightNode)
            "div" -> com.mezze.interpreter.v2.nodes.inline.DirectDivideNode.create(receiverNode, rightNode)
            "mod" -> com.mezze.interpreter.v2.nodes.inline.DirectModuloNode.create(receiverNode, rightNode)
            "eq" -> com.mezze.interpreter.v2.nodes.inline.DirectEqualNode.create(receiverNode, rightNode)
            "neq" -> com.mezze.interpreter.v2.nodes.inline.DirectNotEqualNode.create(receiverNode, rightNode)
            "lt" -> com.mezze.interpreter.v2.nodes.inline.DirectLessThanNode.create(receiverNode, rightNode)
            "le" -> com.mezze.interpreter.v2.nodes.inline.DirectLessThanOrEqualNode.create(receiverNode, rightNode)
            "gt" -> com.mezze.interpreter.v2.nodes.inline.DirectGreaterThanNode.create(receiverNode, rightNode)
            "ge" -> com.mezze.interpreter.v2.nodes.inline.DirectGreaterThanOrEqualNode.create(receiverNode, rightNode)
            "and" -> com.mezze.interpreter.v2.nodes.inline.DirectAndNode.create(receiverNode, rightNode)
            "or" -> com.mezze.interpreter.v2.nodes.inline.DirectOrNode.create(receiverNode, rightNode)
            else -> null
        }
    }

    private fun compileInlineOp(op: InlineOp, left: MezzeNode, right: MezzeNode): MezzeNode {
        return when (op) {
            InlineOp.ADD -> com.mezze.interpreter.v2.nodes.inline.DirectAddNode.create(left, right)
            InlineOp.SUB -> com.mezze.interpreter.v2.nodes.inline.DirectSubtractNode.create(left, right)
            InlineOp.MUL -> com.mezze.interpreter.v2.nodes.inline.DirectMultiplyNode.create(left, right)
            InlineOp.DIV -> com.mezze.interpreter.v2.nodes.inline.DirectDivideNode.create(left, right)
            InlineOp.MOD -> com.mezze.interpreter.v2.nodes.inline.DirectModuloNode.create(left, right)
            InlineOp.EQ -> com.mezze.interpreter.v2.nodes.inline.DirectEqualNode.create(left, right)
            InlineOp.NEQ -> com.mezze.interpreter.v2.nodes.inline.DirectNotEqualNode.create(left, right)
            InlineOp.LT -> com.mezze.interpreter.v2.nodes.inline.DirectLessThanNode.create(left, right)
            InlineOp.LE -> com.mezze.interpreter.v2.nodes.inline.DirectLessThanOrEqualNode.create(left, right)
            InlineOp.GT -> com.mezze.interpreter.v2.nodes.inline.DirectGreaterThanNode.create(left, right)
            InlineOp.GE -> com.mezze.interpreter.v2.nodes.inline.DirectGreaterThanOrEqualNode.create(left, right)
            InlineOp.AND -> com.mezze.interpreter.v2.nodes.inline.DirectAndNode.create(left, right)
            InlineOp.OR -> com.mezze.interpreter.v2.nodes.inline.DirectOrNode.create(left, right)
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
