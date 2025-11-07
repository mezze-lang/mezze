package com.funk.language.interpreter

import com.funk.language.parser.FunkParser
import com.funk.language.ast.*
import com.funk.language.typechecker.TypeChecker
import com.funk.language.typechecker.ModuleResolver

import com.oracle.truffle.api.frame.FrameDescriptor
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.RootNode
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Truffle-integrated interpreter that works with the shared AST
 * This provides proper Truffle execution while using the unified AST architecture
 */
class FunkInterpreter {
    // Centralized effect handling system
    private val effectHandler = EffectHandler()

    private val parser: FunkParser?
    private val typeChecker: TypeChecker?
    private var moduleResolver: ModuleResolver? = null

    // Store last execution context for CLI access
    private var lastExecutionContext: Map<String, FunkValue> = emptyMap()
    
    // Constructor for full parsing/typechecking (used by funk exec)
    constructor() {
        parser = FunkParser()
        typeChecker = TypeChecker()
    }
    
    // Constructor for pre-processed AST (used by compiled JARs)
    constructor(skipParserSetup: Boolean) {
        if (skipParserSetup) {
            parser = null
            typeChecker = null
        } else {
            parser = FunkParser()
            typeChecker = TypeChecker()
        }
    }
    
    /**
     * Parse, typecheck, and execute a Funk program from source code
     */
    fun run(source: String): FunkValue {
        require(parser != null && typeChecker != null) { 
            "Parser and typechecker not available. Use constructor without skipParserSetup for source code execution." 
        }
        val program = parser.parse(source)
        val typedProgram = typeChecker.typeCheck(program)
        return execute(typedProgram)
    }
    
    /**
     * Execute a pre-parsed and typechecked program directly
     */
    fun execute(program: Program): FunkValue {
        return executeProgram(program)
    }
    
    /**
     * Execute a pre-parsed and typechecked program with module resolution support
     */
    fun execute(program: Program, projectRoot: Path): FunkValue {
        // Initialize module resolver
        moduleResolver = ModuleResolver(projectRoot, parser ?: FunkParser())
        return executeProgram(program)
    }
    
    /**
     * Public method for testing - executes a program and returns both result and context
     */
    fun executeWithContext(program: Program): Pair<FunkValue, Map<String, FunkValue>> {
        val context = mutableMapOf<String, FunkValue>()
        
        // Register built-in native functions and stdlib modules
        registerNativeFunctions(context)
        
        var lastResult: FunkValue = FunkNull
        
        for (item in program.items) {
            lastResult = executeItem(item, context)
        }
        
        return Pair(lastResult, context.toMap())
    }
    
    /**
     * Get the context from the last program execution
     */
    fun getLastExecutionContext(): Map<String, FunkValue> = lastExecutionContext

    /**
     * Execute a program (enhanced with type information)
     */
    private fun executeProgram(program: Program): FunkValue {
        // Simple execution context
        val context = mutableMapOf<String, FunkValue>()

        // Register built-in native functions
        registerNativeFunctions(context)

        // Process imports first if module resolver is available
        if (moduleResolver != null) {
            processImports(program, context)
        }

        // Execute each item in the program
        var lastResult: FunkValue = FunkNull

        for (item in program.items) {
            lastResult = executeItem(item, context)
        }

        // Store the context for CLI access
        lastExecutionContext = context.toMap()

        return lastResult
    }
    
    /**
     * Process import declarations and populate context with imported items
     */
    private fun processImports(program: Program, context: MutableMap<String, FunkValue>) {
        for (item in program.items) {
            when (item) {
                is UseDeclaration -> {
                    processUseDeclaration(item, context)
                }
                is PubUseDeclaration -> {
                    // Treat pub use declarations like regular use declarations for local context
                    processUseDeclaration(UseDeclaration(item.path, item.alias), context)
                }
                else -> {
                    // Other items are processed later
                }
            }
        }
    }
    
