package com.mezze.language.typechecker.compiler

import com.mezze.language.ast.EffectBindingKind
import com.mezze.language.ast.Keywords
import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.holdsTypeVar
import com.mezze.language.ast.holdsUnknownPlaceholder
import com.mezze.language.ast.MezzeType.*
import com.mezze.language.ast.TypeIdentity
import com.mezze.language.typechecker.solving.IdentityVerdict
import com.mezze.language.typechecker.solving.identityVerdict
import com.mezze.language.typechecker.solving.sameDeclaredIdentity
import com.mezze.language.ast.TypeVar
import com.mezze.language.ast.VariantAliases
import com.mezze.language.parsedast.Loc
import com.mezze.language.truffleir.*
import com.mezze.language.ast.ScopedEffectConstants
import com.mezze.language.ast.PrimitiveTypes
import com.mezze.language.typedast.*

/*
 * Pattern compilation, moved out of TypedASTCompiler.
 *
 * `match` lowering and pattern lowering are one subject and sat as ~600 lines in
 * the middle of a 5,580-line file. They are extension functions rather than a
 * separate class because they read and WRITE the compiler's frame state
 * (`localsByName`, `scopeStack`, `allocLocal`) — the same shape
 * `ExpressionConstraintGenerator` is split into across ExprFunctionConstraints
 * and its siblings, for the same reason.
 *
 * A pure move: no body was edited, only the indentation and the receiver.
 */

