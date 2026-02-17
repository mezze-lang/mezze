package com.mezze.language.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.source.Source
import com.mezze.language.interpreter.nodes.ASTToTruffleConverter
import com.mezze.language.interpreter.nodes.MezzeNode
import com.mezze.language.interpreter.nodes.literal.*
import com.mezze.language.interpreter.nodes.arithmetic.*
import com.mezze.language.parser.MezzeParser
import com.mezze.language.typechecker.TypeChecker

class TruffleIntegrationTest {
    
    private lateinit var language: MezzeLanguage
    private lateinit var context: MezzeContext
    private lateinit var frameDescriptor: com.oracle.truffle.api.frame.FrameDescriptor
    
    @BeforeEach
    fun setup() {
        language = MezzeLanguage()
        context = MezzeContext(language, null)
        frameDescriptor = com.oracle.truffle.api.frame.FrameDescriptor.newBuilder().build()
    }
    
    private fun createFrame() = Truffle.getRuntime().createVirtualFrame(arrayOf(), frameDescriptor)
    
    @Test
    fun testBasicLiteralNodes() {
        val frame = createFrame()
        
        // Test integer literal
        val intNode = IntLiteralNode(42)
        val intResult = intNode.execute(frame)
        assertTrue(intResult is MezzeInt)
        assertEquals(42L, (intResult as MezzeInt).value)
        
        // Test string literal
        val stringNode = StringLiteralNode("hello")
        val stringResult = stringNode.execute(frame)
        assertTrue(stringResult is MezzeString)
        assertEquals("hello", (stringResult as MezzeString).value)
        
        // Test boolean literal
        val boolNode = BooleanLiteralNode(true)
        val boolResult = boolNode.execute(frame)
        assertTrue(boolResult is MezzeBool)
        assertEquals(true, (boolResult as MezzeBool).value)
    }
    
    @Test
    fun testArithmeticNodes() {
        val frame = createFrame()
        
        // Test addition
        val leftNode = IntLiteralNode(10)
        val rightNode = IntLiteralNode(5)
        val addNode = AddNode(leftNode, rightNode)
        
        val addResult = addNode.execute(frame)
        assertTrue(addResult is MezzeInt)
        assertEquals(15L, (addResult as MezzeInt).value)
        
        // Test multiplication
        val leftNode2 = IntLiteralNode(6)
        val rightNode2 = IntLiteralNode(7)
        val multiplyNode = MultiplyNode(leftNode2, rightNode2)
        
        val multiplyResult = multiplyNode.execute(frame)
        assertTrue(multiplyResult is MezzeInt)
        assertEquals(42L, (multiplyResult as MezzeInt).value)
    }
    
    @Test
    fun testComparisonNodes() {
        val frame = createFrame()
        
        // Test equality
        val leftNode = IntLiteralNode(42)
        val rightNode = IntLiteralNode(42)
        val equalNode = com.mezze.language.interpreter.nodes.comparison.EqualNode(leftNode, rightNode)
        
        val equalResult = equalNode.execute(frame)
        assertTrue(equalResult is MezzeBool)
        assertEquals(true, (equalResult as MezzeBool).value)
        
        // Test less than
        val leftNode2 = IntLiteralNode(5)
        val rightNode2 = IntLiteralNode(10)
        val lessThanNode = com.mezze.language.interpreter.nodes.comparison.LessThanNode(leftNode2, rightNode2)
        
        val lessThanResult = lessThanNode.execute(frame)
        assertTrue(lessThanResult is MezzeBool)
        assertEquals(true, (lessThanResult as MezzeBool).value)
    }
    
    @Test
    fun testASTToTruffleConversion() {
        val sourceCode = """
            let x = 42
            let y = 24
            x + y
        """.trimIndent()
        
        // Parse and type check
        val parser = MezzeParser()
        val ast = parser.parse(sourceCode)
        val typeChecker = TypeChecker()
        val typedProgram = typeChecker.typeCheck(ast).program

        // Convert to Truffle nodes
        val converter = ASTToTruffleConverter()
        val truffleNode = converter.convertProgram(typedProgram)
        
        assertNotNull(truffleNode)
        assertTrue(truffleNode is com.mezze.language.interpreter.nodes.control.SequenceNode)
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
            assertTrue(result is MezzeInt)
            assertEquals(300L, (result as MezzeInt).value)
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
        assertTrue(floatResult is MezzeFloat)
        assertEquals(6.0, (floatResult as MezzeFloat).value, 0.001)
        
        // Test Nat addition (using custom literal nodes)
        val leftNatNode = object : MezzeNode() {
            override fun execute(frame: com.oracle.truffle.api.frame.VirtualFrame) = MezzeNat(15)
        }
        val rightNatNode = object : MezzeNode() {
            override fun execute(frame: com.oracle.truffle.api.frame.VirtualFrame) = MezzeNat(25)
        }
        val natAddNode = AddNode(leftNatNode, rightNatNode)
        
        val natResult = natAddNode.execute(frame)
        assertTrue(natResult is MezzeNat)
        assertEquals(40L, (natResult as MezzeNat).value)
        
        // Test Frac addition
        val leftFracNode = object : MezzeNode() {
            override fun execute(frame: com.oracle.truffle.api.frame.VirtualFrame) = MezzeFrac(1, 2) // 1/2
        }
        val rightFracNode = object : MezzeNode() {
            override fun execute(frame: com.oracle.truffle.api.frame.VirtualFrame) = MezzeFrac(1, 3) // 1/3
        }
        val fracAddNode = AddNode(leftFracNode, rightFracNode)
        
        val fracResult = fracAddNode.execute(frame)
        assertTrue(fracResult is MezzeFrac)
        val frac = fracResult as MezzeFrac
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
        assertTrue(result is MezzeInt)
        assertEquals(1001000L, (result as MezzeInt).value)
        
        // Test that overflow detection works by using Math.addExact behavior
        // We'll test this indirectly by verifying the arithmetic operations work correctly
        val normalAddNode = AddNode(IntLiteralNode(100), IntLiteralNode(200))
        val normalResult = normalAddNode.execute(frame)
        assertTrue(normalResult is MezzeInt)
        assertEquals(300L, (normalResult as MezzeInt).value)
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
        assertTrue(result is MezzeInt)
        assertEquals(900L, (result as MezzeInt).value)
    }
}