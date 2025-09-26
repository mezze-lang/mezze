package com.funk.language.typechecker

import com.funk.language.ast.*
import com.funk.language.ast.prettyPrintType
import com.funk.language.parser.FunkParserImpl
import com.funk.language.stdlib.StdLib
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Clean rewrite of the type checker with proper constraint propagation
 * and simplified architecture
 */
class NewTypeChecker {
    
    // Type environment for variables and functions
    private val context = mutableMapOf<String, FunkType>()
    
    // Constraint system for type inference
    private val constraints = mutableListOf<Constraint>()
    
    // Type variable generator
    private var typeVarCounter = 0
    
    data class Constraint(
        val left: FunkType,
        val right: FunkType,
        val origin: String
    )
    
    init {
        registerBuiltinTypes()
    }
    
    /**
     * Main type checking entry point
     */
    fun typeCheck(program: Program): Program {
        // Clear state
        constraints.clear()
        
        // Type check all items
        val typedItems = program.items.map { item ->
            when (item) {
                is LetBinding -> typeCheckLetBinding(item)
                is TypeDeclaration -> typeCheckTypeDeclaration(item)
                else -> item
            }
        }
        
        return program.copy(items = typedItems)
    }
    
    /**
     * Type check a let binding
     */
    private fun typeCheckLetBinding(binding: LetBinding): LetBinding {
        val inferredType = inferType(binding.expression)
        context[binding.variableName] = inferredType
        return binding.copy(inferredType = inferredType)
    }
    
    /**
     * Type check a type declaration
     */
    private fun typeCheckTypeDeclaration(typeDecl: TypeDeclaration): TypeDeclaration {
        // Register the type in context
        when (val definition = typeDecl.definition) {
            is VariantDefinition -> {
                val constructors = definition.constructors.associate { constructor ->
                    constructor.name to constructor.dataType?.let { convertTypeAnnotation(it) }
                }
                val variantType = FunkType.VariantType(constructors)
                context[typeDecl.name] = variantType
                
                // Register constructors
                definition.constructors.forEach { constructor ->
                    val dataType = constructor.dataType
                    val constructorType = if (dataType != null) {
                        FunkType.FunctionType(convertTypeAnnotation(dataType), variantType)
                    } else {
                        variantType
                    }
                    context[constructor.name] = constructorType
                }
            }
            is RecordDefinition -> {
                val fieldTypes = definition.fields.associate { field ->
                    field.name to convertTypeAnnotation(field.type)
                }
                val recordType = FunkType.RecordType(fieldTypes)
                context[typeDecl.name] = recordType
            }
        }
        return typeDecl.copy(inferredType = context[typeDecl.name])
    }
    
    /**
     * Main type inference function
     */
    private fun inferType(expr: Expression): FunkType {
        return when (expr) {
            is IntLiteral -> FunkType.IntType
            is FloatLiteral -> FunkType.FloatType
            is StringLiteral -> FunkType.StringType
            is BooleanLiteral -> FunkType.BooleanType
            
            is Identifier -> {
                context[expr.name] ?: throw TypeCheckError.undefinedVariable(expr.name, expr)
            }
            
            is BinaryOperation -> inferBinaryOperation(expr)
            is FunctionCall -> inferFunctionCall(expr)
            is LambdaExpression -> inferLambda(expr)
            is RecordLiteral -> inferRecordLiteral(expr)
            is MemberAccess -> inferMemberAccess(expr)
            is DoExpression -> inferDoExpression(expr)
            is MatchExpression -> inferMatchExpression(expr)
            is IfExpression -> {
                val conditionType = inferType(expr.condition)
                if (conditionType != FunkType.BooleanType) {
                    throw TypeCheckError("If condition must be Boolean")
                }
                val thenType = inferType(expr.thenBranch)
                val elseType = inferType(expr.elseBranch)
                unify(thenType, elseType, "if branches")
                thenType
            }
            is UnaryOperation -> {
                val operandType = inferType(expr.operand)
                when (expr.operator) {
                    "-" -> {
                        if (isNumericType(operandType)) operandType
                        else throw TypeCheckError("Unary minus requires numeric type")
                    }
                    "!" -> {
                        if (operandType == FunkType.BooleanType) FunkType.BooleanType
                        else throw TypeCheckError("Logical not requires Boolean type")
                    }
                    else -> throw TypeCheckError("Unknown unary operator: ${expr.operator}")
                }
            }
            
            // Handle other expression types with basic inference
            else -> {
                // For now, just return a fresh type variable for unsupported expressions
                freshTypeVar()
            }
        }
    }
    
