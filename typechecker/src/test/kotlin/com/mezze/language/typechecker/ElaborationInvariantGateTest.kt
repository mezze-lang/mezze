package com.mezze.language.typechecker

import com.mezze.language.typechecker.validation.ElaborationInvariants
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Task 0.2 — the ratchet for "the compiler finished without knowing the type".
 *
 * Measures the whole typed stdlib, which is the largest body of Mezze that
 * exists and exercises every shape the plan is about: generic containers,
 * ability impls, derived instances, codecs. Each finding is a place the builder
 * reached the end of elaboration with an open type variable and something
 * downstream had to guess.
 *
 * The baseline file is the contract. A new finding fails the gate, and so does a
 * stale entry once a finding is fixed — the count can only go down. Task A.2
 * takes it to zero and turns the remainder into a hard ElaborationError; until
 * then this keeps the number from quietly growing back.
 *
 * Findings are keyed WITHOUT their source location. Line numbers move whenever
 * the file above them is edited, and a baseline that churns on unrelated edits
 * teaches people to regenerate it without reading it.
 */
class ElaborationInvariantGateTest {

    private val baselineResource = "/elaboration-invariant-baseline.txt"

    private fun baseline(): Set<String> =
        (javaClass.getResourceAsStream(baselineResource)
            ?: fail("missing $baselineResource"))
            .bufferedReader().readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toSet()

    private fun currentFindings(): List<ElaborationInvariants.Finding> {
        // PrecompiledStdlib is an object whose init loads the snapshot, so
        // reading the map is what triggers the load.
        val modules = PrecompiledStdlib.stdlibTypedModules.values
        assertTrue(
            modules.isNotEmpty(),
            "no typed stdlib modules loaded — the gate would vacuously pass and hide every finding",
        )
        return modules.flatMap { ElaborationInvariants.check(it) }
    }

    @Test
    fun `no new place where elaboration finished without a type`() {
        val allowed = baseline()
        val found = currentFindings().map { it.key() }.toSortedSet()
        val added = found - allowed
        if (added.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("${added.size} new elaboration gap(s) — a node reached the end of")
                    appendLine("elaboration with an open type, so something downstream is guessing:")
                    added.sorted().forEach { appendLine("  $it") }
                    appendLine()
                    appendLine("Fix the gap. Only add a line to")
                    appendLine("typechecker/src/test/resources${baselineResource} if you have")
                    appendLine("established the type genuinely cannot be known yet, and say why.")
                },
            )
        }
    }

    @Test
    fun `the baseline has no stale entries`() {
        // Without this the ratchet only ever holds level: a fixed gap would leave
        // its line behind, quietly re-permitting the same gap later.
        val allowed = baseline()
        val found = currentFindings().map { it.key() }.toSet()
        val stale = allowed - found
        if (stale.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("${stale.size} baseline entr(ies) no longer occur — delete them, so the")
                    appendLine("ratchet cannot silently allow these gaps back:")
                    stale.sorted().forEach { appendLine("  $it") }
                },
            )
        }
    }
}
