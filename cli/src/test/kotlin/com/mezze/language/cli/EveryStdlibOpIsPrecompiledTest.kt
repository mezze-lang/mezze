package com.mezze.language.cli

import com.mezze.language.typechecker.PrecompiledStdlib
import com.mezze.language.typechecker.StdlibSnapshotData

import com.mezze.language.typedast.Hash
import com.mezze.language.typedast.TypedLetDecl
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * The precompiled stdlib is the WHOLE stdlib: every module `std/MANIFEST.txt`
 * lists, every `let` and every impl operation in them, lowered into the
 * snapshot — not only what the prelude happens to reach.
 *
 * Until 2026-11-17 the lowered set was ~20 of 79 modules. `codec/query`,
 * `codec/json`, `db`, `polyglot/jvm` and the rest were typechecked (for the
 * decls table) but lowered inside each USER program, with that program's
 * compiler context. Two consequences, both measured:
 *
 *  - a lowering that only misbehaves in stdlib mode never ran there — the
 *    `from_query` field-read bug pinned in [HeldDecodeDictWithoutDerivedImplTest]
 *    was invisible on main for exactly this reason;
 *  - the per-module compilers each lowered EVERY impl op and `putIfAbsent`
 *    kept whichever module came first: 1152 hashes with two different IRs on
 *    main. Each op is now lowered once, by its owner.
 *
 * Lives in the cli tests because this JVM loads the BUILT snapshot — the thing
 * that ships. A typechecker-test JVM compiles only the prelude-reachable set from
 * source (the whole stdlib cost each such JVM ~4 s), so it cannot answer this.
 */
class EveryStdlibOpIsPrecompiledTest {

    private val modules get() = PrecompiledStdlib.stdlibTypedModules.values.also {
        assertTrue(StdlibSnapshotData.present, "no built stdlib snapshot on the cli test classpath — run :cli:compileStdlib")
    }

    @Test
    fun `the shipped typed-module set is the whole stdlib`() {
        // The manifest lists 79 files today; `std::syntax` and `std::codebase`
        // may be withheld from the snapshot for size, and `_prelude` is loaded
        // separately. Anything near the old reachable set (~20) is the bug.
        assertTrue(
            modules.size >= 60,
            "only ${modules.size} typed stdlib modules shipped — expected the whole stdlib: " +
                modules.map { it.path.substringAfterLast('/') }.sorted(),
        )
    }

    @Test
    fun `every impl operation of every shipped module is lowered`() {
        val missing = modules.flatMap { m ->
            m.abilityImpls.flatMap { impl ->
                impl.operations.keys
                    .filter { op -> Hash.computeOpHash(impl.hash, op) !in PrecompiledStdlib.preloweredFunctions }
                    .map { op -> "${m.path.substringAfter("std/")}: $op" }
            }
        }
        assertTrue(
            missing.isEmpty(),
            "${missing.size} impl operation(s) typechecked but not in the snapshot — " +
                "they would be lowered per user program instead: ${missing.take(25)}",
        )
    }

    @Test
    fun `every let of every shipped module is lowered`() {
        val missing = modules.flatMap { m ->
            m.decls.filterIsInstance<TypedLetDecl>()
                .filter { it.hash !in PrecompiledStdlib.preloweredFunctions }
                .map { "${m.path.substringAfter("std/")}: ${it.name}" }
        }
        assertTrue(
            missing.isEmpty(),
            "${missing.size} let(s) typechecked but not in the snapshot: ${missing.take(25)}",
        )
    }
}
