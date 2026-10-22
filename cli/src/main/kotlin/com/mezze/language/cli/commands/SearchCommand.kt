package com.mezze.language.cli.commands

import com.mezze.language.codebase.AbilitySearch
import com.mezze.language.codebase.EffectReach
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

    private enum class Mode { TYPE, NAME, IMPLS, IMPLEMENTS, CALLERS, CALLEES, EFFECTS, PERFORMERS, REFS, UNUSED }

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

        if (positional.isEmpty() && "--unused" !in flags) {
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
            "--impls" in flags -> Mode.IMPLS
            "--implements" in flags -> Mode.IMPLEMENTS
            "--callers" in flags -> Mode.CALLERS
            "--callees" in flags -> Mode.CALLEES
            "--effects" in flags -> Mode.EFFECTS
            "--performers" in flags -> Mode.PERFORMERS
            "--refs" in flags -> Mode.REFS
            "--unused" in flags -> Mode.UNUSED
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

        // The graph and reference modes all take a NAME and resolve it to a
        // declaration first, so they share one entry point.
        if (mode in setOf(Mode.CALLERS, Mode.CALLEES, Mode.EFFECTS, Mode.REFS, Mode.UNUSED, Mode.PERFORMERS)) {
            ProjectStore.open(currentDir).use { store ->
                when (mode) {
                    Mode.UNUSED -> printUnused(store, json)
                    Mode.PERFORMERS -> printPerformers(store, query, json)
                    else -> {
                        val target = resolveDecl(store, query) ?: return
                        when (mode) {
                            Mode.CALLERS -> printGraph(store, target, json, callers = true, limit = limit)
                            Mode.CALLEES -> printGraph(store, target, json, callers = false, limit = limit)
                            Mode.EFFECTS -> printEffects(store, target, json)
                            Mode.REFS -> printRefs(store, target, json)
                            else -> {}
                        }
                    }
                }
            }
            return
        }

        if (mode == Mode.IMPLS) {
            ProjectStore.open(currentDir).use { store -> printImpls(store, query) }
            return
        }
        if (mode == Mode.IMPLEMENTS) {
            val type = try {
                TypeSearch.parseQuery(query)
            } catch (e: ParseException) {
                println("${Glyphs.ERROR} `$query` is not a type")
                println()
                println(e.message)
                kotlin.system.exitProcess(1)
            }
            ProjectStore.open(currentDir).use { store -> printAbilitiesOf(store, query, type) }
            return
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
                        signature = SignatureIndex.render(store.getSignature(decl.hash), store::getTypeRows),
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

    /** Shared with `mez doc` — see [DeclResolver], which explains a miss identically. */
    private fun resolveDecl(
        store: com.mezze.language.store.MezzeStore,
        name: String,
    ): com.mezze.language.store.DeclRow? =
        DeclResolver.resolveOrExplain(store, name, command = "mez search --callers")

    private fun printGraph(
        store: com.mezze.language.store.MezzeStore,
        target: com.mezze.language.store.DeclRow,
        json: Boolean,
        callers: Boolean,
        limit: Int,
    ) {
        val reached =
            if (callers) store.transitiveEdgesTo(target.hash)
            else store.transitiveEdgesFrom(target.hash)
        val rows = reached.mapNotNull { r -> store.findDeclByHash(r.hash)?.let { it to r.depth } }

        if (json) {
            printRowsJson(
                if (callers) "callers" else "callees",
                "${target.modulePath}::${target.declName}",
                rows.map { (d, depth) -> d to mapOf("depth" to depth.toString()) },
                store,
            )
            return
        }
        if (rows.isEmpty()) {
            println(
                if (callers) "Nothing calls ${target.declName} — changing it breaks nothing indexed"
                else "${target.declName} calls nothing indexed"
            )
            // The graph is built from LOWERED code, so a function that gets inlined
            // has no call site to point at. Saying nothing here would let "nothing
            // calls this" read as "this is dead".
            println()
            println("${Glyphs.TIP} The call graph is built after inlining, so a small " +
                "function inlined into its callers legitimately has no callers here.")
            return
        }
        println(
            if (callers) "${rows.size} declaration${if (rows.size == 1) "" else "s"} reach ${target.declName}"
            else "${target.declName} reaches ${rows.size} declaration${if (rows.size == 1) "" else "s"}"
        )
        println()
        // Grouped by distance: the direct callers are what you look at first, and a
        // flat list hides which those are.
        for ((depth, group) in rows.take(limit).groupBy { it.second }.toSortedMap()) {
            println("  ${if (depth == 1) "direct" else "$depth hops"}")
            for ((d, _) in group.sortedBy { it.first.declName }) {
                println("    ${d.modulePath}::${d.declName}")
            }
        }
        if (rows.size > limit) println()
        if (rows.size > limit) println("  … ${rows.size - limit} more (raise --limit=N to see them)")
    }

    private fun printEffects(
        store: com.mezze.language.store.MezzeStore,
        target: com.mezze.language.store.DeclRow,
        json: Boolean,
    ) {
        val reached = EffectReach.of(store, target.hash)
        val own = store.getResidualEffectsFor(listOf(target.hash))[target.hash].orEmpty()

        if (json) {
            println("{")
            println("  \"decl\": \"${target.modulePath}::${target.declName}\",")
            println("  \"declared\": [${own.sorted().joinToString(", ") { "\"$it\"" }}],")
            println("  \"reached\": [")
            reached.forEachIndexed { i, a ->
                val comma = if (i == reached.lastIndex) "" else ","
                val src = a.source?.let { "\"${it.modulePath}::${it.declName}\"" } ?: "null"
                println("    {\"effect\": \"${a.effect}\", \"depth\": ${a.depth}, \"via\": $src}$comma")
            }
            println("  ]")
            println("}")
            return
        }

        if (reached.isEmpty()) {
            println("${target.declName} performs no effects, transitively")
            return
        }
        println("${target.declName} reaches ${reached.size} effect${if (reached.size == 1) "" else "s"}")
        println()
        val width = reached.maxOf { it.effect.length }
        for (a in reached) {
            val src = a.source
            val where = when {
                a.depth == 0 -> "declared here"
                src == null -> "${a.depth} hops away"
                else -> "via ${src.modulePath}::${src.declName} (${a.depth} hop${if (a.depth == 1) "" else "s"})"
            }
            println("  ${a.effect.padEnd(width)}   $where")
        }
        val undeclared = reached.filter { it.effect !in own }
        if (undeclared.isNotEmpty()) {
            println()
            println("${Glyphs.TIP} ${undeclared.size} not in its own signature: " +
                undeclared.joinToString(", ") { it.effect } +
                ". That is not necessarily a leak — an effect handled in between is " +
                "marked `*` on the row that handles it.")
        }
    }

    private fun printRefs(
        store: com.mezze.language.store.MezzeStore,
        target: com.mezze.language.store.DeclRow,
        json: Boolean,
    ) {
        val usages = store.getUsages(target.hash)
        if (json) {
            println("{")
            println("  \"decl\": \"${target.modulePath}::${target.declName}\",")
            println("  \"references\": [")
            usages.forEachIndexed { i, u ->
                val comma = if (i == usages.lastIndex) "" else ","
                println("    {\"file\": \"${u.filePath}\", \"line\": ${u.startLine}, " +
                    "\"column\": ${u.startCol}}$comma")
            }
            println("  ]")
            println("}")
            return
        }
        if (usages.isEmpty()) {
            println("No indexed reference to ${target.declName}")
            return
        }
        println("${usages.size} reference${if (usages.size == 1) "" else "s"} to ${target.declName}")
        println()
        for (u in usages.sortedWith(compareBy({ it.filePath }, { it.startLine }))) {
            println("  ${u.filePath}:${u.startLine}:${u.startCol}")
        }
    }

    private fun printPerformers(
        store: com.mezze.language.store.MezzeStore,
        effectName: String,
        json: Boolean,
    ) {
        val performers = store.findEffectPerformersByName(effectName)
        if (json) {
            println("{")
            println("  \"effect\": \"$effectName\",")
            println("  \"performers\": [")
            performers.forEachIndexed { i, p ->
                val comma = if (i == performers.lastIndex) "" else ","
                println("    {\"name\": \"${p.performerName}\", \"module\": " +
                    "${p.module?.let { "\"$it\"" } ?: "null"}}$comma")
            }
            println("  ]")
            println("}")
            return
        }
        if (performers.isEmpty()) {
            println("No performer of `$effectName` is indexed")
            val effects = store.getDeclsByKind(com.mezze.language.store.DeclKind.EFFECT).map { it.declName }
            if (effectName !in effects) {
                LevenshteinSuggest.closest(effectName, effects.distinct())?.let {
                    println()
                    println("${Glyphs.TIP} No effect named `$effectName`. Did you mean `$it`?")
                }
            }
            return
        }
        println("${performers.size} performer${if (performers.size == 1) "" else "s"} of $effectName")
        println()
        val width = performers.maxOf { it.performerName.length }
        for (p in performers.sortedBy { it.performerName }) {
            println("  ${p.performerName.padEnd(width)}   ${p.module ?: ""}")
        }
    }

    private fun printUnused(store: com.mezze.language.store.MezzeStore, json: Boolean) {
        val dead = store.findUnreferencedDecls()
        if (json) {
            printRowsJson("unused", null, dead.map { it to emptyMap<String, String>() }, store)
            return
        }
        if (dead.isEmpty()) {
            println("Nothing indexed is both private and unreferenced")
            return
        }
        println("${dead.size} private declaration${if (dead.size == 1) "" else "s"} nothing references")
        println()
        for (d in dead) println("  ${d.modulePath}::${d.declName}")
        println()
        // Say what this does NOT claim. A report that overstates its own reach gets
        // ignored the first time it is wrong.
        println("${Glyphs.TIP} Private, with no SOURCE reference outside its own body. " +
            "A public declaration nobody imports is not listed, nor is a private cluster " +
            "whose members only reference each other.")
    }

    /** Shared JSON shape for "a list of declarations, each with extra fields". */
    private fun printRowsJson(
        kind: String,
        subject: String?,
        rows: List<Pair<com.mezze.language.store.DeclRow, Map<String, String>>>,
        store: com.mezze.language.store.MezzeStore,
    ) {
        fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
        val files = rows.map { it.first.modulePath }.distinct()
            .associateWith { store.getModule(it)?.filePath }
        println("{")
        println("  \"kind\": \"$kind\",")
        if (subject != null) println("  \"subject\": \"${esc(subject)}\",")
        println("  \"results\": [")
        rows.forEachIndexed { i, (d, extra) ->
            val comma = if (i == rows.lastIndex) "" else ","
            val extras = extra.entries.joinToString("") { (k, v) -> ", \"$k\": $v" }
            println("    {\"module\": \"${esc(d.modulePath)}\", \"name\": \"${esc(d.declName)}\", " +
                "\"hash\": \"${d.hash.hex}\", \"file\": " +
                "${files[d.modulePath]?.let { "\"${esc(it)}\"" } ?: "null"}, " +
                "\"line\": ${d.startLine}$extras}$comma")
        }
        println("  ]")
        println("}")
    }

    private fun printImpls(store: com.mezze.language.store.MezzeStore, abilityName: String) {
        val impls = AbilitySearch.implsOf(store, abilityName)
        if (impls.isEmpty()) {
            println("No impl of `$abilityName` is indexed")
            val abilities = store.getDeclsByKind(com.mezze.language.store.DeclKind.ABILITY)
            if (abilities.none { it.declName == abilityName }) {
                println()
                val suggestion = LevenshteinSuggest.closest(abilityName, abilities.map { it.declName }.distinct())
                if (suggestion != null) println("${Glyphs.TIP} No ability named `$abilityName`. Did you mean `$suggestion`?")
                else println("${Glyphs.TIP} No ability named `$abilityName` is indexed — run `mez index` first.")
            }
            return
        }
        println("${impls.size} impl${if (impls.size == 1) "" else "s"} of $abilityName")
        println()
        val width = impls.maxOf { it.rendered.ifEmpty { "?" }.length }
        for (impl in impls) {
            // An impl whose targets are not indexed is still listed: it exists, and
            // saying so beats dropping it or miscounting.
            val target = impl.rendered.ifEmpty { "<target not indexed>" }
            println("  ${target.padEnd(width)}   ${impl.module ?: ""}")
        }
    }

    private fun printAbilitiesOf(
        store: com.mezze.language.store.MezzeStore,
        rendered: String,
        type: com.mezze.language.ast.MezzeType,
    ) {
        val found = AbilitySearch.abilitiesOf(store, type)
        if (found.isEmpty()) {
            println("Nothing indexed implements any ability for $rendered")
            println()
            println("${Glyphs.TIP} Targets are matched by unification, so `Deque Int` " +
                "matches an `impl … for Deque 'a`. Check the type is spelled as the impl " +
                "declares it, and that `mez index` has run.")
            return
        }
        println("$rendered implements ${found.size} abilit${if (found.size == 1) "y" else "ies"}")
        println()
        val width = found.maxOf { it.first.length }
        for ((ability, impl) in found) {
            println("  ${ability.padEnd(width)}   ${impl.module ?: ""}   (for ${impl.rendered})")
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
        println("Usage: mez search <query> [--name|--type|--impls|--implements] [--limit=N] [--json]")
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
        println("By ability:")
        println("  mez search --impls Ord                    which types implement Ord")
        println("  mez search --implements 'Deque Int'       which abilities Deque Int has")
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
