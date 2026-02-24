package com.mezze.language.interpreter.nodes.pattern

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
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

        // No pattern matched - throw via TruffleBoundary
        throwNoMatchingPattern(value)
    }

    @TruffleBoundary
    private fun throwNoMatchingPattern(value: MezzeValue): Nothing {
        throw RuntimeException("No matching pattern found for value: ${value.getDisplayString()}")
    }
}

/**
 * Represents an if-let binding in a match guard.
 * Evaluates an expression and tries to match it against a pattern.
 * If the pattern matches, binds variables to the frame; otherwise returns null (failure).
 */
@NodeInfo(shortName = "if-let-binding")
class IfLetBindingNode(
    @field:Child private var expressionNode: MezzeNode,
    @field:Child private var patternNode: PatternNode
) : MezzeNode() {

    override fun execute(frame: VirtualFrame): MezzeValue {
        throw UnsupportedOperationException("IfLetBindingNode should not be executed directly")
    }

    /**
     * Try to match the if-let binding.
     * @return true if the pattern matches and bindings were made, false otherwise
     */
    fun tryMatch(frame: VirtualFrame): Boolean {
        val value = expressionNode.execute(frame)
        return when (value) {
            is MezzeInt -> patternNode.matchesInt(frame, value)
            is MezzeString -> patternNode.matchesString(frame, value)
            is MezzeBool -> patternNode.matchesBoolean(frame, value)
            is MezzeVariant -> patternNode.matchesVariant(frame, value)
            is MezzeRecord -> patternNode.matchesRecord(frame, value)
            else -> patternNode.matchesGeneric(frame, value)
        }
    }
}

/**
 * Individual pattern case node
 */
