package com.mezze.language.typechecker.emit

import com.mezze.language.ast.*
import com.mezze.language.parsedast.*
import com.mezze.language.typedast.*

/**
 * Continuations: a performer is CONT-CAPABLE when any of its op bodies names
 * `cont`. The check is SYNTACTIC on purpose — it is the capability tax's whole
 * trigger, and a handler that never says `cont` pays nothing (design: "the tax
 * is only paid by a block whose handler mentions `cont`"). `cont` is a
 * runtime-bound keyword, so this is a keyword rule, not a name match on user
 * code.
 */
internal fun mentionsCont(expr: ParsedExpr): Boolean {
    if (expr is ParsedIdentifier && expr.qualifier == null && expr.localName == ScopedEffectConstants.CONT) return true
    return expr.children().any { mentionsCont(it) }
}

/**
 * A cont-capable perform's body becomes its own ROOT: a BDSL yield suspends a
 * root, and the perform boundary must be the frame that catches it. Captures are
 * the body's free locals, exactly as for a written lambda.
 */
internal fun TypedAstBuilder.performBodyAsThunk(body: TypedExpr, loc: Loc): TypedLambda =
    TypedLambda(
        params = emptyList(),
        body = body,
        captures = collectCaptures(body, emptySet()),
        type = MezzeType.FunctionType(MezzeType.RecordType(emptyMap()), body.type),
        loc = loc,
    )

/** The performer's ops whose bodies mention `cont` — the only places a park can
 *  originate, since `cont` is V0702 anywhere else. Empty = not cont-capable. */
internal fun contOpsOf(decl: ParsedPerformerDecl?): Set<String> =
    decl?.operations?.filter { op -> mentionsCont(op.implementation) }?.map { it.name }?.toSet() ?: emptySet()
