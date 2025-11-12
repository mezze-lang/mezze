package com.funk.language.interpreter.nodes.pattern

import com.oracle.truffle.api.dsl.Cached
import com.oracle.truffle.api.dsl.Specialization
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.NodeInfo
import com.oracle.truffle.api.profiles.BranchProfile
import com.funk.language.interpreter.nodes.FunkNode
import com.funk.language.interpreter.FunkValue
import com.funk.language.interpreter.FunkInt
import com.funk.language.interpreter.FunkString
import com.funk.language.interpreter.FunkBool
import com.funk.language.interpreter.FunkVariant
import com.funk.language.interpreter.FunkRecord
import com.funk.language.interpreter.FunkNull

/**
 * Pattern matching node with manual type-specific optimizations
 */
@NodeInfo(shortName = "pattern-match")
class PatternMatchNode(
    @field:Child private var valueNode: FunkNode,
    @field:Children private val caseNodes: Array<PatternCaseNode>
) : FunkNode() {
    
    override fun execute(frame: VirtualFrame): FunkValue {
        val value = valueNode.execute(frame)
        
        // Try each case in order
        for (caseNode in caseNodes) {
            val result = when (value) {
                is FunkInt -> caseNode.tryMatchInt(frame, value)
                is FunkString -> caseNode.tryMatchString(frame, value)
                is FunkBool -> caseNode.tryMatchBoolean(frame, value)
                is FunkVariant -> caseNode.tryMatchVariant(frame, value)
                is FunkRecord -> caseNode.tryMatchRecord(frame, value)
                else -> caseNode.tryMatchGeneric(frame, value)
            }
            
            if (result != null) return result
        }
        
        // No pattern matched
        throw RuntimeException("No matching pattern found for value: ${value.getDisplayString()}")
    }
}

/**
 * Individual pattern case node
 */
@NodeInfo(shortName = "pattern-case")
class PatternCaseNode(
    @field:Children private val patterns: Array<PatternNode>,
    @field:Child private var guardNode: FunkNode?,
    @field:Child private var bodyNode: FunkNode
) : FunkNode() {
    
    override fun execute(frame: VirtualFrame): FunkValue {
        throw UnsupportedOperationException("PatternCaseNode should not be executed directly")
    }
    
    /**
     * Try to match an integer value
     */
    fun tryMatchInt(frame: VirtualFrame, value: FunkInt): FunkValue? {
        for (pattern in patterns) {
            if (pattern.matchesInt(frame, value)) {
                return executeWithGuard(frame, value)
            }
        }
        return null
    }
    
    /**
     * Try to match a string value
     */
    fun tryMatchString(frame: VirtualFrame, value: FunkString): FunkValue? {
        for (pattern in patterns) {
            if (pattern.matchesString(frame, value)) {
                return executeWithGuard(frame, value)
            }
        }
        return null
    }
    
    /**
     * Try to match a boolean value
     */
    fun tryMatchBoolean(frame: VirtualFrame, value: FunkBool): FunkValue? {
        for (pattern in patterns) {
            if (pattern.matchesBoolean(frame, value)) {
                return executeWithGuard(frame, value)
            }
        }
        return null
    }
    
    /**
     * Try to match a variant value
     */
    fun tryMatchVariant(frame: VirtualFrame, value: FunkVariant): FunkValue? {
        for (pattern in patterns) {
            if (pattern.matchesVariant(frame, value)) {
                return executeWithGuard(frame, value)
            }
        }
        return null
    }
    
    /**
     * Try to match a record value
     */
    fun tryMatchRecord(frame: VirtualFrame, value: FunkRecord): FunkValue? {
        for (pattern in patterns) {
            if (pattern.matchesRecord(frame, value)) {
                return executeWithGuard(frame, value)
            }
        }
        return null
    }
    
    /**
     * Try to match any value (fallback)
     */
    fun tryMatchGeneric(frame: VirtualFrame, value: FunkValue): FunkValue? {
        for (pattern in patterns) {
            if (pattern.matchesGeneric(frame, value)) {
                return executeWithGuard(frame, value)
            }
        }
        return null
    }
    
    /**
     * Execute the case body with optional guard check
     */
    private fun executeWithGuard(frame: VirtualFrame, value: FunkValue): FunkValue? {
        // Check guard condition if present
        if (guardNode != null) {
            val guardResult = guardNode!!.execute(frame)
            val guardPassed = when (guardResult) {
                is FunkBool -> guardResult.value
                else -> throw RuntimeException("Guard condition must be boolean")
            }
            if (!guardPassed) {
                return null // Guard failed, try next pattern
            }
        }
        
        return bodyNode.execute(frame)
    }
}

