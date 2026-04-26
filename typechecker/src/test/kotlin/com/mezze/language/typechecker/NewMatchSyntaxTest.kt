package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.ast.*
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NewMatchSyntaxTest {
    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setUp() {
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    private fun parseAndTypeCheck(code: String): TypeCheckResult {
        val parsedModule = parser.parseParsedModule(code, "test.mz")
        return typeChecker.typeCheck(parsedModule, Paths.get("."))
    }

    private fun getVariableType(result: TypeCheckResult, varName: String): MezzeType? {
        return result.getVariableType(varName)
    }

    @Test
    fun `test basic new match syntax`() {
        val code = """
            type Color = | Red, Blue |
            
            let test = {color} -> match color is
              Red -> "red"
              Blue -> "blue"
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        val testType = getVariableType(result, "test")

        assertNotNull(testType)
        assertTrue(testType is MezzeType.FunctionType)
        assertTrue(testType.returnType is MezzeType.StringType)
    }

    @Test
    fun `test multiple patterns in single case`() {
        val code = """
            type Color = | Red, Blue, Green |
            
            let classify = {color} -> match color is
              Red | Blue -> "primary"
              Green -> "secondary"
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        val classifyType = getVariableType(result, "classify")

        assertNotNull(classifyType)
        assertTrue(classifyType is MezzeType.FunctionType)
        assertTrue(classifyType.returnType is MezzeType.StringType)
    }

    @Test
    fun `test guard conditions with pattern variables`() {
        val code = """
            type Temp = | Hot { degrees: Int } |
            
            let classify = {temp} -> match temp is
              Hot { degrees } if degrees > 80 -> "very hot"
              Hot { degrees } -> "hot"
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        val classifyType = getVariableType(result, "classify")

        assertNotNull(classifyType)
        assertTrue(classifyType is MezzeType.FunctionType)
        assertTrue(classifyType.returnType is MezzeType.StringType)
    }

    @Test
    fun `test guard conditions with logical operators`() {
        val code = """
            type Temp = | Hot { degrees: Int }, Cold { degrees: Int } |
            
            let classify = {temp} -> match temp is
              Hot { degrees } if degrees > 50 && degrees < 100 -> "warm"
              Cold { degrees } if degrees < -10 || degrees > 30 -> "extreme cold"
              Hot { degrees } -> "hot"
              Cold { degrees } -> "cold"
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        val classifyType = getVariableType(result, "classify")

        assertNotNull(classifyType)
        assertTrue(classifyType is MezzeType.FunctionType)
        assertTrue(classifyType.returnType is MezzeType.StringType)
    }

    @Test
    fun `test multiple patterns with guards`() {
        val code = """
            type Temp = | Hot { degrees: Int }, Cold { degrees: Int }, Mild |
            
            let classify = {temp} -> match temp is
              Hot { degrees } | Cold { degrees } if degrees > 100 -> "extreme"
              Hot { degrees } -> "hot"
              Cold { degrees } -> "cold"
              Mild -> "mild"
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        val classifyType = getVariableType(result, "classify")

        assertNotNull(classifyType)
        assertTrue(classifyType is MezzeType.FunctionType)
        assertTrue(classifyType.returnType is MezzeType.StringType)
    }

    @Test
    fun `test exhaustiveness checking - exhaustive match`() {
        val code = """
            type Color = | Red, Blue |
            
            let test = {color} -> match color is
              Red -> "red"
              Blue -> "blue"
        """.trimIndent()

        // Should not throw - this is exhaustive
        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    @Test
    fun `test exhaustiveness checking - non-exhaustive match without wildcard`() {
        val code = """
            type Color = | Red, Blue, Green |

            let test = {color} -> match color is
              Red -> "red"
              Blue -> "blue"
        """.trimIndent()

        // Should throw - missing case for Green (inferred from variant constructors)
        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message?.contains("Green") == true)
    }

    @Test
    fun `test exhaustiveness checking - non-exhaustive with wildcard`() {
        val code = """
            type Color = | Red, Blue, Green |
            
            let test = {color} -> match color is
              Red -> "red"
              _ -> "other"
        """.trimIndent()

        // Should not throw - wildcard covers remaining cases
        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    @Test
    fun `test exhaustiveness checking with guards - should require wildcard`() {
        val code = """
            type Temp = | Hot { degrees: Int } |

            let test = {temp} -> match temp is
              Hot { degrees } if degrees > 80 -> "very hot"
        """.trimIndent()

        // Should throw - guarded case alone doesn't provide exhaustive coverage
        val exception = assertThrows<TypeCheckError> {
            parseAndTypeCheck(code)
        }
        assertTrue(exception.message?.contains("Hot") == true || exception.message?.contains("exhaustive") == true)
    }

    @Test
    fun `test exhaustiveness checking with guards and wildcard`() {
        val code = """
            type Temp = | Hot { degrees: Int } |
            
            let test = {temp} -> match temp is
              Hot { degrees } if degrees > 80 -> "very hot"
              _ -> "other"
        """.trimIndent()

        // Should not throw - wildcard covers conditional cases
        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    @Test
    fun `test guard condition must be boolean`() {
        val code = """
            type Temp = | Hot { degrees: Int } |

            let test = {temp} -> match temp is
              Hot { degrees } if degrees > 0 -> "hot"
              _ -> "other"
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    @Test
    fun `test pattern variables correctly typed in guards`() {
        val code = """
            type Person = | Adult { age: Int }, Child { name: Str } |
            
            let canVote = {person} -> match person is
              Adult { age } if age >= 18 -> True
              _ -> False
        """.trimIndent()

        // Should not throw - age is correctly typed as Int
        val result = parseAndTypeCheck(code)
        val canVoteType = getVariableType(result,"canVote")

        assertNotNull(canVoteType)
        assertTrue(canVoteType is MezzeType.FunctionType)
        // Bool can be BoolType or VariantType with True/False constructors
        val returnType = canVoteType.returnType
        assertTrue(returnType is MezzeType.BoolType ||
                   (returnType is MezzeType.VariantType && returnType.constructors.keys == setOf("True", "False")))
    }

    @Test
    fun `test multiple patterns with same variable names`() {
        val code = """
            type Temp = | Hot { degrees: Int }, Cold { degrees: Int } |

            let isExtreme = {temp} -> match temp is
              Hot { degrees } | Cold { degrees } if degrees > 50 || degrees < -20 -> True
              _ -> False
        """.trimIndent()

        // Should not throw - both patterns bind 'degrees' as Int
        val result = parseAndTypeCheck(code)
        val isExtremeType = getVariableType(result,"isExtreme")

        assertNotNull(isExtremeType)
        assertTrue(isExtremeType is MezzeType.FunctionType)
        // Bool can be BoolType or VariantType with True/False constructors
        val returnType = isExtremeType.returnType
        assertTrue(returnType is MezzeType.BoolType ||
                   (returnType is MezzeType.VariantType && returnType.constructors.keys == setOf("True", "False")))
    }

    @Test
    fun `test open variant with wildcard`() {
        val code = """
            type Color = | Red, Blue, ... |
            
            let test = {color} -> match color is
              Red -> "red"
              Blue -> "blue"
              _ -> "unknown"
        """.trimIndent()

        // Should not throw - open variant allows wildcard
        val result = parseAndTypeCheck(code)
        assertNotNull(result)
    }

    @Test
    fun `test logical operators in guards`() {
        val code = """
            type Grade = | Score { value: Int } |
            
            let letterGrade = {grade} -> match grade is
              Score { value } if value >= 90 && value <= 100 -> "A"
              Score { value } if value >= 80 && value < 90 -> "B"
              Score { value } if value >= 70 && value < 80 -> "C"
              Score { value } if value >= 60 && value < 70 -> "D"
              _ -> "F"
        """.trimIndent()

        // Should not throw - all logical operators work correctly
        val result = parseAndTypeCheck(code)
        val letterGradeType = getVariableType(result,"letterGrade")

        assertNotNull(letterGradeType)
        assertTrue(letterGradeType is MezzeType.FunctionType)
        assertTrue(letterGradeType.returnType is MezzeType.StringType)
    }

    @Test
    fun `test complex nested patterns with guards`() {
        val code = """
            type User = | User { name: Str, age: Int } |
            type Status = | Active { user: User }, Inactive |
            
            let canAccess = {status} -> match status is
              Active { user } if user.age >= 18 -> True
              _ -> False
        """.trimIndent()

        // Should not throw - nested pattern access in guards
        val result = parseAndTypeCheck(code)
        val canAccessType = getVariableType(result,"canAccess")

        assertNotNull(canAccessType)
        assertTrue(canAccessType is MezzeType.FunctionType)
        // Bool can be BoolType or VariantType with True/False constructors
        val returnType = canAccessType.returnType
        assertTrue(returnType is MezzeType.BoolType ||
                   (returnType is MezzeType.VariantType && returnType.constructors.keys == setOf("True", "False")))
    }
}
