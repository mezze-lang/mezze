package com.funk.language.interpreter

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import com.oracle.truffle.api.frame.FrameDescriptor
import com.oracle.truffle.api.frame.VirtualFrame
import com.funk.language.interpreter.nodes.*

/**
 * Simple tests for individual Truffle nodes without the full language runtime.
 * This helps us verify our basic node implementations work correctly.
 */
class SimpleNodeTest {
    
    @Test
    fun testIntLiteralNode() {
        val node = FunkIntLiteralNode(42L)
        val frame = createDummyFrame()
        
        val result = node.executeGeneric(frame)
        assertTrue(result is FunkInt)
        assertEquals(42L, (result as FunkInt).value)
    }
    
    @Test
    fun testStringLiteralNode() {
        val node = FunkStringLiteralNode("hello")
        val frame = createDummyFrame()
        
        val result = node.executeGeneric(frame)
        assertTrue(result is FunkString)
        assertEquals("hello", (result as FunkString).value)
    }
    
    @Test
    fun testBooleanLiteralNode() {
        val node = FunkBooleanLiteralNode(true)
        val frame = createDummyFrame()
        
        val result = node.executeGeneric(frame)
        assertTrue(result is FunkBoolean)
        assertTrue((result as FunkBoolean).value)
    }
    
    @Test
    fun testRecordLiteralNode() {
        val fields = mapOf(
            "name" to FunkStringLiteralNode("John"),
            "age" to FunkIntLiteralNode(30L)
        )
        val node = FunkRecordLiteralNode(fields)
        val frame = createDummyFrame()
        
        val result = node.executeGeneric(frame)
        assertTrue(result is FunkRecord)
        
        val record = result as FunkRecord
        assertEquals("John", (record.fields["name"] as FunkString).value)
        assertEquals(30L, (record.fields["age"] as FunkInt).value)
    }
    
    @Test
    fun testBinaryOperationNode() {
        val leftNode = FunkIntLiteralNode(5L)
        val rightNode = FunkIntLiteralNode(3L)
        val addNode = FunkBinaryOperationNode(leftNode, "+", rightNode)
        val frame = createDummyFrame()
        
        val result = addNode.executeGeneric(frame)
        assertTrue(result is FunkInt)
        assertEquals(8L, (result as FunkInt).value)
    }
    
    @Test
    fun testIfNode() {
        val conditionNode = FunkBooleanLiteralNode(true)
        val thenNode = FunkStringLiteralNode("yes")
        val elseNode = FunkStringLiteralNode("no")
        val ifNode = FunkIfNode(conditionNode, thenNode, elseNode)
        val frame = createDummyFrame()
        
        val result = ifNode.executeGeneric(frame)
        assertTrue(result is FunkString)
        assertEquals("yes", (result as FunkString).value)
    }
    
    private fun createDummyFrame(): VirtualFrame {
        // Create a minimal frame for testing
        val frameDescriptor = FrameDescriptor.newBuilder().build()
        // We'll need to create a proper frame, but for now this is a placeholder
        // In a real implementation, we'd use the Truffle framework to create frames
        throw UnsupportedOperationException("Frame creation not implemented yet - this test will be updated when we have proper frame support")
    }
}
