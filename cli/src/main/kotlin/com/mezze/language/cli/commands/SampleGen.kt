package com.mezze.language.cli.commands

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
     */
    val IMPORTS: Set<String> = setOf(
        "use std::effects::sample::{ with_seed, choose, size, resize }",
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
    ): String {
        if (depth > MAX_DEPTH) return DISPATCH

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

        // NO sequence arm, and its absence is the point. It used to read
        //
        //     return "list_of { g = {} -> ${'$'}{of(element, …)} }"
        //
        // because "the element draw is at a CONCRETE type, where dispatch works, and
        // it cannot at `[T]` itself" — true at the time and not any more. S-10 fixed
        // the monomorphizer hole that made a parameterised impl of a receiver-less
        // operation unreachable, so `let xs: [Int] = Arbitrary.arbitrary {}` now
        // resolves through `impl Arbitrary for ['a]` like any other impl. Descending
        // here would mean this command deciding how long a sequence is and how its
        // elements are drawn — type-specific knowledge belonging to the ability, not
        // to the tool that calls it.
        //
        // The same will be true of the two arms below once S-4 lets `Option` and
        // `Result` have instances at all.
        if (type is MezzeType.TypeApplication) {
            when (type.constructor) {
                "Option" -> {
                    val inner = type.args.firstOrNull() ?: return DISPATCH
                    // 1 in 10, which is ScalaCheck's `Gen.option`:
                    // `frequency(1 -> const(None), 9 -> some(g))`. Often enough that
                    // the case code forgets is hit, rare enough that a record of five
                    // Options is not usually all empty.
                    return "if choose { bound = 10 } == 0 then None " +
                        "else Some { val = ${of(inner, depth + 1, recursive)} }"
                }
                "Result" -> {
                    if (type.args.size < 2) return DISPATCH
                    // Weighted toward `Ok`, but `Err` often enough to matter — the
                    // error path is the one that goes untested.
                    return "if choose { bound = 3 } == 0 " +
                        "then Err { error = ${of(type.args[1], depth + 1, recursive)} } " +
                        "else Ok { val = ${of(type.args[0], depth + 1, recursive)} }"
                }
            }
        }

        // An ANONYMOUS record is built field by field. A record carrying a
        // declaration takes the default instead: it has a derived instance, and
        // dispatch through it also handles components this walker would have to
        // special-case.
        if (type is MezzeType.RecordType && type.identity !is com.mezze.language.ast.TypeIdentity.Declared) {
            if (type.fields.isEmpty()) return "{}"
            return type.fields.entries.joinToString(", ", prefix = "{ ", postfix = " }") { (name, fieldType) ->
                "$name = ${of(fieldType, depth + 1, recursive)}"
            }
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
    data class Plan(val decls: List<String>, val lets: String, val record: String)

    fun bindFields(
        fields: Map<String, MezzeType>,
        argsName: String,
        render: (MezzeType) -> String,
    ): Plan {
        val recursive = mutableMapOf<String, MezzeType.VariantType>()
        val lets = fields.entries.joinToString("\n") { (name, type) ->
            "              let ${argsName}_$name: ${render(type)} = ${of(type, 0, recursive)}"
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
        return Plan(decls, lets, record)
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

    /**
     * Deep enough for any type a person writes, and a bound rather than none because
     * a cycle here would hang the COMMAND rather than produce a diagnostic.
     */
    private const val MAX_DEPTH = 12
}
