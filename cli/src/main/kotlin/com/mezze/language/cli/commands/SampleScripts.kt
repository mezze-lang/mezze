package com.mezze.language.cli.commands

import com.mezze.language.langservice.sample.SampleBranches
import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.ModulePath
import com.mezze.language.typedast.TypedEffectDecl
import com.mezze.language.typedast.forEachChild

/**
 * Scripted performers: how `mez sample` runs a function whose effects nothing
 * handles.
 *
 * ## The decision this reverses, and the concern it keeps
 *
 * Sample used to REFUSE such a function, deliberately: running it unhandled
 * returns a suspended computation that renders as something plausible, and "a
 * sample you had to configure would be showing a run you set up rather than what
 * the function does".
 *
 * The second half is the one that matters, and it is honoured here rather than
 * waived: the user configures nothing, and every value an operation returns is
 * drawn by the ROW — up front, like an argument — then handed to the performer as
 * a field and shown in the table as its own column (`⟨Config.ask⟩`). So nothing
 * about the run is hidden inside a handler; an operation's result is just another
 * input, and the row says what it was.
 *
 * That framing is also why it is right rather than merely convenient. Mezze's
 * effect row is the complete list of a function's hidden inputs: anything it can
 * observe that the caller does not pass in arrives through an operation. Drawing
 * those operations' results is sampling the function's inputs — all of them.
 *
 * ## What is scripted, and what is still refused
 *
 * An effect is scriptable when its declaration can be found by HASH (the row
 * carries one per effect), it has no associated types, and its operations are
 * monomorphic. Each operation's results are a short sequence the row draws, handed
 * out in call order (`std::effects::sample::scripted`) — so `let a = now {}; let
 * b = now {}; b > a` can come out either way. The column shows the whole sequence.
 * An operation returning `{}` (`Log.log`) is answered with `{}` and gets no column.
 *
 * Anything else is refused as before, naming the effect: a polymorphic operation
 * (`Exception.throw : … -> 'a`) has no type to draw at, and an associated type is
 * part of the effect's contract that a script would have to invent.
 */
object SampleScripts {

    data class Op(
        val name: String,
        val params: List<String>,
        val returnType: MezzeType,
        /**
         * Whether the target can REACH this operation — from its own body or from
         * anything it calls. An operation it cannot reach still gets a value, so the
         * performer is total, but no column: it is not an input to this function.
         * Reachable rather than "in the body", because hiding a column for an
         * operation a helper performs would hide an input the function does see.
         */
        val called: Boolean = true,
    ) {
        /** An operation returning `{}` carries nothing worth drawing or showing. */
        val valued: Boolean get() = !(returnType is MezzeType.RecordType && returnType.fields.isEmpty())
        /** Gets a column: carries a value AND the function can observe it. */
        val shown: Boolean get() = valued && called
    }

    data class Script(
        val effect: String,
        /** The effect's declaration hash — what a `TypedPerformOp` names it by. */
        val hash: com.mezze.language.typedast.Hash,
        /** `use` line that brings the effect into scope, or null when it is declared locally. */
        val importLine: String?,
        val ops: List<Op>,
    ) {
        /** Uppercase, like every performer; prefixed so it cannot collide with a user's. */
        val performer: String get() = "MezSampleScript$effect"
        fun field(op: Op): String = "r_${op.name}"
        fun label(op: Op): String = "⟨$effect.${op.name}⟩"
        /** Identity of this operation's result as a row input: effect HASH and operation. */
        fun key(op: Op): String = SampleBranches.contextKey(hash, op.name)
    }

    /** Every valued operation of [scripts] as analysis context: key → (label, type). */
    fun context(scripts: List<Script>): Map<String, Pair<String, MezzeType>> =
        scripts.flatMap { sc -> sc.ops.filter { it.valued }.map { sc.key(it) to (sc.label(it) to it.returnType) } }.toMap()

    /**
     * Context keys of every operation reachable from [root]: its body, then every
     * declaration it references by HASH, transitively. A visited set bounds it, so
     * recursion and mutual recursion terminate.
     */
    fun reachedOperations(
        root: com.mezze.language.typedast.TypedLetDecl,
        decls: Map<com.mezze.language.typedast.Hash, com.mezze.language.typedast.TypedLetDecl>,
    ): Set<String> {
        val keys = mutableSetOf<String>()
        val seen = mutableSetOf<com.mezze.language.typedast.Hash>()
        val pending = ArrayDeque(listOf(root))
        while (pending.isNotEmpty()) {
            val d = pending.removeFirst()
            if (!seen.add(d.hash)) continue
            fun walk(e: com.mezze.language.typedast.TypedExpr) {
                when (e) {
                    is com.mezze.language.typedast.TypedPerformOp -> keys.add(SampleBranches.contextKey(e.effectHash, e.operationName))
                    is com.mezze.language.typedast.TypedVar ->
                        (e.binding as? com.mezze.language.typedast.BindingRef.ByHash)?.let { ref -> decls[ref.hash]?.let { pending.add(it) } }
                    else -> Unit
                }
                e.forEachChild { walk(it) }
            }
            walk(d.value)
        }
        return keys
    }

