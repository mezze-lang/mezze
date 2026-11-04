package com.mezze.language.typechecker.mono

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.TypeVar
import com.mezze.language.ast.collectTypeVarsInOrder
import com.mezze.language.typedast.AbilityImplRef
import com.mezze.language.typedast.BindingRef
import com.mezze.language.typedast.Hash
import com.mezze.language.typedast.TypedAbilityImpl
import com.mezze.language.typedast.TypedBlock
import com.mezze.language.typedast.TypedCall
import com.mezze.language.typedast.TypedDecl
import com.mezze.language.typedast.TypedExpr
import com.mezze.language.typedast.TypedLambda
import com.mezze.language.typedast.TypedLetDecl
import com.mezze.language.typedast.TypedStatement
import com.mezze.language.typedast.TypedVar
import com.mezze.language.typedast.forEachChild
import com.mezze.language.typedast.mapChildren

/**
 * Monomorphise a POLYMORPHIC LOCAL LAMBDA at the type its uses ask for.
 *
 * A do-block `let` bound to a lambda generalizes (#76), and generalization is
 * suppressed only for a NON-GROUND capture (#15). So the ordinary way of naming
 * a step in a pipeline
 *
 *     let scaled = { xs } -> iter::map { it = xs, f = { x } -> x * factor }
 *
 * produces a local POLYMORPHIC function. Its body is compiled once, generically:
 *
 *     LET  scaled  ({xs: Iter['h660]} -> Iter['o659])  with <NumMul Int 'h660 having out='o659>
 *     CALL scaled  ({xs: Iter[Int]}   -> Iter[Int])    with <NumMul Int Int    having out=Int>
 *
 * The call site is already fully concrete and already names the impl that
 * discharges the obligation — but the BODY keeps the variables, so `x * factor`
 * stays a dictionary field read, `classifyUnaryLiteralLambda` cannot classify
 * it, the chain does not fuse, and the pipeline runs element-at-a-time.
 * `bench/cases/dotprobe` measures the two spellings at 63 456 ps/elem against
 * 136.
 *
 * `Monomorphizer` cannot reach this. It specializes DECLS, keyed by content
 * hash, and emits new ones; a local binding is not a decl, and the function it
 * sits inside is already concrete so no specialization of it is ever produced.
 * Hence a pass of its own — but one that reuses the decl path's two real steps:
 * substitute the type arguments into the body ([TypeSubstitution.applyExpr]) and
 * rewrite the constraint-driven method calls against the resolved impls
 * (`rewriteConstrainedMethodCalls`, handed in as [rewriteOps]).
 *
 * WHAT IT DELIBERATELY DOES NOT DO. No lambda lifting and no closure
 * conversion: the binding stays exactly where it is, so its captures are
 * untouched — which is the whole reason to specialize in place rather than
 * hoist. And a lambda whose uses disagree on the type keeps its polymorphism;
 * this pass only ever removes polymorphism that nothing was using.
 */
internal object LocalLambdaSpecializer {

    fun specializeDecls(
        decls: List<TypedDecl>,
        abilityImpls: List<TypedAbilityImpl>,
        recordShapes: Map<Hash, Map<String, MezzeType>>,
        rewriteOps: (TypedExpr, List<AbilityImplRef>) -> TypedExpr,
    ): List<TypedDecl> {
        val ctx = Ctx(abilityImpls, recordShapes, rewriteOps)
        return decls.map { d -> if (d is TypedLetDecl) d.copy(value = rewrite(d.value, ctx)) else d }
    }

    private class Ctx(
        val abilityImpls: List<TypedAbilityImpl>,
        val recordShapes: Map<Hash, Map<String, MezzeType>>,
        val rewriteOps: (TypedExpr, List<AbilityImplRef>) -> TypedExpr,
    ) {
        var specialized = 0
    }

    /** Bottom-up, so a block nested in a specialized lambda is handled too. */
    private fun rewrite(expr: TypedExpr, ctx: Ctx): TypedExpr {
        val mapped = expr.mapChildren { child -> rewrite(child, ctx) }
        return if (mapped is TypedBlock) specializeBlock(mapped, ctx) else mapped
    }

