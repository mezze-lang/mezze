package com.mezze.language.cli

import com.mezze.language.cli.commands.SampleCoverageReport
import com.mezze.language.truffleir.BranchCoverage.Site
import com.mezze.language.truffleir.SourceInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SampleCoverageReportTest {

    private val source = listOf(
        "let f = { n: Int } -> match n is",
        "  0 -> \"zero\"",
        "  _ -> if n > 99 then \"big\" else \"small\"",
    )
    private fun site(id: Int, line: Int, col: Int, kind: Site.Kind, arm: Int) =
        Site(id, SourceInfo(0, line, col, line, col + 4), kind, arm)
    private val sites = listOf(
        site(0, 2, 3, Site.Kind.ARM, 0), site(1, 3, 3, Site.Kind.ARM, 1),
        site(2, 3, 24, Site.Kind.THEN, 0), site(3, 3, 35, Site.Kind.ELSE, 1),
    )

    @Test
    fun `nothing to say about a function with no branches`() {
        assertEquals(emptyList<String>(), SampleCoverageReport.lines("f", source, emptyList(), emptyList()))
    }

    @Test
    fun `one line when every branch ran`() {
        val report = SampleCoverageReport.lines("f", source, sites, emptyList())
        assertTrue(report.last().contains("every branch of `f` was reached (4)"), report.joinToString("\n"))
    }

    @Test
    fun `unreached branches are listed in source order with their line`() {
        val report = SampleCoverageReport.lines("f", source, sites, listOf(sites[2], sites[0]))
        assertEquals("Not reached by any row (2 of 4 branches):", report[1])
        assertTrue(report[2].startsWith("  line 2  0 -> \"zero\"") && report[2].endsWith("(arm 1)"), report[2])
        assertTrue(report[3].startsWith("  line 3  _ -> if n > 99") && report[3].endsWith("(then)"), report[3])
    }
}
