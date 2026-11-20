package com.mezze.language.cli

import com.mezze.language.cli.commands.NativeBuildPath
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.PrecompiledStdlib
import com.mezze.language.typechecker.TypeChecker
import com.mezze.language.typedast.Hash
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import kotlin.test.assertTrue

/**
 * An embedded app (native binary, standalone jar) must carry every stdlib
 * performer it performs — the record the runtime registers and the op functions
 * it inflates from — because a native binary has no stdlib snapshot to fall back
 * on. Found when every native build died at its first `perform Console` with
 * `Handler not found for: <Console's hash>` while the same jar ran on the JVM.
 */
class EmbeddedAppCarriesStdlibPerformersTest {

    private val program = """
        use std::effects::log::{ Console, println }
        pub let main = {} -> perform Console in println { msg = "hi" }
    """.trimIndent()

    private fun lowered(): Pair<List<com.mezze.language.truffleir.FunctionIR>, List<Array<String>>> {
        val parsed = MezzeParser().parseParsedModule(program, "test.mz")
        val result = TypeChecker().typeCheck(parsed, Paths.get("."), enablePrelude = true)
        return result.loweredFunctions to result.performerRecords
    }

    private fun consoleRecord(): Array<String> =
        PrecompiledStdlib.preloweredPerformerRecords.firstOrNull { rec ->
            PrecompiledStdlib.stdlibTypedModules.values.any { m ->
                m.performerDecls.any { it.name == "Console" && it.hash.hex == rec[0] }
            }
        } ?: throw AssertionError("the stdlib snapshot has no performer record for Console")

    @Test
    fun `the app's own performer list does not carry Console`() {
        val (_, own) = lowered()
        assertTrue(own.none { it[0] == consoleRecord()[0] },
            "the emitter must not re-lower a precompiled stdlib performer into the app")
    }

    @Test
    fun `the embedded closure carries Console's record and its op functions`() {
        val (fns, own) = lowered()
        val embedded = NativeBuildPath.finalizeClosure(fns, own, codebase = null)
        val console = consoleRecord()
        assertTrue(embedded.performerRecords.any { it[0] == console[0] },
            "Console's performer record must be embedded; got ${embedded.performerRecords.map { it[0].take(12) }}")
        val carried = embedded.functions.mapTo(HashSet()) { it.hash }
        var i = 2
        while (i + 1 < console.size) {
            assertTrue(Hash(console[i]) in carried, "Console's op `${console[i + 1]}` must be in the closure")
            i += 2
        }
    }
}
