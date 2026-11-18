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
 * root, and the perform boundary must be the frame that catches it. The body is
 * BUILT inside a lambda scope, exactly as a written `{} -> body` is, so the
 * enclosing function's parameters and lets come out at depth >= 1 and are
 * collected as captures. (BUG 69/70: wrapping the body AFTER it was typed in the
 * enclosing scope left them at depth 0 — not captures — so the thunk read its own
 * uninitialised frame slots: a stale Boolean/String cast to a continuation root,
 * or an Illegal frame slot.)
 */
internal fun TypedAstBuilder.buildPerformBodyThunk(parsedBody: ParsedExpr, file: String, contextType: MezzeType?, loc: Loc): TypedLambda {
    pushScope(mutableListOf(), isLambdaBoundary = true)
    val frame = TypedAstBuilder.DictFrame(dictFrame)
    dictFrame = frame
    enclosingArrowEffects.addLast(null)
    val body = try {
        buildExpr(parsedBody, file, contextType)
    } catch (e: Throwable) {
        dictFrame = frame.parent
        if (frame.parent == null) dictDemands.clear()
        throw e
    } finally {
        enclosingArrowEffects.removeLast()
    }
    popScope()
    val captures = collectCaptures(body, emptySet())
    dictFrame = frame.parent
    if (frame.parent == null) judgeDictDemands()
    return TypedLambda(
        params = emptyList(),
        body = body,
        captures = captures,
        type = MezzeType.FunctionType(MezzeType.RecordType(emptyMap()), body.type),
        loc = loc,
    )
}

/** The performer's ops whose bodies mention `cont` — the only places a park can
 *  originate, since `cont` is V0702 anywhere else. Empty = not cont-capable. */
internal fun contOpsOf(decl: ParsedPerformerDecl?): Set<String> =
    decl?.operations?.filter { op -> mentionsCont(op.implementation) }?.map { it.name }?.toSet() ?: emptySet()
