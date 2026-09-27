package com.mezze.language.cli.commands

import com.mezze.language.codebase.DocPage
import com.mezze.language.codebase.DocPages
import java.io.File

/**
 * `mez doc <name>` — everything the index knows about one declaration.
 *
 * The information was all there and scattered: the signature in the type index,
 * the doc comment and `#{ }` metadata on the declaration, the effects in the
 * effect junctions, the impls in the ability tables, the references in `usages`.
 * Answering "what IS this?" meant six queries and knowing which six.
 *
 * Reads the index and never typechecks, so it describes the last `mez index` /
 * `mez check`. `--json` is the same page as a machine-readable object — the shape a
 * documentation browser or an MCP tool consumes.
 */
class DocCommand {

    fun execute(args: List<String>, currentDir: File = File(System.getProperty("user.dir"))) {
        val flags = args.filter { it.startsWith("-") }
        val positional = args.filter { !it.startsWith("-") }
        val json = "--json" in flags

        if (positional.isEmpty()) {
            printUsage()
            kotlin.system.exitProcess(1)
        }
        val name = positional.joinToString(" ")

        if (!File(currentDir, "Mezze.toml").exists()) {
            println("${Glyphs.ERROR} No Mezze.toml found in ${currentDir.absolutePath}")
            println("   `mez doc` reads this project's index; run `mez init` first.")
            kotlin.system.exitProcess(1)
        }

        ProjectStore.open(currentDir).use { store ->
            val decl = DeclResolver.resolveOrExplain(store, name, command = "mez doc")
                ?: kotlin.system.exitProcess(1)
            val page = DocPages.of(store, decl)
            if (json) printJson(page) else printHuman(page)
        }
    }

    private fun printHuman(page: DocPage) {
        val d = page.decl
        println("${d.declKind.name.lowercase()}  ${page.qualifiedName}")
        page.signature?.let { println("  $it") }
        println()

        // The doc comment first: it is what the author chose to say.
        page.doc?.takeIf { it.isNotBlank() }?.let { doc ->
            for (line in doc.trim().lines()) println("  $line")
            println()
        }

        section("Defined at") {
            val where = page.file ?: "<unknown file>"
            listOf("$where:${d.startLine}:${d.startCol}")
        }

        section("Requires") { page.constraints.map { "$it (ability)" } }

        // Declared effects, then the ones only a traversal finds — kept separate
        // because the first are visible in the signature and the second are not.
        section("Performs") { page.declaredEffects.sorted() }
        section("Reaches (not in its signature)") {
            page.undeclaredEffects.map { a ->
                val via = a.source?.let { "${it.modulePath}::${it.declName}" } ?: "?"
                "${a.effect} — via $via, ${a.depth} hop${if (a.depth == 1) "" else "s"}"
            }
        }

        section("Metadata") { page.metadata?.let { listOf(it) }.orEmpty() }

        // Padded so the module column lines up and the eye can scan it.
        section("Implemented by") { twoColumn(page.impls.map { it.rendered to it.module }) }
        section("Implements") { page.implementedAbilities }
        section("Performed by") { twoColumn(page.performers.map { it.performerName to it.module }) }

        section("Calls") { page.directCallees.map { "${it.modulePath}::${it.declName}" } }
        section("Called by") { page.directCallers.map { "${it.modulePath}::${it.declName}" } }

        // References last: a long list, and the least surprising thing on the page.
        if (page.references.isNotEmpty()) {
            println("References (${page.references.size})")
            for (u in page.references.sortedWith(compareBy({ it.filePath }, { it.startLine })).take(10)) {
                println("  ${u.filePath}:${u.startLine}:${u.startCol}")
            }
            if (page.references.size > 10) {
                println("  … ${page.references.size - 10} more — mez search --refs '${page.qualifiedName}'")
            }
            println()
        }
    }

    /** `name   module`, with the first column padded to a common width. */
    private fun twoColumn(rows: List<Pair<String, String?>>): List<String> {
        if (rows.isEmpty()) return emptyList()
        val width = rows.maxOf { it.first.length }
        return rows.map { (left, right) -> "${left.padEnd(width)}   ${right ?: ""}".trimEnd() }
    }

    /** Print a titled block, or nothing at all when it is empty. */
    private fun section(title: String, items: () -> List<String>) {
        val values = items()
        if (values.isEmpty()) return
        println(title)
        for (v in values) println("  $v")
        println()
    }

    private fun printJson(page: DocPage) {
        fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "").replace("\t", "\\t")
        fun str(s: String?) = s?.let { "\"${esc(it)}\"" } ?: "null"
        fun arr(items: List<String>) = items.joinToString(", ") { "\"${esc(it)}\"" }

        val d = page.decl
        println("{")
        println("  \"name\": ${str(d.declName)},")
        println("  \"module\": ${str(d.modulePath)},")
        println("  \"qualified\": ${str(page.qualifiedName)},")
        println("  \"kind\": ${str(d.declKind.name.lowercase())},")
        // The hash is the identity: stable across renames, and the key every other
        // query takes.
        println("  \"hash\": ${str(d.hash.hex)},")
        println("  \"signature\": ${str(page.signature)},")
        println("  \"doc\": ${str(page.doc)},")
        // Raw, because it is already JSON — quoting it would make a consumer parse
        // a string to reach an object.
        println("  \"metadata\": ${page.metadata ?: "null"},")
        println("  \"file\": ${str(page.file)},")
        println("  \"line\": ${d.startLine},")
        println("  \"column\": ${d.startCol},")
        println("  \"constraints\": [${arr(page.constraints)}],")
        println("  \"declaredEffects\": [${arr(page.declaredEffects.sorted())}],")
        println("  \"reachedEffects\": [")
        page.reachedEffects.forEachIndexed { i, a ->
            val comma = if (i == page.reachedEffects.lastIndex) "" else ","
            val via = a.source?.let { "${it.modulePath}::${it.declName}" }
            println("    {\"effect\": ${str(a.effect)}, \"depth\": ${a.depth}, \"via\": ${str(via)}}$comma")
        }
        println("  ],")
        println("  \"impls\": [")
        page.impls.forEachIndexed { i, impl ->
            val comma = if (i == page.impls.lastIndex) "" else ","
            println("    {\"for\": ${str(impl.rendered)}, \"module\": ${str(impl.module)}}$comma")
        }
        println("  ],")
        println("  \"implements\": [${arr(page.implementedAbilities)}],")
        println("  \"performers\": [${arr(page.performers.map { it.performerName })}],")
        println("  \"calls\": [${arr(page.directCallees.map { "${it.modulePath}::${it.declName}" })}],")
        println("  \"calledBy\": [${arr(page.directCallers.map { "${it.modulePath}::${it.declName}" })}],")
        println("  \"references\": [")
        page.references.forEachIndexed { i, u ->
            val comma = if (i == page.references.lastIndex) "" else ","
            println("    {\"file\": ${str(u.filePath)}, \"line\": ${u.startLine}, \"column\": ${u.startCol}}$comma")
        }
        println("  ]")
        println("}")
    }

    private fun printUsage() {
        println("Usage: mez doc <name> [--json]")
        println()
        println("Everything the index knows about one declaration: its signature, doc")
        println("comment, metadata, effects (declared AND transitively reached), ability")
        println("impls, callers, callees and references.")
        println()
        println("Examples:")
        println("  mez doc fold")
        println("  mez doc 'std::collections::seq::size'    qualify an ambiguous name")
        println("  mez doc Ord                              an ability, with its impls")
        println("  mez doc Log --json                       machine-readable")
        println()
        println("Reads the index — run `mez check` or `mez index` first.")
    }
}
