package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import com.mezze.language.parsedast.diagnostics.ErrorCode
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Paths

/**
 * T0301_UNBOUND_TYPE_VAR extension to ability method signatures.
 *
 * Tyvars in an ability method sig must be bound somewhere:
 *   - `ability.typeParams` (which includes the `for` receiver — parser flattens)
 *   - `ability.associatedTypes` (declared via `having 'x`)
 *   - the op's own `abilityConstraints` (`with X 'y` introduces `'y`)
 *
 * If none of those bind the tyvar, it's an unbound "magic tyvar" — reject
 * with T0301. This is the invariant that codec's old style violated
 * (`ability Encode for 'a where { encode : ... -> Result 'out ... }` —
 * `'out` unbound). We enforce it now that stdlib is clean and the
 * multi-param generic ability infra is in place.
 */
class AbilityMethodUnboundTyvarTest {
    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setUp() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun check(src: String): TypeCheckResult {
        val ast = parser.parseParsedModule(src.trimIndent(), "test.mz")
        return typeChecker.typeCheck(ast, Paths.get("."))
    }

    private fun checkFails(src: String): TypeCheckError =
        assertThrows<TypeCheckError> { check(src) }

    // ------------------------------------------------------------------
    // Rejects: unbound tyvars in method sigs
    // ------------------------------------------------------------------

    @Test
    fun `unbound tyvar in method return type is rejected`() {
        val err = checkFails(
            """
            ability Bad for 'a where {
                op : { it: 'a } -> 'x
            }
            """
        )
        val msg = err.message!!
        assertTrue(msg.contains("'x") || msg.contains("undeclared"),
            "expected 'x mentioned, got: $msg")
        assertTrue(err.code == ErrorCode.T0301_UNBOUND_TYPE_VAR,
            "expected T0301, got: ${err.code}")
    }

    @Test
    fun `unbound tyvar in method param type is rejected`() {
        val err = checkFails(
            """
            ability Bad for 'a where {
                op : { it: 'a, other: 'y } -> 'a
            }
            """
        )
        assertTrue(err.message!!.contains("'y") || err.message!!.contains("undeclared"),
            "expected 'y mentioned, got: ${err.message}")
    }

    @Test
    fun `unbound tyvar deep in nested type is rejected`() {
        val err = checkFails(
            """
            ability Bad for 'a where {
                op : { it: 'a } -> Result 'a 'e
            }
            """
        )
        assertTrue(err.message!!.contains("'e") || err.message!!.contains("undeclared"),
            "expected 'e mentioned, got: ${err.message}")
    }

    @Test
    fun `tyvar first-introduced by with-clause is bound for this op`() {
        // 'z appears only in `with X 'z` — Rust/Haskell semantics: the
        // with-clause introduces it, doesn't require prior declaration.
        // Nothing in paramType/returnType uses 'z that isn't already in
        // the with-clause, so no unbound tyvar to flag.
        val r = check(
            """
            ability X for 'a where { m : { it: 'a } -> Str }
            ability WithIntroduces for 'a where {
                op : { it: 'a } with X 'z -> Str
            }
            """
        )
        assertNotNull(r)
    }

    // ------------------------------------------------------------------
    // Accepts: everything properly bound
    // ------------------------------------------------------------------

    @Test
    fun `bound by receiver typeParam is accepted`() {
        val r = check(
            """
            ability Ok1 for 'a where {
                op : { it: 'a } -> 'a
            }
            """
        )
        assertNotNull(r)
    }

    @Test
    fun `bound by extra typeParam is accepted`() {
        val r = check(
            """
            ability Ok2 'b for 'a where {
                op : { it: 'a } -> 'b
            }
            """
        )
        assertNotNull(r)
    }

    @Test
    fun `bound by associatedType is accepted`() {
        val r = check(
            """
            ability Ok3 for 'a having 'out where {
                op : { it: 'a } -> 'out
            }
            """
        )
        assertNotNull(r)
    }

    @Test
    fun `bound by op-level with-constraint typearg is accepted`() {
        // 'b appears in 'with X 'b' — the with-clause introduces it for this op
        val r = check(
            """
            ability X for 'a where { m : { it: 'a } -> Str }
            ability Ok4 for 'a where {
                op : { it: 'a } with X 'b -> Str
            }
            """
        )
        assertNotNull(r)
    }

    @Test
    fun `multi-param plus associated type plus with-constraint all bind`() {
        val r = check(
            """
            ability Ordish for 'a where { ord : { it: 'a } -> Int }
            ability Big 'e for 'a having 'out where {
                op : { it: 'a, e: 'e, extra: 'k } with Ordish 'k -> 'out
            }
            """
        )
        assertNotNull(r)
    }
}
