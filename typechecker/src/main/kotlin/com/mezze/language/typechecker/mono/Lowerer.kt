package com.mezze.language.typechecker.mono

import com.mezze.language.resolvedast.*
import com.mezze.language.truffleir.*
import com.mezze.language.typedast.Hash

/**
 * Lowerer — converts ResolvedAST to TruffleIR.
 *
 * This is the final compilation stage:
 * - Loc → compact SourceInfo (file index instead of file path)
 * - Produces the serializable form stored in SQLite blobs
 * - This is what TruffleCompiler reads to build Truffle AST nodes
 */
class Lowerer {

    /** Counter for allocating temp local slots for nested pattern extraction */
    private var nextTempSlot = 0
    /** Capture count for current function — used to offset param slots in frame */
    private var currentCaptureCount = 0

    fun lower(resolvedModule: ResolvedModule): LoweredModule {
        val fileTable = buildFileTable(resolvedModule)
        val functions = resolvedModule.functions.map { fn ->
            lowerFunction(fn, fileTable)
        }
        // Include effect handler operations as standalone functions
        val handlerFunctions = resolvedModule.effectHandlers.flatMap { handler ->
            handler.operations.map { opFn ->
                lowerFunction(opFn, fileTable)
            }
        }
        return LoweredModule(
            fileTable = fileTable,
            functions = functions + handlerFunctions
        )
    }

    data class LoweredModule(
        val fileTable: FileTable,
        val functions: List<FunctionIR>
    )

    private fun lowerFunction(fn: ResolvedFunction, fileTable: FileTable): FunctionIR {
        val fileIndex = fileTable.files.indexOf(fn.loc.file).coerceAtLeast(0)
        // Reset temp slot counter — locals start after fn.locals, temps are appended
        nextTempSlot = fn.locals.size
        currentCaptureCount = fn.captures.size
        val body = lowerExpr(fn.body, fileTable)
        // Collect extra temp slots that were allocated during lowering
        val extraTempCount = nextTempSlot - fn.locals.size
        val allLocalSlots = fn.locals.map { it.kind } + List(extraTempCount) { SlotKind.OBJECT }
        val allLocalNames = fn.locals.map { it.name } + List(extraTempCount) { "__temp_$it" }

        return FunctionIR(
            hash = fn.hash,
            name = fn.name,
            qualifiedName = fn.qualifiedName,
            paramSlots = fn.params.map { it.kind },
            paramNames = fn.params.map { it.name },
            localSlots = allLocalSlots,
            localNames = allLocalNames,
            captureSlots = fn.captures.map { it.kind },
            captureNames = fn.captures.map { it.name },
            body = body,
            sourceFile = fn.loc.file,
            sourceSection = locToSourceInfo(fn.loc, fileTable),
            // ResolvedFunction doesn't carry type info (types are erased before Resolver).
            // returnType populated when Resolver preserves types (future work).
            returnType = null
        )
    }

    /** Allocate a temp local slot (used for nested pattern extraction) */
    private fun allocTempSlot(): Int = nextTempSlot++

