package com.mezze.language.typechecker.constraints

import com.mezze.language.ast.MezzeType
import java.util.IdentityHashMap

/**
 * Which of a growing list of constraints can affect a given set of variables:
 * those connected to them through shared variables, directly or through other
 * constraints.
 *
 * A constraint that shares no variable with them, by any chain, constrains
 * nothing they could be solved to — solving it alongside them changes none of
 * their answers. A do-block solves each `let` locally to bind it, and solved
 * everything the block had accumulated so far each time: a block of k lets
 * solved its first statement's constraints k times.
 *
 * Variables are type variables and the row variables of open records, variants
 * and effect rows. Each constraint is indexed once, when first seen.
 */
internal class ConstraintComponents {
    private val parent = HashMap<Any, Any>()

    /** A variable of each indexed constraint — any one, they are all joined — or null if it has none. */
    private val anchor = IdentityHashMap<Constraint, Any?>()

    /**
     * The constraints of [constraints] connected to [seeds], in their order. A
     * constraint with no variables affects no variable, and is left out: it can
     * only fail, and the full solve that follows reports that.
     */
    fun relevant(constraints: List<Constraint>, seeds: Set<Any>): List<Constraint> {
        for (c in constraints) index(c)
        val roots = seeds.mapTo(HashSet()) { find(it) }
        return constraints.filter { c -> anchor[c]?.let { find(it) in roots } == true }
    }

    private fun index(c: Constraint) {
        if (anchor.containsKey(c)) return
        val vars = HashSet<Any>()
        c.collectVariables(vars)
        val first = vars.firstOrNull()
        if (first != null) for (v in vars) union(first, v)
        anchor[c] = first
    }

    private fun find(x: Any): Any {
        var root = x
        while (true) root = parent[root]?.takeIf { it != root } ?: break
        // Path compression: everything on the way points at the root.
        var at = x
        while (at != root) {
            val next = parent.getValue(at)
            parent[at] = root
            at = next
        }
        return root
    }

    private fun union(a: Any, b: Any) {
        val ra = find(a)
        val rb = find(b)
        if (ra != rb) parent[ra] = rb
    }
}

/** Every variable [this] constraint mentions, into [out]. */
internal fun Constraint.collectVariables(out: MutableSet<Any>) {
    when (this) {
        is Constraint.Equality -> { collectVariables(left, out); collectVariables(right, out) }
        is Constraint.AbilityRequired -> { out += typeVar; typeArgs.forEach { collectVariables(it, out) } }
        is Constraint.AssociatedTypeResolution -> {
            out += primaryTypeVar
            out.addAll(associatedTypeVars.values)
            out.addAll(classArgTypeVars)
        }
        is Constraint.Subsumes -> { collectVariables(general, out); collectVariables(specific, out) }
        is Constraint.HasField -> { collectVariables(recordType, out); collectVariables(fieldType, out) }
        is Constraint.HasConstructor -> { collectVariables(variantType, out); payloadType?.let { collectVariables(it, out) } }
        is Constraint.MustBeOpen -> collectVariables(type, out)
        is Constraint.RecordPatternExhaustive -> collectVariables(recordType, out)
        is Constraint.InherentMethodResolve -> {
            collectVariables(receiverType, out)
            collectVariables(argsType, out)
            collectVariables(resultType, out)
            candidates.forEach { collectVariables(it.methodType, out) }
        }
        is Constraint.DefaultAssociatedType -> { collectVariables(bindingType, out); collectVariables(shape, out) }
    }
}

/** Every variable [type] mentions — type variables and open rows' row variables — into [out]. */
internal fun collectVariables(type: MezzeType, out: MutableSet<Any>) {
    if (type.isGround) return
    when (type) {
        is MezzeType.VarType -> out += type.variable
        is MezzeType.RecordType -> type.rowVar?.let { out += it }
        is MezzeType.VariantType -> type.rowVar?.let { out += it }
        is MezzeType.FunctionType -> type.effects?.rowVar?.let { out += it }
        is MezzeType.EffectRow -> type.rowVar?.let { out += it }
        is MezzeType.NewtypeType, is MezzeType.TypeApplication,
        MezzeType.IntType, MezzeType.FloatType, MezzeType.StringType,
        MezzeType.FracType, MezzeType.CharType, MezzeType.BytesType -> {}
    }
    type.anyChild { collectVariables(it, out); false }
}
