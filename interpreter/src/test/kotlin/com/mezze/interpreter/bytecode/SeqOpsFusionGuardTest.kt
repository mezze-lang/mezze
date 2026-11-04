package com.mezze.interpreter.bytecode

import com.mezze.interpreter.nodes.builtin.dsl.NativeBootstrap
import com.mezze.language.truffleir.NativeHofNames
import com.mezze.language.truffleir.NativeSignatures
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A declared native must not also be a fusion sink or stage.
 *
 * WHY THIS TEST EXISTS. `ChainCanonicalizer` recognises a fusable chain by
 * matching `CallNativeIR` and reading the chain's source and lambda out of the
 * argument record by field name. Every one of those match sites is guarded
 * `&& !node.isScalarABI`, so moving an op to the scalar ABI does not merely fail
 * to speed it up — it makes the fuser REFUSE the call, and the fusion is lost
 * with no diagnostic anywhere. `seq::map` + `size` is 1503x on that path, so the
 * regression would be enormous and silent.
 *
 * Porting collection natives to the scalar ABI is worth 2-2.7x each (see
 * `bench/cases/allocsurface` and `MapOps`), so more of them will be ported. This
 * test is what makes that safe: it fails the moment a declaration lands for an
 * op whose bare name is part of the fuser's vocabulary.
 *
 * If a future change teaches `ChainCanonicalizer` to read scalar-ABI operands at
 * a given site — `nativeOperands` already does, so this is tractable — then the
 * corresponding name can be removed from the exclusion by deleting its guard,
 * and this test will start allowing it.
 */
class SeqOpsFusionGuardTest {

    /** Collection prefixes whose ops `ChainCanonicalizer` fuses. */
    private val fusedPrefixes = listOf(
        "__native_seq_", "__native_iter_", "__native_list_",
        "__native_set_", "__native_sorted_set_", "__native_deque_",
    )

    @Test
    fun `no declared collection native shares a name with a fuser op`() {
        NativeBootstrap.ensureLoaded()

        val fuserOps = NativeHofNames::class.java.declaredFields
            .filter { it.name.startsWith("OP_") && it.type == String::class.java }
            .mapNotNull { it.isAccessible = true; it.get(null) as? String }
            .toSet()
        assertTrue(fuserOps.contains("size"), "sanity: the fuser vocabulary should include `size`")
        assertTrue(fuserOps.contains("fold"), "sanity: the fuser vocabulary should include `fold`")

        val offenders = mutableListOf<String>()
        for (name in NativeSignatures.knownNames()) {
            val prefix = fusedPrefixes.firstOrNull { name.startsWith(it) } ?: continue
            val bare = name.removePrefix(prefix)
            if (bare in fuserOps) offenders.add("$name (bare op `$bare`)")
        }

        assertTrue(
            offenders.isEmpty(),
            "These natives are declared on the scalar ABI but are also fuser ops, so " +
                "ChainCanonicalizer's `!isScalarABI` guards will refuse them and the chain " +
                "fusion is silently lost:\n  " + offenders.joinToString("\n  ") +
                "\nEither drop the declaration, or teach the matching ChainCanonicalizer " +
                "site to read scalar-ABI operands via `nativeOperands` and remove its guard.",
        )
    }
}
