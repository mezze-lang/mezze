package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * An internal compiler failure never speaks over a diagnostic about the program.
 *
 * Emit reads what the solver produced. Run it on a module that did NOT
 * type-check and it crashes in a hundred ways — a missing solved type, a
 * performer with no content hash, an effect that was never declared — every one
 * of them downstream of an error the solver already reported with a location and
 * a sentence the author can act on. So the choice of what to report is made in
 * `TypeChecker.checkParsedModuleInner`, at the one point that knows the COMPLETE
 * error list (the post-emit validator included), not at the crash.
 *
 * The rule, in one line: **an emit crash is the news only when nothing else was
 * wrong with the program.**
 *
 * This is a ratchet. The four programs below each crash emit on their way past a
 * real diagnostic; they used to work only because three blanket
 * `catch (e: Exception)` handlers swallowed the crash into a null module — which
 * also meant that a crash on a WELL-TYPED program was swallowed the same way and
 * surfaced at the runtime as "TruffleIR not produced by typechecker. typedModule
 * is null", a sentence with no location and no cause. Those handlers are gone; a
 * crash on a well-typed module is now an I0003 diagnostic naming the phase and
 * the underlying exception, with the cause attached so `--verbose` shows the
 * stack. If a future phase reports an internal failure before the collected
 * errors are consulted, one of these trips.
 */
class InternalEmitFailureReportingTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setUp() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun errorFor(source: String): String {
        val e = runCatching {
            typeChecker.typeCheck(parser.parseParsedModule(source, "test.mz"), Paths.get("."))
        }.exceptionOrNull()
        val message = e?.message ?: ""
        assertTrue(message.isNotBlank(), "expected a diagnostic, got: $e")
        assertFalse(
            message.contains("Internal compiler error"),
            "a program error must be reported as itself, not as a compiler bug; got: $message",
        )
        return message
    }

    /** No solved type for the destructuring binding — emit asserts it has one. */
    @Test
    fun `a partial record destructuring reports the missing fields`() {
        val message = errorFor(
            """
            let record = { x = 1, y = 2, z = 3 }
            let { x, y } = record
            """.trimIndent(),
        )
        assertTrue(message.contains("z"), "should name the field left out; got: $message")
    }

    /** The same crash, reached through the message-quality case. */
    @Test
    fun `the destructuring diagnostic still lists every missing field`() {
        val message = errorFor(
            """
            let data = { name = "test", age = 25, city = "NYC" }
            let { name } = data
            """.trimIndent(),
        )
        assertTrue(message.contains("age") && message.contains("city"), "got: $message")
    }

    /** `Performer 'X' has no content hash — cannot build handler chain entry`. */
    @Test
    fun `an unknown performer reports the performer, not the handler-chain crash`() {
        val message = errorFor("let x = perform UnknownHandler in 42")
        assertTrue(message.contains("Unknown performer"), "got: $message")
        assertTrue(message.contains("UnknownHandler"), "got: $message")
    }

    /** `Effect 'X' not found in declarations or imports`. */
    @Test
    fun `an undefined effect reports the import, not the lookup crash`() {
        val message = errorFor(
            """
            impl UndefinedEffect for MyHandler {} where {
                op = {} -> {}
            }
            """.trimIndent(),
        )
        assertTrue(message.contains("not defined or not imported"), "got: $message")
    }
}
