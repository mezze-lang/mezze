package com.mezze.language.cli.commands

import com.mezze.language.ast.MezzeType
import com.mezze.language.typedast.TypedLetDecl

/**
 * Can a tool RUN this function on the user's behalf, and if not, what is
 * missing?
 *
 * This exists because the honest answer is not obvious and the dishonest one is
 * silent. Mezze is lazy-until-discharged: calling an effectful function outside
 * a `perform` does not crash and does not error — it returns a `MezzeSuspend`,
 * a deferred computation. So a tool that executes a function without supplying
 * handlers does not fail. It produces a plausible-looking result that is really
 * an unevaluated thunk, and a `println` in there prints nothing.
 *
 * For a tool whose entire job is showing the user what a function does, that is
 * the worst possible failure: **it looks like it worked**. Refusing up front is
 * not politeness, it is the only thing that makes the output mean anything.
 *
 * [EntryPointCheck] applies the same rule to the app entry point, where the
 * consequence is identical. The shared structural part lives on
 * `MezzeType.FunctionType.residualEffects`.
 *
 * ## There is no way to supply handlers
 *
 * Sample takes a function reference and nothing else. A sample you had to
 * configure — nominating a handler per effect — would be showing a run you set
 * up rather than what the function does, so an undischarged effect means the
 * function is simply not sampleable. Both remedies are edits to the code: handle
 * the effect inside the function, or sample one that already does.
 *
 * This is why [refusal] explains rather than instructs. An earlier version
 * suggested `mez sample perform <XHandler> in ref`, a form that does not and will
 * not exist.
 */
object RunnabilityCheck {

    sealed class Result {
        /** Safe to execute with no handlers. */
        object Runnable : Result()

        /** Not a function, so there is nothing to apply arguments to. */
        data class NotAFunction(val actualType: MezzeType) : Result()

        /**
         * Concrete effects remain undischarged. [effects] is sorted for a
         * stable message; [instances] are those carrying associated-type
         * bindings, which a handler must match rather than merely name.
         */
        data class NeedsHandlers(
            val effects: List<String>,
            val instances: List<MezzeType.EffectInstance>,
        ) : Result()
    }

    /**
     * Judge a declaration's runnability from its type alone.
     *
     * Note what is deliberately NOT rejected: the `*` performed-marker and an
     * open row-var tail. Neither is a concrete effect — both are absent from
     * `allEffectNames` — so a function typed `{} -> {} <*>` is runnable, and
     * treating those as "effectful" would refuse functions that work.
     */
    fun check(decl: TypedLetDecl): Result = check(decl.scheme.type)

    fun check(type: MezzeType): Result {
        if (type !is MezzeType.FunctionType) return Result.NotAFunction(type)

        val residual = type.residualEffects ?: return Result.Runnable
        val names = residual.allEffectNames
        if (names.isEmpty()) return Result.Runnable

        return Result.NeedsHandlers(
            effects = names.sorted(),
            instances = residual.effectInstances.sortedBy { it.name },
        )
    }

    /**
     * A user-facing refusal, or null when the function is runnable.
     *
     * [reference] is how the user named the function, so the suggested command
     * echoes what they typed rather than a reconstructed path.
     */
    fun refusal(result: Result, reference: String): String? = when (result) {
        Result.Runnable -> null

        is Result.NotAFunction ->
            "`$reference` is not a function (it has type ${result.actualType}), so there is " +
                "nothing to apply arguments to."

        is Result.NeedsHandlers -> buildString {
            val list = result.effects.joinToString(", ")
            append("`$reference` performs $list, which nothing here handles, so it cannot be ")
            append("sampled.\n\n")
            append("Running it unhandled would not fail — Mezze defers undischarged effects, so ")
            append("the result would be a suspended computation rather than a value, and it would ")
            append("render as something plausible.\n\n")
            append("Sample is offered only for a function whose effects are already discharged: ")
            append("an empty row, or `<*>` where the body handles what it performs. There is ")
            append("deliberately no way to nominate handlers for it — a sample you had to ")
            append("configure would be showing a run you set up rather than what the function ")
            append("does. Handle the effect inside the function, or sample one that already ")
            append("does.\n")

            // An associated type is part of the contract, not decoration —
            // `Exception having 'error = Str` and `Exception having 'error = IoError`
            // are different obligations, and naming only the effect leaves the reader
            // to guess which one this is.
            if (result.instances.isNotEmpty()) {
                append("\n")
                for (instance in result.instances) {
                    val bindings = instance.associatedTypes.entries
                        .sortedBy { it.key }
                        .joinToString(", ") { (name, type) -> "'$name = $type" }
                    if (bindings.isNotEmpty()) {
                        append("`${instance.name}` carries $bindings — a handler would have to match it.\n")
                    }
                }
            }
        }
    }
}
