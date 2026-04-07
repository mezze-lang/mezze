package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.ast.*
import com.mezze.language.typedast.TypedTypeDecl
import com.mezze.language.typedast.TypedVariantDef
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CompleteTypeInferenceTest {
    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setUp() {
        ModuleResolver.clearCache()  // Clear module cache to prevent state pollution between tests
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

    /**
     * Helper to retrieve a type declaration by name from the TypedModule.
     * Returns a helper object that mimics the old TypeDeclaration.inferredType API.
     */
    private fun getTypeDeclaration(result: TypeCheckResult, typeName: String): TypedTypeDecl? {
        return result.typedModule?.decls
            ?.filterIsInstance<TypedTypeDecl>()
            ?.find { it.name == typeName }
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

            "Circle: $(int::to_str { n = circleArea }), Rectangle: $(int::to_str { n = rectangleArea }), Triangle: $(int::to_str { n = triangleArea })"
        """.trimIndent()

        val result = parseAndTypeCheck(code)

        // Verify Shape type definition has all constructors
        val shapeTypeDecl = getTypeDeclaration(result,"Shape")
        assertNotNull(shapeTypeDecl)
        assertTrue(shapeTypeDecl.definition is TypedVariantDef)
        val shapeVariantDef = shapeTypeDecl.definition as TypedVariantDef
        assertEquals(3, shapeVariantDef.constructors.size)
        assertTrue(shapeVariantDef.constructors.containsKey("Circle"))
        assertTrue(shapeVariantDef.constructors.containsKey("Rectangle"))
        assertTrue(shapeVariantDef.constructors.containsKey("Triangle"))

        // Verify calculateArea function type
        val calculateAreaType = getVariableType(result,"calculateArea")
        assertNotNull(calculateAreaType)
        assertTrue(calculateAreaType is MezzeType.FunctionType)
        val funcType = calculateAreaType as MezzeType.FunctionType
        assertTrue(funcType.paramType is MezzeType.RecordType)
        assertTrue((funcType.paramType as MezzeType.RecordType).fields.containsKey("shape"))
        assertTrue(funcType.returnType is MezzeType.IntType)

        // Verify that all area calculations result in Int
        assertEquals(MezzeType.IntType, getVariableType(result,"circleArea"))
        assertEquals(MezzeType.IntType, getVariableType(result,"rectangleArea"))
        assertEquals(MezzeType.IntType, getVariableType(result,"triangleArea"))
        
        // The individual shape instances may have more specific variant types (just the constructor they use)
        // but they should still be variant types that are assignable to Shape
        val circleType = getVariableType(result,"circle")
        val rectangleType = getVariableType(result,"rectangle") 
        val triangleType = getVariableType(result,"triangle")
        
        assertTrue(circleType is MezzeType.VariantType)
        assertTrue(rectangleType is MezzeType.VariantType)
        assertTrue(triangleType is MezzeType.VariantType)
    }

    @Test
    fun `test pattern variable inference in simple match`() {
        val code = """
            type Color = | Red { value: Int } |
            
            let test = {color} -> match color is
              Red { value } -> value + 10
              
            let result = test { color = Red { value = 5 } }
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        
        val testType = getVariableType(result,"test")
        assertNotNull(testType)
        assertTrue(testType is MezzeType.FunctionType)
        
        val resultType = getVariableType(result,"result")
        assertEquals(MezzeType.IntType, resultType)
    }

    @Test
    fun `test multiple pattern variable inference`() {
        val code = """
            type Point = | Point2D { x: Int, y: Int } |
            
            let distance = {point} -> match point is
              Point2D { x, y } -> x * x + y * y
              
            let result = distance { point = Point2D { x = 3, y = 4 } }
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        
        val distanceType = getVariableType(result,"distance")
        assertNotNull(distanceType)
        assertTrue(distanceType is MezzeType.FunctionType)
        
        val resultType = getVariableType(result,"result")
        assertEquals(MezzeType.IntType, resultType)
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

        val result = parseAndTypeCheck(code)
        
        val extractType = getVariableType(result,"extract")
        assertNotNull(extractType)
        assertTrue(extractType is MezzeType.FunctionType)
        
        val resultType = getVariableType(result,"result")
        assertEquals(MezzeType.IntType, resultType)
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

        val result = parseAndTypeCheck(code)
        
        val classifyType = getVariableType(result,"classify")
        assertNotNull(classifyType)
        assertTrue(classifyType is MezzeType.FunctionType)
        
        val resultType = getVariableType(result,"result")
        assertEquals(MezzeType.StringType, resultType)
    }

    @Test
    fun `test multiple patterns with same variable names`() {
        val code = """
            use std::int

            type Temperature = | Hot { degrees: Int }, Cold { degrees: Int } |

            let describe = {temp} -> match temp is
              Hot { degrees } | Cold { degrees } -> "Temperature is $(int::to_str { n = degrees })"

            let result = describe { temp = Hot { degrees = 80 } }
        """.trimIndent()

        val result = parseAndTypeCheck(code)
        
        val describeType = getVariableType(result,"describe")
        assertNotNull(describeType)
        assertTrue(describeType is MezzeType.FunctionType)
        
        val resultType = getVariableType(result,"result")
        assertEquals(MezzeType.StringType, resultType)
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

        val result = parseAndTypeCheck(code)
        
        val evaluateType = getVariableType(result,"evaluate")
        assertNotNull(evaluateType)
        assertTrue(evaluateType is MezzeType.FunctionType)
        
        val resultType = getVariableType(result,"result")
        assertEquals(MezzeType.IntType, resultType)
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

        val result = parseAndTypeCheck(code)
        
        val isValidType = getVariableType(result,"isValid")
        assertNotNull(isValidType)
        assertTrue(isValidType is MezzeType.FunctionType)
        
        val resultType = getVariableType(result,"result")
        // Bool can be BoolType or VariantType with True/False constructors
        assertTrue(resultType is MezzeType.BoolType ||
                   (resultType is MezzeType.VariantType && resultType.constructors.keys == setOf("True", "False")))
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

        val result = parseAndTypeCheck(code)
        
        val processType = getVariableType(result,"process")
        assertNotNull(processType)
        assertTrue(processType is MezzeType.FunctionType)
        
        val resultType = getVariableType(result,"result")
        assertEquals(MezzeType.IntType, resultType)
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

        val result = parseAndTypeCheck(code)
        
        val getCodeType = getVariableType(result,"getCode")
        assertNotNull(getCodeType)
        assertTrue(getCodeType is MezzeType.FunctionType)
        
        val resultType = getVariableType(result,"result")
        assertEquals(MezzeType.IntType, resultType)
    }
}
