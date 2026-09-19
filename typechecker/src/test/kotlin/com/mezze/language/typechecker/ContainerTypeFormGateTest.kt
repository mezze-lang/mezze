package com.mezze.language.typechecker

import java.io.File
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * B.1 — the two representations of a builtin container cannot grow further apart.
 *
 * `Seq`, `List`, `Set`, `Map`, `SortedMap` and `SortedSet` each exist twice: as a
 * dedicated `MezzeType` node and as a `TypeApplication` carrying the same name.
 * Consumers are two-sided — every matcher handles both — but a PRODUCER picks one,
 * and a pass that receives the other form silently does nothing. That has produced
 * wrong answers three times independently: `containerElementType` missing the
 * `TypeApplication` arm, `harvestVarBindings` the same, and mono's
 * `elementVarName`, which is why `List P == List P` compared structurally while
 * `[P] == [P]` did not.
 *
 * The endpoint is one form and the deletion of the dedicated nodes — 415
 * references, a refactor rather than a fix. The annotation converter was the
 * chokepoint: it turned every written `Seq 'a` / `Map 'k 'v` / … into a
 * dedicated node while every other applied type became a TypeApplication, and
 * it now produces one form for all of them. That took the producers from 49 to
 * 41 with both gates green, and left SortedSetType and SortedMapType with no
 * producer at all. Until the rest follow, this bounds them, so the eventual
 * deletion has a fixed target instead of a moving one.
 *
 * Counted by reading the source rather than by reflection: a construction is a
 * textual `SeqType(` that is not a `when` arm. That is crude, and it only has to
 * be stable — it is a ratchet, not an inventory.
 */
class ContainerTypeFormGateTest {

    /** Construction sites per dedicated container node, measured. */
    /**
     * ALL SIX are gone: SeqType, ListType, SetType, MapType, SortedSetType and
     * SortedMapType. A builtin container has exactly one representation now —
     * `TypeApplication("Seq", [T])` and its siblings — built by
     * `BuiltinTypes.seqApp` / `listApp` or by the annotation converter, which
     * sends every applied type down one path.
     *
     * The map is empty and the gate asserts ZERO construction sites for each
     * name. Reintroducing a dedicated node fails here, which is the point: the
     * duplication produced three independent wrong answers before it was
     * removed (containerElementType, harvestVarBindings, and mono's
     * elementVarName, the last of which is why `List P == List P` compared
     * structurally while `[P] == [P]` did not).
     */
    private val baseline = mapOf(
        "SeqType" to 0,
        "ListType" to 0,
        "SetType" to 0,
        "MapType" to 0,
        "SortedMapType" to 0,
        "SortedSetType" to 0,
    )

    private val roots = listOf(
        "ast/src/main", "typechecker/src/main", "interpreter/src/main",
        "lsp/src/main", "langservice/src/main", "parser/src/main", "formatter/src/main",
    )

    private fun repoRoot(): File {
        var dir = File(".").absoluteFile
        while (dir.parentFile != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        return dir
    }

    private fun countConstructions(typeName: String): Int {
        val root = repoRoot()
        var n = 0
        for (r in roots) {
            val dir = File(root, r)
            if (!dir.isDirectory) continue
            dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
                f.forEachLine { line ->
                    // A construction, not a match arm or a type position.
                    // `Parsed$typeName(` CONTAINS `$typeName(`. The parsed
                    // annotation `[T]` is a different thing entirely — it is
                    // syntax, and it stays; only the solved MezzeType node was
                    // deleted. Counting it here read as 11 phantom SeqType
                    // constructions after the node was already gone.
                    if (line.contains("$typeName(") &&
                        !line.contains("Parsed$typeName(") &&
                        !line.contains("is MezzeType.$typeName") &&
                        !line.contains("is $typeName") &&
                        !line.contains("data class $typeName")
                    ) n++
                }
            }
        }
        return n
    }

    @Test
    fun `no new place constructs a dedicated container type`() {
        val found = baseline.keys.associateWith { countConstructions(it) }
        // No "> 0" companion any more: the correct total IS zero. The pattern is
        // kept honest by the fact that it counted 49 sites before the deletions
        // and each drop was checked against a compile.
        val grown = found.filter { (k, v) -> v > baseline.getValue(k) }
        if (grown.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("a dedicated container type gained construction sites:")
                    grown.forEach { (k, v) -> appendLine("  $k: ${baseline.getValue(k)} -> $v") }
                    appendLine()
                    appendLine("Builtin containers exist in two forms and a producer picks one; a pass")
                    appendLine("given the other silently does nothing. Build the TypeApplication form, or")
                    appendLine("if this is genuinely needed, raise the number here deliberately.")
                },
            )
        }
    }
}
