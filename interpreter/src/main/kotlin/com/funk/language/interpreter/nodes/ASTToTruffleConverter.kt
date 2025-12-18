package com.funk.language.interpreter.nodes

import com.oracle.truffle.api.frame.FrameDescriptor
import com.funk.language.ast.*
import com.funk.language.interpreter.nodes.literal.*
import com.funk.language.interpreter.nodes.arithmetic.*
import com.funk.language.interpreter.nodes.comparison.*
import com.funk.language.interpreter.nodes.control.*
import com.funk.language.interpreter.nodes.function.*
import com.funk.language.interpreter.nodes.pattern.*
import com.funk.language.interpreter.nodes.variable.*
import com.funk.language.interpreter.FunkValue
import com.funk.language.interpreter.FunkInt
import com.funk.language.interpreter.FunkFloat
import com.funk.language.interpreter.FunkString
import com.funk.language.interpreter.FunkBool
import com.funk.language.interpreter.FunkRecord

/**
 * Converts Funk AST nodes to optimized Truffle nodes
 */
class ASTToTruffleConverter(
    private val context: com.funk.language.interpreter.FunkContext? = null,
    private val projectRoot: java.nio.file.Path? = null
) {

    private var frameDescriptorBuilder = FrameDescriptor.newBuilder()
    private val variableSlots = mutableMapOf<String, Int>()

    /**
     * Map of handler name → (effect name, implementation name)
     * e.g., "NativeRandom" → ("Random", "Random$NativeRandom")
     */
    private val handlerToEffectMapping = mutableMapOf<String, Pair<String, String>>()
    
    /**
     * Convert a program to a Truffle node
     */
    fun convertProgram(program: Program): FunkNode {
        val itemNodes = program.items.map { convertProgramItem(it) }.toTypedArray()
        return SequenceNode(itemNodes)
    }
    
    /**
     * Convert a program item to a Truffle node
     */
    private fun convertProgramItem(item: ProgramItem): FunkNode {
        return when (item) {
            is LetBinding -> convertLetBinding(item)
            is TopLevelExpression -> convertExpression(item.expression)
            is TypeDeclaration -> NullLiteralNode() // Type declarations don't produce runtime values
            is EffectDeclaration -> {
                // Register the effect with the effect handler so we can detect effect calls
                registerEffectDeclaration(item)
                NullLiteralNode() // Effect declarations don't produce runtime values
            }
            is ImplDeclaration -> convertImplDeclaration(item) // Convert effect implementations
            is AbilityDeclaration -> NullLiteralNode() // Ability declarations handled separately
            is ImplAbilityDeclaration -> convertImplAbilityDeclaration(item) // Return implementation record
            is UseDeclaration -> NullLiteralNode() // Use declarations resolved at type-check time
            is PubUseDeclaration -> NullLiteralNode() // Pub use declarations resolved at type-check time
            else -> throw RuntimeException("Unsupported program item: ${item::class.simpleName}")
        }
    }

    /**
     * Register an effect declaration so we can detect effect calls later
     */
    private fun registerEffectDeclaration(effectDecl: EffectDeclaration) {
        // Create a record containing the effect's operations (as a marker)
        // This allows EffectHandler to recognize effect names
        val effectRecord = FunkRecord(emptyMap()) // Empty record, just used as a marker

        // If we have a context, register with its effect handler
        context?.effectHandler?.registerEffect(effectDecl.name, effectRecord)

        println("DEBUG registerEffectDeclaration: Registered effect '${effectDecl.name}'")
    }

    /**
     * Convert an effect implementation (ImplDeclaration) to a record containing the operations
     * The record is stored with a mangled name like "Random$NativeRandom"
     */
    private fun convertImplDeclaration(impl: ImplDeclaration): FunkNode {
        val fieldNodes = mutableMapOf<String, FunkNode>()

        // Each operation becomes a field in the record
        for (op in impl.operations) {
            fieldNodes[op.name] = convertExpression(op.implementation)
        }

        // Create the implementation record
        val recordNode = RecordLiteralNode(fieldNodes)

        // Create a mangled name for the implementation like "Random$NativeRandom"
        val implName = "${impl.effectName}\$${impl.performerName}"

        println("DEBUG convertImplDeclaration: Storing impl as '$implName'")

        // CRITICAL: Store the mapping so we can look up handlers by performer name
        // This allows "perform NativeRandom in ..." to find the Random effect implementation
        handlerToEffectMapping[impl.performerName] = impl.effectName to implName
        println("DEBUG convertImplDeclaration: Mapped handler '${impl.performerName}' → effect '${impl.effectName}', impl '$implName'")

        // Store the implementation record with the mangled name
        val slot = getOrCreateSlot(implName)
        return SimpleWriteVariableNode(slot, recordNode, implName)
    }

    /**
     * Convert an impl ability declaration to a record containing the implementations
     * The record is stored with a mangled name like "Show$Int"
     */
    private fun convertImplAbilityDeclaration(impl: ImplAbilityDeclaration): FunkNode {
        val fieldNodes = mutableMapOf<String, FunkNode>()

        // Each operation becomes a field in the record
        for (op in impl.operations) {
            fieldNodes[op.name] = convertExpression(op.implementation)
        }

        // Create the implementation record
        val recordNode = RecordLiteralNode(fieldNodes)

        // Create a mangled name for the implementation like "Show$Int"
        val targetTypeNames = impl.targetTypes.joinToString("$") { typeAnnotation ->
            // Simple type name extraction - just use the type annotation's name
            when (typeAnnotation) {
                is IdentifierTypeAnnotation -> typeAnnotation.name
                else -> typeAnnotation.toString()
            }
        }
        val implName = "${impl.abilityName}\$$targetTypeNames"

        println("DEBUG convertImplAbilityDeclaration: Storing impl as '$implName'")

        // Store the implementation record with the mangled name
        val slot = getOrCreateSlot(implName)
        return SimpleWriteVariableNode(slot, recordNode, implName)
    }
    
    /**
     * Convert a let binding to a Truffle node
     * Supports pattern destructuring for records
     */
    private fun convertLetBinding(letBinding: LetBinding): FunkNode {
        return when (val pattern = letBinding.pattern) {
            is VariablePattern -> {
                // Simple case: let x = expr
                val slot = getOrCreateSlot(pattern.name)
                val valueNode = convertExpression(letBinding.expression)
                SimpleWriteVariableNode(slot, valueNode, pattern.name)
            }
            is RecordPattern -> {
                // Destructuring case: let {x, y} = expr
                convertRecordPatternBinding(pattern, letBinding.expression)
            }
            is WildcardPattern -> {
                // Wildcard case: let _ = expr
                // Just evaluate the expression for side effects, don't bind anything
                convertExpression(letBinding.expression)
            }
            else -> throw RuntimeException("Unsupported pattern in let binding: ${pattern::class.simpleName}")
        }
    }

    /**
     * Convert a record pattern binding to a Truffle node that performs destructuring
     * Examples:
     * - let {x, y} = point -> extracts x and y fields
     * - let {x = myX, y = myY} = point -> extracts x and y, binds to myX and myY
     * - let {inner = {value}} = outer -> nested destructuring
     * - let {a, ...} = record -> partial destructuring with ellipsis
     */
    private fun convertRecordPatternBinding(pattern: RecordPattern, valueExpr: Expression): FunkNode {
        val valueNode = convertExpression(valueExpr)

        // Collect all bindings from the pattern
        val bindings = collectRecordPatternBindings(pattern)

        // CRITICAL: Pre-allocate frame slots for all variables BEFORE creating the node
        // This must happen at node construction time, not execution time
        val slots = bindings.map { (_, varName) ->
            varName to getOrCreateSlot(varName)
        }.toMap()

        // Create a node that performs all the bindings
        return object : FunkNode() {
            @field:Child private var valueChild = valueNode

            override fun execute(frame: com.oracle.truffle.api.frame.VirtualFrame): FunkValue {
                val value = valueChild.execute(frame)

                if (value !is FunkRecord) {
                    throw RuntimeException("Cannot destructure non-record value: ${value::class.simpleName}")
                }

                // Get context for defining local variables (needed for top-level bindings)
                val context = com.funk.language.interpreter.FunkLanguage.getCurrentContext(this)

                // Perform each binding
                for ((fieldPath, varName) in bindings) {
                    var currentValue: FunkValue = value

                    // Navigate the field path (for nested patterns)
                    for (fieldName in fieldPath) {
                        if (currentValue !is FunkRecord) {
                            throw RuntimeException("Cannot access field '$fieldName' on non-record value: ${currentValue::class.simpleName}")
                        }
                        currentValue = currentValue.fields[fieldName]
                            ?: throw RuntimeException("Field '$fieldName' not found in record")
                    }

                    // Bind the value to the variable in both frame and context
                    val slot = slots[varName]!!
                    frame.setObject(slot, currentValue)

                    // ALSO write to context.locals so module exports can be extracted
                    // This is critical for top-level let bindings
                    context.defineLocal(varName, currentValue)
                }

                // Return empty record (unit value)
                return FunkRecord(emptyMap())
            }
        }
    }

    /**
     * Collect bindings from a record pattern
     * Returns a list of (fieldPath, variableName) pairs
     *
     * Examples:
     * - {x, y} returns [(["x"], "x"), (["y"], "y")]
     * - {x = myX, y = myY} returns [(["x"], "myX"), (["y"], "myY")]
     * - {inner = {value}} returns [(["inner", "value"], "value")]
     * - {a, ...} returns [(["a"], "a")] (ellipsis is ignored)
     */
    private fun collectRecordPatternBindings(
        pattern: RecordPattern,
        prefix: List<String> = emptyList()
    ): List<Pair<List<String>, String>> {
        val bindings = mutableListOf<Pair<List<String>, String>>()

        for (entry in pattern.entries) {
            val fieldPath = prefix + entry.name

            when (val entryPattern = entry.pattern) {
                null -> {
                    // Shorthand: {x} means {x = x}
                    bindings.add(fieldPath to entry.name)
                }
                is VariablePattern -> {
                    // Renamed: {x = myX}
                    bindings.add(fieldPath to entryPattern.name)
                }
                is RecordPattern -> {
                    // Nested: {inner = {value}}
                    bindings.addAll(collectRecordPatternBindings(entryPattern, fieldPath))
                }
                is WildcardPattern -> {
                    // {x = _} - ignore this field
                }
                else -> throw RuntimeException("Unsupported pattern in record destructuring: ${entryPattern::class.simpleName}")
            }
        }

        return bindings
    }
    
    /**
     * Convert an expression to a Truffle node
     */
    fun convertExpression(expr: Expression): FunkNode {
        System.err.println("DEBUG convertExpression: Converting ${expr::class.simpleName}")
        if (expr is PerformExpression) {
            System.err.println("!!! FOUND PerformExpression in convertExpression !!!")
        }
        if (expr is FunctionCall) {
            val funcCall = expr
            System.err.println("DEBUG convertExpression: FunctionCall detected, function=${funcCall.function::class.simpleName}")
            if (funcCall.function is MemberAccess) {
                val ma = funcCall.function as MemberAccess
                System.err.println("DEBUG convertExpression:   MemberAccess: receiver=${ma.receiver::class.simpleName}, member=${ma.member}")
                if (ma.receiver is Identifier) {
                    System.err.println("DEBUG convertExpression:   Receiver identifier: ${(ma.receiver as Identifier).name}")
                }
            }
        }
        return when (expr) {
            is IntLiteral -> IntLiteralNode(expr.value)
            is NatLiteral -> IntLiteralNode(expr.value)  // Nat values are represented as Int at runtime
            is FloatLiteral -> FloatLiteralNode(expr.value)
            is FractionLiteral -> FractionLiteralNode(expr.numerator, expr.denominator)
            is StringLiteral -> {
                val parts = expr.parts
                if (expr.isInterpolated && parts != null) {
                    // Convert to InterpolatedStringNode with part nodes
                    val partNodes = parts.map { part ->
                        when (part) {
                            is StringPart -> StringLiteralNode(part.value)
                            is ExpressionPart -> convertExpression(part.expression)
                        }
                    }.toTypedArray()
                    InterpolatedStringNode(partNodes)
                } else {
                    StringLiteralNode(expr.value)
                }
            }
            is BooleanLiteral -> BooleanLiteralNode(expr.value)
            is Identifier -> convertIdentifier(expr)
            // BinaryOperation and UnaryOperation removed - operators are desugared at parse time
            is IfExpression -> convertIfExpression(expr)
            is MatchExpression -> convertMatchExpression(expr)
            is FunctionCall -> convertFunctionCall(expr)
            is LambdaExpression -> convertLambdaExpression(expr)
            is RecordLiteral -> convertRecordLiteral(expr)
            is ArrayLiteral -> convertArrayLiteral(expr)
            is ArrayAccess -> convertArrayAccess(expr)
            is MemberAccess -> convertMemberAccess(expr)
            is VariantConstruction -> convertVariantConstruction(expr)
            is DoExpression -> convertDoExpression(expr)
            is PerformExpression -> convertPerformExpression(expr)
            else -> throw RuntimeException("Unsupported expression: ${expr::class.simpleName}")
        }
    }
    
    /**
     * Convert an identifier to a variable read node or global lookup
     */
    private fun convertIdentifier(identifier: Identifier): FunkNode {
        // CRITICAL: Check if this variable is already in the frame FIRST
        // This ensures parameters and local variables take precedence over builtins
        if (variableSlots.containsKey(identifier.name)) {
            val slot = variableSlots[identifier.name]!!
            return SimpleReadVariableNode(slot, identifier.name)
        }

        // Check if this is a built-in function that should be resolved globally
        if (isBuiltinFunction(identifier.name)) {
            return GlobalLookupNode(identifier.name)
        }

        // FALLBACK: Detect if this is a builtin ability name that should have been resolved
        // This catches cases where the typechecker failed to resolve ability calls properly
        val abilityNames = listOf("NumAdd", "NumSub", "NumMul", "NumDiv", "NumMod", "Eq", "Ord", "LogicalAnd", "LogicalOr", "LogicalNot")
        if (identifier.name in abilityNames) {
            println("WARNING convertIdentifier: Looking up builtin ability name '${identifier.name}' as identifier!")
            println("WARNING: This likely means the typechecker didn't properly resolve an operator")
            println("WARNING: Creating a node that will throw a clear error at runtime")
            // Return a node that throws a helpful error
            return object : FunkNode() {
                override fun execute(frame: com.oracle.truffle.api.frame.VirtualFrame): FunkValue {
                    throw RuntimeException("Undefined variable '${identifier.name}' at runtime. This should have been caught by the type checker. " +
                        "Note: '${identifier.name}' is a builtin ability name and should be used with an operation like ${identifier.name}.add or ${identifier.name}.modulo")
                }
            }
        }

        // Otherwise, treat as a local variable
        // Pass the variable name for fallback to context.locals (needed for module imports)
        val slot = getOrCreateSlot(identifier.name)
        return SimpleReadVariableNode(slot, identifier.name)
    }
    
    /**
     * Check if an identifier is a built-in function
     */
    private fun isBuiltinFunction(name: String): Boolean {
        return when (name) {
            "intToString", "floatToString", "boolToString", "stringToInt",
            "print", "debug", "abs", "max", "min", "length", "concat",
            "arrayLength", "arrayGet", "arraySet", "arrayAdd", "arrayRemove",
            "arrayConcat", "arrayIsEmpty" -> true
            else -> false
        }
    }
    
    // BinaryOperation and UnaryOperation removed - operators are desugared at parse time

    /**
     * Convert an if expression to a conditional node
     */
    private fun convertIfExpression(ifExpr: IfExpression): FunkNode {
        val conditionNode = convertExpression(ifExpr.condition)
        val thenNode = convertExpression(ifExpr.thenBranch)
        val elseNode = convertExpression(ifExpr.elseBranch)
        
        return IfNode(conditionNode, thenNode, elseNode)
    }
    
    /**
     * Convert a match expression to a pattern matching node
     */
    private fun convertMatchExpression(matchExpr: MatchExpression): FunkNode {
        val valueNode = convertExpression(matchExpr.expression)
        val caseNodes = matchExpr.cases.map { convertMatchCase(it) }.toTypedArray()
        
        return createPatternMatchNode(valueNode, caseNodes)
    }
    
    /**
     * Convert a match case to a pattern case node
     */
    private fun convertMatchCase(matchCase: MatchCase): PatternCaseNode {
        val patterns = matchCase.patterns.map { convertPattern(it) }.toTypedArray()
        val guardNode = matchCase.guard?.let { convertExpression(it) }
        val bodyNode = convertExpression(matchCase.expression)
        
        return createPatternCaseNode(patterns, guardNode, bodyNode)
    }
    
    /**
     * Convert a pattern to a pattern node
     */
    private fun convertPattern(pattern: Pattern): PatternNode {
        return when (pattern) {
            is VariablePattern -> VariablePatternNode(pattern.name)
            is WildcardPattern -> WildcardPatternNode()
            is LiteralPattern -> {
                // Convert literal patterns to LiteralPatternNode with the appropriate FunkValue
                val value = when (val lit = pattern.value) {
                    is IntLiteralValue -> FunkInt(lit.value)
                    is NatLiteralValue -> FunkInt(lit.value)  // Nat values are represented as Int at runtime
                    is FloatLiteralValue -> FunkFloat(lit.value)
                    is StringLiteralValue -> FunkString(lit.value)
                    is BooleanLiteralValue -> FunkBool(lit.value)
                }
                LiteralPatternNode(value)
            }
            is ConstructorPattern -> {
                // Constructor patterns match variants with optional nested record patterns
                // Examples: Some { val }, None, Ok { value }

                val recordPattern = pattern.recordPattern

                // If there's a record pattern, collect all variable bindings and pre-allocate slots
                val variableSlots = if (recordPattern != null) {
                    val bindings = collectRecordPatternBindings(recordPattern)
                    bindings.associate { (_, varName) ->
                        varName to getOrCreateSlot(varName)
                    }
                } else {
                    emptyMap()
                }

                // Create the ConstructorPatternNode with pre-allocated slots
                ConstructorPatternNode(
                    constructorName = pattern.constructor,
                    recordPattern = recordPattern,
                    variableSlots = variableSlots
                )
            }
            is RecordPattern -> {
                // For now, treat record patterns as wildcard patterns
                // This is a simplified implementation
                WildcardPatternNode()
            }
        }
    }
    
    /**
     * Convert a function call to a function call node
     * Uses resolved abilities for direct dispatch when available
     */
    private fun convertFunctionCall(funcCall: FunctionCall): FunkNode {
        val functionClass = funcCall.function::class.simpleName
        System.err.println("=== DEBUG convertFunctionCall ENTRY: function=$functionClass, resolvedAbilities=${funcCall.resolvedAbilities.size}, resolvedFunctionName=${funcCall.resolvedFunctionName}")

        // PRIORITY 1: Check for monomorphic function call (Rust-style)
        // Monomorphic functions are specialized and should be called directly
        if (funcCall.resolvedFunctionName != null) {
            println("DEBUG convertFunctionCall: Has resolved function name: ${funcCall.resolvedFunctionName}")
            return convertMonomorphicFunctionCall(funcCall)
        }

        // PRIORITY 2: Check if this call has resolved abilities (from TypeChecker ability resolution pass)
        // Resolved abilities are compile-time known and take precedence over runtime effect detection
        if (funcCall.resolvedAbilities.isNotEmpty()) {
            println("DEBUG convertFunctionCall: Has ${funcCall.resolvedAbilities.size} resolved abilities")
            // Use direct ability call for better Truffle performance
            return convertResolvedAbilityCall(funcCall)
        }

        // PRIORITY 2.5: Fallback for ability calls that weren't resolved
        // This handles cases where modules are type-checked but ability resolution info is lost
        // Detect patterns like NumAdd.add, NumMod.modulo, Eq.eq, etc.
        if (funcCall.function is MemberAccess) {
            val memberAccess = funcCall.function as MemberAccess
            if (memberAccess.receiver is Identifier) {
                val receiverName = (memberAccess.receiver as Identifier).name
                val operationName = memberAccess.member

                // Check if this looks like a builtin ability call
                val isBuiltinAbility = receiverName in listOf(
                    "NumAdd", "NumSub", "NumMul", "NumDiv", "NumMod",
                    "Eq", "Ord", "LogicalAnd", "LogicalOr", "LogicalNot"
                )

                if (isBuiltinAbility) {
                    println("DEBUG convertFunctionCall: Detected unresolved builtin ability call: $receiverName.$operationName")
                    // Manually create the builtin operation node
                    val argsNode = convertExpression(funcCall.arguments)

                    return when {
                        receiverName == "NumAdd" && operationName == "add" -> createBuiltinBinaryOp("add", argsNode)
                        receiverName == "NumSub" && operationName == "sub" -> createBuiltinBinaryOp("subtract", argsNode)
                        receiverName == "NumSub" && operationName == "neg" -> createBuiltinUnaryOp("negate", argsNode)
                        receiverName == "NumMul" && operationName == "mul" -> createBuiltinBinaryOp("multiply", argsNode)
                        receiverName == "NumDiv" && operationName == "div" -> createBuiltinBinaryOp("divide", argsNode)
                        receiverName == "NumMod" && operationName == "mod" -> createBuiltinBinaryOp("modulo", argsNode)
                        receiverName == "Eq" && operationName == "eq" -> createBuiltinBinaryOp("equal", argsNode)
                        receiverName == "Eq" && operationName == "neq" -> createBuiltinBinaryOp("notEqual", argsNode)
                        receiverName == "Ord" && operationName == "lt" -> createBuiltinBinaryOp("lessThan", argsNode)
                        receiverName == "Ord" && operationName == "gt" -> createBuiltinBinaryOp("greaterThan", argsNode)
                        receiverName == "Ord" && operationName == "le" -> createBuiltinBinaryOp("lessEqual", argsNode)
                        receiverName == "Ord" && operationName == "ge" -> createBuiltinBinaryOp("greaterEqual", argsNode)
                        receiverName == "LogicalAnd" && operationName == "and" -> createBuiltinBinaryOp("and", argsNode)
                        receiverName == "LogicalOr" && operationName == "or" -> createBuiltinBinaryOp("or", argsNode)
                        receiverName == "LogicalNot" && operationName == "not" -> createBuiltinUnaryOp("not", argsNode)
                        else -> {
                            // Unknown ability operation - fall through to regular handling
                            println("DEBUG convertFunctionCall: Unknown ability operation $receiverName.$operationName")
                            val functionNode = convertExpression(funcCall.function)
                            createFunctionCallNode(functionNode, argsNode)
                        }
                    }
                }
            }
        }

        // PRIORITY 3: Check for effect calls (runtime detection)
        // Effect calls are like Random.nextInt where Random is an effect, not a value
        // This needs to be checked at runtime since context might not be available during conversion
        if (funcCall.function is MemberAccess) {
            val memberAccess = funcCall.function as MemberAccess
            val receiverClass = memberAccess.receiver::class.simpleName
            System.err.println("=== DEBUG convertFunctionCall: function IS MemberAccess, receiver=$receiverClass, member=${memberAccess.member}")

            if (memberAccess.receiver is Identifier) {
                val receiverName = (memberAccess.receiver as Identifier).name
                val operationName = memberAccess.member

                // Check if this is an effect call
                System.err.println("=== DEBUG convertFunctionCall: MemberAccess receiver IS Identifier '$receiverName', operation='$operationName'")
                System.err.println("=== DEBUG convertFunctionCall: Creating dynamic effect/function call node for '$receiverName.$operationName'")
                System.err.println("=== *** RETURNING EARLY WITH DYNAMIC EFFECT CALL NODE ***")

                // Create a node that checks at runtime if this is an effect call
                return createDynamicEffectOrFunctionCall(receiverName, operationName, funcCall.arguments)
            } else if (memberAccess.receiver is VariantConstruction) {
                // Effect names are often capitalized and parsed as VariantConstruction
                val receiverName = (memberAccess.receiver as VariantConstruction).constructor
                val operationName = memberAccess.member

                System.err.println("=== DEBUG convertFunctionCall: MemberAccess receiver IS VariantConstruction '$receiverName', operation='$operationName'")
                System.err.println("=== DEBUG convertFunctionCall: Creating dynamic effect/function call node for '$receiverName.$operationName'")
                System.err.println("=== *** RETURNING EARLY WITH DYNAMIC EFFECT CALL NODE ***")

                // Create a node that checks at runtime if this is an effect call
                return createDynamicEffectOrFunctionCall(receiverName, operationName, funcCall.arguments)
            } else {
                System.err.println("=== DEBUG convertFunctionCall: MemberAccess receiver is NOT Identifier or VariantConstruction, continuing...")
            }
        } else {
            System.err.println("=== DEBUG convertFunctionCall: function is NOT MemberAccess, continuing...")
        }

        // Default: Dynamic dispatch through FunctionCallNode
        val functionNode = convertExpression(funcCall.function)
        val argumentsNode = convertExpression(funcCall.arguments)

        return createFunctionCallNode(functionNode, argumentsNode)
    }

    /**
     * Create a node that decides at runtime whether this is an effect call or regular function call
     */
    private fun createDynamicEffectOrFunctionCall(
        receiverName: String,
        operationName: String,
        argumentsExpr: Expression
    ): FunkNode {
        val argumentsNode = convertExpression(argumentsExpr)

        return object : FunkNode() {
            @field:Child private var argsChild = argumentsNode

            override fun execute(frame: com.oracle.truffle.api.frame.VirtualFrame): FunkValue {
                val context = getContext()
                val effectHandler = context.effectHandler

                println("DEBUG DynamicEffectCall: Checking if '$receiverName' is an effect")
                println("DEBUG DynamicEffectCall: context.effectHandler = $effectHandler")
                println("DEBUG DynamicEffectCall: isEffectDeclared('$receiverName') = ${effectHandler.isEffectDeclared(receiverName)}")

                // Check if this is a declared effect
                if (effectHandler.isEffectDeclared(receiverName)) {
                    println("DEBUG DynamicEffectCall: '$receiverName' IS an effect, calling executeEffectCall")

                    // This is an effect call - use the effect handler
                    val arguments = argsChild.execute(frame)

                    // Build a mutable context map for the effect handler
                    val effectContext = mutableMapOf<String, FunkValue>()
                    effectContext.putAll(context.captureEnvironment())

                    // Execute the effect call through the handler
                    return effectHandler.executeEffectCall(
                        effectName = receiverName,
                        operationName = operationName,
                        arguments = arguments,
                        context = effectContext,
                        interpreter = com.funk.language.interpreter.FunkInterpreter()
                    )
                } else {
                    println("DEBUG DynamicEffectCall: '$receiverName' is NOT declared as effect")
                    println("DEBUG DynamicEffectCall: effectHandler=$effectHandler")
                    println("DEBUG DynamicEffectCall: Trying to look up '$receiverName' as regular variable")

                    // Not an effect - treat as regular member access and function call
                    // This is a fallback for non-effect member access like myRecord.myFunction
                    val receiver = context.lookupGlobal(receiverName)
                    println("DEBUG DynamicEffectCall: lookupGlobal('$receiverName') returned: ${receiver?.let { it::class.simpleName } ?: "null"}")

                    if (receiver == null) {
                        throw RuntimeException("Undefined variable: $receiverName")
                    }

                    if (receiver !is FunkRecord) {
                        throw RuntimeException("Cannot access member '$operationName' on non-record value: ${receiver::class.simpleName}")
                    }

                    val operation = receiver.fields[operationName]
                        ?: throw RuntimeException("Field '$operationName' not found in record")

                    if (operation !is com.funk.language.interpreter.FunkTruffleFunction) {
                        throw RuntimeException("Field '$operationName' is not a function")
                    }

                    val arguments = argsChild.execute(frame)
                    return operation.callTarget.call(arguments) as FunkValue
                }
            }
        }
    }

    /**
     * Convert a function call with resolved abilities to a direct call node
     * This enables Truffle to optimize the call without runtime ability lookup
     */
    private fun convertResolvedAbilityCall(funcCall: FunctionCall): FunkNode {
        // Get the first resolved ability (for now, we only handle single ability calls)
        val resolvedAbility = funcCall.resolvedAbilities.values.firstOrNull()
        println("DEBUG convertResolvedAbilityCall: resolvedAbility=$resolvedAbility, isBuiltin=${resolvedAbility?.isBuiltin}, implementationRef=${resolvedAbility?.implementationRef}")

        if (resolvedAbility != null) {
            // Check if it's a builtin or user-defined ability
            if (resolvedAbility.isBuiltin) {
            println("DEBUG convertResolvedAbilityCall: This is a builtin ability")
            // For builtin abilities, create specialized arithmetic/comparison nodes
            // Example: NumAdd -> AddNode, NumSub -> SubtractNode, etc.

            // The function should be a MemberAccess like x.add
            println("DEBUG convertResolvedAbilityCall: function type is ${funcCall.function::class.simpleName}")
            if (funcCall.function is MemberAccess) {
                val memberAccess = funcCall.function as MemberAccess
                val operationName = memberAccess.member
                val abilityName = resolvedAbility.abilityName
                println("DEBUG convertResolvedAbilityCall: abilityName=$abilityName, operationName=$operationName")

                // Convert arguments (should be a record like {l = x, r = y})
                val argsNode = convertExpression(funcCall.arguments)

                // Create specialized node based on ability and operation
                // Arithmetic: add, sub, mul, div
                // Comparison: eq, neq, lt, gt, le, ge
                return when {
                    // Arithmetic operators
                    abilityName == "NumAdd" && operationName == "add" -> {
                        println("DEBUG convertResolvedAbilityCall: Creating builtin add node")
                        createBuiltinBinaryOp("add", argsNode)
                    }
                    abilityName == "NumSub" && operationName == "sub" -> {
                        println("DEBUG convertResolvedAbilityCall: Creating builtin subtract node")
                        createBuiltinBinaryOp("subtract", argsNode)
                    }
                    abilityName == "NumSub" && operationName == "neg" -> {
                        println("DEBUG convertResolvedAbilityCall: Creating builtin negate node")
                        createBuiltinUnaryOp("negate", argsNode)
                    }
                    abilityName == "NumMul" && operationName == "mul" -> {
                        println("DEBUG convertResolvedAbilityCall: Creating builtin multiply node")
                        createBuiltinBinaryOp("multiply", argsNode)
                    }
                    abilityName == "NumDiv" && operationName == "div" -> {
                        println("DEBUG convertResolvedAbilityCall: Creating builtin divide node")
                        createBuiltinBinaryOp("divide", argsNode)
                    }
                    abilityName == "NumMod" && operationName == "mod" -> {
                        println("DEBUG convertResolvedAbilityCall: Creating builtin modulo node")
                        createBuiltinBinaryOp("modulo", argsNode)
                    }
                    // Comparison operators
                    abilityName == "Eq" && operationName == "eq" -> {
                        println("DEBUG convertResolvedAbilityCall: Creating builtin equal node")
                        createBuiltinBinaryOp("equal", argsNode)
                    }
                    abilityName == "Eq" && operationName == "neq" -> {
                        println("DEBUG convertResolvedAbilityCall: Creating builtin not-equal node")
                        createBuiltinBinaryOp("notEqual", argsNode)
                    }
                    abilityName == "Ord" && operationName == "lt" -> {
                        println("DEBUG convertResolvedAbilityCall: Creating builtin less-than node")
                        createBuiltinBinaryOp("lessThan", argsNode)
                    }
                    abilityName == "Ord" && operationName == "gt" -> {
                        println("DEBUG convertResolvedAbilityCall: Creating builtin greater-than node")
                        createBuiltinBinaryOp("greaterThan", argsNode)
                    }
                    abilityName == "Ord" && operationName == "le" -> {
                        println("DEBUG convertResolvedAbilityCall: Creating builtin less-equal node")
                        createBuiltinBinaryOp("lessEqual", argsNode)
                    }
                    abilityName == "Ord" && operationName == "ge" -> {
                        println("DEBUG convertResolvedAbilityCall: Creating builtin greater-equal node")
                        createBuiltinBinaryOp("greaterEqual", argsNode)
                    }
                    // Logical operators
                    abilityName == "LogicalAnd" && operationName == "and" -> {
                        println("DEBUG convertResolvedAbilityCall: Creating builtin and node")
                        createBuiltinBinaryOp("and", argsNode)
                    }
                    abilityName == "LogicalOr" && operationName == "or" -> {
                        println("DEBUG convertResolvedAbilityCall: Creating builtin or node")
                        createBuiltinBinaryOp("or", argsNode)
                    }
                    abilityName == "LogicalNot" && operationName == "not" -> {
                        println("DEBUG convertResolvedAbilityCall: Creating builtin not node")
                        createBuiltinUnaryOp("not", argsNode)
                    }
                    else -> {
                        println("DEBUG convertResolvedAbilityCall: No matching builtin operation, falling back to dynamic dispatch")
                        // Unsupported builtin ability - fallback to dynamic dispatch
                        val functionNode = convertExpression(funcCall.function)
                        createFunctionCallNode(functionNode, argsNode)
                    }
                }
            } else {
                println("DEBUG convertResolvedAbilityCall: function is not MemberAccess, falling back")
            }
            } else {
                // User-defined ability - look up the implementation directly
                println("DEBUG convertResolvedAbilityCall: This is a user-defined ability")

                // The function should be a MemberAccess like Show.show
                if (funcCall.function is MemberAccess) {
                    val memberAccess = funcCall.function as MemberAccess
                    val operationName = memberAccess.member
                    val implementationRef = resolvedAbility.implementationRef
                    println("DEBUG convertResolvedAbilityCall: operationName=$operationName, implementationRef=$implementationRef")

                    // Convert arguments
                    val argsNode = convertExpression(funcCall.arguments)

                    // Look up the implementation record (e.g., "Show$Int")
                    // Then access the operation field (e.g., "show")
                    // The implementationRef might be something like "Show$Int::show" or just "Show$Int"
                    // Let's handle both cases
                    val implRecordName = if (implementationRef.contains("::")) {
                        // Extract the record name before ::
                        implementationRef.substringBefore("::")
                    } else {
                        implementationRef
                    }

                    println("DEBUG convertResolvedAbilityCall: Looking up impl record: $implRecordName, operation: $operationName")

                    // Create a node that:
                    // 1. Looks up the implementation record from context
                    // 2. Accesses the operation field
                    // 3. Calls it with the arguments
                    return object : FunkNode() {
                        @field:Child private var argumentsChild = argsNode

                        override fun execute(frame: com.oracle.truffle.api.frame.VirtualFrame): FunkValue {
                            // Look up the implementation record
                            val implRecord = context?.lookupGlobal(implRecordName) ?: context?.locals?.get(implRecordName)

                            if (implRecord == null || implRecord is com.funk.language.interpreter.FunkNull) {
                                throw com.funk.language.interpreter.FunkException("Cannot find ability implementation: $implRecordName")
                            }

                            if (implRecord !is FunkRecord) {
                                throw com.funk.language.interpreter.FunkException("Ability implementation must be a record, got: ${implRecord::class.simpleName}")
                            }

                            // Get the operation from the implementation record
                            val operation = implRecord.fields[operationName]
                            if (operation == null || operation is com.funk.language.interpreter.FunkNull) {
                                throw com.funk.language.interpreter.FunkException("Cannot find operation '$operationName' in ability implementation $implRecordName")
                            }

                            if (operation !is com.funk.language.interpreter.FunkTruffleFunction) {
                                throw com.funk.language.interpreter.FunkException("Ability operation must be a function, got: ${operation::class.simpleName}")
                            }

                            // Call the operation with the arguments
                            val args = argumentsChild.execute(frame)
                            return operation.callTarget.call(args) as FunkValue
                        }
                    }
                } else {
                    println("DEBUG convertResolvedAbilityCall: User-defined ability but function is not MemberAccess, falling back")
                }
            }
        }

        // Fallback to regular function call for non-builtin abilities
        println("DEBUG convertResolvedAbilityCall: Using fallback path")
        val functionNode = convertExpression(funcCall.function)
        val argumentsNode = convertExpression(funcCall.arguments)
        return createFunctionCallNode(functionNode, argumentsNode)
    }

    /**
     * Create a builtin unary operation node that extracts n from a record argument
     */
    private fun createBuiltinUnaryOp(operation: String, argsNode: FunkNode): FunkNode {
        // argsNode evaluates to a record {n = value}
        // We need to extract n and pass it to the unary op node
        return object : FunkNode() {
            @field:Child private var argumentsChild = argsNode

            override fun execute(frame: com.oracle.truffle.api.frame.VirtualFrame): FunkValue {
                val argsValue = argumentsChild.execute(frame)
                if (argsValue !is FunkRecord) {
                    throw RuntimeException("Unary operation arguments must be a record, got: ${argsValue::class.simpleName}")
                }

                val operand = argsValue.fields["n"] ?: throw RuntimeException("Missing 'n' field in unary operation")

                return when (operation) {
                    "negate" -> {
                        when (operand) {
                            is FunkInt -> FunkInt(-operand.value)
                            is FunkFloat -> FunkFloat(-operand.value)
                            else -> throw RuntimeException("Cannot negate ${operand::class.simpleName}")
                        }
                    }
                    "not" -> {
                        when (operand) {
                            is FunkBool -> FunkBool(!operand.value)
                            else -> throw RuntimeException("Cannot apply logical not to ${operand::class.simpleName}")
                        }
                    }
                    else -> throw RuntimeException("Unsupported unary operation: $operation")
                }
            }
        }
    }

    /**
     * Create a builtin binary operation node that extracts l and r from a record argument
     */
    private fun createBuiltinBinaryOp(operation: String, argsNode: FunkNode): FunkNode {
        // argsNode evaluates to a record {l = left, r = right}
        // We need to extract l and r and pass them to the binary op node
        return object : FunkNode() {
            @field:Child private var argumentsChild = argsNode

            override fun execute(frame: com.oracle.truffle.api.frame.VirtualFrame): FunkValue {
                val argsValue = argumentsChild.execute(frame)
                if (argsValue !is FunkRecord) {
                    throw RuntimeException("Binary operation arguments must be a record, got: ${argsValue::class.simpleName}")
                }

                val left = argsValue.fields["l"] ?: throw RuntimeException("Missing 'l' field in binary operation")
                val right = argsValue.fields["r"] ?: throw RuntimeException("Missing 'r' field in binary operation")

                return when (operation) {
                    // Arithmetic operations
                    "add" -> {
                        when {
                            left is FunkInt && right is FunkInt -> FunkInt(left.value + right.value)
                            left is FunkFloat && right is FunkFloat -> FunkFloat(left.value + right.value)
                            left is com.funk.language.interpreter.FunkNat && right is com.funk.language.interpreter.FunkNat ->
                                com.funk.language.interpreter.FunkNat(left.value + right.value)
                            left is com.funk.language.interpreter.FunkFrac && right is com.funk.language.interpreter.FunkFrac -> {
                                val newNumerator = left.numerator * right.denominator + right.numerator * left.denominator
                                val newDenominator = left.denominator * right.denominator
                                com.funk.language.interpreter.FunkFrac(newNumerator, newDenominator).reduce()
                            }
                            else -> throw RuntimeException("Cannot add ${left::class.simpleName} and ${right::class.simpleName}")
                        }
                    }
                    "subtract" -> {
                        when {
                            left is FunkInt && right is FunkInt -> FunkInt(left.value - right.value)
                            left is FunkFloat && right is FunkFloat -> FunkFloat(left.value - right.value)
                            left is com.funk.language.interpreter.FunkNat && right is com.funk.language.interpreter.FunkNat ->
                                FunkInt(left.value - right.value) // Nat - Nat can be negative, so return Int
                            left is com.funk.language.interpreter.FunkFrac && right is com.funk.language.interpreter.FunkFrac -> {
                                val newNumerator = left.numerator * right.denominator - right.numerator * left.denominator
                                val newDenominator = left.denominator * right.denominator
                                com.funk.language.interpreter.FunkFrac(newNumerator, newDenominator).reduce()
                            }
                            else -> throw RuntimeException("Cannot subtract ${left::class.simpleName} and ${right::class.simpleName}")
                        }
                    }
                    "multiply" -> {
                        when {
                            left is FunkInt && right is FunkInt -> FunkInt(left.value * right.value)
                            left is FunkFloat && right is FunkFloat -> FunkFloat(left.value * right.value)
                            left is com.funk.language.interpreter.FunkNat && right is com.funk.language.interpreter.FunkNat ->
                                com.funk.language.interpreter.FunkNat(left.value * right.value)
                            left is com.funk.language.interpreter.FunkFrac && right is com.funk.language.interpreter.FunkFrac -> {
                                val newNumerator = left.numerator * right.numerator
                                val newDenominator = left.denominator * right.denominator
                                com.funk.language.interpreter.FunkFrac(newNumerator, newDenominator).reduce()
                            }
                            else -> throw RuntimeException("Cannot multiply ${left::class.simpleName} and ${right::class.simpleName}")
                        }
                    }
                    "divide" -> {
                        when {
                            left is FunkInt && right is FunkInt -> FunkInt(left.value / right.value)
                            left is FunkFloat && right is FunkFloat -> FunkFloat(left.value / right.value)
                            left is com.funk.language.interpreter.FunkNat && right is com.funk.language.interpreter.FunkNat -> {
                                if (right.value == 0L) throw ArithmeticException("Division by zero")
                                com.funk.language.interpreter.FunkNat(left.value / right.value)
                            }
                            left is com.funk.language.interpreter.FunkFrac && right is com.funk.language.interpreter.FunkFrac -> {
                                if (right.numerator == 0L) throw ArithmeticException("Division by zero")
                                val newNumerator = left.numerator * right.denominator
                                val newDenominator = left.denominator * right.numerator
                                com.funk.language.interpreter.FunkFrac(newNumerator, newDenominator).reduce()
                            }
                            else -> throw RuntimeException("Cannot divide ${left::class.simpleName} and ${right::class.simpleName}")
                        }
                    }
                    "modulo" -> {
                        when {
                            left is FunkInt && right is FunkInt -> {
                                if (right.value == 0L) throw ArithmeticException("Modulo by zero")
                                FunkInt(left.value % right.value)
                            }
                            left is com.funk.language.interpreter.FunkNat && right is com.funk.language.interpreter.FunkNat -> {
                                if (right.value == 0L) throw ArithmeticException("Modulo by zero")
                                com.funk.language.interpreter.FunkNat(left.value % right.value)
                            }
                            else -> throw RuntimeException("Cannot compute modulo for ${left::class.simpleName} and ${right::class.simpleName}")
                        }
                    }
                    // Comparison operations
                    "equal" -> {
                        // Structural equality comparison - works for all value types
                        FunkBool(structuralEquals(left, right))
                    }
                    "notEqual" -> {
                        // Structural not-equal comparison
                        FunkBool(!structuralEquals(left, right))
                    }
                    "lessThan" -> {
                        when {
                            left is FunkInt && right is FunkInt -> FunkBool(left.value < right.value)
                            left is FunkFloat && right is FunkFloat -> FunkBool(left.value < right.value)
                            left is com.funk.language.interpreter.FunkNat && right is com.funk.language.interpreter.FunkNat ->
                                FunkBool(left.value < right.value)
                            left is com.funk.language.interpreter.FunkFrac && right is com.funk.language.interpreter.FunkFrac ->
                                FunkBool(left.numerator * right.denominator < right.numerator * left.denominator)
                            else -> throw RuntimeException("Cannot compare ${left::class.simpleName} < ${right::class.simpleName}")
                        }
                    }
                    "greaterThan" -> {
                        when {
                            left is FunkInt && right is FunkInt -> FunkBool(left.value > right.value)
                            left is FunkFloat && right is FunkFloat -> FunkBool(left.value > right.value)
                            left is com.funk.language.interpreter.FunkNat && right is com.funk.language.interpreter.FunkNat ->
                                FunkBool(left.value > right.value)
                            left is com.funk.language.interpreter.FunkFrac && right is com.funk.language.interpreter.FunkFrac ->
                                FunkBool(left.numerator * right.denominator > right.numerator * left.denominator)
                            else -> throw RuntimeException("Cannot compare ${left::class.simpleName} > ${right::class.simpleName}")
                        }
                    }
                    "lessEqual" -> {
                        when {
                            left is FunkInt && right is FunkInt -> FunkBool(left.value <= right.value)
                            left is FunkFloat && right is FunkFloat -> FunkBool(left.value <= right.value)
                            left is com.funk.language.interpreter.FunkNat && right is com.funk.language.interpreter.FunkNat ->
                                FunkBool(left.value <= right.value)
                            left is com.funk.language.interpreter.FunkFrac && right is com.funk.language.interpreter.FunkFrac ->
                                FunkBool(left.numerator * right.denominator <= right.numerator * left.denominator)
                            else -> throw RuntimeException("Cannot compare ${left::class.simpleName} <= ${right::class.simpleName}")
                        }
                    }
                    "greaterEqual" -> {
                        when {
                            left is FunkInt && right is FunkInt -> FunkBool(left.value >= right.value)
                            left is FunkFloat && right is FunkFloat -> FunkBool(left.value >= right.value)
                            left is com.funk.language.interpreter.FunkNat && right is com.funk.language.interpreter.FunkNat ->
                                FunkBool(left.value >= right.value)
                            left is com.funk.language.interpreter.FunkFrac && right is com.funk.language.interpreter.FunkFrac ->
                                FunkBool(left.numerator * right.denominator >= right.numerator * left.denominator)
                            else -> throw RuntimeException("Cannot compare ${left::class.simpleName} >= ${right::class.simpleName}")
                        }
                    }
                    // Logical operations
                    "and" -> {
                        when {
                            left is FunkBool && right is FunkBool -> FunkBool(left.value && right.value)
                            else -> throw RuntimeException("Cannot apply logical and to ${left::class.simpleName} and ${right::class.simpleName}")
                        }
                    }
                    "or" -> {
                        when {
                            left is FunkBool && right is FunkBool -> FunkBool(left.value || right.value)
                            else -> throw RuntimeException("Cannot apply logical or to ${left::class.simpleName} and ${right::class.simpleName}")
                        }
                    }
                    else -> throw RuntimeException("Unsupported binary operation: $operation")
                }
            }
        }
    }

    /**
     * Structural equality check for Funk values
     * Handles primitives, records, variants, and nested structures
     */
    private fun structuralEquals(left: FunkValue, right: FunkValue): Boolean {
        return when {
            // Primitive types
            left is FunkInt && right is FunkInt -> left.value == right.value
            left is FunkFloat && right is FunkFloat -> left.value == right.value
            left is FunkString && right is FunkString -> left.value == right.value
            left is FunkBool && right is FunkBool -> left.value == right.value
            left is com.funk.language.interpreter.FunkNat && right is com.funk.language.interpreter.FunkNat -> left.value == right.value

            // Fractions - compare using cross multiplication: a/b == c/d iff a*d == b*c
            left is com.funk.language.interpreter.FunkFrac && right is com.funk.language.interpreter.FunkFrac ->
                left.numerator * right.denominator == right.numerator * left.denominator

            // Records - check all fields structurally
            left is FunkRecord && right is FunkRecord -> {
                if (left.fields.keys != right.fields.keys) {
                    false // Different field sets
                } else {
                    // Check each field recursively
                    left.fields.all { (key, leftValue) ->
                        val rightValue = right.fields[key]
                        rightValue != null && structuralEquals(leftValue, rightValue)
                    }
                }
            }

            // Variants - check constructor and data
            left is com.funk.language.interpreter.FunkVariant && right is com.funk.language.interpreter.FunkVariant -> {
                if (left.constructor != right.constructor) {
                    false // Different constructors
                } else {
                    // Check data values recursively
                    when {
                        left.value == null && right.value == null -> true
                        left.value != null && right.value != null -> structuralEquals(left.value, right.value)
                        else -> false // One has data, other doesn't
                    }
                }
            }

            // Different types are never equal
            else -> false
        }
    }

    /**
     * Convert a monomorphic function call to a direct call node
     * Rust-style: Look up the mangled function name directly
     */
    private fun convertMonomorphicFunctionCall(funcCall: FunctionCall): FunkNode {
        val monomorphicName = funcCall.resolvedFunctionName!!
        println("DEBUG convertMonomorphicFunctionCall: Looking up monomorphic function: $monomorphicName")

        // Look up the monomorphic function by its mangled name
        // Example: square$Int instead of square
        val slot = getOrCreateSlot(monomorphicName)
        val functionNode = SimpleReadVariableNode(slot, monomorphicName)

        val argumentsNode = convertExpression(funcCall.arguments)
        return createFunctionCallNode(functionNode, argumentsNode)
    }
    
    /**
     * Convert a lambda expression to a function definition node
     */
    private fun convertLambdaExpression(lambda: LambdaExpression): FunkNode {
        val paramNames = when (val params = lambda.parameters) {
            is UntypedParameters -> params.names
            is TypedParameters -> params.recordType.entries.map { it.name }
        }

        // Detect free variables (variables used in body but not parameters)
        val allVariables = collectVariableReferences(lambda.body)
        val freeVariables = allVariables.filter { it !in paramNames }.distinct()
        println("DEBUG convertLambdaExpression: Found free variables: $freeVariables in lambda with params $paramNames")

        // Create a new converter for the function body to manage its own frame
        val functionConverter = ASTToTruffleConverter()

        // Pre-register parameter names in the function's frame
        paramNames.forEach { paramName ->
            functionConverter.getOrCreateSlot(paramName)
        }

        // Pre-register closure variables in the function's frame (after parameters)
        freeVariables.forEach { varName ->
            functionConverter.getOrCreateSlot(varName)
        }

        val bodyNode = functionConverter.convertExpression(lambda.body)
        val functionFrameDescriptor = functionConverter.getFrameDescriptor()

        // Pass closure variable names to function definition node
        return FunctionDefinitionNode("lambda", paramNames, bodyNode, functionFrameDescriptor, freeVariables)
    }

    /**
     * Collect all variable references in an expression
     * Returns a list of variable names (may contain duplicates)
     */
    private fun collectVariableReferences(expr: Expression): List<String> {
        val variables = mutableListOf<String>()

        fun visit(e: Expression) {
            when (e) {
                is Identifier -> variables.add(e.name)
                is IntLiteral, is NatLiteral, is FloatLiteral, is FractionLiteral,
                is StringLiteral, is BooleanLiteral -> Unit // Literals don't reference variables
                is IfExpression -> {
                    visit(e.condition)
                    visit(e.thenBranch)
                    visit(e.elseBranch)
                    Unit
                }
                is MatchExpression -> {
                    visit(e.expression)
                    e.cases.forEach { case ->
                        visit(case.expression)
                        case.guard?.let { visit(it) }
                    }
                    Unit
                }
                is FunctionCall -> {
                    visit(e.function)
                    visit(e.arguments)
                    Unit
                }
                is LambdaExpression -> {
                    // Don't traverse into nested lambdas - they have their own scope
                    // We only want free variables at this level
                    Unit
                }
                is RecordLiteral -> {
                    e.getFieldEntries().forEach { entry ->
                        entry.value?.let { visit(it) }
                    }
                    Unit
                }
                is ArrayLiteral -> {
                    e.elements.forEach { visit(it) }
                    Unit
                }
                is ArrayAccess -> {
                    visit(e.array)
                    visit(e.index)
                    Unit
                }
                is MemberAccess -> {
                    // Only visit the receiver if it's not a builtin ability name
                    // Ability names like NumMul, NumAdd, Ord should not be captured as closure variables
                    if (e.receiver is Identifier) {
                        val receiverName = (e.receiver as Identifier).name
                        val builtinAbilities = listOf("NumAdd", "NumSub", "NumMul", "NumDiv", "NumMod",
                                                      "Eq", "Ord", "LogicalAnd", "LogicalOr", "LogicalNot")
                        if (receiverName !in builtinAbilities) {
                            // This is a regular variable, capture it
                            visit(e.receiver)
                        }
                        // Otherwise skip - it's an ability name
                    } else {
                        // Receiver is not an identifier (might be a complex expression)
                        visit(e.receiver)
                    }
                    Unit
                }
                is VariantConstruction -> {
                    e.value?.let { visit(it) }
                    Unit
                }
                is DoExpression -> {
                    e.statements.forEach { stmt ->
                        when (stmt) {
                            is LetStatement -> visit(stmt.expression)
                            is ExpressionStatement -> visit(stmt.expression)
                        }
                    }
                    Unit
                }
                is PerformExpression -> {
                    visit(e.expression)
                    Unit
                }
                else -> Unit // Other expression types
            }
        }

        visit(expr)
        return variables
    }
    
    /**
     * Convert a record literal to a record construction node
     */
    private fun convertRecordLiteral(recordLit: RecordLiteral): FunkNode {
        val fieldNodes = mutableMapOf<String, FunkNode>()
        val spreadNodes = mutableListOf<FunkNode>()

        // Handle spread entries first
        for (spreadEntry in recordLit.getSpreadEntries()) {
            // Convert the spread expression to a node
            val spreadNode = convertExpression(spreadEntry.expression)
            spreadNodes.add(spreadNode)
        }

        // Handle field entries
        for (entry in recordLit.getFieldEntries()) {
            fieldNodes[entry.name] = entry.value?.let { convertExpression(it) } ?: NullLiteralNode()
        }

        // If there are spread entries, create a node that merges records at runtime
        if (spreadNodes.isNotEmpty()) {
            return createRecordSpreadNode(spreadNodes, fieldNodes)
        }

        return RecordLiteralNode(fieldNodes)
    }

    /**
     * Create a node that handles record spread syntax
     * Example: {...person, age = 26} merges person fields with the age override
     */
    private fun createRecordSpreadNode(
        spreadNodes: List<FunkNode>,
        fieldNodes: Map<String, FunkNode>
    ): FunkNode {
        return object : FunkNode() {
            @field:Children private val spreadChildren = spreadNodes.toTypedArray()
            private val fieldChildren = fieldNodes.mapValues { (_, node) -> node }

            override fun execute(frame: com.oracle.truffle.api.frame.VirtualFrame): FunkValue {
                // Start with an empty map
                val mergedFields = mutableMapOf<String, FunkValue>()

                // Merge all spread records
                for (spreadChild in spreadChildren) {
                    val spreadValue = spreadChild.execute(frame)
                    if (spreadValue !is FunkRecord) {
                        throw RuntimeException("Cannot spread non-record value: ${spreadValue::class.simpleName}")
                    }
                    // Add all fields from the spread record
                    mergedFields.putAll(spreadValue.fields)
                }

                // Override/add explicit field values
                for ((fieldName, fieldNode) in fieldChildren) {
                    mergedFields[fieldName] = fieldNode.execute(frame)
                }

                return FunkRecord(mergedFields)
            }
        }
    }
    
    /**
     * Convert an Array literal to an Array construction node
     */
    private fun convertArrayLiteral(arrayLit: ArrayLiteral): FunkNode {
        val elementNodes = arrayLit.elements.map { convertExpression(it) }.toTypedArray()
        return ArrayLiteralNode(elementNodes)
    }
    
    /**
     * Convert array access to an array access node
     */
    private fun convertArrayAccess(arrayAccess: ArrayAccess): FunkNode {
        val arrayNode = convertExpression(arrayAccess.array)
        val indexNode = convertExpression(arrayAccess.index)
        return ArrayAccessNode(arrayNode, indexNode)
    }
    
    /**
     * Convert member access to a member access node
     * Handles both module-qualified names like int::add and record field access like person.name
     *
     * The distinction is made based on the inferredType of the receiver:
     * - If receiver has a RecordType, it's record field access (person.name)
     * - If receiver has a ModuleType or is untyped, it might be a module-qualified name (int::add)
     *
     * However, in practice, module-qualified names should have been resolved during type checking.
     * So we default to record field access for runtime safety.
     */
    private fun convertMemberAccess(memberAccess: MemberAccess): FunkNode {
        val receiverExpr = memberAccess.receiver
        val memberName = memberAccess.member

        // Check if the receiver has type information that indicates it's a record
        val receiverType = receiverExpr.inferredType

        // If receiver is an Identifier AND has no type (or is not a record type),
        // it might be a module-qualified name like int::add
        // But we need to be careful not to confuse variables with modules
        if (receiverExpr is Identifier && receiverType == null) {
            // This might be a module-qualified name
            // Create BOTH a module-qualified lookup AND a record field access fallback
            val moduleName = receiverExpr.name
            val qualifiedName = "$moduleName::$memberName"

            // Try to resolve as a module-qualified name first
            val slot = getOrCreateSlot(qualifiedName)
            val moduleQualifiedNode = SimpleReadVariableNode(slot, qualifiedName)

            // But also create a record field access fallback
            // If the module-qualified lookup returns FunkNull, we'll try record access
            // Actually, we can't create a fallback node easily, so let's just use record access
            // Module-qualified names should have been resolved during type checking anyway
            // Fall through to record field access
        }

        // Default: treat as record field access
        // This handles person.name, myRecord.field, etc.
        val receiverNode = convertExpression(receiverExpr)
        return RecordFieldAccessNode(receiverNode, memberName)
    }
    
    /**
     * Convert variant construction to a variant construction node
     */
    private fun convertVariantConstruction(variantConst: VariantConstruction): FunkNode {
        val constructorName = variantConst.constructor
        val valueNode = variantConst.value?.let { convertExpression(it) }
        return com.funk.language.interpreter.nodes.literal.VariantConstructionNode(constructorName, valueNode)
    }
    
    /**
     * Convert do expression to a parallel-aware node with runtime parallelism detection
     */
    private fun convertDoExpression(doExpr: DoExpression): FunkNode {
        // Use the factory to create the most appropriate do-expression node
        return com.funk.language.interpreter.nodes.control.DoExpressionNodeFactory
            .createDoExpressionNode(doExpr, this)
    }
    
    /**
     * Convert a statement to a Truffle node
     * Supports pattern destructuring in let statements
     */
    private fun convertStatement(stmt: Statement): FunkNode {
        return when (stmt) {
            is LetStatement -> {
                // Use the same pattern conversion logic as LetBinding
                when (val pattern = stmt.pattern) {
                    is VariablePattern -> {
                        val slot = getOrCreateSlot(pattern.name)
                        val valueNode = convertExpression(stmt.expression)
                        SimpleWriteVariableNode(slot, valueNode, pattern.name)
                    }
                    is RecordPattern -> {
                        convertRecordPatternBinding(pattern, stmt.expression)
                    }
                    is WildcardPattern -> {
                        convertExpression(stmt.expression)
                    }
                    else -> throw RuntimeException("Unsupported pattern in let statement: ${pattern::class.simpleName}")
                }
            }
            is ExpressionStatement -> convertExpression(stmt.expression)
            else -> throw RuntimeException("Unsupported statement: ${stmt::class.simpleName}")
        }
    }
    
    /**
     * Convert perform expression to a PerformNode that manages effect handler stack
     */
    private fun convertPerformExpression(performExpr: PerformExpression): FunkNode {
        System.err.println("DEBUG convertPerformExpression: Starting conversion, expression type=${performExpr.expression::class.simpleName}")
        System.err.println("DEBUG convertPerformExpression: resolvedHandlers=${performExpr.resolvedHandlers}")
        System.err.println("DEBUG convertPerformExpression: handlers=${performExpr.handlers.map { it.name }}")

        // Debug the inner expression if it's a FunctionCall
        if (performExpr.expression is FunctionCall) {
            val funcCall = performExpr.expression as FunctionCall
            System.err.println("DEBUG convertPerformExpression: Inner expression is FunctionCall")
            System.err.println("DEBUG convertPerformExpression:   function type=${funcCall.function::class.simpleName}")
            if (funcCall.function is MemberAccess) {
                val memberAccess = funcCall.function as MemberAccess
                System.err.println("DEBUG convertPerformExpression:   MemberAccess receiver=${memberAccess.receiver::class.simpleName}")
                if (memberAccess.receiver is Identifier) {
                    System.err.println("DEBUG convertPerformExpression:   Receiver name=${(memberAccess.receiver as Identifier).name}")
                    System.err.println("DEBUG convertPerformExpression:   Member name=${memberAccess.member}")
                }
            }
            System.err.println("DEBUG convertPerformExpression:   resolvedAbilities=${funcCall.resolvedAbilities.size}")
        }

        // Convert the inner expression that will be executed with handlers
        System.err.println("DEBUG convertPerformExpression: About to call convertExpression on inner expression")
        val expressionNode = convertExpression(performExpr.expression)
        println("DEBUG convertPerformExpression: Converted inner expression, node type=${expressionNode::class.simpleName}")

        // Build a map of effect handlers
        // The handlers in performExpr are handler names like "NativeConsole"
        // We need to look up the actual handler implementations (which are FunkRecords containing effect operations)
        val handlersMap = mutableMapOf<String, FunkValue>()

        // For each handler, we need to:
        // 1. Look up the handler by name (e.g., "NativeConsole")
        // 2. Find which effect it implements (from resolvedHandlers)
        // 3. Add it to the map with the effect name as key
        for (resolvedHandler in performExpr.resolvedHandlers.values) {
            val effectName = resolvedHandler.effectName
            val performerName = resolvedHandler.performerName

            // Create a node that will look up the handler at runtime
            // For now, we'll just store the performer name and look it up when PerformNode executes
            // This is a simplified approach - in production we'd want to resolve this at compile time

            // We'll create the handlers map at runtime, so we need a special node
            // that evaluates the handlers and then executes with them
            // Actually, PerformNode expects a Map<String, FunkValue>, but we need to look up
            // the values at runtime. So we need to create a node that does the lookup.
        }

        // Capture the mapping for use at runtime
        val capturedMapping = handlerToEffectMapping.toMap()

        // For now, create a simplified PerformNode that looks up handlers at runtime
        return object : com.funk.language.interpreter.nodes.FunkNode() {
            @field:Child private var exprChild = expressionNode

            override fun execute(frame: com.oracle.truffle.api.frame.VirtualFrame): FunkValue {
                // Build the handlers map by looking up each performer in the context
                val runtimeHandlers = mutableMapOf<String, FunkValue>()
                val context = getContext()

                println("DEBUG PerformNode.execute: performExpr.resolvedHandlers=${performExpr.resolvedHandlers}")
                println("DEBUG PerformNode.execute: performExpr.handlers=${performExpr.handlers.map { it.name }}")

                // If resolvedHandlers is populated, use it
                if (performExpr.resolvedHandlers.isNotEmpty()) {
                    for (resolvedHandler in performExpr.resolvedHandlers.values) {
                        val effectName = resolvedHandler.effectName
                        val performerName = resolvedHandler.performerName
                        val implRef = resolvedHandler.implDeclarationRef

                        println("DEBUG PerformNode.execute: Using resolvedHandler: effectName=$effectName, performerName=$performerName, implRef=$implRef")

                        val lookupName = implRef ?: performerName
                        val performerValue = context.lookupGlobal(lookupName)
                            ?: throw RuntimeException("Handler implementation not found: $lookupName (effect: $effectName, performer: $performerName)")

                        runtimeHandlers[effectName] = performerValue
                    }
                } else {
                    // Fallback: Use the handlerToEffectMapping we built during conversion
                    println("DEBUG PerformNode.execute: resolvedHandlers is empty, using capturedMapping")
                    println("DEBUG PerformNode.execute: capturedMapping keys=${capturedMapping.keys}")
                    for (handler in performExpr.handlers) {
                        val handlerName = handler.name
                        println("DEBUG PerformNode.execute: Looking up handler '$handlerName' in mapping")

                        // Look up the effect name and implementation name from our mapping
                        val mapping = capturedMapping[handlerName]
                        if (mapping == null) {
                            throw RuntimeException("Handler '$handlerName' not found in effect mapping. Available handlers: ${capturedMapping.keys}")
                        }

                        val (effectName, implName) = mapping
                        println("DEBUG PerformNode.execute: Handler '$handlerName' implements effect '$effectName', impl '$implName'")

                        // Look up the implementation record
                        val performerValue = context.lookupGlobal(implName)
                            ?: throw RuntimeException("Handler implementation not found: $implName for handler $handlerName")

                        println("DEBUG PerformNode.execute: Found handler value of type: ${performerValue::class.simpleName}")

                        // Store the handler under the effect name
                        runtimeHandlers[effectName] = performerValue
                    }
                }

                // Use the shared effect handler from context
                val effectHandler = context.effectHandler
                val handlersRecord = FunkRecord(runtimeHandlers)

                // Push handlers onto stack
                effectHandler.pushHandlers(handlersRecord)

                try {
                    return exprChild.execute(frame)
                } finally {
                    // Always pop handlers, even if exception occurs
                    effectHandler.popHandlers()
                }
            }
        }
    }
    
    /**
     * Get or create a frame slot for a variable
     */
    fun getOrCreateSlot(variableName: String): Int {
        return variableSlots.getOrPut(variableName) {
            val slotIndex = variableSlots.size
            frameDescriptorBuilder.addSlot(com.oracle.truffle.api.frame.FrameSlotKind.Object, variableName, null)
            slotIndex
        }
    }
    
    /**
     * Get the frame descriptor for this converter
     */
    fun getFrameDescriptor(): FrameDescriptor = frameDescriptorBuilder.build()

    /**
     * Get the variable slots mapping
     */
    fun getVariableSlots(): Map<String, Int> = variableSlots.toMap()

    // Factory methods for creating specialized nodes
    
    private fun createAddNode(left: FunkNode, right: FunkNode): FunkNode {
        return AddNode(left, right)
    }
    
    private fun createSubtractNode(left: FunkNode, right: FunkNode): FunkNode {
        return SubtractNode(left, right)
    }
    
    private fun createMultiplyNode(left: FunkNode, right: FunkNode): FunkNode {
        return MultiplyNode(left, right)
    }
    
    private fun createDivideNode(left: FunkNode, right: FunkNode): FunkNode {
        return DivideNode(left, right)
    }
    
    private fun createEqualNode(left: FunkNode, right: FunkNode): FunkNode {
        return EqualNode(left, right)
    }
    
    private fun createLessThanNode(left: FunkNode, right: FunkNode): FunkNode {
        return LessThanNode(left, right)
    }
    
    private fun createGreaterThanNode(left: FunkNode, right: FunkNode): FunkNode {
        return GreaterThanNode(left, right)
    }
    
    private fun createLessEqualNode(left: FunkNode, right: FunkNode): FunkNode {
        return LessEqualNode(left, right)
    }
    
    private fun createGreaterEqualNode(left: FunkNode, right: FunkNode): FunkNode {
        return GreaterEqualNode(left, right)
    }
    
    private fun createNegateNode(operand: FunkNode): FunkNode {
        // Simple negation implementation
        return object : FunkNode() {
            @field:Child private var operandChild = operand
            
            override fun execute(frame: com.oracle.truffle.api.frame.VirtualFrame): FunkValue {
                val value = operandChild.execute(frame)
                return when (value) {
                    is FunkInt -> FunkInt(-value.value)
                    is FunkFloat -> com.funk.language.interpreter.FunkFloat(-value.value)
                    else -> throw RuntimeException("Cannot negate ${value::class.simpleName}")
                }
            }
        }
    }
    
    private fun createNotNode(operand: FunkNode): FunkNode {
        // Simple logical not implementation
        return object : FunkNode() {
            @field:Child private var operandChild = operand
            
            override fun execute(frame: com.oracle.truffle.api.frame.VirtualFrame): FunkValue {
                val value = operandChild.execute(frame)
                return when (value) {
                    is FunkBool -> FunkBool(!value.value)
                    else -> throw RuntimeException("Cannot apply logical not to ${value::class.simpleName}")
                }
            }
        }
    }
    
    private fun createPatternMatchNode(valueNode: FunkNode, caseNodes: Array<PatternCaseNode>): FunkNode {
        return PatternMatchNode(valueNode, caseNodes)
    }
    
    private fun createPatternCaseNode(patterns: Array<PatternNode>, guardNode: FunkNode?, bodyNode: FunkNode): PatternCaseNode {
        return PatternCaseNode(patterns, guardNode, bodyNode)
    }
    
    private fun createFunctionCallNode(functionNode: FunkNode, argumentsNode: FunkNode): FunkNode {
        return FunctionCallNode(functionNode, argumentsNode)
    }
}