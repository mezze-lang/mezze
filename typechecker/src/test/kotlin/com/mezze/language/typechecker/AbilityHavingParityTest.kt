package com.mezze.language.typechecker

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.prettyPrintType
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * Bring ABILITY associated types (`having 'out = ...` on a `with`-clause) to
 * parity with EFFECT associated types (`having 'error = ...` in an effect row).
 * Effects already:
 *   - print `having 'a = T, 'b = U` (comma-joined, sorted by key),
 *   - reject a wrong `having` annotation as a type error,
 *   - allow `having` to be omitted in annotations.
 * These tests pin the same three behaviours for ability constraints. They are
 * RED until the ability path populates/checks/prints `having`.
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

    /** A single associated type prints as `having 'out = T` in the with-clause,
     *  mirroring the effect printer's `<Exception having 'error = Str>`. */
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
            printed.contains("Producer") && printed.contains("having") &&
                printed.contains("'out ="),
            "with-clause should render the associated binding, got: $printed"
        )
    }

    /** TWO associated types on one ability print comma-joined, sorted by key —
     *  exactly like the effect printer (`having 'k = Int, 'v = Str`). */
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
        // effect-printer style: `KV ... having 'k = Int, 'v = Str` (sorted by key)
        assertTrue(
            printed.contains("having 'k = Int, 'v = Str"),
            "expected both associated types comma-joined, sorted by key; got: $printed"
        )
    }

    // ─── SOUNDNESS: wrong having in an annotation is a type error ─────────

    /** Annotating `having 'out = Int` when the impl says `'out = Str` must be a
     *  type error — same as effects reject `<Exception having 'error = Int>`. */
    @Test
    fun `wrong single having on an ability annotation is a type error`() {
        val e = err(
            """
            ability Producer for 'a having 'out where {
              produce: { it: 'a } -> 'out
            }
            impl Producer for Int having 'out = Str where {
              produce = { it } -> "x"
            }
            pub let mk: { x: 'a } with Producer 'a having 'out = Int -> 'out = { x } ->
              x.produce {}
            """.trimIndent()
        )
        assertNotNull(e, "annotating 'out = Int when the impl binds 'out = Str must be rejected")
    }

    /** With multiple associated types, a mismatch on ANY key is a type error. */
    @Test
    fun `wrong one of multiple having bindings is a type error`() {
        val e = err(
            """
            ability KV for 'c having 'k, 'v where {
              first_key: { it: 'c } -> 'k
            }
            impl KV for [Int] having 'k = Int, 'v = Str where {
              first_key = { it } -> 0
            }
            pub let go: { xs: [Int] } with KV [Int] having 'k = Int, 'v = Int -> Int = { xs } ->
              xs.first_key {}
            """.trimIndent()
        )
        assertNotNull(e, "'v = Int contradicts the impl's 'v = Str, must be rejected")
    }

    // ─── ANNOTATION RULES: having is optional (parity with effects) ──────

    /** Omitting `having` in the annotation is fine — it's inferred from the impl,
     *  exactly as `<Exception>` (no having) is allowed. */
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

    /** A CORRECT having annotation typechecks (control for the mismatch tests). */
    @Test
    fun `correct having on an ability annotation typechecks`() {
        assertNull(
            err(
                """
                ability Producer for 'a having 'out where {
                  produce: { it: 'a } -> 'out
                }
                impl Producer for Int having 'out = Str where {
                  produce = { it } -> "x"
                }
                pub let mk: { x: 'a } with Producer 'a having 'out = Str -> 'out = { x } ->
                  x.produce {}
                """.trimIndent()
            ),
            "matching 'out = Str should typecheck"
        )
    }
}
