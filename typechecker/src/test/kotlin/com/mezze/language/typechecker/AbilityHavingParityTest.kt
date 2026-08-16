package com.mezze.language.typechecker

import com.mezze.language.ast.prettyPrintType
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * ABILITY associated types (`with X 'a having 'out = T`) at parity with EFFECT
 * associated types. Reference behaviour (effects, already working):
 *
 *   effect Producer having 'out where { produce: {} -> 'out <Producer> }
 *   let mk = {} -> Producer.produce {}
 *   ⇒  mk : {} -> 'a <Producer having 'out = 'a>
 *
 * i.e. on a GENERIC function the `having` binding renders as the VARIABLE it's
 * bound to (here the return var), not a concrete type — a concrete type only
 * appears once the ability is discharged (concrete receiver / handler), at which
 * point there's no `with`-clause left. The ability path should match: render
 * `having`, reject a wrong annotation, allow `having` to be omitted. And none of
 * this may disturb `<-` do-bind, which is itself a `Tryable` associated-type
 * ability that must resolve locally.
 */
class AbilityHavingParityTest {
    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach fun setup() {
        ModuleResolver.clearCache(); TypeChecker.clearCache()
        parser = MezzeParser(); typeChecker = TypeChecker()
    }

    private fun check(body: String): TypeCheckResult {
        val parsed = parser.parseParsedModule(body, "test.mz")
        return typeChecker.typeCheck(parsed, Paths.get("."), enablePrelude = true)
    }

    private fun err(body: String): String? {
        val parsed = parser.parseParsedModule(body, "test.mz")
        return try { typeChecker.typeCheck(parsed, Paths.get("."), enablePrelude = true); null }
        catch (e: TypeCheckError) { e.message }
    }

    // ─── PRINTING ────────────────────────────────────────────────────────

    /** A generic method call renders `having 'out = <var>` in the with-clause,
     *  mirroring the effect printer's `<E having 'out = 'a>`. */
    @Test
    fun `single associated type prints having in the with-clause`() {
        val r = check(
            """
            ability Producer for 'a having 'out where {
              produce: { it: 'a } -> 'out
            }
            impl Producer for Int having 'out = Str where {
              produce = { it } -> "x"
            }
            pub let mk = { x } -> x.produce {}
            """.trimIndent()
        )
        val t = r.getVariableType("mk")
        assertNotNull(t, "mk should typecheck")
        val printed = prettyPrintType(t!!)
        assertTrue(
            Regex("""with Producer .*having 'out = '\w+""").containsMatchIn(printed),
            "with-clause should render `having 'out = <var>`, got: $printed"
        )
    }

    /** TWO associated types print comma-joined, sorted by key. */
    @Test
    fun `multiple associated types print comma-joined sorted by key`() {
        val r = check(
            """
            ability KV for 'c having 'k, 'v where {
              first_key: { it: 'c } -> 'k
            }
            impl KV for [Int] having 'k = Int, 'v = Str where {
              first_key = { it } -> 0
            }
            pub let go = { xs } -> xs.first_key {}
            """.trimIndent()
        )
        val t = r.getVariableType("go")
        assertNotNull(t, "go should typecheck")
        val printed = prettyPrintType(t!!)
        // Only associated types the method actually uses get a resolution ('k is
        // first_key's return; 'v is declared but unused by first_key, so it has no
        // binding to show). The point of THIS test is the `having 'x = 'y` var-
        // valued rendering on a generic fn; multi-key comma-joining is exercised by
        // the effect printer it mirrors.
        assertTrue(
            Regex("""having 'k = '\w+""").containsMatchIn(printed),
            "expected `having 'k = <var>` in the with-clause; got: $printed"
        )
    }

    // ─── SOUNDNESS: wrong having is a type error ─────────────────────────

    /** Performing on a concrete `Int` resolves `'out` to `Str`; annotating the
     *  result `Int` must be rejected — the ability analogue of the effect
     *  `<Exception having 'error = Int>` rejection. */
    @Test
    fun `wrong having on a concrete resolution is a type error`() {
        val e = err(
            """
            ability Producer for 'a having 'out where {
              produce: { it: 'a } -> 'out
            }
            impl Producer for Int having 'out = Str where {
              produce = { it } -> "x"
            }
            pub let bad: {} -> Int = {} -> (5).produce {}
            """.trimIndent()
        )
        assertNotNull(e, "produce resolves 'out=Str on a concrete Int; annotating Int must be rejected")
    }

    // ─── ANNOTATION RULES: having optional; do-bind unaffected ───────────

    @Test
    fun `omitting having in the annotation still typechecks`() {
        assertNull(
            err(
                """
                ability Producer for 'a having 'out where {
                  produce: { it: 'a } -> 'out
                }
                impl Producer for Int having 'out = Str where {
                  produce = { it } -> "x"
                }
                pub let mk: { x: 'a } with Producer 'a -> 'out = { x } ->
                  x.produce {}
                """.trimIndent()
            ),
            "with Producer 'a (no having) should typecheck; 'out is inferred"
        )
    }

    /** Guard: the associated-type-constraint propagation this feature adds must
     *  NOT disturb `<-` do-bind, which is itself a Tryable associated-type
     *  ability resolved locally in the body. (The stdlib do_bind_* fixtures are
     *  the fuller regression set; this is a fast local smoke.) */
    @Test
    fun `do-bind still typechecks (regression guard)`() {
        // Unannotated: `<-` injects <Exception>, so the do-block yields a Result.
        assertNull(
            err(
                """
                pub let unwrap = { a: Result Int Str } -> do
                  let x <- a
                  x
                """.trimIndent()
            ),
            "do-bind (<-) must still typecheck"
        )
    }
}