@NodeInfo(shortName = "pattern-case")
class PatternCaseNode(
    @field:Children private val patterns: Array<PatternNode>,
    @field:Child private var guardNode: MezzeNode?,
    @field:Child private var bodyNode: MezzeNode,
    @field:Children private val ifLetBindings: Array<IfLetBindingNode> = emptyArray()
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
     * Execute the case body with optional guard and if-let binding checks
     */
    private fun executeWithGuard(frame: VirtualFrame, value: MezzeValue): MezzeValue? {
        // Check if-let bindings first (they may add bindings used by guard)
        var i = 0
        while (i < ifLetBindings.size) {
            if (!ifLetBindings[i].tryMatch(frame)) {
                return null // If-let binding failed, try next pattern
            }
            i++
        }

        // Check guard condition if present
        val guard = guardNode
        if (guard != null) {
            val guardResult = guard.execute(frame)
            val guardPassed = checkGuardBoolean(guardResult)
            if (!guardPassed) {
                return null // Guard failed, try next pattern
            }
        }

        return bodyNode.execute(frame)
    }

    @TruffleBoundary
    private fun checkGuardBoolean(guardResult: MezzeValue): Boolean {
        return when (guardResult) {
            is MezzeBool -> guardResult.value
            else -> throw RuntimeException("Guard condition must be boolean")
        }
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
 *
 * NOTE: matchesRecord and matchesGeneric delegate to @TruffleBoundary methods
 * because MezzeRecord.fields is a lazy property which can trigger concurrent
 * exception handling when accessed (SafePublicationLazyImpl.getValue).
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
        return compareRecords(value)
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private fun compareRecords(value: MezzeRecord): Boolean {
        return expectedValue is MezzeRecord && expectedValue.fields == value.fields
    }

    override fun matchesGeneric(frame: VirtualFrame, value: MezzeValue): Boolean {
        return compareGeneric(value)
    }

    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private fun compareGeneric(value: MezzeValue): Boolean {
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
 *
 * TRUFFLE COMPLIANCE:
 * - Pattern bindings are pre-computed at construction time (parse time)
 * - Stored as @CompilationFinal arrays for JIT-friendly access
 * - No AST iteration at runtime
 */
@NodeInfo(shortName = "constructor-pattern")
class ConstructorPatternNode(
    private val constructorName: String,
    recordPattern: com.mezze.language.ast.RecordPattern?,
    private val variableSlots: Map<String, Int>  // Pre-allocated slots for pattern variables
) : PatternNode() {

    /**
     * Pre-computed pattern bindings: array of (fieldPath, varName, slotIndex)
     * Computed once at construction time, used at runtime for O(1) access
     */
    @com.oracle.truffle.api.CompilerDirectives.CompilationFinal(dimensions = 1)
    private val bindingFieldPaths: Array<Array<String>>

    @com.oracle.truffle.api.CompilerDirectives.CompilationFinal(dimensions = 1)
    private val bindingVarNames: Array<String>

    @com.oracle.truffle.api.CompilerDirectives.CompilationFinal(dimensions = 1)
    private val bindingSlotIndices: IntArray

    @com.oracle.truffle.api.CompilerDirectives.CompilationFinal
    private val hasBindings: Boolean

    init {
        // Pre-compute bindings at construction time
        if (recordPattern != null) {
            val bindings = computeBindingsAtParseTime(recordPattern)
            bindingFieldPaths = bindings.map { it.first.toTypedArray() }.toTypedArray()
            bindingVarNames = bindings.map { it.second }.toTypedArray()
            bindingSlotIndices = bindings.map { variableSlots[it.second] ?: -1 }.toIntArray()
            hasBindings = bindings.isNotEmpty()
        } else {
            bindingFieldPaths = emptyArray()
            bindingVarNames = emptyArray()
            bindingSlotIndices = IntArray(0)
            hasBindings = false
        }
    }

    /**
     * Compute bindings from a record pattern at parse time.
     * This is only called during construction, not at runtime.
     */
    private fun computeBindingsAtParseTime(
        pattern: com.mezze.language.ast.RecordPattern,
        prefix: List<String> = emptyList()
    ): List<Pair<List<String>, String>> {
        val bindings = mutableListOf<Pair<List<String>, String>>()

        for (entry in pattern.entries) {
            val fieldPath = prefix + entry.name

            when (val entryPattern = entry.pattern) {
                null -> bindings.add(fieldPath to entry.name)
                is com.mezze.language.ast.VariablePattern -> bindings.add(fieldPath to entryPattern.name)
                is com.mezze.language.ast.RecordPattern -> bindings.addAll(computeBindingsAtParseTime(entryPattern, fieldPath))
                is com.mezze.language.ast.WildcardPattern -> { /* ignore */ }
                else -> { /* unsupported */ }
            }
        }

        return bindings
    }

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
        // Nil pattern must match Nil constructor with no bindings
        return constructorName == "Nil" && !hasBindings
    }

    /**
     * Match against MezzeCons (specialized List Cons constructor)
     * Pattern: Cons { head, tail } or Cons { head = x, tail = xs }
     * Uses pre-computed bindings for JIT-friendly access.
     */
    @com.oracle.truffle.api.nodes.ExplodeLoop
    private fun matchesCons(frame: VirtualFrame, value: MezzeCons): Boolean {
        // Must be matching against Cons constructor
        if (constructorName != "Cons") {
            return false
        }

        // If no bindings, just match constructor name
        if (!hasBindings) {
            return true
        }

        // Use pre-computed bindings (JIT-friendly loop)
        for (i in bindingFieldPaths.indices) {
            val fieldPath = bindingFieldPaths[i]
            val slotIndex = bindingSlotIndices[i]

            // For Cons, we only support direct field access (head, tail)
            if (fieldPath.size != 1) {
                return false
            }

            val fieldName = fieldPath[0]
            val fieldValue: MezzeValue = when (fieldName) {
                "head" -> value.head
                "tail" -> value.tail
                else -> return false
            }

            // Bind the value to the variable slot
            if (slotIndex < 0) return false
            frame.setObject(slotIndex, fieldValue)
        }

        return true
    }

    /**
     * Match against MezzeVariant using pre-computed bindings.
     * Uses JIT-friendly loops with @ExplodeLoop where possible.
     */
    override fun matchesVariant(frame: VirtualFrame, value: MezzeVariant): Boolean {
        // Check if constructor name matches
        if (value.constructor != constructorName) {
            return false
        }

        // If no bindings, just check constructor name
        if (!hasBindings) {
            return true
        }

        // If there are bindings, extract and bind fields from the variant's value
        val variantValue = value.value
        if (variantValue !is MezzeRecord) {
            return false
        }

        // Extract field values using boundary method (Map navigation)
        val extractedValues = extractFieldValues(variantValue)
        if (extractedValues == null) {
            return false
        }

        // Bind values to frame slots (hot path, no boundary)
        for (i in extractedValues.indices) {
            val slotIndex = bindingSlotIndices[i]
            if (slotIndex >= 0) {
                frame.setObject(slotIndex, extractedValues[i])
            }
        }

        return true
    }

    /**
     * Extract field values from variant using pre-computed bindings.
     * Marked @TruffleBoundary because it navigates record fields via Map.
     * Returns null if any field path is invalid.
     */
    @TruffleBoundary
    private fun extractFieldValues(variantValue: MezzeRecord): Array<MezzeValue>? {
        val values = Array<MezzeValue>(bindingFieldPaths.size) { MezzeNull }

        for (i in bindingFieldPaths.indices) {
            val fieldPath = bindingFieldPaths[i]
            var currentValue: MezzeValue = variantValue

            // Navigate the field path (for nested patterns)
            for (fieldName in fieldPath) {
                if (currentValue !is MezzeRecord) {
                    return null
                }
                currentValue = currentValue.fields[fieldName] ?: return null
            }

            values[i] = currentValue
        }

        return values
    }

    // NOTE: collectRecordPatternBindings was removed - all bindings are now
    // pre-computed in init {} block using computeBindingsAtParseTime()
}

/**
 * Array pattern node (matches arrays/lists with specific structure)
 * Examples:
 * - [] -> matches empty array
 * - [x] -> matches single element array, binds x
 * - [x, y] -> matches two element array, binds x and y
 * - [head, ...tail] -> matches array with at least one element, binds head and tail
 *
 * NOTE: Collection operations (toList, drop) are moved to @TruffleBoundary methods
 * to avoid JIT blockers from Kotlin's exception handling code paths.
 */
@NodeInfo(shortName = "array-pattern")
class ArrayPatternNode(
    @field:Children private val elementPatterns: Array<PatternNode>,
    private val restPatternName: String?,
    private val elementSlots: Map<String, Int>,  // Pre-allocated slots for element bindings
    private val restSlot: Int?  // Pre-allocated slot for rest pattern
) : PatternNode() {

    // Pre-compute the number of element patterns for bounds checking
    @com.oracle.truffle.api.CompilerDirectives.CompilationFinal
    private val numElementPatterns: Int = elementPatterns.size

    @com.oracle.truffle.api.CompilerDirectives.CompilationFinal
    private val hasRestPattern: Boolean = restPatternName != null

    override fun matchesInt(frame: VirtualFrame, value: MezzeInt): Boolean = false
    override fun matchesString(frame: VirtualFrame, value: MezzeString): Boolean = false
    override fun matchesBoolean(frame: VirtualFrame, value: MezzeBool): Boolean = false
    override fun matchesVariant(frame: VirtualFrame, value: MezzeVariant): Boolean = false
    override fun matchesRecord(frame: VirtualFrame, value: MezzeRecord): Boolean = false

    override fun matchesGeneric(frame: VirtualFrame, value: MezzeValue): Boolean {
        // Extract array elements via boundary method
        val arrayElements = extractArrayElements(value) ?: return false
        val arraySize = arrayElements.size

        // Check if array length is compatible with pattern
        if (!hasRestPattern) {
            // No rest pattern - array must have exactly the right number of elements
            if (arraySize != numElementPatterns) {
                return false
            }
        } else {
            // Rest pattern present - array must have at least the required elements
            if (arraySize < numElementPatterns) {
                return false
            }
        }

        // Match each element pattern
        var i = 0
        while (i < numElementPatterns) {
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
            i++
        }

        // Bind rest pattern if present (anonymous rest patterns have null name)
        if (restPatternName != null && restSlot != null) {
            val restValue = extractRestElements(value, arrayElements)
            frame.setObject(restSlot, restValue)
        }
        // For anonymous rest patterns ([x, ...]), we still check length compatibility
        // but don't bind the rest elements

        return true
    }

    /**
     * Extract array elements from the value.
     * Marked @TruffleBoundary because toList()/toKotlinList() may trigger collection operations.
     */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private fun extractArrayElements(value: MezzeValue): Array<MezzeValue>? {
        return when (value) {
            is MezzeArrayList -> value.toList().toTypedArray()
            is MezzeList -> value.toKotlinList().toTypedArray()
            else -> null
        }
    }

    /**
     * Extract rest elements and create the appropriate collection type.
     * Marked @TruffleBoundary because it uses drop() and collection creation.
     */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private fun extractRestElements(originalValue: MezzeValue, arrayElements: Array<MezzeValue>): MezzeValue {
        val restElements = arrayElements.drop(numElementPatterns)
        return when (originalValue) {
            is MezzeArrayList -> MezzeArrayList(restElements)
            is MezzeList -> MezzeList.fromKotlinList(restElements)
            else -> MezzeList.fromKotlinList(restElements)  // Fallback
        }
    }
}

/**
 * String interpolation pattern node (matches strings with embedded captures)
 * Examples:
 * - "hello $(name)" -> matches "hello world", binds name = "world"
 * - "$(a)-$(b)" -> matches "foo-bar", binds a = "foo", b = "bar"
 * - "prefix-$(middle)-suffix" -> matches "prefix-value-suffix", binds middle = "value"
 *
 * NOTE: String matching is performed in @TruffleBoundary methods to avoid JIT blockers
 * from Map operations and collection manipulation.
 */
@NodeInfo(shortName = "string-interpolation-pattern")
class StringInterpolationPatternNode(
    parts: List<StringPatternPart>,
    variableSlots: Map<String, Int>  // Pre-allocated slots for capture variables
) : PatternNode() {

    /**
     * A part of a string interpolation pattern - either a literal string or a capture.
     */
    sealed interface StringPatternPart {
        data class Literal(val value: String) : StringPatternPart
        data class Capture(val variableName: String) : StringPatternPart
    }

    // Convert to arrays for JIT optimization
    @com.oracle.truffle.api.CompilerDirectives.CompilationFinal(dimensions = 1)
    private val partsArray: Array<StringPatternPart> = parts.toTypedArray()

    @com.oracle.truffle.api.CompilerDirectives.CompilationFinal(dimensions = 1)
    private val captureVarNames: Array<String>

    @com.oracle.truffle.api.CompilerDirectives.CompilationFinal(dimensions = 1)
    private val captureSlotIndices: IntArray

    @com.oracle.truffle.api.CompilerDirectives.CompilationFinal
    private val numCaptures: Int

    init {
        // Pre-compute capture variable names and slots
        val captures = parts.filterIsInstance<StringPatternPart.Capture>()
        captureVarNames = captures.map { it.variableName }.toTypedArray()
        captureSlotIndices = captures.map { variableSlots[it.variableName] ?: -1 }.toIntArray()
        numCaptures = captures.size
    }

    override fun matchesInt(frame: VirtualFrame, value: MezzeInt): Boolean = false
    override fun matchesBoolean(frame: VirtualFrame, value: MezzeBool): Boolean = false
    override fun matchesVariant(frame: VirtualFrame, value: MezzeVariant): Boolean = false
    override fun matchesRecord(frame: VirtualFrame, value: MezzeRecord): Boolean = false
    override fun matchesGeneric(frame: VirtualFrame, value: MezzeValue): Boolean {
        return when (value) {
            is MezzeString -> matchesString(frame, value)
            else -> false
        }
    }

    override fun matchesString(frame: VirtualFrame, value: MezzeString): Boolean {
        // Perform matching in boundary method
        val captureValues = performMatching(value.value) ?: return false

        // Bind captured values to frame slots
        var i = 0
        while (i < numCaptures) {
            val slot = captureSlotIndices[i]
            if (slot >= 0 && i < captureValues.size) {
                frame.setObject(slot, MezzeString(captureValues[i]))
            }
            i++
        }

        return true
    }

    /**
     * Perform the actual string matching.
     * Returns an array of captured values in order, or null if no match.
     * Marked @TruffleBoundary because it uses Map operations and string manipulation.
     */
    @com.oracle.truffle.api.CompilerDirectives.TruffleBoundary
    private fun performMatching(inputString: String): Array<String>? {
        val captureValues = mutableMapOf<String, String>()

        // Try to match the string against the pattern
        val matched = matchPattern(inputString, 0, 0, captureValues)

        if (!matched) {
            return null
        }

        // Build result array in capture order
        return Array(numCaptures) { i ->
            captureValues[captureVarNames[i]] ?: ""
        }
    }

    /**
     * Recursively match the pattern against the input string.
     *
     * @param input The input string to match
     * @param inputPos Current position in the input string
     * @param partIndex Current pattern part index
     * @param captures Map to store captured values
     * @return true if the pattern matches from this position
     */
    private fun matchPattern(
        input: String,
        inputPos: Int,
        partIndex: Int,
        captures: MutableMap<String, String>
    ): Boolean {
        // If we've processed all parts, we should be at the end of the input
        if (partIndex >= partsArray.size) {
            return inputPos == input.length
        }

        val part = partsArray[partIndex]

        when (part) {
            is StringPatternPart.Literal -> {
                // Literal must match exactly
                val literal = part.value
                if (inputPos + literal.length > input.length) {
                    return false
                }
                if (!input.regionMatches(inputPos, literal, 0, literal.length)) {
                    return false
                }
                // Continue matching after the literal
                return matchPattern(input, inputPos + literal.length, partIndex + 1, captures)
            }

            is StringPatternPart.Capture -> {
                // Capture matches a portion of the string
                // We need to find where the next literal starts (if any)
                val nextPart = if (partIndex + 1 < partsArray.size) partsArray[partIndex + 1] else null

                when (nextPart) {
                    null -> {
                        // This is the last part - capture the rest of the string
                        captures[part.variableName] = input.substring(inputPos)
                        return true
                    }

                    is StringPatternPart.Literal -> {
                        // Find the next occurrence of the literal to determine capture bounds
                        // We try the earliest match first (greedy-first doesn't work for patterns)
                        val nextLiteral = nextPart.value
                        var searchPos = inputPos

                        while (true) {
                            val literalPos = input.indexOf(nextLiteral, searchPos)
                            if (literalPos == -1) {
                                // Literal not found - pattern doesn't match
                                return false
                            }

                            // Try capturing everything up to this literal position
                            val capturedValue = input.substring(inputPos, literalPos)
                            val savedCaptures = captures.toMap()
                            captures[part.variableName] = capturedValue

                            // Try to match the rest of the pattern
                            if (matchPattern(input, literalPos, partIndex + 1, captures)) {
                                return true
                            }

                            // Backtrack and try next occurrence
                            captures.clear()
                            captures.putAll(savedCaptures)
                            searchPos = literalPos + 1
                        }
                    }

                    is StringPatternPart.Capture -> {
                        // Adjacent captures - this shouldn't happen due to parser validation
                        // But if it does, we need to handle it
                        // For now, try all possible split points
                        for (splitPos in inputPos..input.length) {
                            val capturedValue = input.substring(inputPos, splitPos)
                            val savedCaptures = captures.toMap()
                            captures[part.variableName] = capturedValue

                            if (matchPattern(input, splitPos, partIndex + 1, captures)) {
                                return true
                            }

                            captures.clear()
                            captures.putAll(savedCaptures)
                        }
                        return false
                    }
                }
            }
        }
    }
}