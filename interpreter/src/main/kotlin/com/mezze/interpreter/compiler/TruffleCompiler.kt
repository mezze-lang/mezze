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

    /**
     * In-memory source content, keyed by file path, provided by the caller
     * (e.g. the polyglot `ParsingRequest.getSource()` on `context.eval`). When
     * present, [getOrCreateSource] builds the Truffle Source from THIS content
     * instead of re-reading the file from disk — essential for the debugger,
     * whose breakpoints match against the exact Source the client evaluated
     * (an in-memory eval source has no on-disk file, so disk-reading yields
     * empty content and breakpoints never resolve).
     */
    private val sourceContentOverrides = mutableMapOf<String, String>()

    /** Provide the real source text for [file] (overrides disk-reading). */
    fun registerSourceContent(file: String, content: String) {
        sourceContentOverrides[file] = content
    }
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
        if (section != null) {
            mezzeNode.setSourceSection(section)
            // Tag statement-level nodes with StandardTags.StatementTag so the
            // debugger can place line breakpoints + single-step. We tag the
            // meaningful stop points — calls (where work happens) and local
            // writes (let bindings) — rather than every sub-expression, which
            // keeps the instrumentation surface small (no wrapper on literals
            // / local reads) while still stopping on every line a user cares
            // about. Only when the node has a real source section.
            if (isStatementLevel(node)) mezzeNode.markStatement()
        }
        return mezzeNode
    }

    /**
     * Attribute [node] to the statement's own source [info] and tag it as a
     * debugger stop-point. Used for do-block statements, whose wrapper node
     * (WriteLocalWithContextNode) is created directly (not via compileNode) and
     * whose value's own loc is unreliable after inlining. Overwrites any section
     * the node already got from its value — the statement line is what a user
     * sets a breakpoint on.
     */
    private fun attributeStatement(node: MezzeNode, info: SourceInfo, source: Source) {
        val section = createSourceSection(source, info) ?: return
        node.setSourceSection(section)
        node.markStatement()
    }

    /** True for IR nodes that make good debugger line-breakpoint stop points. */
    private fun isStatementLevel(node: NodeIR): Boolean = when (node) {
        is CallDirectIR, is CallLocalIR, is CallIndirectIR, is CallNativeIR,
        is WriteLocalIR -> true
        else -> false
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
            is EffectBindingIR -> {
                // Effect-handler runtime name (`scope`, `resume`, `abort`).
                com.mezze.interpreter.nodes.effect.EffectBindingNode(node.name)
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
                // DS-Wire fast paths: many stdlib ops compile to a single
                // CallNativeIR("__native_xxx", record). When the matching
                // specialized Truffle node exists, lower directly to it
                // instead of the HashMap-driven NativeDispatch path. Skips
                // the per-call HashMap.get + handler.apply + record alloc.
                val dsWired = tryCompileDSWiredNative(node, source)
                if (dsWired != null) return dsWired

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
                            val writeNode = WriteLocalWithContextNode.create(
                                valueNode, stmt.slot, slotKindToInt(stmt.kind),
                                stmt.name, stmt.contentHash
                            )
                            // Attribute the statement to its OWN source line (not
                            // the value's, which inlining can scramble to another
                            // line) and tag it as a debugger stop-point. A do-block
                            // `let` is the canonical line a user breakpoints on.
                            attributeStatement(writeNode, stmt.src, source)
                            allNodes.add(writeNode)
                        }
                        is StmtIR.Expr -> {
                            val exprNode = compileNode(stmt.node, source)
                            attributeStatement(exprNode, stmt.src, source)
                            allNodes.add(exprNode)
                        }
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
                        innerFd
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
                        innerFd
                    )
                }
            }

            // === Effects ===
            is HandleIR -> {
                val bodyNode = compileNode(node.body, source)
                if (node.handlers.isNotEmpty()) {
                    // Nest HandleNodes for multiple handlers: outermost handler
                    // wraps the next which wraps ... which wraps the body.
                    // Process from last to first so the first handler is outermost.
                    var currentBody: MezzeNode = bodyNode
                    for (handler in node.handlers.reversed()) {
                        val handlerNode: MezzeNode = if (handler.params != null) {
                            val paramsNode = compileNode(handler.params!!, source)
                            HandleNode.createParameterizedHandlerLookup(
                                -1, handler.handlerHash, handler.performerName, paramsNode
                            )
                        } else {
                            HandleNode.createHandlerLookup(
                                -1, handler.handlerHash, handler.performerName
                            )
                        }
                        currentBody = HandleNode(handlerNode, currentBody, handler.effectHash)
                    }
                    currentBody
                } else {
                    bodyNode
                }
            }
            is PerformOpIR -> {
                // Effect operation — fiber-yield via OpCallNode; the surrounding
                // HandleNode's driver dispatches by op name. The effect hash is
                // only used to build a MezzeSuspended thunk when there is no
                // fiber in scope (the lazy-effects fallback).
                //
                // Fast path: `Mut.mutate { act = lambda }` is by far the
                // hottest effect-op in the language (every stdlib mutable op
                // — incr, write_int, push, etc. — wraps its body in
                // `Mut.mutate { act = {} -> __native_... }`). Skip building
                // the wrapper `{ act = ... }` record entirely: emit a
                // specialized op-call that takes the `act` value directly.
                // The marker fast path in OpCallNode recognises this and
                // calls the impl with the raw lambda as args (the impl is
                // `{ act } -> act {}`, which is the LocalMut shape).
                if (isMutMutateWithActLambda(node)) {
                    val argRecord = node.arg as MakeRecordIR
                    val actNode = compileNode(argRecord.fields[0].value, source)
                    com.mezze.interpreter.nodes.effect.OpCallNode.createMutMutateActFast(
                        node.effectHash, actNode
                    )
                } else {
                    val argNode = compileNode(node.arg, source)
                    com.mezze.interpreter.nodes.effect.OpCallNode.create(
                        node.operationName, node.effectHash, argNode
                    )
                }
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
     * True if {@code node} is a `Mut.mutate { act = (something) }` call.
     * In that shape the wrapper record allocation can be elided — the
     * impl is `{ act } -> act {}` so we can pass the `act` value
     * straight to the impl instead of allocating a `{act = ...}` record.
     */
    private fun isMutMutateWithActLambda(node: PerformOpIR): Boolean {
        if (node.operationName != "mutate") return false
        if (!com.mezze.interpreter.nodes.effect.MarkerEffects.isMutEffect(node.effectHash)) return false
        val arg = node.arg as? MakeRecordIR ?: return false
        if (arg.fields.size != 1) return false
        return arg.fields[0].name == "act"
    }

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

    /** DS-Wire: lower select CallNativeIR sites directly to specialized Truffle
     *  nodes, skipping NativeDispatch's HashMap lookup + record indirection.
     *
     *  Returns null when the native op isn't wired or the arg shape doesn't
     *  match the expectation (in which case the regular NativeDispatch path
     *  takes over and stays correct). */
    private fun tryCompileDSWiredNative(node: CallNativeIR, source: Source): MezzeNode? {
        if (System.getenv("MEZZE_DISABLE_DSWIRE") == "true") return null
        val argRec = node.arg as? MakeRecordIR ?: return null

        // Pull a named field's IR; null when missing (caller falls back to NativeDispatch).
        fun field(name: String): NodeIR? = argRec.fields.firstOrNull { it.name == name }?.value

        // Helper: collect arg expressions in declared order; bail if any missing.
        fun fields(vararg names: String): Array<MezzeNode>? {
            val out = arrayOfNulls<MezzeNode>(names.size)
            for ((i, n) in names.withIndex()) {
                val expr = field(n) ?: return null
                out[i] = compileNode(expr, source)
            }
            @Suppress("UNCHECKED_CAST")
            return out as Array<MezzeNode>
        }

        // Wire a native op by name → typed Natives method. Returns the
        // bridge node (1-arg, 2-arg, or N-arg specialization chosen by
        // NativeBridgeNode.create based on argNodes.length), or null if
        // the arg shape doesn't match.
        fun bridge(args: Array<MezzeNode>?, h: com.mezze.interpreter.nodes.builtin.NativeBridgeNode.Handler):
                MezzeNode? {
            if (args == null) return null
            return com.mezze.interpreter.nodes.builtin.NativeBridgeNode.create(args, h)
        }
        fun bridgeWithCall(args: Array<MezzeNode>?, h: com.mezze.interpreter.nodes.builtin.NativeBridgeNode.Handler):
                MezzeNode? {
            if (args == null) return null
            return com.mezze.interpreter.nodes.builtin.NativeBridgeNode.createWithCallNode(args, h)
        }

        return when (node.name) {
            // seq::size {xs} → SeqLengthNode(xs). SeqLengthNode returns plain
            // long for MezzeSeq/MezzeList/Object[]; semantics match SeqNatives.size.
            "__native_seq_size" -> {
                val xs = field("xs") ?: return null
                com.mezze.interpreter.nodes.seq.SeqLengthNode.create(compileNode(xs, source))
            }
            // seq::get {xs, i} → SeqGetOptionNode(xs, i). Returns Option-wrapped
            // result, matching SeqNatives.get exactly (delegates to it under the
            // hood after extracting positional args).
            "__native_seq_get" -> {
                val xs = field("xs") ?: return null
                val i = field("i") ?: return null
                com.mezze.interpreter.nodes.seq.SeqGetOptionNode.create(
                    compileNode(xs, source), compileNode(i, source))
            }
            // seq::map {xs, f} → SeqMapCallNode. Matches SeqNatives.map semantics:
            // both call f {x = element} and rebuild the seq via fromArray.
            "__native_seq_map" -> {
                val xs = field("xs") ?: return null
                val f = field("f") ?: return null
                com.mezze.interpreter.nodes.seq.SeqMapCallNode.create(
                    compileNode(xs, source), compileNode(f, source))
            }
            // seq::filter {xs, p} → SeqFilterCallNode. Matches SeqNatives.filter.
            "__native_seq_filter" -> {
                val xs = field("xs") ?: return null
                val p = field("p") ?: return null
                com.mezze.interpreter.nodes.seq.SeqFilterCallNode.create(
                    compileNode(xs, source), compileNode(p, source))
            }
            // seq::fold {xs, init, f} → SeqFoldCallNode. Matches SeqNatives.fold.
            "__native_seq_fold" -> {
                val xs = field("xs") ?: return null
                val init = field("init") ?: return null
                val f = field("f") ?: return null
                com.mezze.interpreter.nodes.seq.SeqFoldCallNode.create(
                    compileNode(xs, source), compileNode(init, source), compileNode(f, source))
            }
            // seq::sum {xs} → SeqSumCallNode. Plain reduce over the backing
            // long[] — no folder dispatch, no record alloc, no boxing.
            // Java-for-loop-shaped; PE/HotSpot vectorize the hot path.
            "__native_seq_sum" -> {
                val xs = field("xs") ?: return null
                com.mezze.interpreter.nodes.seq.SeqSumCallNode.create(compileNode(xs, source))
            }
            // seq::min {xs} → SeqReduceLongCallNode(MIN). Same shape as sum:
            // plain reduce over the backing long[], wraps result in Some.
            "__native_seq_min" -> {
                val xs = field("xs") ?: return null
                com.mezze.interpreter.nodes.seq.SeqReduceLongCallNode.createMin(
                    compileNode(xs, source))
            }
            // seq::max {xs} → SeqReduceLongCallNode(MAX). Mirror of min.
            "__native_seq_max" -> {
                val xs = field("xs") ?: return null
                com.mezze.interpreter.nodes.seq.SeqReduceLongCallNode.createMax(
                    compileNode(xs, source))
            }
            // seq::count {xs, p} → SeqCountCallNode. Per-predicate PIC,
            // Truffle LoopNode wraps the hot loop with the predicate's
            // DirectCallNode as @Child so the inliner inlines the
            // predicate into the OSR'd body. Skips the @TruffleBoundary +
            // IndirectCallNode generic-dispatch path through SeqNatives.count.
            "__native_seq_count" -> {
                val xs = field("xs") ?: return null
                val p = field("p") ?: return null
                com.mezze.interpreter.nodes.seq.SeqCountCallNode.create(
                    compileNode(xs, source), compileNode(p, source))
            }
            // seq::any {xs, p} → SeqAnyCallNode. Short-circuit on first match.
            "__native_seq_any" -> {
                val xs = field("xs") ?: return null
                val p = field("p") ?: return null
                com.mezze.interpreter.nodes.seq.SeqAnyCallNode.create(
                    compileNode(xs, source), compileNode(p, source))
            }
            // seq::all {xs, p} → SeqAllCallNode. Short-circuit on first !match.
            "__native_seq_all" -> {
                val xs = field("xs") ?: return null
                val p = field("p") ?: return null
                com.mezze.interpreter.nodes.seq.SeqAllCallNode.create(
                    compileNode(xs, source), compileNode(p, source))
            }

            // ===== Map ops =====
            // m → MapNatives.size(m)
            "__native_map_size" -> bridge(fields("m")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.collections.MapNatives.size(
                    args[0] as com.mezze.datastructures.map.MezzeMap<*, *>)
            }
            "__native_map_is_empty" -> bridge(fields("m")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.collections.MapNatives.isEmpty(
                    args[0] as com.mezze.datastructures.map.MezzeMap<*, *>)
            }
            "__native_map_get" -> bridge(fields("m", "k")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.collections.MapNatives.get(
                    args[0] as com.mezze.datastructures.map.MezzeMap<*, *>, args[1])
            }
            "__native_map_insert" -> bridge(fields("m", "k", "v")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.collections.MapNatives.insert(
                    args[0] as com.mezze.datastructures.map.MezzeMap<*, *>, args[1], args[2])
            }
            "__native_map_remove" -> bridge(fields("m", "k")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.collections.MapNatives.remove(
                    args[0] as com.mezze.datastructures.map.MezzeMap<*, *>, args[1])
            }
            "__native_map_contains_key" -> bridge(fields("m", "k")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.collections.MapNatives.containsKey(
                    args[0] as com.mezze.datastructures.map.MezzeMap<*, *>, args[1])
            }
            "__native_map_contains_value" -> bridge(fields("m", "v")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.collections.MapNatives.containsValue(
                    args[0] as com.mezze.datastructures.map.MezzeMap<*, *>, args[1])
            }
            "__native_map_get_or_default" -> bridge(fields("m", "k", "default")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.collections.MapNatives.getOrDefault(
                    args[0] as com.mezze.datastructures.map.MezzeMap<*, *>, args[1], args[2])
            }
            "__native_map_singleton" -> bridge(fields("k", "v")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.collections.MapNatives.singleton(args[0], args[1])
            }
            "__native_map_keys" -> bridge(fields("m")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.collections.MapNatives.keys(
                    args[0] as com.mezze.datastructures.map.MezzeMap<*, *>)
            }
            "__native_map_values" -> bridge(fields("m")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.collections.MapNatives.values(
                    args[0] as com.mezze.datastructures.map.MezzeMap<*, *>)
            }
            "__native_map_keys_seq" -> bridge(fields("m")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.collections.MapNatives.keysSeq(
                    args[0] as com.mezze.datastructures.map.MezzeMap<*, *>)
            }
            "__native_map_values_seq" -> bridge(fields("m")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.collections.MapNatives.valuesSeq(
                    args[0] as com.mezze.datastructures.map.MezzeMap<*, *>)
            }
            "__native_map_to_seq" -> bridge(fields("m")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.collections.MapNatives.toSeq(
                    args[0] as com.mezze.datastructures.map.MezzeMap<*, *>)
            }
            "__native_map_merge" -> bridge(fields("m1", "m2")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.collections.MapNatives.merge(
                    args[0] as com.mezze.datastructures.map.MezzeMap<*, *>,
                    args[1] as com.mezze.datastructures.map.MezzeMap<*, *>)
            }
            "__native_map_equals" -> bridge(fields("m1", "m2")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.collections.MapNatives.equals(
                    args[0] as com.mezze.datastructures.map.MezzeMap<*, *>,
                    args[1] as com.mezze.datastructures.map.MezzeMap<*, *>)
            }

            // ===== Set ops =====
            "__native_set_size" -> bridge(fields("s")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.collections.SetNatives.size(
                    args[0] as com.mezze.datastructures.set.MezzeSet<*>)
            }
            "__native_set_is_empty" -> bridge(fields("s")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.collections.SetNatives.isEmpty(
                    args[0] as com.mezze.datastructures.set.MezzeSet<*>)
            }
            "__native_set_contains" -> bridge(fields("s", "x")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.collections.SetNatives.contains(
                    args[0] as com.mezze.datastructures.set.MezzeSet<*>, args[1])
            }
            "__native_set_insert" -> bridge(fields("s", "x")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.collections.SetNatives.insert(
                    args[0] as com.mezze.datastructures.set.MezzeSet<*>, args[1])
            }
            "__native_set_remove" -> bridge(fields("s", "x")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.collections.SetNatives.remove(
                    args[0] as com.mezze.datastructures.set.MezzeSet<*>, args[1])
            }

            // ===== List ops =====
            "__native_list_length" -> bridge(fields("l")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.collections.ListNatives.length(
                    args[0] as com.mezze.datastructures.list.MezzeList<*>)
            }
            "__native_list_is_empty" -> bridge(fields("l")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.collections.ListNatives.isEmpty(
                    args[0] as com.mezze.datastructures.list.MezzeList<*>)
            }
            "__native_list_cons" -> bridge(fields("h", "t")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.collections.ListNatives.cons(
                    args[0], args[1] as com.mezze.datastructures.list.MezzeList<*>)
            }
            "__native_list_head" -> bridge(fields("l")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.collections.ListNatives.head(
                    args[0] as com.mezze.datastructures.list.MezzeList<*>)
            }
            "__native_list_tail" -> bridge(fields("l")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.collections.ListNatives.tail(
                    args[0] as com.mezze.datastructures.list.MezzeList<*>)
            }

            // ==================== Mutation primitives ====================
            // DS-Wire every __native_mut_* op: skips the NativeDispatch
            // HashMap lookup + per-call MezzeRecord allocation, and lets
            // the JIT inline straight through to the MutableX field ops.
            // Combined with MutEraser (which removes the Mut.mutate
            // wrapper), this makes Mut primitives behave like direct
            // value-type access at the JIT level.

            // --- MutInt ---
            "__native_int_to_mut" -> bridge(fields("n")) { args, _ ->
                com.mezze.interpreter.values.MutableInt((args[0] as Long))
            }
            "__native_int_to_immut" -> bridge(fields("n")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableInt).get()
            }
            "__native_mut_int_read" -> bridge(fields("n")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableInt).get()
            }
            "__native_mut_int_write" -> bridge(fields("n", "v")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableInt).set(args[1] as Long)
                com.mezze.interpreter.values.MezzeRecord.empty()
            }
            "__native_mut_int_incr" -> bridge(fields("n", "delta")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableInt).add(args[1] as Long)
                com.mezze.interpreter.values.MezzeRecord.empty()
            }
            "__native_mut_int_decr" -> bridge(fields("n", "delta")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableInt).subtract(args[1] as Long)
                com.mezze.interpreter.values.MezzeRecord.empty()
            }
            "__native_mut_int_mul" -> bridge(fields("n", "factor")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableInt).multiply(args[1] as Long)
                com.mezze.interpreter.values.MezzeRecord.empty()
            }
            "__native_mut_int_div" -> bridge(fields("n", "divisor")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableInt).divide(args[1] as Long)
                com.mezze.interpreter.values.MezzeRecord.empty()
            }
            "__native_mut_int_mod" -> bridge(fields("n", "divisor")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableInt).mod(args[1] as Long)
                com.mezze.interpreter.values.MezzeRecord.empty()
            }
            "__native_mut_int_cas" -> bridge(fields("n", "expected", "new_val")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableInt)
                    .compareAndSwap(args[1] as Long, args[2] as Long)
            }

            // --- MutFloat ---
            "__native_float_to_mut" -> bridge(fields("n")) { args, _ ->
                com.mezze.interpreter.values.MutableFloat((args[0] as Double))
            }
            "__native_float_to_immut" -> bridge(fields("n")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableFloat).get()
            }
            "__native_mut_float_read" -> bridge(fields("n")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableFloat).get()
            }
            "__native_mut_float_write" -> bridge(fields("n", "v")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableFloat).set(args[1] as Double)
                com.mezze.interpreter.values.MezzeRecord.empty()
            }
            "__native_mut_float_add" -> bridge(fields("n", "delta")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableFloat).add(args[1] as Double)
                com.mezze.interpreter.values.MezzeRecord.empty()
            }
            "__native_mut_float_sub" -> bridge(fields("n", "delta")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableFloat).subtract(args[1] as Double)
                com.mezze.interpreter.values.MezzeRecord.empty()
            }
            "__native_mut_float_mul" -> bridge(fields("n", "factor")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableFloat).multiply(args[1] as Double)
                com.mezze.interpreter.values.MezzeRecord.empty()
            }
            "__native_mut_float_div" -> bridge(fields("n", "divisor")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableFloat).divide(args[1] as Double)
                com.mezze.interpreter.values.MezzeRecord.empty()
            }

            // --- MutBool ---
            "__native_bool_to_mut" -> bridge(fields("b")) { args, _ ->
                com.mezze.interpreter.values.MutableBool((args[0] as Boolean))
            }
            "__native_bool_to_immut" -> bridge(fields("b")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableBool).get()
            }
            "__native_mut_bool_read" -> bridge(fields("b")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableBool).get()
            }
            "__native_mut_bool_write" -> bridge(fields("b", "v")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableBool).set(args[1] as Boolean)
                com.mezze.interpreter.values.MezzeRecord.empty()
            }
            "__native_mut_bool_toggle" -> bridge(fields("b")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableBool).toggle()
                com.mezze.interpreter.values.MezzeRecord.empty()
            }
            "__native_mut_bool_set_true" -> bridge(fields("b")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableBool).set(true)
                com.mezze.interpreter.values.MezzeRecord.empty()
            }
            "__native_mut_bool_set_false" -> bridge(fields("b")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableBool).set(false)
                com.mezze.interpreter.values.MezzeRecord.empty()
            }

            // --- MutString ---
            "__native_string_to_mut" -> bridge(fields("s")) { args, _ ->
                com.mezze.interpreter.values.MutableString(args[0] as String)
            }
            "__native_string_to_immut" -> bridge(fields("s")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableString).get()
            }
            "__native_string_with_capacity" -> bridge(fields("capacity")) { args, _ ->
                com.mezze.interpreter.values.MutableString((args[0] as Long).toInt())
            }
            "__native_mut_string_read" -> bridge(fields("s")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableString).get()
            }
            "__native_mut_string_write" -> bridge(fields("s", "v")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableString).set(args[1] as String)
                com.mezze.interpreter.values.MezzeRecord.empty()
            }
            "__native_mut_string_len" -> bridge(fields("s")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableString).length().toLong()
            }
            "__native_mut_string_is_empty" -> bridge(fields("s")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableString).isEmpty()
            }
            "__native_mut_string_append" -> bridge(fields("s", "suffix")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableString).append(args[1] as String)
                com.mezze.interpreter.values.MezzeRecord.empty()
            }
            "__native_mut_string_prepend" -> bridge(fields("s", "prefix")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableString).prepend(args[1] as String)
                com.mezze.interpreter.values.MezzeRecord.empty()
            }
            "__native_mut_string_clear" -> bridge(fields("s")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableString).clear()
                com.mezze.interpreter.values.MezzeRecord.empty()
            }
            "__native_mut_string_reverse" -> bridge(fields("s")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableString).reverse()
                com.mezze.interpreter.values.MezzeRecord.empty()
            }
            "__native_mut_string_insert" -> bridge(fields("s", "i", "text")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableString)
                    .insert((args[1] as Long).toInt(), args[2] as String)
                com.mezze.interpreter.values.MezzeRecord.empty()
            }
            "__native_mut_string_delete" -> bridge(fields("s", "start", "end")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableString)
                    .delete((args[1] as Long).toInt(), (args[2] as Long).toInt())
                com.mezze.interpreter.values.MezzeRecord.empty()
            }
            "__native_mut_string_char_at" -> bridge(fields("s", "i")) { args, _ ->
                val ch = (args[0] as com.mezze.interpreter.values.MutableString)
                    .charAt((args[1] as Long).toInt())
                if (ch == ' ') com.mezze.interpreter.values.MezzeVariant.none()
                else com.mezze.interpreter.values.MezzeVariant.some(java.lang.String.valueOf(ch))
            }
            "__native_mut_string_set_char_at" -> bridge(fields("s", "i", "c")) { args, _ ->
                val charStr = args[2] as String
                if (charStr.isEmpty()) {
                    throw RuntimeException("__native_mut_string_set_char_at: empty string for char")
                }
                (args[0] as com.mezze.interpreter.values.MutableString)
                    .setCharAt((args[1] as Long).toInt(), charStr[0])
                com.mezze.interpreter.values.MezzeRecord.empty()
            }

            // --- MutChar ---
            "__native_char_to_mut" -> bridge(fields("c")) { args, _ ->
                val s = args[0] as String
                if (s.isEmpty()) throw RuntimeException("__native_char_to_mut: empty string")
                com.mezze.interpreter.values.MutableChar(s[0])
            }
            "__native_char_to_immut" -> bridge(fields("c")) { args, _ ->
                java.lang.String.valueOf((args[0] as com.mezze.interpreter.values.MutableChar).get())
            }
            "__native_mut_char_read" -> bridge(fields("c")) { args, _ ->
                java.lang.String.valueOf((args[0] as com.mezze.interpreter.values.MutableChar).get())
            }
            "__native_mut_char_write" -> bridge(fields("c", "v")) { args, _ ->
                val s = args[1] as String
                if (s.isEmpty()) throw RuntimeException("__native_mut_char_write: empty string")
                (args[0] as com.mezze.interpreter.values.MutableChar).set(s[0])
                com.mezze.interpreter.values.MezzeRecord.empty()
            }
            "__native_mut_char_to_upper" -> bridge(fields("c")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableChar).toUpper()
                com.mezze.interpreter.values.MezzeRecord.empty()
            }
            "__native_mut_char_to_lower" -> bridge(fields("c")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableChar).toLower()
                com.mezze.interpreter.values.MezzeRecord.empty()
            }
            "__native_mut_char_is_digit" -> bridge(fields("c")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableChar).isDigit
            }
            "__native_mut_char_is_letter" -> bridge(fields("c")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableChar).isLetter
            }
            "__native_mut_char_is_whitespace" -> bridge(fields("c")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableChar).isWhitespace
            }

            // --- MutSeq ---
            "__native_seq_to_mut" -> bridge(fields("seq")) { args, _ ->
                com.mezze.interpreter.values.MutableSeq.fromImmutable(
                    args[0] as com.mezze.datastructures.seq.MezzeSeq<*>)
            }
            "__native_seq_to_immut" -> bridge(fields("seq")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableSeq).toImmutable()
            }
            "__native_mut_seq_with_capacity" -> bridge(fields("capacity")) { args, _ ->
                com.mezze.interpreter.values.MutableSeq.withCapacity((args[0] as Long).toInt())
            }
            "__native_mut_seq_sort" -> bridge(fields("seq")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableSeq).sort()
                com.mezze.interpreter.values.MezzeRecord.empty()
            }
            "__native_mut_seq_reverse" -> bridge(fields("seq")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableSeq).reverse()
                com.mezze.interpreter.values.MezzeRecord.empty()
            }
            "__native_mut_seq_fill" -> bridge(fields("seq", "v")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableSeq).fill(args[1])
                com.mezze.interpreter.values.MezzeRecord.empty()
            }
            "__native_mut_seq_fill_range" -> bridge(fields("seq", "start", "end", "v")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableSeq)
                    .fillRange((args[1] as Long).toInt(), (args[2] as Long).toInt(), args[3])
                com.mezze.interpreter.values.MezzeRecord.empty()
            }
            "__native_mut_seq_push" -> bridge(fields("seq", "v")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableSeq).push(args[1])
                com.mezze.interpreter.values.MezzeRecord.empty()
            }
            "__native_mut_seq_pop" -> bridge(fields("seq")) { args, _ ->
                val v = (args[0] as com.mezze.interpreter.values.MutableSeq).pop()
                if (v == null) com.mezze.interpreter.values.MezzeVariant.none()
                else com.mezze.interpreter.values.MezzeVariant.some(v)
            }
            "__native_mut_seq_get" -> bridge(fields("seq", "i")) { args, _ ->
                val v = (args[0] as com.mezze.interpreter.values.MutableSeq)
                    .getSafe((args[1] as Long).toInt())
                if (v == null) com.mezze.interpreter.values.MezzeVariant.none()
                else com.mezze.interpreter.values.MezzeVariant.some(v)
            }
            "__native_mut_seq_set" -> bridge(fields("seq", "i", "v")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableSeq)
                    .setSafe((args[1] as Long).toInt(), args[2])
            }
            "__native_mut_seq_len" -> bridge(fields("seq")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableSeq).size().toLong()
            }
            "__native_mut_seq_clear" -> bridge(fields("seq")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableSeq).clear()
                com.mezze.interpreter.values.MezzeRecord.empty()
            }
            "__native_mut_seq_insert" -> bridge(fields("seq", "i", "v")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableSeq)
                    .insert((args[1] as Long).toInt(), args[2])
            }
            "__native_mut_seq_remove" -> bridge(fields("seq", "i")) { args, _ ->
                val v = (args[0] as com.mezze.interpreter.values.MutableSeq)
                    .remove((args[1] as Long).toInt())
                if (v == null) com.mezze.interpreter.values.MezzeVariant.none()
                else com.mezze.interpreter.values.MezzeVariant.some(v)
            }
            "__native_mut_seq_swap" -> bridge(fields("seq", "i", "j")) { args, _ ->
                try {
                    (args[0] as com.mezze.interpreter.values.MutableSeq)
                        .swap((args[1] as Long).toInt(), (args[2] as Long).toInt())
                    true
                } catch (e: IndexOutOfBoundsException) {
                    false
                }
            }

            // --- MutMap ---
            "__native_map_to_mut" -> bridge(fields("map")) { args, _ ->
                com.mezze.interpreter.values.MutableMap.fromImmutable(
                    args[0] as com.mezze.datastructures.map.MezzeMap<*, *>)
            }
            "__native_map_to_immut" -> bridge(fields("map")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableMap).toImmutable()
            }
            "__native_mut_map_with_capacity" -> bridge(fields("capacity")) { args, _ ->
                com.mezze.interpreter.values.MutableMap.withCapacity((args[0] as Long).toInt())
            }
            "__native_mut_map_get" -> bridge(fields("map", "key")) { args, _ ->
                val v = (args[0] as com.mezze.interpreter.values.MutableMap).get(args[1])
                if (v == null) com.mezze.interpreter.values.MezzeVariant.none()
                else com.mezze.interpreter.values.MezzeVariant.some(v)
            }
            "__native_mut_map_put" -> bridge(fields("map", "key", "value")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableMap).put(args[1], args[2])
                com.mezze.interpreter.values.MezzeRecord.empty()
            }
            "__native_mut_map_remove" -> bridge(fields("map", "key")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableMap).remove(args[1])
                com.mezze.interpreter.values.MezzeRecord.empty()
            }
            "__native_mut_map_contains" -> bridge(fields("map", "key")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableMap).containsKey(args[1])
            }
            "__native_mut_map_size" -> bridge(fields("map")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableMap).size().toLong()
            }
            "__native_mut_map_clear" -> bridge(fields("map")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableMap).clear()
                com.mezze.interpreter.values.MezzeRecord.empty()
            }
            "__native_mut_map_keys" -> bridge(fields("map")) { args, _ ->
                com.mezze.datastructures.seq.MezzeSeq.fromArray(
                    (args[0] as com.mezze.interpreter.values.MutableMap).keys())
            }
            "__native_mut_map_values" -> bridge(fields("map")) { args, _ ->
                com.mezze.datastructures.seq.MezzeSeq.fromArray(
                    (args[0] as com.mezze.interpreter.values.MutableMap).values())
            }
            "__native_mut_map_entries" -> bridge(fields("map")) { args, _ ->
                com.mezze.datastructures.seq.MezzeSeq.fromArray(
                    (args[0] as com.mezze.interpreter.values.MutableMap).entries())
            }

            // --- MutSet ---
            "__native_set_to_mut" -> bridge(fields("set")) { args, _ ->
                com.mezze.interpreter.values.MutableSet.fromImmutable(
                    args[0] as com.mezze.datastructures.set.MezzeSet<*>)
            }
            "__native_set_to_immut" -> bridge(fields("set")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableSet).toImmutable()
            }
            "__native_mut_set_with_capacity" -> bridge(fields("capacity")) { args, _ ->
                com.mezze.interpreter.values.MutableSet.withCapacity((args[0] as Long).toInt())
            }
            "__native_mut_set_add" -> bridge(fields("set", "elem")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableSet).add(args[1])
                com.mezze.interpreter.values.MezzeRecord.empty()
            }
            "__native_mut_set_remove" -> bridge(fields("set", "elem")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableSet).remove(args[1])
                com.mezze.interpreter.values.MezzeRecord.empty()
            }
            "__native_mut_set_contains" -> bridge(fields("set", "elem")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableSet).contains(args[1])
            }
            "__native_mut_set_size" -> bridge(fields("set")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableSet).size().toLong()
            }
            "__native_mut_set_clear" -> bridge(fields("set")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableSet).clear()
                com.mezze.interpreter.values.MezzeRecord.empty()
            }
            "__native_mut_set_elements" -> bridge(fields("set")) { args, _ ->
                com.mezze.datastructures.seq.MezzeSeq.fromArray(
                    (args[0] as com.mezze.interpreter.values.MutableSet).elements())
            }
            "__native_mut_set_toggle" -> bridge(fields("set", "elem")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableSet).toggle(args[1])
            }

            // --- MutBytes ---
            // NOTE: __native_bytes_to_mut accepts multiple input shapes
            // (byte[] / MezzeBytes / BytesNatives.MezzeBytes); kept on the
            // NativeDispatch slow path so the polymorphic acceptance logic
            // doesn't need to be duplicated here.
            "__native_bytes_to_immut" -> bridge(fields("bytes")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableBytes).toImmutable()
            }
            "__native_mut_bytes_get" -> bridge(fields("bytes", "i")) { args, _ ->
                val v = (args[0] as com.mezze.interpreter.values.MutableBytes)
                    .getSafe((args[1] as Long).toInt())
                if (v == -1) com.mezze.interpreter.values.MezzeVariant.none()
                else com.mezze.interpreter.values.MezzeVariant.some(v.toLong())
            }
            "__native_mut_bytes_set" -> bridge(fields("bytes", "i", "v")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableBytes)
                    .setSafe((args[1] as Long).toInt(), (args[2] as Long).toInt())
            }
            "__native_mut_bytes_len" -> bridge(fields("bytes")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableBytes).length().toLong()
            }
            "__native_mut_bytes_fill" -> bridge(fields("bytes", "v")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableBytes).fill((args[1] as Long).toByte())
                com.mezze.interpreter.values.MezzeRecord.empty()
            }
            "__native_mut_bytes_fill_range" -> bridge(fields("bytes", "start", "end", "v")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableBytes)
                    .fillRange(
                        (args[1] as Long).toInt(),
                        (args[2] as Long).toInt(),
                        (args[3] as Long).toByte())
                com.mezze.interpreter.values.MezzeRecord.empty()
            }
            "__native_mut_bytes_slice" -> bridge(fields("bytes", "start", "end")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableBytes)
                    .slice((args[1] as Long).toInt(), (args[2] as Long).toInt())
            }
            "__native_mut_bytes_resize" -> bridge(fields("bytes", "size")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableBytes).resize((args[1] as Long).toInt())
                com.mezze.interpreter.values.MezzeRecord.empty()
            }
            "__native_mut_bytes_swap" -> bridge(fields("bytes", "i", "j")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableBytes)
                    .swap((args[1] as Long).toInt(), (args[2] as Long).toInt())
            }
            "__native_mut_bytes_reverse" -> bridge(fields("bytes")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableBytes).reverse()
                com.mezze.interpreter.values.MezzeRecord.empty()
            }
            "__native_mut_bytes_clear" -> bridge(fields("bytes")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableBytes).clear()
                com.mezze.interpreter.values.MezzeRecord.empty()
            }
            "__native_mut_bytes_copy" -> bridge(
                fields("dest", "src", "src_offset", "dest_offset", "length")
            ) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableBytes).copyFromMutable(
                    args[1] as com.mezze.interpreter.values.MutableBytes,
                    (args[2] as Long).toInt(),
                    (args[3] as Long).toInt(),
                    (args[4] as Long).toInt())
                com.mezze.interpreter.values.MezzeRecord.empty()
            }

            // --- MutDeque ---
            "__native_deque_to_mut" -> bridge(fields("deque")) { args, _ ->
                com.mezze.interpreter.values.MutableDeque.fromImmutable(
                    args[0] as com.mezze.datastructures.deque.MezzeDeque<*>)
            }
            "__native_deque_to_immut" -> bridge(fields("deque")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableDeque).toImmutable()
            }
            "__native_mut_deque_with_capacity" -> bridge(fields("capacity")) { args, _ ->
                com.mezze.interpreter.values.MutableDeque.withCapacity((args[0] as Long).toInt())
            }
            "__native_mut_deque_push_front" -> bridge(fields("deque", "elem")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableDeque).pushFront(args[1])
                com.mezze.interpreter.values.MezzeRecord.empty()
            }
            "__native_mut_deque_push_back" -> bridge(fields("deque", "elem")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableDeque).pushBack(args[1])
                com.mezze.interpreter.values.MezzeRecord.empty()
            }
            "__native_mut_deque_pop_front" -> bridge(fields("deque")) { args, _ ->
                val v = (args[0] as com.mezze.interpreter.values.MutableDeque).popFront()
                if (v == null) com.mezze.interpreter.values.MezzeVariant.none()
                else com.mezze.interpreter.values.MezzeVariant.some(v)
            }
            "__native_mut_deque_pop_back" -> bridge(fields("deque")) { args, _ ->
                val v = (args[0] as com.mezze.interpreter.values.MutableDeque).popBack()
                if (v == null) com.mezze.interpreter.values.MezzeVariant.none()
                else com.mezze.interpreter.values.MezzeVariant.some(v)
            }
            "__native_mut_deque_peek_front" -> bridge(fields("deque")) { args, _ ->
                val v = (args[0] as com.mezze.interpreter.values.MutableDeque).peekFront()
                if (v == null) com.mezze.interpreter.values.MezzeVariant.none()
                else com.mezze.interpreter.values.MezzeVariant.some(v)
            }
            "__native_mut_deque_peek_back" -> bridge(fields("deque")) { args, _ ->
                val v = (args[0] as com.mezze.interpreter.values.MutableDeque).peekBack()
                if (v == null) com.mezze.interpreter.values.MezzeVariant.none()
                else com.mezze.interpreter.values.MezzeVariant.some(v)
            }
            "__native_mut_deque_size" -> bridge(fields("deque")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableDeque).size().toLong()
            }
            "__native_mut_deque_is_empty" -> bridge(fields("deque")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableDeque).isEmpty
            }
            "__native_mut_deque_clear" -> bridge(fields("deque")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableDeque).clear()
                com.mezze.interpreter.values.MezzeRecord.empty()
            }
            "__native_mut_deque_reverse" -> bridge(fields("deque")) { args, _ ->
                (args[0] as com.mezze.interpreter.values.MutableDeque).reverse()
                com.mezze.interpreter.values.MezzeRecord.empty()
            }
            "__native_mut_deque_to_seq" -> bridge(fields("deque")) { args, _ ->
                com.mezze.datastructures.seq.MezzeSeq.fromArray(
                    (args[0] as com.mezze.interpreter.values.MutableDeque).toArray())
            }

            // ==================== Float ====================
            // Skip __native_float_to_str / __native_float_from_string —
            // they go through Double.toString / parsing helpers that benefit
            // less from the DS-Wire and are colder.
            "__native_float_abs" -> bridge(fields("n")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.primitives.PrimitiveNatives
                    .floatAbs(args[0] as Double)
            }
            "__native_float_sqrt" -> bridge(fields("n")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.primitives.PrimitiveNatives
                    .floatSqrt(args[0] as Double)
            }
            "__native_float_floor" -> bridge(fields("n")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.primitives.PrimitiveNatives
                    .floatFloor(args[0] as Double)
            }
            "__native_float_ceil" -> bridge(fields("n")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.primitives.PrimitiveNatives
                    .floatCeil(args[0] as Double)
            }
            "__native_float_round" -> bridge(fields("n")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.primitives.PrimitiveNatives
                    .floatRound(args[0] as Double).toDouble()
            }
            "__native_float_sign" -> bridge(fields("n")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.primitives.PrimitiveNatives
                    .floatSignum(args[0] as Double)
            }
            "__native_float_neg" -> bridge(fields("n")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.primitives.PrimitiveNatives
                    .floatNegate(args[0] as Double)
            }
            "__native_float_exp" -> bridge(fields("n")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.primitives.PrimitiveNatives
                    .floatExp(args[0] as Double)
            }
            "__native_float_ln" -> bridge(fields("n")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.primitives.PrimitiveNatives
                    .floatLog(args[0] as Double)
            }
            "__native_float_log10" -> bridge(fields("n")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.primitives.PrimitiveNatives
                    .floatLog10(args[0] as Double)
            }
            "__native_float_sin" -> bridge(fields("n")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.primitives.PrimitiveNatives
                    .floatSin(args[0] as Double)
            }
            "__native_float_cos" -> bridge(fields("n")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.primitives.PrimitiveNatives
                    .floatCos(args[0] as Double)
            }
            "__native_float_tan" -> bridge(fields("n")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.primitives.PrimitiveNatives
                    .floatTan(args[0] as Double)
            }
            "__native_float_asin" -> bridge(fields("n")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.primitives.PrimitiveNatives
                    .floatAsin(args[0] as Double)
            }
            "__native_float_acos" -> bridge(fields("n")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.primitives.PrimitiveNatives
                    .floatAcos(args[0] as Double)
            }
            "__native_float_atan" -> bridge(fields("n")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.primitives.PrimitiveNatives
                    .floatAtan(args[0] as Double)
            }
            "__native_float_atan2" -> bridge(fields("y", "x")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.primitives.PrimitiveNatives
                    .floatAtan2(args[0] as Double, args[1] as Double)
            }
            "__native_float_is_nan" -> bridge(fields("n")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.primitives.PrimitiveNatives
                    .floatIsNaN(args[0] as Double)
            }
            "__native_float_is_infinite" -> bridge(fields("n")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.primitives.PrimitiveNatives
                    .floatIsInfinite(args[0] as Double)
            }
            "__native_float_is_finite" -> bridge(fields("n")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.primitives.PrimitiveNatives
                    .floatIsFinite(args[0] as Double)
            }
            "__native_float_add" -> bridge(fields("l", "r")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.primitives.PrimitiveNatives
                    .floatAdd(args[0] as Double, args[1] as Double)
            }
            "__native_float_subtract" -> bridge(fields("l", "r")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.primitives.PrimitiveNatives
                    .floatSubtract(args[0] as Double, args[1] as Double)
            }
            "__native_float_multiply" -> bridge(fields("l", "r")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.primitives.PrimitiveNatives
                    .floatMultiply(args[0] as Double, args[1] as Double)
            }
            "__native_float_divide" -> bridge(fields("l", "r")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.primitives.PrimitiveNatives
                    .floatDivide(args[0] as Double, args[1] as Double)
            }
            "__native_float_mod" -> bridge(fields("l", "r")) { args, _ ->
                (args[0] as Double) % (args[1] as Double)
            }
            "__native_float_min" -> bridge(fields("l", "r")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.primitives.PrimitiveNatives
                    .floatMin(args[0] as Double, args[1] as Double)
            }
            "__native_float_max" -> bridge(fields("l", "r")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.primitives.PrimitiveNatives
                    .floatMax(args[0] as Double, args[1] as Double)
            }
            "__native_float_pow" -> bridge(fields("base", "exp")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.primitives.PrimitiveNatives
                    .floatPow(args[0] as Double, args[1] as Double)
            }
            "__native_float_clamp" -> bridge(fields("n", "min", "max")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.primitives.PrimitiveNatives
                    .floatClamp(args[0] as Double, args[1] as Double, args[2] as Double)
            }
            "__native_float_eq" -> bridge(fields("l", "r")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.primitives.PrimitiveNatives
                    .floatEq(args[0] as Double, args[1] as Double)
            }
            "__native_float_neq" -> bridge(fields("l", "r")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.primitives.PrimitiveNatives
                    .floatNeq(args[0] as Double, args[1] as Double)
            }
            "__native_float_lt" -> bridge(fields("l", "r")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.primitives.PrimitiveNatives
                    .floatLt(args[0] as Double, args[1] as Double)
            }
            "__native_float_le" -> bridge(fields("l", "r")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.primitives.PrimitiveNatives
                    .floatLte(args[0] as Double, args[1] as Double)
            }
            "__native_float_gt" -> bridge(fields("l", "r")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.primitives.PrimitiveNatives
                    .floatGt(args[0] as Double, args[1] as Double)
            }
            "__native_float_ge" -> bridge(fields("l", "r")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.primitives.PrimitiveNatives
                    .floatGte(args[0] as Double, args[1] as Double)
            }
            "__native_float_from_int" -> bridge(fields("n")) { args, _ ->
                (args[0] as Long).toDouble()
            }
            "__native_float_from_nat" -> bridge(fields("n")) { args, _ ->
                (args[0] as Long).toDouble()
            }

            // ==================== Bool ====================
            "__native_bool_and" -> bridge(fields("l", "r")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.primitives.PrimitiveNatives
                    .boolAnd(args[0] as Boolean, args[1] as Boolean)
            }
            "__native_bool_or" -> bridge(fields("l", "r")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.primitives.PrimitiveNatives
                    .boolOr(args[0] as Boolean, args[1] as Boolean)
            }
            "__native_bool_xor" -> bridge(fields("l", "r")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.primitives.PrimitiveNatives
                    .boolXor(args[0] as Boolean, args[1] as Boolean)
            }
            "__native_bool_not" -> bridge(fields("b")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.primitives.PrimitiveNatives
                    .boolNot(args[0] as Boolean)
            }
            "__native_bool_eq" -> bridge(fields("l", "r")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.primitives.PrimitiveNatives
                    .boolEq(args[0] as Boolean, args[1] as Boolean)
            }
            "__native_bool_neq" -> bridge(fields("l", "r")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.primitives.PrimitiveNatives
                    .boolNeq(args[0] as Boolean, args[1] as Boolean)
            }
            "__native_bool_to_str" -> bridge(fields("b")) { args, _ ->
                (args[0] as Boolean).toString()
            }

            // ==================== Char ====================
            // Mezze char is a 1-char String at runtime — CharNatives takes String.
            "__native_char_is_letter" -> bridge(fields("c")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.primitives.CharNatives
                    .isLetter(args[0] as String)
            }
            "__native_char_is_digit" -> bridge(fields("c")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.primitives.CharNatives
                    .isDigit(args[0] as String)
            }
            "__native_char_is_alpha_numeric" -> bridge(fields("c")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.primitives.CharNatives
                    .isAlphaNumeric(args[0] as String)
            }
            "__native_char_is_whitespace" -> bridge(fields("c")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.primitives.CharNatives
                    .isWhitespace(args[0] as String)
            }
            "__native_char_is_upper" -> bridge(fields("c")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.primitives.CharNatives
                    .isUpper(args[0] as String)
            }
            "__native_char_is_lower" -> bridge(fields("c")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.primitives.CharNatives
                    .isLower(args[0] as String)
            }
            "__native_char_to_upper" -> bridge(fields("c")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.primitives.CharNatives
                    .toUpper(args[0] as String)
            }
            "__native_char_to_lower" -> bridge(fields("c")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.primitives.CharNatives
                    .toLower(args[0] as String)
            }
            "__native_char_to_int" -> bridge(fields("c")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.primitives.CharNatives
                    .toInt(args[0] as String)
            }
            "__native_char_from_int" -> bridge(fields("code")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.primitives.CharNatives
                    .fromInt(args[0] as Long)
            }
            "__native_char_to_str" -> bridge(fields("c")) { args, _ ->
                args[0] as String
            }
            "__native_char_eq" -> bridge(fields("a", "b")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.primitives.CharNatives
                    .eq(args[0] as String, args[1] as String)
            }
            "__native_char_compare" -> bridge(fields("a", "b")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.primitives.CharNatives
                    .compare(args[0] as String, args[1] as String)
            }
            "__native_char_compare_int" -> bridge(fields("a", "b")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.primitives.CharNatives
                    .compareInt(args[0] as String, args[1] as String)
            }

            // ==================== String (hot ones) ====================
            "__native_string_size" -> bridge(fields("s")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.primitives.PrimitiveNatives
                    .stringLength(args[0] as String).toLong()
            }
            "__native_string_concat" -> bridge(fields("l", "r")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.primitives.PrimitiveNatives
                    .stringConcat(args[0] as String, args[1] as String)
            }
            "__native_string_eq" -> bridge(fields("l", "r")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.primitives.PrimitiveNatives
                    .strEq(args[0] as String, args[1] as String)
            }
            "__native_string_neq" -> bridge(fields("l", "r")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.primitives.PrimitiveNatives
                    .strNeq(args[0] as String, args[1] as String)
            }
            "__native_string_lt" -> bridge(fields("l", "r")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.primitives.PrimitiveNatives
                    .strLt(args[0] as String, args[1] as String)
            }
            "__native_string_le" -> bridge(fields("l", "r")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.primitives.PrimitiveNatives
                    .strLte(args[0] as String, args[1] as String)
            }
            "__native_string_gt" -> bridge(fields("l", "r")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.primitives.PrimitiveNatives
                    .strGt(args[0] as String, args[1] as String)
            }
            "__native_string_ge" -> bridge(fields("l", "r")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.primitives.PrimitiveNatives
                    .strGte(args[0] as String, args[1] as String)
            }
            "__native_string_starts_with" -> bridge(fields("s", "prefix")) { args, _ ->
                (args[0] as String).startsWith(args[1] as String)
            }
            "__native_string_ends_with" -> bridge(fields("s", "suffix")) { args, _ ->
                (args[0] as String).endsWith(args[1] as String)
            }
            "__native_string_contains" -> bridge(fields("s", "substring")) { args, _ ->
                (args[0] as String).contains(args[1] as String)
            }

            // ==================== Int (hot ones) ====================
            // Most int ops are already lowered to InlineBinaryOpIR (+, -, *, /, ==, <, >, etc),
            // but a few stdlib utilities still go through the native call path.
            "__native_int_abs" -> bridge(fields("n")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.primitives.PrimitiveNatives
                    .intAbs(args[0] as Long)
            }
            "__native_int_min" -> bridge(fields("l", "r")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.primitives.PrimitiveNatives
                    .intMin(args[0] as Long, args[1] as Long)
            }
            "__native_int_max" -> bridge(fields("l", "r")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.primitives.PrimitiveNatives
                    .intMax(args[0] as Long, args[1] as Long)
            }
            "__native_int_clamp" -> bridge(fields("n", "min", "max")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.primitives.PrimitiveNatives
                    .intClamp(args[0] as Long, args[1] as Long, args[2] as Long)
            }
            "__native_int_sign" -> bridge(fields("n")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.primitives.PrimitiveNatives
                    .intSign(args[0] as Long)
            }
            "__native_int_is_even" -> bridge(fields("n")) { args, _ ->
                (args[0] as Long) % 2L == 0L
            }
            "__native_int_is_odd" -> bridge(fields("n")) { args, _ ->
                (args[0] as Long) % 2L != 0L
            }
            "__native_int_to_float" -> bridge(fields("n")) { args, _ ->
                com.mezze.interpreter.nodes.builtin.primitives.PrimitiveNatives
                    .intToFloat(args[0] as Long)
            }
            "__native_int_from_nat" -> bridge(fields("n")) { args, _ ->
                args[0] as Long
            }

            else -> null
        }
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

    /** Public accessor for building a SourceSection — used by PipelineRunner to
     *  attribute the entry root node so the debugger doesn't treat it as
     *  internal (isInternal() = section == null → the debugger skips the whole
     *  root and never fires probes inside it). */
    fun createSourceSectionPublic(source: Source, info: SourceInfo): SourceSection? =
        createSourceSection(source, info)

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
            // For a real on-disk file, build from the File so the Source carries
            // a `file://` URI — the debugger matches a client breakpoint (also a
            // `file://` URI) to a source section by URI, so the schemes MUST
            // agree. The `(lang, content, name)` overload instead yields a
            // content-hashed `truffle:<hash>//path` URI that never matches.
            val f = java.io.File(file)
            val override = sourceContentOverrides[file]
            // Content: caller override, else the file's text, else empty.
            val content = override
                ?: try { f.readText() } catch (_: Exception) { "" }
            val builder = Source.newBuilder("mezze", content, file)
            // For a real on-disk file, stamp the `file://` URI explicitly so the
            // debugger can match a client breakpoint (also a `file://` URI) to a
            // source section — the default `(lang, content, name)` URI is a
            // content-hashed `truffle:<hash>//path` that never matches.
            if (f.isFile) {
                try { builder.uri(f.toURI()) } catch (_: Exception) { /* keep default */ }
            }
            builder.build()
        }
    }
}
