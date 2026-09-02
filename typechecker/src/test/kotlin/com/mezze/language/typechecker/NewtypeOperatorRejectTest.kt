package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * #402 — B: a newtype is NOT transparently a number. Numeric operators
 * (`+`, `*`, `-`, ...) are class-param abilities (NumAdd / NumMul `'r for 'l
 * having 'out`). A `newtype Money = Int` has NO auto-derived NumAdd/NumMul, so
 * `Money + Money` MUST be rejected unless the user supplies an impl. The historic
 * bug this pins: `Money + Money` was wrongly allowed by transparently unwrapping
 * to Int (a `.val`-style leak) — that must NOT happen.
 *
 * The positive direction (an explicit `impl NumAdd Money for Money having
 * 'out = Money` making `+` legal, and its runtime result) is pinned by
 * NewtypeImplRuntimeTest.MoneyOpacityRuntime; here we pin the REJECTION when no
 * impl exists.
 */
class NewtypeOperatorRejectTest {
    private lateinit var parser: MezzeParser
    private lateinit var tc: TypeChecker

    @BeforeEach fun setup() {
        ModuleResolver.clearCache(); TypeChecker.clearCache()
        parser = MezzeParser(); tc = TypeChecker()
    }

    private fun check(src: String): Result<TypeCheckResult> = runCatching {
        tc.typeCheck(parser.parseParsedModule(src.trimIndent(), "test.mz"), Paths.get("."), enablePrelude = true)
    }

    @Test
    fun `Money plus Money without a NumAdd impl is rejected`() {
        val r = check("""
            newtype Money = Int
            let a = Money.(350)
            let b = Money.(475)
            ## No `impl NumAdd Money for Money` — a newtype is not transparently a
            ## number, so `+` must be rejected (no unwrap-to-Int leak).
            let bad = a + b
        """)
        assertTrue(r.isFailure) {
            "`Money + Money` with no NumAdd impl must be rejected — a newtype is not a transparent Int"
        }
    }

    @Test
    fun `Money times Int without a NumMul impl is rejected`() {
        val r = check("""
            newtype Money = Int
            let a = Money.(350)
            let bad = a * 3
        """)
        assertTrue(r.isFailure) {
            "`Money * 3` with no NumMul impl must be rejected — the newtype does not inherit Int's `*`"
        }
    }

    @Test
    fun `Money minus Money without an impl is rejected`() {
        val r = check("""
            newtype Money = Int
            let a = Money.(475)
            let b = Money.(350)
            let bad = a - b
        """)
        assertTrue(r.isFailure) {
            "`Money - Money` with no impl must be rejected — a newtype does not inherit Int's `-`"
        }
    }

    // Positive control: the SAME `+` on bare Int is fine — proving the rejection
    // above is the newtype barrier, not a broken operator.
    @Test
    fun `bare Int plus Int still works`() {
        val r = check("""
            let a = 350
            let b = 475
            let ok = a + b
        """)
        assertTrue(r.isSuccess) {
            "bare Int + Int must still typecheck; got: ${r.exceptionOrNull()?.message}"
        }
    }

    // With an explicit impl, `Money + Money` becomes legal (positive direction,
    // type-side). Runtime is pinned in NewtypeImplRuntimeTest.MoneyOpacityRuntime.
    @Test
    fun `Money plus Money WITH a NumAdd impl typechecks`() {
        val r = check("""
            use std::abilities::numeric::{ NumAdd }
            newtype Money = Int
            impl NumAdd Money for Money having 'out = Money where {
              add = { it, r } -> match it is Money.(a) -> match r is Money.(b) -> Money.(a + b)
            }
            let a = Money.(350)
            let b = Money.(475)
            let ok = a + b
        """)
        assertTrue(r.isSuccess) {
            "`Money + Money` with a NumAdd impl must typecheck; got: ${r.exceptionOrNull()?.message}"
        }
    }

    // And the wrapped result stays Money — `+` does not launder Money into Int.
    @Test
    fun `Money plus Money result is Money not Int`() {
        val r = check("""
            use std::abilities::numeric::{ NumAdd }
            newtype Money = Int
            impl NumAdd Money for Money having 'out = Money where {
              add = { it, r } -> match it is Money.(a) -> match r is Money.(b) -> Money.(a + b)
            }
            let a = Money.(350)
            let b = Money.(475)
            let sum = a + b
        """).getOrThrow()
        val t = r.getVariableType("sum")?.let { com.mezze.language.ast.prettyPrintType(it) }
        assertFalse(t == "Int") { "Money + Money must not launder to Int; got $t" }
        assertTrue(t == "Money") { "Money + Money must stay Money; got $t" }
    }
}