internal fun TypedASTCompiler.compileMatch(expr: TypedMatch, src: SourceInfo): NodeIR {
    val scrutineeIR = compileExpr(expr.scrutinee)
    // Derive a concrete scrutinee type from AST context when the solver left
    // `expr.scrutinee.type` as a bare VarType. This is the "walk the type
    // environment" step for polyglot newtype dispatch: `match uuid_val is`
    // where `uuid_val`'s let-binding RHS was `match ... -> c.static { ... }`.
    // `slotConcreteTypes` was populated in compileBlock; deriveConcreteType
    // walks through TypedVar → slot → RHS.
    val concreteScrutineeType: MezzeType? = run {
        val stated = expr.scrutinee.type
        if (stated !is VarType && !containsVarTypeDeep(stated)) return@run stated
        deriveConcreteType(expr.scrutinee)
    }
    // Use flatMap to expand or-patterns (cases with multiple patterns) into
    // separate CaseIR entries sharing the same body. This way `[] | [_] -> True`
    // becomes two CaseIR entries: one for `[]` and one for `[_]`.
    val cases = expr.cases.flatMap { case ->
        // Determine which patterns to emit. For or-patterns, case.patterns has multiple entries.
        val patternsToEmit = if (case.patterns.size > 1) case.patterns else listOf(case.patterns.firstOrNull())

        patternsToEmit.map { pat ->
            // Push a new scope for this match case's bindings
            val caseScope = mutableMapOf<String, Int>()
            val savedSlotConcreteTypesInCase = slotConcreteTypes.toMap()

            // Allocate slots for pattern bindings (always fresh)
            val bindings = case.bindings.map { b ->
                val slot = forceAllocLocal(b.name)
                caseScope[b.name] = slot
                BindingIR(slot, typeToSlotKind(b.type))
            }

            // Structurally derive concrete types for pattern bindings whose
            // declared PatternBinding.type is a VarType (solver left them
            // unresolved — see slotConcreteTypes field comment). Reads the
            // concrete scrutinee type recovered above against the pattern
            // shape, so `Ok { val = u }` off `Result JvmForeign _` binds
            // `u : JvmForeign` even when case.bindings[i].type is `'u`.
            if (concreteScrutineeType != null && pat != null) {
                val derived = mutableMapOf<String, MezzeType>()
                readPatternBindings(pat, concreteScrutineeType, derived)
                for (b in case.bindings) {
                    if (b.type !is VarType && !containsVarTypeDeep(b.type)) continue
                    val slot = caseScope[b.name] ?: continue
                    derived[b.name]?.let { slotConcreteTypes[slot] = it }
                }
            }

            // Push scope so nested expressions use these bindings at depth 0
            scopeStack.addLast(caseScope)

            // Collect nested destructuring info BEFORE compilePattern,
            // so temp slots for nested records are allocated.
            val nestedDestructures = mutableListOf<NestedDestructureInfo>()
            collectNestedDestructuring(pat, nestedDestructures)

            // Collect nested seq destructuring (nested seq patterns like [[x]])
            val nestedSeqDestructures = mutableListOf<NestedSeqDestructureInfo>()
            collectNestedSeqDestructuring(pat, nestedSeqDestructures)

            val patternIR = compilePattern(pat)
            var guardIR = case.guard?.let { compileExpr(it) }

            // HOLE A: synthesize runtime tests for nested constructor/literal
            // sub-patterns and fold them into the guard. The outer pattern has
            // already bound each field to a slot, so the test reads those slots
            // (ReadLocalIR) and recurses. A false test falls through to the next
            // arm via the guard mechanism below. When there is also a user guard,
            // gate it behind the nested test (IfIR short-circuit) so the guard
            // never runs against a value whose nested shape didn't match.
            val nestedTest: NodeIR? = when (pat) {
                is TypedRecordPattern -> {
                    val terms = pat.fields.mapNotNull { fp ->
                        if (fp.pattern is TypedVarPattern || fp.pattern is TypedWildcardPattern || fp.pattern == null) {
                            null
                        } else {
                            val slot = fieldBindingSlot(fp)
                            if (slot < 0) null
                            else buildNestedTests(fp.pattern, ReadLocalIR(slot, SlotKind.OBJECT, src), src)
                        }
                    }
                    andAll(terms, src)
                }
                is TypedVariantPattern -> {
                    // Nested tests inside a top-level variant arm's payload fields.
                    val payload = pat.payload
                    if (payload is TypedRecordPattern) {
                        val terms = payload.fields.mapNotNull { fp ->
                            if (fp.pattern is TypedVarPattern || fp.pattern is TypedWildcardPattern || fp.pattern == null) {
                                null
                            } else {
                                val slot = fieldBindingSlot(fp)
                                if (slot < 0) null
                                else buildNestedTests(fp.pattern, ReadLocalIR(slot, SlotKind.OBJECT, src), src)
                            }
                        }
                        andAll(terms, src)
                    } else null
                }
                is TypedSeqPattern -> {
                    // B7: a top-level seq arm's REFUTABLE element sub-patterns
                    // (`[Some {val=x}, ...rest]`) are bound (via collectNested-
                    // SeqDestructuring → `__seq_tmp_$idx`) but never TESTED — the
                    // arm matched on length alone, so `[None]` slipped through,
                    // bound x=null, and crashed. Test each refutable element
                    // against its already-bound temp slot.
                    val terms = pat.elements.mapIndexedNotNull { idx, elem ->
                        if (elem is TypedVarPattern || elem is TypedWildcardPattern) null
                        else {
                            val elemSlot = localsByName["__seq_tmp_$idx"]
                            if (elemSlot == null) null
                            else buildNestedTests(elem, ReadLocalIR(elemSlot, SlotKind.OBJECT, src), src)
                        }
                    }
                    andAll(terms, src)
                }
                else -> null
            }
            if (nestedTest != null) {
                guardIR = if (guardIR == null) nestedTest
                          else IfIR(nestedTest, guardIR, BoolLitIR(false, src), src)
            }

            // Compile if-let bindings.
            // Also collect nested destructuring from if-let patterns.
            val ifLetIRs = case.ifLetBindings.map { ifLet ->
                // Collect nested destructuring from if-let patterns too
                collectNestedDestructuring(ifLet.pattern, nestedDestructures)
                val exprIR = compileExpr(ifLet.expr)
                val ifLetPatternIR = compilePattern(ifLet.pattern)
                // Resolve binding slots from the case scope
                val fieldNames = ifLet.bindingNames
                val fieldSlots = fieldNames.map { name -> caseScope[name] ?: -1 }
                IfLetBindingIR(exprIR, ifLetPatternIR, fieldNames, fieldSlots, locToSourceInfo(ifLet.loc))
            }

            var bodyIR = compileExpr(case.body)

            // Wrap guard AND body with nested destructuring let-bindings if needed.
            // For pattern `Some { val = { x = px, y = py } } if x > 0 -> x`:
            //   tempSlot holds the intermediate record {x=10, y=20}
            //   Guard becomes: do { let px = tempSlot.x; let py = tempSlot.y; <originalGuard> }
            //   Body becomes:  do { let px = tempSlot.x; let py = tempSlot.y; <originalBody> }
            // Both share the same frame, so the guard's writes are visible to the body.
            // We wrap both so the variables are available regardless of evaluation order.
            if (nestedDestructures.isNotEmpty()) {
                val destructureStmts = mutableListOf<StmtIR>()
                for (nd in nestedDestructures) {
                    // A level nested two-or-more deep is not bound by the flat
                    // PatternIR; first read its record off the parent temp slot.
                    // Outer levels are earlier in the list, so the parent slot
                    // is already populated by the time we reach a child.
                    val rf = nd.readFrom
                    if (rf != null) {
                        val readParent = ReadLocalIR(rf.first, SlotKind.OBJECT, src)
                        val readThis = ReadFieldIR(readParent, rf.second, -1, src)
                        destructureStmts.add(StmtIR.Let(nd.tempSlot, SlotKind.OBJECT, "__nested_lvl", Hash.ZERO, readThis, src))
                    }
                    for ((fieldName, bindingSlot) in nd.fieldBindings) {
                        val readTemp = ReadLocalIR(nd.tempSlot, SlotKind.OBJECT, src)
                        val readField = ReadFieldIR(readTemp, fieldName, -1, src)
                        destructureStmts.add(StmtIR.Let(bindingSlot, SlotKind.OBJECT, "__nested_" + fieldName, Hash.ZERO, readField, src))
                    }
                }
                // Wrap guard (if present) with destructuring so guard can reference nested vars
                if (guardIR != null) {
                    guardIR = BlockIR(destructureStmts.toList(), guardIR, src)
                }
                // Wrap body too (in case guard doesn't exist, or for independent body access)
                bodyIR = BlockIR(destructureStmts.toList(), bodyIR, src)
            }

            // Handle nested seq destructuring: for patterns like [[x, ...rest1], ...rest2],
            // the inner [x, ...rest1] was bound to a temp slot. Now generate a nested match
            // in the body that extracts bindings from the temp slot.
            if (nestedSeqDestructures.isNotEmpty()) {
                val seqStmts = mutableListOf<StmtIR>()
                for (nsd in nestedSeqDestructures) {
                    // Generate: let var = seq_get(tempSlot, index) for each binding in the inner pattern
                    val innerPat = nsd.innerPattern
                    if (innerPat is TypedSeqPattern) {
                        // For each element in the inner seq pattern, generate a seq_get
                        for ((innerIdx, innerElem) in innerPat.elements.withIndex()) {
                            if (innerElem is TypedVarPattern) {
                                val bindingSlot = scopeStack.lastOrNull()?.get(innerElem.name)
                                    ?: localsByName[innerElem.name]
                                    ?: forceAllocLocal(innerElem.name)
                                val readTemp = ReadLocalIR(nsd.tempSlot, SlotKind.OBJECT, src)
                                val getElem = SeqGetIR(readTemp, LongLitIR(innerIdx.toLong(), src), src)
                                seqStmts.add(StmtIR.Let(bindingSlot, SlotKind.OBJECT, innerElem.name, Hash.ZERO, getElem, src))
                            }
                        }
                        // Handle rest binding in inner seq pattern.
                        // "_" (`..._`) matches the tail but binds nothing — skip it.
                        val restName = innerPat.rest
                        if (restName != null && restName != "_") {
                            val restSlot = scopeStack.lastOrNull()?.get(restName)
                                ?: localsByName[restName]
                                ?: forceAllocLocal(restName)
                            // Generate a nested match to bind elements and rest from the temp slot.
                            // match tempSlot is [e0, e1, ...rest] -> rest
                            val innerCaseBindings = mutableListOf<BindingIR>()
                            val innerElementSlots = innerPat.elements.mapIndexed { _, iElem ->
                                if (iElem is TypedVarPattern) {
                                    val s = scopeStack.lastOrNull()?.get(iElem.name)
                                        ?: localsByName[iElem.name]
                                        ?: forceAllocLocal(iElem.name)
                                    s
                                } else -1
                            }
                            val innerPatternIR = PatternIR.Seq(innerElementSlots, restSlot)
                            val innerCaseBody = ReadLocalIR(restSlot, SlotKind.OBJECT, src)
                            val innerCase = CaseIR(innerPatternIR, innerCaseBindings, null, innerCaseBody, src)
                            val readTemp = ReadLocalIR(nsd.tempSlot, SlotKind.OBJECT, src)
                            val innerMatch = MatchIR(readTemp, listOf(innerCase), src)
                            seqStmts.add(StmtIR.Let(restSlot, SlotKind.OBJECT, restName, Hash.ZERO, innerMatch, src))
                        }
                    } else if (innerPat is TypedVariantPattern) {
                        // Nested variant pattern in seq: [Ok {val}] -> ...
                        // Generate a nested match on the temp slot to extract variant payload fields.
                        val innerPatternIR = compilePattern(innerPat)
                        val innerCaseBindings = mutableListOf<BindingIR>()

                        // Collect nested record destructuring within the variant payload
                        val innerNestedDestructures = mutableListOf<NestedDestructureInfo>()
                        collectNestedDestructuring(innerPat, innerNestedDestructures)

                        // Build inner body that produces unit (bindings are side-effects on the frame)
                        var innerBody: NodeIR = ReadLocalIR(nsd.tempSlot, SlotKind.OBJECT, src)

                        // Wrap with nested record destructuring if needed
                        if (innerNestedDestructures.isNotEmpty()) {
                            val dStmts = mutableListOf<StmtIR>()
                            for (nd in innerNestedDestructures) {
                                for ((fieldName, bindingSlot) in nd.fieldBindings) {
                                    val readTemp2 = ReadLocalIR(nd.tempSlot, SlotKind.OBJECT, src)
                                    val readField = ReadFieldIR(readTemp2, fieldName, -1, src)
                                    dStmts.add(StmtIR.Let(bindingSlot, SlotKind.OBJECT, "__nested_$fieldName", Hash.ZERO, readField, src))
                                }
                            }
                            innerBody = BlockIR(dStmts, innerBody, src)
                        }

                        val innerCase = CaseIR(innerPatternIR, innerCaseBindings, null, innerBody, src)
                        // Add a wildcard fallback that returns the temp slot unchanged
                        val wildcardCase = CaseIR(PatternIR.Wildcard(-1), emptyList(), null, ReadLocalIR(nsd.tempSlot, SlotKind.OBJECT, src), src)
                        val readTemp = ReadLocalIR(nsd.tempSlot, SlotKind.OBJECT, src)
                        val innerMatch = MatchIR(readTemp, listOf(innerCase, wildcardCase), src)
                        // Use an arbitrary result slot (the match result is not used, only the bindings matter)
                        val resultSlot = forceAllocLocal("__seq_variant_result_${nsd.tempSlot}")
                        seqStmts.add(StmtIR.Let(resultSlot, SlotKind.OBJECT, "__seq_variant_result", Hash.ZERO, innerMatch, src))
                    } else if (innerPat is TypedLiteralPattern) {
                        // Nested literal pattern in seq: [1, 2] -> ...
                        // No bindings needed for literals in seq elements
                    }
                }
                if (seqStmts.isNotEmpty()) {
                    if (guardIR != null) {
                        guardIR = BlockIR(seqStmts.toList(), guardIR, src)
                    }
                    bodyIR = BlockIR(seqStmts.toList(), bodyIR, src)
                }
            }

            // Pop scope
            scopeStack.removeLast()
            slotConcreteTypes.clear()
            slotConcreteTypes.putAll(savedSlotConcreteTypesInCase)

            CaseIR(patternIR, bindings, guardIR, bodyIR, locToSourceInfo(case.loc), ifLetIRs)
        }
    }
    return MatchIR(scrutineeIR, cases, src)
}

