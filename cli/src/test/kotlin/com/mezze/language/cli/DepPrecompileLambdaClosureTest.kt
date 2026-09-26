package com.mezze.language.cli

import com.mezze.language.parser.MezzeParser
import com.mezze.language.stdlib.FunctionIRReachability
import com.mezze.language.truffleir.FunctionIR
import com.mezze.language.typechecker.ModuleResolver
import com.mezze.language.typechecker.TypeChecker
import com.mezze.language.typedast.Hash
import com.mezze.language.typedast.TypedLetDecl
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * A dependency must persist the lambdas lifted out of its declaration bodies,
 * not only the declarations themselves.
 *
 * `DepPrecompiler` used to store exactly `ownHashes` — decl hashes plus
 * ability-impl operation hashes. A lambda in a decl body is lowered to its OWN
 * `FunctionIR` whose hash is neither of those, so it was dropped. A consumer
 * then resolved the dep, walked to that hash, found nothing, and
 * `computeReachableClosure` silently `continue`d past it — leaving a dangling
 * reference in the artifact.
 *
 * A comptime thunk is exactly such a lambda, which is how a library using
 * `comptime` produced a jar that built clean (exit 0, no warning) and died on
 * every run with "comptime thunk ... is not among the compiled functions".
 *
 * This asserts the property at the level it actually broke: everything a
 * declaration REACHES within its own lowering has to be reachable from the set
 * that gets stored.
 */
class DepPrecompileLambdaClosureTest {

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
    }

    /** Mirrors what DepPrecompiler stores: decl hashes, closed over what they
     *  reach inside this lowering. */
    private fun storedHashes(own: Set<Hash>, lowered: Map<Hash, FunctionIR>): Set<Hash> {
        val stored = LinkedHashSet<Hash>()
        val pending = ArrayDeque(own)
        while (pending.isNotEmpty()) {
            val h = pending.removeFirst()
            if (!stored.add(h)) continue
            val fn = lowered[h] ?: continue
            for (ref in FunctionIRReachability.collectReferencedHashes(listOf(fn))) {
                if (ref !in stored && lowered.containsKey(ref)) pending.add(ref)
            }
        }
        return stored
    }

    @Test
    fun `a lambda lifted out of a declaration body is reachable from what gets stored`() {
        val source = """
            use std::effects::log::{ println, Console }

            pub let run_twice: { f: {} -> {} <...> } -> {} <...> = { f } -> do
              f {}
              f {}

            ## The lambda here is lowered to its own FunctionIR, and its hash is
            ## neither a decl hash nor an ability-op hash.
            pub let greet: {} -> {} <Log> = {} -> run_twice {
              f = {} -> println { msg = "hi" }
            }
        """.trimIndent()

        val parsed = MezzeParser().parseParsedModule(source, "lib.mz")
        val result = TypeChecker().typeCheck(parsed, Paths.get("."), enablePrelude = true)
        val lowered = result.loweredFunctions.associateBy { it.hash }
        assertTrue(lowered.isNotEmpty(), "type checking produced no IR")

        val declHashes = result.typedModule!!.decls
            .filterIsInstance<TypedLetDecl>()
            .map { it.hash }
            .toSet()

        val stored = storedHashes(declHashes, lowered)

        // The regression, stated as a property: a decl body containing a lambda
        // means the stored set must be STRICTLY larger than the decl set. Under
        // the old behaviour the two were equal and the lambda was lost.
        assertTrue(
            stored.size > declHashes.size,
            "nothing beyond the declarations was stored, so a lifted lambda would be " +
                "dropped (decls=${declHashes.size}, stored=${stored.size})",
        )

        // And everything stored must actually resolve — a stored hash with no
        // FunctionIR behind it is the dangling reference in another form.
        val unresolvable = stored.filter { it !in lowered }
        assertTrue(
            unresolvable.isEmpty(),
            "stored hashes with nothing behind them: ${unresolvable.map { it.hex.take(8) }}",
        )
    }
}
