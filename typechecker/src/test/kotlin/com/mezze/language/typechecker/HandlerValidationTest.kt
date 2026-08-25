package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertContains
import kotlin.test.assertNotNull

/**
 * Tests for handler validation rules in perform expressions.
 *
 * Handler rules:
 * 1. Unknown performer - handler must be defined with `impl Effect for Handler`
 * 2. Duplicate handlers - can't have multiple handlers for the same effect
 * 3. Pure expression - can't provide handlers for expression with no effects
 * 4. No matching handler - at least one handler must handle an effect the expression uses
 * 5. Unnecessary handler (single) - single handler must match an effect in the expression
 * 6. Handler order - rightmost handler applies first, can add effects for leftward handlers
 */
class HandlerValidationTest {
    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    // ========== RULE 1: Unknown performer ==========

    @Test
    fun `unknown performer should fail`() {
        val source = """
            let x = perform UnknownHandler in 42
        """.trimIndent()

        val error = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(parser.parseParsedModule(source, "test.mz"), Paths.get("."))
        }
        assertContains(error.message, "Unknown performer")
        assertContains(error.message, "UnknownHandler")
    }

    // ========== RULE 2: Duplicate handlers ==========

    @Test
    fun `duplicate handlers for same effect should fail`() {
        val source = """
            effect Log where {
              info: { msg: Str } -> {} <Log>
            }

            impl Log for ConsoleLogger where {
              info = { msg } -> {}
            }

            impl Log for FileLogger where {
              info = { msg } -> {}
            }

            let logMsg = {} -> Log.info { msg = "test" }

            perform ConsoleLogger, FileLogger in logMsg {}
        """.trimIndent()

        val error = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(parser.parseParsedModule(source, "test.mz"), Paths.get("."))
        }
        assertContains(error.message, "Multiple handlers for the same effect")
        assertContains(error.message, "Log")
    }

    // ========== RULE 3: Pure expression ==========

    // V0508: `perform` on a purely-typed body (effect row is empty) is a hard
    // typecheck error. The handler can never fire — the body performs
    // nothing — so this is dead code. #274 relaxed the wrong-handler case
    // (body performs A, handler B) to a runtime no-op, but a fully-pure body
    // still errors because it can never be a spawn-chain-snapshot case.
    @Test
    fun `handler for pure expression is rejected`() {
        val source = """
            effect Log where {
              info: { msg: Str } -> {} <Log>
            }

            impl Log for ConsoleLogger where {
              info = { msg } -> {}
            }

            let pureValue = 42

            perform ConsoleLogger in pureValue
        """.trimIndent()

        val error = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(parser.parseParsedModule(source, "test.mz"), Paths.get("."))
        }
        assertContains(error.message, "performs nothing")
    }

    // ========== RULE 4: No matching handler ==========

    // #274: a handler that doesn't match any performed effect is a runtime
    // no-op (the op simply routes to whatever DOES match, up the chain, or
    // suspends). Typechecker accepts it.
    @Test
    fun `handler not matching any effect is accepted as runtime no-op`() {
        val source = """
            effect Log where {
              info: { msg: Str } -> {} <Log>
            }

            effect Network where {
              send: { data: Str } -> {} <Network>
            }

            impl Log for ConsoleLogger where {
              info = { msg } -> {}
            }

            impl Network for HttpClient where {
              send = { data } -> {}
            }

            let logMsg = {} -> Log.info { msg = "test" }

            # logMsg uses Log, but we provide HttpClient which handles Network.
            # After #274 this is a no-op, not a typecheck error.
            perform HttpClient in logMsg {}
        """.trimIndent()

        typeChecker.typeCheck(parser.parseParsedModule(source, "test.mz"), Paths.get("."))
    }

    // ========== RULE 5: Unnecessary handler (single handler) ==========

    // #274: an unnecessary handler (handles an effect the body doesn't
    // perform) is a runtime no-op.
    @Test
    fun `single unnecessary handler is accepted as runtime no-op`() {
        val source = """
            effect Log where {
              info: { msg: Str } -> {} <Log>
            }

            effect Network where {
              send: { data: Str } -> {} <Network>
            }

            impl Log for ConsoleLogger where {
              info = { msg } -> {}
            }

            impl Network for HttpClient where {
              send = { data } -> {}
            }

            let logAndSend = {} -> do
              Log.info { msg = "test" }
              Network.send { data = "hello" }

            let partiallyHandled = perform ConsoleLogger in logAndSend

            effect FileSystem where {
              write: { path: Str } -> {} <FileSystem>
            }

            impl FileSystem for DiskFS where {
              write = { path } -> {}
            }

            # DiskFS handles FileSystem, which partiallyHandled doesn't perform.
            # After #274 this is a no-op, not a typecheck error.
            perform DiskFS in partiallyHandled {}
        """.trimIndent()

        typeChecker.typeCheck(parser.parseParsedModule(source, "test.mz"), Paths.get("."))
    }

    // ========== RULE 6: Handler order (rightmost first) ==========

    @Test
    fun `handlers applied in correct order - rightmost first`() {
        val source = """
            effect Log where {
              info: { msg: Str } -> {} <Log>
            }

            effect FileSystem where {
              write: { path: Str, content: Str } -> {} <FileSystem>
            }

            effect Network where {
              send: { endpoint: Str, data: Str } -> {} <Network>
            }

            impl FileSystem for DiskFS where {
              write = { path, content } -> {}
            }

            impl Network for HttpClient where {
              send = { endpoint, data } -> {}
            }

            # CompositeLogger handles Log but ADDS FileSystem and Network effects
            impl Log for CompositeLogger where {
              info: { msg: Str } -> {} <FileSystem, Network> = { msg } -> do
                FileSystem.write { path = "/log.txt", content = msg }
                Network.send { endpoint = "log-server", data = msg }
            }

            let logMsg = {} -> Log.info { msg = "test" }

            # Order: CompositeLogger first (handles Log, adds FS+Network)
            #        DiskFS next (handles FileSystem)
            #        HttpClient last (handles Network)
            perform HttpClient, DiskFS, CompositeLogger in logMsg {}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        assertNotNull(typedProgram, "Handler order should work correctly")
    }

    @Test
    fun `wrong handler order should still work - all effects handled`() {
        // Even if order seems "wrong", as long as all effects are handled it should pass
        // The rightmost handler applies first
        val source = """
            effect A where { op: {} -> {} <A> }
            effect B where { op: {} -> {} <B> }

            impl A for HandlerA where { op = {} -> {} }
            impl B for HandlerB where { op = {} -> {} }

            let useAB = {} -> do
              A.op {}
              B.op {}

            # HandlerB is rightmost so applies first to handle B
            # HandlerA is leftmost so applies second to handle A
            perform HandlerA, HandlerB in useAB {}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        assertNotNull(typedProgram)
    }

    // ========== Nested performs ==========

    // The inner perform handles every effect the body performs; the outer
    // perform's body is then purely-typed (empty effect row), which V0508
    // rejects. See `handler for pure expression is rejected` above — same
    // rule applies whether pureness came from an explicit pure value or
    // from an inner perform draining the row.
    @Test
    fun `nested performs with unnecessary outer handler is rejected`() {
        val source = """
            effect Log where {
              info: { msg: Str } -> {} <Log>
            }

            effect Network where {
              send: { data: Str } -> {} <Network>
            }

            impl Log for ConsoleLogger where {
              info = { msg } -> {}
            }

            impl Network for HttpClient where {
              send = { data } -> {}
            }

            let logMsg = {} -> Log.info { msg = "test" }

            # Inner perform handles Log, body has no effects left.
            # Outer HttpClient handles Network on now-pure body → V0508.
            let test = {} -> perform HttpClient in (perform ConsoleLogger in logMsg {})
        """.trimIndent()

        assertThrows<TypeCheckError> {
            typeChecker.typeCheck(parser.parseParsedModule(source, "test.mz"), Paths.get("."))
        }
    }

    @Test
    fun `nested performs with correct handlers should pass`() {
        val source = """
            effect Log where {
              info: { msg: Str } -> {} <Log>
            }

            effect Network where {
              send: { data: Str } -> {} <Network>
            }

            impl Log for ConsoleLogger where {
              info = { msg } -> {}
            }

            impl Network for HttpClient where {
              send = { data } -> {}
            }

            let logAndSend = {} -> do
              Log.info { msg = "test" }
              Network.send { data = "hello" }

            # Inner perform handles Log, leaves Network
            # Outer perform handles Network
            perform HttpClient in (perform ConsoleLogger in logAndSend {})
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        assertNotNull(typedProgram)
    }

    // ========== Top-level performed effects ==========

    @Test
    fun `top-level value with performed effects should fail`() {
        val source = """
            effect Log where {
              info: { msg: Str } -> {} <Log>
            }

            impl Log for ConsoleLogger where {
              info = { msg } -> {}
            }

            let logMsg = {} -> Log.info { msg = "test" }

            # This creates a top-level value with <*>
            let result = perform ConsoleLogger in logMsg {}
        """.trimIndent()

        val error = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(parser.parseParsedModule(source, "test.mz"), Paths.get("."))
        }
        assertContains(error.message, "Top-level value")
        assertContains(error.message, "performed effects")
    }

    @Test
    fun `top-level function with performed effects should pass`() {
        val source = """
            effect Log where {
              info: { msg: Str } -> {} <Log>
            }

            impl Log for ConsoleLogger where {
              info = { msg } -> {}
            }

            let logMsg = {} -> Log.info { msg = "test" }

            # This is a function, so <*> is allowed
            let getResult = {} -> perform ConsoleLogger in logMsg {}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val typedProgram = typeChecker.typeCheck(parsedModule, Paths.get("."))

        assertNotNull(typedProgram)
    }
}