/**
 * Info about a nested record destructuring that needs post-bind processing.
 * tempSlot: the frame slot holding the intermediate record value
 * fieldBindings: list of (fieldName, targetSlot) pairs to extract from the record
 */
internal data class NestedDestructureInfo(
    val tempSlot: Int,
    val fieldBindings: List<Pair<String, Int>>,  // (fieldName, targetSlot)
    // Non-null for a level nested two-or-more deep: (parentTempSlot, field)
    // to read this level's record off the parent's temp slot. Chains depth.
    val readFrom: Pair<Int, String>? = null
)

/**
 * Info about a nested seq element destructuring that needs post-bind processing.
 * tempSlot: the frame slot holding the intermediate seq element value
 * innerPattern: the full TypedPattern for the nested seq element, compiled to
 *   a nested match in the body
 */
internal data class NestedSeqDestructureInfo(
    val tempSlot: Int,
    val innerPattern: TypedPattern
)

/**
 * Walk a typed pattern tree and collect any nested record sub-patterns
 * that can't be handled by a flat PatternIR. Allocates temp slots for
 * intermediate records.
 */
internal fun TypedASTCompiler.collectNestedDestructuring(pattern: TypedPattern?, out: MutableList<NestedDestructureInfo>) {
    if (pattern == null) return
    when (pattern) {
        is TypedVariantPattern -> (pattern.payload as? TypedRecordPattern)?.let {
            collectNestedRecordFields(it, out, null)
        }
        is TypedRecordPattern -> collectNestedRecordFields(pattern, out, null)
        else -> {}
    }
}

