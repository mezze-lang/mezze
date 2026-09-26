package com.mezze.language.cli.commands

import com.mezze.language.codebase.TypeSearch
import com.mezze.language.store.SignatureIndex
import com.mezze.language.parsedast.diagnostics.LevenshteinSuggest
import com.mezze.language.parser.ParseException
import java.io.File

/**
 * `mez search '<type>'` — find declarations by their TYPE.
 *
 * You know the shape of what you want and not its name: `fold`, `reduce`,
 * `inject` and `aggregate` are all plausible spellings of one function, and name
 * search only helps if you guess the one the codebase picked.
 *
 * Reads the index; it never typechecks. That means results describe the last
 * `mez index` / `mez check`, and saying so when the index is empty is better than
 * silently reporting no matches for a project that simply has not been indexed.
 */
class SearchCommand {

    private enum class Mode { TYPE, NAME }

    private companion object {
        /**
         * Characters that only appear in a TYPE. A query containing any of them is
         * a type query; a bare `sort` or `sort_by` is a name.
         */
        val TYPE_SYNTAX = charArrayOf('{', '}', '[', ']', '<', '>', '|', '\'', '-')
    }

    fun execute(args: List<String>, currentDir: File = File(System.getProperty("user.dir"))) {
        val flags = args.filter { it.startsWith("-") }
        val positional = args.filter { !it.startsWith("-") }
        val json = "--json" in flags
        val limit = flags.firstOrNull { it.startsWith("--limit=") }
            ?.removePrefix("--limit=")?.toIntOrNull() ?: 25

        if (positional.isEmpty()) {
            printUsage()
            kotlin.system.exitProcess(1)
        }
        // Joined, so an unquoted query still works: `mez search { x: Int } -> Int`
        // arrives as several argv entries and refusing it would be pedantry.
        val query = positional.joinToString(" ")

        // WHICH search this is. Guessing matters here because a bare word is a VALID
        // type: by Mezze's convention a lowercase name is a type VARIABLE, so
        // `mez search sort` would parse as "any type at all" and match everything —
        // a silently useless answer to a reasonable request.
        //
        // The rule: anything containing type SYNTAX is a type query; anything else
        // is a name. `--type` and `--name` override it, because a heuristic that
        // cannot be overridden is a trap.
        val mode = when {
            "--type" in flags -> Mode.TYPE
            "--name" in flags -> Mode.NAME
            query.any { it in TYPE_SYNTAX } -> Mode.TYPE
            else -> Mode.NAME
        }

        if (!File(currentDir, "Mezze.toml").exists()) {
            println("${Glyphs.ERROR} No Mezze.toml found in ${currentDir.absolutePath}")
            println("   Search reads this project's index; run `mez init` first.")
            kotlin.system.exitProcess(1)
        }

        // Parse before opening the store: a typo in the query should not depend on
        // whether an index exists, and the parser's message points into what was
        // typed.
        val queryType = if (mode == Mode.TYPE) {
            try {
                TypeSearch.parseQuery(query)
            } catch (e: ParseException) {
                println("${Glyphs.ERROR} `$query` is not a type")
                println()
                println(e.message)
                println()
                println("${Glyphs.TIP} A query is a type, e.g. '{ xs: [Int] } -> Int' or '{ x: 'a } -> 'a'")
                println("   To search by name instead: mez search --name '$query'")
                kotlin.system.exitProcess(1)
            }
        } else null

        ProjectStore.open(currentDir).use { store ->
            val hits =
                if (queryType != null) TypeSearch.search(store, queryType, limit = limit)
                else store.findDeclsByText(query).take(limit).map { decl ->
                    TypeSearch.Hit(
                        decl = decl,
                        quality = TypeSearch.Quality.EXACT,
                        signature = SignatureIndex.render(store.getSignature(decl.hash)),
                        bindings = 0,
                    )
                }

            if (json) {
                // The file path lives on the module, not the decl. An agent needs
                // somewhere to open, so resolve it here rather than emitting null.
                val files = hits.map { it.decl.modulePath }.distinct()
                    .associateWith { store.getModule(it)?.filePath }
                printJson(query, hits, files, mode)
                return
            }

            if (hits.isEmpty()) {
                println(
                    if (mode == Mode.NAME) "No declaration's name or doc mentions `$query`"
                    else "No declaration matches $query"
                )
                val indexed = store.getDeclsByKind(com.mezze.language.store.DeclKind.LET).size
                if (indexed == 0) {
                    println()
                    println("${Glyphs.TIP} Nothing is indexed yet — run `mez check` or `mez index` first.")
                } else if (mode == Mode.NAME) {
                    println()
                    // Did-you-mean, from the compiler's own suggester — the same one
                    // behind "Did you mean `x`?" in a type error, so a near miss
                    // reads the same way here as it does there.
                    //
                    // Computed in Kotlin, not in SQL: SQLite's fuzzy extension
                    // (spellfix1/editdist3) is not in the bundled build, and over a
                    // codebase-sized name list the difference does not matter.
                    // EVERY declaration name, across every kind. Using
                    // `searchDeclsByName("")` for this silently considered only the
                    // first 200 alphabetically — that query is capped — so a
                    // suggestion for `identiti` never saw `identity` and the hint
                    // just never appeared.
                    val names = com.mezze.language.store.DeclKind.entries
                        .flatMap { kind -> store.getDeclsByKind(kind) }
                        .map { it.declName }
                        .distinct()
                    val suggestion = LevenshteinSuggest.closest(query, names)
                    if (suggestion != null) {
                        println("${Glyphs.TIP} Did you mean `$suggestion`?")
                    } else {
                        println("${Glyphs.TIP} $indexed declarations searched. Names and doc comments are " +
                            "matched by substring, so try a shorter fragment.")
                    }
                } else {
                    println()
                    println("${Glyphs.TIP} $indexed declarations searched. Put a type variable where you " +
                        "do not care ('{ xs: ['a] } -> Int'), or `...` for \"and any other fields\" " +
                        "('{ xs: ['a], ... } -> Int').")
                }
                return
            }

            println("${hits.size} match${if (hits.size == 1) "" else "es"} for $query")
            println()
            // Aligned so the signatures form a column the eye can scan; the name
            // and its module are what you act on.
            val width = hits.maxOf { "${it.decl.modulePath}::${it.decl.declName}".length }
            for (hit in hits) {
                val qualified = "${hit.decl.modulePath}::${hit.decl.declName}"
                println("  ${qualified.padEnd(width)}  ${hit.signature ?: "<no signature>"}")
                if (mode == Mode.TYPE && hit.quality != TypeSearch.Quality.EXACT) {
                    println("  ${" ".repeat(width)}  ${describe(hit.quality)}")
                }
            }
        }
    }

