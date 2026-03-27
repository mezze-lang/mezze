package com.mezze.language.typechecker

import com.mezze.language.ast.*
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertNotNull
import kotlin.test.assertContains

/**
 * Tests for unreachable pattern detection in match expressions.
 *
 * Key rules:
 * 1. Closed variants (declared types like `type Color = | Red, Blue |`) - wildcard after all constructors is ERROR
 * 2. Built-in closed types (Option, Result, Bool) - wildcard after all constructors is ERROR
 * 3. Inferred open variants (no type declaration, has wildcard) - wildcard keeps type open, OK
 * 4. Open variants (declared with `...`) - wildcard is always OK
 */
class UnreachablePatternTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun parseAndTypeCheck(code: String): TypeCheckResult {
        val program = parser.parse(code)
        return typeChecker.typeCheck(program)
    }

    private fun assertTypeCheckFails(code: String, expectedMessagePart: String): TypeCheckError {
        val program = parser.parse(code)
        val error = assertThrows<TypeCheckError> {
            typeChecker.typeCheck(program)
        }
        assertContains(error.message ?: "", expectedMessagePart, ignoreCase = true)
        return error
    }

    // ========================================================================
    // CLOSED USER-DEFINED VARIANTS
    // ========================================================================

    @Test
    fun `wildcard after all constructors of closed variant should fail`() {
        val code = """
            type Color = | Red, Blue |

            let f = { c } -> match c is
                Red -> 1
                Blue -> 2
                _ -> 3
        """.trimIndent()

        assertTypeCheckFails(code, "Unreachable pattern")
    }

    @Test
    fun `variable pattern after all constructors of closed variant should fail`() {
        val code = """
            type Color = | Red, Blue |

            let f = { c } -> match c is
                Red -> 1
                Blue -> 2
                other -> 3
        """.trimIndent()

        assertTypeCheckFails(code, "Unreachable pattern")
    }

    @Test
    fun `exhaustive match on closed variant without wildcard should pass`() {
        val code = """
            type Color = | Red, Blue, Green |

            let f = { c } -> match c is
                Red -> 1
                Blue -> 2
                Green -> 3
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    @Test
    fun `wildcard covering missing constructors on closed variant should pass`() {
        val code = """
            type Color = | Red, Blue, Green |

            let f = { c } -> match c is
                Red -> 1
                _ -> 0
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    // ========================================================================
    // BUILT-IN CLOSED TYPES (Option, Result, Bool)
    // ========================================================================

    @Test
    fun `wildcard after Some and None should fail`() {
        val code = """
            let f = { opt } -> match opt is
                Some { val } -> val
                None -> 0
                _ -> -1
        """.trimIndent()

        assertTypeCheckFails(code, "Unreachable pattern")
    }

    @Test
    fun `wildcard after Ok and Err should fail`() {
        val code = """
            let f = { res } -> match res is
                Ok { val } -> val
                Err { error } -> 0
                _ -> -1
        """.trimIndent()

        assertTypeCheckFails(code, "Unreachable pattern")
    }

    @Test
    fun `wildcard after True and False should fail`() {
        val code = """
            let f = { b } -> match b is
                True -> 1
                False -> 0
                _ -> -1
        """.trimIndent()

        assertTypeCheckFails(code, "Unreachable pattern")
    }

    @Test
    fun `exhaustive Option match without wildcard should pass`() {
        val code = """
            let f = { opt } -> match opt is
                Some { val } -> val
                None -> 0
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    @Test
    fun `exhaustive Result match without wildcard should pass`() {
        val code = """
            let f = { res } -> match res is
                Ok { val } -> val
                Err { error } -> 0
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    @Test
    fun `exhaustive Bool match without wildcard should pass`() {
        val code = """
            let f = { b } -> match b is
                True -> 1
                False -> 0
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    // ========================================================================
    // INFERRED OPEN VARIANTS (no type declaration)
    // ========================================================================

    @Test
    fun `wildcard with inferred variant should pass and keep type open`() {
        // No type declaration - variant is inferred from patterns
        // Wildcard makes the type open: | Red, Blue, ... |
        val code = """
            let f = { c } -> match c is
                Red -> 1
                Blue -> 2
                _ -> 0
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    @Test
    fun `variable pattern with inferred variant should pass and keep type open`() {
        val code = """
            let f = { c } -> match c is
                Red -> 1
                other -> 0
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    @Test
    fun `inferred variant without wildcard should be closed`() {
        // Without wildcard, the variant is closed to exactly the matched constructors
        val code = """
            let f = { c } -> match c is
                Red -> 1
                Blue -> 2
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    // ========================================================================
    // DECLARED OPEN VARIANTS
    // ========================================================================

    @Test
    fun `wildcard on declared open variant should always pass`() {
        val code = """
            type Event = | Click, Hover, ... |

            let f = { e } -> match e is
                Click -> 1
                Hover -> 2
                _ -> 0
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    @Test
    fun `partial match on open variant with wildcard should pass`() {
        val code = """
            type Event = | Click, Hover, ... |

            let f = { e } -> match e is
                Click -> 1
                _ -> 0
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    // ========================================================================
    // EDGE CASES
    // ========================================================================

    @Test
    fun `wildcard with guard should not count as exhaustive`() {
        // Guards make patterns conditional, so subsequent patterns may still be reachable
        val code = """
            type Color = | Red, Blue |

            let f = { c, x } -> match c is
                Red if x > 0 -> 1
                Blue -> 2
                _ -> 0
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    @Test
    fun `nested match with unreachable pattern should fail`() {
        val code = """
            type Outer = | A, B |
            type Inner = | X, Y |

            let f = { o, i } -> match o is
                A -> match i is
                    X -> 1
                    Y -> 2
                    _ -> 3
                B -> 0
        """.trimIndent()

        assertTypeCheckFails(code, "Unreachable pattern")
    }

    @Test
    fun `single constructor variant with wildcard should fail`() {
        val code = """
            type Single = | Only |

            let f = { s } -> match s is
                Only -> 1
                _ -> 0
        """.trimIndent()

        assertTypeCheckFails(code, "Unreachable pattern")
    }

    @Test
    fun `variant with payload - wildcard after all constructors should fail`() {
        val code = """
            type Maybe 'a = | Just { val: 'a }, Nothing |

            let f = { m } -> match m is
                Just { val } -> val
                Nothing -> 0
                _ -> -1
        """.trimIndent()

        assertTypeCheckFails(code, "Unreachable pattern")
    }

    @Test
    fun `or-pattern covering all constructors with wildcard should fail`() {
        val code = """
            type Color = | Red, Blue |

            let f = { c } -> match c is
                Red | Blue -> 1
                _ -> 0
        """.trimIndent()

        assertTypeCheckFails(code, "Unreachable pattern")
    }

    @Test
    fun `three constructor variant exhaustive then wildcard should fail`() {
        val code = """
            type Traffic = | Red, Yellow, Green |

            let f = { t } -> match t is
                Red -> "stop"
                Yellow -> "caution"
                Green -> "go"
                _ -> "unknown"
        """.trimIndent()

        assertTypeCheckFails(code, "Unreachable pattern")
    }

    @Test
    fun `error message should list all constructors`() {
        val code = """
            type ABC = | A, B, C |

            let f = { x } -> match x is
                A -> 1
                B -> 2
                C -> 3
                _ -> 0
        """.trimIndent()

        val error = assertTypeCheckFails(code, "Unreachable pattern")
        // Check that error message mentions the constructors
        val msg = error.message ?: ""
        assertContains(msg, "A", ignoreCase = false)
        assertContains(msg, "B", ignoreCase = false)
        assertContains(msg, "C", ignoreCase = false)
    }
}