// For each field of [record] whose sub-pattern is itself a record, allocate a
// temp slot for the field value, bind its direct-variable inner fields, and
// RECURSE so arbitrarily deep nesting works. The flat PatternIR only reaches
// the outermost level, so a field nested two-or-more deep reads its value from
// its parent's temp slot via [parent] (parentTempSlot to read this field off).
internal fun TypedASTCompiler.collectNestedRecordFields(
    record: TypedRecordPattern,
    out: MutableList<NestedDestructureInfo>,
    parent: Int?,
) {
    for (fp in record.fields) {
        val nested = fp.pattern as? TypedRecordPattern ?: continue
        val tempSlot = forceAllocLocal("__tmp_${fp.name}")
        val fieldBindings = nested.fields.map { innerFp ->
            val bindingName = if (innerFp.pattern is TypedVarPattern) (innerFp.pattern as TypedVarPattern).name else innerFp.name
            val slot = scopeStack.lastOrNull()?.get(bindingName)
                ?: localsByName[bindingName]
                ?: forceAllocLocal(bindingName)
            Pair(innerFp.name, slot)
        }
        // Top-level nested field: the flat PatternIR already bound tempSlot
        // (parent == null). Deeper: read this field off the parent temp slot.
        val readFrom = if (parent == null) null else Pair(parent, fp.name)
        out.add(NestedDestructureInfo(tempSlot, fieldBindings, readFrom))
        collectNestedRecordFields(nested, out, tempSlot)
    }
}

