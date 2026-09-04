package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import com.mezze.language.parsedast.diagnostics.ErrorCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import java.nio.file.Paths
import java.time.Duration

/**
 * The typechecker must never hang or stack-overflow on any program. Recursive
 * types used to drive `AbilityChecker.canDeriveAbility` into infinite recursion
 * (it re-expands the nominal type through its own fields); the coinductive
 * `seen` cycle guard fixes that. And the `checkParsedModule` StackOverflowError
 * backstop converts any residual deep recursion into a clean located T0214
 * rather than crashing. If T0214 shows up on a recursive-type case below, the
 * cycle guard regressed (the checker fell back to the crash-catcher).
 *
 * `Json` (std::codec::json) is a genuinely recursive variant — `JArray { items:
 * [Json] }`, `JObject { fields: [{ key: Str, value: Json }] }` — so `==` on it
 * exercises exactly the recursive structural-ability derivation.
 */
class TypecheckerAntiHangTest {

    private val parser = MezzeParser()

    private fun check(src: String): TypeChecker.TypeCheckResult {
        val module = parser.parseParsedModule(src, "t.mz")
        return TypeChecker().checkParsedModule(module, Paths.get("."), enablePrelude = true)
    }

    private fun errorsOf(r: TypeChecker.TypeCheckResult): List<TypeChecker.TypeCheckError> =
        when (r) {
            is TypeChecker.TypeCheckResult.Failure -> r.errors
            is TypeChecker.TypeCheckResult.Success -> emptyList()
        }

    @Test
    fun `deriving Eq on the recursive Json type terminates without stack overflow`() {
        assertTimeoutPreemptively(Duration.ofSeconds(15)) {
            val r = check(
                "use std::codec::json::{ Json }\n\n" +
                    "let same = { a: Json, b: Json } -> a == b\n"
            )
            assertFalse(
                errorsOf(r).any { it.code == ErrorCode.T0214_TYPECHECK_LIMIT_EXCEEDED },
                "recursive-type ability derivation hit the stack-overflow backstop (T0214) — the cycle guard regressed"
            )
        }
    }
}
