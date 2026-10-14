package com.mezze.language.langservice.sample

import com.mezze.language.ast.MezzeType

/**
 * Mezze source that generates a value of a given type.
 *
 * The command ALREADY knows every argument's type — it read the function's parameter
 * off the typed declaration. So there is no reason to emit a bare
 * `Arbitrary.arbitrary {}` and hope the call site pins it; the type is in hand and
 * the generator can be written from it directly. That removes the annotation
 * question entirely, and with it the difference between a named parameter type and
 * one written inline.
 *
 * ## It ROUTES to `Arbitrary` — it does not reimplement it
 *
 * The default for any type is `Arbitrary.arbitrary {}`, so every instance the
 * compiler derived and every one an author wrote is what actually produces the
 * value. Which values are interesting for an `Int` is decided in
 * `std::abilities::arbitrary` and nowhere else.
 *
 * Descending structurally happens only where DISPATCH cannot reach, and each case
 * is a compiler gap with a task against it rather than a style choice:
 *
 *  - `Option` / `Result`, because no instance exists for them at all — not for want
 *    of a body, but because the coherence rule refuses a hand-written one and the
 *    thing that would derive it cannot name the ability (S-4, #161). Generating the
 *    constructors inline needs no instance for the container.
 *  - an ANONYMOUS record, because an anonymous annotation does not pin a
 *    receiver-less operation, so there is nothing to write a binding against.
 *  - a RECURSIVE variant, which is the one arm that is not a gap: bounding the
 *    recursion needs the `Sample` size budget, and a DERIVED impl cannot name it —
 *    it is written into the user's module, which has no reason to import sampling.
 *    This command injects its own imports, so here it can.
 *
 * When those tasks land, each arm here becomes dead and should be deleted — the
 * default already does the right thing. This file is a bridge, and its arms are the
 * list of what it is bridging.
 *
 * The SEQUENCE arm is gone, and that is what this list looked like one entry
 * longer. It emitted `list_of { g = … }` to put the inner draw at a concrete
 * element type "where dispatch works, and it cannot at `[T]` itself" — true until
 * S-10 fixed the monomorphizer hole that made a parameterised impl of a
 * receiver-less operation unreachable. `let xs: [Int] = Arbitrary.arbitrary {}` now
 * resolves through `impl Arbitrary for ['a]`, so how long a sequence is and how its
 * elements are drawn is back where it belongs: in the ability.
 *
 * The weights match `std::abilities::arbitrary`'s own, for the obvious reason: a
 * value's distribution should not depend on whether it was reached through dispatch
 * or through this file.
 */
object SampleGen {

    /**
     * Imports the emitted source needs. The command prepends these.
     *
     * `list_of` is no longer among them: nothing emitted here calls it since the
     * sequence arm went away. `choose` is still needed for the `Option`/`Result`
     * weighting, and `size`/`resize` for the recursive-variant helper.
     *
     * S-21: the performer is `CoveringSample`, not `SeededSample` — see
     * [SampleCommand.rowBinding]. A performer name has to be IMPORTED to be
     * installable; without it the generated rows fail with "Unknown performer",
     * which is what this list is for.
     */
    val IMPORTS: Set<String> = setOf(
        "use std::effects::sample::{ CoveringSampleWith, choose, size, resize, one_of, list_of }",
        "use std::abilities::arbitrary::{ Arbitrary }",
    )