/**
 * HOLE A: build a runtime boolean test for nested CONSTRUCTOR/LITERAL
 * sub-patterns inside a record field or variant payload field. A flat
 * `PatternIR.Record`/`PatternIR.Variant` only EXTRACTS + BINDS its fields —
 * it never tests a nested tag/literal. So `match r is { c = Red } -> …` (r a
 * record with `c: Color`) selected its arm on the record's SHAPE alone and
 * bound `c`'s value, silently ignoring that the nested `Red` must MATCH — a
 * `{ c = Blue }` value wrongly ran the `Red` arm. Same for a nested variant
 * in a variant payload (`Wrap { inner = A { n } }`) and a nested literal
 * (`{ n = 0 }`).
 *
 * We synthesize a pure boolean expression that reads the field (via
 * `accessExpr`) and tests the nested pattern, and AND it (short-circuit, see
 * [compileMatch]) into the case guard. Because a false guard falls through to
 * the NEXT arm (the guard→body→next-arm structure already handles that), a
 * nested mismatch correctly abandons the arm — which a body-level test could
 * never do. Returns null when the sub-pattern is irrefutable (var/wildcard/
 * absent), i.e. no test is needed.
 *
 * Bindings are unaffected: the outer pattern + [collectNestedDestructuring]
 * still bind every field; this only ADDS tests. Nested variant *payload*
 * variables are bound by the synthesized `MatchIR`'s Variant pattern into the
 * same frame slots the body reads (resolved by name), so `A { n } -> n` still
 * sees `n`. Nested seq / string-interp sub-patterns are not tested here (they
 * are separate, pre-existing gaps); they return null and keep prior behavior.
 */
