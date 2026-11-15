package com.mezze.language.typechecker.validation

import com.mezze.language.parsedast.*

/**
 * A `with <Ability> …` constraint must supply EVERY parameter of the ability it
 * names.
 *
 * ```mezze
 * ability Make 'b for 'a where { make: { it: 'a } -> 'b }
 *
 * pub let via: { x: 'a } with Make 'b 'a -> 'b = { x } -> x.make {}   # fine
 * pub let via: { x: Src } with Make 'b    -> 'b = { x } -> x.make {}  # refused
 * pub let via: { x: Src } with Make       -> Int = { x } -> 1         # refused
 * ```
 *
 * USER RULE. An ability's parameters are positional — `ability Make 'b for 'a`
 * declares `['b, 'a]`, class parameters first and the receiver last — and a
 * constraint that names only some of them leaves the rest to be guessed. What
 * was measured before this rule (BUG 41 ii):
 *
 *     with Make 'b       (1 of 2)  ->  V0402 "Int, Int does not implement 'Make'"
 *     with Conv Cfg 'b   (2 of 3)  ->  V0402 "Int, Cfg, Int does not implement 'Conv'"
 *
 * Neither tuple can exist. `Make`'s receiver is `Src`, never `Int`; `Conv`'s
 * slots are `[Cfg, Str, Src]`, not `[Int, Cfg, Int]` — the omitted slot picked
 * up a COPY of a neighbour, and the arguments shifted. So a typo in a
 * constraint reported a missing impl for a tuple nobody wrote, and the author
 * had no way to connect the message to the mistake. Reporting the arity instead
 * names the actual error at the place it was made.
 *
 * `with` with no arguments at all used to be accepted at the declaration and
 * only surfaced as a T0210 once the function was called.
 *
 * WHY AS-WRITTEN. This is a property of the text — count the arguments, count
 * the declared parameters — so it belongs with the other well-formedness checks
 * that run before the solver. A module that names an ability wrongly never
 * reaches dispatch, which is where the consequences used to appear.
 *
 * SCOPE. Only constraints naming an ability whose declaration this validator can
 * see: one declared in the same module, or one the caller's [paramsOf] resolves.
 * An ability it cannot find is SKIPPED rather than guessed about — an unknown
 * name is `V0401`'s business, and inventing an arity error for an import this
 * pass cannot resolve would be the same kind of guess the rule exists to stop.
 */
internal class WithConstraintArityValidator(
    /** Declared parameters for an imported ability, in order, or null when the
     *  ability cannot be resolved from here. */
    private val paramsOf: (String) -> List<String>? = { null },
) {
    fun validate(module: ParsedModule): List<ValidationError> {
        // Locally-declared abilities first: `typeParams` already holds the class
        // parameters AND the receiver (the parser builds it as
        // `genericParams + receiverTypeParam`), so its size IS the arity.
        val local = HashMap<String, List<String>>()
        for (decl in module.decls) {
            if (decl is ParsedAbilityDecl) local[decl.name] = decl.typeParams
        }

        val errors = mutableListOf<ValidationError>()

        fun check(constraints: List<ParsedFnAbilityConstraint>) {
            for (c in constraints) {
                val want = local[c.abilityName] ?: paramsOf(c.abilityName) ?: continue
                val got = c.typeArgs.size
                if (got != want.size) {
                    errors.add(
                        ValidationError.WithConstraintArityMismatch(
                            abilityName = c.abilityName,
                            declaredParams = want,
                            actual = got,
                            loc = c.loc,
                        )
                    )
                }
            }
        }

        /** Every function type inside an annotation, including nested ones —
         *  a callback parameter carries its own `with` clause. */
        fun functionTypes(annotation: ParsedTypeAnnotation): List<ParsedFunctionType> =
            (if (annotation is ParsedFunctionType) listOf(annotation) else emptyList()) +
                annotation.children().flatMap { functionTypes(it) }

        fun checkAnnotation(annotation: ParsedTypeAnnotation?) {
            annotation ?: return
            for (fn in functionTypes(annotation)) check(fn.abilityConstraints)
        }

        /** A `with` clause can also sit on a LAMBDA rather than an annotation. */
        fun checkExpr(expr: ParsedExpr) {
            if (expr is ParsedLambda) check(expr.abilityConstraints)
            expr.children().forEach { checkExpr(it) }
        }

        for (decl in module.decls) {
            when (decl) {
                is ParsedLetDecl -> {
                    checkAnnotation(decl.typeAnnotation)
                    decl.value?.let { checkExpr(it) }
                }
                // An ability's own operations, where the constraint is on the op
                // rather than on an annotation.
                is ParsedAbilityDecl ->
                    for (op in decl.operations) {
                        check(op.abilityConstraints)
                        checkAnnotation(op.paramType)
                        checkAnnotation(op.returnType)
                    }
                // AN IMPL'S OPERATIONS ARE WHERE THE STDLIB WRITES THEM — all
                // twelve `with Decode …` constraints sat here, so a check that
                // skipped impl ops would have missed every one of them.
                is ParsedImplAbilityDecl ->
                    for (op in decl.operations) {
                        check(op.abilityConstraints)
                        checkAnnotation(op.paramType)
                        checkAnnotation(op.returnType)
                        checkExpr(op.implementation)
                    }
                else -> Unit
            }
        }
        return errors
    }
}