    /**
     * Mezze source for a value of [type].
     *
     * [depth] bounds the descent. A type cannot nest infinitely through records and
     * containers — a recursive one goes through a NAMED declaration, which takes the
     * dispatch default and stops here — but the bound is cheap and a generator that
     * recursed forever at compile time would be a worse failure than a missing case.
     */
    /**
     * A value of [type] built from its STRUCTURE, for a type that holds an
     * unwrapped newtype: the newtype as `Ctor.(<its representation>)`, a record
     * field by field, a variant as a uniform pick among thunks — one per
     * constructor, so only the chosen one draws — and the containers whose shape is
     * plain to write. Null for anything else, which then dispatches and reports.
     */
    private fun structural(
        type: MezzeType,
        depth: Int,
        recursive: MutableMap<String, MezzeType.VariantType>,
        unwrap: Unwrap,
    ): String? {
        fun inner(t: MezzeType) = of(t, depth + 1, recursive, unwrap)
        unwrap.newtypeOf(type)?.let { n -> return "${n.constructor}.(${inner(n.wrappedType)})" }
        fun pick(thunks: List<String>): String =
            if (thunks.size == 1) "(${thunks.single()}) {}"
            else "(one_of { xs = [${thunks.joinToString(", ")}], def = ${thunks.first()} }) {}"
        return when (type) {
            is MezzeType.RecordType ->
                if (type.fields.isEmpty()) "{}"
                else type.fields.entries.joinToString(", ", prefix = "{ ", postfix = " }") { (f, t) -> "$f = ${inner(t)}" }
            is MezzeType.VariantType -> pick(type.constructors.map { (c, payload) ->
                "{} -> " + (if (payload == null) c else "$c ${inner(payload)}")
            })
            is MezzeType.TypeApplication -> when (type.constructor) {
                "Seq" -> "list_of { g = {} -> ${inner(type.args.single())} }"
                "Option" -> pick(listOf("{} -> None", "{} -> Some { val = ${inner(type.args.single())} }"))
                "Result" -> pick(listOf(
                    "{} -> Ok { val = ${inner(type.args[0])} }",
                    "{} -> Err { error = ${inner(type.args[1])} }",
                ))
                else -> null
            }
            else -> null
        }
    }

    /**
     * Every NEWTYPE reachable from [type], outermost first: through record fields,
     * variant payloads, container arguments and a newtype's own wrapped type.
     *
     * A newtype never derives `Arbitrary` — only its author can say which values are
     * valid — so a parameter holding one (directly or anywhere inside) cannot be
     * generated until someone writes the impl. The command asks this to find the
     * ones nobody has, so it can draw them from their representation instead and SAY
     * that it did. A recursive declaration is reached through a NAME and not walked.
     */
    fun newtypesIn(
        type: MezzeType,
        /**
         * The newtype a DECLARED type application names, or null when it names
         * something else. A newtype parameter arrives as `TypeApplication(Age)`
         * carrying the declaration's identity, not as a `NewtypeType` — the two
         * representations of one declared type — so only the declaration can say.
         */
        resolve: (MezzeType.TypeApplication) -> MezzeType.NewtypeType? = { null },
    ): List<MezzeType.NewtypeType> {
        val out = LinkedHashMap<String, MezzeType.NewtypeType>()
        fun walk(t: MezzeType, depth: Int) {
            if (depth > MAX_DEPTH) return
            val newtype = t as? MezzeType.NewtypeType ?: (t as? MezzeType.TypeApplication)?.let(resolve)
            if (newtype != null) {
                if (out.putIfAbsent(newtype.name, newtype) == null) walk(newtype.wrappedType, depth + 1)
                return
            }
            if (t is MezzeType.FunctionType) { walk(t.returnType, depth + 1); return }
            t.anyChild { child -> walk(child, depth + 1); false }
        }
        walk(type, 0)
        return out.values.toList()
    }

    /**
     * Why a parameter cannot be generated, as `path: reason` lines — the component
     * that blocks it, named where it sits (`u.callback`), not the outer type the
     * compiler reports (`User does not implement 'Arbitrary'`).
     *
     * Two blockers are known structurally: a FUNCTION below the top level (a
     * top-level function parameter gets a constant function, see [of]; one inside a
     * declared record or variant is generated by that type's derived impl, and
     * there is no `Arbitrary` for functions), and a builtin type with no instance —
     * `Promise`, `Foreign` and the like. Empty when neither is found, and the
     * compiler's own message stands alone.
     */
    fun blockers(name: String, type: MezzeType): List<String> {
        val out = mutableListOf<String>()
        fun walk(path: String, t: MezzeType, depth: Int, top: Boolean) {
            if (depth > MAX_DEPTH) return
            when (t) {
                is MezzeType.FunctionType ->
                    if (!top) out.add("`$path` is a function (`${com.mezze.language.ast.prettyPrintType(t)}`), and no `Arbitrary` exists for functions")
                is MezzeType.RecordType -> t.fields.forEach { (f, ft) -> walk("$path.$f", ft, depth + 1, false) }
                is MezzeType.VariantType -> t.constructors.forEach { (c, p) -> if (p != null) walk("$path.$c", p, depth + 1, false) }
                is MezzeType.TypeApplication -> {
                    val builtin = t.identity as? com.mezze.language.ast.TypeIdentity.Builtin
                    if (builtin != null && t.constructor !in GENERATED_CONTAINERS) {
                        out.add("`$path` is a `${com.mezze.language.ast.prettyPrintType(t)}`, which has no `Arbitrary`")
                    } else t.args.forEachIndexed { i, a -> walk("$path[$i]", a, depth + 1, false) }
                }
                else -> Unit
            }
        }
        walk(name, type, 0, top = true)
        return out
    }

