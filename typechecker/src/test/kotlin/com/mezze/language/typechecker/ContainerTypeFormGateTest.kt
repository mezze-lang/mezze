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
    private val baseline = mapOf(
        "SeqType" to 30,
    )

    /**
     * FIVE of the six are ABSENT because they no longer exist: SetType, MapType,
     * SortedSetType, SortedMapType and ListType. Once the annotation converter
     * stopped producing them, four had no producer at all; ListType's two
     * (VariantSolver's cons-on-a-variable solve, and BuiltinTypes' cons payload)
     * were moved to `BuiltinTypes.listApp`. Each was then deleted along with every
     * arm that matched it — including the cross-form unification arms, which are
     * the thing this duplication cost in the first place.
     *
     * SeqType alone remains, with 30 producers built directly rather than through
     * any annotation. It is `[]` syntax, which is why it is the one that did not
     * fall out of the converter change.
     */

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
                    if (line.contains("$typeName(") &&
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
        assertTrue(
            found.values.sum() > 0,
            "counted nothing — the source roots or the pattern are wrong, and this gate is vacuous",
        )
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
