package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Assertions.assertDoesNotThrow

/**
 * Regression guard for the class-param fn-operand obligation-drop hole.
 *
 * A function value used as an operand of a class-parameter numeric ability
 * (NumAdd/NumSub/NumMul, all shaped `Ability 'r for 'l having 'out`) used to be
 * SILENTLY ACCEPTED when its co-operand was itself a deferred class-param
 * `having 'out` result (still an unresolved var at solve time) — e.g.
 * `(10 + 20) * f`. Only a concrete-literal co-operand (`10 * f`) tripped the
 * rejection. See AbilityChecker.functionOperandHasNoImpl.
 *
 * A concrete FunctionType is monotonic and never implements a numeric ability,
 * so its unimplementability is decidable independent of the other operand — the
 * fix rejects it even when a sibling position is still a var.
 */
class ClassParamFnOperandHoleTest {
    private lateinit var parser: MezzeParser

    @org.junit.jupiter.api.BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
    }

    private fun reject(code: String) {
        assertThrows<TypeCheckError>("expected `$code` to be rejected (function in numeric op)") {
            TypeChecker().typeCheck(parser.parseParsedModule(code, "t.mz"), Paths.get("."))
        }
    }

    private fun accept(code: String) {
        assertDoesNotThrow {
            TypeChecker().typeCheck(parser.parseParsedModule(code, "t.mz"), Paths.get("."))
        }
    }

    // ===== the hole: fn operand whose co-operand is a deferred class-param result =====

    @Test fun `mul with deferred-add co-operand rejects`() =
        reject("let f = {x: Int} -> x + 1\nlet r = (10 + 20) * f - 5\nr")

    @Test fun `add with deferred-add co-operand rejects`() =
        reject("let f = {x: Int} -> x\nlet r = (10 + 20) + f + 5\nr")

    @Test fun `sub with deferred-add co-operand rejects`() =
        reject("let f = {x: Int} -> x\nlet r = (10 + 20) - f + 5\nr")

    @Test fun `mul with function on the left rejects`() =
        reject("let f = {x: Int} -> x\nlet r = f * (10 + 20 - 5)\nr")

    // ===== already-caught cases (concrete-literal co-operand) still reject =====

    @Test fun `mul with literal co-operand rejects`() =
        reject("let f = {x: Int} -> x\nlet r = 10 * f\nr")

    @Test fun `sub with literal co-operand rejects`() =
        reject("let f = {x: Int} -> x\nlet r = f - 5\nr")

    // ===== no false positives: legitimate code must still type-check =====

    @Test fun `plain int arithmetic still ok`() =
        accept("let r = (10 + 20) * 2 - 5\nr")

    @Test fun `nat and float arithmetic still ok`() =
        accept("let a = 3n + 4n\nlet b = (1.5 + 2.5) * 2.0\nb")

    @Test fun `higher-order function application still ok`() =
        accept("let apply = {f, x} -> f {x = x}\nlet g = {x: Int} -> x + 1\nlet r = apply {f = g, x = 5}\nr")

    @Test fun `function stored in a record still ok`() =
        accept("let rec = {f = {x: Int} -> x + 1, n = 3}\nlet r = rec.n * 2\nr")

    @Test fun `polymorphic arithmetic function still ok`() =
        accept("let add = {x, y} -> x + y\nlet r = add {x = 1, y = 2}\nr")
}
