package com.mezze.language.typechecker

import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for duplicate type and constructor declaration validation.
 * These tests ensure the typechecker rejects duplicate type names and
 * reused variant constructors across different types.
 */
class DuplicateDeclarationTest {
    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setUp() {
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun parseAndTypeCheck(code: String) = typeChecker.typeCheck(parser.parse(code)).program

    // ========== DUPLICATE TYPE NAME TESTS ==========

    @Test
    fun `test duplicate type declaration should fail`() {
        val code = """
            type Color = | Red, Blue, Green |

            type Color = | Red, Blue, Green, Pink |

            let main = {} -> 0
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message?.contains("Color") == true)
        assertTrue(exception.message?.contains("already declared") == true)
    }

    @Test
    fun `test different type names should pass`() {
        val code = """
            type Color = | Red, Blue |

            type Size = | Small, Large |

            let main = {} -> 0
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `test duplicate record type declaration should fail`() {
        val code = """
            type Point = { x: Int, y: Int }

            type Point = { x: Int, y: Int, z: Int }

            let main = {} -> 0
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message?.contains("Point") == true)
        assertTrue(exception.message?.contains("already declared") == true)
    }

    // ========== DUPLICATE CONSTRUCTOR TESTS ==========

    @Test
    fun `test duplicate constructor across types should fail`() {
        val code = """
            type Color = | Red, Blue, Green |

            type Color2 = | Red, Blue, Green, Pink |

            let main = {} -> 0
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message?.contains("Red") == true)
        assertTrue(exception.message?.contains("Color") == true)
        assertTrue(exception.message?.contains("Color2") == true)
    }

    @Test
    fun `test single overlapping constructor should fail`() {
        val code = """
            type Result = | Ok { value: Int }, Err { message: Str } |

            type Status = | Ok, Pending, Failed |

            let main = {} -> 0
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message?.contains("Ok") == true)
    }

    @Test
    fun `test unique constructors across types should pass`() {
        val code = """
            type Color = | Red, Blue, Green |

            type Size = | Small, Medium, Large |

            let main = {} -> 0
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }

    @Test
    fun `test variant with payload constructor collision should fail`() {
        val code = """
            type Option = | Some { val: Int }, None |

            type Maybe = | Some { val: Str }, Nothing |

            let main = {} -> 0
        """.trimIndent()

        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message?.contains("Some") == true)
    }

    @Test
    fun `test multiple types with unique constructors should pass`() {
        val code = """
            type Color = | Red, Blue |
            type Size = | Small, Large |
            type Status = | Active, Inactive |

            let main = {} -> 0
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        assertNotNull(program)
    }
}