    /** Why a hit is not an exact match — stated, because the user asked for a type. */
    private fun describe(quality: TypeSearch.Quality): String = when (quality) {
        TypeSearch.Quality.EXACT -> ""
        TypeSearch.Quality.MORE_GENERAL -> "↑ more general than your query"
        TypeSearch.Quality.MORE_SPECIFIC -> "↓ more specific than your query"
        TypeSearch.Quality.UNIFIABLE -> "~ unifies with your query"
    }

    private fun printJson(
        query: String,
        hits: List<TypeSearch.Hit>,
        files: Map<String, String?>,
        mode: Mode,
    ) {
        fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
        fun str(s: String?) = s?.let { "\"${esc(it)}\"" } ?: "null"
        println("{")
        println("  \"query\": ${str(query)},")
        println("  \"matches\": [")
        hits.forEachIndexed { i, hit ->
            val comma = if (i == hits.lastIndex) "" else ","
            println("    {")
            println("      \"module\": ${str(hit.decl.modulePath)},")
            println("      \"name\": ${str(hit.decl.declName)},")
            // The hash is the identity — stable across renames, and what every
            // other store query keys on.
            println("      \"hash\": ${str(hit.decl.hash.hex)},")
            println("      \"signature\": ${str(hit.signature)},")
            // In name mode there is no type-match quality to report, and calling it
            // "exact" would claim the signature matched something it was never
            // compared against.
            println("      \"match\": ${str(if (mode == Mode.NAME) "name" else hit.quality.name.lowercase())},")
            println("      \"file\": ${str(files[hit.decl.modulePath])},")
            println("      \"line\": ${hit.decl.startLine},")
            println("      \"column\": ${hit.decl.startCol}")
            println("    }$comma")
        }
        println("  ]")
        println("}")
    }

    private fun printUsage() {
        println("Usage: mez search <query> [--name|--type] [--limit=N] [--json]")
        println()
        println("Find declarations by TYPE or by NAME. A query containing type syntax")
        println("is a type search; anything else is a name search.")
        println()
        println("By type:")
        println("  mez search '{ x: Int, y: Int } -> Int'     an exact shape")
        println("  mez search '{ x: \'a } -> \'a'              anything shaped like identity")
        println("  mez search '{ xs: [\'a], ... } -> Int'      a sequence in, an Int out,")
        println("                                            and any other arguments")
        println("  mez search '{ msg: Str } -> {} <Log>'      something that logs")
        println()
        println("By name or doc comment:")
        println("  mez search sort                           name or doc contains `sort`")
        println("  mez search sea                            finds binary_search_insert_point")
        println("  mez search --name 'Int'                   force a name search")
        println()
        println("In a type query, a type variable means \"I do not care what this is\" and")
        println("`...` in a record means \"and any other fields\". Matches are ranked: exact")
        println("first, then signatures more general than the query.")
    }
}