    /**
     * Infer binary operation with proper fraction handling
     */
    private fun inferBinaryOperation(expr: BinaryOperation): FunkType {
        // CRITICAL: Handle integer division as fraction
        if (expr.operator == "/" && expr.left is IntLiteral && expr.right is IntLiteral) {
            return FunkType.FracType
        }
        
        val leftType = inferType(expr.left)
        val rightType = inferType(expr.right)
        
        return when (expr.operator) {
            "+", "-", "*", "/" -> {
                when {
                    leftType == rightType && isNumericType(leftType) -> leftType
                    leftType is FunkType.FracType && rightType is FunkType.IntType -> FunkType.FracType
                    leftType is FunkType.IntType && rightType is FunkType.FracType -> FunkType.FracType
                    leftType is FunkType.NatType && rightType is FunkType.NatType -> {
                        if (expr.operator == "-") FunkType.IntType else FunkType.NatType
                    }
                    isNumericType(leftType) && isNumericType(rightType) -> {
                        // Default to Int for mixed numeric operations
                        FunkType.IntType
                    }
                    else -> throw TypeCheckError("Type mismatch in arithmetic: ${prettyPrintType(leftType)} ${expr.operator} ${prettyPrintType(rightType)}")
                }
            }
            "++" -> {
                if (leftType == FunkType.StringType && rightType == FunkType.StringType) {
                    FunkType.StringType
                } else {
                    throw TypeCheckError("String concatenation requires both operands to be String")
                }
            }
            "==", "!=", "<", ">", "<=", ">=" -> {
                if (leftType == rightType || (isNumericType(leftType) && isNumericType(rightType))) {
                    FunkType.BooleanType
                } else {
                    throw TypeCheckError("Comparison requires compatible types")
                }
            }
            else -> throw TypeCheckError("Unknown binary operator: ${expr.operator}")
        }
    }
    
    /**
     * Infer function call with constraint propagation
     */
    private fun inferFunctionCall(expr: FunctionCall): FunkType {
        val functionType = inferType(expr.function)
        val argumentType = inferType(expr.arguments)
        
        // Special handling for constraining functions
        val function = expr.function
        if (function is Identifier) {
            val functionName = function.name
            when (functionName) {
                "intToString" -> {
                    // Propagate Int constraint to arguments
                    propagateConstraint(expr.arguments, FunkType.IntType)
                    return FunkType.StringType
                }
                "floatToString" -> {
                    // Propagate Float constraint to arguments
                    propagateConstraint(expr.arguments, FunkType.FloatType)
                    return FunkType.StringType
                }
            }
        }
        
        return when (functionType) {
            is FunkType.FunctionType -> {
                unify(argumentType, functionType.paramType, "function call")
                functionType.returnType
            }
            else -> throw TypeCheckError("Cannot call non-function type: ${prettyPrintType(functionType)}")
        }
    }
    
    /**
     * Infer lambda with proper constraint propagation
     */
    private fun inferLambda(expr: LambdaExpression): FunkType {
        val savedContext = context.toMap()
        
        try {
            val paramType = when (val params = expr.parameters) {
                is UntypedParameters -> {
                    // Create type variables for parameters
                    val paramTypes = params.names.associateWith { freshTypeVar() }
                    paramTypes.forEach { (name, type) -> context[name] = type }
                    FunkType.RecordType(paramTypes)
                }
                is TypedParameters -> {
                    val recordType = convertRecordType(params.recordType)
                    if (recordType is FunkType.RecordType) {
                        recordType.fields.forEach { (name, type) -> context[name] = type }
                    }
                    recordType
                }
            }
            
            // Infer body type
            val bodyType = inferType(expr.body)
            
            // Solve constraints to get final parameter types
            val substitutions = solveConstraints()
            val finalParamType = applySubstitutions(paramType, substitutions)
            val finalBodyType = applySubstitutions(bodyType, substitutions)
            
            return FunkType.FunctionType(finalParamType, finalBodyType)
            
        } finally {
            context.clear()
            context.putAll(savedContext)
        }
    }
    
    /**
     * Infer record literal
     */
    private fun inferRecordLiteral(expr: RecordLiteral): FunkType {
        val fieldTypes = expr.entries.associate { entry ->
            entry.name to (entry.value?.let { inferType(it) } ?: freshTypeVar())
        }
        return FunkType.RecordType(fieldTypes)
    }
    