    /** Builtin containers `std::abilities::arbitrary` (or derivation) generates. */
    private val GENERATED_CONTAINERS = setOf(
        "Seq", "List", "Deque", "Set", "SortedSet", "Map", "SortedMap", "NonEmptyList", "Option", "Result",
    )

    /**
     * Newtypes with NO `Arbitrary` anywhere in scope, keyed by their declaration's
     * identity, which this command draws from their REPRESENTATION — and says so.
     *
     * Generated directly rather than through a sample-local impl: an impl must sit
     * with the ability or the type (the orphan rule), so it could not be written
     * for a newtype declared in another of the user's modules. Only the expression
     * this command compiles does this; `Arbitrary`, the user's program and property
     * tests still refuse — that is where "only the author knows what is valid"
     * matters, and here it is a convenience for looking at a function.
     */
    class Unwrap(val byHash: Map<com.mezze.language.typedast.Hash, MezzeType.NewtypeType>) {
        fun newtypeOf(type: MezzeType): MezzeType.NewtypeType? = declaredHash(type)?.let { byHash[it] }
        /** Whether [type] holds one of these anywhere inside, so dispatch would fail on it. */
        fun reaches(type: MezzeType): Boolean = byHash.isNotEmpty() && newtypesIn(type) { newtypeOf(it) }.isNotEmpty()
        companion object {
            val NONE = Unwrap(emptyMap())
            fun declaredHash(t: MezzeType): com.mezze.language.typedast.Hash? =
                (((t as? MezzeType.TypeApplication)?.identity ?: (t as? MezzeType.NewtypeType)?.identity)
                    as? com.mezze.language.ast.TypeIdentity.Declared)?.hash
        }
    }

    fun of(
        type: MezzeType,
        depth: Int = 0,
        /**
         * Recursive variants met on the way, by display name.
         *
         * Collected rather than expanded because a recursive type cannot be
         * expanded: `Node`'s payload is `TypeApplication(Tree)`, deliberately
         * unexpanded, and chasing it would not terminate. Each one becomes a named
         * top-level helper that calls ITSELF, which is also why the helper cannot
         * live in the expression — a `do`-block `let` is not recursive.
         */
        recursive: MutableMap<String, MezzeType.VariantType> = mutableMapOf(),
        unwrap: Unwrap = Unwrap.NONE,
    ): String {
        if (depth > MAX_DEPTH) return DISPATCH

        // A newtype with no instance, or anything holding one: built here, since
        // dispatch would ask for the instance that does not exist. See [Unwrap].
        if (unwrap.reaches(type)) structural(type, depth, recursive, unwrap)?.let { return it }

        // A recursive reference INSIDE a helper being generated: call the helper.
        // Reached before anything else, since the name is all there is to go on.
        if (type is MezzeType.TypeApplication && type.constructor in recursive) {
            return "${helperName(type.constructor)} {}"
        }

        // A recursive VARIANT: register it and call its helper. Without this the
        // field fell through to dispatch and failed, because a recursive variant
        // does not derive — bounding it needs the `Sample` size budget, which a
        // DERIVED impl cannot name (it is written into the user's module, which has
        // no reason to import sampling). This command injects its own imports, so
        // here it can. See S-2's recursive-variant refusal.
        recursiveVariantName(type)?.let { name ->
            recursive[name] = type as MezzeType.VariantType
            return "${helperName(name)} {}"
        }

        // NO arm for a sequence, an `Option` or a `Result`, and their absence is
        // the point. Each was here because dispatch could not reach the type:
        //
        //   - a sequence, because a parameterised impl of a receiver-less operation
        //     was unreachable from a written call site (S-10),
        //   - `Option` / `Result`, because no instance existed for them AT ALL —
        //     `std::option` could not name `Arbitrary` without closing an import
        //     cycle, so the coherence rule refused a hand-written impl for a type
        //     derivation never got to derive.
        //
        // Both are fixed, so all three resolve through the ability now. The
        // `Option` weighting this file used to carry — `None` one time in ten —
        // lives in the DERIVER, as a rule for every variant rather than a special
        // case here: an empty constructor is drawn one time in eight. Measured
        // 32 `None` in 300 draws, against 153 under the uniform coin flip.
        //
        // Keeping any of them would mean this command deciding a distribution,
        // which belongs to the ability. Worse, it would mean TWO distributions —
        // `mez sample` one way and a user's own `arbitrary {}` another.

        // An ANONYMOUS record is built field by field. A record carrying a
        // declaration takes the default instead: it has a derived instance, and
        // dispatch through it also handles components this walker would have to
        // special-case.
        if (type is MezzeType.RecordType && type.identity !is com.mezze.language.ast.TypeIdentity.Declared) {
            if (type.fields.isEmpty()) return "{}"
            return type.fields.entries.joinToString(", ", prefix = "{ ", postfix = " }") { (name, fieldType) ->
                "$name = ${of(fieldType, depth + 1, recursive, unwrap)}"
            }
        }

        // A FUNCTION parameter: no `Arbitrary` for functions exists or can, so draw
        // one value of the result type and return it whatever the arguments — a
        // constant function. Pure, as the parameter's type almost always demands;
        // drawing inside the body would perform `Sample` where none is allowed.
        if (type is MezzeType.FunctionType) {
            val params = (type.paramType as? MezzeType.RecordType)?.fields?.keys.orEmpty()
            val binder = if (params.isEmpty()) "{}" else params.joinToString(", ", prefix = "{ ", postfix = " }")
            return "({ __mez_sample_v } -> $binder -> __mez_sample_v) { __mez_sample_v = ${of(type.returnType, depth + 1, recursive, unwrap)} }"
        }

        return DISPATCH
    }

