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

    /** True when resolving handler operation bodies — `scope` reads from context, not frame */
    private var inHandlerOperation = false

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
                operations = pd.operations.entries.mapIndexed { idx, (opName, body) ->
                    val ctx = SlotAllocator()
                    inHandlerOperation = true
                    val resolvedBody = resolveExpr(body, ctx)
                    inHandlerOperation = false
                    // Use pre-computed content hash from TypedASTHasher
                    val opHash = pd.operationHashes[opName]
                        ?: error("Missing operation hash for ${pd.name}::$opName — TypedASTHasher must compute all operation hashes")
                    ResolvedFunction(
                        hash = opHash,
                        name = opName,
                        qualifiedName = "${pd.name}::$opName",
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
                        // In handler operations, `scope` is provided by the HandleNode
                        // via the MezzeContext, not as a frame-local variable.
                        if (inHandlerOperation && expr.name == "scope") {
                            return ResolvedReadContext("scope", hash, loc)
                        }
                        val kind = typeToSlotKind(expr.type)
                        val existingSlot = ctx.findLocal(expr.name)
                        if (existingSlot != null) {
                            ResolvedReadLocal(existingSlot, kind, hash, loc)
                        } else if (ctx.parent != null && ctx.findParam(expr.name) == null) {
                            // Inside a lambda, NOT a param in this scope — try capture
                            val captureIdx = ctx.getOrCreateCapture(expr.name, null, kind)
                            if (captureIdx != null) {
                                ResolvedReadCapture(captureIdx, kind, hash, loc)
                            } else {
                                val slot = ctx.getOrAllocLocal(expr.name, kind)
                                ResolvedReadLocal(slot, kind, hash, loc)
                            }
                        } else {
                            val slot = ctx.getOrAllocLocal(expr.name, kind)
                            ResolvedReadLocal(slot, kind, hash, loc)
                        }
                    }
                    is MonoBinding.Param -> {
                        val kind = typeToSlotKind(expr.type)
                        ResolvedReadParam(binding.index, kind, hash, loc)
                    }
                    is MonoBinding.Capture -> {
                        val kind = typeToSlotKind(expr.type)
                        ResolvedReadCapture(binding.index, kind, hash, loc)
                    }
                    is MonoBinding.FunctionRef -> {
                        // Lambda/function reference: check local slot first, then capture, then CallDirect
                        val existingSlot = ctx.findLocal(expr.name)
                        if (existingSlot != null) {
                            val kind = typeToSlotKind(expr.type)
                            ResolvedReadLocal(existingSlot, kind, hash, loc)
                        } else {
                            // Try capture from parent scope
                            val kind = typeToSlotKind(expr.type)
                            val bindingHash = (binding.ref as? MonoFunctionRef.ByHash)?.hash?.hex
                            val captureIdx = ctx.getOrCreateCapture(expr.name, bindingHash, kind)
                            if (captureIdx != null) {
                                ResolvedReadCapture(captureIdx, kind, hash, loc)
                            } else {
                                when (val ref = binding.ref) {
                                    is MonoFunctionRef.ByHash -> ResolvedCallDirect(ref.hash, ResolvedUnitLit(hash, loc), false, hash, loc)
                                    is MonoFunctionRef.Native -> ResolvedCallNative(ref.name, ResolvedUnitLit(hash, loc), hash, loc)
                                }
                            }
                        }
                    }
                    is MonoBinding.ValueRef -> {
                        // Non-lambda value reference: check local slot first, then capture, then CallDirect
                        // CallDirect with UnitLit calls the function that evaluates and returns the value
                        val existingSlot = ctx.findLocal(expr.name)
                        if (existingSlot != null) {
                            val kind = typeToSlotKind(expr.type)
                            ResolvedReadLocal(existingSlot, kind, hash, loc)
                        } else {
                            val kind = typeToSlotKind(expr.type)
                            val captureIdx = ctx.getOrCreateCapture(expr.name, binding.hash.hex, kind)
                            if (captureIdx != null) {
                                ResolvedReadCapture(captureIdx, kind, hash, loc)
                            } else {
                                ResolvedCallDirect(binding.hash, ResolvedUnitLit(hash, loc), false, hash, loc)
                            }
                        }
                    }
                }
            }

            is MonoCall -> {
                val resolvedArg = resolveExpr(expr.arg, ctx)
                val fn = expr.function
                // Debug removed
                when {
                    fn is MonoVar && fn.binding is MonoBinding.FunctionRef -> {
                        val ref = (fn.binding as MonoBinding.FunctionRef).ref
                        when (ref) {
                            is MonoFunctionRef.ByHash -> {
                                val localSlot = ctx.findLocal(fn.name)
                                if (localSlot != null) {
                                    val kind = typeToSlotKind(fn.type)
                                    val readFn = ResolvedReadLocal(localSlot, kind, hash, loc)
                                    ResolvedCallIndirect(readFn, resolvedArg, expr.isTailCall, hash, loc)
                                } else {
                                    val kind = typeToSlotKind(fn.type)
                                    val captureIdx = ctx.getOrCreateCapture(fn.name, ref.hash.hex, kind)
                                    if (captureIdx != null) {
                                        val readFn = ResolvedReadCapture(captureIdx, kind, hash, loc)
                                        ResolvedCallIndirect(readFn, resolvedArg, expr.isTailCall, hash, loc)
                                    } else {
                                        ResolvedCallDirect(ref.hash, resolvedArg, expr.isTailCall, hash, loc)
                                    }
                                }
                            }
                            is MonoFunctionRef.Native -> ResolvedCallNative(ref.name, resolvedArg, hash, loc)
                        }
                    }
                    fn is MonoVar && fn.binding is MonoBinding.ValueRef -> {
                        // Values aren't callable — but this can happen with HOFs passing values.
                        // Read the value and try indirect call (the value might be a closure at runtime).
                        val valueHash = (fn.binding as MonoBinding.ValueRef).hash
                        val localSlot = ctx.findLocal(fn.name)
                        if (localSlot != null) {
                            val kind = typeToSlotKind(fn.type)
                            val readFn = ResolvedReadLocal(localSlot, kind, hash, loc)
                            ResolvedCallIndirect(readFn, resolvedArg, expr.isTailCall, hash, loc)
                        } else {
                            val kind = typeToSlotKind(fn.type)
                            val captureIdx = ctx.getOrCreateCapture(fn.name, valueHash.hex, kind)
                            if (captureIdx != null) {
                                val readFn = ResolvedReadCapture(captureIdx, kind, hash, loc)
                                ResolvedCallIndirect(readFn, resolvedArg, expr.isTailCall, hash, loc)
                            } else {
                                // Call the value's function to get it, then invoke indirectly
                                ResolvedCallDirect(valueHash, resolvedArg, expr.isTailCall, hash, loc)
                            }
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
                val innerCtx = SlotAllocator(parent = ctx)
                for (param in expr.params) {
                    innerCtx.allocParam(param.name, typeToSlotKind(param.type))
                }
                val resolvedBody = resolveExpr(expr.body, innerCtx)
                // Content-based lambda hash — deterministic across compilation contexts
                val fnHash = computeExprHash(expr)

                val resolvedCaptures = innerCtx.getCaptures()

                extractedLambdas.add(ResolvedFunction(
                    hash = fnHash,
                    name = "lambda",
                    qualifiedName = "lambda",
                    params = innerCtx.getParams(),
                    locals = innerCtx.getLocals(),
                    captures = resolvedCaptures,
                    body = resolvedBody,
                    isPublic = false,
                    loc = expr.loc
                ))

                val captureReads = resolvedCaptures.map { c ->
                    ResolvedCaptureRead(c.sourceIndex, c.kind)
                }
                ResolvedMakeLambda(fnHash, captureReads, hash, loc)
            }

            is MonoRecord -> {
                // Collect explicit field names (these override spread fields)
                val explicitFields = expr.entries
                    .filterIsInstance<MonoRecordEntry.Field>()
                    .map { it.name }
                    .toSet()

                val resolvedFields = mutableListOf<ResolvedFieldEntry>()
                for (entry in expr.entries) {
                    when (entry) {
                        is MonoRecordEntry.Field ->
                            resolvedFields.add(ResolvedFieldEntry(entry.name, resolveExpr(entry.value, ctx)))
                        is MonoRecordEntry.Spread -> {
                            // Expand spread: for each field in the spread record's type that isn't
                            // explicitly overridden, emit a field access on the resolved spread expr.
                            val spreadType = entry.expr.type
                            if (spreadType is RecordType) {
                                val resolvedSpread = resolveExpr(entry.expr, ctx)
                                for ((fieldName, _) in spreadType.fields) {
                                    if (fieldName !in explicitFields) {
                                        val fieldAccess = ResolvedFieldAccess(resolvedSpread, fieldName, -1, hash, loc)
                                        resolvedFields.add(ResolvedFieldEntry(fieldName, fieldAccess))
                                    }
                                }
                            }
                            // If not a RecordType, skip (shouldn't happen with well-typed code)
                        }
                    }
                }
                ResolvedMakeRecord(resolvedFields, hash, loc)
            }
            is MonoFieldAccess -> ResolvedFieldAccess(
                resolveExpr(expr.record, ctx),
                expr.field,
                expr.fieldIndex,
                hash, loc
            )
            is MonoRecordUpdate -> {
                val explicitUpdateFields = expr.updates
                    .filterIsInstance<MonoRecordEntry.Field>()
                    .map { it.name }
                    .toSet()
                val resolvedUpdates = mutableListOf<ResolvedFieldEntry>()
                for (entry in expr.updates) {
                    when (entry) {
                        is MonoRecordEntry.Field ->
                            resolvedUpdates.add(ResolvedFieldEntry(entry.name, resolveExpr(entry.value, ctx)))
                        is MonoRecordEntry.Spread -> {
                            val spreadType = entry.expr.type
                            if (spreadType is RecordType) {
                                val resolvedSpread = resolveExpr(entry.expr, ctx)
                                for ((fieldName, _) in spreadType.fields) {
                                    if (fieldName !in explicitUpdateFields) {
                                        val fieldAccess = ResolvedFieldAccess(resolvedSpread, fieldName, -1, hash, loc)
                                        resolvedUpdates.add(ResolvedFieldEntry(fieldName, fieldAccess))
                                    }
                                }
                            }
                        }
                    }
                }
                ResolvedRecordUpdate(resolveExpr(expr.record, ctx), resolvedUpdates, hash, loc)
            }

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

            is MonoMatch -> {
                ResolvedMatch(
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
            }

            is MonoBlock -> ResolvedBlock(
                expr.statements.map { stmt ->
                    when (stmt) {
                        is MonoStatement.Let -> {
                            val kind = typeToSlotKind(stmt.type)
                            val slot = ctx.getOrAllocLocal(stmt.name, kind)
                            ctx.setLocalHash(stmt.name, stmt.contentHash.hex)
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

    private class SlotAllocator(
        /** Parent scope for capture resolution */
        val parent: SlotAllocator? = null
    ) {
        private val params = mutableListOf<ResolvedSlot>()
        private val locals = mutableListOf<ResolvedSlot>()
        private val localsByName = mutableMapOf<String, Int>()
        /** Track which content hash each local was defined with */
        private val localHashByName = mutableMapOf<String, String>()
        /** Captures from parent scope */
        private val capturesList = mutableListOf<ResolvedCaptureSlot>()
        private val capturesByKey = mutableMapOf<String, Int>()  // "name:hash" → capture index

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

        /** Register the content hash for a local (from Let statement) */
        fun setLocalHash(name: String, hash: String) {
            localHashByName[name] = hash
        }

        /** Find existing local slot by name, returns null if not allocated */
        fun findLocal(name: String): Int? = localsByName[name]

        /** Find existing param by name */
        fun findParam(name: String): Int? = params.firstOrNull { it.name == name }?.index

        /**
         * Find or create a capture from the parent scope.
         * Uses name+hash to avoid collisions (e.g., local "length" vs imported "length").
         * Returns capture index, or null if not found in parent.
         */
        fun getOrCreateCapture(name: String, bindingHash: String?, kind: SlotKind): Int? {
            // Key includes hash to avoid name collisions
            val key = "$name:${bindingHash ?: "local"}"
            capturesByKey[key]?.let { return it }

            // Find in parent by name AND verify hash matches
            val parentSlot = parent?.findLocal(name)
            if (parentSlot != null) {
                val parentHash = parent?.localHashByName?.get(name)
                // Only capture if hash matches (or binding has no hash = local value)
                if (bindingHash == null || parentHash == null || parentHash == bindingHash) {
                    val captureIdx = capturesList.size
                    capturesList.add(ResolvedCaptureSlot(
                        sourceIndex = parentSlot, targetIndex = captureIdx, kind = kind, name = name
                    ))
                    capturesByKey[key] = captureIdx
                    return captureIdx
                }
            }
            return null
        }

        fun getParams(): List<ResolvedSlot> = params.toList()
        fun getLocals(): List<ResolvedSlot> = locals.toList()
        fun getCaptures(): List<ResolvedCaptureSlot> = capturesList.toList()
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
            is MonoRecordPattern -> {
                // Derive field types from the record pattern's type when available
                val recordType = pattern.type as? MezzeType.RecordType
                ResolvedRecordPattern(
                    pattern.fields.map { f ->
                        val nested = f.pattern?.let { resolvePattern(it, ctx) }
                        if (nested == null) {
                            // No rename — bind field name directly.
                            // Use the field's actual type for correct slot kind.
                            val fieldType = recordType?.fields?.get(f.name)
                            val kind = if (fieldType != null) typeToSlotKind(fieldType) else SlotKind.OBJECT
                            val slot = ctx.getOrAllocLocal(f.name, kind)
                            ResolvedPatternField(f.name, 0, ResolvedVarPattern(slot, kind, pattern.loc))
                        } else {
                            ResolvedPatternField(f.name, 0, nested)
                        }
                    },
                    pattern.isOpen, pattern.loc
                )
            }
            is MonoSeqPattern -> ResolvedSeqPattern(
                pattern.elements.map { resolvePattern(it, ctx) },
                pattern.rest?.let { ctx.getOrAllocLocal(it, SlotKind.OBJECT) },
                pattern.loc
            )
        }
    }

    /** Content-based expression hash — deterministic across compilation contexts.
     *  Uses structural hashing of MonoExpr (no locations, no types, de Bruijn indices). */
    private fun computeExprHash(expr: MonoExpr): Hash {
        val builder = com.mezze.language.ast.hashing.MezzeHashBuilder()
        hashMonoExpr(builder, expr)
        return Hash(builder.build().toFullHexString())
    }

    /** Hash a MonoExpr structurally — same content always produces same hash. */
    private fun hashMonoExpr(b: com.mezze.language.ast.hashing.MezzeHashBuilder, expr: MonoExpr) {
        when (expr) {
            is MonoIntLit -> { b.append(byteArrayOf(0x01)); b.appendLong(expr.value) }
            is MonoNatLit -> { b.append(byteArrayOf(0x02)); b.appendLong(expr.value) }
            is MonoFloatLit -> { b.append(byteArrayOf(0x03)); b.appendLong(java.lang.Double.doubleToLongBits(expr.value)) }
            is MonoFracLit -> { b.append(byteArrayOf(0x04)); b.appendLong(expr.numerator); b.appendLong(expr.denominator) }
            is MonoBoolLit -> { b.append(byteArrayOf(0x05)); b.appendBoolean(expr.value) }
            is MonoCharLit -> { b.append(byteArrayOf(0x06)); b.appendInt(expr.value.code) }
            is MonoStringLit -> {
                b.append(byteArrayOf(0x07)); b.appendInt(expr.parts.size)
                for (p in expr.parts) when (p) {
                    is MonoStringPart.Literal -> { b.appendBoolean(false); b.append(p.value) }
                    is MonoStringPart.Interpolation -> { b.appendBoolean(true); hashMonoExpr(b, p.expr) }
                }
            }
            is MonoVar -> {
                when (val binding = expr.binding) {
                    is MonoBinding.FunctionRef -> when (val ref = binding.ref) {
                        is MonoFunctionRef.ByHash -> { b.append(byteArrayOf(0x10)); b.append(ref.hash.hex) }
                        is MonoFunctionRef.Native -> { b.append(byteArrayOf(0x11)); b.append(ref.name) }
                    }
                    is MonoBinding.ValueRef -> { b.append(byteArrayOf(0x12)); b.append(binding.hash.hex) }
                    is MonoBinding.Local -> { b.append(byteArrayOf(0x13)); b.appendInt(binding.depth); b.appendInt(binding.index) }
                    is MonoBinding.Param -> { b.append(byteArrayOf(0x14)); b.appendInt(binding.index) }
                    is MonoBinding.Capture -> { b.append(byteArrayOf(0x15)); b.appendInt(binding.index) }
                }
            }
            is MonoLambda -> {
                b.append(byteArrayOf(0x20)); b.appendInt(expr.params.size)
                for (p in expr.params) b.append(p.name)
                hashMonoExpr(b, expr.body)
            }
            is MonoCall -> { b.append(byteArrayOf(0x21)); hashMonoExpr(b, expr.function); hashMonoExpr(b, expr.arg) }
            is MonoRecord -> {
                b.append(byteArrayOf(0x22)); b.appendInt(expr.entries.size)
                for (e in expr.entries) when (e) {
                    is MonoRecordEntry.Field -> { b.appendBoolean(false); b.append(e.name); hashMonoExpr(b, e.value) }
                    is MonoRecordEntry.Spread -> { b.appendBoolean(true); hashMonoExpr(b, e.expr) }
                }
            }
            is MonoFieldAccess -> { b.append(byteArrayOf(0x23)); hashMonoExpr(b, expr.record); b.append(expr.field) }
            is MonoRecordUpdate -> { b.append(byteArrayOf(0x24)); hashMonoExpr(b, expr.record); b.appendInt(expr.updates.size)
                for (e in expr.updates) when (e) {
                    is MonoRecordEntry.Field -> { b.appendBoolean(false); b.append(e.name); hashMonoExpr(b, e.value) }
                    is MonoRecordEntry.Spread -> { b.appendBoolean(true); hashMonoExpr(b, e.expr) }
                }
            }
            is MonoVariantConstruct -> { b.append(byteArrayOf(0x25)); b.append(expr.tag); b.appendBoolean(expr.payload != null); expr.payload?.let { hashMonoExpr(b, it) } }
            is MonoMatch -> { b.append(byteArrayOf(0x26)); hashMonoExpr(b, expr.scrutinee); b.appendInt(expr.cases.size)
                for (c in expr.cases) { hashMonoPattern(b, c.pattern); c.guard?.let { b.appendBoolean(true); hashMonoExpr(b, it) } ?: b.appendBoolean(false); hashMonoExpr(b, c.body) }
            }
            is MonoIf -> { b.append(byteArrayOf(0x27)); hashMonoExpr(b, expr.condition); hashMonoExpr(b, expr.thenBranch); hashMonoExpr(b, expr.elseBranch) }
            is MonoBlock -> { b.append(byteArrayOf(0x28)); b.appendInt(expr.statements.size)
                for (s in expr.statements) when (s) {
                    is MonoStatement.Expr -> { b.appendBoolean(false); hashMonoExpr(b, s.expr) }
                    is MonoStatement.Let -> { b.appendBoolean(true); b.append(s.contentHash.hex); hashMonoExpr(b, s.value) }
                }
                hashMonoExpr(b, expr.result)
            }
            is MonoSeq -> { b.append(byteArrayOf(0x29)); b.appendInt(expr.elements.size)
                for (e in expr.elements) when (e) {
                    is MonoSeqElement.Regular -> { b.appendBoolean(false); hashMonoExpr(b, e.expr) }
                    is MonoSeqElement.Spread -> { b.appendBoolean(true); hashMonoExpr(b, e.expr) }
                }
            }
            is MonoPerform -> { b.append(byteArrayOf(0x30)); b.appendInt(expr.handlers.size)
                for (h in expr.handlers) { b.append(h.performerHash.hex); b.append(h.effectHash.hex) }
                hashMonoExpr(b, expr.body)
            }
            is MonoPerformOp -> { b.append(byteArrayOf(0x31)); b.append(expr.effectHash.hex); b.append(expr.operationName); hashMonoExpr(b, expr.arg) }
        }
    }

    private fun hashMonoPattern(b: com.mezze.language.ast.hashing.MezzeHashBuilder, p: MonoPattern) {
        when (p) {
            is MonoVarPattern -> b.append(byteArrayOf(0x40))
            is MonoWildcardPattern -> b.append(byteArrayOf(0x41))
            is MonoLiteralPattern -> { b.append(byteArrayOf(0x42)); hashMonoExpr(b, p.value) }
            is MonoVariantPattern -> { b.append(byteArrayOf(0x43)); b.append(p.tag); b.appendBoolean(p.payload != null); p.payload?.let { hashMonoPattern(b, it) } }
            is MonoRecordPattern -> { b.append(byteArrayOf(0x44)); b.appendInt(p.fields.size); for (f in p.fields) { b.append(f.name); b.appendBoolean(f.pattern != null); f.pattern?.let { hashMonoPattern(b, it) } }; b.appendBoolean(p.isOpen) }
            is MonoSeqPattern -> { b.append(byteArrayOf(0x45)); b.appendInt(p.elements.size); for (e in p.elements) hashMonoPattern(b, e); b.appendBoolean(p.rest != null) }
        }
    }
}