    /**
     * Infer member access
     */
    private fun inferMemberAccess(expr: MemberAccess): FunkType {
        val receiverType = inferType(expr.receiver)
        
        return when (receiverType) {
            is FunkType.RecordType -> {
                receiverType.fields[expr.member] 
                    ?: throw TypeCheckError("Field '${expr.member}' not found in record")
            }
            is FunkType.VarType -> {
                // Create constraint for field access
                val fieldType = freshTypeVar()
                val recordType = FunkType.RecordType(mapOf(expr.member to fieldType))
                unify(receiverType, recordType, "member access")
                fieldType
            }
            else -> throw TypeCheckError("Cannot access member '${expr.member}' on type ${prettyPrintType(receiverType)}")
        }
    }
    
    /**
     * Infer do expression
     */
    private fun inferDoExpression(expr: DoExpression): FunkType {
        val savedContext = context.toMap()
        
        try {
            // Process statements
            expr.statements.forEach { stmt ->
                when (stmt) {
                    is LetStatement -> {
                        val valueType = inferType(stmt.expression)
                        context[stmt.variableName] = valueType
                    }
                    is ExpressionStatement -> {
                        inferType(stmt.expression)
                    }
                }
            }
            
            // Return type of result expression
            return inferType(expr.result)
            
        } finally {
            context.clear()
            context.putAll(savedContext)
        }
    }
    
    /**
     * Infer match expression
     */
    private fun inferMatchExpression(expr: MatchExpression): FunkType {
        val matchedType = inferType(expr.expression)
        
        // Infer types of all cases
        val caseTypes = expr.cases.map { case ->
            val savedContext = context.toMap()
            try {
                // Add pattern bindings to context for all patterns in the case
                case.patterns.forEach { pattern ->
                    val bindings = extractPatternBindings(pattern, matchedType)
                    bindings.forEach { (name, type) -> context[name] = type }
                }
                
                // Check guard if present
                case.guard?.let { guard ->
                    val guardType = inferType(guard)
                    if (guardType != FunkType.BooleanType) {
                        throw TypeCheckError("Guard must be Boolean")
                    }
                }
                
                // Infer case expression type
                inferType(case.expression)
                
            } finally {
                context.clear()
                context.putAll(savedContext)
            }
        }
        
        // All cases must have the same type
        val resultType = caseTypes.firstOrNull() ?: throw TypeCheckError("Match expression has no cases")
        caseTypes.forEach { caseType ->
            unify(resultType, caseType, "match case")
        }
        
        return resultType
    }
    
    /**
     * Propagate constraint to expression (for intToString, etc.)
     */
    private fun propagateConstraint(expr: Expression, targetType: FunkType) {
        when (expr) {
            is RecordLiteral -> {
                expr.entries.forEach { entry ->
                    if (entry.name == "val" && entry.value != null) {
                        propagateConstraint(entry.value!!, targetType)
                    }
                }
            }
            is BinaryOperation -> {
                // For x * 2, if target is Int, constrain x to Int
                if (targetType is FunkType.IntType) {
                    propagateConstraint(expr.left, targetType)
                    // Don't constrain literals
                    if (expr.right !is IntLiteral) {
                        propagateConstraint(expr.right, targetType)
                    }
                }
            }
            is Identifier -> {
                // If this is a type variable, constrain it
                val currentType = context[expr.name]
                if (currentType is FunkType.VarType) {
                    unify(currentType, targetType, "constraint propagation")
                }
            }
            else -> {
                // For other expression types, do nothing for now
            }
        }
    }
    
    /**
     * Extract pattern bindings from match patterns
     */
    private fun extractPatternBindings(pattern: Pattern, matchedType: FunkType): Map<String, FunkType> {
        return when (pattern) {
            is VariablePattern -> mapOf(pattern.name to matchedType)
            is ConstructorPattern -> {
                val bindings = mutableMapOf<String, FunkType>()
                
                // Add constructor field bindings
                pattern.recordPattern?.let { recordPattern ->
                    recordPattern.entries.forEach { entry ->
                        val entryPattern = entry.pattern
                        if (entryPattern != null) {
                            bindings.putAll(extractPatternBindings(entryPattern, freshTypeVar()))
                        } else {
                            // Shorthand {x} instead of {x = x}
                            bindings[entry.name] = freshTypeVar()
                        }
                    }
                }
                
                bindings
            }
            is WildcardPattern -> emptyMap()
            else -> emptyMap()
        }
    }
    
    /**
     * Unify two types and add constraint
     */
    private fun unify(type1: FunkType, type2: FunkType, origin: String) {
        constraints.add(Constraint(type1, type2, origin))
    }
    
