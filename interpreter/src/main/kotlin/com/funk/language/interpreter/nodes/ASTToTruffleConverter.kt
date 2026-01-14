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
    private val projectRoot: java.nio.file.Path? = null,
    parentHandlerMapping: Map<String, Pair<String, String>>? = null,
    private val parentVariableSlots: Map<String, Int>? = null
) {

    private var frameDescriptorBuilder = FrameDescriptor.newBuilder()
    private val variableSlots = mutableMapOf<String, Int>()

    /**
     * Map of handler name → (effect name, implementation name)
     * e.g., "NativeRandom" → ("Random", "Random$NativeRandom")
     * Initialized with parent mapping if provided (for nested converters in lambdas)
     */
    private val handlerToEffectMapping = mutableMapOf<String, Pair<String, String>>().apply {
        if (parentHandlerMapping != null) {
            putAll(parentHandlerMapping)
        }
    }

    /**
     * Effect declarations collected during conversion
     * These will be registered with the effect handler during execution
     */
    private val effectDeclarations = mutableListOf<EffectDeclaration>()
    
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
            is UseDeclaration -> convertUseDeclaration(item)
            is PubUseDeclaration -> convertPubUseDeclaration(item)
            else -> throw RuntimeException("Unsupported program item: ${item::class.simpleName}")
        }
    }

    /**
     * Store an effect declaration for registration during execution
     * The context doesn't exist during parse time, so we collect declarations
     * and register them when execution starts
     */
    private fun registerEffectDeclaration(effectDecl: EffectDeclaration) {
        // Store the declaration for later registration
        effectDeclarations.add(effectDecl)

        // If we have a context (e.g., during module loading), register immediately
        if (context != null) {
            // Create a record with all the operations from the effect declaration
            // Each operation gets a FunkNull placeholder - the actual implementations
            // come from ImplDeclaration records
            val operationsMap = effectDecl.operations.associate { op ->
                op.name to com.funk.language.interpreter.FunkNull
            }
            val effectRecord = FunkRecord(operationsMap)
            context.effectHandler?.registerEffect(effectDecl.name, effectRecord)
        }
    }

    /**
     * Convert a use declaration to runtime bindings
     * This makes imported values available in the current module's scope
     */
    private fun convertUseDeclaration(useDecl: UseDeclaration): FunkNode {
        return when (val usePath = useDecl.path) {
            is SelectivePath -> {
                // Selective import like: use std::effects::continuation::{Continuation, Delimited}
                // Extract the import items and bind each one
                val bindingNodes = usePath.items.map { importItem ->
                    val importedName = importItem.name
                    val localName = importItem.alias ?: importedName
                    val slot = getOrCreateSlot(localName)

                    object : FunkNode() {
                        override fun execute(frame: com.oracle.truffle.api.frame.VirtualFrame): FunkValue {
                            val context = getContext()

                            // Look up the imported value from global context
                            val value = context.lookupGlobal(importedName)
                                ?: com.funk.language.interpreter.FunkNull

                            // Bind it in the current frame
                            frame.setObject(slot, value)

                            // CRITICAL: Define in both locals AND globals
                            // This ensures module imports are accessible to lambdas in impl declarations
                            // which execute in different contexts and need global access to imports
                            context.defineLocal(localName, value)
                            context.defineGlobal(localName, value)

                            return com.funk.language.interpreter.FunkNull
                        }
                    }
                }

                if (bindingNodes.isEmpty()) {
                    NullLiteralNode()
                } else {
                    SequenceNode(bindingNodes.toTypedArray())
                }
            }
            is ModulePath -> {
                // Module-level import like: use std::collections::array
                // For now, this doesn't require runtime binding since module functions
                // are already registered globally during module loading
                NullLiteralNode()
            }
        }
    }

    /**
     * Convert a pub use declaration to runtime bindings
     */
    private fun convertPubUseDeclaration(pubUse: PubUseDeclaration): FunkNode {
        // PubUseDeclaration has the same structure as UseDeclaration
        // We can create an equivalent UseDeclaration and convert it
        val equiv = UseDeclaration(pubUse.path, pubUse.alias)
        return convertUseDeclaration(equiv)
    }

    /**
     * Convert an effect implementation (ImplDeclaration) to a record containing the operations
     * The record is stored with a mangled name like "Random$NativeRandom"
     * AND also bound to the performer name for imports
     */
    private fun convertImplDeclaration(impl: ImplDeclaration): FunkNode {
        // If the implementation has parameters, we need to create a FUNCTION
        // that takes those parameters and returns a handler record
        // Example: impl Choice for AllPaths { cont: Continuation }
        //   becomes: (params) -> { choose = ..., fail = ... }

        if (impl.performerParams.isNotEmpty()) {
            // Create a wrapper function that takes parameters and returns the handler record
            return convertParameterizedImplDeclaration(impl)
        }

        // No parameters - create a direct record
        val fieldNodes = mutableMapOf<String, FunkNode>()

        // Each operation becomes a field in the record
        for (op in impl.operations) {
            fieldNodes[op.name] = convertExpression(op.implementation)
        }

        // Create the implementation record
        val recordNode = RecordLiteralNode(fieldNodes)

        // Create a mangled name for the implementation like "Random$NativeRandom"
        val implName = "${impl.effectName}\$${impl.performerName}"

        // CRITICAL: Store the mapping so we can look up handlers by performer name
        // This allows "perform NativeRandom in ..." to find the Random effect implementation
        handlerToEffectMapping[impl.performerName] = impl.effectName to implName

        // CRITICAL: Register the performer globally so effect calls can find it
        // This allows "Log.println" to find the "Console" performer
        if (context != null) {
            context.effectHandler?.registerPerformer(impl.performerName, impl.effectName)
        }

        // Create TWO bindings:
        // 1. Mangled name (for perform expressions)
        val mangledSlot = getOrCreateSlot(implName)
        val mangledBinding = SimpleWriteVariableNode(mangledSlot, recordNode, implName)

        // 2. Performer name (for imports like: use module::{PerformerName})
        //    This reads from the mangled slot and writes to the performer slot
        val performerSlot = getOrCreateSlot(impl.performerName)
        val performerBinding = object : FunkNode() {
            override fun execute(frame: com.oracle.truffle.api.frame.VirtualFrame): FunkValue {
                // Read the value from the mangled slot
                val value = frame.getObject(mangledSlot) as FunkValue

                // Write it to the performer slot
                frame.setObject(performerSlot, value)

                // Also define in context for global lookups
                val context = getContext()
                context.defineLocal(impl.performerName, value)

                // Register the performer at runtime too (for hot-reloading scenarios)
                context.effectHandler?.registerPerformer(impl.performerName, impl.effectName)

                return value
            }
        }

        // Return a sequence that executes both bindings
        return SequenceNode(arrayOf(mangledBinding, performerBinding))
    }

    /**
     * Convert a parameterized ImplDeclaration to a function that takes parameters
     * and returns a handler record with operations bound to those parameters.
     *
     * Example: impl Choice for AllPaths { cont: Continuation } where { ... }
     * Creates: {cont} -> { choose = {options} -> cont.fork {...}, fail = {} -> cont.abort {} }
     */
    private fun convertParameterizedImplDeclaration(impl: ImplDeclaration): FunkNode {
        // Create mangled names for storage
        val implName = "${impl.effectName}\$${impl.performerName}"

        // CRITICAL: Register the handler mapping so perform expressions can find it
        handlerToEffectMapping[impl.performerName] = impl.effectName to implName

        // CRITICAL: Register the performer with the effect handler
        if (context != null) {
            context.effectHandler?.registerPerformer(impl.performerName, impl.effectName)
        }

        // Pre-convert operations with parameters as free variables
        // Each operation will capture impl parameters (like 'cont') as closure variables
        val operationConverters = mutableListOf<Pair<String, ASTToTruffleConverter>>()
        val operationNodes = mutableMapOf<String, FunctionDefinitionNode>()

        for (op in impl.operations) {
            // Create a converter for this operation
            val opConverter = ASTToTruffleConverter(
                context = this.context,
                projectRoot = this.projectRoot,
                parentHandlerMapping = this.handlerToEffectMapping,
                parentVariableSlots = this.variableSlots.toMap()
            )

            // The operation is a lambda like: { options } -> cont.fork { ... }
            // We need to detect that 'cont' is a free variable (not a parameter)
            val opExpr = op.implementation
            if (opExpr !is LambdaExpression) {
                throw RuntimeException("Operation implementation must be a lambda for parameterized impl")
            }

            // Get operation parameter names
            val opParamNames = when (val params = opExpr.parameters) {
                is UntypedParameters -> params.names
                is TypedParameters -> params.recordType.entries.map { it.name }
            }

            // Detect free variables in the operation body
            // These will include impl parameters like 'cont'
            val allVars = collectVariableReferences(opExpr.body)
            val freeVars = allVars.filter { it !in opParamNames }.distinct()

            // Pre-register operation parameters
            opParamNames.forEach { opConverter.getOrCreateSlot(it) }

            // Pre-register free variables (impl parameters will be here)
            freeVars.forEach { opConverter.getOrCreateSlot(it) }

            // Convert the operation body
            val bodyNode = opConverter.convertExpression(opExpr.body)
            val frameDesc = opConverter.getFrameDescriptor()

            // Create slots mapping for closure variables
            val closureSlots = freeVars.mapNotNull { varName ->
                val slot = opConverter.variableSlots[varName]
                if (slot != null) varName to slot else null
            }.toMap()

            // Create the function definition node
            val funcNode = FunctionDefinitionNode(
                name = "${impl.performerName}_${op.name}",
                parameterNames = opParamNames,
                bodyNode = bodyNode,
                frameDescriptor = frameDesc,
                closureVariableNames = freeVars,
                closureVariableSlots = closureSlots
            )

            operationNodes[op.name] = funcNode
            operationConverters.add(op.name to opConverter)
        }

        // Create a node that builds the handler record with operations bound to parameters
        val wrapperNode = object : FunkNode() {
            override fun execute(frame: com.oracle.truffle.api.frame.VirtualFrame): FunkValue {
                // This creates a function that takes impl parameters and returns a handler record
                return com.funk.language.interpreter.NativeFunkFunction("impl_${impl.performerName}") { args ->
                    if (args.isEmpty() || args[0] !is FunkRecord) {
                        throw RuntimeException("Wrapper function expects a record parameter")
                    }

                    val paramsRecord = args[0] as FunkRecord

                    // Execute each operation function node to create FunkTruffleFunction instances
                    // with impl parameters captured as closure variables
                    val operationFields = mutableMapOf<String, FunkValue>()
                    try {
                        for ((opName, funcNode) in operationNodes) {
                            // Create a frame with 2-element arguments array per Truffle convention
                            val converter = operationConverters.find { it.first == opName }!!.second
                            val frameDesc = converter.getFrameDescriptor()
                            val args = arrayOf<Any?>(FunkRecord(emptyMap()), emptyMap<String, FunkValue>())
                            val tempFrame = com.oracle.truffle.api.Truffle.getRuntime()
                                .createMaterializedFrame(args, frameDesc)

                            // Write impl parameters to the frame so they can be captured as closures
                            for ((paramName, paramValue) in paramsRecord.fields) {
                                val slot = converter.variableSlots[paramName]
                                if (slot != null) {
                                    tempFrame.setObject(slot, paramValue)
                                }
                            }

                            // Execute the function definition to get a FunkTruffleFunction
                            // with parameters captured
                            val opFunc = funcNode.execute(tempFrame)
                            operationFields[opName] = opFunc
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        throw RuntimeException("Failed to create parameterized handler operations: ${e.message}", e)
                    }

                    FunkRecord(operationFields)
                }
            }
        }

        // Store the wrapper function with the mangled name
        val mangledSlot = getOrCreateSlot(implName)
        val mangledBinding = SimpleWriteVariableNode(mangledSlot, wrapperNode, implName)

        // Also store under performer name for imports
        val performerSlot = getOrCreateSlot(impl.performerName)
        val performerBinding = object : FunkNode() {
            override fun execute(frame: com.oracle.truffle.api.frame.VirtualFrame): FunkValue {
                val value = frame.getObject(mangledSlot) as FunkValue
                frame.setObject(performerSlot, value)

                val context = getContext()
                context.defineLocal(impl.performerName, value)
                context.effectHandler?.registerPerformer(impl.performerName, impl.effectName)

                return value
            }
        }

        return SequenceNode(arrayOf(mangledBinding, performerBinding))
    }

    /**
     * Convert an impl ability declaration to a record containing the implementations
     * The record is stored with a mangled name like "Show$Int" (using $ to avoid user namespace conflicts)
     */
    private fun convertImplAbilityDeclaration(impl: ImplAbilityDeclaration): FunkNode {
        val fieldNodes = mutableMapOf<String, FunkNode>()

        // Each operation becomes a field in the record
        for (op in impl.operations) {
            fieldNodes[op.name] = convertExpression(op.implementation)
        }

        // Create the implementation record
        val recordNode = RecordLiteralNode(fieldNodes)

        // Create a mangled name using $ to prevent user namespace conflicts: "Show$Int"
        // Dollar signs are reserved for compiler/runtime use and cannot appear in user identifiers
        val targetTypeNames = impl.targetTypes.joinToString("$") { typeAnnotation ->
            // Simple type name extraction - just use the type annotation's name
            when (typeAnnotation) {
                is IdentifierTypeAnnotation -> typeAnnotation.name
                else -> typeAnnotation.toString()
            }
        }
        val implName = "${impl.abilityName}\$$targetTypeNames"

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
        println("DEBUG convertExpression: expr class = ${expr::class.simpleName}")
        if (expr is FunctionCall && expr.function is MemberAccess) {
            val ma = expr.function as MemberAccess
            println("DEBUG FunctionCall with MemberAccess: receiver=${ma.receiver::class.simpleName}, member=${ma.member}")
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
            is StateMachineExpression -> StateMachineNode(expr, this)
            else -> throw RuntimeException("Unsupported expression: ${expr::class.simpleName}")
        }
    }
    
    /**
     * Convert an identifier to a variable read node or global lookup
     */
    private fun convertIdentifier(identifier: Identifier): FunkNode {
        // Special handling for state machine input placeholder
        // This identifier is used by the ContinuationTransformer to access input values
        // passed to continuation states when they are resumed
        if (identifier.name == "__state_machine_input__") {
            if (!variableSlots.containsKey("__state_machine_input__")) {
                getOrCreateSlot("__state_machine_input__")
            }
            val slot = variableSlots["__state_machine_input__"]!!
            return SimpleReadVariableNode(slot, "__state_machine_input__")
        }

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
            is VariablePattern -> {
                val slot = getOrCreateSlot(pattern.name)
                VariablePatternNode(pattern.name, slot)
            }
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
            is ArrayPattern -> {
                // Array pattern matches arrays with specific length and optional rest pattern
                // Examples: [], [x], [x, y], [head, ...tail]
                val elementPatterns = pattern.elements.map { convertPattern(it) }.toTypedArray()
                val restPatternName = pattern.restPattern

                // Pre-allocate slots for element bindings and rest pattern
                val elementSlots = pattern.elements.flatMap { p ->
                    when (p) {
                        is VariablePattern -> listOf(p.name to getOrCreateSlot(p.name))
                        else -> emptyList()
                    }
                }.toMap()

                val restSlot = restPatternName?.let { getOrCreateSlot(it) }

                ArrayPatternNode(elementPatterns, restPatternName, elementSlots, restSlot)
            }
        }
    }
    
    /**
     * Convert a function call to a function call node
     * Uses resolved abilities for direct dispatch when available
     */
    private fun convertFunctionCall(funcCall: FunctionCall): FunkNode {
        val functionClass = funcCall.function::class.simpleName
        println("DEBUG convertFunctionCall: function=${functionClass}, resolvedFunctionName=${funcCall.resolvedFunctionName}, resolvedAbilities=${funcCall.resolvedAbilities.keys}")

        // PRIORITY 1: Check for monomorphic function call (Rust-style)
        // Monomorphic functions are specialized and should be called directly
        if (funcCall.resolvedFunctionName != null) {
            println("DEBUG: Taking monomorphic path")
            return convertMonomorphicFunctionCall(funcCall)
        }

        // PRIORITY 2: Check if this call has resolved abilities (from TypeChecker ability resolution pass)
        // Resolved abilities are compile-time known and take precedence over runtime effect detection
        if (funcCall.resolvedAbilities.isNotEmpty()) {
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

            if (memberAccess.receiver is Identifier) {
                val receiverName = (memberAccess.receiver as Identifier).name
                val operationName = memberAccess.member

                // CRITICAL: Check if the receiver is a local variable/parameter
                // If it's in the frame, it's a regular record field access, not an effect call
                if (variableSlots.containsKey(receiverName)) {
                    println("DEBUG: receiverName '$receiverName' is in variableSlots, taking record field access path")
                    // This is a regular function call on a record field (e.g., cfg.handler)
                    // Use default dispatch
                    val functionNode = convertExpression(funcCall.function)
                    val argumentsNode = convertExpression(funcCall.arguments)
                    println("DEBUG: Creating FunctionCallNode for record field access")
                    return createFunctionCallNode(functionNode, argumentsNode)
                }

                // Create a node that checks at runtime if this is an effect call
                return createDynamicEffectOrFunctionCall(receiverName, operationName, funcCall.arguments)
            } else if (memberAccess.receiver is VariantConstruction) {
                // Effect names are often capitalized and parsed as VariantConstruction
                val receiverName = (memberAccess.receiver as VariantConstruction).constructor
                val operationName = memberAccess.member

                // Create a node that checks at runtime if this is an effect call
                return createDynamicEffectOrFunctionCall(receiverName, operationName, funcCall.arguments)
            } else {
            }
        } else {
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


                // Check if this is a declared effect
                if (effectHandler.isEffectDeclared(receiverName)) {

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

                    // Not an effect - treat as regular member access and function call
                    // This is a fallback for non-effect member access like myRecord.myFunction
                    val receiver = context.lookupGlobal(receiverName)

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
                    // CRITICAL: FunkFunctionRootNode expects 4 arguments:
                    // [0] = args record, [1] = closure variables map (legacy),
                    // [2] = MaterializedFrame (lexical scope), [3] = slot mapping
                    return operation.callTarget.call(
                        arguments,
                        operation.closureVariables,
                        operation.lexicalScope,
                        operation.lexicalScopeSlotMap
                    ) as FunkValue
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

        if (resolvedAbility != null) {
            // Check if it's a builtin or user-defined ability
            if (resolvedAbility.isBuiltin) {
            // For builtin abilities, create specialized arithmetic/comparison nodes
            // Example: NumAdd -> AddNode, NumSub -> SubtractNode, etc.

            // The function should be a MemberAccess like x.add
            if (funcCall.function is MemberAccess) {
                val memberAccess = funcCall.function as MemberAccess
                val operationName = memberAccess.member
                val abilityName = resolvedAbility.abilityName

                // Convert arguments (should be a record like {l = x, r = y})
                val argsNode = convertExpression(funcCall.arguments)

                // Create specialized node based on ability and operation
                // Arithmetic: add, sub, mul, div
                // Comparison: eq, neq, lt, gt, le, ge
                return when {
                    // Arithmetic operators
                    abilityName == "NumAdd" && operationName == "add" -> {
                        createBuiltinBinaryOp("add", argsNode)
                    }
                    abilityName == "NumSub" && operationName == "sub" -> {
                        createBuiltinBinaryOp("subtract", argsNode)
                    }
                    abilityName == "NumSub" && operationName == "neg" -> {
                        createBuiltinUnaryOp("negate", argsNode)
                    }
                    abilityName == "NumMul" && operationName == "mul" -> {
                        createBuiltinBinaryOp("multiply", argsNode)
                    }
                    abilityName == "NumDiv" && operationName == "div" -> {
                        createBuiltinBinaryOp("divide", argsNode)
                    }
                    abilityName == "NumMod" && operationName == "mod" -> {
                        createBuiltinBinaryOp("modulo", argsNode)
                    }
                    // Comparison operators
                    abilityName == "Eq" && operationName == "eq" -> {
                        createBuiltinBinaryOp("equal", argsNode)
                    }
                    abilityName == "Eq" && operationName == "neq" -> {
                        createBuiltinBinaryOp("notEqual", argsNode)
                    }
                    abilityName == "Ord" && operationName == "lt" -> {
                        createBuiltinBinaryOp("lessThan", argsNode)
                    }
                    abilityName == "Ord" && operationName == "gt" -> {
                        createBuiltinBinaryOp("greaterThan", argsNode)
                    }
                    abilityName == "Ord" && operationName == "le" -> {
                        createBuiltinBinaryOp("lessEqual", argsNode)
                    }
                    abilityName == "Ord" && operationName == "ge" -> {
                        createBuiltinBinaryOp("greaterEqual", argsNode)
                    }
                    // Logical operators
                    abilityName == "LogicalAnd" && operationName == "and" -> {
                        createBuiltinBinaryOp("and", argsNode)
                    }
                    abilityName == "LogicalOr" && operationName == "or" -> {
                        createBuiltinBinaryOp("or", argsNode)
                    }
                    abilityName == "LogicalNot" && operationName == "not" -> {
                        createBuiltinUnaryOp("not", argsNode)
                    }
                    else -> {
                        // Unsupported builtin ability - fallback to dynamic dispatch
                        val functionNode = convertExpression(funcCall.function)
                        createFunctionCallNode(functionNode, argsNode)
                    }
                }
            } else {
            }
            } else {
                // User-defined ability - look up the implementation directly

                // The function should be a MemberAccess like Show.show
                if (funcCall.function is MemberAccess) {
                    val memberAccess = funcCall.function as MemberAccess
                    val operationName = memberAccess.member
                    val implementationRef = resolvedAbility.implementationRef

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


                    // Create a node that:
                    // 1. Looks up the implementation record from context
                    // 2. Accesses the operation field
                    // 3. Calls it with the arguments
                    return object : FunkNode() {
                        @field:Child private var argumentsChild = argsNode

                        override fun execute(frame: com.oracle.truffle.api.frame.VirtualFrame): FunkValue {
                            // Look up the implementation record from the CURRENT runtime context
                            // NOT the converter's compile-time context
                            val runtimeContext = com.funk.language.interpreter.FunkLanguage.getCurrentContext(this)
                            val implRecord = runtimeContext.lookupGlobal(implRecordName)

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
                            // CRITICAL: FunkFunctionRootNode expects 4 arguments:
                            // [0] = args record, [1] = closure variables map (legacy),
                            // [2] = MaterializedFrame (lexical scope), [3] = slot mapping
                            return operation.callTarget.call(
                                args,
                                operation.closureVariables,
                                operation.lexicalScope,
                                operation.lexicalScopeSlotMap
                            ) as FunkValue
                        }
                    }
                } else {
                }
            }
        }

        // Fallback to regular function call for non-builtin abilities
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

                // DEBUG: Print record fields
                System.err.println("DEBUG binary op '$operation': record fields = ${argsValue.fields.keys}, values = ${argsValue.fields.values.map { it::class.simpleName }}")

                val left = argsValue.fields["l"] ?: throw RuntimeException("Missing 'l' field in binary operation (available fields: ${argsValue.fields.keys})")
                val right = argsValue.fields["r"] ?: throw RuntimeException("Missing 'r' field in binary operation (available fields: ${argsValue.fields.keys})")

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
     * Handles primitives, records, variants, lists, and nested structures
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

            // Lists - check length and all elements structurally
            left is com.funk.language.interpreter.FunkList && right is com.funk.language.interpreter.FunkList -> {
                if (left.elements.size != right.elements.size) {
                    false // Different lengths
                } else {
                    // Check each element recursively
                    left.elements.zip(right.elements).all { (leftElem, rightElem) ->
                        structuralEquals(leftElem, rightElem)
                    }
                }
            }

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

        // CRITICAL: Always use the monomorphic name directly, never fall back to generic name
        // The monomorphic instance is a separate binding created by the type checker
        // and should be looked up by its full monomorphic name (e.g., "displayValue$Int")
        // Falling back to the generic name would execute the wrong (polymorphic) function
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
            is UntypedParameters -> {
                params.names
            }
            is TypedParameters -> {
                params.recordType.entries.map { it.name }
            }
        }

        // Detect free variables (variables used in body but not parameters)
        val allVariables = collectVariableReferences(lambda.body)
        val freeVariables = allVariables.filter { it !in paramNames }.distinct()

        // Create a new converter for the function body to manage its own frame
        // CRITICAL FIX: Pass BOTH the handler mapping AND the current variable slots as parent context
        val functionConverter = ASTToTruffleConverter(
            context = this.context,
            projectRoot = this.projectRoot,
            parentHandlerMapping = this.handlerToEffectMapping,
            parentVariableSlots = this.variableSlots.toMap()
        )

        // Pre-register parameter names in the function's frame FIRST
        // Parameters get the lowest slot numbers
        paramNames.forEach { paramName ->
            functionConverter.getOrCreateSlot(paramName)
        }

        // Pre-register closure variables in the function's frame (after parameters)
        // This ensures each converter has its own slot numbers for closure variables
        // CRITICAL: This creates NEW slots for closure variables in THIS frame
        // even if they have slots in parent frames (avoiding slot number collisions)
        freeVariables.forEach { varName ->
            functionConverter.getOrCreateSlot(varName)
        }

        val bodyNode = functionConverter.convertExpression(lambda.body)
        val functionFrameDescriptor = functionConverter.getFrameDescriptor()

        // CRITICAL FIX: Build slot mappings for closure variables
        // We need to map each closure variable to its slot in the CURRENT frame (where the lambda is being created)
        // The child converter (functionConverter) has already allocated slots for these variables,
        // but we need to know which slot in the CURRENT frame (this converter) they come from
        val closureVariableSlots = freeVariables.mapNotNull { varName ->
            // Look in the immediate parent frame (this converter)
            val slot = variableSlots[varName]
            if (slot != null) {
                // This variable is in the current frame - we can capture it via slot number
                varName to slot
            } else {
                // Variable not in immediate parent frame
                // Check if it's in the child converter's frame (meaning it will be bound from closure at runtime)
                val childSlot = functionConverter.variableSlots[varName]
                if (childSlot != null && childSlot >= paramNames.size) {
                    // This is a closure variable in the child frame (slot index >= parameter count)
                    // It will be bound from the parent function's closure variables
                    // We need to find it in the parent function's frame
                    // For now, mark it as needing global/closure lookup by not including it in the map
                    null
                } else {
                    // Variable not found - will use global lookup at runtime
                    null
                }
            }
        }.toMap()

        // Pass closure variable names AND their CURRENT frame slots to function definition node
        return FunctionDefinitionNode("lambda", paramNames, bodyNode, functionFrameDescriptor, freeVariables, closureVariableSlots)
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
                is BooleanLiteral -> Unit // Literals don't reference variables
                is StringLiteral -> {
                    // CRITICAL: String literals can contain interpolated expressions that reference variables
                    // Example: "$(path): $(msg)" references both path and msg
                    if (e.isInterpolated) {
                        val parts = e.parts
                        if (parts != null) {
                            parts.forEach { part ->
                                when (part) {
                                    is StringPart -> Unit // String parts don't reference variables
                                    is ExpressionPart -> visit(part.expression) // Visit interpolated expressions
                                }
                            }
                        }
                    }
                    Unit
                }
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
                    // CRITICAL FIX: If this function call has a resolved monomorphic name,
                    // we need to collect that name for closure capture, not the generic name
                    // This ensures closure variables match the variable reads in the function body
                    if (e.resolvedFunctionName != null && e.function is Identifier) {
                        // Use the monomorphic name for closure capture
                        variables.add(e.resolvedFunctionName!!)
                    } else {
                        // Regular case: visit the function expression
                        visit(e.function)
                    }
                    visit(e.arguments)
                    Unit
                }
                is LambdaExpression -> {
                    // CRITICAL FIX: Recursively collect free variables from nested lambdas
                    // Nested lambdas can reference variables from outer scopes, and those variables
                    // need to be available in the parent lambda's lexical scope (MaterializedFrame)
                    //
                    // Example: {x} -> spawn {computation = {} -> fn {x = x}}
                    // The innermost lambda {} -> fn {x = x} references:
                    // - fn (from parent scope, 2 levels up)
                    // - x (from immediate parent lambda, 1 level up)
                    // Both need to be in the parent lambda's free variables so they're captured
                    // in the MaterializedFrame

                    // Get nested lambda's parameters
                    val nestedParams = when (val params = e.parameters) {
                        is UntypedParameters -> params.names
                        is TypedParameters -> params.recordType.entries.map { it.name }
                    }

                    // Recursively collect variables from nested lambda body
                    val nestedVars = collectVariableReferences(e.body)

                    // Filter out the nested lambda's parameters - those are local to that lambda
                    val nestedFreeVars = nestedVars.filter { it !in nestedParams }

                    // Add nested free variables to the current lambda's variables
                    // These become free variables of the parent lambda
                    variables.addAll(nestedFreeVars)

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
        println("DEBUG convertRecordLiteral: fieldEntries = ${recordLit.getFieldEntries().map { it.name }}")
        println("DEBUG convertRecordLiteral: spreadEntries = ${recordLit.getSpreadEntries().size}")
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
            val entryValue = entry.value
            fieldNodes[entry.name] = if (entryValue != null) {
                // Explicit value: {x = expr}
                convertExpression(entryValue)
            } else {
                // Shorthand syntax: {x} means {x = x}
                // Create a variable read for the field name
                convertIdentifier(Identifier(entry.name))
            }
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

        // Debug the inner expression if it's a FunctionCall
        if (performExpr.expression is FunctionCall) {
            val funcCall = performExpr.expression as FunctionCall
            if (funcCall.function is MemberAccess) {
                val memberAccess = funcCall.function as MemberAccess
                if (memberAccess.receiver is Identifier) {
                }
            }
        }

        // Convert the inner expression that will be executed with handlers
        val expressionNode = convertExpression(performExpr.expression)

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

        // CRITICAL: Pre-convert handler parameters at node construction time
        // This maps handler name -> parameter node (or null if no parameters)
        val handlerParamNodes = performExpr.handlers.associate { handler ->
            handler.name to (handler.parameters?.let { convertExpression(it) })
        }

        // For now, create a simplified PerformNode that looks up handlers at runtime
        return object : com.funk.language.interpreter.nodes.FunkNode() {
            @field:Child private var exprChild = expressionNode

            override fun execute(frame: com.oracle.truffle.api.frame.VirtualFrame): FunkValue {
                // Build the handlers map by looking up each performer in the context
                val runtimeHandlers = mutableMapOf<String, FunkValue>()
                val context = getContext()


                // If resolvedHandlers is populated, use it
                if (performExpr.resolvedHandlers.isNotEmpty()) {
                    for (resolvedHandler in performExpr.resolvedHandlers.values) {
                        val effectName = resolvedHandler.effectName
                        val performerName = resolvedHandler.performerName
                        val implRef = resolvedHandler.implDeclarationRef


                        val lookupName = implRef ?: performerName
                        val performerValue = context.lookupGlobal(lookupName)
                            ?: throw RuntimeException("Handler implementation not found: $lookupName (effect: $effectName, performer: $performerName)")

                        runtimeHandlers[effectName] = performerValue
                    }
                } else {
                    // Fallback: Use the handlerToEffectMapping we built during conversion
                    for (handler in performExpr.handlers) {
                        val handlerName = handler.name

                        // Look up the effect name and implementation name from our mapping
                        val mapping = capturedMapping[handlerName]
                        if (mapping == null) {
                            throw RuntimeException("Handler '$handlerName' not found in effect mapping. Available handlers: ${capturedMapping.keys}")
                        }

                        val (effectName, implName) = mapping

                        // Look up the implementation (might be a function or a record)
                        var performerValue = context.lookupGlobal(implName)
                            ?: throw RuntimeException("Handler implementation not found: $implName for handler $handlerName")

                        // If the handler has parameters (e.g., AllPaths {cont = continuation::new {}}),
                        // the performer is a FUNCTION that needs to be called with those parameters
                        val paramsNode = handlerParamNodes[handlerName]
                        if (paramsNode != null) {
                            // Evaluate the pre-converted parameter node
                            val paramsValue = paramsNode.execute(frame)

                            if (paramsValue !is FunkRecord) {
                                throw RuntimeException("Handler parameters must be a record, got: ${paramsValue::class.simpleName}")
                            }

                            // Check if the performer is a function (parameterized implementation)
                            when (performerValue) {
                                is com.funk.language.interpreter.NativeFunkFunction -> {
                                    // Native wrapper function - call it with the parameters
                                    performerValue = performerValue.call(listOf(paramsValue))
                                }
                                is com.funk.language.interpreter.FunkTruffleFunction -> {
                                    // Truffle function - call via CallTarget
                                    // CRITICAL: FunkFunctionRootNode expects 4 arguments:
                                    // [0] = args record, [1] = closure variables map (legacy),
                                    // [2] = MaterializedFrame (lexical scope), [3] = slot mapping
                                    performerValue = performerValue.callTarget.call(
                                        paramsValue,
                                        performerValue.closureVariables,
                                        performerValue.lexicalScope,
                                        performerValue.lexicalScopeSlotMap
                                    ) as FunkValue
                                }
                                else -> {
                                    // Fallback: Old behavior for non-parameterized handlers
                                    // Inject parameters into the context
                                    for ((paramName, paramValue) in paramsValue.fields) {
                                        context.defineLocal(paramName, paramValue)
                                    }
                                }
                            }
                        }

                        // Store the handler under the effect name
                        runtimeHandlers[effectName] = performerValue
                    }
                }

                // Use the shared effect handler from context
                val effectHandler = context.effectHandler

                // CRITICAL: Merge with existing handlers from the stack
                // This ensures nested perform expressions accumulate handlers
                // Example: `perform Console in perform Delimited` should have BOTH handlers available
                val existingHandlers = effectHandler.getCurrentHandlers()
                val mergedHandlers = if (existingHandlers != null) {
                    // Merge: new handlers override existing ones with same name
                    existingHandlers.fields.toMutableMap().apply {
                        putAll(runtimeHandlers)
                    }
                } else {
                    runtimeHandlers
                }

                val handlersRecord = FunkRecord(mergedHandlers)

                // Push merged handlers onto stack
                effectHandler.pushHandlers(handlersRecord)

                try {
                    val result = exprChild.execute(frame)

                    // CRITICAL: Check if result is FunkContinuationSuspended (for early return)
                    // ONLY unwrap for WithReturn performer (Return effect), NOT for Delimited (generators)
                    // - perform WithReturn: unwraps and returns suspended value (early return semantics)
                    // - perform Delimited: returns FunkContinuationSuspended as-is (generator semantics)
                    if (result is com.funk.language.interpreter.FunkContinuationSuspended) {
                        // Check if we're handling the Return effect (early return)
                        if (runtimeHandlers.containsKey("Return")) {
                            // WithReturn performer: extract the suspended value and return it immediately
                            // This implements early return semantics
                            return result.suspendedValue
                        }

                        // Check if we're handling the Exception effect with ToResult, ToOption, or Ignore
                        // These performers suspend with Err/None/{}, so just return the suspended value
                        if (runtimeHandlers.containsKey("Exception")) {
                            return result.suspendedValue
                        }

                        // Otherwise (Delimited performer for generators): return the suspended continuation as-is
                        // so callers can resume it with next(), to_list(), etc.
                        return result
                    }

                    // LAZY EFFECTS MODEL: Check if result is FunkSuspended
                    // If all effects are now handled, execute the deferred computation
                    if (result is com.funk.language.interpreter.FunkSuspended) {
                        // Try to execute the suspended computation with available handlers
                        return result.executeWith(runtimeHandlers, context, frame)
                    }

                    // EXCEPTION EFFECT: Wrap successful results in Ok for ToResult handler
                    if (runtimeHandlers.containsKey("Exception")) {
                        // If we reach here, no exception was thrown, so wrap the result in Ok
                        return com.funk.language.interpreter.FunkVariant(
                            "Ok",
                            FunkRecord(mapOf("val" to result))
                        )
                    }

                    return result
                } catch (e: com.funk.language.interpreter.stdlib.effects.ForkedExecutionException) {
                    // CRITICAL: Fork exception thrown from within the perform block
                    // This means the continuation was forked with multiple values
                    // Return the collected results as an array (this is the final result of the perform block)
                    System.err.println("DEBUG: Caught ForkedExecutionException in perform block, returning ${e.values.size} results")
                    return com.funk.language.interpreter.FunkArrayList(e.values)
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
            val localSlot = variableSlots.size
            frameDescriptorBuilder.addSlot(com.oracle.truffle.api.frame.FrameSlotKind.Object, variableName, null)
            localSlot
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

    /**
     * Get the effect declarations collected during conversion
     * These need to be registered with the effect handler during execution
     */
    fun getEffectDeclarations(): List<EffectDeclaration> = effectDeclarations.toList()

    /**
     * Get the handler mappings collected during conversion
     * Maps handler name → (effect name, implementation name)
     * Used to propagate handler mappings from modules to main program
     */
    fun getHandlerMappings(): Map<String, Pair<String, String>> = handlerToEffectMapping.toMap()

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