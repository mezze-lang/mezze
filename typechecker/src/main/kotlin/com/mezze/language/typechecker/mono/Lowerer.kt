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

    fun lower(resolvedModule: ResolvedModule): LoweredModule {
        val fileTable = buildFileTable(resolvedModule)
        val functions = resolvedModule.functions.map { fn ->
            lowerFunction(fn, fileTable)
        }
        return LoweredModule(
            fileTable = fileTable,
            functions = functions
        )
    }

    data class LoweredModule(
        val fileTable: FileTable,
        val functions: List<FunctionIR>
    )

    private fun lowerFunction(fn: ResolvedFunction, fileTable: FileTable): FunctionIR {
        val fileIndex = fileTable.files.indexOf(fn.loc.file).coerceAtLeast(0)
        val body = lowerExpr(fn.body, fileTable)

        return FunctionIR(
            hash = fn.hash,
            name = fn.name,
            qualifiedName = fn.qualifiedName,
            paramSlots = fn.params.map { it.kind },
            paramNames = fn.params.map { it.name },
            localSlots = fn.locals.map { it.kind },
            localNames = fn.locals.map { it.name },
            captureSlots = fn.captures.map { it.kind },
            captureNames = fn.captures.map { it.name },
            body = body,
            sourceFile = fn.loc.file,
            sourceSection = locToSourceInfo(fn.loc, fileTable)
        )
    }

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
            is ResolvedReadParam -> ReadArgIR(expr.index, expr.kind, src)
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

            is ResolvedMatch -> MatchIR(
                lowerExpr(expr.scrutinee, ft),
                expr.cases.map { c ->
                    CaseIR(
                        pattern = lowerPattern(c.pattern, ft),
                        bindings = c.slotBindings.map { b -> BindingIR(b.slot, b.kind) },
                        guard = c.guard?.let { lowerExpr(it, ft) },
                        body = lowerExpr(c.body, ft),
                        src = locToSourceInfo(c.loc, ft)
                    )
                },
                src
            )

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

    private fun lowerPattern(pattern: ResolvedPattern, ft: FileTable): PatternIR {
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
                                val slot = (f.pattern as? ResolvedVarPattern)?.slot ?: -1
                                if (slot >= 0) fieldSlots.add(slot)
                            }
                        }
                        is ResolvedVarPattern -> fieldSlots.add(p.slot)
                        else -> {}
                    }
                }
                PatternIR.Variant(pattern.tag, "", fieldNames, fieldSlots)
            }
            is ResolvedRecordPattern -> PatternIR.Record(
                pattern.fields.map { "field" },
                pattern.fields.mapNotNull { f -> (f.pattern as? ResolvedVarPattern)?.slot },
                pattern.isOpen
            )
            is ResolvedSeqPattern -> PatternIR.Wildcard() // TODO
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
