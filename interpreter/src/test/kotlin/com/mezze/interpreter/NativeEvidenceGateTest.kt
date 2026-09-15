package com.mezze.interpreter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * RATCHET: Java never decides equality / ordering / hashing of a Mezze value.
 *
 * Every collection native that needs to compare, order or hash elements must be
 * handed the element's instance (a Mezze `eq` / `cmp` / `hash` function — the
 * evidence the ability system already resolved) or express the operation in
 * Mezze over non-comparing primitives. A structural Java comparison
 * (`deepEquals`, `compareElements`, `Objects.equals`, `.equals`, `compareTo`,
 * `hashCode` on element values) silently ignores a newtype's declared Eq/Ord —
 * the "silently wrong `==`" class of bug.
 *
 * The allowlist is TODAY'S set of offenders. It may only shrink: a native added
 * to the list fails this test, and an entry that no longer offends must be
 * removed (so the ratchet cannot go stale).
 */
class NativeEvidenceGateTest {
    private val forbidden = Regex(
        """\bdeepEquals\(|\bcompareElements\(|(?<![A-Za-z_.])compare\(|Objects\.equals\(|\.equals\(|\.compareTo\(|\.hashCode\("""
    )
    private val methodStart = Regex("""(?=\n    (?:public|private|protected)?\s*static [^\n]*\()""")
    private val methodName = Regex("""\n    (?:public|private|protected)?\s*static [^\n]*?\b([a-zA-Z_][A-Za-z0-9_]*)\(""")

    private fun collectionsDir(): Path {
        val candidates = listOf(
            Paths.get("src/main/java/com/mezze/interpreter/nodes/builtin/collections"),
            Paths.get("interpreter/src/main/java/com/mezze/interpreter/nodes/builtin/collections"),
        )
        return candidates.first { Files.isDirectory(it) }
    }

    private fun offenders(): Set<String> {
        val out = sortedSetOf<String>()
        Files.list(collectionsDir()).use { files ->
            files.filter { it.toString().endsWith(".java") }.sorted().forEach { p ->
                val src = Files.readString(p)
                for (part in src.split(methodStart)) {
                    val name = methodName.find(part)?.groupValues?.get(1) ?: continue
                    if (forbidden.containsMatchIn(part)) out.add("${p.fileName}:$name")
                }
            }
        }
        return out
    }

    private fun allowlist(): Set<String> =
        javaClass.getResourceAsStream("/native-structural-compare-allowlist.txt")!!
            .bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toSet()

    @Test
    fun `no collection native compares, orders or hashes elements structurally beyond the allowlist`() {
        val now = offenders()
        val allowed = allowlist()
        val added = now - allowed
        assertEquals(emptySet<String>(), added,
            "NEW natives compare/order/hash Mezze values structurally — hand them the element's eq/cmp/hash instead:\n  " +
                added.joinToString("\n  "))
    }

    @Test
    fun `the allowlist is not stale — every entry still offends`() {
        val stale = allowlist() - offenders()
        assertEquals(emptySet<String>(), stale,
            "these natives no longer compare structurally — remove them from native-structural-compare-allowlist.txt (ratchet down):\n  " +
                stale.joinToString("\n  "))
    }
}
