package com.mezze.language.cli.commands

import com.mezze.language.codebase.TypeSearch
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

        if (!File(currentDir, "Mezze.toml").exists()) {
            println("${Glyphs.ERROR} No Mezze.toml found in ${currentDir.absolutePath}")
            println("   Search reads this project's index; run `mez init` first.")
            kotlin.system.exitProcess(1)
        }

        // Parse before opening the store: a typo in the query should not depend on
        // whether an index exists, and the parser's message points into what was
        // typed.
        val queryType = try {
            TypeSearch.parseQuery(query)
        } catch (e: ParseException) {
            println("${Glyphs.ERROR} `$query` is not a type")
            println()
            println(e.message)
            println()
            println("${Glyphs.TIP} A query is a type, e.g. '{ xs: [Int] } -> Int' or '{ x: 'a } -> 'a'")
            kotlin.system.exitProcess(1)
        }

        ProjectStore.open(currentDir).use { store ->
            val hits = TypeSearch.search(store, queryType, limit = limit)

            if (json) {
                // The file path lives on the module, not the decl. An agent needs
                // somewhere to open, so resolve it here rather than emitting null.
                val files = hits.map { it.decl.modulePath }.distinct()
                    .associateWith { store.getModule(it)?.filePath }
                printJson(query, hits, files)
                return
            }

            if (hits.isEmpty()) {
                println("No declaration matches $query")
                val indexed = store.getDeclsByKind(com.mezze.language.store.DeclKind.LET).size
                if (indexed == 0) {
                    println()
                    println("${Glyphs.TIP} Nothing is indexed yet — run `mez check` or `mez index` first.")
                } else {
                    println()
                    println("${Glyphs.TIP} $indexed declarations searched. Try a type variable where you " +
                        "do not care: '{ xs: ['a] } -> Int'")
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
                if (hit.quality != TypeSearch.Quality.EXACT) {
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

    private fun printJson(query: String, hits: List<TypeSearch.Hit>, files: Map<String, String?>) {
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
            println("      \"match\": ${str(hit.quality.name.lowercase())},")
            println("      \"file\": ${str(files[hit.decl.modulePath])},")
            println("      \"line\": ${hit.decl.startLine},")
            println("      \"column\": ${hit.decl.startCol}")
            println("    }$comma")
        }
        println("  ]")
        println("}")
    }

    private fun printUsage() {
        println("Usage: mez search '<type>' [--limit=N] [--json]")
        println()
        println("Find declarations by type rather than by name.")
        println()
        println("Examples:")
        println("  mez search '{ x: Int, y: Int } -> Int'     an exact shape")
        println("  mez search '{ x: 'a } -> 'a'               anything shaped like identity")
        println("  mez search '{ xs: ['a] } -> Int'           a sequence in, a count out")
        println("  mez search '{ msg: Str } -> {} <Log>'      something that logs")
        println()
        println("A type variable means \"I do not care what this is\". Matches are ranked:")
        println("exact first, then signatures more general than your query.")
    }
}
