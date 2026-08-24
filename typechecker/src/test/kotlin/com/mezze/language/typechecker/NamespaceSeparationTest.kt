package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import kotlin.test.assertTrue
import kotlin.test.assertFails

/**
 * #311 — Namespace separation tests.
 *
 * Mezze has two declaration namespaces:
 *
 *   Type namespace: type X, newtype X, ability X, effect X,
 *                   and declared variant/newtype constructor names
 *   Value namespace: let x, declared constructors, performer X
 *
 * Constructors live in BOTH namespaces because they appear in type
 * definitions AND can be invoked as values.
 *
 * Self-naming (`type Foo = | Foo {…} |`, `newtype Foo = Native`)
 * is one logical declaration — the type name and its eponymous
 * constructor share the slot, no collision.
 */
class NamespaceSeparationTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun typeCheck(code: String): TypeCheckResult {
        val parsedModule = parser.parseParsedModule(code, "test.mz")
        return typeChecker.typeCheck(parsedModule, Paths.get("."))
    }

    private fun assertRejects(code: String, expectInError: String) {
        val ex = assertFails { typeCheck(code) }
        // Errors may chain via cause; walk the chain.
        val messages = generateSequence<Throwable>(ex) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" | ")
        assertTrue(
            messages.contains(expectInError, ignoreCase = true),
            "expected error chain to mention '$expectInError', got: $messages"
        )
    }

    private fun assertAccepts(code: String) {
        // typeCheck throws TypeCheckError on failure; if it returns, code is accepted.
        typeCheck(code)
    }

    // ========================================================================
    // TYPE NAMESPACE — collisions among type-level identifiers
    // ========================================================================

    @Test
    fun `type and effect with same name collide`() {
        assertRejects(
            """
                type Foo = { x: Int }
                effect Foo where {
                  op : { x: Int } -> Int <Foo>
                }
            """.trimIndent(),
            "Foo"
        )
    }

    @Test
    fun `type and ability with same name collide`() {
        assertRejects(
            """
                type Foo = { x: Int }
                ability Foo for 'a where {
                  op : { it: 'a } -> 'a
                }
            """.trimIndent(),
            "Foo"
        )
    }

    @Test
    fun `effect and ability with same name collide`() {
        assertRejects(
            """
                effect Foo where {
                  op : {} -> {} <Foo>
                }
                ability Foo for 'a where {
                  m : { it: 'a } -> 'a
                }
            """.trimIndent(),
            "Foo"
        )
    }

    @Test
    fun `variant constructor and effect with same name collide`() {
        assertRejects(
            """
                type Color = | Red, Green, Blue |
                effect Red where {
                  op : {} -> {} <Red>
                }
            """.trimIndent(),
            "Red"
        )
    }

    @Test
    fun `variant constructor and ability with same name collide`() {
        assertRejects(
            """
                type Color = | Red, Green, Blue |
                ability Red for 'a where {
                  m : { it: 'a } -> 'a
                }
            """.trimIndent(),
            "Red"
        )
    }

    @Test
    fun `newtype constructor and effect with same name collide`() {
        assertRejects(
            """
                newtype Box = Int
                effect Box where {
                  op : {} -> {} <Box>
                }
            """.trimIndent(),
            "Box"
        )
    }

    // ========================================================================
    // VALUE NAMESPACE — collisions among value-level identifiers
    // ========================================================================

    @Test
    fun `let binding and performer with same name collide`() {
        // performer P is in value ns; let foo would also be (lowercase, but
        // performer is uppercase by convention so this combo is rare)
        assertRejects(
            """
                effect Console where {
                  log : { msg: Str } -> {} <Console>
                }
                impl Console for Logger where {
                  log = { msg } -> {}
                }
                let Logger = 42
            """.trimIndent(),
            "Logger"
        )
    }

    @Test
    fun `variant constructor and let with same name collide`() {
        assertRejects(
            """
                type Color = | Red, Green |
                let Red = 42
            """.trimIndent(),
            "Red"
        )
    }

    // ========================================================================
    // SELF-NAMING — type and constructor sharing a name in same decl
    // ========================================================================

    @Test
    fun `type with eponymous variant constructor is fine`() {
        // type Box = | Box {val: Int} | — Box appears as both type and
        // constructor but it's ONE declaration, no collision.
        assertAccepts(
            """
                type Box = | Box { val: Int } |
                let b = Box.(5)
            """.trimIndent()
        )
    }

    @Test
    fun `newtype with eponymous constructor is fine`() {
        // Newtype constructors take {val = …} syntax to wrap the inner value.
        assertAccepts(
            """
                newtype Box = Int
                let b : Box = Box.(42)
            """.trimIndent()
        )
    }

    // ========================================================================
    // CROSS-NAMESPACE — type-only and value-only names sharing
    // ========================================================================

    @Test
    fun `record type alias with no constructor allows same-name let`() {
        // type Box = { x: Int } — Box is type-ns only (records have no
        // constructor in value ns).  let Box = 5 is fine.
        assertAccepts(
            """
                type BoxR = { x: Int }
                let BoxR = 42
            """.trimIndent()
        )
    }

    @Test
    fun `record type alias used as record construction works`() {
        // The user's motivating example.  Box is just a type alias for the
        // record shape; `Box.(5)` resolves as record construction, not
        // a constructor invocation.
        assertAccepts(
            """
                type Boxy = { val: Int }
                let x : Boxy = { val = 5 }
            """.trimIndent()
        )
    }

    // ========================================================================
    // ANONYMOUS CONSTRUCTOR SHAPE-CONSISTENCY
    // ========================================================================

    @Test
    fun `anonymous constructors with same name and same shape are fine`() {
        assertAccepts(
            """
                let x = Red { val = 5 }
                let y = Red { val = 7 }
            """.trimIndent()
        )
    }

    @Test
    fun `anonymous constructors with same name and conflicting shapes collide`() {
        // The user's specific scenario: two `Red` uses with different payloads.
        // Should reject loudly — `Red` can't have two shapes simultaneously.
        assertRejects(
            """
                let x = Red { val = 5 }
                let y = Red { isLight = False }
            """.trimIndent(),
            "Red"
        )
    }

    @Test
    fun `anonymous constructors with same field names but incompatible types are rejected`() {
        // Same field name `id` but Int vs Str — won't unify, so the second use
        // can never share a type with the first. Reject.
        assertRejects(
            """
                let x = Red { id = 5 }
                let y = Red { id = "hello" }
            """.trimIndent(),
            "Red"
        )
    }

    @Test
    fun `anonymous constructor must match declared variant shape`() {
        assertRejects(
            """
                type Color = | Red { val: Int } |
                let r = Red { isLight = False }
            """.trimIndent(),
            "Red"
        )
    }
}
