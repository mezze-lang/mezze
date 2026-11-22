package com.mezze.language.typechecker.compiler

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.hashing.MezzeHashBuilder
import com.mezze.language.ast.hashing.appendType
import com.mezze.language.ast.normalizeTypeVariables
import com.mezze.language.typedast.Hash
import com.mezze.language.typedast.TypedLambda

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
