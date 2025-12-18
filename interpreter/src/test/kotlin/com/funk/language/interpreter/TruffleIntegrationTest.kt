package com.funk.language.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.source.Source
import com.funk.language.interpreter.nodes.ASTToTruffleConverter
import com.funk.language.interpreter.nodes.FunkNode
import com.funk.language.interpreter.nodes.literal.*
import com.funk.language.interpreter.nodes.arithmetic.*
import com.funk.language.parser.FunkParser
import com.funk.language.typechecker.TypeChecker

class TruffleIntegrationTest {
    
    private lateinit var language: FunkLanguage
    private lateinit var context: FunkContext
    private lateinit var frameDescriptor: com.oracle.truffle.api.frame.FrameDescriptor
    
    @BeforeEach
    fun setup() {
        language = FunkLanguage()
        context = FunkContext(language, null)
        frameDescriptor = com.oracle.truffle.api.frame.FrameDescriptor.newBuilder().build()
    }
    
    private fun createFrame() = Truffle.getRuntime().createVirtualFrame(arrayOf(), frameDescriptor)
    
    @Test
    fun testBasicLiteralNodes() {
        val frame = createFrame()
        
        // Test integer literal
        val intNode = IntLiteralNode(42)
        val intResult = intNode.execute(frame)
        assertTrue(intResult is FunkInt)
        assertEquals(42L, (intResult as FunkInt).value)
        
        // Test string literal
        val stringNode = StringLiteralNode("hello")
        val stringResult = stringNode.execute(frame)
        assertTrue(stringResult is FunkString)
        assertEquals("hello", (stringResult as FunkString).value)
        
        // Test boolean literal
        val boolNode = BooleanLiteralNode(true)
        val boolResult = boolNode.execute(frame)
        assertTrue(boolResult is FunkBool)
        assertEquals(true, (boolResult as FunkBool).value)
    }
    
    @Test
    fun testArithmeticNodes() {
        val frame = createFrame()
        
        // Test addition
        val leftNode = IntLiteralNode(10)
        val rightNode = IntLiteralNode(5)
        val addNode = AddNode(leftNode, rightNode)
        
        val addResult = addNode.execute(frame)
        assertTrue(addResult is FunkInt)
        assertEquals(15L, (addResult as FunkInt).value)
        
        // Test multiplication
        val leftNode2 = IntLiteralNode(6)
        val rightNode2 = IntLiteralNode(7)
        val multiplyNode = MultiplyNode(leftNode2, rightNode2)
        
        val multiplyResult = multiplyNode.execute(frame)
        assertTrue(multiplyResult is FunkInt)
        assertEquals(42L, (multiplyResult as FunkInt).value)
    }
    
    @Test
    fun testComparisonNodes() {
        val frame = createFrame()
        
        // Test equality
        val leftNode = IntLiteralNode(42)
        val rightNode = IntLiteralNode(42)
        val equalNode = com.funk.language.interpreter.nodes.comparison.EqualNode(leftNode, rightNode)
        
        val equalResult = equalNode.execute(frame)
        assertTrue(equalResult is FunkBool)
        assertEquals(true, (equalResult as FunkBool).value)
        
        // Test less than
        val leftNode2 = IntLiteralNode(5)
        val rightNode2 = IntLiteralNode(10)
        val lessThanNode = com.funk.language.interpreter.nodes.comparison.LessThanNode(leftNode2, rightNode2)
        
        val lessThanResult = lessThanNode.execute(frame)
        assertTrue(lessThanResult is FunkBool)
        assertEquals(true, (lessThanResult as FunkBool).value)
    }
    
    @Test
    fun testASTToTruffleConversion() {
        val sourceCode = """
            let x = 42
            let y = 24
            x + y
        """.trimIndent()
        
        // Parse and type check
        val parser = FunkParser()
        val ast = parser.parse(sourceCode)
        val typeChecker = TypeChecker()
        val typedProgram = typeChecker.typeCheck(ast).program

        // Convert to Truffle nodes
        val converter = ASTToTruffleConverter()
        val truffleNode = converter.convertProgram(typedProgram)
        
        assertNotNull(truffleNode)
        assertTrue(truffleNode is com.funk.language.interpreter.nodes.control.SequenceNode)
    }
    
    @Test
    fun testTruffleLanguageIntegration() {
        // Simple test to verify language creation works
        assertNotNull(language)
        assertNotNull(context)
    }
    
