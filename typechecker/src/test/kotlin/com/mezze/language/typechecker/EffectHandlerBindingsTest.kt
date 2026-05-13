package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows

/**
 * Validates that `resume` and `abort` are typecheck-bound inside op handler
 * body scope.
 *
 * These names are contributed by the runtime, not declared by the user.
 * They are only visible inside `op = { ... } -> body` positions of an
 * `impl X for Y where`.
 *
 * Type shape inside an op handler for `op: 'arg -> 'op_ret`, under a
 * `perform Handler in body` of overall type `'r`:
 *
 *   resume : { val: 'op_ret } -> 'r
 *   abort  : { val: 'r } -> 'never
 *
 * `'r` is fresh at impl-definition time; unified at the perform site.
 */
class EffectHandlerBindingsTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    @Test
    fun `abort is bound inside op handler body`() {
        val source = """
            effect Exception where {
              raise : {err: Str} -> 'a <Exception>
            }

            impl Exception for ToResult where {
              raise : {err: Str} -> 'a = {err} -> abort {val = err}
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        assertDoesNotThrow {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
    }

    @Test
    fun `resume is bound inside op handler body`() {
        val source = """
            effect Counter where {
              next : {} -> Int <Counter>
            }

            impl Counter for Echo where {
              next : {} -> Int = {} -> resume {val = 42}
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        assertDoesNotThrow {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
    }

    @Test
    fun `abort outside an op handler is unbound`() {
        // `abort` must not be available at top level — it's only contributed
        // inside op handler bodies of an `impl X for Y where`.
        val source = """
            let main = {} -> abort {val = 1}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        assertThrows<TypeCheckError> {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
    }

    @Test
    fun `let binding named abort is rejected as reserved`() {
        // Reserving abort/resume at binding sites prevents users from
        // accidentally shadowing the runtime-bound names. The parser
        // rejects them in pattern position; the typechecker rejects them
        // at semantic level. Either layer is acceptable.
        val source = """
            let abort = 42
            abort
        """.trimIndent()

        val err = assertThrows<Throwable> {
            val parsedModule = parser.parseParsedModule(source, "test.mz")
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
        assertTrue(err.message!!.contains("reserved"),
            "expected 'reserved' in error, got: ${err.message}")
    }

    @Test
    fun `let binding named resume is rejected as reserved`() {
        val source = """
            let resume = 1
            resume
        """.trimIndent()

        val err = assertThrows<Throwable> {
            val parsedModule = parser.parseParsedModule(source, "test.mz")
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
        assertTrue(err.message!!.contains("reserved"),
            "expected 'reserved' in error, got: ${err.message}")
    }

    @Test
    fun `let binding named scope is rejected as reserved`() {
        // `scope` is the runtime-bound name produced by `initialize_scope`
        // inside op handler bodies. Reserved at parser level alongside
        // abort/resume.
        val source = """
            let scope = 1
            scope
        """.trimIndent()

        val err = assertThrows<Throwable> {
            val parsedModule = parser.parseParsedModule(source, "test.mz")
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
        assertTrue(err.message!!.contains("reserved"),
            "expected 'reserved' in error, got: ${err.message}")
    }

    @Test
    fun `performer named abort is rejected as reserved`() {
        val source = """
            effect Foo where {
              do_it: {} -> {} <Foo>
            }

            impl Foo for abort where {
              do_it = {} -> resume {val = {}}
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        val err = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(parsedModule, Paths.get("."))
        }
        assertTrue(err.message!!.contains("reserved"),
            "expected 'reserved' in error, got: ${err.message}")
    }
}