/**
 * The frame slot that [compilePattern] binds a record/variant field's VALUE
 * to — must mirror that logic exactly so [buildNestedTests] reads the same
 * slot the outer pattern populated. A nested-record field goes to `__tmp_*`
 * (allocated by [collectNestedDestructuring]); everything else goes to the
 * field's binding name (renamed var, else the field name).
 */
internal fun TypedASTCompiler.fieldBindingSlot(fp: TypedFieldPattern): Int {
    if (fp.pattern is TypedRecordPattern) {
        return localsByName["__tmp_${fp.name}"] ?: -1
    }
    val bindingName = if (fp.pattern is TypedVarPattern) (fp.pattern as TypedVarPattern).name else fp.name
    return scopeStack.lastOrNull()?.get(bindingName)
        ?: localsByName[bindingName]
        ?: -1
}

internal fun TypedASTCompiler.buildNestedTests(pattern: TypedPattern?, accessExpr: NodeIR, src: SourceInfo): NodeIR? {
    if (pattern == null) return null
    return when (pattern) {
        is TypedVarPattern, is TypedWildcardPattern -> null
        is TypedLiteralPattern -> InlineBinaryOpIR(InlineOp.EQ, accessExpr, compileExpr(pattern.value), src)
        is TypedRecordPattern -> {
            val terms = pattern.fields.mapNotNull { fp ->
                buildNestedTests(fp.pattern, ReadFieldIR(accessExpr, fp.name, -1, src), src)
            }
            andAll(terms, src)
        }
        is TypedVariantPattern -> {
            // Test the tag AND (recursively) the payload, atomically, via a
            // nested match whose true-arm binds the payload and returns the
            // sub-tests, and whose wildcard-arm returns false.
            val adtHashHex = ((pattern.type as? VariantType)?.identity as? TypeIdentity.Declared)?.hash?.hex
            val tag = com.mezze.language.ast.BuiltinTypes.computeVariantTag(pattern.tag, adtHashHex)

            val fieldNames = mutableListOf<String>()
            val fieldSlots = mutableListOf<Int>()
            var payloadTest: NodeIR? = null
            when (val payload = pattern.payload) {
                is TypedRecordPattern -> {
                    val terms = mutableListOf<NodeIR>()
                    for (fp in payload.fields) {
                        fieldNames.add(fp.name)
                        val bindingName = if (fp.pattern is TypedVarPattern) (fp.pattern as TypedVarPattern).name else fp.name
                        val slot = scopeStack.lastOrNull()?.get(bindingName)
                            ?: localsByName[bindingName]
                            ?: forceAllocLocal(bindingName)
                        fieldSlots.add(slot)
                        // A nested sub-pattern inside the payload field reads the
                        // just-bound slot and recurses.
                        buildNestedTests(fp.pattern, ReadLocalIR(slot, SlotKind.OBJECT, src), src)
                            ?.let { terms.add(it) }
                    }
                    payloadTest = andAll(terms, src)
                }
                is TypedVarPattern -> {
                    fieldNames.add(payload.name)
                    val slot = scopeStack.lastOrNull()?.get(payload.name)
                        ?: localsByName[payload.name]
                        ?: forceAllocLocal(payload.name)
                    fieldSlots.add(slot)
                }
                else -> {}
            }

            val trueBody: NodeIR = payloadTest ?: BoolLitIR(true, src)
            val matchCase = CaseIR(
                PatternIR.Variant(tag, pattern.tag, fieldNames, fieldSlots),
                emptyList(), null, trueBody, src,
            )
            val wildcardCase = CaseIR(PatternIR.Wildcard(-1), emptyList(), null, BoolLitIR(false, src), src)
            MatchIR(accessExpr, listOf(matchCase, wildcardCase), src)
        }
        is TypedSeqPattern, is TypedStringInterpPattern -> {
            // B4/B6: a nested seq / string-interpolation sub-pattern needs a
            // runtime shape test (length/prefix), or the enclosing arm would
            // match on the outer tag/field-presence alone and fire wrongly.
            // Build a nested match over the field value: `<pattern> -> true,
            // _ -> false`, reusing compilePattern for the PatternIR + its slot
            // allocation (mirrors the TypedVariantPattern case above).
            val patIR = compilePattern(pattern)
            val trueCase = CaseIR(patIR, emptyList(), null, BoolLitIR(true, src), src)
            val wildcardCase = CaseIR(PatternIR.Wildcard(-1), emptyList(), null, BoolLitIR(false, src), src)
            MatchIR(accessExpr, listOf(trueCase, wildcardCase), src)
        }
    }
}