/**
 * Base class for individual patterns
 */
abstract class PatternNode : FunkNode() {
    
    override fun execute(frame: VirtualFrame): FunkValue {
        throw UnsupportedOperationException("PatternNode should not be executed directly")
    }
    
    abstract fun matchesInt(frame: VirtualFrame, value: FunkInt): Boolean
    abstract fun matchesString(frame: VirtualFrame, value: FunkString): Boolean
    abstract fun matchesBoolean(frame: VirtualFrame, value: FunkBool): Boolean
    abstract fun matchesVariant(frame: VirtualFrame, value: FunkVariant): Boolean
    abstract fun matchesRecord(frame: VirtualFrame, value: FunkRecord): Boolean
    abstract fun matchesGeneric(frame: VirtualFrame, value: FunkValue): Boolean
}

/**
 * Literal pattern node (matches exact values)
 */
@NodeInfo(shortName = "literal-pattern")
class LiteralPatternNode(private val expectedValue: FunkValue) : PatternNode() {
    
    override fun matchesInt(frame: VirtualFrame, value: FunkInt): Boolean {
        return expectedValue is FunkInt && expectedValue.value == value.value
    }
    
    override fun matchesString(frame: VirtualFrame, value: FunkString): Boolean {
        return expectedValue is FunkString && expectedValue.value == value.value
    }
    
    override fun matchesBoolean(frame: VirtualFrame, value: FunkBool): Boolean {
        return expectedValue is FunkBool && expectedValue.value == value.value
    }
    
    override fun matchesVariant(frame: VirtualFrame, value: FunkVariant): Boolean {
        return expectedValue is FunkVariant && 
               expectedValue.constructor == value.constructor &&
               expectedValue.value == value.value
    }
    
    override fun matchesRecord(frame: VirtualFrame, value: FunkRecord): Boolean {
        return expectedValue is FunkRecord && expectedValue.fields == value.fields
    }
    
    override fun matchesGeneric(frame: VirtualFrame, value: FunkValue): Boolean {
        return expectedValue == value
    }
}

/**
 * Variable pattern node (matches anything and binds to variable)
 */
@NodeInfo(shortName = "variable-pattern")
class VariablePatternNode(private val variableName: String) : PatternNode() {
    
    override fun matchesInt(frame: VirtualFrame, value: FunkInt): Boolean {
        bindVariable(frame, value)
        return true
    }
    
    override fun matchesString(frame: VirtualFrame, value: FunkString): Boolean {
        bindVariable(frame, value)
        return true
    }
    
    override fun matchesBoolean(frame: VirtualFrame, value: FunkBool): Boolean {
        bindVariable(frame, value)
        return true
    }
    
    override fun matchesVariant(frame: VirtualFrame, value: FunkVariant): Boolean {
        bindVariable(frame, value)
        return true
    }
    
    override fun matchesRecord(frame: VirtualFrame, value: FunkRecord): Boolean {
        bindVariable(frame, value)
        return true
    }
    
    override fun matchesGeneric(frame: VirtualFrame, value: FunkValue): Boolean {
        bindVariable(frame, value)
        return true
    }
    
    private fun bindVariable(frame: VirtualFrame, value: FunkValue) {
        // For now, we'll use a simple approach - in a real implementation,
        // we'd need to track variable slots properly
        // This is a placeholder that would need proper slot management
        frame.setObject(0, value) // Simplified - would need proper slot allocation
    }
}

/**
 * Wildcard pattern node (matches anything without binding)
 */
@NodeInfo(shortName = "wildcard-pattern")
class WildcardPatternNode : PatternNode() {
    
    override fun matchesInt(frame: VirtualFrame, value: FunkInt): Boolean = true
    override fun matchesString(frame: VirtualFrame, value: FunkString): Boolean = true
    override fun matchesBoolean(frame: VirtualFrame, value: FunkBool): Boolean = true
    override fun matchesVariant(frame: VirtualFrame, value: FunkVariant): Boolean = true
    override fun matchesRecord(frame: VirtualFrame, value: FunkRecord): Boolean = true
    override fun matchesGeneric(frame: VirtualFrame, value: FunkValue): Boolean = true
}