    /**
     * Process a single use declaration and add imported items to context
     */
    private fun processUseDeclaration(useDecl: UseDeclaration, context: MutableMap<String, FunkValue>) {
        val resolver = moduleResolver ?: return
        
        when (val path = useDecl.path) {
            is ModulePath -> {
                val modulePath = path.segments.joinToString("::")
                val module = resolver.resolveModule(modulePath)
                
                // Execute the module to get runtime values
                val moduleContext = executeModule(module.program)

                // Import all public exports
                val prefix = useDecl.alias ?: path.segments.last()
                for ((name, export) in module.exports) {
                    val qualifiedName = "$prefix::$name"

                    // Get the actual runtime value from the executed module
                    val runtimeValue = moduleContext[name] ?: FunkNull
                    context[qualifiedName] = runtimeValue

                    // For constructors, also add without qualification
                    if (export.type == com.funk.language.typechecker.ExportedItemType.CONSTRUCTOR) {
                        context[name] = runtimeValue
                    }
                }

                // Import ability implementations from the module
                // Ability implementations have keys like "NumAdd_Int", "Eq_String", etc.
                for ((key, value) in moduleContext) {
                    if (key.contains("_") && !key.startsWith("__") && value is FunkRecord) {
                        // Check if this looks like an ability implementation key
                        val parts = key.split("_")
                        if (parts.size >= 2 && !key.endsWith("_ability")) {
                            // This is likely an ability implementation, import it
                            context[key] = value
                        }
                    }
                }
            }
            
            is SelectivePath -> {
                val modulePath = path.module.joinToString("::")
                val module = resolver.resolveModule(modulePath)

                // Execute the module to get runtime values
                val moduleContext = executeModule(module.program)

                // Import __impl_mappings from the module
                val moduleImplMappings = moduleContext["__impl_mappings"] as? FunkRecord
                if (moduleImplMappings != null) {
                    val currentImplMappings = context["__impl_mappings"] as? FunkRecord
                    val mergedMappings = (currentImplMappings?.fields ?: emptyMap()).toMutableMap()
                    mergedMappings.putAll(moduleImplMappings.fields)
                    context["__impl_mappings"] = FunkRecord(mergedMappings)
                }

                // Import only selected items
                for (item in path.items) {
                    val export = module.exports[item.name]
                        ?: throw RuntimeException("Item '${item.name}' not found in module '$modulePath'")

                    // Get the actual runtime value from the executed module
                    val runtimeValue = moduleContext[item.name] ?: FunkNull
                    val contextName = item.alias ?: item.name
                    context[contextName] = runtimeValue
                }

                // Import ability implementations from the module
                // Ability implementations have keys like "NumAdd_Int", "Eq_String", etc.
                for ((key, value) in moduleContext) {
                    if (key.contains("_") && !key.startsWith("__") && value is FunkRecord) {
                        // Check if this looks like an ability implementation key
                        val parts = key.split("_")
                        if (parts.size >= 2 && !key.endsWith("_ability")) {
                            // This is likely an ability implementation, import it
                            context[key] = value
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Execute a module and return its context (all defined variables)
     */
    private fun executeModule(program: Program): Map<String, FunkValue> {
        val moduleContext = mutableMapOf<String, FunkValue>()
        
        // Register built-in native functions
        registerNativeFunctions(moduleContext)
        
        // Execute each item in the module
        for (item in program.items) {
            executeItem(item, moduleContext)
        }
        
        return moduleContext.toMap()
    }
    
    /**
     * Execute a single program item
     */
    private fun executeItem(item: ProgramItem, context: MutableMap<String, FunkValue>): FunkValue {
        return when (item) {
            is LetBinding -> {
                // Handle recursive functions by pre-declaring the variable
                if (item.expression is LambdaExpression) {
                    // For lambda expressions, create a placeholder first for recursion
                    val recursivePlaceholder = RecursiveFunctionPlaceholder(item.variableName)
                    context[item.variableName] = recursivePlaceholder
                    
                    // Now evaluate the lambda with the placeholder in context
                    val value = evaluateExpression(item.expression, context)
                    
                    // Replace the placeholder with the actual function
                    context[item.variableName] = value
                    
                    // If it's a user function, set up recursive reference
                    if (value is SimpleFunkFunction) {
                        value.setupRecursiveReference(item.variableName, value)
                    }
                    
                    value
                } else {
                    // Non-function case: check for self-reference (which should be forbidden)
                    if (containsSelfReference(item.expression, item.variableName)) {
                        throw RuntimeException("Variable '${item.variableName}' cannot reference itself in its own definition. Self-reference is only allowed for functions.")
                    }
                    
                    // Evaluate normally without the variable in context
                    val value = evaluateExpression(item.expression, context)
                    context[item.variableName] = value
                    value
                }
            }
            is TypeDeclaration -> {
                // Type declarations don't produce runtime values
                FunkNull
            }
            is EffectDeclaration -> {
                // Effect declarations create a record type with operation signatures
                // This allows Log.print to be resolved as a member access
                val operations = item.operations.associate { op ->
                    op.name to FunkNull // Placeholder - will be replaced by handlers
                }
                val effectRecord = FunkRecord(operations)
                context[item.name] = effectRecord
                // Register in effect handler for global access
                effectHandler.registerEffect(item.name, effectRecord)
                effectRecord
            }
            is ImplDeclaration -> {
                if (item.performerParams.isEmpty()) {
                    // Non-parameterized performer - create performer directly
                    createDirectPerformer(item, context)
                } else {
                    // Parameterized performer - create performer factory
                    createPerformerFactory(item, context)
                }
            }
            is AbilityDeclaration -> {
                // Ability declarations create a record type with operation signatures
                // This is similar to effect declarations but for type classes
                val operations = item.operations.associate { op ->
                    op.name to FunkNull // Placeholder - will be replaced by implementations
                }
                val abilityRecord = FunkRecord(operations)
                context["${item.name}_ability"] = abilityRecord
                abilityRecord
            }
            is ImplAbilityDeclaration -> {
                // Ability implementations create concrete instances
                // Register the implementation for the specific type
                val operations = item.operations.associate { op ->
                    val implementation = evaluateExpression(op.implementation, context)
                    op.name to implementation
                }
                val implRecord = FunkRecord(operations)
                
                val targetTypeNames = item.targetTypes.map { targetType ->
                    when (targetType) {
                        is IdentifierTypeAnnotation -> targetType.name
                        else -> "Unknown"
                    }
                }
                
                // Store the implementation with a key that combines ability and target types
                val key = "${item.abilityName}_${targetTypeNames.joinToString("_")}"
                context[key] = implRecord
                implRecord
            }
            is TopLevelExpression -> {
                // Top-level expressions are evaluated and their result is returned
                evaluateExpression(item.expression, context)
            }
            is UseDeclaration -> {
                // Use declarations are processed at type-check time, no runtime effect
                FunkNull
            }
            is PubUseDeclaration -> {
                // Pub use declarations are processed at type-check time, no runtime effect
                FunkNull
            }
            else -> {
                throw RuntimeException("Unsupported program item: ${item::class.simpleName}")
            }
        }
    }
    
    /**
     * Evaluate an expression (simplified version)
     */
    private fun evaluateExpression(expr: Expression, context: MutableMap<String, FunkValue>): FunkValue {
        // println("DEBUG: Evaluating expression: ${expr::class.simpleName}")
        return when (expr) {
            is IntLiteral -> FunkInt(expr.value)
            is FloatLiteral -> FunkFloat(expr.value)
            is StringLiteral -> FunkString(expr.value)
            is BooleanLiteral -> FunkBoolean(expr.value)
            is Identifier -> {
                context[expr.name] ?: run {
                    // If it's an uppercase identifier, treat it as a variant constructor
                    if (expr.name.first().isUpperCase()) {
                        FunkVariant(expr.name, null) // Simple constructor with no data
                    } else {
                        throw UndefinedVariableException(expr.name)
                    }
                }
            }
            is BinaryOperation -> {
                // Handle pipe operator specially - desugar before evaluation
                if (expr.operator == "|>") {
                    val leftValue = evaluateExpression(expr.left, context)
                    // Desugar: replace @ placeholders in right side with leftValue
                    val desugaredRight = replaceAtPlaceholder(expr.right, leftValue, context)
                    evaluateExpression(desugaredRight, context)
                } else if (expr.operator == "++") {
                    // String concatenation is still special (not an ability yet)
                    val left = evaluateExpression(expr.left, context)
                    val right = evaluateExpression(expr.right, context)
                    evaluateBinaryOperation(left, expr.operator, right)
                } else {
                    // Desugar operator to ability method call
                    // e.g., x + y becomes NumAdd.num_add { l = x, r = y }
                    val desugared = desugarOperatorToAbilityCall(expr, context)
                    if (desugared != null) {
                        evaluateExpression(desugared, context)
                    } else {
                        // Fallback to old behavior for operators without abilities
                        val left = evaluateExpression(expr.left, context)
                        val right = evaluateExpression(expr.right, context)
                        evaluateBinaryOperation(left, expr.operator, right)
                    }
                }
            }
            is UnaryOperation -> {
                val operand = evaluateExpression(expr.operand, context)
                evaluateUnaryOperation(expr.operator, operand)
            }
            is RecordLiteral -> {
                val fields = mutableMapOf<String, FunkValue>()
                
                // Handle spread entries first
                for (spreadEntry in expr.getSpreadEntries()) {
                    val spreadValue = evaluateExpression(spreadEntry.expression, context)
                    if (spreadValue is FunkRecord) {
                        // Spread all fields from the record (they can be overridden by later fields)
                        fields.putAll(spreadValue.fields)
                    } else {
                        throw RuntimeException("Cannot spread non-record value")
                    }
                }
                
                // Handle field entries (they can override spread fields)
                for (entry in expr.getFieldEntries()) {
                    val value = entry.value?.let { evaluateExpression(it, context) } ?: FunkNull
                    fields[entry.name] = value
                }
                
                FunkRecord(fields)
            }
            is MemberAccess -> {
                // Check if this is an effect call (e.g., Console.print, Log.log)
                val receiver = expr.receiver
                val receiverName = when (receiver) {
                    is Identifier -> receiver.name
                    is VariantConstruction -> receiver.constructor
                    else -> null
                }
                
                if (receiverName != null) {
                    
                    
                    // First check if this is an effect call
                    // Check if this is a declared effect (not just any record)
                    val isEffectCall = effectHandler.isEffectDeclared(receiverName) && 
                                      context[receiverName] is FunkRecord &&
                                      (context[receiverName] as FunkRecord).fields.containsKey(expr.member)
                    
                    
                    if (isEffectCall) {
                        // This is an effect call - return an EffectCallExpression wrapper
                        return NativeFunkFunction(
                            name = "$receiverName.${expr.member}",
                            implementation = { args ->
                                if (args.size != 1) {
                                    throw RuntimeException("Effect operation $receiverName.${expr.member} expects exactly one argument")
                                }
                                val arguments = args[0]
                                
                                // Directly call the effect handling logic
                                effectHandler.executeEffectCall(receiverName, expr.member, arguments, context, this@FunkInterpreter)
                            }
                        )
                    }
                    
                    // Check if this is an ability call (e.g., Show.show)
                    val abilityKey = "${receiverName}_ability"
                    if (context.containsKey(abilityKey)) {
                        // This is an ability operation reference - return a function that will handle ability resolution
                        // when called with arguments
                        return NativeFunkFunction(
                            name = "$receiverName.${expr.member}",
                            implementation = { args ->
                                if (args.size != 1) {
                                    throw RuntimeException("Ability operation $receiverName.${expr.member} expects exactly one argument")
                                }
                                val arguments = args[0]
                                
                                // Extract type arguments from the arguments for implementation lookup
                                val typeArgs = when (arguments) {
                                    is FunkRecord -> {
                                        arguments.fields.values.map { arg ->
                                            when (arg) {
                                                is FunkInt -> "Int"
                                                is FunkString -> "String"
                                                is FunkBoolean -> "Boolean"
                                                is FunkFloat -> "Float"
                                                is FunkNull -> "Null"
                                                is FunkRecord -> "Record"
                                                is FunkVariant -> "Variant"
                                                is FunkList -> "List"
                                                is FunkNonEmptyList -> "NonEmptyList"
                                                is FunkNonEmptyList -> "NonEmptyList"
                                                is FunkArrayList -> "ArrayList"
                                                is FunkMap -> "Map"
                                                is FunkSet -> "Set"
                                                is SimpleFunkFunction -> "Function"
                                                is NativeFunkFunction -> "NativeFunction"
                                                else -> "Unknown"
                                            }
                                        }.distinct()
                                    }
                                    else -> {
                                        listOf(when (arguments) {
                                            is FunkInt -> "Int"
                                            is FunkString -> "String"
                                            is FunkBoolean -> "Boolean"
                                            is FunkFloat -> "Float"
                                            is FunkNull -> "Null"
                                            is FunkRecord -> "Record"
                                            is FunkVariant -> "Variant"
                                            is FunkList -> "List"
                                            is FunkNonEmptyList -> "NonEmptyList"
                                            is FunkArrayList -> "ArrayList"
                                            is FunkMap -> "Map"
                                            is FunkSet -> "Set"
                                            is SimpleFunkFunction -> "Function"
                                            is NativeFunkFunction -> "NativeFunction"
                                            else -> "Unknown"
                                        })
                                    }
                                }
                                
                                // Find matching implementation
                                val matchingImpl = context.entries.find { (key, value) ->
                                    if (value is FunkRecord && key.startsWith("${receiverName}_")) {
                                        val keyTypes = key.removePrefix("${receiverName}_").split("_")
                                        typeArgs.size <= keyTypes.size &&
                                        typeArgs.zip(keyTypes).all { (inputType, implType) -> inputType == implType }
                                    } else false
                                }

                                // If no implementation found, try to derive one for Record/Variant types
                                val operationFunction = if (matchingImpl != null) {
                                    val (implKey, implementation) = matchingImpl
                                    val implRecord = implementation as FunkRecord
                                    implRecord.fields[expr.member]
                                        ?: throw RuntimeException("Operation ${expr.member} not found in $receiverName implementation")
                                } else {
                                    // Try to automatically derive Eq or Inspect for Record/Variant/Collection types
                                    val derived = deriveDerivedAbilityImplementation(receiverName, expr.member, typeArgs, arguments)
                                    if (derived != null) {
                                        derived
                                    } else {
                                        throw RuntimeException("No implementation found for ability $receiverName with input types $typeArgs (derivation not available for ability=$receiverName operation=$expr.member)")
                                    }
                                }

                                // Call the operation function
                                when (operationFunction) {
                                    is NativeFunkFunction -> operationFunction.call(listOf(arguments))
                                    is SimpleFunkFunction -> {
                                        callFunctionWithRecord(operationFunction, arguments as FunkRecord, context)
                                    }
                                    else -> throw RuntimeException("Invalid operation function type: ${operationFunction::class.simpleName}")
                                }
                            }
                        )
                    }
                }
                
                // CRITICAL: Check for effect handlers BEFORE regular member access
                // Priority 1: Active handlers (from perform expressions)
                if (expr.receiver is Identifier) {
                    val effectName = (expr.receiver as Identifier).name
                    val operationName = expr.member
                    
                    // Check if we have an active handler for this effect
                    val handlersRecord = (context["__handlers"] as? FunkRecord) ?: effectHandler.getCurrentHandlers()
                    if (handlersRecord?.fields?.containsKey(effectName) == true) {
                        // This is an effect operation - return the handler operation
                        val handler = handlersRecord.fields[effectName] as? FunkRecord
                        return handler?.fields?.get(operationName) 
                            ?: throw RuntimeException("Operation '$operationName' not found in handler for effect '$effectName'")
                    }
                }
                
                // Regular member access
                val record = evaluateExpression(expr.receiver, context)
                when (record) {
                    is FunkRecord -> record.fields[expr.member] 
                        ?: throw RuntimeException("Field '${expr.member}' not found in record")
                    else -> throw RuntimeException("Cannot access member '${expr.member}' on non-record value")
                }
            }
            is VariantConstruction -> {
                // CRITICAL FIX: Check if this "variant constructor" is actually a value in the context
                // This handles cases like BasicLogger.info where BasicLogger should be looked up, not treated as a variant
                if (expr.value == null && context.containsKey(expr.constructor)) {
                    // This is actually an identifier lookup, not a variant construction
                    context[expr.constructor]!!
                } else {
                    // This is a real variant construction
                val data = expr.value?.let { evaluateExpression(it, context) }
                FunkVariant(expr.constructor, data)
                }
            }
            is IfExpression -> {
                val condition = evaluateExpression(expr.condition, context)
                if (isTruthy(condition)) {
                    evaluateExpression(expr.thenBranch, context)
                } else {
                    evaluateExpression(expr.elseBranch, context)
                }
            }
            is MatchExpression -> {
                val value = evaluateExpression(expr.expression, context)
                evaluateMatchExpression(value, expr.cases, context)
            }
            is DoExpression -> {
                // Check if this do block contains effect calls
                if (containsEffectCalls(expr)) {
                    // Execute as state machine for async effects
                    executeDoBlockAsStateMachine(expr, context)
                } else {
                    // Execute sequentially for pure computations
                    executeDoBlockSequentially(expr, context)
                }
            }
            is FunctionCall -> {
                // Check if this is an effect call (function is a member access like Log.log)
                if (expr.function is MemberAccess) {
                    val memberAccess = expr.function as MemberAccess
                    if (memberAccess.receiver is Identifier) {
                        val effectName = (memberAccess.receiver as Identifier).name
                        val operationName = memberAccess.member
                        // CRITICAL: Always check if this is an effect call FIRST
                        // This ensures handlers take precedence over effect declarations
                        if (effectHandler.shouldConvertToEffectCall(effectName, context)) {
                            return effectHandler.executeEffectCall(effectName, operationName, evaluateExpression(expr.arguments, context), context, this)
                        }
                        
                        // Check if this is an ability call (e.g., Show.show, Eq.equals)
                        if (context.containsKey("${effectName}_ability")) {
                            // Ability resolution - inline implementation to avoid scope issues
                            val arguments = evaluateExpression(expr.arguments, context)
                            
                            // Extract type arguments from the arguments for implementation lookup
                            val typeArgs = when (arguments) {
                                is FunkRecord -> {
                                    // For record arguments like {val: 42}, extract the types of the field values
                                    arguments.fields.values.map { arg ->
                                        when (arg) {
                                            is FunkInt -> "Int"
                                            is FunkString -> "String"
                                            is FunkBoolean -> "Boolean"
                                            is FunkFloat -> "Float"
                                            is FunkNull -> "Null"
                                            is FunkRecord -> "Record"
                                            is FunkVariant -> "Variant"
                                            is FunkList -> "List"
                                            is FunkNonEmptyList -> "NonEmptyList"
                                            is FunkArrayList -> "ArrayList"
                                            is FunkMap -> "Map"
                                            is FunkSet -> "Set"
                                            is SimpleFunkFunction -> "Function"
                                            is NativeFunkFunction -> "NativeFunction"
                                            else -> "Unknown"
                                        }
                                    }.distinct()
                                }
                                else -> {
                                    listOf(when (arguments) {
                                        is FunkInt -> "Int"
                                        is FunkString -> "String"
                                        is FunkBoolean -> "Boolean"
                                        is FunkFloat -> "Float"
                                        is FunkNull -> "Null"
                                        is FunkRecord -> "Record"
                                        is FunkVariant -> "Variant"
                                        is FunkList -> "List"
                                                is FunkNonEmptyList -> "NonEmptyList"
                                        is FunkArrayList -> "ArrayList"
                                        is FunkMap -> "Map"
                                        is FunkSet -> "Set"
                                        is SimpleFunkFunction -> "Function"
                                        is NativeFunkFunction -> "NativeFunction"
                                        else -> "Unknown"
                                    })
                                }
                            }
                            
                            // Find matching implementation by searching all available implementations
                            // This handles multi-parameter abilities by matching input types and finding compatible implementations
                            val matchingImpl = context.entries.find { (key, value) ->
                                if (value is FunkRecord && key.startsWith("${effectName}_")) {
                                    val keyTypes = key.removePrefix("${effectName}_").split("_")
                                    // Check if the input types match the beginning of the implementation types
                                    typeArgs.size <= keyTypes.size &&
                                    typeArgs.zip(keyTypes).all { (inputType, implType) -> inputType == implType }
                                } else false
                            }

                            val implRecord = if (matchingImpl != null) {
                                val (implKey, implementation) = matchingImpl
                                implementation as FunkRecord
                            } else {
                                // Try to derive implementation for Record/Variant types
                                val derivedOp = deriveDerivedAbilityImplementation(effectName, operationName, typeArgs, arguments)
                                if (derivedOp != null) {
                                    // Create a FunkRecord with the derived operation
                                    FunkRecord(mapOf(operationName to derivedOp))
                                } else {
                                    throw RuntimeException("No implementation found for ability $effectName with input types $typeArgs")
                                }
                            }
                            
                            // Get the operation function from the implementation
                            val operationFunction = implRecord.fields[operationName]
                                ?: throw RuntimeException("Operation $operationName not found in $effectName implementation")
                            
                            // Call the operation function using existing infrastructure (preserves tail call optimization and Loom threads)
                            return when (operationFunction) {
                                is NativeFunkFunction -> {
                                    operationFunction.call(listOf(arguments))
                                }
                                is SimpleFunkFunction -> {
                                    if (arguments is FunkRecord) {
                                        // Use existing callFunctionWithRecord which has full tail call optimization and Loom thread support
                                        callFunctionWithRecord(operationFunction, arguments, context)
                                    } else {
                                        // Convert single argument to record
                                        val recordArgs = FunkRecord(mapOf("arg0" to arguments))
                                        callFunctionWithRecord(operationFunction, recordArgs, context)
                                    }
                                }
                                else -> {
                                    throw RuntimeException("Invalid ability operation type: $operationFunction")
                                }
                            }
                        }
                    }
                }
                
                val function = evaluateExpression(expr.function, context)
                val argumentsValue = evaluateExpression(expr.arguments, context)
                
                // Special case: If function is a variant constructor, create variant construction
                if (function is FunkVariant && function.value == null && argumentsValue is FunkRecord) {
                    // This is a variant constructor call like Success {data = 42}
                    // Convert to variant construction
                    FunkVariant(function.constructor, argumentsValue)
                } else if (argumentsValue is FunkRecord) {
                // Handle record arguments for partial application
                    callFunctionWithRecord(function, argumentsValue, context)
                } else {
                    // Otherwise treat as single argument (legacy)
                    val args = listOf(argumentsValue)
                    callFunction(function, args, context)
                }
            }
            is LambdaExpression -> {
                val paramNames = when (val params = expr.parameters) {
                    is UntypedParameters -> params.names
                    is TypedParameters -> {
                        // Extract parameter names from the typed parameters
                        params.recordType.entries.map { it.name }
                    }
                }
                // Don't capture effect context at creation time - get it dynamically at execution time
                SimpleFunkFunction(
                    parameters = paramNames,
                    body = expr.body,
                    closure = context.toMap(),
                    inferredType = expr.inferredType
                    // effectContext will be populated at execution time
                )
            }

            is PerformExpression -> {
                // Execute expression with provided handlers
                // Process handlers in reverse order (last to first) to match semantics:
                // perform A, B, C do expr  =>  A handles outermost, C handles innermost
                val handlerMap = mutableMapOf<String, FunkValue>()

                expr.handlers.reversed().forEach { performHandler ->
                    val handlerName = performHandler.name
                    val handlerOrFactory = context[handlerName]
                        ?: throw HandlerNotFoundException(handlerName)

                    val handler = when (handlerOrFactory) {
                        is FunkRecord -> {
                            // Direct handler (non-parameterized)
                            handlerOrFactory
                        }
                        is PerformerFactory -> {
                            // Parameterized handler - instantiate with provided parameters
                            val params = performHandler.parameters?.let { recordLit ->
                                evaluateExpression(recordLit, context) as FunkRecord
                            } ?: FunkRecord(emptyMap())
                            handlerOrFactory.createPerformer(params)
                        }
                        else -> {
                            throw RuntimeException("Invalid handler type: ${handlerOrFactory::class.simpleName}")
                        }
                    }

                    // Map performer to its effect based on impl declarations
                    val effectName = findEffectForPerformer(handlerName, context)
                    handlerMap[effectName] = handler

                    // Also make the performer available by its name for delegation
                    context[handlerName] = handler
                }

                // Set up handlers in context before evaluating the expression
                val originalHandlers = context["__handlers"]
                val existingHandlers = (originalHandlers as? FunkRecord)?.fields ?: emptyMap()
                val mergedHandlers = existingHandlers.toMutableMap()
                mergedHandlers.putAll(handlerMap)
                val handlersRecord = FunkRecord(mergedHandlers)
                context["__handlers"] = handlersRecord

                // Push handlers onto effect handler stack
                effectHandler.pushHandlers(handlersRecord)

                try {
                    // Evaluate the expression with handlers available
                    val result = evaluateExpression(expr.expression, context)

                    // Check if result is a suspended value (performing on already suspended computation)
                    val finalResult = if (result is FunkSuspended) {
                        // Handle effects on suspended computation
                        val handledEffects = handlerMap.keys
                        val remainingEffects = result.unhandledEffects - handledEffects

                        if (remainingEffects.isEmpty()) {
                            // All effects handled - unwrap the value
                            result.value
                        } else {
                            // Some effects remain - return new suspended value
                            FunkSuspended(result.value, remainingEffects)
                        }
                    } else {
                        // Check if the result type is SuspendedType and wrap if needed
                        when (val resultType = expr.inferredType) {
                            is com.funk.language.ast.FunkType.SuspendedType -> {
                                // The result has unhandled effects - wrap it in FunkSuspended
                                FunkSuspended(
                                    value = if (result is FunkNull) FunkRecord(emptyMap()) else result,
                                    unhandledEffects = resultType.effects.effects
                                )
                            }
                            else -> {
                                // No unhandled effects - return as normal
                                if (result is FunkNull) {
                                    FunkRecord(emptyMap()) // Unit type
                                } else {
                                    result
                                }
                            }
                        }
                    }
                    finalResult
                } finally {
                    // Restore original handlers
                    if (originalHandlers != null) {
                        context["__handlers"] = originalHandlers
                    } else {
                        context.remove("__handlers")
                    }
                    
                    // Pop handlers from effect handler stack
                    effectHandler.popHandlers()
                }
            }
            is EffectCallExpression -> {
                // Perform effect call using effect handler
                effectHandler.executeEffectCall(expr.effectName, expr.operationName, evaluateExpression(expr.arguments, context), context, this)
            }
            // PipeExpression and PlaceholderExpression removed - desugared during parsing
            is RecordUpdateExpression -> {
                // Evaluate record update: { baseRecord | field = value, ... }
                val baseRecord = evaluateExpression(expr.baseRecord, context)
                if (baseRecord !is FunkRecord) {
                    throw RuntimeException("Cannot update non-record value: $baseRecord")
                }
                
                // Create updated record with new field values
                val updatedFields = baseRecord.fields.toMutableMap()
                
                expr.updates.forEach { update ->
                    when (update.fieldPath.size) {
                        1 -> {
                            // Simple field update: x = v
                            val fieldName = update.fieldPath[0]
                            // For open records, we allow adding new fields
                            // The type checker has already validated this is allowed
                            val newValue = evaluateExpression(update.value, context)
                            updatedFields[fieldName] = newValue
                        }
                        2 -> {
                            // Nested field update: y.z = v
                            val outerField = update.fieldPath[0]
                            val innerField = update.fieldPath[1]
                            
                            if (!baseRecord.fields.containsKey(outerField)) {
                                throw RuntimeException("Field '$outerField' does not exist in record")
                            }
                            
                            val outerValue = baseRecord.fields[outerField]
                            if (outerValue !is FunkRecord) {
                                throw RuntimeException("Cannot access field '$innerField' on non-record value: $outerValue")
                            }
                            
                            if (!outerValue.fields.containsKey(innerField)) {
                                throw RuntimeException("Field '$innerField' does not exist in nested record")
                            }
                            
                            // Update the nested record
                            val updatedNestedFields = outerValue.fields.toMutableMap()
                            val newValue = evaluateExpression(update.value, context)
                            updatedNestedFields[innerField] = newValue
                            updatedFields[outerField] = FunkRecord(updatedNestedFields)
                        }
                        else -> {
                            throw RuntimeException("Nested field updates beyond one level are not supported")
                        }
                    }
                }
                
                FunkRecord(updatedFields)
            }
            else -> {
                throw RuntimeException("Unsupported expression: ${expr::class.simpleName}")
            }
        }
    }
    
    // substitutePlaceholders method removed - pipe operator now desugared during parsing

    /**
     * Evaluate a match expression with pattern matching
     */
    private fun evaluateMatchExpression(value: FunkValue, cases: List<MatchCase>, context: MutableMap<String, FunkValue>): FunkValue {
        for (case in cases) {
            // Try each pattern in the case
            for (pattern in case.patterns) {
                val patternContext = mutableMapOf<String, FunkValue>()
                if (matchesPattern(value, pattern, patternContext)) {
                    // Pattern matched, now check guard if present
                    val newContext = context.toMutableMap()
                    newContext.putAll(patternContext)
                    
                    // Evaluate guard condition if present
                    val guardPassed = case.guard?.let { guard ->
                        val guardResult = evaluateExpression(guard, newContext)
                        when (guardResult) {
                            is FunkBoolean -> guardResult.value
                            else -> throw RuntimeException("Guard condition must evaluate to Boolean, got ${guardResult::class.simpleName}")
                        }
                    } ?: true // No guard means it passes
                    
                    if (guardPassed) {
                        // Guard passed (or no guard), execute case body
                        return evaluateExpression(case.expression, newContext)
                    }
                    // Guard failed, continue to next pattern/case
                }
            }
        }
        throw RuntimeException("No matching pattern found for value: $value")
    }
    
    /**
     * Check if a value matches a pattern and extract bindings
     */
    private fun matchesPattern(value: FunkValue, pattern: Pattern, bindings: MutableMap<String, FunkValue>): Boolean {
        return when (pattern) {
            is ConstructorPattern -> {
                if (value is FunkVariant) {
                    if (value.constructor == pattern.constructor) {
                        // If pattern has record data, match it
                        val recordPattern = pattern.recordPattern
                        if (recordPattern != null) {
                            val valueData = value.value
                            if (valueData != null) {
                                matchesPattern(valueData, recordPattern, bindings)
                            } else {
                                false
                            }
                        } else {
                            // No record pattern means no data expected
                            value.value == null
                        }
                    } else {
                        false
                    }
                } else {
                    false
                }
            }
            is RecordPattern -> {
                if (value is FunkRecord) {
                    // Match all fields in the pattern
                    pattern.entries.all { entry ->
                        val fieldValue = value.fields[entry.name]
                        if (fieldValue != null) {
                            val entryPattern = entry.pattern
                            if (entryPattern != null) {
                                matchesPattern(fieldValue, entryPattern, bindings)
                            } else {
                                // Shorthand pattern {x} binds the field value to variable x
                                bindings[entry.name] = fieldValue
                                true
                            }
                        } else {
                            false
                        }
                    }
                } else {
                    false
                }
            }
            is VariablePattern -> {
                // Bind the value to the variable
                bindings[pattern.name] = value
                true
            }
            is WildcardPattern -> {
                // Wildcard matches anything
                true
            }
            else -> {
                throw RuntimeException("Unsupported pattern: ${pattern::class.simpleName}")
            }
        }
    }

    /**
     * Execute a statement
     */
    private fun executeStatement(stmt: Statement, context: MutableMap<String, FunkValue>): FunkValue {
        return when (stmt) {
            is LetStatement -> {
                val value = evaluateExpression(stmt.expression, context)
                context[stmt.variableName] = value
                value
            }
            is ExpressionStatement -> {
                evaluateExpression(stmt.expression, context)
            }
            else -> {
                throw RuntimeException("Unsupported statement: ${stmt::class.simpleName}")
            }
        }
    }
    
    /**
     * Evaluate binary operations using built-in ability dispatch
     */
    private fun evaluateBinaryOperation(left: FunkValue, operator: String, right: FunkValue): FunkValue {
        // Use optimized built-in ability dispatch for primitive types
        return when (operator) {
            "+" -> when {
                // Built-in NumAdd ability implementations for primitive types
                left is FunkInt && right is FunkInt -> FunkInt(left.value + right.value)
                left is FunkFloat && right is FunkFloat -> FunkFloat(left.value + right.value)
                // TODO: Add support for user-defined NumAdd implementations
                else -> throw ArithmeticException("+", left::class.simpleName ?: "Unknown", right::class.simpleName ?: "Unknown")
            }
            "++" -> when {
                left is FunkString && right is FunkString -> FunkString(left.value + right.value)
                else -> throw ArithmeticException("++", left::class.simpleName ?: "Unknown", right::class.simpleName ?: "Unknown")
            }
            "-" -> when {
                left is FunkInt && right is FunkInt -> FunkInt(left.value - right.value)
                left is FunkFloat && right is FunkFloat -> FunkFloat(left.value - right.value)
                else -> throw RuntimeException("Cannot subtract ${right::class.simpleName} from ${left::class.simpleName}")
            }
            "*" -> when {
                left is FunkInt && right is FunkInt -> FunkInt(left.value * right.value)
                left is FunkFloat && right is FunkFloat -> FunkFloat(left.value * right.value)
                else -> throw RuntimeException("Cannot multiply ${left::class.simpleName} and ${right::class.simpleName}")
            }
            "/" -> when {
                left is FunkInt && right is FunkInt -> FunkInt(left.value / right.value)
                left is FunkFloat && right is FunkFloat -> FunkFloat(left.value / right.value)
                else -> throw RuntimeException("Cannot divide ${left::class.simpleName} by ${right::class.simpleName}")
            }
            "%" -> when {
                left is FunkInt && right is FunkInt -> FunkInt(left.value % right.value)
                else -> throw RuntimeException("Cannot take modulo of ${left::class.simpleName} and ${right::class.simpleName}")
            }
            "==" -> {
                // Built-in Eq ability implementation for all types
                FunkBoolean(evaluateEquality(left, right))
            }
            "!=" -> {
                // Built-in Eq ability implementation (negated)
                FunkBoolean(!evaluateEquality(left, right))
            }
            "<" -> when {
                left is FunkInt && right is FunkInt -> FunkBoolean(left.value < right.value)
                left is FunkFloat && right is FunkFloat -> FunkBoolean(left.value < right.value)
                else -> throw RuntimeException("Cannot compare ${left::class.simpleName} and ${right::class.simpleName}")
            }
            ">" -> when {
                left is FunkInt && right is FunkInt -> FunkBoolean(left.value > right.value)
                left is FunkFloat && right is FunkFloat -> FunkBoolean(left.value > right.value)
                else -> throw RuntimeException("Cannot compare ${left::class.simpleName} and ${right::class.simpleName}")
            }
            "<=" -> when {
                left is FunkInt && right is FunkInt -> FunkBoolean(left.value <= right.value)
                left is FunkFloat && right is FunkFloat -> FunkBoolean(left.value <= right.value)
                else -> throw RuntimeException("Cannot compare ${left::class.simpleName} and ${right::class.simpleName}")
            }
            ">=" -> when {
                left is FunkInt && right is FunkInt -> FunkBoolean(left.value >= right.value)
                left is FunkFloat && right is FunkFloat -> FunkBoolean(left.value >= right.value)
                else -> throw RuntimeException("Cannot compare ${left::class.simpleName} and ${right::class.simpleName}")
            }
            "&&" -> FunkBoolean(isTruthy(left) && isTruthy(right))
            "||" -> FunkBoolean(isTruthy(left) || isTruthy(right))
            else -> throw RuntimeException("Unknown binary operator: $operator")
        }
    }

    /**
     * Desugar binary operators to ability method calls
     * e.g., x + y becomes NumAdd.num_add { l = x, r = y }
     * Returns null if operator doesn't map to an ability
     */
    private fun desugarOperatorToAbilityCall(expr: BinaryOperation, context: Map<String, FunkValue>): Expression? {
        val (abilityName, methodName) = when (expr.operator) {
            "+" -> Pair("NumAdd", "num_add")
            "-" -> Pair("NumSub", "num_sub")
            "*" -> Pair("NumMul", "num_mul")
            "/" -> Pair("NumDiv", "num_div")
            "==" -> Pair("Eq", "eq")
            else -> return null  // Not an ability operator
        }

        // Create: AbilityName.methodName { l = left, r = right }
        return FunctionCall(
            MemberAccess(
                Identifier(abilityName),
                methodName
            ),
            RecordLiteral(listOf(
                RecordFieldEntry("l", expr.left),
                RecordFieldEntry("r", expr.right)
            ))
        )
    }

    /**
     * Replace @ placeholders with the piped value in an expression
     * Used to desugar pipe operator: value |> function {param = @}
     */
    private fun replaceAtPlaceholder(expr: Expression, value: FunkValue, context: Map<String, FunkValue>): Expression {
        return when (expr) {
            // Base case: @ placeholder gets replaced with value literal
            is Identifier -> {
                if (expr.name == "@") {
                    // Convert FunkValue to Expression literal
                    valueToExpression(value)
                } else {
                    expr
                }
            }

            // Recursive cases: transform child expressions
            is RecordLiteral -> {
                // RecordLiteral has entries, not a simple map
                // Need to rebuild with transformed entries
                val newEntries = mutableListOf<RecordLiteralEntry>()
                for (entry in expr.entries) {
                    when (entry) {
                        is RecordFieldEntry -> {
                            val newValue = entry.value?.let { replaceAtPlaceholder(it, value, context) }
                            newEntries.add(RecordFieldEntry(entry.name, newValue))
                        }
                        is RecordSpreadEntry -> {
                            newEntries.add(RecordSpreadEntry(replaceAtPlaceholder(entry.expression, value, context)))
                        }
                    }
                }
                RecordLiteral(newEntries)
            }

            is FunctionCall -> {
                FunctionCall(
                    replaceAtPlaceholder(expr.function, value, context),
                    replaceAtPlaceholder(expr.arguments, value, context)
                )
            }

            is BinaryOperation -> {
                BinaryOperation(
                    replaceAtPlaceholder(expr.left, value, context),
                    expr.operator,
                    replaceAtPlaceholder(expr.right, value, context)
                )
            }

            is UnaryOperation -> {
                UnaryOperation(
                    expr.operator,
                    replaceAtPlaceholder(expr.operand, value, context)
                )
            }

            is IfExpression -> {
                IfExpression(
                    replaceAtPlaceholder(expr.condition, value, context),
                    replaceAtPlaceholder(expr.thenBranch, value, context),
                    replaceAtPlaceholder(expr.elseBranch, value, context)
                )
            }

            is MatchExpression -> {
                MatchExpression(
                    replaceAtPlaceholder(expr.expression, value, context),
                    expr.cases.map { case ->
                        MatchCase(
                            case.patterns,
                            case.guard?.let { replaceAtPlaceholder(it, value, context) },
                            replaceAtPlaceholder(case.expression, value, context)
                        )
                    }
                )
            }

            is DoExpression -> {
                DoExpression(
                    expr.statements.map { stmt ->
                        when (stmt) {
                            is LetStatement -> LetStatement(
                                stmt.variableName,
                                stmt.typeAnnotation,
                                replaceAtPlaceholder(stmt.expression, value, context)
                            )
                            is ExpressionStatement -> ExpressionStatement(
                                replaceAtPlaceholder(stmt.expression, value, context)
                            )
                        }
                    },
                    replaceAtPlaceholder(expr.result, value, context)
                )
            }

            is ArrayLiteral -> {
                ArrayLiteral(
                    expr.elements.map { replaceAtPlaceholder(it, value, context) }
                )
            }

            is LambdaExpression -> {
                // Replace placeholders in lambda body
                LambdaExpression(
                    expr.parameters,
                    replaceAtPlaceholder(expr.body, value, context),
                    expr.returnType,
                    expr.abilityConstraints
                )
            }

            is MemberAccess -> {
                MemberAccess(
                    replaceAtPlaceholder(expr.receiver, value, context),
                    expr.member
                )
            }

            is RecordUpdateExpression -> {
                RecordUpdateExpression(
                    replaceAtPlaceholder(expr.baseRecord, value, context),
                    expr.updates.map { update ->
                        RecordUpdateEntry(update.fieldPath, replaceAtPlaceholder(update.value, value, context))
                    }
                )
            }

            is PerformExpression -> {
                PerformExpression(
                    replaceAtPlaceholder(expr.expression, value, context),
                    expr.handlers
                )
            }

            // Literals and other expressions don't contain placeholders
            is IntLiteral, is FloatLiteral, is StringLiteral, is BooleanLiteral,
            is ArrayListLiteral, is TypeDeclaration, is EffectCallExpression -> expr

            else -> expr
        }
    }

    /**
     * Convert a FunkValue to an Expression literal
     * Used for desugaring pipe operator placeholders
     */
    private fun valueToExpression(value: FunkValue): Expression {
        return when (value) {
            is FunkInt -> IntLiteral(value.value)
            is FunkFloat -> FloatLiteral(value.value)
            is FunkString -> StringLiteral(value.value)
            is FunkBoolean -> BooleanLiteral(value.value)
            is FunkNull -> StringLiteral("null") // Create a placeholder since there's no NullLiteral object
            is FunkRecord -> {
                // Convert record to RecordLiteral
                val entries = value.fields.map { (name, v) ->
                    RecordFieldEntry(name, valueToExpression(v))
                }
                RecordLiteral(entries)
            }
            is FunkList -> ArrayLiteral(
                value.elements.map { valueToExpression(it) }
            )
            is FunkVariant -> {
                // Variant constructor application
                val constructorExpr = Identifier(value.constructor)
                if (value.value != null) {
                    FunctionCall(constructorExpr, valueToExpression(value.value))
                } else {
                    constructorExpr
                }
            }
            // For closures and other complex values, we can't convert them back to expressions
            // This should not happen in practice since @ is meant for simple values
            else -> throw RuntimeException("Cannot convert ${value::class.simpleName} to expression for pipe operator placeholder")
        }
    }

    /**
     * Evaluate equality using built-in Eq ability implementations
     */
    private fun evaluateEquality(left: FunkValue, right: FunkValue): Boolean {
        return when {
            // Built-in Eq implementations for primitive types
            left is FunkInt && right is FunkInt -> left.value == right.value
            left is FunkFloat && right is FunkFloat -> left.value == right.value
            left is FunkString && right is FunkString -> left.value == right.value
            left is FunkBoolean && right is FunkBoolean -> left.value == right.value
            left is FunkNull && right is FunkNull -> true
            
            // Built-in Eq implementations for built-in types
            left is FunkVariant && right is FunkVariant -> {
                left.constructor == right.constructor && 
                when {
                    left.value == null && right.value == null -> true
                    left.value != null && right.value != null -> evaluateEquality(left.value, right.value)
                    else -> false
                }
            }
            left is FunkList && right is FunkList -> {
                left.elements.size == right.elements.size &&
                left.elements.zip(right.elements).all { (l, r) -> evaluateEquality(l, r) }
            }
            left is FunkRecord && right is FunkRecord -> {
                left.fields.size == right.fields.size &&
                left.fields.all { (key, value) ->
                    right.fields.containsKey(key) && evaluateEquality(value, right.fields[key]!!)
                }
            }
            
            // Default case - different types are not equal
            else -> false
        }
    }
    
    /**
     * Evaluate unary operations
     */
    private fun evaluateUnaryOperation(operator: String, operand: FunkValue): FunkValue {
        if (operator == "-") {
            if (operand is FunkInt) {
                return FunkInt(-operand.value)
            } else if (operand is FunkFloat) {
                return FunkFloat(-operand.value)
            } else {
                throw RuntimeException("Cannot negate ${operand::class.simpleName}")
            }
        } else if (operator == "!") {
            return FunkBoolean(!isTruthy(operand))
        } else {
            throw RuntimeException("Unknown unary operator: $operator")
        }
    }
    
    /**
     * Call a function with arguments
     */
    /**
     * Call a function with record arguments, supporting partial application
     */
    fun callFunctionWithRecord(function: FunkValue, arguments: FunkRecord, context: MutableMap<String, FunkValue>): FunkValue {
        // println("DEBUG: callFunctionWithRecord called with function type: ${function::class.simpleName}")
        return when (function) {
            is NativeFunkFunction -> {
                // Call native function directly - it expects a single FunkValue argument
                function.call(listOf(arguments))
            }
            is SimpleFunkFunction -> {
                val providedParams = arguments.fields.keys
                val requiredParams = function.parameters.toSet()
                val missingParams = requiredParams - providedParams
                
                if (missingParams.isEmpty()) {
                    // All parameters provided - execute function
                    // Note: We don't check for unhandled effects here because the function body
                    // may contain perform expressions that will install handlers during execution

                    // Execute function with inherited context
                    val newContext = function.closure.toMutableMap()
                    
                    // CRITICAL FIX: Merge current context with closure context
                    // This ensures that all performers (like BasicLogger) are available for delegation
                    // For imports and handlers, ALWAYS use the current context value (prefer fresh over stale)
                    context.forEach { (key, value) ->
                        // Always overwrite with current context for these cases:
                        // - New keys not in closure
                        // - Effect/handler-related items (Console, LocalDisk, Log, FileSystem, etc.)
                        // - Native functions (__native_* or native_*)
                        if (!newContext.containsKey(key) ||
                            effectHandler.isEffectDeclared(key) ||
                            value is FunkRecord ||
                            value is NativeFunkFunction ||
                            key.startsWith("__native_") ||
                            key.startsWith("native_") ||
                            key == "__impl_mappings") {
                            newContext[key] = value
                        }
                    }
                    
                    
                    // Setup function context with proper effect infrastructure
                    effectHandler.setupFunctionContext(newContext, context)
                    
                    // Add recursive reference if this is a recursive function
                    function.recursiveName?.let { name ->
                        newContext[name] = function.recursiveFunction ?: function
                    }
                    
                    // Bind all parameters
                    for (param in function.parameters) {
                        newContext[param] = arguments.fields[param] ?: FunkNull
                    }
                    
                    // Execute with tail call optimization
                    executeWithTailCallOptimization(function, newContext)
                } else {
                    // Partial application - create partial function
                    FunkPartialFunction(
                        function,
                        arguments.fields,
                        missingParams.toList()
                    )
                }
            }
            is FunkPartialFunction -> {
                // Calling a partial function - combine arguments
                val combinedArgs = function.providedArguments + arguments.fields
                val newRecord = FunkRecord(combinedArgs)
                callFunctionWithRecord(function.originalFunction, newRecord, context)
            }
            else -> throw RuntimeException("Cannot call non-function value: ${function::class.simpleName}")
        }
    }

    fun callFunction(function: FunkValue, args: List<FunkValue>, context: MutableMap<String, FunkValue>): FunkValue {
        return when (function) {
            is NativeFunkFunction -> {
                // For native functions, convert args list to record if needed
                val argsRecord = if (args.size == 1 && args[0] is FunkRecord) {
                    args[0] as FunkRecord
                } else {
                    // Convert list to record with generic names
                    FunkRecord(args.mapIndexed { index, value -> "arg$index" to value }.toMap())
                }
                function.call(argsRecord.fields.values.toList())
            }
            is SimpleFunkFunction -> {
                // RUNTIME EFFECT VALIDATION: Check if function has unhandled effects
                if (function.hasUnhandledEffects()) {
                    val unhandledEffects = function.getUnhandledEffects()
                    val availableHandlers = (context["__handlers"] as? FunkRecord)?.fields?.keys ?: emptySet()
                    val actuallyUnhandledEffects = unhandledEffects - availableHandlers
                    
                    if (actuallyUnhandledEffects.isNotEmpty()) {
                        throw FunkException(
                            "Cannot execute function with unhandled effects: ${actuallyUnhandledEffects.joinToString(", ")}. " +
                            "Use 'perform ... in' to provide handlers for these effects."
                        )
                    }
                }
                
                        // Create new context with captured closure and parameters
                        val newContext = function.closure.toMutableMap()
                        
                        // Bind parameters (simplified - assume list of parameter names)
                        function.parameters.forEachIndexed { index, param ->
                            if (index < args.size) {
                                newContext[param] = args[index]
                            }
                        }
                        
                        // Propagate effect handlers from current context
                        context["__handlers"]?.let { 
                            newContext["__handlers"] = it 
                        }
                        
                        evaluateExpression(function.body, newContext)
            }
            else -> throw RuntimeException("Cannot call non-function value: ${function::class.simpleName}")
        }
    }
    
    /**
     * Check if a value is truthy
     */
    private fun isTruthy(value: FunkValue): Boolean {
        return when (value) {
            is FunkBoolean -> value.value
            is FunkNull -> false
            else -> true
        }
    }
    
    /**
     * Check if a do expression contains effect calls
     */
    private fun containsEffectCalls(expr: DoExpression): Boolean {
        // Check statements
        for (stmt in expr.statements) {
            when (stmt) {
                is LetStatement -> if (containsEffectCallsInExpression(stmt.expression)) return true
                is ExpressionStatement -> if (containsEffectCallsInExpression(stmt.expression)) return true
            }
        }
        // Check result expression
        return containsEffectCallsInExpression(expr.result)
    }
    
    /**
     * Check if an expression contains effect calls
     */
    private fun containsEffectCallsInExpression(expr: Expression): Boolean {
        return when (expr) {
            is EffectCallExpression -> true
            is DoExpression -> containsEffectCalls(expr)
            is FunctionCall -> containsEffectCallsInExpression(expr.function) || containsEffectCallsInExpression(expr.arguments)
            is BinaryOperation -> containsEffectCallsInExpression(expr.left) || containsEffectCallsInExpression(expr.right)
            is IfExpression -> containsEffectCallsInExpression(expr.condition) || 
                               containsEffectCallsInExpression(expr.thenBranch) || 
                               containsEffectCallsInExpression(expr.elseBranch)
            is MatchExpression -> containsEffectCallsInExpression(expr.expression) || 
                                  expr.cases.any { containsEffectCallsInExpression(it.expression) }
            is RecordLiteral -> expr.getFieldEntries().any { it.value?.let { v -> containsEffectCallsInExpression(v) } ?: false } ||
                                 expr.getSpreadEntries().any { containsEffectCallsInExpression(it.expression) }
            is LambdaExpression -> containsEffectCallsInExpression(expr.body)
            is MemberAccess -> containsEffectCallsInExpression(expr.receiver)
            is RecordUpdateExpression -> containsEffectCallsInExpression(expr.baseRecord) || 
                                         expr.updates.any { containsEffectCallsInExpression(it.value) }
            is PerformExpression -> containsEffectCallsInExpression(expr.expression)
            else -> false
        }
    }
    
    /**
     * Execute an effect call
     */
    
    
    /**
     * Execute a do block as a state machine (for effectful code)
     */
    private fun executeDoBlockAsStateMachine(expr: DoExpression, context: MutableMap<String, FunkValue>): FunkValue {
        // Create a state machine for this do block
        val stateMachine = StateMachine.create(
            localVariables = context.toMutableMap(),
            effectContext = EffectContext(
                handlers = extractEffectHandlers(context),
                currentHandler = context["__current_handler"] as? FunkRecord
            )
        )
        
        // Compile do block into state machine states
        val states = compileDoBlockToStates(expr)
        
        // Execute state machine with Loom virtual thread
        return executeStateMachineAsync(stateMachine, states, context)
    }
    
    /**
     * Execute a do block sequentially (for pure computations)
     */
    private fun executeDoBlockSequentially(expr: DoExpression, context: MutableMap<String, FunkValue>): FunkValue {
        val localContext = context.toMutableMap()
        
        // Execute statements
        for (stmt in expr.statements) {
            executeStatement(stmt, localContext)
        }
        
        // Execute result expression
        return evaluateExpression(expr.result, localContext)
    }
    
    /**
     * Execute a state machine with async support using Project Loom
     */
    private fun executeStateMachine(stateMachine: StateMachine, expr: DoExpression, context: MutableMap<String, FunkValue>): FunkValue {
        var currentMachine = stateMachine
        val localContext = currentMachine.localVariables
        
        // Execute statements sequentially, handling async effects
        for ((index, stmt) in expr.statements.withIndex()) {
            when (stmt) {
                is LetStatement -> {
                    // Check if the expression contains async effects
                    if (containsEffectCallsInExpression(stmt.expression)) {
                        val result = executeExpressionWithAsyncSupport(stmt.expression, localContext, currentMachine)
                        localContext[stmt.variableName] = result
                    } else {
                        val result = evaluateExpression(stmt.expression, localContext)
                        localContext[stmt.variableName] = result
                    }
                }
                is ExpressionStatement -> {
                    if (containsEffectCallsInExpression(stmt.expression)) {
                        executeExpressionWithAsyncSupport(stmt.expression, localContext, currentMachine)
                    } else {
                        evaluateExpression(stmt.expression, localContext)
                    }
                }
            }
        }
        
        // Execute result expression
        return if (containsEffectCallsInExpression(expr.result)) {
            executeExpressionWithAsyncSupport(expr.result, localContext, currentMachine)
        } else {
            evaluateExpression(expr.result, localContext)
        }
    }
    
    /**
     * Execute an expression with async effect support using virtual threads
     */
    private fun executeExpressionWithAsyncSupport(
        expr: Expression, 
        context: MutableMap<String, FunkValue>,
        stateMachine: StateMachine
    ): FunkValue {
        return when (expr) {
            is EffectCallExpression -> {
                evaluateExpression(expr, context)
            }
            is DoExpression -> {
                executeStateMachine(stateMachine, expr, context)
            }
            else -> {
                // For non-effect expressions, execute normally
                evaluateExpression(expr, context)
            }
        }
    }
    

    
    /**
     * Extract effect handlers from context
     */
    private fun extractEffectHandlers(context: MutableMap<String, FunkValue>): Map<String, FunkRecord> {
        val handlers = mutableMapOf<String, FunkRecord>()
        val handlersValue = context["__handlers"] as? FunkRecord
        if (handlersValue != null) {
            handlersValue.fields.forEach { (name, value) ->
                if (value is FunkRecord) {
                    handlers[name] = value
                }
            }
        }
        return handlers
    }
    
    /**
     * Compile a do block into state machine states
     */
    private fun compileDoBlockToStates(expr: DoExpression): List<StateMachineState> {
        val states = mutableListOf<StateMachineState>()
        
        // Each statement becomes a state
        expr.statements.forEachIndexed { index, statement ->
            when (statement) {
                is LetStatement -> {
                    states.add(StateMachineState.LetState(index, statement.variableName, statement.expression))
                }
                is ExpressionStatement -> {
                    if (containsEffectCallsInExpression(statement.expression)) {
                        // Effect call - this is a suspension point
                        states.add(StateMachineState.EffectState(index, statement.expression))
                    } else {
                        // Pure expression
                        states.add(StateMachineState.ExpressionState(index, statement.expression))
                    }
                }
            }
        }
        
        // Final result expression
        states.add(StateMachineState.ResultState(expr.statements.size, expr.result))
        
        return states
    }
    
    /**
     * Execute state machine asynchronously using Loom virtual threads
     */
    private fun executeStateMachineAsync(
        initialStateMachine: StateMachine, 
        states: List<StateMachineState>, 
        context: MutableMap<String, FunkValue>
    ): FunkValue {
        // Simple state machine execution without async for now
        var currentMachine = initialStateMachine
        var result: FunkValue = FunkNull
        
        for (state in states) {
            when (state) {
                is StateMachineState.LetState -> {
                    val value = evaluateExpression(state.expression, currentMachine.localVariables)
                    currentMachine.localVariables[state.variableName] = value
                }
                is StateMachineState.ExpressionState -> {
                    evaluateExpression(state.expression, currentMachine.localVariables)
                }
                is StateMachineState.EffectState -> {
                    // Execute effect call synchronously for now
                    result = evaluateExpression(state.expression, currentMachine.localVariables)
                }
                is StateMachineState.ResultState -> {
                    result = evaluateExpression(state.expression, currentMachine.localVariables)
                }
            }
        }
        
        return result
    }
    

    
    /**
     * Convert expressions like Log.log to EffectCallExpression
     */
    private fun convertToEffectCall(expression: Expression, context: MutableMap<String, FunkValue>): EffectCallExpression? {
        return when (expression) {
            is FunctionCall -> {
                if (expression.function is MemberAccess) {
                    val memberAccess = expression.function as MemberAccess
                    if (memberAccess.receiver is Identifier) {
                        val effectName = (memberAccess.receiver as Identifier).name
                        val operationName = memberAccess.member
                        
                        // Check if this is a known effect
                        if (context.containsKey(effectName) && context[effectName] is FunkRecord) {
                            return EffectCallExpression(effectName, operationName, expression.arguments)
                        }
                    }
                }
                null
            }
            else -> null
        }
    }
    
    /**
     * Check if an effect is declared (globally available)
     */
    private fun isEffectDeclared(effectName: String): Boolean {
        return effectHandler.isEffectDeclared(effectName)
    }
    
    
    /**
     * Find which effect a handler implements based on stored impl mappings
     */
    private fun findEffectForPerformer(performerName: String, context: MutableMap<String, FunkValue>): String {
        return effectHandler.findEffectForPerformer(performerName, context)
    }
    
    /**
     * Create a direct performer for non-parameterized implementations
     */
    private fun createDirectPerformer(item: ImplDeclaration, context: MutableMap<String, FunkValue>): FunkValue {
        val operations = item.operations.associate { op ->
            // Extract parameter names from the record type annotation
            val paramNames = when (val paramType = op.paramType) {
                is RecordTypeAnnotation -> {
                    // Extract field names from record type: {msg: String, level: Int} -> ["msg", "level"]
                    paramType.entries.map { it.name }
                }
                is IdentifierTypeAnnotation -> {
                    // Single parameter: String -> ["value"]
                    listOf("value")
                }
                else -> {
                    // Fallback for complex types
                    listOf("args")
                }
            }
            
            op.name to when (val impl = op.implementation) {
                is LambdaExpression -> {
                    // Evaluate the lambda expression to create a proper function with parameters
                    evaluateExpression(impl, context)
                }
                else -> {
                    // For non-lambda implementations, wrap in a function
                    SimpleFunkFunction(
                        paramNames,
                        impl,
                        context.toMap()
                    )
                }
            }
        }
        val performer = FunkRecord(operations)
        context[item.performerName] = performer
        
        // Store the impl mapping for later lookup
        @Suppress("UNCHECKED_CAST")
        val implMappings = context.getOrPut("__impl_mappings") { 
            FunkRecord(mutableMapOf<String, FunkValue>()) 
        } as FunkRecord
        implMappings.fields as MutableMap<String, FunkValue>
        (implMappings.fields as MutableMap)[item.performerName] = FunkString(item.effectName)
        
        return performer
    }
    
    /**
     * Create a performer factory for parameterized implementations
     */
    private fun createPerformerFactory(item: ImplDeclaration, context: MutableMap<String, FunkValue>): FunkValue {
        // Extract parameter names from performer params
        val performerParamNames = item.performerParams.map { it.name }
        
        // Create a special performer factory that can be called to create instances
        val factory = PerformerFactory(item, context.toMap())
        
        // Store factory under performer name
        context[item.performerName] = factory
        
        // Store the impl mapping
        @Suppress("UNCHECKED_CAST")
        val implMappings = context.getOrPut("__impl_mappings") { 
            FunkRecord(mutableMapOf<String, FunkValue>()) 
        } as FunkRecord
        (implMappings.fields as MutableMap)[item.performerName] = FunkString(item.effectName)
        
        return factory
    }
    
    /**
     * Performer factory for parameterized implementations
     */
    class PerformerFactory(
        private val implDeclaration: ImplDeclaration,
        private val capturedContext: Map<String, FunkValue>
    ) : FunkValue() {
        override fun getDisplayString(): String = "<factory-${implDeclaration.performerName}>"
        
        fun createPerformer(params: FunkRecord): FunkRecord {
            // Create operations with access to handler parameters
            val operations = implDeclaration.operations.associate { op ->
                // Extract parameter names from the operation type
                val paramNames = when (val paramType = op.paramType) {
                    is RecordTypeAnnotation -> paramType.entries.map { it.name }
                    is IdentifierTypeAnnotation -> listOf("value")
                    else -> listOf("args")
                }
                
                // Create function with access to both operation params and handler params
                val combinedContext = capturedContext.toMutableMap()
                params.fields.forEach { (name, value) ->
                    combinedContext[name] = value
                }
                
                op.name to SimpleFunkFunction(
                    paramNames,
                    when (val impl = op.implementation) {
                        is LambdaExpression -> impl.body
                        else -> impl
                    },
                    combinedContext
                )
            }
            
            return FunkRecord(operations)
        }
    }
    
    /**
     * Native function that can be called from Funk code
     */
    
    /**
     * Register native functions and load stdlib modules
     */
    private fun registerNativeFunctions(context: MutableMap<String, FunkValue>) {
        // Use the centralized native functions registry
        NativeFunctions.registerAll(context)

        // Register built-in constructors (always available without imports)
        registerBuiltinConstructors(context)

        // Register built-in abilities (NumAdd, NumSub, etc.)
        registerBuiltInAbilities(context)

        // Load core stdlib modules that should always be available
        loadCoreStdLibModules(context)
        
        // Create built-in effect handlers
        context["NativeConsole"] = FunkRecord(mapOf(
            "print" to (context["__native_console_print"] ?: throw RuntimeException("__native_console_print not found")),
            "debug" to (context["__native_console_debug"] ?: throw RuntimeException("__native_console_debug not found"))
        ))
        
        // Add legacy native functions for backward compatibility
        context["native_print"] = context["__native_console_print"]!!
        context["native_debug"] = context["__native_console_debug"]!!
        
        context["NativeRandom"] = FunkRecord(mapOf(
            "nextInt" to (context["native_random_int"] ?: throw RuntimeException("native_random_int not found")),
            "nextDouble" to (context["native_random_double"] ?: throw RuntimeException("native_random_double not found"))
        ))
    }
    
    /**
     * Register built-in constructors that are always available without imports
     */
    private fun registerBuiltinConstructors(context: MutableMap<String, FunkValue>) {
        // Option constructors
        context["Some"] = NativeFunkFunction("Some") { args ->
            if (args.size != 1) {
                throw RuntimeException("Some constructor expects exactly one argument")
            }
            val value = args[0]
            // Accept any record - the user may define their own Option type with different field names
            if (value is FunkRecord) {
                FunkVariant("Some", value)
            } else {
                throw RuntimeException("Some constructor expects a record argument")
            }
        }

        context["None"] = FunkVariant("None", null)

        // Result constructors
        context["Ok"] = NativeFunkFunction("Ok") { args ->
            if (args.size != 1) {
                throw RuntimeException("Ok constructor expects exactly one argument")
            }
            val value = args[0]
            // Accept any record - the user may define their own Result type with different field names
            if (value is FunkRecord) {
                FunkVariant("Ok", value)
            } else {
                throw RuntimeException("Ok constructor expects a record argument")
            }
        }

        context["Err"] = NativeFunkFunction("Err") { args ->
            if (args.size != 1) {
                throw RuntimeException("Err constructor expects exactly one argument")
            }
            val value = args[0]
            if (value is FunkRecord && value.fields.containsKey("err")) {
                FunkVariant("Err", value)
            } else {
                throw RuntimeException("Err constructor expects a record with 'err' field")
            }
        }
        
        // Ordering constructors
        context["LT"] = FunkVariant("LT", null)
        context["EQ"] = FunkVariant("EQ", null)
        context["GT"] = FunkVariant("GT", null)
        
        // Built-in List constructor (for internal use)
        context["List"] = NativeFunkFunction("List") { args ->
            if (args.size != 1) {
                throw RuntimeException("List constructor expects exactly one argument")
            }
            val elements = args[0]
            if (elements is FunkArrayList) {
                // Convert ArrayList to built-in List representation
                FunkList(elements.elements)
            } else {
                throw RuntimeException("List constructor expects an array")
            }
        }
        
        // Built-in Map constructor (for internal use)
        context["Map"] = NativeFunkFunction("Map") { args ->
            if (args.size != 1) {
                throw RuntimeException("Map constructor expects exactly one argument")
            }
            val record = args[0]
            if (record is FunkRecord) {
                // Convert record to built-in Map representation
                val entries: Map<FunkValue, FunkValue> = record.fields.map { (k, v) -> FunkString(k) as FunkValue to v }.toMap()
                FunkMap(entries)
            } else {
                throw RuntimeException("Map constructor expects a record")
            }
        }
        
        // Built-in Set constructor (for internal use)
        context["Set"] = NativeFunkFunction("Set") { args ->
            if (args.size != 1) {
                throw RuntimeException("Set constructor expects exactly one argument")
            }
            val elements = args[0]
            if (elements is FunkArrayList) {
                // Convert ArrayList to built-in Set representation
                FunkSet(elements.elements.toSet())
            } else {
                throw RuntimeException("Set constructor expects an array")
            }
        }
    }

    /**
     * Register built-in abilities that are always available
     * Also register implementations for built-in types (Int, Float, String, Boolean)
     */
    private fun registerBuiltInAbilities(context: MutableMap<String, FunkValue>) {
        // Register ability declarations
        context["NumAdd_ability"] = FunkRecord(mapOf("num_add" to FunkNull))
        context["NumSub_ability"] = FunkRecord(mapOf("num_sub" to FunkNull))
        context["NumMul_ability"] = FunkRecord(mapOf("num_mul" to FunkNull))
        context["NumDiv_ability"] = FunkRecord(mapOf("num_div" to FunkNull))
        context["Eq_ability"] = FunkRecord(mapOf("eq" to FunkNull))
        context["Inspect_ability"] = FunkRecord(mapOf("to_str" to FunkNull))

        // Register implementations for Int
        context["NumAdd_Int"] = FunkRecord(mapOf(
            "num_add" to context["__native_int_add"]!!
        ))
        context["NumSub_Int"] = FunkRecord(mapOf(
            "num_sub" to context["__native_int_subtract"]!!
        ))
        context["NumMul_Int"] = FunkRecord(mapOf(
            "num_mul" to context["__native_int_multiply"]!!
        ))
        context["NumDiv_Int"] = FunkRecord(mapOf(
            "num_div" to context["__native_int_divide"]!!
        ))
        context["Eq_Int"] = FunkRecord(mapOf(
            "eq" to context["__native_int_equals"]!!
        ))
        context["Inspect_Int"] = FunkRecord(mapOf(
            "to_str" to context["__native_int_to_string"]!!
        ))

        // Register implementations for Float
        context["NumAdd_Float"] = FunkRecord(mapOf(
            "num_add" to context["__native_float_add"]!!
        ))
        context["NumSub_Float"] = FunkRecord(mapOf(
            "num_sub" to context["__native_float_subtract"]!!
        ))
        context["NumMul_Float"] = FunkRecord(mapOf(
            "num_mul" to context["__native_float_multiply"]!!
        ))
        context["NumDiv_Float"] = FunkRecord(mapOf(
            "num_div" to context["__native_float_divide"]!!
        ))
        context["Eq_Float"] = FunkRecord(mapOf(
            "eq" to context["__native_float_equals"]!!
        ))
        context["Inspect_Float"] = FunkRecord(mapOf(
            "to_str" to context["__native_float_to_string"]!!
        ))

        // Register implementations for String
        context["Eq_String"] = FunkRecord(mapOf(
            "eq" to context["__native_string_equals"]!!
        ))
        context["Inspect_String"] = FunkRecord(mapOf(
            "to_str" to NativeFunkFunction("to_str_string") { args ->
                val record = args[0] as FunkRecord
                record.fields["val"] as FunkString
            }
        ))

        // Register implementations for Boolean
        context["Eq_Boolean"] = FunkRecord(mapOf(
            "eq" to context["__native_boolean_equals"]!!
        ))
        context["Inspect_Boolean"] = FunkRecord(mapOf(
            "to_str" to NativeFunkFunction("to_str_boolean") { args ->
                val record = args[0] as FunkRecord
                val value = record.fields["val"] as FunkBoolean
                FunkString(if (value.value) "true" else "false")
            }
        ))
    }

    /**
     * Automatically derive Eq or Inspect implementation for composite types
     * (Record, Variant, List, Array, Map, Set)
     * Returns a function that implements the operation, or null if derivation is not possible
     */
    private fun deriveDerivedAbilityImplementation(
        abilityName: String,
        operationName: String,
        typeArgs: List<String>,
        arguments: FunkValue
    ): FunkValue? {
        // Derive for Record, Variant, and collection types
        val derivableTypes = setOf("Record", "Variant", "List", "NonEmptyList", "ArrayList", "Map", "Set")
        if (!typeArgs.any { it in derivableTypes }) {
            return null
        }

        return when (abilityName) {
            "Eq" -> when (operationName) {
                "eq" -> NativeFunkFunction("derived_eq") { args ->
                    val record = args[0] as FunkRecord
                    val left = record.fields["l"]!!
                    val right = record.fields["r"]!!
                    FunkBoolean(deriveStructuralEquality(left, right))
                }
                else -> null
            }
            "Inspect" -> when (operationName) {
                "to_str" -> NativeFunkFunction("derived_to_str") { args ->
                    val record = args[0] as FunkRecord
                    val value = record.fields["val"]!!
                    FunkString(deriveStructuralInspect(value))
                }
                else -> null
            }
            else -> null
        }
    }

    /**
     * Derive structural equality for any two values
     */
    private fun deriveStructuralEquality(left: FunkValue, right: FunkValue): Boolean {
        return when {
            left is FunkInt && right is FunkInt -> left.value == right.value
            left is FunkFloat && right is FunkFloat -> left.value == right.value
            left is FunkString && right is FunkString -> left.value == right.value
            left is FunkBoolean && right is FunkBoolean -> left.value == right.value
            left is FunkNull && right is FunkNull -> true

            // Structural equality for records
            left is FunkRecord && right is FunkRecord -> {
                if (left.fields.keys != right.fields.keys) {
                    false
                } else {
                    left.fields.all { (key, leftValue) ->
                        val rightValue = right.fields[key]
                        rightValue != null && deriveStructuralEquality(leftValue, rightValue)
                    }
                }
            }

            // Structural equality for variants
            left is FunkVariant && right is FunkVariant -> {
                if (left.constructor != right.constructor) {
                    false
                } else {
                    when {
                        left.value == null && right.value == null -> true
                        left.value != null && right.value != null ->
                            deriveStructuralEquality(left.value, right.value)
                        else -> false
                    }
                }
            }

            // Structural equality for lists
            left is FunkList && right is FunkList -> {
                if (left.elements.size != right.elements.size) {
                    false
                } else {
                    left.elements.zip(right.elements).all { (l, r) ->
                        deriveStructuralEquality(l, r)
                    }
                }
            }

            // Structural equality for non-empty lists
            left is FunkNonEmptyList && right is FunkNonEmptyList -> {
                if (left.elements.size != right.elements.size) {
                    false
                } else {
                    left.elements.zip(right.elements).all { (l, r) ->
                        deriveStructuralEquality(l, r)
                    }
                }
            }

            // Structural equality for arrays (ArrayList)
            left is FunkArrayList && right is FunkArrayList -> {
                if (left.elements.size != right.elements.size) {
                    false
                } else {
                    left.elements.zip(right.elements).all { (l, r) ->
                        deriveStructuralEquality(l, r)
                    }
                }
            }

            // Structural equality for maps
            left is FunkMap && right is FunkMap -> {
                if (left.entries.size != right.entries.size) {
                    false
                } else {
                    left.entries.all { (key, leftValue) ->
                        val rightValue = right.entries[key]
                        rightValue != null && deriveStructuralEquality(leftValue, rightValue)
                    }
                }
            }

            // Structural equality for sets
            left is FunkSet && right is FunkSet -> {
                if (left.elements.size != right.elements.size) {
                    false
                } else {
                    // Check if all elements in left are in right
                    left.elements.all { leftElem ->
                        right.elements.any { rightElem ->
                            deriveStructuralEquality(leftElem, rightElem)
                        }
                    }
                }
            }

            else -> false
        }
    }

    /**
     * Derive structural inspect (string representation) for any value
     * Following Roc's Inspect.toStr style
     */
    private fun deriveStructuralInspect(value: FunkValue): String {
        return when (value) {
            is FunkInt -> value.value.toString()
            is FunkFloat -> value.value.toString()
            is FunkString -> "\"${value.value}\""
            is FunkBoolean -> if (value.value) "true" else "false"
            is FunkNull -> "null"

            // Inspect for records
            is FunkRecord -> {
                val fields = value.fields.entries.joinToString(", ") { (key, fieldValue) ->
                    "$key = ${deriveStructuralInspect(fieldValue)}"
                }
                "{ $fields }"
            }

            // Inspect for variants
            is FunkVariant -> {
                if (value.value == null) {
                    value.constructor
                } else {
                    "${value.constructor} ${deriveStructuralInspect(value.value)}"
                }
            }

            // Inspect for lists
            is FunkList -> {
                val elements = value.elements.joinToString(", ") { deriveStructuralInspect(it) }
                "List ($elements)"
            }

            // Inspect for non-empty lists
            is FunkNonEmptyList -> {
                val elements = value.elements.joinToString(", ") { deriveStructuralInspect(it) }
                "NonEmptyList ($elements)"
            }

            // Inspect for arrays (ArrayList)
            is FunkArrayList -> {
                val elements = value.elements.joinToString(", ") { deriveStructuralInspect(it) }
                "[$elements]"
            }

            // Inspect for maps
            is FunkMap -> {
                val entries = value.entries.entries.joinToString(", ") { (key, mapValue) ->
                    "${deriveStructuralInspect(key)}: ${deriveStructuralInspect(mapValue)}"
                }
                "Map {$entries}"
            }

            // Inspect for sets
            is FunkSet -> {
                val elements = value.elements.joinToString(", ") { deriveStructuralInspect(it) }
                "Set {$elements}"
            }

            // Inspect for functions
            is SimpleFunkFunction -> "<function>"
            is NativeFunkFunction -> "<native function>"

            else -> "<unknown>"
        }
    }

    /**
     * Load core stdlib modules that should always be available
     */
    private fun loadCoreStdLibModules(context: MutableMap<String, FunkValue>) {
        try {
            val parser = FunkParser()
            val currentDir = Paths.get(".").toAbsolutePath()

            // Find the project root (go up from interpreter directory if needed)
            val projectRoot = if (currentDir.toString().contains("/interpreter")) {
                // Go up to the parent directory (the actual project root)
                currentDir.parent?.parent ?: currentDir.parent ?: currentDir
            } else {
                currentDir
            }

            val moduleResolver = ModuleResolver(projectRoot, parser)

            // List of core modules to auto-import
            val coreModules = listOf(
                "std::core::abilities",
                "std::core::primitives_minimal"
            )

            for (modulePath in coreModules) {
                try {
                    loadStdLibModule(modulePath, moduleResolver, context)
                } catch (e: Exception) {
                    // Silently ignore missing modules - some might not exist yet
                }
            }

            // Register operators directly (since parsing operators from files is complex)
            registerCoreOperators(context)
        } catch (e: Exception) {
            // Silently ignore initialization errors
        }
    }
    
    /**
     * Register core operators directly in the context
     */
    private fun registerCoreOperators(context: MutableMap<String, FunkValue>) {
        // Arithmetic operators - directly call the native implementations
        context["(+)"] = NativeFunkFunction("(+)") { args ->
            // args is a List<FunkValue>, we expect exactly one FunkRecord argument
            if (args.size != 1) {
                throw RuntimeException("(+) expects exactly 1 record argument, got ${args.size}")
            }
            
            val record = args[0] as? FunkRecord ?: throw RuntimeException("(+) expects a record argument, got ${args[0]::class.simpleName}")
            val left = record.fields["left"] ?: throw RuntimeException("Missing 'left' argument for (+)")
            val right = record.fields["right"] ?: throw RuntimeException("Missing 'right' argument for (+)")
            
            // Determine type and call appropriate native function
            when {
                left is FunkInt && right is FunkInt -> {
                    val addFn = context["__native_int_add"] as? NativeFunkFunction
                        ?: throw RuntimeException("__native_int_add not found")
                    addFn.call(listOf(FunkRecord(mapOf("l" to left, "r" to right))))
                }
                left is FunkFloat && right is FunkFloat -> {
                    val addFn = context["__native_float_add"] as? NativeFunkFunction
                        ?: throw RuntimeException("__native_float_add not found")
                    addFn.call(listOf(FunkRecord(mapOf("l" to left, "r" to right))))
                }
                else -> throw RuntimeException("Unsupported types for (+): ${left::class.simpleName}, ${right::class.simpleName}")
            }
        }
        
        context["(-)"] = NativeFunkFunction("(-)") { args ->
            if (args.size != 1) {
                throw RuntimeException("(-) expects exactly 1 record argument, got ${args.size}")
            }
            
            val record = args[0] as? FunkRecord ?: throw RuntimeException("(-) expects a record argument, got ${args[0]::class.simpleName}")
            val left = record.fields["left"] ?: throw RuntimeException("Missing 'left' argument for (-)")
            val right = record.fields["right"] ?: throw RuntimeException("Missing 'right' argument for (-)")
            
            when {
                left is FunkInt && right is FunkInt -> {
                    val subFn = context["__native_int_subtract"] as? NativeFunkFunction
                        ?: throw RuntimeException("__native_int_subtract not found")
                    subFn.call(listOf(FunkRecord(mapOf("l" to left, "r" to right))))
                }
                left is FunkFloat && right is FunkFloat -> {
                    val subFn = context["__native_float_subtract"] as? NativeFunkFunction
                        ?: throw RuntimeException("__native_float_subtract not found")
                    subFn.call(listOf(FunkRecord(mapOf("l" to left, "r" to right))))
                }
                else -> throw RuntimeException("Unsupported types for (-): ${left::class.simpleName}, ${right::class.simpleName}")
            }
        }
        
        context["(*)"] = NativeFunkFunction("(*)") { args ->
            if (args.size != 1) {
                throw RuntimeException("(*) expects exactly 1 record argument, got ${args.size}")
            }
            
            val record = args[0] as? FunkRecord ?: throw RuntimeException("(*) expects a record argument, got ${args[0]::class.simpleName}")
            val left = record.fields["left"] ?: throw RuntimeException("Missing 'left' argument for (*)")
            val right = record.fields["right"] ?: throw RuntimeException("Missing 'right' argument for (*)")
            
            when {
                left is FunkInt && right is FunkInt -> {
                    val mulFn = context["__native_int_multiply"] as? NativeFunkFunction
                        ?: throw RuntimeException("__native_int_multiply not found")
                    mulFn.call(listOf(FunkRecord(mapOf("l" to left, "r" to right))))
                }
                left is FunkFloat && right is FunkFloat -> {
                    val mulFn = context["__native_float_multiply"] as? NativeFunkFunction
                        ?: throw RuntimeException("__native_float_multiply not found")
                    mulFn.call(listOf(FunkRecord(mapOf("l" to left, "r" to right))))
                }
                else -> throw RuntimeException("Unsupported types for (*): ${left::class.simpleName}, ${right::class.simpleName}")
            }
        }
        
        // String concatenation operator
        context["(++)"] = NativeFunkFunction("(++)") { args ->
            if (args.size != 1) {
                throw RuntimeException("(++) expects exactly 1 record argument, got ${args.size}")
            }
            
            val record = args[0] as? FunkRecord ?: throw RuntimeException("(++) expects a record argument, got ${args[0]::class.simpleName}")
            val left = record.fields["left"] ?: throw RuntimeException("Missing 'left' argument for (++)")
            val right = record.fields["right"] ?: throw RuntimeException("Missing 'right' argument for (++)")
            
            when {
                left is FunkString && right is FunkString -> {
                    val concatFn = context["__native_string_concat"] as? NativeFunkFunction
                        ?: throw RuntimeException("__native_string_concat not found")
                    concatFn.call(listOf(FunkRecord(mapOf("l" to left, "r" to right))))
                }
                else -> throw RuntimeException("Unsupported types for (++): ${left::class.simpleName}, ${right::class.simpleName}")
            }
        }
        
        // Comparison operators
        context["(==)"] = NativeFunkFunction("(==)") { args ->
            if (args.size != 1) {
                throw RuntimeException("(==) expects exactly 1 record argument, got ${args.size}")
            }
            
            val record = args[0] as? FunkRecord ?: throw RuntimeException("(==) expects a record argument, got ${args[0]::class.simpleName}")
            val left = record.fields["left"] ?: throw RuntimeException("Missing 'left' argument for (==)")
            val right = record.fields["right"] ?: throw RuntimeException("Missing 'right' argument for (==)")
            
            when {
                left is FunkInt && right is FunkInt -> {
                    val eqFn = context["__native_int_equals"] as? NativeFunkFunction
                        ?: throw RuntimeException("__native_int_equals not found")
                    eqFn.call(listOf(FunkRecord(mapOf("l" to left, "r" to right))))
                }
                left is FunkString && right is FunkString -> {
                    val eqFn = context["__native_string_equals"] as? NativeFunkFunction
                        ?: throw RuntimeException("__native_string_equals not found")
                    eqFn.call(listOf(FunkRecord(mapOf("l" to left, "r" to right))))
                }
                left is FunkBoolean && right is FunkBoolean -> {
                    FunkBoolean(left.value == right.value)
                }
                else -> throw RuntimeException("Unsupported types for (==): ${left::class.simpleName}, ${right::class.simpleName}")
            }
        }
        
        context["(>)"] = NativeFunkFunction("(>)") { args ->
            if (args.size != 1) {
                throw RuntimeException("(>) expects exactly 1 record argument, got ${args.size}")
            }
            
            val record = args[0] as? FunkRecord ?: throw RuntimeException("(>) expects a record argument, got ${args[0]::class.simpleName}")
            val left = record.fields["left"] ?: throw RuntimeException("Missing 'left' argument for (>)")
            val right = record.fields["right"] ?: throw RuntimeException("Missing 'right' argument for (>)")
            
            when {
                left is FunkInt && right is FunkInt -> {
                    val gtFn = context["__native_int_greater_than"] as? NativeFunkFunction
                        ?: throw RuntimeException("__native_int_greater_than not found")
                    gtFn.call(listOf(FunkRecord(mapOf("l" to left, "r" to right))))
                }
                left is FunkString && right is FunkString -> {
                    val gtFn = context["__native_string_greater_than"] as? NativeFunkFunction
                        ?: throw RuntimeException("__native_string_greater_than not found")
                    gtFn.call(listOf(FunkRecord(mapOf("l" to left, "r" to right))))
                }
                else -> throw RuntimeException("Unsupported types for (>): ${left::class.simpleName}, ${right::class.simpleName}")
            }
        }
        
        context["(<)"] = NativeFunkFunction("(<)") { args ->
            if (args.size != 1) {
                throw RuntimeException("(<) expects exactly 1 record argument, got ${args.size}")
            }
            
            val record = args[0] as? FunkRecord ?: throw RuntimeException("(<) expects a record argument, got ${args[0]::class.simpleName}")
            val left = record.fields["left"] ?: throw RuntimeException("Missing 'left' argument for (<)")
            val right = record.fields["right"] ?: throw RuntimeException("Missing 'right' argument for (<)")
            
            when {
                left is FunkInt && right is FunkInt -> {
                    val ltFn = context["__native_int_less_than"] as? NativeFunkFunction
                        ?: throw RuntimeException("__native_int_less_than not found")
                    ltFn.call(listOf(FunkRecord(mapOf("l" to left, "r" to right))))
                }
                left is FunkString && right is FunkString -> {
                    val ltFn = context["__native_string_less_than"] as? NativeFunkFunction
                        ?: throw RuntimeException("__native_string_less_than not found")
                    ltFn.call(listOf(FunkRecord(mapOf("l" to left, "r" to right))))
                }
                else -> throw RuntimeException("Unsupported types for (<): ${left::class.simpleName}, ${right::class.simpleName}")
            }
        }
        
        // Division operator (/)
        context["(/)"] = NativeFunkFunction("(/)") { args ->
            if (args.size != 1) {
                throw RuntimeException("(/) expects exactly 1 record argument, got ${args.size}")
            }
            
            val record = args[0] as? FunkRecord ?: throw RuntimeException("(/) expects a record argument, got ${args[0]::class.simpleName}")
            val left = record.fields["left"] ?: throw RuntimeException("Missing 'left' argument for (/)")
            val right = record.fields["right"] ?: throw RuntimeException("Missing 'right' argument for (/)")
            
            when {
                left is FunkInt && right is FunkInt -> {
                    val divFn = context["__native_int_divide"] as? NativeFunkFunction
                        ?: throw RuntimeException("__native_int_divide not found")
                    divFn.call(listOf(FunkRecord(mapOf("l" to left, "r" to right))))
                }
                left is FunkFloat && right is FunkFloat -> {
                    val divFn = context["__native_float_divide"] as? NativeFunkFunction
                        ?: throw RuntimeException("__native_float_divide not found")
                    divFn.call(listOf(FunkRecord(mapOf("l" to left, "r" to right))))
                }
                else -> throw RuntimeException("Unsupported types for (/): ${left::class.simpleName}, ${right::class.simpleName}")
            }
        }
        
        // Not equals operator (!=)
        context["(!=)"] = NativeFunkFunction("(!=)") { args ->
            if (args.size != 1) {
                throw RuntimeException("(!=) expects exactly 1 record argument, got ${args.size}")
            }
            
            val record = args[0] as? FunkRecord ?: throw RuntimeException("(!=) expects a record argument, got ${args[0]::class.simpleName}")
            val left = record.fields["left"] ?: throw RuntimeException("Missing 'left' argument for (!=)")
            val right = record.fields["right"] ?: throw RuntimeException("Missing 'right' argument for (!=)")
            
            // Implement != as not(==)
            when {
                left is FunkInt && right is FunkInt -> {
                    val eqFn = context["__native_int_equals"] as? NativeFunkFunction
                        ?: throw RuntimeException("__native_int_equals not found")
                    val result = eqFn.call(listOf(FunkRecord(mapOf("l" to left, "r" to right))))
                    FunkBoolean(!(result as FunkBoolean).value)
                }
                left is FunkString && right is FunkString -> {
                    val eqFn = context["__native_string_equals"] as? NativeFunkFunction
                        ?: throw RuntimeException("__native_string_equals not found")
                    val result = eqFn.call(listOf(FunkRecord(mapOf("l" to left, "r" to right))))
                    FunkBoolean(!(result as FunkBoolean).value)
                }
                left is FunkBoolean && right is FunkBoolean -> {
                    FunkBoolean(left.value != right.value)
                }
                else -> throw RuntimeException("Unsupported types for (!=): ${left::class.simpleName}, ${right::class.simpleName}")
            }
        }
        
        // Less than or equal operator (<=)
        context["(<=)"] = NativeFunkFunction("(<=)") { args ->
            if (args.size != 1) {
                throw RuntimeException("(<=) expects exactly 1 record argument, got ${args.size}")
            }
            
            val record = args[0] as? FunkRecord ?: throw RuntimeException("(<=) expects a record argument, got ${args[0]::class.simpleName}")
            val left = record.fields["left"] ?: throw RuntimeException("Missing 'left' argument for (<=)")
            val right = record.fields["right"] ?: throw RuntimeException("Missing 'right' argument for (<=)")
            
            // Implement <= as not(>)
            when {
                left is FunkInt && right is FunkInt -> {
                    val gtFn = context["__native_int_greater_than"] as? NativeFunkFunction
                        ?: throw RuntimeException("__native_int_greater_than not found")
                    val result = gtFn.call(listOf(FunkRecord(mapOf("l" to left, "r" to right))))
                    FunkBoolean(!(result as FunkBoolean).value)
                }
                left is FunkString && right is FunkString -> {
                    val gtFn = context["__native_string_greater_than"] as? NativeFunkFunction
                        ?: throw RuntimeException("__native_string_greater_than not found")
                    val result = gtFn.call(listOf(FunkRecord(mapOf("l" to left, "r" to right))))
                    FunkBoolean(!(result as FunkBoolean).value)
                }
                else -> throw RuntimeException("Unsupported types for (<=): ${left::class.simpleName}, ${right::class.simpleName}")
            }
        }
        
        // Greater than or equal operator (>=)
        context["(>=)"] = NativeFunkFunction("(>=)") { args ->
            if (args.size != 1) {
                throw RuntimeException("(>=) expects exactly 1 record argument, got ${args.size}")
            }
            
            val record = args[0] as? FunkRecord ?: throw RuntimeException("(>=) expects a record argument, got ${args[0]::class.simpleName}")
            val left = record.fields["left"] ?: throw RuntimeException("Missing 'left' argument for (>=)")
            val right = record.fields["right"] ?: throw RuntimeException("Missing 'right' argument for (>=)")
            
            // Implement >= as not(<)
            when {
                left is FunkInt && right is FunkInt -> {
                    val ltFn = context["__native_int_less_than"] as? NativeFunkFunction
                        ?: throw RuntimeException("__native_int_less_than not found")
                    val result = ltFn.call(listOf(FunkRecord(mapOf("l" to left, "r" to right))))
                    FunkBoolean(!(result as FunkBoolean).value)
                }
                left is FunkString && right is FunkString -> {
                    val ltFn = context["__native_string_less_than"] as? NativeFunkFunction
                        ?: throw RuntimeException("__native_string_less_than not found")
                    val result = ltFn.call(listOf(FunkRecord(mapOf("l" to left, "r" to right))))
                    FunkBoolean(!(result as FunkBoolean).value)
                }
                else -> throw RuntimeException("Unsupported types for (>=): ${left::class.simpleName}, ${right::class.simpleName}")
            }
        }
        
        // Logical AND operator (&&)
        context["(&&)"] = NativeFunkFunction("(&&)") { args ->
            if (args.size != 1) {
                throw RuntimeException("(&&) expects exactly 1 record argument, got ${args.size}")
            }
            
            val record = args[0] as? FunkRecord ?: throw RuntimeException("(&&) expects a record argument, got ${args[0]::class.simpleName}")
            val left = record.fields["left"] ?: throw RuntimeException("Missing 'left' argument for (&&)")
            val right = record.fields["right"] ?: throw RuntimeException("Missing 'right' argument for (&&)")
            
            when {
                left is FunkBoolean && right is FunkBoolean -> {
                    FunkBoolean(left.value && right.value)
                }
                else -> throw RuntimeException("Unsupported types for (&&): ${left::class.simpleName}, ${right::class.simpleName}")
            }
        }
        
        // Logical OR operator (||)
        context["(||)"] = NativeFunkFunction("(||)") { args ->
            if (args.size != 1) {
                throw RuntimeException("(||) expects exactly 1 record argument, got ${args.size}")
            }
            
            val record = args[0] as? FunkRecord ?: throw RuntimeException("(||) expects a record argument, got ${args[0]::class.simpleName}")
            val left = record.fields["left"] ?: throw RuntimeException("Missing 'left' argument for (||)")
            val right = record.fields["right"] ?: throw RuntimeException("Missing 'right' argument for (||)")
            
            when {
                left is FunkBoolean && right is FunkBoolean -> {
                    FunkBoolean(left.value || right.value)
                }
                else -> throw RuntimeException("Unsupported types for (||): ${left::class.simpleName}, ${right::class.simpleName}")
            }
        }
        
        // Legacy boolean functions
        context["boolAnd"] = NativeFunkFunction("boolAnd") { args ->
            if (args.size != 1) {
                throw RuntimeException("boolAnd expects exactly 1 record argument, got ${args.size}")
            }
            
            val record = args[0] as? FunkRecord ?: throw RuntimeException("boolAnd expects a record argument, got ${args[0]::class.simpleName}")
            val a = record.fields["a"] as? FunkBoolean ?: throw RuntimeException("Missing 'a' argument for boolAnd")
            val b = record.fields["b"] as? FunkBoolean ?: throw RuntimeException("Missing 'b' argument for boolAnd")
            
            FunkBoolean(a.value && b.value)
        }
        
        context["boolOr"] = NativeFunkFunction("boolOr") { args ->
            if (args.size != 1) {
                throw RuntimeException("boolOr expects exactly 1 record argument, got ${args.size}")
            }
            
            val record = args[0] as? FunkRecord ?: throw RuntimeException("boolOr expects a record argument, got ${args[0]::class.simpleName}")
            val a = record.fields["a"] as? FunkBoolean ?: throw RuntimeException("Missing 'a' argument for boolOr")
            val b = record.fields["b"] as? FunkBoolean ?: throw RuntimeException("Missing 'b' argument for boolOr")
            
            FunkBoolean(a.value || b.value)
        }
        
        context["boolNot"] = NativeFunkFunction("boolNot") { args ->
            if (args.size != 1) {
                throw RuntimeException("boolNot expects exactly 1 record argument, got ${args.size}")
            }
            
            val record = args[0] as? FunkRecord ?: throw RuntimeException("boolNot expects a record argument, got ${args[0]::class.simpleName}")
            val value = record.fields["val"] as? FunkBoolean ?: throw RuntimeException("Missing 'val' argument for boolNot")
            
            FunkBoolean(!value.value)
        }
        
        // Random functions
        context["native_random_int"] = NativeFunkFunction("native_random_int") { args ->
            if (args.size != 1) {
                throw RuntimeException("native_random_int expects exactly 1 record argument, got ${args.size}")
            }
            
            val record = args[0] as? FunkRecord ?: throw RuntimeException("native_random_int expects a record argument, got ${args[0]::class.simpleName}")
            val min = record.fields["min"] as? FunkInt ?: throw RuntimeException("Missing 'min' argument for native_random_int")
            val max = record.fields["max"] as? FunkInt ?: throw RuntimeException("Missing 'max' argument for native_random_int")
            
            val randomValue = kotlin.random.Random.nextInt(min.value.toInt(), max.value.toInt() + 1)
            FunkInt(randomValue.toLong())
        }
        
        context["native_random_double"] = NativeFunkFunction("native_random_double") { args ->
            if (args.size != 1) {
                throw RuntimeException("native_random_double expects exactly 1 record argument, got ${args.size}")
            }
            
            val randomValue = kotlin.random.Random.nextDouble()
            FunkFloat(randomValue)
        }
        
        context["native_random"] = NativeFunkFunction("native_random") { args ->
            if (args.size != 1) {
                throw RuntimeException("native_random expects exactly 1 record argument, got ${args.size}")
            }
            
            val record = args[0] as? FunkRecord ?: throw RuntimeException("native_random expects a record argument, got ${args[0]::class.simpleName}")
            val max = record.fields["max"] as? FunkInt ?: throw RuntimeException("Missing 'max' argument for native_random")
            
            val randomValue = kotlin.random.Random.nextInt(0, max.value.toInt() + 1)
            FunkInt(randomValue.toLong())
        }
    }
    
    /**
     * Load a specific stdlib module and add its exports to the context
     */
    private fun loadStdLibModule(modulePath: String, moduleResolver: ModuleResolver, context: MutableMap<String, FunkValue>) {
        try {
            // Resolve the module
            val module = moduleResolver.resolveModule(modulePath)
            
            // Execute the module to get its exports
            val moduleContext = mutableMapOf<String, FunkValue>()
            
            // Add native functions to module context (needed for ability implementations)
            NativeFunctions.registerAll(moduleContext)
            
            // Execute each item in the module
            for (item in module.program.items) {
                executeItem(item, moduleContext)
            }
            
            // Add public exports to the main context
            for ((name, export) in module.exports) {
                if (moduleContext.containsKey(name)) {
                    context[name] = moduleContext[name]!!
                }
            }
        } catch (e: Exception) {
            throw e
        }
    }
    /**
     * Execute function with tail call optimization
     */
    private fun executeWithTailCallOptimization(
        function: SimpleFunkFunction,
        initialContext: MutableMap<String, FunkValue>
    ): FunkValue {
        var currentContext = initialContext
        var currentFunction = function
        
        // Tail call optimization loop
        while (true) {
            // Mark this context as being in tail position for optimization
            currentContext["__in_tail_position"] = FunkBoolean(true)
            
            val result = evaluateExpressionWithTailCallDetection(currentFunction.body, currentContext, currentFunction)
            
            // Check if result is a tail call
            if (result is TailCall && result.function == currentFunction) {
                // This is a tail recursive call - optimize it
                currentContext = currentFunction.closure.toMutableMap()
                
                // Add recursive reference
                currentFunction.recursiveName?.let { name ->
                    currentContext[name] = currentFunction
                }
                
                // Bind new parameters from tail call
                when (result.args) {
                    is FunkRecord -> {
                        for (param in currentFunction.parameters) {
                            currentContext[param] = result.args.fields[param] ?: FunkNull
                        }
                    }
                    else -> {
                        if (currentFunction.parameters.size == 1) {
                            currentContext[currentFunction.parameters[0]] = result.args
                        } else {
                            throw RuntimeException("Function expects ${currentFunction.parameters.size} parameters but got single value")
                        }
                    }
                }
                
                // Continue loop for tail call optimization
            } else {
                // Not a tail call, return the result
                return result
            }
        }
    }
    
    /**
     * Evaluate expression with tail call detection
     */
    private fun evaluateExpressionWithTailCallDetection(
        expr: Expression,
        context: MutableMap<String, FunkValue>,
        currentFunction: SimpleFunkFunction
    ): FunkValue {
        // Check if we're in tail position and this is a recursive call
        val inTailPosition = (context["__in_tail_position"] as? FunkBoolean)?.value ?: false
        
        return when (expr) {
            is FunctionCall -> {
                // Check if this is a tail recursive call
                val function = evaluateExpression(expr.function, context)
                
                if (inTailPosition && function == currentFunction) {
                    // This is a tail recursive call - return TailCall object
                    val arguments = evaluateExpression(expr.arguments, context)
                    TailCall(currentFunction, arguments)
                } else {
                    // Regular function call
                    val argumentsValue = evaluateExpression(expr.arguments, context)
                    
                    // Special case: If function is a variant constructor, create variant construction
                    if (function is FunkVariant && function.value == null && argumentsValue is FunkRecord) {
                        // This is a variant constructor call like Success {data = 42}
                        // Convert to variant construction
                        FunkVariant(function.constructor, argumentsValue)
                    } else if (argumentsValue is FunkRecord) {
                        callFunctionWithRecord(function, argumentsValue, context)
                    } else {
                        val args = listOf(argumentsValue)
                        callFunction(function, args, context)
                    }
                }
            }
            is IfExpression -> {
                // Both branches are in tail position
                val condition = evaluateExpression(expr.condition, context)
                if (isTruthy(condition)) {
                    evaluateExpressionWithTailCallDetection(expr.thenBranch, context, currentFunction)
                } else {
                    evaluateExpressionWithTailCallDetection(expr.elseBranch, context, currentFunction)
                }
            }
            is DoExpression -> {
                // Only the result expression is in tail position
                val localContext = context.toMutableMap()
                
                // Execute statements (not in tail position)
                localContext["__in_tail_position"] = FunkBoolean(false)
                for (stmt in expr.statements) {
                    executeStatement(stmt, localContext)
                }
                
                // Result expression is in tail position
                localContext["__in_tail_position"] = FunkBoolean(true)
                evaluateExpressionWithTailCallDetection(expr.result, localContext, currentFunction)
            }
            else -> {
                // For other expressions, use regular evaluation
                evaluateExpression(expr, context)
            }
        }
    }
    
    /**
     * Check if an expression contains a self-reference to the given variable name
     */
    private fun containsSelfReference(expr: Expression, variableName: String): Boolean {
        return when (expr) {
            is Identifier -> expr.name == variableName
            is BinaryOperation -> containsSelfReference(expr.left, variableName) || containsSelfReference(expr.right, variableName)
            is UnaryOperation -> containsSelfReference(expr.operand, variableName)
            is FunctionCall -> containsSelfReference(expr.function, variableName) || containsSelfReference(expr.arguments, variableName)
            is IfExpression -> containsSelfReference(expr.condition, variableName) || 
                              containsSelfReference(expr.thenBranch, variableName) || 
                              containsSelfReference(expr.elseBranch, variableName)
            is DoExpression -> {
                // Check statements and result
                expr.statements.any { stmt -> 
                    when (stmt) {
                        is LetStatement -> containsSelfReference(stmt.expression, variableName)
                        is ExpressionStatement -> containsSelfReference(stmt.expression, variableName)
                    }
                } || containsSelfReference(expr.result, variableName)
            }
            is RecordLiteral -> expr.getFieldEntries().any { entry -> 
                entry.value?.let { containsSelfReference(it, variableName) } ?: false 
            } || expr.getSpreadEntries().any { spreadEntry ->
                containsSelfReference(spreadEntry.expression, variableName)
            }
            is MemberAccess -> containsSelfReference(expr.receiver, variableName)
            is VariantConstruction -> expr.value?.let { containsSelfReference(it, variableName) } ?: false
            is PerformExpression -> containsSelfReference(expr.expression, variableName)
            is LambdaExpression -> containsSelfReference(expr.body, variableName)
            // Literals don't contain references
            is IntLiteral, is FloatLiteral, is StringLiteral, is BooleanLiteral -> false
            // Other expressions we don't need to check
            else -> false
        }
    }
    
    // ============================================================================
    // ABILITY RESOLUTION
    // ============================================================================
    
    /**
     * Resolve and call ability operations at runtime
     */
    private fun resolveAbilityOperation(
        abilityName: String,
        operationName: String,
        arguments: FunkValue,
        context: MutableMap<String, FunkValue>
    ): FunkValue {
        // Extract types from arguments
        val argumentTypes = extractArgumentTypes(arguments)
        
        // Find implementation
        val implKey = "${abilityName}_${argumentTypes.joinToString("_")}"
        val implementation = context[implKey] as? FunkRecord
            ?: throw RuntimeException("No implementation found for ability $abilityName with types $argumentTypes")
        
        // Get operation from implementation
        val operation = implementation.fields[operationName] as? SimpleFunkFunction
            ?: throw RuntimeException("Operation $operationName not found in $abilityName implementation")
        
        // Call the operation
        return if (arguments is FunkRecord) {
            callFunctionWithRecord(operation, arguments, context)
        } else {
            callFunction(operation, listOf(arguments), context)
        }
    }
    
    /**
     * Extract type names from runtime values for ability resolution
     */
    private fun extractArgumentTypes(arguments: FunkValue): List<String> {
        return when (arguments) {
            is FunkRecord -> {
                arguments.fields.values.map { value ->
                    when (value) {
                        is FunkInt -> "Int"
                        is FunkString -> "String"
                        is FunkFloat -> "Float"
                        is FunkBoolean -> "Boolean"
                        else -> "Unknown"
                    }
                }.distinct()
            }
            is FunkInt -> listOf("Int")
            is FunkString -> listOf("String")
            is FunkFloat -> listOf("Float")
            is FunkBoolean -> listOf("Boolean")
            else -> listOf("Unknown")
        }
    }
    
}

/**
 * Simplified function value for the stub interpreter
 */
data class SimpleFunkFunction(
    val parameters: List<String>,
    val body: Expression,
    val closure: Map<String, FunkValue>,
    val name: String? = null,
    val inferredType: FunkType? = null,  // Contains effect row information
    var recursiveName: String? = null,
    var recursiveFunction: SimpleFunkFunction? = null,
    val effectContext: Map<String, FunkRecord> = emptyMap()  // Carry effect declarations
) : FunkValue() {
    override fun getDisplayString(): String = name ?: "<function>"
    
    /**
     * Set up recursive reference for this function
     */
    fun setupRecursiveReference(name: String, function: SimpleFunkFunction) {
        this.recursiveName = name
        this.recursiveFunction = function
    }
    
    /**
     * Check if this function has unhandled effects
     */
    fun hasUnhandledEffects(): Boolean {
        return when (val type = inferredType) {
            is FunkType.FunctionType -> {
                val effects = type.effects
                effects != null && effects.effects.isNotEmpty()
            }
            else -> false
        }
    }
    
    /**
     * Get the unhandled effects of this function
     */
    fun getUnhandledEffects(): Set<String> {
        return when (val type = inferredType) {
            is FunkType.FunctionType -> {
                val effects = type.effects
                effects?.effects ?: emptySet()
            }
            else -> emptySet()
        }
    }
    
    /**
     * Extract type names from runtime values for ability resolution
     */
    private fun extractArgumentTypes(value: FunkValue): List<String> {
        return when (value) {
            is FunkRecord -> {
                // For record arguments like {val: 42}, extract the types of the field values
                value.fields.values.map { getValueTypeName(it) }.distinct()
            }
            else -> listOf(getValueTypeName(value))
        }
    }
    
    /**
     * Get the type name of a runtime value
     */
    private fun getValueTypeName(value: FunkValue): String {
        return when (value) {
            is FunkInt -> "Int"
            is FunkFloat -> "Float"
            is FunkString -> "String"
            is FunkBoolean -> "Boolean"
            is FunkNull -> "Null"
            is FunkRecord -> "Record"
            is FunkVariant -> "Variant"
            is SimpleFunkFunction -> "Function"
            is NativeFunkFunction -> "NativeFunction"
            else -> "Unknown"
        }
    }
    
}