/** AND a list of boolean tests, short-circuit-strict; null if empty. */
internal fun TypedASTCompiler.andAll(terms: List<NodeIR>, src: SourceInfo): NodeIR? =
    terms.reduceOrNull { acc, t -> InlineBinaryOpIR(InlineOp.AND, acc, t, src) }

/**
 * Collect nested seq element patterns that need post-bind destructuring.
 * For pattern `[[x, ...rest1], ...rest2]`, the inner `[x, ...rest1]` is a
 * TypedSeqPattern that can't be represented flat. We bind element 0 to a
 * temp slot and generate a nested match in the body.
 */
internal fun TypedASTCompiler.collectNestedSeqDestructuring(pattern: TypedPattern?, out: MutableList<NestedSeqDestructureInfo>) {
    if (pattern == null) return
    if (pattern is TypedSeqPattern) {
        for ((idx, elem) in pattern.elements.withIndex()) {
            if (elem !is TypedVarPattern && elem !is TypedWildcardPattern) {
                // This is a nested pattern (seq, literal, etc.) that needs
                // to be bound to a temp slot and matched in the body.
                val tempSlot = forceAllocLocal("__seq_tmp_$idx")
                out.add(NestedSeqDestructureInfo(tempSlot, elem))
            }
        }
    }
}

