package com.mezze.language.cli.commands

import com.mezze.language.parsedast.diagnostics.LevenshteinSuggest
import com.mezze.language.store.DeclKind
import com.mezze.language.store.DeclRow
import com.mezze.language.store.MezzeStore

/**
 * A NAME to one declaration, or a message explaining why not.
 *
 * Shared by `mez search`'s graph modes and `mez doc`, which ask the same question
 * and must answer it identically — a name that is ambiguous for one cannot be
 * unambiguous for the other.
 *
 * Ambiguity is reported, never resolved by picking. Twelve declarations are named
 * `size` in the stdlib; silently taking the first would answer a question about a
 * declaration the user did not mean, and they would have no way to tell.
 */
object DeclResolver {

    sealed interface Result {
        data class Found(val decl: DeclRow) : Result

        /** No such declaration. [suggestion] is the nearest name, if one is close. */
        data class NotFound(val name: String, val suggestion: String?) : Result

        /** Several declarations share the name; the caller must qualify it. */
        data class Ambiguous(val name: String, val candidates: List<DeclRow>) : Result
    }

    /**
     * Resolve [name], which may be bare (`size`) or module-qualified
     * (`std::collections::seq::size`).
     *
     * Qualification is what makes the ambiguity message actionable: it tells the
     * user to qualify, so qualifying has to work.
     */
    fun resolve(store: MezzeStore, name: String): Result {
        val bare = name.substringAfterLast("::")
        val modulePrefix = if (name.contains("::")) name.substringBeforeLast("::") else null

        val matches = store.findDeclsByText(bare).filter { row ->
            row.declName == bare && (modulePrefix == null || row.modulePath == modulePrefix)
        }

        return when {
            matches.isEmpty() -> {
                val all = DeclKind.entries
                    .flatMap { store.getDeclsByKind(it) }
                    .map { it.declName }
                    .distinct()
                Result.NotFound(name, LevenshteinSuggest.closest(bare, all))
            }
            matches.size > 1 -> Result.Ambiguous(name, matches.sortedBy { it.modulePath })
            else -> Result.Found(matches.single())
        }
    }

    /**
     * Resolve, or print the reason and return null.
     *
     * The printing lives here so both commands explain a miss the same way; a
     * caller that wants to handle it differently uses [resolve].
     */
    fun resolveOrExplain(store: MezzeStore, name: String, command: String): DeclRow? =
        when (val result = resolve(store, name)) {
            is Result.Found -> result.decl
            is Result.NotFound -> {
                println("No declaration named `${result.name}` is indexed")
                result.suggestion?.let {
                    println()
                    println("${Glyphs.TIP} Did you mean `$it`?")
                }
                null
            }
            is Result.Ambiguous -> {
                println("`${result.name}` is ambiguous — ${result.candidates.size} declarations share it:")
                println()
                for (d in result.candidates) println("  ${d.modulePath}::${d.declName}")
                println()
                println("${Glyphs.TIP} Qualify it: $command '${result.candidates.first().modulePath}::${result.name}'")
                null
            }
        }
}
