package com.mezze.interpreter

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A type NAME is not a type identity — and for the names the compiler owns, there
 * is nothing to resolve in the first place.
 *
 * Two rules, and the second is what makes the first small.
 *
 * ## Built-in names are not declarable
 *
 * `Option`, `Result`, `Seq`, `Int`, … are synthetic: no `.mz` file declares them,
 * so a user declaration under one of those names was never a redeclaration the
 * compiler could notice, and the two meanings coexisted. It went wrong in both
 * directions. `type Int = | Yes, No |` was silently DISCARDED — `Int` kept meaning
 * the primitive and the author's only clue was a later mismatch against a type
 * they believed they had defined. `type Option = | Yes, No |` was HONOURED while
 * the compiler kept applying `Option`'s own rules, so `Option Int` unified with the
 * local type and a well-typed program died at runtime with "no variant tag matched
 * — typechecker exhaustiveness guarantee violated".
 *
 * ## An ordinary stdlib name still shadows, and now resolves by identity
 *
 * `Json`, `HttpError`, `CancelReason` are plain declarations in `.mz` files, so
 * shadowing one is ordinary module scoping and stays legal. That is where the
 * identity fix still has work to do: a name-carried `TypeApplication` from another
 * module is expanded through a declaration, and that declaration is found by HASH —
 * a hash that misses means NOT HERE, rather than "try the name and take whatever
 * shares it".
 */
class ShadowingTypeNameIdentityTest {

    @BeforeEach
    fun setUp() {
        com.mezze.language.typechecker.ModuleResolver.clearCache()
    }

    private fun errorFrom(src: String): String? =
        runCatching { MezzeTestHelper.run(src) }.exceptionOrNull()?.message

    private fun assertReserved(name: String, decl: String) {
        val e = errorFrom("$decl\nlet a: Int = 1\na")
        assertTrue(
            e?.contains("Cannot declare a type named '$name': it is a built-in type") == true,
            "expected '$name' to be reserved; got: $e",
        )
    }

    /**
     * Driven off [com.mezze.language.ast.BuiltinTypes.coreTypeNames] itself rather
     * than a hand-written list, so a name added there cannot quietly go unreserved.
     * That is not hypothetical: `List` sat outside the reserved set for as long as
     * the builtin could not be USED by name, and the hand-written list here carried
     * the exemption with it. Identity for these types is CARRIED BY THE NAME
     * (TypeIdentity.Builtin), so an unreserved one is a second type answering to the
     * name the compiler keys on.
     */
    @Test
    fun `every core built-in type name cannot be declared`() {
        val core = com.mezze.language.ast.BuiltinTypes.coreTypeNames
        assertTrue(core.contains("List"), "List belongs to the core builtins")
        for (n in core) {
            assertReserved(n, "type $n = | Yes, No |")
        }
    }

    /** The builtin `List` is usable by name, which is why reserving it is safe. */
    @Test
    fun `the builtin List resolves by name with its own constructors`() {
        val e = errorFrom(
            """
            let f = { l: List Int } -> match l is
              Nil -> 0
              Cons { head = h, tail = _ } -> h
            f { l = Nil }
            """.trimIndent(),
        )
        assertTrue(e == null, "the builtin List must resolve by name; got: $e")
    }

    @Test
    fun `a primitive type name cannot be declared`() {
        // These used to be silently discarded, which is the worse failure: the
        // author gets no diagnostic at the declaration, only a confusing mismatch
        // wherever they try to use the type they thought they had.
        for (n in listOf("Int", "Str", "Bool", "Float", "Char", "Bytes", "Frac")) {
            assertReserved(n, "type $n = | Yes, No |")
        }
    }

    @Test
    fun `the rule covers newtype and alias declarations too`() {
        assertReserved("Option", "newtype Option = Int")
        assertReserved("Option", "type Option = Int")
    }

    @Test
    fun `an ordinary stdlib type name is still shadowable`() {
        // The boundary. `Json` is declared in a `.mz` file like any other type, so
        // a module may declare its own and mean it.
        assertEquals(
            1L,
            MezzeTestHelper.run(
                """
                type Json = | Yes, No |
                let f: { j: Json } -> Int = { j } ->
                  match j is
                    Yes -> 1
                    No -> 0
                f { j = Yes }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `a shadowed stdlib type does not unify with the stdlib's own`() {
        // The identity fix, on the names where shadowing is still allowed. The
        // stdlib's `Json` arrives name-carried from another module; expanding it
        // through the LOCAL declaration of that name is what produced a well-typed
        // program that got stuck. It is a type error now.
        val e = errorFrom(
            """
            use std::codec::json
            type Json = | Yes, No |
            let f: { j: Json } -> Int = { j } ->
              match j is
                Yes -> 1
                No -> 0
            f { j = json::from_str { s = "1" } }
            """.trimIndent(),
        )
        assertTrue(
            e?.contains("does not have constructor 'Yes'") == true,
            "expected a type error, not a runtime exhaustiveness violation; got: $e",
        )
    }
}
