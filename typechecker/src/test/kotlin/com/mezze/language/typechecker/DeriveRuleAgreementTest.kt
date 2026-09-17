package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Task F — the two derivability rules now agree, and this pins the case where
 * they did not.
 *
 * `TypeValidator`'s copy had the same decision arms as `AbilityChecker`'s but no
 * NEWTYPE rule. It gates the unsatisfied-ability-constraint check: when it says a
 * type derives the ability, no error is reported. So a newtype with no `impl Eq`,
 * used where Eq is required, was accepted by the validator while the checker that
 * actually decides derivability said newtypes derive nothing.
 */
class DeriveRuleAgreementTest {
    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun check(src: String): Result<Any> = runCatching {
        typeChecker.typeCheck(parser.parseParsedModule(src, "test.mz"), Paths.get("."))
    }

    @Test
    fun `a newtype without an Eq impl does not satisfy an Eq constraint`() {
        val result = check(
            """
            use std::abilities::compare::{ Eq, Hash }
            use std::collections::seq
            pub newtype Money = Int
            let dedupe: { xs: [Money] } -> [Money] = { xs } -> xs.distinct {}
            dedupe { xs = [Money.(1), Money.(1)] }
            """.trimIndent()
        )
        assertTrue(
            result.isFailure,
            "a newtype derives nothing, so it cannot satisfy Eq without an impl — accepted instead",
        )
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(
            message.contains("Eq") && message.contains("Money"),
            "must be rejected FOR the missing Eq on Money, not incidentally: $message",
        )
    }

    @Test
    fun `the same newtype WITH an Eq impl is accepted`() {
        // The counterpart, so the test above is evidence of the rule rather than
        // of the program being broken for some other reason.
        val result = check(
            """
            use std::abilities::compare::{ Eq, Hash }
            use std::collections::seq
            pub newtype Money = Int
            impl Hash for Money where {
              hash = { it } -> match it is Money.(a) -> a
            }
            impl Eq for Money where {
              eq = { it, r } -> match it is
                Money.(a) -> match r is
                  Money.(b) -> a == b,
              neq = { it, r } -> match it is
                Money.(a) -> match r is
                  Money.(b) -> a != b
            }
            let dedupe: { xs: [Money] } -> [Money] = { xs } -> xs.distinct {}
            dedupe { xs = [Money.(1), Money.(1)] }
            """.trimIndent()
        )
        assertTrue(result.isSuccess, "with an explicit impl it must typecheck: ${result.exceptionOrNull()}")
    }

    @Test
    fun `a record of ordinary fields still derives Eq`() {
        // The newtype rule must not have made derivation stricter in general.
        val result = check(
            """
            use std::abilities::compare::{ Eq, Hash }
            use std::collections::seq
            type P = { x: Int, y: Str }
            let dedupe: { xs: [P] } -> [P] = { xs } -> xs.distinct {}
            dedupe { xs = [{ x = 1, y = "a" }] }
            """.trimIndent()
        )
        assertTrue(result.isSuccess, "a plain record still derives Eq: ${result.exceptionOrNull()}")
    }
}
