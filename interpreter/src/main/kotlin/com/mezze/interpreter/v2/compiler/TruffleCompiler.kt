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
     * Compile a FunctionIR to a MezzeRootNode.
     */
    fun compile(ir: FunctionIR): MezzeRootNode {
        val source = getOrCreateSource(ir.sourceFile)
        val frameDescriptor = buildFrameDescriptor(ir)
        val bodyNode = compileNode(ir.body, source)

        return MezzeRootNode(
            language,
            frameDescriptor,
            bodyNode,
            ir.name,
            ir.captureSlots.size,   // closureCount — captures come before params in the frame
            ir.paramSlots.size,     // parameterCount
            IntArray(0)  // ability slot requirements — empty for monomorphized code
        )
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
                    // Tail call — uses TailCallNode for TCO
                    val fnNode = MonomorphicCallNode(node.targetHash.hex, node.targetHash.hex, UnitLiteralNode())
                    TailCallNode.create(fnNode, arrayOf(argNode))
                } else {
                    // Direct call by content hash
                    MonomorphicCallNode(node.targetHash.hex, node.targetHash.hex, argNode)
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
                    when (val pat = case.pattern) {
                        is PatternIR.Wildcard -> {
                            wildcardIndex = idx
                            if (pat.slot >= 0) {
                                MatchBranchNode.wildcard(pat.slot, bodyNode)
                            } else {
                                MatchBranchNode.wildcardNoBinding(bodyNode)
                            }
                        }
                        is PatternIR.Variant -> {
                            MatchBranchNode.variantPatternWithFieldNames(
                                pat.tag,
                                pat.fieldNames.toTypedArray(),
                                pat.fieldSlots.toIntArray(),
                                bodyNode
                            )
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
                            MatchBranchNode.literalPattern(litValue, bodyNode)
                        }
                        is PatternIR.Record -> {
                            MatchBranchNode.recordPattern(
                                pat.fieldNames.toTypedArray(),
                                pat.fieldSlots.toIntArray(),
                                pat.isOpen,
                                bodyNode
                            )
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
                    // Compile handler params as the handler value expression.
                    // If params is null, fall back to looking up by effect hash in context.
                    val paramsIR = handler.params
                    val handlerNode: MezzeNode = if (paramsIR != null) {
                        compileNode(paramsIR, source)
                    } else {
                        com.mezze.interpreter.v2.converter.GlobalLookupNode(handler.performerName)
                    }
                    HandleNode(
                        -1,                     // no local slot
                        arrayOf(handler.effectHash.hex),  // effect names for context registration
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

    private fun buildFrameDescriptor(ir: FunctionIR): FrameDescriptor {
        val builder = FrameDescriptor.newBuilder()
        for (i in ir.paramSlots.indices) {
            builder.addSlot(FrameSlotKind.Illegal, ir.paramNames.getOrElse(i) { "param_$i" }, null)
        }
        for (i in ir.localSlots.indices) {
            builder.addSlot(FrameSlotKind.Illegal, ir.localNames.getOrElse(i) { "local_$i" }, null)
        }
        for (i in ir.captureSlots.indices) {
            builder.addSlot(FrameSlotKind.Illegal, ir.captureNames.getOrElse(i) { "capture_$i" }, null)
        }
        return builder.build()
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