    /**
     * One ANNOTATED binding per parameter field, then the record built from them.
     *
     * The annotation is what pins the draws, and it works where a bare field draw
     * does not — because by the time a container is involved the generator is a
     * FUNCTION (`list_of`), whose `'a` the annotation solves, rather than a
     * receiver-less ability op with nothing to dispatch on. `let xs: [Int] =
     * list_of { g = … }` checks; `let xs: [Int] = Arbitrary.arbitrary {}` does not.
     *
     * It is also what makes an `Option Str` field work: the annotation pins the
     * `Some` payload, where the `if None else Some { … }` expression alone leaves it
     * open and the inner draw unpinned.
     *
     * [render] turns a field's type back into source. A type that does not round-trip
     * produces an ordinary compiler diagnostic against the generated expression,
     * which the command prints along with the expression itself — so a failure names
     * both the type and what was written, rather than being silent.
     */
    /** Bindings, the record built from them, and any generator declarations needed. */
    data class Plan(
        val decls: List<String>,
        val lets: String,
        val record: String,
        /**
         * S-21: does this row DRAW anything? A row pinned entirely to empty
         * constructors does not, and installing a `Sample` handler over it is a
         * handler that can never fire — rejected by V0510, the same way a
         * zero-argument function is. The command reads this to decide whether to
         * emit the `perform` at all.
         */
        val draws: Boolean = true,
    )

    fun bindFields(
        fields: Map<String, MezzeType>,
        argsName: String,
        /**
         * S-21: source PINNING a field for this row, or null to draw it normally.
         *
         * Used to put a row on a chosen `match` arm — see [SampleBranches]. The
         * annotation is emitted either way, since it is what makes a container or a
         * recursive variant resolve at all.
         */
        pin: (String) -> SampleBranches.Pinned? = { null },
        /** Newtypes drawn from their representation — see [Unwrap]. */
        unwrap: Unwrap = Unwrap.NONE,
        render: (MezzeType) -> String,
    ): Plan {
        val recursive = mutableMapOf<String, MezzeType.VariantType>()
        var draws = false
        val lets = fields.entries.joinToString("\n") { (name, type) ->
            val pinned = pin(name)
            if (pinned == null || pinned.draws) draws = true
            val value = pinned?.source ?: of(type, 0, recursive, unwrap)
            "              let ${argsName}_$name: ${render(type)} = $value"
        }
        val record = fields.keys.joinToString(", ", prefix = "{ ", postfix = " }") { name ->
            "$name = ${argsName}_$name"
        }
        // Declared AFTER the walk, since the walk is what discovers them. A helper
        // may itself mention another recursive variant, so the map can grow while
        // being drained.
        val decls = mutableListOf<String>()
        val done = mutableSetOf<String>()
        while (true) {
            val next = recursive.keys.firstOrNull { it !in done } ?: break
            done.add(next)
            helperDecl(next, recursive.getValue(next), recursive)?.let { decls.add(it) }
        }
        return Plan(decls, lets, record, draws)
    }

