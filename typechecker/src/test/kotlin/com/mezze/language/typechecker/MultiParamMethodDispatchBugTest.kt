package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * MINIMAL REPRODUCTION: concrete-receiver method dispatch on a MULTI-PARAMETER
 * ability fails, even though the impl exists.
 *
 * The ability `Scale 'by for 'a` has ONE type parameter beyond the receiver
 * (`'by`). We provide exactly one impl — `impl Scale Int for Int` — and then
 * call the method on a concrete `Int` receiver. It should resolve to that impl.
 * It does NOT: the checker looks up `impl Scale for Int` (single-param shape),
 * ignores the `'by` argument, finds nothing, and reports "Int doesn't support
 * the 'Scale' ability".
 *
 * Contrast: the SAME method call through a generic 'a receiver + a matching
 * `with Scale 'a 'a` constraint typechecks fine — so the constraint/scheme path
 * already handles the second type arg; only concrete resolution drops it.
 */
class MultiParamMethodDispatchBugTest {
    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    /** A multi-param ability with exactly ONE impl, for Int. */
    private val prelude = """
        ## `Scale 'by for 'a`: scale an 'a by a 'by, producing 'out.
        ## One extra type parameter ('by) beyond the receiver ('a).
        ability Scale 'by for 'a having 'out where {
          scale: { it: 'a, by: 'by } -> 'out
        }

        ## Exactly one impl: Int scaled by Int gives Int.
        impl Scale Int for Int having 'out = Int where {
          scale = { it, by } -> it * by
        }
    """.trimIndent()

    private fun check(body: String): TypeCheckResult {
        val parsed = parser.parseParsedModule(prelude + "\n\n" + body, "test.mz")
        return typeChecker.typeCheck(parsed, Paths.get("."), enablePrelude = true)
    }

    // ─────────────────────────────────────────────────────────────────────

    /**
     * THE BUG (now fixed). `10.scale { by = 3 }` on a concrete Int receiver, with
     * `impl Scale Int for Int` in scope, must resolve to that impl and give Int.
     *
     * Before the fix this threw "Int doesn't support the 'Scale' ability" because
     * the method-call AbilityRequired constraint was built from `typeParams` only
     * (which EXCLUDES the receiver, stored separately as `receiverTypeParam`), so
     * `abilityTypeVars.first()` was the extra param `'by` (not the receiver) and
     * `drop(1)` dropped every real arg — producing a single-param `Scale for Int`
     * lookup that can't match the 2-annotation `impl Scale Int for Int`.
     */
    @Test
    fun `concrete Int receiver resolves the multi-param impl`() {
        val r = check("pub let result: Int = 10.scale { by = 3 }")
        assertNotNull(
            r.getVariableType("result"),
            "10.scale {by=3} should resolve `impl Scale Int for Int` and give Int"
        )
    }

    /** A second, structurally different multi-param ability to be sure the fix
     *  isn't specific to Scale's shape: heterogeneous receiver vs arg names. */
    @Test
    fun `concrete receiver resolves a different multi-param ability`() {
        val r = check(
            """
            ability Attach 'tag for 'a having 'out where {
              attach: { it: 'a, tag: 'tag } -> 'out
            }
            impl Attach Str for Int having 'out = Str where {
              attach = { it, tag } -> tag
            }
            pub let label: Str = 7.attach { tag = "id" }
            """.trimIndent()
        )
        assertNotNull(
            r.getVariableType("label"),
            "7.attach {tag=\"id\"} should resolve `impl Attach Str for Int`"
        )
    }

    /**
     * PROOF the impl and coherence themselves are fine: the SAME call through a
     * generic receiver + a `with Scale 'a 'a` constraint typechecks. So the gap
     * is specifically concrete-receiver resolution, not the ability/impl/scheme
     * machinery. (This one passes today.)
     */
    @Test
    fun `generic receiver with a Scale constraint typechecks`() {
        val r = check(
            """
            pub let twice: { x: 'a } with Scale 'a 'a -> 'a = { x } ->
              x.scale { by = x }
            """.trimIndent()
        )
        assertNotNull(
            r.getVariableType("twice"),
            "generic 'a.scale {by='a} typechecks as a Scale-constrained scheme"
        )
    }
}
