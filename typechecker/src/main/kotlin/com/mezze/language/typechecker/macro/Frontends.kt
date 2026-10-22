package com.mezze.language.typechecker.macro

import com.mezze.language.parsedast.*
import java.nio.file.Files
import java.nio.file.Path

/**
 * File frontends: a source file in a syntax other than Mezze's, compiled through
 * a Mezze function that reads it.
 *
 * ```toml
 * [frontends]
 * "*.sql.mz" = "sqlsyntax::parse"
 * ```
 *
 * A frontend is a macro with the whole file as its input: the same type
 * (`{ input: MacroInput } -> Expansion`), returning the file's declarations. So a
 * file it reads becomes a module holding one call — `~frontend!( <the file> )` —
 * and everything a macro gets, the frontend gets: the cache, the time budget,
 * hygiene, and diagnostics placed in the file where they belong.
 *
 * Bound by configuration only, never by anything in the file: a file pulled in
 * from elsewhere cannot choose how it is read. A pattern is `*` and a suffix;
 * `use a::b` then finds `src/a/b<suffix>` as well as `src/a/b.mz`.
 */
internal class Frontends private constructor(private val bySuffix: List<Pair<String, Pair<String, String>>>) {

    /** The file suffixes modules may be written with, besides `.mz`. */
    val suffixes: List<String> get() = bySuffix.map { it.first }

    /** The frontend (module, function) for [file], or null when it is plain Mezze. */
    fun forFile(file: String): Pair<String, String>? = bySuffix.firstOrNull { file.endsWith(it.first) }?.second

    companion object {
        val NONE = Frontends(emptyList())

        /** The name a frontend's function is imported under in the module it builds. */
        const val CALLEE = "~frontend"

        /** The `[frontends]` table of [projectRoot]'s `Mezze.toml`. */
        fun of(projectRoot: Path): Frontends {
            val toml = projectRoot.resolve("Mezze.toml")
            if (!Files.isRegularFile(toml)) return NONE
            var inSection = false
            val found = mutableListOf<Pair<String, Pair<String, String>>>()
            for (raw in Files.readAllLines(toml)) {
                val line = raw.substringBefore('#').trim()
                if (line.startsWith("[")) { inSection = line == "[frontends]"; continue }
                if (!inSection || line.isEmpty()) continue
                val m = ENTRY.matchEntire(line) ?: throw IllegalArgumentException(
                    "$toml: `$raw` is not a frontend; write `\"*.ext.mz\" = \"module::function\"`",
                )
                val (pattern, target) = m.destructured
                require(pattern.startsWith("*") && pattern.count { it == '*' } == 1) {
                    "$toml: frontend pattern `$pattern` must be `*` followed by a suffix, like `*.sql.mz`"
                }
                require(MODULE_PATH_SEPARATOR in target) {
                    "$toml: frontend `$target` must name a function in a module, like `sqlsyntax::parse`"
                }
                found += pattern.substring(1) to (target.substringBeforeLast(MODULE_PATH_SEPARATOR) to target.substringAfterLast(MODULE_PATH_SEPARATOR))
            }
            // Longest suffix first, so `*.html.mz` wins over a hypothetical `*.mz`-like one.
            return Frontends(found.sortedByDescending { it.first.length })
        }

        private val ENTRY = Regex(""""([^"]+)"\s*=\s*"([^"]+)"""")

        /** The module [text] is, read by the frontend [target]: one declaration-position call. */
        fun module(file: String, text: String, target: Pair<String, String>): ParsedModule {
            val lines = text.split('\n')
            val whole = Loc(file, 1, 1, lines.size, maxOf(lines.last().length, 1))
            val origin = Loc(file, 1, 1, 1, 1)
            val import = ParsedUseDecl(
                path = ParsedUsePath.Selective(
                    ParsedModulePath(target.first.split(MODULE_PATH_SEPARATOR)),
                    listOf(ParsedImportItem(target.second, CALLEE, origin)),
                    origin,
                ),
                loc = origin,
            )
            val call = ParsedMacroCall(ParsedIdentifier(CALLEE, null, origin), text, origin, null, whole)
            return ParsedModule(file, listOf(import, ParsedTopLevelExpr(call, loc = whole)))
        }
    }
}
