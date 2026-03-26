package com.mezze.language.typechecker.mono

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.MezzeType.*
import com.mezze.language.monoast.*
import com.mezze.language.parsedast.Loc
import com.mezze.language.resolvedast.*
import com.mezze.language.typedast.Hash
import java.security.MessageDigest

/**
 * Resolver — converts MonoAST to ResolvedAST.
 *
 * After this stage:
 * - TYPES ARE GONE (only SlotKind remains)
 * - Frame slots assigned for all locals, params, captures
 * - Variant tags are integers
 * - Field access by index, not name
 * - Everything referenced by hash
 * - Names kept only for debug info
 */
class Resolver {

    /** Lambda bodies extracted during resolution */
    private val extractedLambdas = mutableListOf<ResolvedFunction>()

    fun resolve(monoModule: MonoModule): ResolvedModule {
        extractedLambdas.clear()
        val resolvedFunctions = monoModule.functions.map { fn ->
            resolveFunction(fn)
        }

        val resolvedHandlers = monoModule.performerDecls.map { pd ->
            ResolvedEffectHandler(
                hash = pd.hash,
                effectHash = pd.effectHash,
                performerHash = pd.hash,
                params = emptyList(),
                operations = pd.operations.values.mapIndexed { idx, body ->
                    val ctx = SlotAllocator()
                    val resolvedBody = resolveExpr(body, ctx)
                    ResolvedFunction(
                        hash = computeHash("${pd.hash.hex}_op_$idx"),
                        name = "handler_op_$idx",
                        qualifiedName = "${pd.name}::op_$idx",
                        params = ctx.getParams(),
                        locals = ctx.getLocals(),
                        captures = emptyList(),
                        body = resolvedBody,
                        isPublic = false,
                        loc = body.loc
                    )
                },
                loc = pd.loc
            )
        }

        return ResolvedModule(
            path = monoModule.path,
            rootHash = monoModule.hash,
            functions = resolvedFunctions + extractedLambdas,
            effectHandlers = resolvedHandlers
        )
    }

    private fun resolveFunction(fn: MonoFunction): ResolvedFunction {
        val ctx = SlotAllocator()

        // Allocate param slots
        for (param in fn.params) {
            ctx.allocParam(param.name, typeToSlotKind(param.type))
        }

        val resolvedBody = resolveExpr(fn.body, ctx)

        return ResolvedFunction(
            hash = fn.hash,
            name = fn.name,
            qualifiedName = fn.name, // Would be module::name in full impl
            params = ctx.getParams(),
            locals = ctx.getLocals(),
            captures = emptyList(), // TODO: handle captures
            body = resolvedBody,
            isPublic = fn.isPublic,
            loc = fn.loc
        )
    }