    sealed interface Plan {
        data class Scripted(val scripts: List<Script>) : Plan
        data class Refused(val effect: String, val reason: String) : Plan
    }

    /**
     * Scripts for every concrete effect left on [residual], or the first effect
     * that cannot be scripted and why.
     *
     * [local] is the target module's effect declarations; [loaded] the imported
     * modules' — keyed by path so an imported effect gets a `use` line.
     */
    fun plan(
        residual: MezzeType.EffectRow,
        local: List<TypedEffectDecl>,
        loaded: Map<ModulePath, List<TypedEffectDecl>>,
    ): Plan {
        if (residual.effectInstances.isNotEmpty()) {
            val inst = residual.effectInstances.first()
            return Plan.Refused(inst.name, "it carries an associated type, which a script would have to invent")
        }
        val scripts = mutableListOf<Script>()
        for (name in residual.effects.sorted()) {
            val hash = residual.effectHashes[name]
                ?: return Plan.Refused(name, "its declaration could not be identified")
            val localDecl = local.firstOrNull { it.hash == hash }
            val (decl, importLine) = if (localDecl != null) {
                localDecl to null
            } else {
                val (path, d) = loaded.entries.firstNotNullOfOrNull { (p, ds) ->
                    ds.firstOrNull { it.hash == hash }?.let { p to it }
                } ?: return Plan.Refused(name, "its declaration could not be found")
                d to "use ${path.fullPath}::{ ${d.name} }"
            }
            if (decl.associatedTypes.isNotEmpty()) {
                return Plan.Refused(name, "it declares associated types, which a script would have to invent")
            }
            val ops = decl.operations.map { op ->
                if (mentionsTypeVar(op.returnType) || mentionsTypeVar(op.paramType)) {
                    return Plan.Refused(name, "operation `${op.name}` is polymorphic, so there is no type to draw its result at")
                }
                val params = (op.paramType as? MezzeType.RecordType)?.fields?.keys?.toList() ?: emptyList()
                Op(op.name, params, op.returnType)
            }
            scripts.add(Script(decl.name, decl.hash, importLine, ops))
        }
        return Plan.Scripted(scripts)
    }

    /**
     * `impl E for MezSampleScriptE { r_op: [T], …, key: Int } where {
     *    op = { a } -> scripted { values = r_op, key = key, op = 0 }, … }`
     *
     * The values are FIELDS so the row draws them where it can show them; the
     * performer only hands them back, one per call. [key] is the row's, so no two
     * rows share a position in a sequence.
     */
    fun implDecl(script: Script, render: (MezzeType) -> String): String {
        val valued = script.ops.filter { it.valued }
        val fields = if (valued.isEmpty()) "" else
            (valued.map { "${script.field(it)}: [${render(it.returnType)}]" } + "$KEY: Int")
                .joinToString(", ", prefix = " { ", postfix = " }")
        val ops = script.ops.joinToString(",\n") { op ->
            val params = op.params.joinToString(", ", prefix = "{ ", postfix = " }").let { if (op.params.isEmpty()) "{}" else it }
            val answer = if (!op.valued) "{}"
                else "scripted { values = ${script.field(op)}, key = $KEY, op = ${valued.indexOf(op)} }"
            "  ${op.name} = $params -> $answer"
        }
        return "impl ${script.effect} for ${script.performer}$fields where {\n$ops\n}"
    }

    /** The field naming the row, so each row's sequences have their own positions. */
    const val KEY = "mez_row_key"

    /** How many results the row draws for each operation; calls beyond repeat them. */
    const val CALLS = 4

    /** What a scripted performer needs in scope. */
    const val IMPORT = "use std::effects::sample::{ scripted }"

    /** Through `MezzeType.anyChild`, the one exhaustive child walker — no local `when`. */
    private fun mentionsTypeVar(t: MezzeType): Boolean =
        t is MezzeType.VarType || t.anyChild { mentionsTypeVar(it) }
}
