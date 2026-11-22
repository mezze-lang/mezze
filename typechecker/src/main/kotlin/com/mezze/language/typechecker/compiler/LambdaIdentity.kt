package com.mezze.language.typechecker.compiler

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.hashing.MezzeHashBuilder
import com.mezze.language.ast.hashing.appendType
import com.mezze.language.ast.normalizeTypeVariables
import com.mezze.language.typedast.Hash
import com.mezze.language.typedast.BindingRef
import com.mezze.language.typedast.TypedExpr
import com.mezze.language.typedast.TypedLambda
import com.mezze.language.typedast.TypedVar
import com.mezze.language.typedast.forEachChild

/**
 * The types a lambda is lowered at: its parameters', its captures' and its
 * result's, as one hash that does not depend on how their variables happen to
 * be named or numbered. Folded into a lifted lambda's hash by
 * `TypedASTCompiler.saltedLambdaHash`.
 *
 * The expression hash does not see types, and the code a lambda lowers to is
 * decided by them: `{ x } -> x` over `Int`, `Str` and `Float` reads its
 * parameter from a LONG, an OBJECT and a DOUBLE slot and orders its result
 * directly or through an `Ord` instance; a call inside it goes to whichever
 * specialization its argument types select. All of those were ONE hash, and
 * whichever compiled first was the function under it.
 *
 * The types themselves rather than what lowering takes from them today (slot
 * kinds, ordering): everything else in the body is settled by these plus the
 * references the expression hash already covers, so this stays right when
 * lowering starts reading something new. The price is that two types which
 * lower alike are two hashes for the same code — a missed sharing, never a
 * wrong function.
 */
internal fun loweredAtTypes(lambda: TypedLambda): Hash {
    val fields = LinkedHashMap<String, MezzeType>()
    lambda.params.forEachIndexed { i, p -> fields["p$i"] = p.type }
    lambda.captures.forEachIndexed { i, c -> fields["c$i"] = c.type }
    fields["r"] = lambda.body.type
    return MezzeHashBuilder()
        .also { appendType(it, normalizeTypeVariables(MezzeType.RecordType(fields))) }
        .buildHash()
}

/**
 * The other lambdas a lambda's lowered body NAMES BY HASH, which its own
 * expression does not say.
 *
 * Lowering does not always call a captured function through its closure. A
 * captured helper that itself captures nothing is called directly, by hash
 * ([helperAt], by capture); an enclosing recursive or constrained local lambda
 * is rebuilt or dispatched by hash, looked up by name ([named]). The expression
 * hash sees only "capture 0" and a count, so two functions that each define a
 * local `g` and pass `{ x } -> g { y = x }` on gave that lambda ONE hash for two
 * bodies — and the second function ran the first one's `g`.
 *
 * Empty when the body names none, which leaves the hash as it was.
 */
internal fun namedByHash(
    lambda: TypedLambda,
    helperAt: (captureName: String) -> Hash?,
    named: (name: String) -> List<Hash>,
): String = buildString {
    lambda.captures.forEachIndexed { i, c -> helperAt(c.name)?.let { append('c').append(i).append('=').append(it.hex).append(';') } }
    val names = sortedSetOf<String>()
    fun walk(e: TypedExpr) {
        if (e is TypedVar && (e.binding is BindingRef.Local || e.binding is BindingRef.Capture)) names.add(e.name)
        e.forEachChild { walk(it) }
    }
    walk(lambda.body)
    for (n in names) for (h in named(n)) append(n).append('=').append(h.hex).append(';')
}

/**
 * The hash of an extracted lambda's FunctionIR.
 *
 * `TypedASTHasher.hashExpr` is type-blind, so the SAME source lambda inside a
 * generic body and inside each of its mono specializations would collide on one
 * hash while compiling to DIFFERENT FunctionIRs (capture lists and dispatch
 * differ once types are pinned) — last registration wins and other callers run
 * the wrong frame layout. So the expression hash is salted with everything else
 * the lowered function depends on: the enclosing spec ([spec]), the types it is
 * lowered at ([loweredAtTypes]), the lambdas its body names by hash ([named],
 * from [namedByHash]), and its dictionaries.
 *
 * NOT salted by the enclosing function, though `sorted_set.mz` holds eight
 * textually identical `{ a, b } -> if a < b …` comparators (singleton,
 * from_list, add, remove, contains, min_by, max_by, from_seq) that all share one
 * hash. Folding the enclosing function's hash in separates them, but it also
 * ties a nested lambda's identity to its declaration's hash, and adding a
 * `newtype` changes that hash — so two programs identical after newtype erasure
 * emit different IR, which NewtypeErasureIRTest rightly rejects. Tried for BUG 7
 * and reverted: it separated the eight and changed no behaviour, so the
 * collision is not what picks the wrong comparator.
 */
internal fun lambdaHashOf(
    expr: TypedExpr,
    spec: Hash?,
    dictCaptureNames: List<String>,
    ownDicts: Set<String>,
    named: String,
): Hash {
    val base = com.mezze.language.typedast.TypedASTHasher.hashExpr(expr)
    val types = (expr as? TypedLambda)?.let { loweredAtTypes(it) }
        ?: if (spec == null && dictCaptureNames.isEmpty() && ownDicts.isEmpty()) return base else null
    val b = MezzeHashBuilder()
    b.append("spec_lambda")
    b.append(base.hex)
    if (spec != null) b.append(spec.hex)
    if (types != null) { b.append("at_types"); b.append(types.hex) }
    if (named.isNotEmpty()) { b.append("names"); b.append(named) }
    // The expr hash is type-blind, so the capture LIST has to be folded in:
    // two textually identical comparators that capture different dicts are
    // different functions, and without this they collide onto one registry
    // entry and last-registration-wins runs the wrong one — the #106 collision,
    // environment flavor. See SeqKeyOrdTest.
    //
    // The names, NOT the parent frame slot they are read from. The slot is where
    // the PARENT keeps the dict; it appears in the parent's MakeLambda capture
    // list, never in this lambda's body or its own capture names. Folding it in
    // gave one lambda several identities for a difference it does not contain —
    // over-separation, and the opposite of the stated rule that identity comes
    // from what the lambda IS. Measured on removal: zero change across the
    // interpreter suite (6242 tests), the typechecker suite, SeqKeyOrdTest which
    // is the test the slot was added for, and DedupeCollisionGateTest, which
    // would fail if two differing functions started sharing a hash.
    for (name in dictCaptureNames) b.append(name)
    // Dictionary PARAMETERS change the function's arity, so they are part of
    // its identity for the same reason: the hash is type-blind, and the same
    // text unconstrained takes no such parameters.
    if (ownDicts.isNotEmpty()) {
        b.append("own_dict_params")
        for (name in ownDicts.sorted()) b.append(name)
    }
    return b.buildHash()
}
