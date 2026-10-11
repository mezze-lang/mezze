package com.mezze.language.cli.commands

import com.mezze.language.truffleir.BranchCoverage.Site

/** `mez sample`'s branch-coverage report (S-23). */
internal object SampleCoverageReport {

    fun branchName(site: Site): String = when (site.kind) {
        Site.Kind.THEN -> "then"
        Site.Kind.ELSE -> "else"
        Site.Kind.ARM -> "arm ${site.arm + 1}"
    }

    /**
     * What no row reached, after the table.
     *
     * The rows pin `match` arms by construction, so this is mostly what pinning
     * cannot reach: an arm whose guard never held, a branch on a computed value,
     * an `if` whose condition the generator never satisfied. Silent when the
     * function has no branches; one line when every branch ran.
     */
    fun lines(
        name: String,
        source: List<String>,
        sites: List<Site>,
        unreached: List<Site>,
    ): List<String> {
        if (sites.isEmpty()) return emptyList()
        if (unreached.isEmpty()) {
            return listOf("", "${Glyphs.CHECK} every branch of `$name` was reached (${sites.size})")
        }
        val width = unreached.maxOf { it.src.startLine.toString().length }
        return listOf("", "Not reached by any row (${unreached.size} of ${sites.size} branches):") +
            unreached.sortedWith(compareBy({ it.src.startLine }, { it.src.startCol })).map { site ->
                val text = source.getOrNull(site.src.startLine - 1)?.trim().orEmpty()
                val shown = if (text.length > 60) text.take(59) + "…" else text
                "  line ${site.src.startLine.toString().padStart(width)}  $shown   (${branchName(site)})"
            }
    }
}
