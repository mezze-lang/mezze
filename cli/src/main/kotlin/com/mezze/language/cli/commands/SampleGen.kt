package com.mezze.language.cli.commands

import com.mezze.language.ast.BuiltinTypes
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
 *  - a sequence, because a receiver-less op at `[T]` has nothing to pin it unless it
 *    is reached through a record's derived instance (S-3, #160). Emitting
 *    `list_of { g = … }` puts the inner draw at the concrete element type, where
 *    dispatch works. Measured in `InlineContainerProbeTest`.
 *  - `Option` / `Result`, because no instance exists for them at all — not for want
 *    of a body, but because the coherence rule refuses a hand-written one and the
 *    thing that would derive it cannot name the ability (S-4, #161). Generating the
 *    constructors inline needs no instance for the container.
 *  - an ANONYMOUS record, because an anonymous annotation does not pin a
 *    receiver-less operation, so there is nothing to write a binding against.
 *
 * When those tasks land, each arm here becomes dead and should be deleted — the
 * default already does the right thing. This file is a bridge, and its arms are the
 * list of what it is bridging.
 *
 * The weights match `std::abilities::arbitrary`'s own, for the obvious reason: a
 * value's distribution should not depend on whether it was reached through dispatch
 * or through this file.
 */
object SampleGen {

    /** Imports the emitted source needs. The command prepends these. */
    val IMPORTS: Set<String> = setOf(
        "use std::effects::sample::{ with_seed, list_of, choose }",
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
    fun of(type: MezzeType, depth: Int = 0): String {
        if (depth > MAX_DEPTH) return DISPATCH

        BuiltinTypes.sequenceElementType(type)?.let { element ->
            // The element draw is at a CONCRETE type, which is the whole point:
            // dispatch works there, where it cannot at `[T]` itself.
            return "list_of { g = {} -> ${of(element, depth + 1)} }"
        }

        if (type is MezzeType.TypeApplication) {
            when (type.constructor) {
                "Option" -> {
                    val inner = type.args.firstOrNull() ?: return DISPATCH
                    // 1 in 10, which is ScalaCheck's `Gen.option`:
                    // `frequency(1 -> const(None), 9 -> some(g))`. Often enough that
                    // the case code forgets is hit, rare enough that a record of five
                    // Options is not usually all empty.
                    return "if choose { bound = 10 } == 0 then None " +
                        "else Some { val = ${of(inner, depth + 1)} }"
                }
                "Result" -> {
                    if (type.args.size < 2) return DISPATCH
                    // Weighted toward `Ok`, but `Err` often enough to matter — the
                    // error path is the one that goes untested.
                    return "if choose { bound = 3 } == 0 " +
                        "then Err { error = ${of(type.args[1], depth + 1)} } " +
                        "else Ok { val = ${of(type.args[0], depth + 1)} }"
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
                "$name = ${of(fieldType, depth + 1)}"
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
    fun bindFields(
        fields: Map<String, MezzeType>,
        argsName: String,
        render: (MezzeType) -> String,
    ): Pair<String, String> {
        val lets = fields.entries.joinToString("\n") { (name, type) ->
            "              let ${argsName}_$name: ${render(type)} = ${of(type)}"
        }
        val record = fields.keys.joinToString(", ", prefix = "{ ", postfix = " }") { name ->
            "$name = ${argsName}_$name"
        }
        return lets to record
    }

    private const val DISPATCH = "Arbitrary.arbitrary {}"

    /**
     * Deep enough for any type a person writes, and a bound rather than none because
     * a cycle here would hang the COMMAND rather than produce a diagnostic.
     */
    private const val MAX_DEPTH = 12
}
