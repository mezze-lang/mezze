package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.ast.*
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

/**
 * Demonstrates complete type inference without explicit annotations
 * Core principle: The Mezze language should NEVER require type annotations
 */
class CompleteInferenceDemo {
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
    fun `complete shape calculator with full inference`() {
        val code = """
            type Shape = | Circle { radius: Int }, Rectangle { width: Int, height: Int }, Triangle { base: Int, height: Int } |
            
            let calculateArea = {shape} -> match shape is
              Circle { radius } -> 3 * radius * radius
              Rectangle { width, height } -> width * height
              Triangle { base, height } -> (base * height) / 2
              
            let circle = Circle { radius = 5 }
            let rectangle = Rectangle { width = 4, height = 6 }
            let triangle = Triangle { base = 3, height = 4 }
            
            let circleArea = calculateArea { shape = circle }
            let rectangleArea = calculateArea { shape = rectangle }
            let triangleArea = calculateArea { shape = triangle }
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        
        // Verify calculateArea function type
        val calculateAreaType = getVariableType(result,"calculateArea")
        assertNotNull(calculateAreaType)
        assertTrue(calculateAreaType is MezzeType.FunctionType)
        
        val functionType = calculateAreaType as MezzeType.FunctionType
        assertTrue(functionType.returnType is MezzeType.IntType, "Return type should be Int")
        
        // Verify shape instances are correctly typed
        val circleType = getVariableType(result,"circle")
        assertNotNull(circleType)
        assertTrue(circleType is MezzeType.VariantType, "Circle should be variant type")
        
        val rectangleType = getVariableType(result,"rectangle")
        assertNotNull(rectangleType)
        assertTrue(rectangleType is MezzeType.VariantType, "Rectangle should be variant type")
        
        val triangleType = getVariableType(result,"triangle")
        assertNotNull(triangleType)
        assertTrue(triangleType is MezzeType.VariantType, "Triangle should be variant type")
        
        // Verify area calculations are correctly typed as Int
        val circleAreaType = getVariableType(result,"circleArea")
        assertNotNull(circleAreaType)
        assertTrue(circleAreaType is MezzeType.IntType, "Circle area should be Int")
        
        val rectangleAreaType = getVariableType(result,"rectangleArea")
        assertNotNull(rectangleAreaType)
        assertTrue(rectangleAreaType is MezzeType.IntType, "Rectangle area should be Int")
        
        val triangleAreaType = getVariableType(result,"triangleArea")
        assertNotNull(triangleAreaType)
        assertTrue(triangleAreaType is MezzeType.IntType, "Triangle area should be Int")
    }

    @Test
    fun `simple pattern matching with inference`() {
        val code = """
            type Color = | Red, Blue, Green |
            
            let describe = {color} -> match color is
              Red -> "red"
              Blue -> "blue"
              Green -> "green"
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        val describeType = getVariableType(result,"describe")
        
        assertNotNull(describeType)
        assertTrue(describeType is MezzeType.FunctionType)
        
        val functionType = describeType as MezzeType.FunctionType
        assertTrue(functionType.returnType is MezzeType.StringType, "Should return String")
    }

    @Test
    fun `guard conditions with inference`() {
        val code = """
            type Grade = | Score { points: Int } |
            
            let letterGrade = {grade} -> match grade is
              Score { points } if points >= 90 -> "A"
              Score { points } if points >= 80 -> "B"
              _ -> "F"
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        val letterGradeType = getVariableType(result,"letterGrade")
        
        assertNotNull(letterGradeType)
        assertTrue(letterGradeType is MezzeType.FunctionType)
        
        val functionType = letterGradeType as MezzeType.FunctionType
        assertTrue(functionType.returnType is MezzeType.StringType, "Should return String")
    }

    @Test
    fun `multiple patterns with inference`() {
        val code = """
            type Status = | Active, Inactive, Pending, Error |
            
            let isWorking = {status} -> match status is
              Active | Pending -> True
              Inactive | Error -> False
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        val isWorkingType = getVariableType(result,"isWorking")
        
        assertNotNull(isWorkingType)
        assertTrue(isWorkingType is MezzeType.FunctionType)
        
        val functionType = isWorkingType as MezzeType.FunctionType
        // Bool can be BoolType or VariantType with True/False constructors
        val returnType = functionType.returnType
        assertTrue(returnType is MezzeType.BoolType ||
                   (returnType is MezzeType.VariantType && returnType.constructors.keys == setOf("True", "False")),
                   "Should return Boolean")
    }

    @Test
    fun `logical operators in guards with inference`() {
        val code = """
            type Temperature = | Temp { degrees: Int } |
            
            let comfort = {temp} -> match temp is
              Temp { degrees } if degrees >= 20 && degrees <= 25 -> "perfect"
              Temp { degrees } if degrees < 10 || degrees > 35 -> "extreme"
              _ -> "okay"
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        val comfortType = getVariableType(result,"comfort")
        
        assertNotNull(comfortType)
        assertTrue(comfortType is MezzeType.FunctionType)
        
        val functionType = comfortType as MezzeType.FunctionType
        assertTrue(functionType.returnType is MezzeType.StringType, "Should return String")
    }
}
