package com.mezze.language.typechecker

import com.mezze.language.parsedast.diagnostics.ErrorCode
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows
import java.nio.file.Paths

/**
 * #279/F4: an ability IMPL must not declare a method that the target type already
 * resolves through a more-authoritative provider (field > inherent impl > ability
 * impl). The shadowed method could never run, so the impl is a type error.
 *
 * The dangerous case that motivated this: `x.add {r=y}` on a primitive is folded to
 * a hardware op by the compiler, bypassing dispatch — so `impl Doubler for Int { add
 * = … -> 999 }` used to typecheck and silently run the built-in (`(5).add{r=3}` == 8),
 * with `Doubler.add` dead. Now it's rejected at the impl.
 *
 * The rule is uniform: it applies to user modules, imported modules, and the stdlib
 * alike (the stdlib was cleaned up to obey it — no type has both an inherent and an
 * ability method of the same name).
 *
 * With the prelude in scope these are now rejected one step EARLIER, at the ability
 * DECLARATION (V0701): an operation name means one ability in a scope, and `add`
 * already means `NumAdd`. The impl-level V0420 check still guards a module without
 * the prelude, where `.add` on a primitive is inlined all the same.
 */
class AbilityOperatorMethodNameTest {

    private lateinit var parser: com.mezze.language.parser.MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach fun setup() {
        ModuleResolver.clearCache(); TypeChecker.clearCache()
        parser = com.mezze.language.parser.MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun errorFor(source: String): TypeCheckError {
        val parsed = parser.parseParsedModule(source.trimIndent(), "test.mz")
        return assertThrows<TypeCheckError> { typeChecker.typeCheck(parsed, Paths.get(".")) }
    }

    private fun checks(source: String) {
        val parsed = parser.parseParsedModule(source.trimIndent(), "test.mz")
        typeChecker.typeCheck(parsed, Paths.get("."))  // must not throw
    }

    // ── (a) built-in operator method name shadowed by the compiler's inline path ──

    @Test fun `impl of user ability with add for Int is rejected (shadowed by NumAdd)`() {
        // The exact F4 repro: without the check this typechecks and runs the built-in.
        val err = errorFor(
            """
            ability Doubler for 'a where { add: { it: 'a, r: 'a } -> 'a }
            impl Doubler for Int where { add = { it, r } -> 999 }
            """
        )
        assertEquals(ErrorCode.V0701_DUPLICATE_DECLARATION, err.code)
    }

    @Test fun `impl of user ability with eq is rejected`() {
        val err = errorFor(
            """
            ability Same for 'a where { eq: { it: 'a, r: 'a } -> Bool }
            impl Same for Int where { eq = { it, r } -> True }
            """
        )
        assertEquals(ErrorCode.V0701_DUPLICATE_DECLARATION, err.code)
    }

    @Test fun `impl of user ability with lt is rejected`() {
        val err = errorFor(
            """
            ability Ranked for 'a where { lt: { it: 'a, r: 'a } -> Bool }
            impl Ranked for Int where { lt = { it, r } -> True }
            """
        )
        assertEquals(ErrorCode.V0701_DUPLICATE_DECLARATION, err.code)
    }

    @Test fun `declaring an ability with a builtin-operator method name is rejected even without an impl`() {
        // `add` already means `NumAdd` in every scope that has the prelude.
        val err = errorFor("ability Doubler for 'a where { add: { it: 'a, r: 'a } -> 'a }")
        assertEquals(ErrorCode.V0701_DUPLICATE_DECLARATION, err.code)
        assertTrue(err.message!!.contains("NumAdd"), err.message)
    }

    // ── (b) a non-operator method name is fine even when impl'd for a primitive ──

    @Test fun `impl of user ability with a non-operator method name is accepted`() {
        checks(
            """
            ability Doubler for 'a where { combine: { it: 'a, r: 'a } -> 'a }
            impl Doubler for Int where { combine = { it, r } -> it }
            """
        )
    }

    // ── the built-in operator abilities THEMSELVES are exempt ──

    @Test fun `impl of a NON-primitive user type with an operator method name is still rejected`() {
        // Even for a user record type, reusing an operator name is forbidden — `+`/`.add`
        // desugars identically regardless of receiver, so the collision hazard is the same.
        val err = errorFor(
            """
            type Money = { cents: Int }
            ability Doubler for 'a where { add: { it: 'a, r: 'a } -> 'a }
            impl Doubler for Money where { add = { it, r } -> it }
            """
        )
        assertEquals(ErrorCode.V0701_DUPLICATE_DECLARATION, err.code)
    }
}