    /**
     * Solve all constraints and return substitutions
     */
    private fun solveConstraints(): Map<TypeVar, FunkType> {
        val substitutions = mutableMapOf<TypeVar, FunkType>()
        
        for (constraint in constraints) {
            unifyTypes(constraint.left, constraint.right, substitutions)
        }
        
        constraints.clear()
        return substitutions
    }
    
    /**
     * Unify two types with substitutions
     */
    private fun unifyTypes(type1: FunkType, type2: FunkType, substitutions: MutableMap<TypeVar, FunkType>) {
        val t1 = applySubstitutions(type1, substitutions)
        val t2 = applySubstitutions(type2, substitutions)
        
        when {
            t1 == t2 -> return
            
            t1 is FunkType.VarType -> {
                substitutions[t1.variable] = t2
            }
            
            t2 is FunkType.VarType -> {
                substitutions[t2.variable] = t1
            }
            
            t1 is FunkType.FunctionType && t2 is FunkType.FunctionType -> {
                unifyTypes(t1.paramType, t2.paramType, substitutions)
                unifyTypes(t1.returnType, t2.returnType, substitutions)
            }
            
            t1 is FunkType.RecordType && t2 is FunkType.RecordType -> {
                // Unify common fields
                val commonFields = t1.fields.keys.intersect(t2.fields.keys)
                commonFields.forEach { field ->
                    unifyTypes(t1.fields[field]!!, t2.fields[field]!!, substitutions)
                }
            }
            
            else -> {
                throw TypeCheckError("Cannot unify ${prettyPrintType(t1)} with ${prettyPrintType(t2)}")
            }
        }
    }
    
    /**
     * Apply substitutions to a type
     */
    private fun applySubstitutions(type: FunkType, substitutions: Map<TypeVar, FunkType>): FunkType {
        return when (type) {
            is FunkType.VarType -> substitutions[type.variable] ?: type
            is FunkType.FunctionType -> FunkType.FunctionType(
                applySubstitutions(type.paramType, substitutions),
                applySubstitutions(type.returnType, substitutions)
            )
            is FunkType.RecordType -> FunkType.RecordType(
                type.fields.mapValues { (_, fieldType) ->
                    applySubstitutions(fieldType, substitutions)
                }
            )
            else -> type
        }
    }
    
    /**
     * Generate fresh type variable
     */
    private fun freshTypeVar(): FunkType.VarType {
        return FunkType.VarType(TypeVar("t${typeVarCounter++}"))
    }
    
    /**
     * Check if type is numeric
     */
    private fun isNumericType(type: FunkType): Boolean {
        return type is FunkType.IntType || 
               type is FunkType.FloatType || 
               type is FunkType.NatType || 
               type is FunkType.FracType ||
               type is FunkType.NumType
    }
    
    /**
     * Convert type annotation to FunkType
     */
    private fun convertRecordType(recordType: RecordTypeAnnotation): FunkType {
        val fieldTypes = recordType.entries.associate { entry ->
            entry.name to convertTypeAnnotation(entry.type)
        }
        return FunkType.RecordType(fieldTypes)
    }
    
    /**
     * Convert type annotation to FunkType
     */
    private fun convertTypeAnnotation(annotation: TypeAnnotation): FunkType {
        return when (annotation) {
            is IdentifierTypeAnnotation -> when (annotation.name) {
                "Int" -> FunkType.IntType
                "Float" -> FunkType.FloatType
                "String" -> FunkType.StringType
                "Boolean" -> FunkType.BooleanType
                "Nat" -> FunkType.NatType
                "Frac" -> FunkType.FracType
                else -> throw TypeCheckError("Unknown primitive type: ${annotation.name}")
            }
            is RecordTypeAnnotation -> convertRecordType(annotation)
            is FunctionTypeAnnotation -> FunkType.FunctionType(
                convertTypeAnnotation(annotation.paramType),
                convertTypeAnnotation(annotation.returnType)
            )
            else -> throw TypeCheckError("Unsupported type annotation: ${annotation::class.simpleName}")
        }
    }
    
    /**
     * Register builtin types and functions
     */
    private fun registerBuiltinTypes() {
        // Type conversion functions
        context["intToString"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("val" to FunkType.IntType)),
            FunkType.StringType
        )
        
        context["floatToString"] = FunkType.FunctionType(
            FunkType.RecordType(mapOf("val" to FunkType.FloatType)),
            FunkType.StringType
        )
        
        // Add other builtin functions as needed
    }
}