package com.mezze.language.typechecker.constraints

import com.mezze.language.ast.MezzeType

/**
 * The type a recursive binding's SELF-REFERENCE is equated with: the binding's
 * own type **with its effect row dropped**.
 *
 * EQUATE THE TYPES, NEVER THE ROW. Unifying a self-reference unifies the whole
 * arrow, and a row is not a set to be equated through a recursive reference. The
 * self-call site cannot know the row its own body will turn out to need, so it
 * builds a PURE arrow — and meeting that with the body's real row fails on the
 * row while every type underneath it would have unified:
 *
 *     let handled = {} -> perform H {} in E.op {}      ## {} -> Int <*>
 *     let go = { i, acc } ->
 *       if i <= 0 then acc else go { i = i - handled {}, acc = acc + i }
 *     ## expected { i: Int, acc: 'a } … -> 'b <*>
 *     ## but got  { i: 'd, acc: 'c }  -> 'e
 *
 * Nothing there performs an UNHANDLED effect — `<*>` means "handled here", a
 * marker rather than an obligation — so the loop is ordinary and was refused
 * (known-bugs BUG 35). Dropping the row keeps param, return and every
 * ability-constraint variable unifying, and leaves rows to the effect machinery
 * that owns them.
 *
 * Why the equation is wanted at all: without it nothing ties an accumulator's
 * associated output back to the accumulator, so no call site can resolve it and
 * mono refuses the binding — `{ i: Int, acc: 'a } with NumAdd Int 'a having
 * 'out = out` against a FREE `out`.
 *
 * ONE RULE, TWO SITES. The top-level binding in [ConstraintGenerator] and the
 * do-block `let` in `generateDo` both need it, and only the first had it — which
 * is precisely why BUG 35 was specific to a LOCAL recursive lambda while the same
 * loop at top level was accepted. Keeping the rule in one place is what stops the
 * two from disagreeing again.
 */
internal fun selfReferenceType(bindingType: MezzeType): MezzeType = when (bindingType) {
    is MezzeType.FunctionType ->
        if (bindingType.effects == null) bindingType else bindingType.copy(effects = null)
    else -> bindingType
}
