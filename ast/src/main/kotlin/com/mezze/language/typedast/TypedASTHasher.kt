package com.mezze.language.typedast

import com.mezze.language.ast.hashing.HashTags
import com.mezze.language.ast.hashing.MezzeHashBuilder

/**
 * Content hasher for TypedAST — computes deterministic hashes directly from
 * typed expressions without going through the v1 AST.
 *
 * Design principles (inspired by Unison):
 * - Bound variables use positional (de Bruijn-like) indices, NOT names
 * - Free variable references use their content hash (BindingRef.ByHash)
 * - Native references use their name string
 * - Record field names ARE part of the hash (they're API)
 * - Variant constructor names ARE part of the hash
 * - Source locations are EXCLUDED
 * - Types are EXCLUDED (only structural content matters)
 * - Each node variant has a unique tag byte to prevent collisions
 *
 * This produces the same hash for alpha-equivalent expressions:
 *   `{ x } -> x + 1` and `{ y } -> y + 1` hash identically
 * but NOT for different field names:
 *   `{ x } -> x + 1` and `{ n } -> n + 1` hash differently (field name is API)
 */
object TypedASTHasher {

    /**
     * Hash a TypedExpr to a v2 Hash.
     * This is the primary entry point for content-addressing expressions.
     */
    fun hashExpr(expr: TypedExpr): Hash {
        val builder = MezzeHashBuilder()
        appendExpr(builder, expr)
        return builder.buildHash()
    }

    /**
     * Hash a top-level let binding (function or value).
     * Includes the body expression only — name is excluded.
     */
    fun hashLetDecl(decl: TypedLetDecl): Hash {
        val builder = MezzeHashBuilder()
        builder.append(byteArrayOf(HashTags.LET_BINDING))
        // Include type params for generic functions
        builder.appendInt(decl.typeParams.size)
        for (tp in decl.typeParams) {
            builder.append(tp.name)
        }
        // Hash the body expression
        appendExpr(builder, decl.value)
        return builder.buildHash()
    }

    /**
     * Hash a performer declaration.
     * Returns both the performer hash and per-operation hashes.
     */
    fun hashPerformerDecl(decl: TypedPerformerDecl): PerformerHashes {
        // Hash each operation independently
        val opHashes = mutableMapOf<String, Hash>()
        for ((opName, opBody) in decl.operations) {
            val opBuilder = MezzeHashBuilder()
            opBuilder.append(byteArrayOf(HashTags.IMPL_DECLARATION))
            opBuilder.append(decl.effectHash.hex)
            opBuilder.append(opName)
            appendExpr(opBuilder, opBody)
            opHashes[opName] = opBuilder.buildHash()
        }

        // Hash the performer as a whole
        val builder = MezzeHashBuilder()
        builder.append(byteArrayOf(HashTags.IMPL_DECLARATION))
        builder.append(decl.effectHash.hex)
        builder.appendInt(decl.params.size)
        for (param in decl.params) {
            builder.append(param.name)
        }
        // Include sorted operation hashes for determinism
        builder.appendInt(decl.operations.size)
        for (opName in decl.operations.keys.sorted()) {
            builder.append(opName)
            builder.append(opHashes[opName]!!.hex)
        }
        val performerHash = builder.buildHash()

        return PerformerHashes(performerHash, opHashes)
    }

    /**
     * Hash an effect declaration.
     */
    fun hashEffectDecl(decl: TypedEffectDecl): Hash {
        val builder = MezzeHashBuilder()
        builder.append(byteArrayOf(HashTags.EFFECT_DECLARATION))
        builder.append(decl.name)
        builder.appendInt(decl.operations.size)
        for (op in decl.operations.sortedBy { it.name }) {
            builder.append(op.name)
            appendType(builder, op.paramType)
            appendType(builder, op.returnType)
        }
        return builder.buildHash()
    }

    // ========================================================================
    // Expression hashing — the core recursive walk
    // ========================================================================