    // NOTE: typeTag on NodeIR variants (CallDirectIR, ReadLocalIR, ReadArgIR,
    // MakeRecordIR, MakeVariantIR, MakeLambdaIR) is left as null here because
    // ResolvedExpr doesn't carry type information — types are erased before the
    // Resolver stage. typeTag populated when Resolver preserves types (future work).
    private fun lowerExpr(expr: ResolvedExpr, ft: FileTable): NodeIR {
        val src = locToSourceInfo(expr.loc, ft)

        return when (expr) {
            is ResolvedIntLit -> LongLitIR(expr.value, src)
            is ResolvedNatLit -> LongLitIR(expr.value, src)
            is ResolvedFloatLit -> DoubleLitIR(expr.value, src)
            is ResolvedFracLit -> FracLitIR(expr.numerator, expr.denominator, src)
            is ResolvedBoolLit -> BoolLitIR(expr.value, src)
            is ResolvedCharLit -> CharLitIR(expr.value, src)
            is ResolvedUnitLit -> UnitLitIR(src)
            is ResolvedStringLit -> {
                if (expr.parts.size == 1 && expr.parts[0] is ResolvedStringPart.Literal) {
                    StringLitIR((expr.parts[0] as ResolvedStringPart.Literal).value, src)
                } else {
                    InterpolatedStringIR(
                        expr.parts.map { p ->
                            when (p) {
                                is ResolvedStringPart.Literal -> StringPartIR.Lit(p.value)
                                is ResolvedStringPart.Interpolation -> StringPartIR.Expr(lowerExpr(p.expr, ft))
                            }
                        },
                        src
                    )
                }
            }

            is ResolvedReadLocal -> ReadLocalIR(expr.slot, expr.kind, src)
            is ResolvedWriteLocal -> WriteLocalIR(expr.slot, expr.kind, lowerExpr(expr.value, ft), src)
            // Params are unpacked from record arg into frame slots by MezzeFunctionRootNode.
            // Frame layout: params(0..P-1), locals(P..P+L-1), captures(P+L..P+L+C-1).
            // For lambdas with captures, MezzeFunctionRootNode writes captures first then params,
            // but buildFrameDescriptor puts params first. With 0 captures these are the same.
            is ResolvedReadParam -> ReadLocalIR(expr.index, expr.kind, src)
            is ResolvedReadCapture -> ReadCaptureIR(expr.index, expr.kind, src)

            is ResolvedCallDirect -> CallDirectIR(expr.targetHash, lowerExpr(expr.arg, ft), expr.isTailCall, src)
            is ResolvedCallLocal -> CallLocalIR(expr.slot, lowerExpr(expr.arg, ft), expr.isTailCall, src)
            is ResolvedCallNative -> CallNativeIR(expr.name, lowerExpr(expr.arg, ft), src)
            is ResolvedCallIndirect -> CallIndirectIR(lowerExpr(expr.function, ft), lowerExpr(expr.arg, ft), expr.isTailCall, src)

            is ResolvedMakeRecord -> MakeRecordIR(
                expr.fields.map { f -> FieldIR(f.name, lowerExpr(f.value, ft)) },
                src
            )
            is ResolvedFieldAccess -> ReadFieldIR(lowerExpr(expr.record, ft), expr.fieldName, expr.fieldIndex, src)
            is ResolvedRecordUpdate -> RecordUpdateIR(
                lowerExpr(expr.record, ft),
                expr.updates.map { f -> FieldIR(f.name, lowerExpr(f.value, ft)) },
                src
            )

            is ResolvedMakeVariant -> MakeVariantIR(
                expr.tag, expr.tagName,
                expr.payload?.let { lowerExpr(it, ft) },
                src
            )

            is ResolvedMatch -> {
                MatchIR(
                lowerExpr(expr.scrutinee, ft),
                expr.cases.map { c ->
                    val extractions = mutableListOf<StmtIR>()
                    val pattern = lowerPattern(c.pattern, ft, extractions)
                    var body = lowerExpr(c.body, ft)
                    var guard = c.guard?.let { lowerExpr(it, ft) }

                    // If there are nested pattern extractions, wrap guard and body
                    if (extractions.isNotEmpty()) {
                        val extractionSrc = locToSourceInfo(c.loc, ft)
                        if (guard != null) {
                            // Extractions must run BEFORE the guard
                            val guardStmts = extractions.toList() + listOf(StmtIR.Expr(guard, extractionSrc))
                            guard = BlockIR(guardStmts.dropLast(1), guardStmts.last().let {
                                (it as StmtIR.Expr).node
                            }, extractionSrc)
                        }
                        // Wrap body: extractions + body
                        body = BlockIR(extractions.toList(), body, extractionSrc)
                    }

                    CaseIR(
                        pattern = pattern,
                        bindings = c.slotBindings.map { b -> BindingIR(b.slot, b.kind) },
                        guard = guard,
                        body = body,
                        src = locToSourceInfo(c.loc, ft)
                    )
                },
                src
            )
            }

            is ResolvedIf -> IfIR(
                lowerExpr(expr.cond, ft),
                lowerExpr(expr.thenBr, ft),
                lowerExpr(expr.elseBr, ft),
                src
            )

            is ResolvedBlock -> BlockIR(
                expr.stmts.map { stmt ->
                    when (stmt) {
                        is ResolvedStmt.Let -> StmtIR.Let(stmt.slot, stmt.kind, stmt.name, stmt.contentHash, lowerExpr(stmt.value, ft), locToSourceInfo(stmt.loc, ft))
                        is ResolvedStmt.Expr -> StmtIR.Expr(lowerExpr(stmt.expr, ft), locToSourceInfo(stmt.loc, ft))
                    }
                },
                lowerExpr(expr.result, ft),
                src
            )

            is ResolvedMakeLambda -> MakeLambdaIR(
                expr.functionHash,
                expr.captures.map { c -> CaptureIR(c.slot, c.kind) },
                src
            )

            is ResolvedMakeSeq -> MakeSeqIR(
                expr.elements.map { elem ->
                    when (elem) {
                        is ResolvedSeqElement.Regular -> SeqElementIR.Regular(lowerExpr(elem.expr, ft))
                        is ResolvedSeqElement.Spread -> SeqElementIR.Spread(lowerExpr(elem.expr, ft))
                    }
                },
                src
            )
            is ResolvedSeqGet -> SeqGetIR(lowerExpr(expr.seq, ft), lowerExpr(expr.index, ft), src)

            is ResolvedPerform -> HandleIR(
                lowerExpr(expr.body, ft),
                expr.handlerBindings.map { h ->
                    HandlerBindIR(h.handlerHash, h.effectHash, h.performerName, h.params?.let { lowerExpr(it, ft) })
                },
                src
            )

            is ResolvedPerformOp -> PerformOpIR(expr.effectHash, expr.operationName, lowerExpr(expr.arg, ft), src)
        }
    }

    // ========================================================================
    // Helpers
    // ========================================================================

