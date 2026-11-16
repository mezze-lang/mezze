package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * BUG 47 — an INHERENT impl body was the only kind nobody checked for undefined
 * variables.
 *
 * `validateImplOperationBody` performs that check, but both of its call sites
 * are ability-impl paths, so an inherent body's undefined names were recorded
 * and then ignored — the generator carried on with a fresh type variable.
 *
 * `impl Box where { get = {} -> it.v }` names `it` without declaring it as a
 * parameter. Before the fix that gave two different wrong answers depending on
 * how the result was used:
 *
 *  - `let d = b.get {}` then `d.to_str {}` → T0206 "the receiver's type could
 *    not be determined. Annotate the receiver" — blaming a LATER call, and
 *    advising an annotation, which by the author's rule means inference is wrong;
 *  - chained `b.get {}.to_str {}` → checks clean, then the interpreter dies with
 *    `FrameSlotTypeException: Frame slot kind Object expected, but got Illegal`,
 *    because `CallEmit` passes the receiver as a field named `it` into a lambda
 *    that has no `it` parameter, so there is no frame slot to bind it to.
 *
 * `it` is the receiver and is reserved: an inherent method that uses it must
 * declare it, exactly as an ability impl must.
 */
class InherentMethodReturnTypeTest {

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
    }

    private fun check(code: String): TypeCheckResult =
        TypeChecker().typeCheck(
            MezzeParser().parseParsedModule(code.trimIndent(), "test.mz"),
            Paths.get("."),
        )

    @Test
    fun `an undeclared it is rejected, and the message says how to fix it`() {
        val e = assertThrows(Throwable::class.java) {
            check(
                """
                type Box = { v: Int }
                impl Box where {
                  get = {} -> it.v,
                }
                pub let main = {} -> do
                  let b = { v = 21 }
                  let d = b.get {}
                  d.to_str {}
                """
            )
        }
        val msg = e.message ?: ""
        assertTrue(msg.contains("Undefined variable 'it'"), "names the real cause. Got: $msg")
        assertTrue(msg.contains("receiver"), "explains what `it` is. Got: $msg")
        assertTrue(msg.contains("{ it }"), "shows the fix. Got: $msg")
        assertTrue(
            !msg.contains("to_str"),
            "must NOT blame a later call the way T0206 did. Got: $msg"
        )
    }

    @Test
    fun `an undeclared it is rejected in the chained form too`() {
        // This shape used to type-check and then crash the interpreter.
        val e = assertThrows(Throwable::class.java) {
            check(
                """
                type Box = { v: Int }
                impl Box where {
                  get = {} -> it.v,
                }
                pub let main = {} -> do
                  let b = { v = 21 }
                  b.get {}.to_str {}
                """
            )
        }
        assertTrue(
            e.message?.contains("Undefined variable 'it'") == true,
            "caught at check time instead of crashing at run time. Got: ${e.message}"
        )
    }

    @Test
    fun `an ordinary typo in an inherent body is reported too`() {
        // The same gap swallowed EVERY undefined name, not just `it`:
        // `get = { it } -> nonexistent_thing` checked clean.
        //
        // Judged after the declaration loop rather than during it, because at
        // generation time a module's own bindings are not all in context — a
        // sibling or self reference reads as undefined there, which is how an
        // earlier attempt broke `std::effects::async` ("Undefined variable
        // 'throw_if_cancelled'" inside the impl of that very name) and 53 tests
        // with it.
        val e = assertThrows(Throwable::class.java) {
            check(
                """
                type Box = { v: Int }
                impl Box where {
                  get = { it } -> nonexistent_thing,
                }
                pub let main = {} -> do
                  let b = { v = 21 }
                  b.get {}
                """
            )
        }
        assertTrue(
            e.message?.contains("nonexistent_thing") == true,
            "a typo in an inherent impl body must be reported. Got: ${e.message}"
        )
    }

    @Test
    fun `a sibling reference from an inherent body still resolves`() {
        // The guard for the attempt that failed: a name defined ELSEWHERE in the
        // module must not be mistaken for a typo.
        assertDoesNotThrow {
            check(
                """
                type Box = { v: Int }
                let bump: { n: Int } -> Int = { n } -> n + 1
                impl Box where {
                  get = { it } -> bump { n = it.v },
                }
                pub let main = {} -> do
                  let b = { v = 21 }
                  b.get {}
                """
            )
        }
    }

    @Test
    fun `a declared receiver infers the return type`() {
        assertDoesNotThrow {
            check(
                """
                type Box = { v: Int }
                impl Box where {
                  get = { it } -> it.v,
                }
                pub let main = {} -> do
                  let b = { v = 21 }
                  let d = b.get {}
                  d.to_str {}
                """
            )
        }
    }

    @Test
    fun `a declared receiver infers through arithmetic too`() {
        assertDoesNotThrow {
            check(
                """
                type Box = { v: Int }
                impl Box where {
                  double = { it } -> it.v * 2,
                }
                pub let main = {} -> do
                  let b = { v = 21 }
                  let d = b.double {}
                  d.to_str {}
                """
            )
        }
    }
}