    private fun appendExpr(b: MezzeHashBuilder, expr: TypedExpr) {
        when (expr) {
            is TypedIntLit -> {
                b.append(byteArrayOf(HashTags.INTEGER_LITERAL))
                b.appendLong(expr.value)
            }
            is TypedNatLit -> {
                b.append(byteArrayOf(0x01, HashTags.INTEGER_LITERAL)) // distinct from Int
                b.appendLong(expr.value)
            }
            is TypedFloatLit -> {
                b.append(byteArrayOf(HashTags.FLOAT_LITERAL))
                val bits = java.lang.Double.doubleToLongBits(expr.value)
                b.appendLong(bits)
            }
            is TypedFracLit -> {
                b.append(byteArrayOf(0x02, HashTags.FLOAT_LITERAL)) // distinct from Float
                b.appendLong(expr.numerator)
                b.appendLong(expr.denominator)
            }
            is TypedBoolLit -> {
                b.append(byteArrayOf(HashTags.BOOLEAN_LITERAL))
                b.appendBoolean(expr.value)
            }
            is TypedCharLit -> {
                b.append(byteArrayOf(0x03, HashTags.STRING_LITERAL)) // distinct from String
                b.appendInt(expr.value.code)
            }
            is TypedStringLit -> {
                b.append(byteArrayOf(HashTags.STRING_LITERAL))
                b.appendInt(expr.parts.size)
                for (part in expr.parts) {
                    when (part) {
                        is TypedStringPart.Literal -> {
                            b.appendBoolean(false) // literal marker
                            b.append(part.value)
                        }
                        is TypedStringPart.Interpolation -> {
                            b.appendBoolean(true) // interpolation marker
                            appendExpr(b, part.expr)
                        }
                    }
                }
            }
            is TypedVar -> {
                // The critical Unison-style hashing:
                // - ByHash → include the content hash (transparent reference)
                // - Native → include the native name
                // - Local/Param/Capture → include positional index (de Bruijn)
                // - Name is EXCLUDED
                when (val binding = expr.binding) {
                    is BindingRef.ByHash -> {
                        b.append(byteArrayOf(HashTags.EXTERNAL_REF))
                        b.append(binding.hash.hex)
                    }
                    is BindingRef.Native -> {
                        b.append(byteArrayOf(HashTags.EXTERNAL_REF, 0x01))
                        b.append(binding.name)
                    }
                    is BindingRef.Local -> {
                        b.append(byteArrayOf(HashTags.LOCAL_REF))
                        b.appendInt(binding.depth)
                        b.appendInt(binding.index)
                    }
                    is BindingRef.Param -> {
                        b.append(byteArrayOf(HashTags.LOCAL_REF, 0x01))
                        b.appendInt(binding.index)
                    }
                    is BindingRef.Capture -> {
                        b.append(byteArrayOf(HashTags.LOCAL_REF, 0x02))
                        b.appendInt(binding.index)
                    }
                }
            }
            is TypedLambda -> {
                b.append(byteArrayOf(HashTags.LAMBDA))
                // Param names ARE part of hash (Mezze uses named params = record fields)
                b.appendInt(expr.params.size)
                for (param in expr.params) {
                    b.append(param.name)
                }
                // Captures are positional
                b.appendInt(expr.captures.size)
                appendExpr(b, expr.body)
            }
            is TypedCall -> {
                b.append(byteArrayOf(HashTags.FUNCTION_CALL))
                appendExpr(b, expr.function)
                appendExpr(b, expr.arg)
                b.appendBoolean(expr.isTailCall)
                // Include the resolved ability-impl targets so two calls that are
                // structurally identical but dispatch to DIFFERENT impls (e.g. a
                // `from_json` decoding into `{n}` vs `{name}` — the target is on
                // the RETURN side, invisible in function/arg) hash distinctly and
                // are not deduped into one. Null instantiation (the common case)
                // appends nothing, so existing hashes are unchanged.
                expr.instantiation?.abilityImpls?.let { refs ->
                    for (r in refs) b.append(r.implHash.hex)
                }
            }
            is TypedRecord -> {
                b.append(byteArrayOf(HashTags.RECORD))
                b.appendInt(expr.entries.size)
                for (entry in expr.entries) {
                    when (entry) {
                        is TypedRecordEntry.Field -> {
                            b.appendBoolean(false) // field marker
                            b.append(entry.name)
                            appendExpr(b, entry.value)
                        }
                        is TypedRecordEntry.Spread -> {
                            b.appendBoolean(true) // spread marker
                            appendExpr(b, entry.expr)
                        }
                    }
                }
            }
            is TypedFieldAccess -> {
                b.append(byteArrayOf(HashTags.FIELD_ACCESS))
                appendExpr(b, expr.record)
                b.append(expr.field)
            }
            is TypedRecordUpdate -> {
                b.append(byteArrayOf(HashTags.RECORD, 0x01)) // distinct from Record
                appendExpr(b, expr.record)
                b.appendInt(expr.updates.size)
                for (entry in expr.updates) {
                    when (entry) {
                        is TypedRecordEntry.Field -> {
                            b.appendBoolean(false)
                            b.append(entry.name)
                            appendExpr(b, entry.value)
                        }
                        is TypedRecordEntry.Spread -> {
                            b.appendBoolean(true)
                            appendExpr(b, entry.expr)
                        }
                    }
                }
            }
            is TypedVariantConstruct -> {
                b.append(byteArrayOf(HashTags.VARIANT_CONSTRUCTOR))
                b.append(expr.tag)
                b.appendBoolean(expr.payload != null)
                expr.payload?.let { appendExpr(b, it) }
            }
            is TypedMatch -> {
                b.append(byteArrayOf(HashTags.MATCH))
                appendExpr(b, expr.scrutinee)
                b.appendInt(expr.cases.size)
                for (case_ in expr.cases) {
                    b.appendInt(case_.patterns.size)
                    for (p in case_.patterns) {
                        appendPattern(b, p)
                    }
                    case_.guard?.let { guard ->
                        b.appendBoolean(true)
                        appendExpr(b, guard)
                    } ?: b.appendBoolean(false)
                    b.appendInt(case_.ifLetBindings.size)
                    for (ilb in case_.ifLetBindings) {
                        appendPattern(b, ilb.pattern)
                        appendExpr(b, ilb.expr)
                    }
                    appendExpr(b, case_.body)
                }
            }
            is TypedIf -> {
                b.append(byteArrayOf(HashTags.IF_ELSE))
                appendExpr(b, expr.condition)
                appendExpr(b, expr.thenBranch)
                appendExpr(b, expr.elseBranch)
            }
            is TypedBlock -> {
                b.append(byteArrayOf(HashTags.BLOCK))
                b.appendInt(expr.statements.size)
                for (stmt in expr.statements) {
                    when (stmt) {
                        is TypedStatement.Expr -> {
                            b.appendBoolean(false) // expr statement
                            appendExpr(b, stmt.expr)
                        }
                        is TypedStatement.Let -> {
                            b.appendBoolean(true) // let statement
                            // Let name is excluded (de Bruijn), but contentHash links to definition
                            b.append(stmt.contentHash.hex)
                            appendExpr(b, stmt.value)
                        }
                    }
                }
                appendExpr(b, expr.result)
            }
            is TypedSeq -> {
                b.append(byteArrayOf(HashTags.ARRAY))
                b.appendInt(expr.elements.size)
                for (elem in expr.elements) {
                    when (elem) {
                        is TypedSeqElement.Regular -> {
                            b.appendBoolean(false)
                            appendExpr(b, elem.expr)
                        }
                        is TypedSeqElement.Spread -> {
                            b.appendBoolean(true)
                            appendExpr(b, elem.expr)
                        }
                    }
                }
            }
            is TypedPerform -> {
                b.append(byteArrayOf(HashTags.PERFORM))
                b.appendInt(expr.handlers.size)
                for (h in expr.handlers) {
                    b.append(h.performerHash.hex)
                    b.append(h.effectHash.hex)
                    b.append(h.performerName)
                    h.params?.let { params ->
                        b.appendBoolean(true)
                        appendExpr(b, params)
                    } ?: b.appendBoolean(false)
                }
                appendExpr(b, expr.body)
            }
            is TypedPerformOp -> {
                b.append(byteArrayOf(HashTags.PERFORM, 0x01)) // distinct tag
                b.append(expr.effectHash.hex)
                b.append(expr.operationName)
                appendExpr(b, expr.arg)
            }
            is TypedPipe -> {
                b.append(byteArrayOf(HashTags.PIPE))
                appendExpr(b, expr.left)
                appendExpr(b, expr.right)
            }
        }
    }