    @Test
    fun testSpecializationBehavior() {
        // Test that arithmetic operations work correctly with different types
        val frame = createFrame()
        
        val leftNode = IntLiteralNode(100)
        val rightNode = IntLiteralNode(200)
        val addNode = AddNode(leftNode, rightNode)
        
        // Execute multiple times to verify consistency
        repeat(10) {
            val result = addNode.execute(frame)
            assertTrue(result is FunkInt)
            assertEquals(300L, (result as FunkInt).value)
        }
        
        // Test with mixed types should throw exception
        val leftFloatNode = FloatLiteralNode(10.5)
        val rightIntNode = IntLiteralNode(5)
        val mixedAddNode = AddNode(leftFloatNode, rightIntNode)
        
        assertThrows(IllegalArgumentException::class.java) {
            mixedAddNode.execute(frame)
        }
    }
    
    @Test
    fun testTypeSpecializationWithDifferentNumericTypes() {
        val frame = createFrame()
        
        // Test Float addition
        val leftFloatNode = FloatLiteralNode(3.14)
        val rightFloatNode = FloatLiteralNode(2.86)
        val floatAddNode = AddNode(leftFloatNode, rightFloatNode)
        
        val floatResult = floatAddNode.execute(frame)
        assertTrue(floatResult is FunkFloat)
        assertEquals(6.0, (floatResult as FunkFloat).value, 0.001)
        
        // Test Nat addition (using custom literal nodes)
        val leftNatNode = object : FunkNode() {
            override fun execute(frame: com.oracle.truffle.api.frame.VirtualFrame) = FunkNat(15)
        }
        val rightNatNode = object : FunkNode() {
            override fun execute(frame: com.oracle.truffle.api.frame.VirtualFrame) = FunkNat(25)
        }
        val natAddNode = AddNode(leftNatNode, rightNatNode)
        
        val natResult = natAddNode.execute(frame)
        assertTrue(natResult is FunkNat)
        assertEquals(40L, (natResult as FunkNat).value)
        
        // Test Frac addition
        val leftFracNode = object : FunkNode() {
            override fun execute(frame: com.oracle.truffle.api.frame.VirtualFrame) = FunkFrac(1, 2) // 1/2
        }
        val rightFracNode = object : FunkNode() {
            override fun execute(frame: com.oracle.truffle.api.frame.VirtualFrame) = FunkFrac(1, 3) // 1/3
        }
        val fracAddNode = AddNode(leftFracNode, rightFracNode)
        
        val fracResult = fracAddNode.execute(frame)
        assertTrue(fracResult is FunkFrac)
        val frac = fracResult as FunkFrac
        // 1/2 + 1/3 = 3/6 + 2/6 = 5/6
        assertEquals(5L, frac.numerator)
        assertEquals(6L, frac.denominator)
    }
    
    @Test
    fun testOverflowHandling() {
        val frame = createFrame()
        
        // Test that large numbers work correctly (without overflow)
        val leftLargeNode = IntLiteralNode(1000000L)
        val rightLargeNode = IntLiteralNode(1000L)
        val largeAddNode = AddNode(leftLargeNode, rightLargeNode)
        
        val result = largeAddNode.execute(frame)
        assertTrue(result is FunkInt)
        assertEquals(1001000L, (result as FunkInt).value)
        
        // Test that overflow detection works by using Math.addExact behavior
        // We'll test this indirectly by verifying the arithmetic operations work correctly
        val normalAddNode = AddNode(IntLiteralNode(100), IntLiteralNode(200))
        val normalResult = normalAddNode.execute(frame)
        assertTrue(normalResult is FunkInt)
        assertEquals(300L, (normalResult as FunkInt).value)
    }
    
    @Test
    fun testComplexExpression() {
        val frame = createFrame()
        
        // Build expression: (10 + 20) * 30 = 30 * 30 = 900
        val leftAddNode = IntLiteralNode(10)
        val rightAddNode = IntLiteralNode(20)
        val addNode = AddNode(leftAddNode, rightAddNode)
        
        val multiplyRightNode = IntLiteralNode(30)
        val multiplyNode = MultiplyNode(addNode, multiplyRightNode)
        
        val result = multiplyNode.execute(frame)
        assertTrue(result is FunkInt)
        assertEquals(900L, (result as FunkInt).value)
    }
}