    private fun recursiveVariantName(type: MezzeType): String? {
        val variant = type as? MezzeType.VariantType ?: return null
        val name = (variant.identity as? com.mezze.language.ast.TypeIdentity.Declared)
            ?.displayName ?: return null
        val mentionsSelf = variant.constructors.values.any { payload ->
            payload != null && mentions(payload, name)
        }
        return if (mentionsSelf) name else null
    }

    private fun mentions(type: MezzeType, name: String): Boolean = when (type) {
        is MezzeType.TypeApplication -> type.constructor == name || type.args.any { mentions(it, name) }
        is MezzeType.RecordType -> type.fields.values.any { mentions(it, name) }
        is MezzeType.VariantType ->
            (type.identity as? com.mezze.language.ast.TypeIdentity.Declared)?.displayName == name ||
                type.constructors.values.any { it != null && mentions(it, name) }
        else -> false
    }

    /** Not a name a user could collide with; it is spliced into their module. */
    private fun helperName(typeName: String) = "__mez_sample_gen_$typeName"

    /**
     * A top-level generator for each recursive variant, bounded by the size budget.
     *
     * The shape:
     *
     *     let __mez_sample_gen_Tree: {} -> Tree <Sample> = {} ->
     *       if size {} == 0n
     *         then <pick among the NON-recursive constructors>
     *         else resize { n = size {} / 2n, call = {} -> <pick among ALL> }
     *
     * At budget zero only a base case is reachable, so it terminates. HALVED rather
     * than decremented because `Nat - Nat` is `Int` in this language and could go
     * negative, which would need the partial `nat::from_int`; `Nat / Nat` is total
     * and `1n / 2n` is `0n`. Halving also bounds depth logarithmically, which is what
     * a sample meant to be READ wants — a linear budget on a branching type produces
     * a tree nobody reads.
     *
     * A variant whose EVERY constructor is recursive has no base case and cannot be
     * generated at all; it falls back to dispatch, which reports an ordinary
     * missing-impl diagnostic naming the type.
     */
    private fun helperDecl(
        name: String,
        variant: MezzeType.VariantType,
        recursive: MutableMap<String, MezzeType.VariantType>,
    ): String? {
        val ctors = variant.constructors.entries.toList()
        val base = ctors.filterNot { (_, payload) -> payload != null && mentions(payload, name) }
        if (base.isEmpty()) return null

        fun construct(ctor: Map.Entry<String, MezzeType?>): String {
            val payload = ctor.value ?: return ctor.key
            return "${ctor.key} ${of(payload, 1, recursive)}"
        }

        fun pick(among: List<Map.Entry<String, MezzeType?>>): String {
            if (among.size == 1) return construct(among.first())
            // An if-ladder over ONE draw. A fresh draw per arm would consume a
            // number at every comparison and skew hard toward the later arms.
            val ladder = among.dropLast(1).foldRightIndexed("") { i, ctor, acc ->
                "if __pick == $i then ${construct(ctor)} else " + acc
            }
            return "do\n    let __pick = choose { bound = ${among.size} }\n    " +
                ladder + construct(among.last())
        }

        return "let ${helperName(name)}: {} -> $name <Sample> = {} ->\n" +
            "  if size {} == 0n\n" +
            "    then ${pick(base)}\n" +
            "    else resize { n = size {} / 2n, call = {} -> ${pick(ctors)} }"
    }

    private const val DISPATCH = "Arbitrary.arbitrary {}"

    /** [source] for a reader: every part still to be drawn shown as `_`. */
    fun displaySource(source: String): String = source.replace(DISPATCH, "_")

    /**
     * Deep enough for any type a person writes, and a bound rather than none because
     * a cycle here would hang the COMMAND rather than produce a diagnostic.
     */
    private const val MAX_DEPTH = 12
}
