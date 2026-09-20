package com.mezze.language.typechecker

import com.mezze.language.ast.MezzeType
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.fail

/**
 * A module that declares its own ability named like a prelude one means ITS OWN.
 *
 * `TypeAnnotationConverter` stamped a `with <Name> 'a` constraint from
 * `WellKnownHashConstants` — a GENERATED name-to-hash table of stdlib abilities,
 * which cannot see a local declaration. So a module declaring `ability Ord` had
 * its annotation carry the STDLIB's `Ord` (`compare.mz`, ops lt/le/gt/ge) while
 * the inferred side carried the local one (op `compare`). One ability, two
 * identities, inside a single declaration.
 *
 * It hid because the check that compares the two sides compared NAMES, which
 * cannot see the difference. It surfaced the moment that check was keyed on the
 * declaration hash, and it is the reason that keying could not be done until now.
 *
 * The rule this restores is the ordinary one: a local declaration shadows the
 * prelude, for abilities as for everything else.
 */
class LocalAbilityShadowsPreludeTest {
    private lateinit var parser: MezzeParser

    @BeforeEach
    fun setUp() {
        ModuleResolver.clearCache(); TypeChecker.clearCache(); parser = MezzeParser()
    }

    private fun check(src: String) =
        TypeChecker().typeCheck(parser.parseParsedModule(src.trimIndent(), "test.mz"), Paths.get("."), enablePrelude = true)

    @Test
    fun `an annotation naming a local ability carries the LOCAL declaration's identity`() {
        // `Ord` is also a prelude ability, with entirely different operations.
        val res = check("""
            ability Ord for 'a where {
                compare: {r: 'a} -> Int
            }
            impl Ord for Int where { compare = {r} -> 0 }
            pub let cmp: {x: 'a, y: 'a} with Ord 'a -> Int = {x, y} -> x.compare {r = y}
        """)

        val decl = res.typedModule?.decls.orEmpty()
            .filterIsInstance<com.mezze.language.typedast.TypedLetDecl>()
            .firstOrNull { it.name == "cmp" } ?: fail("no `cmp` declaration emitted")
        val fn = decl.scheme.type as? MezzeType.FunctionType ?: fail("`cmp` is not a function type")
        val ord = fn.abilityConstraints.firstOrNull { it.abilityName == "Ord" }
            ?: fail("`cmp` lost its `with Ord 'a` constraint entirely")

        val hash = assertNotNull(ord.abilityHash, "the Ord constraint carries no identity at all")

        // The identity must be the LOCAL declaration — the one this module wrote,
        // whose single operation is `compare`. The prelude's has lt/le/gt/ge.
        val localOrd = assertNotNull(
            res.typedModule?.abilityDecls?.firstOrNull { it.name == "Ord" },
            "the module's own `ability Ord` was not emitted",
        )
        assertEquals(
            localOrd.hash, hash,
            "the annotation's `Ord` is not this module's `Ord`. It was stamped from the generated " +
                "stdlib table, which maps the NAME to the prelude's declaration and cannot see a " +
                "local one — so the annotation and the inferred body disagreed about which ability " +
                "the program means.",
        )
    }
}