    /**
     * Lower a pattern, collecting extraction statements for nested record patterns.
     *
     * For nested patterns like `Some { val = { x = px, y = py } }`, we:
     * 1. Allocate a temp slot for the intermediate record (`val`)
     * 2. Add extraction statements: `tempSlot.x → slot_for_px`, `tempSlot.y → slot_for_py`
     * 3. Return PatternIR with the temp slot as the field slot
     */
    private fun lowerPattern(pattern: ResolvedPattern, ft: FileTable, extractions: MutableList<StmtIR>): PatternIR {
        return when (pattern) {
            is ResolvedWildcardPattern -> PatternIR.Wildcard()
            is ResolvedVarPattern -> PatternIR.Wildcard(pattern.slot) // Var patterns are wildcards that bind
            is ResolvedLiteralPattern -> PatternIR.Literal(lowerExpr(pattern.value, ft))
            is ResolvedVariantPattern -> {
                val fieldNames = mutableListOf<String>()
                val fieldSlots = mutableListOf<Int>()
                pattern.payload?.let { p ->
                    when (p) {
                        is ResolvedRecordPattern -> {
                            for (f in p.fields) {
                                fieldNames.add(f.fieldName)
                                val slot = resolveFieldSlot(f, ft, extractions)
                                fieldSlots.add(slot)
                            }
                        }
                        is ResolvedVarPattern -> fieldSlots.add(p.slot)
                        else -> {}
                    }
                }
                PatternIR.Variant(pattern.tag, "", fieldNames, fieldSlots)
            }
            is ResolvedRecordPattern -> {
                val names = mutableListOf<String>()
                val slots = mutableListOf<Int>()
                for (f in pattern.fields) {
                    names.add(f.fieldName)
                    slots.add(resolveFieldSlot(f, ft, extractions))
                }
                PatternIR.Record(names, slots, pattern.isOpen)
            }
            is ResolvedSeqPattern -> {
                // Seq pattern [x, y, ...rest]: check length and bind elements
                val elementSlots = pattern.elements.map { elem ->
                    when (elem) {
                        is ResolvedVarPattern -> elem.slot
                        is ResolvedWildcardPattern -> -1
                        else -> -1
                    }
                }
                PatternIR.Seq(elementSlots, pattern.restSlot ?: -1)
            }
        }
    }

    /**
     * Resolve a pattern field to a slot index, handling nested record patterns.
     * - VarPattern: return the var's slot directly
     * - RecordPattern (nested): allocate a temp slot, generate extraction code
     * - null pattern (punning): the field was already resolved to a VarPattern by Resolver
     */
    private fun resolveFieldSlot(
        field: ResolvedPatternField,
        ft: FileTable,
        extractions: MutableList<StmtIR>
    ): Int {
        val subPattern = field.pattern
        return when (subPattern) {
            is ResolvedVarPattern -> subPattern.slot
            is ResolvedRecordPattern -> {
                // Nested record pattern: allocate temp slot, generate extraction code
                val tempSlot = allocTempSlot()
                val dummySrc = SourceInfo(0, 0, 0, 0, 0)
                // For each field in the nested record, generate: tempSlot.fieldName → targetSlot
                emitNestedRecordExtractions(tempSlot, subPattern, ft, extractions)
                tempSlot
            }
            else -> -1
        }
    }

    /**
     * Emit extraction statements for a nested record pattern.
     * Reads each field from the temp slot's record and writes to the target slot.
     * Handles arbitrarily deep nesting via recursion.
     */
    private fun emitNestedRecordExtractions(
        tempSlot: Int,
        recordPattern: ResolvedRecordPattern,
        ft: FileTable,
        extractions: MutableList<StmtIR>
    ) {
        val dummySrc = SourceInfo(0, 0, 0, 0, 0)
        for (f in recordPattern.fields) {
            val fieldAccess = ReadFieldIR(ReadLocalIR(tempSlot, SlotKind.OBJECT, dummySrc), f.fieldName, -1, dummySrc)
            when (val fp = f.pattern) {
                is ResolvedVarPattern -> {
                    // Write field value directly to the binding slot
                    extractions.add(StmtIR.Let(fp.slot, fp.kind, f.fieldName,
                        Hash.ZERO, fieldAccess, dummySrc))
                }
                is ResolvedRecordPattern -> {
                    // Doubly nested: allocate another temp slot
                    val innerTempSlot = allocTempSlot()
                    extractions.add(StmtIR.Let(innerTempSlot, SlotKind.OBJECT, "__nested_${f.fieldName}",
                        Hash.ZERO, fieldAccess, dummySrc))
                    emitNestedRecordExtractions(innerTempSlot, fp, ft, extractions)
                }
                else -> {} // Skip unsupported patterns
            }
        }
    }

    private fun buildFileTable(module: ResolvedModule): FileTable {
        val files = mutableSetOf<String>()
        for (fn in module.functions) {
            files.add(fn.loc.file)
        }
        return FileTable(files.toList())
    }

    private fun locToSourceInfo(loc: com.mezze.language.parsedast.Loc, ft: FileTable): SourceInfo {
        val fileIndex = ft.files.indexOf(loc.file).coerceAtLeast(0)
        return SourceInfo(fileIndex, loc.startLine, loc.startCol, loc.endLine, loc.endCol)
    }
}
