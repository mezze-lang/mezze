package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `use a::{ b, c }` where b and c are SUBMODULES.
 *
 * `std::collections` has no module file — it is a folder of `seq.mz`, `list.mz`,
 * … — so a `{ … }` group naming its submodules used to fail the module load and
 * report an error telling the reader to split the group into one `use` per line.
 * The group is now a spelling of exactly that: each item is imported QUALIFIED
 * under its own last segment (or its alias), in written order.
 *
 * The tests pair each grouped form with the separate-line form it must equal —
 * a feature that resolves but binds something different is not this feature.
 */
class GroupedSubmoduleImportTest {

    private lateinit var tc: TypeChecker

    @BeforeEach
    fun setUp() {
        ModuleResolver.clearCache(); TypeChecker.clearCache(); tc = TypeChecker()
    }

    private fun err(src: String): String? =
        try {
            tc.typeCheck(MezzeParser().parseParsedModule(src.trimIndent(), "t.mz"), Paths.get("."), enablePrelude = true)
            null
        } catch (e: Exception) {
            // A module-resolution failure surfaces as ModuleError, not TypeCheckError.
            e.message ?: e.toString()
        }

    private fun signatureOf(src: String, binding: String): String {
        ModuleResolver.clearCache(); TypeChecker.clearCache()
        val r = TypeChecker().checkParsedModule(
            MezzeParser().parseParsedModule(src.trimIndent(), "t.mz"), Paths.get("."), null, true,
        )
        val success = r as? TypeChecker.TypeCheckResult.Success
            ?: error("expected the program to typecheck, got: $r")
        val decl = success.typedModule!!.decls
            .filterIsInstance<com.mezze.language.typedast.TypedLetDecl>()
            .find { it.name == binding }
            ?: error("no binding named $binding")
        return com.mezze.language.ast.prettyPrintType(decl.scheme.type)
    }

    @Test
    fun `a group of submodules resolves`() {
        assertNull(
            err(
                """
                use std::collections::{ seq, list }
                pub let f = { xs: ['a] } -> seq::size { xs = xs }
                pub let g = { xs: List 'a } -> list::size { xs = xs }
                """,
            ),
            "`use std::collections::{ seq, list }` must import both submodules",
        )
    }

    @Test
    fun `the group binds exactly what the separate lines bind`() {
        val grouped = signatureOf(
            """
            use std::collections::{ seq, list }
            pub let f = { xs: ['a] } -> seq::size { xs = xs }
            """,
            "f",
        )
        val separate = signatureOf(
            """
            use std::collections::seq
            use std::collections::list
            pub let f = { xs: ['a] } -> seq::size { xs = xs }
            """,
            "f",
        )
        assertEquals(separate, grouped, "the group is a spelling, not a second meaning")
    }

    @Test
    fun `a single-item group works too`() {
        assertNull(
            err(
                """
                use std::collections::{ seq }
                pub let f = { xs: ['a] } -> seq::size { xs = xs }
                """,
            ),
        )
    }

    @Test
    fun `an alias inside the group renames that submodule`() {
        assertNull(
            err(
                """
                use std::collections::{ seq as s }
                pub let f = { xs: ['a] } -> s::size { xs = xs }
                """,
            ),
            "`seq as s` must bind the qualifier `s`",
        )
    }

    /**
     * The path is a folder, so a name that is NOT a submodule cannot resolve to
     * anything — the folder declares nothing itself. The error must name the items
     * that fail rather than blame the group, which is fine.
     */
    @Test
    fun `a name that is neither submodule nor export is reported by name`() {
        val e = err(
            """
            use std::collections::{ seq, not_a_thing }
            pub let f = { xs: ['a] } -> seq::size { xs = xs }
            """,
        )
        assertTrue(
            e != null && e.contains("not_a_thing"),
            "the error must name the item that does not resolve; got: $e",
        )
    }
}