    // ========================================================================
    // Pattern hashing
    // ========================================================================

    private fun appendPattern(b: MezzeHashBuilder, pattern: TypedPattern) {
        when (pattern) {
            is TypedVarPattern -> {
                // Variable patterns use positional binding — name excluded
                b.append(byteArrayOf(HashTags.VARIABLE_PATTERN))
            }
            is TypedWildcardPattern -> {
                b.append(byteArrayOf(HashTags.WILDCARD_PATTERN))
            }
            is TypedLiteralPattern -> {
                b.append(byteArrayOf(HashTags.LITERAL_PATTERN))
                appendExpr(b, pattern.value)
            }
            is TypedVariantPattern -> {
                b.append(byteArrayOf(HashTags.CONSTRUCTOR_PATTERN))
                b.append(pattern.tag)
                b.appendBoolean(pattern.payload != null)
                pattern.payload?.let { appendPattern(b, it) }
            }
            is TypedRecordPattern -> {
                b.append(byteArrayOf(HashTags.RECORD_PATTERN))
                b.appendInt(pattern.fields.size)
                for (fp in pattern.fields) {
                    b.append(fp.name)
                    fp.pattern?.let { p ->
                        b.appendBoolean(true)
                        appendPattern(b, p)
                    } ?: b.appendBoolean(false)
                }
                b.appendBoolean(pattern.isOpen)
            }
            is TypedSeqPattern -> {
                b.append(byteArrayOf(HashTags.SEQ_PATTERN))
                b.appendInt(pattern.elements.size)
                for (elem in pattern.elements) {
                    appendPattern(b, elem)
                }
                b.appendBoolean(pattern.rest != null)
                pattern.rest?.let { b.append(it) }
            }
            is TypedStringInterpPattern -> {
                b.append(byteArrayOf(HashTags.STRING_INTERPOLATION_PATTERN))
                b.appendInt(pattern.parts.size)
                for (part in pattern.parts) {
                    when (part) {
                        is TypedStringPatternPart.Literal -> {
                            b.appendBoolean(false)
                            b.append(part.value)
                        }
                        is TypedStringPatternPart.Capture -> {
                            b.appendBoolean(true)
                            // Variable name excluded from hash (positional binding)
                        }
                    }
                }
            }
        }
    }

    // Type hashing — canonical encoder lives in
    // [com.mezze.language.ast.hashing.appendType]; we just import it.
    private fun appendType(b: MezzeHashBuilder, type: com.mezze.language.ast.MezzeType) =
        com.mezze.language.ast.hashing.appendType(b, type)

    // ========================================================================
    // Result types
    // ========================================================================

    data class PerformerHashes(
        val performerHash: Hash,
        val operationHashes: Map<String, Hash>
    )
}