    private fun resolveExpr(expr: MonoExpr, ctx: SlotAllocator): ResolvedExpr {
        val hash = computeExprHash(expr)
        val loc = expr.loc

        return when (expr) {
            is MonoIntLit -> ResolvedIntLit(expr.value, hash, loc)
            is MonoNatLit -> ResolvedNatLit(expr.value, hash, loc)
            is MonoFloatLit -> ResolvedFloatLit(expr.value, hash, loc)
            is MonoFracLit -> ResolvedFracLit(expr.numerator, expr.denominator, hash, loc)
            is MonoBoolLit -> ResolvedBoolLit(expr.value, hash, loc)
            is MonoCharLit -> ResolvedCharLit(expr.value, hash, loc)
            is MonoStringLit -> ResolvedStringLit(
                expr.parts.map { p ->
                    when (p) {
                        is MonoStringPart.Literal -> ResolvedStringPart.Literal(p.value)
                        is MonoStringPart.Interpolation -> ResolvedStringPart.Interpolation(resolveExpr(p.expr, ctx))
                    }
                },
                hash, loc
            )

            is MonoVar -> {
                when (val binding = expr.binding) {
                    is MonoBinding.Local -> {
                        val kind = typeToSlotKind(expr.type)
                        val slot = ctx.getOrAllocLocal(expr.name, kind)
                        ResolvedReadLocal(slot, kind, hash, loc)
                    }
                    is MonoBinding.Param -> {
                        val kind = typeToSlotKind(expr.type)
                        ResolvedReadParam(binding.index, kind, hash, loc)
                    }
                    is MonoBinding.Capture -> {
                        val kind = typeToSlotKind(expr.type)
                        ResolvedReadCapture(binding.index, kind, hash, loc)
                    }
                    is MonoBinding.Function -> {
                        when (val ref = binding.ref) {
                            is MonoFunctionRef.ByHash -> ResolvedCallDirect(ref.hash, ResolvedUnitLit(hash, loc), false, hash, loc)
                            is MonoFunctionRef.Native -> ResolvedCallNative(ref.name, ResolvedUnitLit(hash, loc), hash, loc)
                        }
                    }
                }
            }

            is MonoCall -> {
                val resolvedArg = resolveExpr(expr.arg, ctx)
                val fn = expr.function
                // Debug removed
                when {
                    fn is MonoVar && fn.binding is MonoBinding.Function -> {
                        val ref = (fn.binding as MonoBinding.Function).ref
                        when (ref) {
                            is MonoFunctionRef.ByHash -> ResolvedCallDirect(ref.hash, resolvedArg, expr.isTailCall, hash, loc)
                            is MonoFunctionRef.Native -> ResolvedCallNative(ref.name, resolvedArg, hash, loc)
                        }
                    }
                    else -> {
                        // Indirect call — evaluate function expression then invoke
                        val resolvedFn = resolveExpr(expr.function, ctx)
                        ResolvedCallIndirect(resolvedFn, resolvedArg, expr.isTailCall, hash, loc)
                    }
                }
            }

            is MonoLambda -> {
                val innerCtx = SlotAllocator()
                for (param in expr.params) {
                    innerCtx.allocParam(param.name, typeToSlotKind(param.type))
                }
                val resolvedBody = resolveExpr(expr.body, innerCtx)
                val fnHash = computeHash("lambda_${hash.hex}")

                // Extract lambda body as a separate ResolvedFunction
                extractedLambdas.add(ResolvedFunction(
                    hash = fnHash,
                    name = "lambda",
                    qualifiedName = "lambda",
                    params = innerCtx.getParams(),
                    locals = innerCtx.getLocals(),
                    captures = emptyList(),
                    body = resolvedBody,
                    isPublic = false,
                    loc = expr.loc
                ))

                val captures = expr.captures.map { c ->
                    ResolvedCaptureRead(0, typeToSlotKind(c.type))
                }
                ResolvedMakeLambda(fnHash, captures, hash, loc)
            }

            is MonoRecord -> ResolvedMakeRecord(
                expr.entries.mapNotNull { entry ->
                    when (entry) {
                        is MonoRecordEntry.Field -> ResolvedFieldEntry(entry.name, resolveExpr(entry.value, ctx))
                        is MonoRecordEntry.Spread -> null // TODO: expand spreads
                    }
                },
                hash, loc
            )
            is MonoFieldAccess -> ResolvedFieldAccess(
                resolveExpr(expr.record, ctx),
                expr.field,
                expr.fieldIndex,
                hash, loc
            )
            is MonoRecordUpdate -> ResolvedRecordUpdate(
                resolveExpr(expr.record, ctx),
                expr.updates.mapNotNull { entry ->
                    when (entry) {
                        is MonoRecordEntry.Field -> ResolvedFieldEntry(entry.name, resolveExpr(entry.value, ctx))
                        is MonoRecordEntry.Spread -> null
                    }
                },
                hash, loc
            )

            is MonoVariantConstruct -> {
                val tag = expr.tag.hashCode() and 0x7FFFFFFF // Simple tag assignment
                ResolvedMakeVariant(
                    tag, expr.tag,
                    expr.payload?.let { resolveExpr(it, ctx) },
                    hash, loc
                )
            }

            is MonoIf -> ResolvedIf(
                resolveExpr(expr.condition, ctx),
                resolveExpr(expr.thenBranch, ctx),
                resolveExpr(expr.elseBranch, ctx),
                hash, loc
            )

            is MonoMatch -> ResolvedMatch(
                resolveExpr(expr.scrutinee, ctx),
                expr.cases.map { c ->
                    ResolvedCase(
                        resolvePattern(c.pattern, ctx),
                        c.bindings.map { b ->
                            val kind = typeToSlotKind(b.type)
                            val slot = ctx.getOrAllocLocal(b.name, kind)
                            ResolvedSlotBinding(slot, kind, b.name)
                        },
                        c.guard?.let { resolveExpr(it, ctx) },
                        resolveExpr(c.body, ctx),
                        c.loc
                    )
                },
                hash, loc
            )

            is MonoBlock -> ResolvedBlock(
                expr.statements.map { stmt ->
                    when (stmt) {
                        is MonoStatement.Let -> {
                            val kind = typeToSlotKind(stmt.type)
                            val slot = ctx.getOrAllocLocal(stmt.name, kind)
                            ResolvedStmt.Let(slot, kind, stmt.name, stmt.contentHash, resolveExpr(stmt.value, ctx), stmt.loc)
                        }
                        is MonoStatement.Expr -> ResolvedStmt.Expr(resolveExpr(stmt.expr, ctx), stmt.loc)
                    }
                },
                resolveExpr(expr.result, ctx),
                hash, loc
            )

            is MonoSeq -> ResolvedMakeSeq(
                expr.elements.map { elem ->
                    when (elem) {
                        is MonoSeqElement.Regular -> ResolvedSeqElement.Regular(resolveExpr(elem.expr, ctx))
                        is MonoSeqElement.Spread -> ResolvedSeqElement.Spread(resolveExpr(elem.expr, ctx))
                    }
                },
                hash, loc
            )

            is MonoPerform -> ResolvedPerform(
                expr.handlers.map { h ->
                    ResolvedHandlerBinding(h.performerHash, h.effectHash, h.performerName, h.params?.let { resolveExpr(it, ctx) })
                },
                resolveExpr(expr.body, ctx),
                hash, loc
            )

            is MonoPerformOp -> ResolvedPerformOp(
                expr.effectHash, expr.operationName,
                resolveExpr(expr.arg, ctx), hash, loc
            )
        }
    }

