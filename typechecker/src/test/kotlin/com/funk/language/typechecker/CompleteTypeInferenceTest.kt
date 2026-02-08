package com.funk.language.typechecker

import com.funk.language.ast.*
import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CompleteTypeInferenceTest {
    private lateinit var parser: FunkParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setUp() {
        ModuleResolver.clearCache()  // Clear module cache to prevent state pollution between tests
        parser = FunkParser()
        typeChecker = TypeChecker()
    }

    private fun parseAndTypeCheck(code: String): Program {
        val program = parser.parse(code)
        return typeChecker.typeCheck(program).program
    }

    private fun getVariableType(program: Program, varName: String): FunkType? {
        return program.items.filterIsInstance<LetBinding>()
            .find { it.variableName == varName }?.inferredType
    }

    private fun getTypeDeclaration(program: Program, typeName: String): TypeDeclaration? {
        return program.items.filterIsInstance<TypeDeclaration>()
            .find { it.name == typeName }
    }

    @Test
    fun `test complete shape calculator example with full inference`() {
        val code = """
            use std::int

            type Shape = | Circle { radius: Int }, Rectangle { width: Int, height: Int }, Triangle { base: Int, height: Int } |

            let calculateArea = {shape} -> match shape is
              Circle { radius } -> 3 * radius * radius  # π ≈ 3
              Rectangle { width, height } -> width * height
              Triangle { base, height } -> (base * height) / 2

            let circle = Circle { radius = 5 }
            let rectangle = Rectangle { width = 4, height = 6 }
            let triangle = Triangle { base = 3, height = 4 }

            let circleArea = calculateArea { shape = circle }
            let rectangleArea = calculateArea { shape = rectangle }
            let triangleArea = calculateArea { shape = triangle }

            "Circle: $(int::to_string { n = circleArea }), Rectangle: $(int::to_string { n = rectangleArea }), Triangle: $(int::to_string { n = triangleArea })"
        """.trimIndent()

        val program = parseAndTypeCheck(code)

        // Verify Shape type definition has all constructors
        val shapeTypeDecl = getTypeDeclaration(program, "Shape")
        assertNotNull(shapeTypeDecl)
        assertTrue(shapeTypeDecl.inferredType is FunkType.VariantType)
        val shapeVariantType = shapeTypeDecl.inferredType as FunkType.VariantType
        assertEquals(3, shapeVariantType.constructors.size)
        assertTrue(shapeVariantType.constructors.containsKey("Circle"))
        assertTrue(shapeVariantType.constructors.containsKey("Rectangle"))
        assertTrue(shapeVariantType.constructors.containsKey("Triangle"))

        // Verify calculateArea function type
        val calculateAreaType = getVariableType(program, "calculateArea")
        assertNotNull(calculateAreaType)
        assertTrue(calculateAreaType is FunkType.FunctionType)
        val funcType = calculateAreaType as FunkType.FunctionType
        assertTrue(funcType.paramType is FunkType.RecordType)
        assertTrue((funcType.paramType as FunkType.RecordType).fields.containsKey("shape"))
        assertTrue(funcType.returnType is FunkType.IntType)

        // Verify that all area calculations result in Int
        assertEquals(FunkType.IntType, getVariableType(program, "circleArea"))
        assertEquals(FunkType.IntType, getVariableType(program, "rectangleArea"))
        assertEquals(FunkType.IntType, getVariableType(program, "triangleArea"))
        
        // The individual shape instances may have more specific variant types (just the constructor they use)
        // but they should still be variant types that are assignable to Shape
        val circleType = getVariableType(program, "circle")
        val rectangleType = getVariableType(program, "rectangle") 
        val triangleType = getVariableType(program, "triangle")
        
        assertTrue(circleType is FunkType.VariantType)
        assertTrue(rectangleType is FunkType.VariantType)
        assertTrue(triangleType is FunkType.VariantType)
    }

    @Test
    fun `test pattern variable inference in simple match`() {
        val code = """
            type Color = | Red { value: Int } |
            
            let test = {color} -> match color is
              Red { value } -> value + 10
              
            let result = test { color = Red { value = 5 } }
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        
        val testType = getVariableType(program, "test")
        assertNotNull(testType)
        assertTrue(testType is FunkType.FunctionType)
        
        val resultType = getVariableType(program, "result")
        assertEquals(FunkType.IntType, resultType)
    }

    @Test
    fun `test multiple pattern variable inference`() {
        val code = """
            type Point = | Point2D { x: Int, y: Int } |
            
            let distance = {point} -> match point is
              Point2D { x, y } -> x * x + y * y
              
            let result = distance { point = Point2D { x = 3, y = 4 } }
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        
        val distanceType = getVariableType(program, "distance")
        assertNotNull(distanceType)
        assertTrue(distanceType is FunkType.FunctionType)
        
        val resultType = getVariableType(program, "result")
        assertEquals(FunkType.IntType, resultType)
    }

    @Test
    fun `test nested pattern variable inference`() {
        val code = """
            type Inner = | Inner { value: Int } |
            type Outer = | Outer { inner: Inner } |
            
            let extract = {outer} -> match outer is
              Outer { inner } -> match inner is
                Inner { value } -> value
                
            let result = extract { outer = Outer { inner = Inner { value = 42 } } }
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        
        val extractType = getVariableType(program, "extract")
        assertNotNull(extractType)
        assertTrue(extractType is FunkType.FunctionType)
        
        val resultType = getVariableType(program, "result")
        assertEquals(FunkType.IntType, resultType)
    }

    @Test
    fun `test guard condition with pattern variables`() {
        val code = """
            type Score = | Score { points: Int } |
            
            let classify = {score} -> match score is
              Score { points } if points > 90 -> "A"
              Score { points } -> "B"
              
            let result = classify { score = Score { points = 95 } }
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        
        val classifyType = getVariableType(program, "classify")
        assertNotNull(classifyType)
        assertTrue(classifyType is FunkType.FunctionType)
        
        val resultType = getVariableType(program, "result")
        assertEquals(FunkType.StringType, resultType)
    }

    @Test
    fun `test multiple patterns with same variable names`() {
        val code = """
            use std::int

            type Temperature = | Hot { degrees: Int }, Cold { degrees: Int } |

            let describe = {temp} -> match temp is
              Hot { degrees } | Cold { degrees } -> "Temperature is $(int::to_string { n = degrees })"

            let result = describe { temp = Hot { degrees = 80 } }
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        
        val describeType = getVariableType(program, "describe")
        assertNotNull(describeType)
        assertTrue(describeType is FunkType.FunctionType)
        
        val resultType = getVariableType(program, "result")
        assertEquals(FunkType.StringType, resultType)
    }

    @Test
    fun `test complex variant with multiple constructors`() {
        val code = """
            type Expression = | Number { value: Int }, Add { left: Int, right: Int } |
            
            let evaluate = {expr: Expression} -> match expr is
              Number { value } -> value
              Add { left, right } -> left + right
              
            let result = evaluate { expr = Add { left = 5, right = 3 } }
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        
        val evaluateType = getVariableType(program, "evaluate")
        assertNotNull(evaluateType)
        assertTrue(evaluateType is FunkType.FunctionType)
        
        val resultType = getVariableType(program, "result")
        assertEquals(FunkType.IntType, resultType)
    }

    @Test
    fun `test inference with logical operators in guards`() {
        val code = """
            type Range = | Range { min: Int, max: Int } |
            
            let isValid = {range: Range} -> match range is
              Range { min, max } if min >= 0 && max <= 100 -> True
              _ -> False
              
            let result = isValid { range = Range { min = 10, max = 90 } }
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        
        val isValidType = getVariableType(program, "isValid")
        assertNotNull(isValidType)
        assertTrue(isValidType is FunkType.FunctionType)
        
        val resultType = getVariableType(program, "result")
        // Bool can be BooleanType or VariantType with True/False constructors
        assertTrue(resultType is FunkType.BooleanType ||
                   (resultType is FunkType.VariantType && resultType.constructors.keys == setOf("True", "False")))
    }

    @Test
    fun `test function composition with inferred types`() {
        val code = """
            type Box = | Box { content: Int } |
            
            let unbox = {box: Box} -> match box is
              Box { content } -> content
              
            let double = {x: Int} -> x * 2
            
            let process = {box: Box} -> double { x = unbox { box = box } }
            
            let result = process { box = Box { content = 21 } }
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        
        val processType = getVariableType(program, "process")
        assertNotNull(processType)
        assertTrue(processType is FunkType.FunctionType)
        
        val resultType = getVariableType(program, "result")
        assertEquals(FunkType.IntType, resultType)
    }

    @Test
    fun `test simple variant type inference`() {
        val code = """
            type Status = | Active { code: Int }, Inactive |
            
            let getCode = {status: Status} -> match status is
              Active { code } -> code
              Inactive -> 0
              
            let myStatus = Active { code = 42 }
            let result = getCode { status = myStatus }
        """.trimIndent()

        val program = parseAndTypeCheck(code)
        
        val getCodeType = getVariableType(program, "getCode")
        assertNotNull(getCodeType)
        assertTrue(getCodeType is FunkType.FunctionType)
        
        val resultType = getVariableType(program, "result")
        assertEquals(FunkType.IntType, resultType)
    }
}
