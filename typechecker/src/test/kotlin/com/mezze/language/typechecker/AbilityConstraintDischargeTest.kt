package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.ast.*
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

/**
 * An ability constraint on a called function must be DISCHARGED when the call
 * supplies a concrete argument that has an impl — not propagated onto the
 * caller. A user-declared ability must behave like the builtin abilities do:
 * `usesBuiltin = {} -> (42).to_dbg_str {}` infers `{} -> Str` (no residual),
 * so a user `Priced` used the same way must too.
 */
class AbilityConstraintDischargeTest {

    private lateinit var typeChecker: TypeChecker
    private lateinit var parser: MezzeParser

    @BeforeEach
    fun setUp() {
        typeChecker = TypeChecker()
        parser = MezzeParser()
    }

    private fun mainConstraints(code: String): List<MezzeType.FunctionAbilityConstraint> {
        val parsed = parser.parseParsedModule(code, "test.mz")
        val typed = typeChecker.typeCheck(parsed, Paths.get("."))
        assertNotNull(typed)
        val mainType = typed.getVariableType("main")
        assertTrue(mainType is MezzeType.FunctionType, "main should be a function, got $mainType")
        return (mainType as MezzeType.FunctionType).abilityConstraints
    }

    @Test
    fun `builtin ability discharges against a concrete receiver (baseline)`() {
        // The known-good contrast: builtin abilities do NOT leak.
        val code = "let main = {} -> (42).to_dbg_str {}"
        assertEquals(emptyList<MezzeType.FunctionAbilityConstraint>(), mainConstraints(code),
            "builtin to_dbg_str against a concrete Int must not leak a constraint onto main")
    }

    @Test
    fun `user ability discharges against a concrete argument`() {
        val code = """
            ability Priced for 'a where { cents: { it: 'a } -> Int }
            type Book = { price: Int }
            impl Priced for Book where { cents = { it } -> it.price }

            let show: { it: 'a } with Priced 'a -> Str = { it } -> it.cents {}.to_str {}

            let main = {} -> show { it = { price = 1 } }
        """.trimIndent()
        assertEquals(emptyList<MezzeType.FunctionAbilityConstraint>(), mainConstraints(code),
            "Priced 'a must discharge against the concrete Book argument, not leak onto main")
    }
}
