package com.mezze.language.interpreter.nodes.pattern

import com.oracle.truffle.api.dsl.Cached
import com.oracle.truffle.api.dsl.Specialization
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.NodeInfo
import com.oracle.truffle.api.profiles.BranchProfile
import com.mezze.language.interpreter.nodes.MezzeNode
import com.mezze.language.interpreter.MezzeValue
import com.mezze.language.interpreter.MezzeInt
import com.mezze.language.interpreter.MezzeString
import com.mezze.language.interpreter.MezzeBool
import com.mezze.language.interpreter.MezzeVariant
import com.mezze.language.interpreter.MezzeRecord
import com.mezze.language.interpreter.MezzeNull
import com.mezze.language.interpreter.MezzeArrayList
import com.mezze.language.interpreter.MezzeList
import com.mezze.language.interpreter.MezzeCons
import com.mezze.language.interpreter.MezzeNil

/**
 * Pattern matching node with manual type-specific optimizations
 */
@NodeInfo(shortName = "pattern-match")
class PatternMatchNode(
    @field:Child private var valueNode: MezzeNode,
    @field:Children private val caseNodes: Array<PatternCaseNode>
) : MezzeNode() {
    
    override fun execute(frame: VirtualFrame): MezzeValue {
        val value = valueNode.execute(frame)
        
        // Try each case in order
        for (caseNode in caseNodes) {
            val result = when (value) {
                is MezzeInt -> caseNode.tryMatchInt(frame, value)
                is MezzeString -> caseNode.tryMatchString(frame, value)
                is MezzeBool -> caseNode.tryMatchBoolean(frame, value)
                is MezzeVariant -> caseNode.tryMatchVariant(frame, value)
                is MezzeRecord -> caseNode.tryMatchRecord(frame, value)
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
    @field:Child private var guardNode: MezzeNode?,
    @field:Child private var bodyNode: MezzeNode
) : MezzeNode() {
    
    override fun execute(frame: VirtualFrame): MezzeValue {
        throw UnsupportedOperationException("PatternCaseNode should not be executed directly")
    }
    
    /**
     * Try to match an integer value
     */
    fun tryMatchInt(frame: VirtualFrame, value: MezzeInt): MezzeValue? {
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
    fun tryMatchString(frame: VirtualFrame, value: MezzeString): MezzeValue? {
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
    fun tryMatchBoolean(frame: VirtualFrame, value: MezzeBool): MezzeValue? {
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
    fun tryMatchVariant(frame: VirtualFrame, value: MezzeVariant): MezzeValue? {
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
    fun tryMatchRecord(frame: VirtualFrame, value: MezzeRecord): MezzeValue? {
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
    fun tryMatchGeneric(frame: VirtualFrame, value: MezzeValue): MezzeValue? {
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
    private fun executeWithGuard(frame: VirtualFrame, value: MezzeValue): MezzeValue? {
        // Check guard condition if present
        if (guardNode != null) {
            val guardResult = guardNode!!.execute(frame)
            val guardPassed = when (guardResult) {
                is MezzeBool -> guardResult.value
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
abstract class PatternNode : MezzeNode() {
    
    override fun execute(frame: VirtualFrame): MezzeValue {
        throw UnsupportedOperationException("PatternNode should not be executed directly")
    }
    
    abstract fun matchesInt(frame: VirtualFrame, value: MezzeInt): Boolean
    abstract fun matchesString(frame: VirtualFrame, value: MezzeString): Boolean
    abstract fun matchesBoolean(frame: VirtualFrame, value: MezzeBool): Boolean
    abstract fun matchesVariant(frame: VirtualFrame, value: MezzeVariant): Boolean
    abstract fun matchesRecord(frame: VirtualFrame, value: MezzeRecord): Boolean
    abstract fun matchesGeneric(frame: VirtualFrame, value: MezzeValue): Boolean
}

/**
 * Literal pattern node (matches exact values)
 */
@NodeInfo(shortName = "literal-pattern")
class LiteralPatternNode(private val expectedValue: MezzeValue) : PatternNode() {
    
    override fun matchesInt(frame: VirtualFrame, value: MezzeInt): Boolean {
        return expectedValue is MezzeInt && expectedValue.value == value.value
    }
    
    override fun matchesString(frame: VirtualFrame, value: MezzeString): Boolean {
        return expectedValue is MezzeString && expectedValue.value == value.value
    }
    
    override fun matchesBoolean(frame: VirtualFrame, value: MezzeBool): Boolean {
        return expectedValue is MezzeBool && expectedValue.value == value.value
    }
    
    override fun matchesVariant(frame: VirtualFrame, value: MezzeVariant): Boolean {
        return expectedValue is MezzeVariant && 
               expectedValue.constructor == value.constructor &&
               expectedValue.value == value.value
    }
    
    override fun matchesRecord(frame: VirtualFrame, value: MezzeRecord): Boolean {
        return expectedValue is MezzeRecord && expectedValue.fields == value.fields
    }
    
    override fun matchesGeneric(frame: VirtualFrame, value: MezzeValue): Boolean {
        return expectedValue == value
    }
}

/**
 * Variable pattern node (matches anything and binds to variable)
 */
@NodeInfo(shortName = "variable-pattern")
class VariablePatternNode(
    private val variableName: String,
    private val variableSlot: Int
) : PatternNode() {

    override fun matchesInt(frame: VirtualFrame, value: MezzeInt): Boolean {
        bindVariable(frame, value)
        return true
    }

    override fun matchesString(frame: VirtualFrame, value: MezzeString): Boolean {
        bindVariable(frame, value)
        return true
    }

    override fun matchesBoolean(frame: VirtualFrame, value: MezzeBool): Boolean {
        bindVariable(frame, value)
        return true
    }

    override fun matchesVariant(frame: VirtualFrame, value: MezzeVariant): Boolean {
        bindVariable(frame, value)
        return true
    }

    override fun matchesRecord(frame: VirtualFrame, value: MezzeRecord): Boolean {
        bindVariable(frame, value)
        return true
    }

    override fun matchesGeneric(frame: VirtualFrame, value: MezzeValue): Boolean {
        bindVariable(frame, value)
        return true
    }

    private fun bindVariable(frame: VirtualFrame, value: MezzeValue) {
        frame.setObject(variableSlot, value)
    }
}

/**
 * Wildcard pattern node (matches anything without binding)
 */
@NodeInfo(shortName = "wildcard-pattern")
class WildcardPatternNode : PatternNode() {

    override fun matchesInt(frame: VirtualFrame, value: MezzeInt): Boolean = true
    override fun matchesString(frame: VirtualFrame, value: MezzeString): Boolean = true
    override fun matchesBoolean(frame: VirtualFrame, value: MezzeBool): Boolean = true
    override fun matchesVariant(frame: VirtualFrame, value: MezzeVariant): Boolean = true
    override fun matchesRecord(frame: VirtualFrame, value: MezzeRecord): Boolean = true
    override fun matchesGeneric(frame: VirtualFrame, value: MezzeValue): Boolean = true
}

/**
 * Constructor pattern node (matches variant constructors with optional nested patterns)
 * Examples:
 * - Some { val } -> matches Some variant, extracts `val` field
 * - None -> matches None variant (no fields)
 * - Ok { value } -> matches Ok variant, extracts `value` field
 */
@NodeInfo(shortName = "constructor-pattern")
class ConstructorPatternNode(
    private val constructorName: String,
    private val recordPattern: com.mezze.language.ast.RecordPattern?,
    private val variableSlots: Map<String, Int>  // Pre-allocated slots for pattern variables
) : PatternNode() {

    override fun matchesInt(frame: VirtualFrame, value: MezzeInt): Boolean = false
    override fun matchesString(frame: VirtualFrame, value: MezzeString): Boolean = false

    /**
     * Match True/False constructor patterns against MezzeBool values.
     * This enables pattern matching like: match flag is True -> ... | False -> ...
     */
    override fun matchesBoolean(frame: VirtualFrame, value: MezzeBool): Boolean {
        return when (constructorName) {
            "True" -> value.value == true
            "False" -> value.value == false
            else -> false
        }
    }

    override fun matchesRecord(frame: VirtualFrame, value: MezzeRecord): Boolean = false
    override fun matchesGeneric(frame: VirtualFrame, value: MezzeValue): Boolean {
        return when (value) {
            is MezzeVariant -> matchesVariant(frame, value)
            // Handle specialized variants (Bool -> MezzeBool, List -> MezzeCons/MezzeNil)
            is MezzeBool -> matchesBoolean(frame, value)
            is MezzeCons -> matchesCons(frame, value)
            is MezzeNil -> matchesNil(frame)
            else -> false
        }
    }

    /**
     * Match against MezzeNil (specialized List Nil constructor)
     */
    private fun matchesNil(frame: VirtualFrame): Boolean {
        // Nil pattern must match Nil constructor with no record pattern
        return constructorName == "Nil" && recordPattern == null
    }

    /**
     * Match against MezzeCons (specialized List Cons constructor)
     * Pattern: Cons { head, tail } or Cons { head = x, tail = xs }
     */
    private fun matchesCons(frame: VirtualFrame, value: MezzeCons): Boolean {
        // Must be matching against Cons constructor
        if (constructorName != "Cons") {
            return false
        }

        // Must have a record pattern to extract head/tail
        if (recordPattern == null) {
            return true  // Just matching constructor without binding
        }

        // Extract bindings from the pattern
        val bindings = collectRecordPatternBindings(recordPattern)
        for ((fieldPath, varName) in bindings) {
            // For Cons, we only support direct field access (head, tail)
            if (fieldPath.size != 1) {
                return false  // Nested patterns not supported for Cons
            }

            val fieldName = fieldPath[0]
            val fieldValue: MezzeValue = when (fieldName) {
                "head" -> value.head
                "tail" -> value.tail
                else -> return false  // Unknown field
            }

            // Bind the value to the variable slot
            val slot = variableSlots[varName] ?: return false
            frame.setObject(slot, fieldValue)
        }

        return true
    }

    override fun matchesVariant(frame: VirtualFrame, value: MezzeVariant): Boolean {
        // Check if constructor name matches
        if (value.constructor != constructorName) {
            return false
        }

        // If there's no record pattern, just check constructor name
        if (recordPattern == null) {
            return true
        }

        // If there's a record pattern, extract and bind fields from the variant's value
        val variantValue = value.value
        if (variantValue !is MezzeRecord) {
            // Pattern expects a record but variant has no record value
            return false
        }

        // Extract bindings from the pattern and bind them to frame slots
        val bindings = collectRecordPatternBindings(recordPattern)
        for ((fieldPath, varName) in bindings) {
            var currentValue: MezzeValue = variantValue

            // Navigate the field path (for nested patterns)
            for (fieldName in fieldPath) {
                if (currentValue !is MezzeRecord) {
                    return false  // Pattern doesn't match structure
                }
                currentValue = currentValue.fields[fieldName] ?: return false
            }

            // Bind the value to the variable slot
            val slot = variableSlots[varName] ?: return false
            frame.setObject(slot, currentValue)
        }

        return true
    }

    /**
     * Helper to collect bindings from a record pattern
     * (Duplicates logic from ASTToTruffleConverter for pattern matching)
     */
    private fun collectRecordPatternBindings(
        pattern: com.mezze.language.ast.RecordPattern,
        prefix: List<String> = emptyList()
    ): List<Pair<List<String>, String>> {
        val bindings = mutableListOf<Pair<List<String>, String>>()

        for (entry in pattern.entries) {
            val fieldPath = prefix + entry.name

            when (val entryPattern = entry.pattern) {
                null -> {
                    // Shorthand: {val} means {val = val}
                    bindings.add(fieldPath to entry.name)
                }
                is com.mezze.language.ast.VariablePattern -> {
                    // Renamed: {val = x}
                    bindings.add(fieldPath to entryPattern.name)
                }
                is com.mezze.language.ast.RecordPattern -> {
                    // Nested: {inner = {value}}
                    bindings.addAll(collectRecordPatternBindings(entryPattern, fieldPath))
                }
                is com.mezze.language.ast.WildcardPattern -> {
                    // {val = _} - ignore this field
                }
                else -> {
                    // Unsupported pattern - fail to match
                    return emptyList()
                }
            }
        }

        return bindings
    }
}

/**
 * Array pattern node (matches arrays/lists with specific structure)
 * Examples:
 * - [] -> matches empty array
 * - [x] -> matches single element array, binds x
 * - [x, y] -> matches two element array, binds x and y
 * - [head, ...tail] -> matches array with at least one element, binds head and tail
 */
@NodeInfo(shortName = "array-pattern")
class ArrayPatternNode(
    @field:Children private val elementPatterns: Array<PatternNode>,
    private val restPatternName: String?,
    private val elementSlots: Map<String, Int>,  // Pre-allocated slots for element bindings
    private val restSlot: Int?  // Pre-allocated slot for rest pattern
) : PatternNode() {

    override fun matchesInt(frame: VirtualFrame, value: MezzeInt): Boolean = false
    override fun matchesString(frame: VirtualFrame, value: MezzeString): Boolean = false
    override fun matchesBoolean(frame: VirtualFrame, value: MezzeBool): Boolean = false
    override fun matchesVariant(frame: VirtualFrame, value: MezzeVariant): Boolean = false
    override fun matchesRecord(frame: VirtualFrame, value: MezzeRecord): Boolean = false

    override fun matchesGeneric(frame: VirtualFrame, value: MezzeValue): Boolean {
        // Check if value is an array or list
        val arrayElements = when (value) {
            is MezzeArrayList -> value.toList()
            is MezzeList -> value.toKotlinList()
            else -> return false
        }

        // Check if array length is compatible with pattern
        val minLength = elementPatterns.size
        if (restPatternName == null) {
            // No rest pattern - array must have exactly the right number of elements
            if (arrayElements.size != minLength) {
                return false
            }
        } else {
            // Rest pattern present - array must have at least the required elements
            if (arrayElements.size < minLength) {
                return false
            }
        }

        // Match each element pattern
        for (i in elementPatterns.indices) {
            val elementValue = arrayElements[i]
            val elementPattern = elementPatterns[i]

            // Try to match the element
            val matched = when (elementValue) {
                is MezzeInt -> elementPattern.matchesInt(frame, elementValue)
                is MezzeString -> elementPattern.matchesString(frame, elementValue)
                is MezzeBool -> elementPattern.matchesBoolean(frame, elementValue)
                is MezzeVariant -> elementPattern.matchesVariant(frame, elementValue)
                is MezzeRecord -> elementPattern.matchesRecord(frame, elementValue)
                else -> elementPattern.matchesGeneric(frame, elementValue)
            }

            if (!matched) {
                return false
            }
        }

        // Bind rest pattern if present (anonymous rest patterns have null name)
        if (restPatternName != null && restSlot != null) {
            val restElements = arrayElements.drop(elementPatterns.size)
            // Create the same type of collection as the input
            val restValue = when (value) {
                is MezzeArrayList -> MezzeArrayList(restElements)
                is MezzeList -> MezzeList.fromKotlinList(restElements)
                else -> MezzeList.fromKotlinList(restElements)  // Fallback
            }
            frame.setObject(restSlot, restValue)
        }
        // For anonymous rest patterns ([x, ...]), we still check length compatibility
        // but don't bind the rest elements

        return true
    }
}