    private fun specializeBlock(block: TypedBlock, ctx: Ctx): TypedBlock {
        var statements = block.statements
        for (i in statements.indices) {
            val stmt = statements[i] as? TypedStatement.Let ?: continue
            val lambda = stmt.value as? TypedLambda ?: continue
            val generic = stmt.type as? MezzeType.FunctionType ?: continue

            // Only a binding that carries an obligation is worth specializing: a
            // polymorphic local with no ability constraint has no dictionary to
            // remove, and substituting its element type would buy nothing while
            // costing a tree rebuild.
            if (generic.abilityConstraints.isEmpty()) continue

            val vars = collectTypeVarsInOrder(generic, sortFields = false).distinctBy { it.name }
            if (vars.isEmpty()) continue

            // `isRecursive` means the body calls the binding's own name. Its
            // self-call would have to be rewritten to the specialized version
            // too, which this pass does not do, so leave it generic.
            if (stmt.isRecursive) continue

            val uses = useTypes(stmt.name, statements, i + 1, block.result) ?: continue
            if (uses.size != 1) continue
            val concrete = uses.first() as? MezzeType.FunctionType ?: continue

            val byName = HashMap<String, MezzeType>()
            if (!unify(generic, concrete, byName)) continue
            // Every variable the generic type names must be pinned. A partial
            // binding would leave a variable behind and the dictionary with it.
            if (vars.any { it.name !in byName }) continue
            if (byName.values.any { !isConcrete(it) }) continue

            // Resolve the obligation from the CALL SITE's type, whose constraint
            // arguments are already concrete — so no typeParam/typeArg mapping is
            // needed to read them.
            val impls = MonoDriver.resolveAbilityConstraintsOf(
                concrete, emptyList(), emptyList(), ctx.abilityImpls, ctx.recordShapes,
            )
            if (impls.size != concrete.abilityConstraints.size) continue

            val sub = substitutionFor(generic, lambda, byName)
            if (sub.isEmpty()) continue

            // Drop the discharged constraints from the signature. They are now
            // evidence in the body, not a demand on the caller, and leaving them
            // on the type would have lowering thread a dictionary that the
            // rewritten body no longer reads.
            val newType = (TypeSubstitution.applyType(generic, sub) as? MezzeType.FunctionType)
                ?.copy(abilityConstraints = emptyList())
                ?: continue
            val newValue = ctx.rewriteOps(TypeSubstitution.applyExpr(lambda, sub), impls)

            statements = statements.toMutableList().also {
                it[i] = stmt.copy(value = newValue, type = newType)
            }
            ctx.specialized++
        }
        return if (statements === block.statements) block else block.copy(statements = statements)
    }

    /**
     * The types [name] is CALLED at, over the statements from [from] on and the
     * block's result — or null if the question cannot be answered safely.
     *
     * Null, not an empty set, for the cases where specializing would be wrong:
     * a rebinding of the same name further down the block (the later uses are a
     * different thing), or a reference to the name that is not a call (it is
     * passed on, or returned, and its type there is not this pass's to pin).
     */
    private fun useTypes(
        name: String,
        statements: List<TypedStatement>,
        from: Int,
        result: TypedExpr,
    ): Set<MezzeType>? {
        val found = LinkedHashSet<MezzeType>()
        val region = ArrayList<TypedExpr>()
        for (j in from until statements.size) {
            when (val s = statements[j]) {
                is TypedStatement.Let -> {
                    if (s.name == name) return null   // shadowed from here on
                    region.add(s.value)
                }
                is TypedStatement.Expr -> region.add(s.expr)
            }
        }
        region.add(result)
        for (e in region) if (!collectUses(e, name, found)) return null
        return found
    }

    /** False as soon as the name is met anywhere other than in call position. */
    private fun collectUses(expr: TypedExpr, name: String, out: MutableSet<MezzeType>): Boolean {
        if (expr is TypedCall) {
            val fn = expr.function
            if (fn is TypedVar && fn.name == name && fn.binding is BindingRef.Local) {
                out.add(fn.type)
                return collectUses(expr.arg, name, out)
            }
        }
        if (expr is TypedVar && expr.name == name && expr.binding is BindingRef.Local) return false
        // A lambda that rebinds the name as a parameter hides ours; anything
        // under it is not a use of this binding, and this pass does not try to
        // tell the two apart.
        if (expr is TypedLambda && expr.params.any { it.name == name }) return true
        var ok = true
        expr.forEachChild { child -> if (ok && !collectUses(child, name, out)) ok = false }
        return ok
    }

    /**
     * Point every `TypeVar` the lambda mentions at its concrete type.
     *
     * By NAME, exactly as [Monomorphizer.buildSubstitution] does and for the
     * same reason: the solver mints a fresh id per binding site, so one written
     * variable appears as several `TypeVar` instances sharing a name.
     */
    private fun substitutionFor(
        generic: MezzeType.FunctionType,
        lambda: TypedLambda,
        byName: Map<String, MezzeType>,
    ): Map<TypeVar, MezzeType> {
        val sub = HashMap<TypeVar, MezzeType>()
        for (tv in collectTypeVarsInOrder(generic, sortFields = false)) {
            byName[tv.name]?.let { sub[tv] = it }
        }
        fun walk(e: TypedExpr) {
            for (tv in collectTypeVarsInOrder(e.type, sortFields = false)) {
                byName[tv.name]?.let { sub[tv] = it }
            }
            if (e is TypedLambda) for (p in e.params) {
                for (tv in collectTypeVarsInOrder(p.type, sortFields = false)) {
                    byName[tv.name]?.let { sub[tv] = it }
                }
            }
            e.forEachChild { walk(it) }
        }
        walk(lambda)
        return sub
    }
}