    // ========================================================================
    // Slot allocation
    // ========================================================================

    private class SlotAllocator {
        private val params = mutableListOf<ResolvedSlot>()
        private val locals = mutableListOf<ResolvedSlot>()
        private val localsByName = mutableMapOf<String, Int>()

        fun allocParam(name: String, kind: SlotKind): Int {
            val slot = params.size
            params.add(ResolvedSlot(slot, kind, name))
            return slot
        }

        fun getOrAllocLocal(name: String, kind: SlotKind): Int {
            localsByName[name]?.let { return it }
            val slot = locals.size
            locals.add(ResolvedSlot(slot, kind, name))
            localsByName[name] = slot
            return slot
        }

        fun getParams(): List<ResolvedSlot> = params.toList()
        fun getLocals(): List<ResolvedSlot> = locals.toList()
    }

    // ========================================================================
    // Type → SlotKind
    // ========================================================================

    private fun typeToSlotKind(type: MezzeType): SlotKind {
        return when (type) {
            is IntType -> SlotKind.LONG
            is NatType -> SlotKind.LONG
            is FloatType -> SlotKind.DOUBLE
            is BoolType -> SlotKind.BOOLEAN
            else -> SlotKind.OBJECT
        }
    }

    // ========================================================================
    // Hashing
    // ========================================================================

    private fun resolvePattern(pattern: MonoPattern, ctx: SlotAllocator): ResolvedPattern {
        return when (pattern) {
            is MonoWildcardPattern -> ResolvedWildcardPattern(pattern.loc)
            is MonoVarPattern -> {
                val kind = typeToSlotKind(pattern.type)
                val slot = ctx.getOrAllocLocal(pattern.name, kind)
                ResolvedVarPattern(slot, kind, pattern.loc)
            }
            is MonoLiteralPattern -> {
                val resolvedValue = resolveExpr(pattern.value, ctx)
                ResolvedLiteralPattern(resolvedValue, pattern.loc)
            }
            is MonoVariantPattern -> {
                val tag = pattern.tag.hashCode() and 0x7FFFFFFF
                val payload = pattern.payload?.let { resolvePattern(it, ctx) }
                ResolvedVariantPattern(tag, payload, pattern.loc)
            }
            is MonoRecordPattern -> ResolvedRecordPattern(
                pattern.fields.map { f ->
                    val nested = f.pattern?.let { resolvePattern(it, ctx) }
                    if (nested == null) {
                        val kind = SlotKind.OBJECT
                        val slot = ctx.getOrAllocLocal(f.name, kind)
                        ResolvedPatternField(f.name, 0, ResolvedVarPattern(slot, kind, pattern.loc))
                    } else {
                        ResolvedPatternField(f.name, 0, nested)
                    }
                },
                pattern.isOpen, pattern.loc
            )
            is MonoSeqPattern -> ResolvedSeqPattern(
                pattern.elements.map { resolvePattern(it, ctx) },
                pattern.rest?.let { ctx.getOrAllocLocal(it, SlotKind.OBJECT) },
                pattern.loc
            )
        }
    }

    private fun computeExprHash(expr: MonoExpr): Hash {
        val builder = com.mezze.language.ast.hashing.MezzeHashBuilder()
        builder.append(expr.toString())
        return Hash.fromV1(builder.build().toFullHexString())
    }

    private fun computeHash(input: String): Hash {
        val builder = com.mezze.language.ast.hashing.MezzeHashBuilder()
        builder.append(input)
        return Hash.fromV1(builder.build().toFullHexString())
    }
}
