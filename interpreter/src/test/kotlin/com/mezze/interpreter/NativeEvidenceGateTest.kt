package com.mezze.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * RATCHET: Java never decides equality or ordering of a Mezze value.
 *
 * A collection native that needs to compare or order elements must be handed the
 * element's instance — the `eq` / `cmp` function the ability system already
 * resolved — and call it (see [com.mezze.interpreter.nodes.builtin.collections.ScalarCall]).
 * Deciding structurally in Java silently ignores a newtype's declared Eq/Ord:
 * `contains` misses a value that IS equal, a `SortedSet` of records treats every
 * element as equal and collapses. Wrong answers, no crash.
 *
 * The offenders are the file-local structural comparators (`deepEquals`,
 * `compareElements`, `compare(Object, Object)`) and their callers, plus
 * `Objects.equals` on element values. The allowlist below is TODAY'S set; it may
 * only SHRINK. Converting the last caller lets the comparator itself be deleted,
 * so the file drops off the list entirely.
 *
 * Note this rule is deliberately narrow: `"acc".equals(paramName)` and
 * `"Yield".hashCode()` compare Java strings, not Mezze values, and are none of
 * this test's business.
 */
class NativeEvidenceGateTest {
    /** Names of the file-local structural comparators. Declaring one, or calling
     *  one, is what the ratchet counts. */
    private val structuralComparators = listOf("deepEquals", "compareElements", "compare")

    private val methodStart = Regex("""(?=\n    (?:public|private|protected)?\s*static [^\n]*\()""")
    private val methodName = Regex("""\n    (?:public|private|protected)?\s*static [^\n]*?\b([a-zA-Z_][A-Za-z0-9_]*)\(""")

    private fun collectionsDir(): Path = listOf(
        Paths.get("src/main/java/com/mezze/interpreter/nodes/builtin/collections"),
        Paths.get("interpreter/src/main/java/com/mezze/interpreter/nodes/builtin/collections"),
    ).first { Files.isDirectory(it) }

    private fun offenders(): Set<String> {
        val out = sortedSetOf<String>()
        Files.list(collectionsDir()).use { files ->
            files.filter { it.toString().endsWith(".java") }.sorted().forEach { path ->
                val src = Files.readString(path)
                // Only comparators this file actually declares can be called unqualified.
                val declared = structuralComparators.filter {
                    Regex("""\n    (?:private|public|protected)?\s*static (?:boolean|int) $it\(Object """).containsMatchIn(src)
                }
                if (declared.isEmpty() && !src.contains("Objects.equals(")) return@forEach
                val calls = Regex(
                    (declared.map { """(?<![A-Za-z_.])$it\(""" } + """Objects\.equals\(""").joinToString("|")
                )
                for (part in src.split(methodStart)) {
                    val name = methodName.find(part)?.groupValues?.get(1) ?: continue
                    if (calls.containsMatchIn(part)) out.add("${path.fileName}:$name")
                }
            }
        }
        return out
    }

    private fun allowlist(): Set<String> =
        javaClass.getResourceAsStream("/native-structural-compare-allowlist.txt")!!
            .bufferedReader().readLines()
            .map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.toSet()

    @Test
    fun `no collection native decides equality or ordering of a Mezze value in Java`() {
        val added = offenders() - allowlist()
        assertEquals(emptySet<String>(), added,
            "NEW natives compare/order Mezze values structurally — take the element's eq/cmp function " +
                "and call it through ScalarCall instead:\n  " + added.joinToString("\n  "))
    }

    @Test
    fun `the allowlist is not stale — every entry still offends`() {
        val stale = allowlist() - offenders()
        assertEquals(emptySet<String>(), stale,
            "these natives no longer compare structurally — remove them from " +
                "native-structural-compare-allowlist.txt so the ratchet stays tight:\n  " + stale.joinToString("\n  "))
    }
}
