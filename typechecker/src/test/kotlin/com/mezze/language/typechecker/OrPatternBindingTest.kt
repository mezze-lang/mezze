package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for or-pattern binding validation.
 *
 * Or-patterns (pattern1 | pattern2 | ...) must bind exactly the same variables
 * in all alternatives. This ensures that the case body can use any bound variable
 * regardless of which alternative matched.
 */
class OrPatternBindingTest {
    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setUp() {
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun parseAndTypeCheck(code: String) = typeChecker.typeCheck(parser.parse(code))

    // ========== VALID OR-PATTERNS (SAME BINDINGS) ==========

    @Test
    fun `test or-pattern with same bindings should pass`() {
        val code = """
            type Result = | Ok { value: Int }, Err { value: Int } |

            let get_value = {r} ->
                match r is
                    Ok { value = v } | Err { value = v } -> v
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `test or-pattern with no bindings should pass`() {
        val code = """
            type Color = | Red, Blue, Green |

            let is_warm = {c} ->
                match c is
                    Red | Green -> True
                    Blue -> False
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `test or-pattern with multiple same bindings should pass`() {
        val code = """
            type Shape = | Rect { w: Int, h: Int }, Square { w: Int, h: Int } |

            let area = {s} ->
                match s is
                    Rect { w = width, h = height } | Square { w = width, h = height } -> width * height
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `test or-pattern with three alternatives same bindings should pass`() {
        val code = """
            type Status = | Active { id: Int }, Pending { id: Int }, Cancelled { id: Int } |

            let get_id = {s} ->
                match s is
                    Active { id = x } | Pending { id = x } | Cancelled { id = x } -> x
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    // ========== INVALID OR-PATTERNS (DIFFERENT BINDINGS) ==========

    @Test
    fun `test or-pattern with missing binding in second alternative should fail`() {
        val code = """
            type Maybe = | Just { value: Int }, Nothing |

            let bad = {m} ->
                match m is
                    Just { value = v } | Nothing -> v
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message?.contains("Or-pattern") == true ||
                   exception.message?.contains("same variables") == true ||
                   exception.message?.contains("binding") == true)
        assertTrue(exception.message?.contains("v") == true ||
                   exception.message?.contains("missing") == true)
    }

    @Test
    fun `test or-pattern with extra binding in second alternative should fail`() {
        val code = """
            type Result = | Ok { value: Int }, Err { code: Int, msg: Str } |

            let bad = {r} ->
                match r is
                    Ok { value = x } | Err { code = x, msg = m } -> x
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message?.contains("Or-pattern") == true ||
                   exception.message?.contains("same variables") == true)
        assertTrue(exception.message?.contains("m") == true ||
                   exception.message?.contains("extra") == true)
    }

    @Test
    fun `test or-pattern with different binding names should fail`() {
        val code = """
            type Pair = | Left { val: Int }, Right { val: Int } |

            let bad = {p} ->
                match p is
                    Left { val = x } | Right { val = y } -> 0
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message?.contains("Or-pattern") == true ||
                   exception.message?.contains("same variables") == true)
    }

    @Test
    fun `test or-pattern first has binding second has none should fail`() {
        val code = """
            type Option = | Some { val: Int }, None |

            let bad = {opt} ->
                match opt is
                    Some { val = v } | None -> v
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message?.contains("Or-pattern") == true ||
                   exception.message?.contains("v") == true ||
                   exception.message?.contains("missing") == true)
    }

    @Test
    fun `test or-pattern first has none second has binding should fail`() {
        val code = """
            type Option = | Some { val: Int }, None |

            let bad = {opt} ->
                match opt is
                    None | Some { val = v } -> 0
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message?.contains("Or-pattern") == true ||
                   exception.message?.contains("v") == true ||
                   exception.message?.contains("extra") == true)
    }

    // ========== OR-PATTERN TYPE UNIFICATION ==========

    @Test
    fun `test or-pattern bindings must have same type`() {
        // Both alternatives bind 'x' but the types should unify
        val code = """
            type Container = | IntBox { val: Int }, AnotherInt { val: Int } |

            let extract = {c} ->
                match c is
                    IntBox { val = x } | AnotherInt { val = x } -> x
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    // ========== EDGE CASES ==========

    @Test
    fun `test single pattern no or should work normally`() {
        val code = """
            type Box = | Box { val: Int } |

            let unbox = {b} ->
                match b is
                    Box { val = v } -> v
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `test or-pattern in nested match should validate bindings`() {
        val code = """
            type Outer = | A { inner: Int }, B { inner: Int } |

            let process = {o, r: Result Int String} ->
                match o is
                    A { inner = x } | B { inner = x } ->
                        match r is
                            Ok { val = y } -> x + y
                            Err { error = _ } -> x
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `test or-pattern with wildcard bindings`() {
        // Wildcards don't create bindings, so this should pass
        val code = """
            type Triple = | A { x: Int, y: Int, z: Int }, B { x: Int, y: Int, z: Int } |

            let get_x = {t} ->
                match t is
                    A { x = val, ... } | B { x = val, ... } -> val
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }
}