internal fun TypedASTCompiler.compilePattern(pattern: TypedPattern?): PatternIR {
    if (pattern == null) return PatternIR.Wildcard()
    return when (pattern) {
        is TypedWildcardPattern -> PatternIR.Wildcard()
        is TypedVarPattern -> {
            // Bind the scrutinee to the variable's frame slot
            val slot = scopeStack.lastOrNull()?.get(pattern.name)
                ?: localsByName[pattern.name]
                ?: -1
            PatternIR.Wildcard(slot)
        }
        is TypedLiteralPattern -> PatternIR.Literal(compileExpr(pattern.value))
        is TypedVariantPattern -> {
            val adtHashHex = ((pattern.type as? VariantType)?.identity as? TypeIdentity.Declared)?.hash?.hex
            val tag = com.mezze.language.ast.BuiltinTypes.computeVariantTag(pattern.tag, adtHashHex)
            val fieldNames = mutableListOf<String>()
            val fieldSlots = mutableListOf<Int>()
            val payload = pattern.payload
            if (payload is TypedRecordPattern) {
                for (fp in payload.fields) {
                    fieldNames.add(fp.name)  // record field name (for extraction at runtime)
                    if (fp.pattern is TypedRecordPattern) {
                        // Nested record pattern: bind to temp slot (allocated by collectNestedDestructuring)
                        val tempSlot = localsByName["__tmp_${fp.name}"] ?: forceAllocLocal("__tmp_${fp.name}")
                        fieldSlots.add(tempSlot)
                    } else {
                        // Simple binding: { val = ys } → "ys", { val } → "val"
                        val bindingName = if (fp.pattern is TypedVarPattern) (fp.pattern as TypedVarPattern).name else fp.name
                        val slot = scopeStack.lastOrNull()?.get(bindingName)
                            ?: localsByName[bindingName]
                            ?: forceAllocLocal(bindingName)
                        fieldSlots.add(slot)
                    }
                }
            } else if (payload is TypedVarPattern) {
                fieldNames.add(payload.name)
                val slot = scopeStack.lastOrNull()?.get(payload.name)
                    ?: localsByName[payload.name]
                    ?: forceAllocLocal(payload.name)
                fieldSlots.add(slot)
            }
            PatternIR.Variant(tag, pattern.tag, fieldNames, fieldSlots)
        }
        is TypedRecordPattern -> {
            // Field names are for runtime extraction from the record.
            // Slots must map to the BINDING name (renamed variable), not the field name.
            // { value = v } → fieldName="value", bindingName="v"
            val fieldNames = pattern.fields.map { it.name }
            val fieldSlots = pattern.fields.map { fp ->
                if (fp.pattern is TypedRecordPattern) {
                    // Nested record pattern: bind to temp slot (allocated by collectNestedDestructuring)
                    localsByName["__tmp_${fp.name}"] ?: forceAllocLocal("__tmp_${fp.name}")
                } else {
                    val bindingName = if (fp.pattern is TypedVarPattern) (fp.pattern as TypedVarPattern).name else fp.name
                    scopeStack.lastOrNull()?.get(bindingName)
                        ?: localsByName[bindingName]
                        ?: forceAllocLocal(bindingName)
                }
            }
            PatternIR.Record(fieldNames, fieldSlots, pattern.isOpen)
        }
        is TypedSeqPattern -> {
            PatternIR.Seq(
                pattern.elements.mapIndexed { idx, elem ->
                    when (elem) {
                        is TypedVarPattern -> {
                            scopeStack.lastOrNull()?.get(elem.name)
                                ?: localsByName[elem.name]
                                ?: forceAllocLocal(elem.name)
                        }
                        is TypedWildcardPattern -> -1
                        else -> {
                            // Nested pattern (seq, literal, etc.) — bind to temp slot
                            // (allocated by collectNestedSeqDestructuring)
                            localsByName["__seq_tmp_$idx"] ?: forceAllocLocal("__seq_tmp_$idx")
                        }
                    }
                },
                run {
                    val restName = pattern.rest
                    // "_" is the tail-ignoring rest (`..._`): match the tail but bind nothing,
                    // just like a wildcard element (slot -1).
                    if (restName != null && restName != "_") {
                        scopeStack.lastOrNull()?.get(restName)
                            ?: localsByName[restName]
                            ?: forceAllocLocal(restName)
                    } else -1
                },
                // hasRest: any rest (named OR `..._`) makes this an open, length>=N pattern.
                hasRest = pattern.rest != null
            )
        }
        is TypedStringInterpPattern -> {
            val partTypes = mutableListOf<Int>()
            val literals = mutableListOf<String?>()
            // captureSlots is parallel with partTypes: -1 for literals, actual slot for captures
            val captureSlots = mutableListOf<Int>()
            for (part in pattern.parts) {
                when (part) {
                    is TypedStringPatternPart.Literal -> {
                        partTypes.add(0) // 0 = literal
                        literals.add(part.value)
                        captureSlots.add(-1) // no slot for literal parts
                    }
                    is TypedStringPatternPart.Capture -> {
                        partTypes.add(1) // 1 = capture
                        literals.add(null)
                        val slot = scopeStack.lastOrNull()?.get(part.variableName)
                            ?: localsByName[part.variableName]
                            ?: forceAllocLocal(part.variableName)
                        captureSlots.add(slot)
                    }
                }
            }
            PatternIR.StringInterp(partTypes, literals, captureSlots)
        }
    }
}
