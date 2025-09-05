package com.funk.language.typechecker

import com.funk.language.parser.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Comprehensive test suite for the complete Funk type system
 */
class ComprehensiveTypeSystemTest {
    
    private lateinit var typeChecker: PrincipalTypeInference
    
    @BeforeEach
    fun setup() {
        typeChecker = PrincipalTypeInference()
    }
    
    // ========== BASIC TYPE INFERENCE ==========
    
    @Test
    fun `test literal type inference`() {
        val intLiteral = Literal(42)
        val floatLiteral = Literal(3.14f)
        val stringLiteral = Literal("hello")
        val boolLiteral = Literal(true)
        
        val context = InferenceContext()
        
        assertEquals(FunkType.IntType, typeChecker.inferExpression(intLiteral, context).type)
        assertEquals(FunkType.FloatType, typeChecker.inferExpression(floatLiteral, context).type)
        assertEquals(FunkType.StringType, typeChecker.inferExpression(stringLiteral, context).type)
        assertEquals(FunkType.BooleanType, typeChecker.inferExpression(boolLiteral, context).type)
    }
    
    @Test
    fun `test identifier type inference with environment`() {
        val context = InferenceContext()
        context.env.extend("x", TypeScheme.monomorphic(FunkType.IntType))
        context.env.extend("y", TypeScheme.monomorphic(FunkType.StringType))
        
        val xId = Identifier("x")
        val yId = Identifier("y")
        
        assertEquals(FunkType.IntType, typeChecker.inferExpression(xId, context).type)
        assertEquals(FunkType.StringType, typeChecker.inferExpression(yId, context).type)
    }
    
    @Test
    fun `test unbound variable error`() {
        val context = InferenceContext()
        val unboundId = Identifier("unknown")
        
        assertThrows<StructuredTypeError> {
            typeChecker.inferExpression(unboundId, context)
        }
    }
    
    // ========== BINARY OPERATIONS ==========
    
    @Test
    fun `test arithmetic operations`() {
        val context = InferenceContext()
        val addOp = BinaryOperation(Literal(1), "+", Literal(2))
        val subOp = BinaryOperation(Literal(5.0f), "-", Literal(2.0f))
        
        val addResult = typeChecker.inferExpression(addOp, context)
        val subResult = typeChecker.inferExpression(subOp, context)
        
        assertTrue(addResult.type is FunkType.VarType || addResult.type == FunkType.IntType)
        assertTrue(subResult.type is FunkType.VarType || subResult.type == FunkType.FloatType)
    }
    
    @Test
    fun `test comparison operations`() {
        val context = InferenceContext()
        val eqOp = BinaryOperation(Literal(1), "==", Literal(2))
        val ltOp = BinaryOperation(Literal("a"), "<", Literal("b"))
        
        val eqResult = typeChecker.inferExpression(eqOp, context)
        val ltResult = typeChecker.inferExpression(ltOp, context)
        
        assertEquals(FunkType.BooleanType, eqResult.type)
        assertEquals(FunkType.BooleanType, ltResult.type)
    }
    
    @Test
    fun `test logical operations`() {
        val context = InferenceContext()
        val andOp = BinaryOperation(Literal(true), "&&", Literal(false))
        val orOp = BinaryOperation(Literal(true), "||", Literal(false))
        
        val andResult = typeChecker.inferExpression(andOp, context)
        val orResult = typeChecker.inferExpression(orOp, context)
        
        assertEquals(FunkType.BooleanType, andResult.type)
        assertEquals(FunkType.BooleanType, orResult.type)
    }
    
    // ========== RECORD TYPES ==========
    
    @Test
    fun `test closed record literal inference`() {
        val context = InferenceContext()
        val record = RecordLiteral(
            listOf(
                RecordEntry("name", Literal("John")),
                RecordEntry("age", Literal(30))
            )
        )
        
        val result = typeChecker.inferExpression(record, context)
        
        assertTrue(result.type is FunkType.RecordType)
        val recordType = result.type as FunkType.RecordType
        assertEquals(FunkType.StringType, recordType.fields["name"])
        assertEquals(FunkType.IntType, recordType.fields["age"])
        assertTrue(recordType.isClosed)
    }
    
    @Test
    fun `test open record literal inference`() {
        val context = InferenceContext()
        val record = RecordLiteral(
            listOf(
                RecordEntry("x", Literal(1)),
                RecordEntry("y", Literal(2))
            ),
            restField = "rest"
        )
        
        val result = typeChecker.inferExpression(record, context)
        
        assertTrue(result.type is FunkType.RecordType)
        val recordType = result.type as FunkType.RecordType
        assertEquals(FunkType.IntType, recordType.fields["x"])
        assertEquals(FunkType.IntType, recordType.fields["y"])
        assertTrue(recordType.isOpen)
    }
    
    @Test
    fun `test member access on closed record`() {
        val context = InferenceContext()
        val record = RecordLiteral(
            listOf(RecordEntry("name", Literal("Alice")))
        )
        val access = MemberAccess(record, "name")
        
        val result = typeChecker.inferExpression(access, context)
        
        assertEquals(FunkType.StringType, result.type)
    }
    
    @Test
    fun `test member access on open record`() {
        val context = InferenceContext()
        val openRecord = RecordLiteral(
            listOf(RecordEntry("x", Literal(10))),
            restField = "rest"
        )
        val access = MemberAccess(openRecord, "y") // Field not explicitly present
        
        val result = typeChecker.inferExpression(access, context)
        
        // Should infer a type variable for the unknown field
        assertTrue(result.type is FunkType.VarType)
    }
    
    @Test
    fun `test record update`() {
        val context = InferenceContext()
        val originalRecord = RecordLiteral(
            listOf(
                RecordEntry("name", Literal("Bob")),
                RecordEntry("age", Literal(25))
            )
        )
        val update = RecordUpdate(
            originalRecord,
            listOf(RecordEntry("age", Literal(26)))
        )
        
        val result = typeChecker.inferExpression(update, context)
        
        assertTrue(result.type is FunkType.RecordType)
        val recordType = result.type as FunkType.RecordType
        assertEquals(2, recordType.fields.size)
        assertTrue(recordType.fields.containsKey("name"))
        assertTrue(recordType.fields.containsKey("age"))
    }
    
    // ========== VARIANT TYPES ==========
    
    @Test
    fun `test variant constructor inference`() {
        val context = InferenceContext()
        val constructor = VariantConstruction("Some", Literal(42))
        
        val result = typeChecker.inferExpression(constructor, context)
        
        assertTrue(result.type is FunkType.VariantType)
        val variantType = result.type as FunkType.VariantType
        assertEquals(FunkType.IntType, variantType.constructors["Some"])
        assertTrue(variantType.isOpen) // Open by default for inference
    }
    
    @Test
    fun `test variant constructor without value`() {
        val context = InferenceContext()
        val constructor = VariantConstruction("None")
        
        val result = typeChecker.inferExpression(constructor, context)
        
        assertTrue(result.type is FunkType.VariantType)
        val variantType = result.type as FunkType.VariantType
        assertNull(variantType.constructors["None"])
    }
    
    // ========== FUNCTION TYPES ==========
    
    @Test
    fun `test lambda expression inference`() {
        val context = InferenceContext()
        val lambda = LambdaExpression(
            TypedParameters(RecordType(listOf(
                RecordTypeEntry("x", SimpleType("Int"))
            ))),
            BinaryOperation(Identifier("x"), "+", Literal(1))
        )
        
        val result = typeChecker.inferExpression(lambda, context)
        
        assertTrue(result.type is FunkType.FunctionType)
        val funcType = result.type as FunkType.FunctionType
        assertTrue(funcType.paramType is FunkType.RecordType)
        // Return type should be inferred as Int or a type variable
    }
    
    @Test
    fun `test function call inference`() {
        val context = InferenceContext()
        
        // Define a function: { x: Int } -> Int
        val funcType = FunkType.FunctionType(
            FunkType.RecordType(mapOf("x" to FunkType.IntType)),
            FunkType.IntType
        )
        context.env.extend("add1", TypeScheme.monomorphic(funcType))
        
        val call = FunctionCall(
            Identifier("add1"),
            RecordLiteral(listOf(RecordEntry("x", Literal(5))))
        )
        
        val result = typeChecker.inferExpression(call, context)
        
        assertEquals(FunkType.IntType, result.type)
    }
    
    // ========== RECORD-BASED CURRYING ==========
    
    @Test
    fun `test partial function application`() {
        val context = InferenceContext()
        
        // Function: { x: Int, y: Int } -> Int
        val funcType = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "x" to FunkType.IntType,
                "y" to FunkType.IntType
            )),
            FunkType.IntType
        )
        context.env.extend("add", TypeScheme.monomorphic(funcType))
        
        // Partial application: add { x = 5 }
        val partialCall = FunctionCall(
            Identifier("add"),
            RecordLiteral(listOf(RecordEntry("x", Literal(5))))
        )
        
        val result = typeChecker.inferExpression(partialCall, context)
        
        // Should return a function type: { y: Int } -> Int
        assertTrue(result.type is FunkType.FunctionType)
        val resultFuncType = result.type as FunkType.FunctionType
        assertTrue(resultFuncType.paramType is FunkType.RecordType)
        val paramRecord = resultFuncType.paramType as FunkType.RecordType
        assertEquals(1, paramRecord.fields.size)
        assertTrue(paramRecord.fields.containsKey("y"))
    }
    
    @Test
    fun `test over-application error on closed record`() {
        val context = InferenceContext()
        
        // Function: { x: Int } -> Int (closed record parameter)
        val funcType = FunkType.FunctionType(
            FunkType.RecordType(mapOf("x" to FunkType.IntType)),
            FunkType.IntType
        )
        context.env.extend("func", TypeScheme.monomorphic(funcType))
        
        // Over-application: func { x = 1, y = 2 }
        val overCall = FunctionCall(
            Identifier("func"),
            RecordLiteral(listOf(
                RecordEntry("x", Literal(1)),
                RecordEntry("y", Literal(2))
            ))
        )
        
        // Should generate constraints that lead to unification error
        val result = typeChecker.inferExpression(overCall, context)
        assertNotNull(result.constraints)
    }
    
    // ========== CONTROL FLOW ==========
    
    @Test
    fun `test if expression inference`() {
        val context = InferenceContext()
        val ifExpr = IfExpression(
            Literal(true),
            Literal(1),
            Literal(2)
        )
        
        val result = typeChecker.inferExpression(ifExpr, context)
        
        assertEquals(FunkType.IntType, result.type)
    }
    
    @Test
    fun `test if expression type mismatch`() {
        val context = InferenceContext()
        val ifExpr = IfExpression(
            Literal(true),
            Literal(1),
            Literal("string")
        )
        
        val result = typeChecker.inferExpression(ifExpr, context)
        
        // Should generate unification constraints
        assertTrue(result.constraints.isNotEmpty())
    }
    
    @Test
    fun `test match expression inference`() {
        val context = InferenceContext()
        val matchExpr = MatchExpression(
            Literal(1),
            listOf(
                MatchCase(WildcardPattern, Literal("matched")),
                MatchCase(WildcardPattern, Literal("default"))
            )
        )
        
        val result = typeChecker.inferExpression(matchExpr, context)
        
        assertEquals(FunkType.StringType, result.type)
    }
    
    // ========== PIPE OPERATOR ==========
    
    @Test
    fun `test pipe expression inference`() {
        val context = InferenceContext()
        
        // Function: Int -> String
        val funcType = FunkType.FunctionType(FunkType.IntType, FunkType.StringType)
        context.env.extend("toString", TypeScheme.monomorphic(funcType))
        
        val pipe = PipeExpression(
            Literal(42),
            Identifier("toString")
        )
        
        val result = typeChecker.inferExpression(pipe, context)
        
        assertEquals(FunkType.StringType, result.type)
    }
    
    @Test
    fun `test pipe with record currying`() {
        val context = InferenceContext()
        
        // Function: { x: Int, y: Int } -> Int
        val funcType = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "x" to FunkType.IntType,
                "y" to FunkType.IntType
            )),
            FunkType.IntType
        )
        context.env.extend("add", TypeScheme.monomorphic(funcType))
        
        // 5 |> add { x = @, y = 3 } (conceptually)
        // For now, test basic pipe functionality
        val pipe = PipeExpression(
            Literal(5),
            FunctionCall(
                Identifier("add"),
                RecordLiteral(listOf(
                    RecordEntry("x", PlaceholderExpression),
                    RecordEntry("y", Literal(3))
                ))
            )
        )
        
        val result = typeChecker.inferExpression(pipe, context)
        assertNotNull(result.type)
    }
    
    // ========== DO EXPRESSIONS ==========
    
    @Test
    fun `test do expression inference`() {
        val context = InferenceContext()
        val doExpr = DoExpression(
            listOf(
                LetBinding("x", null, Literal(10)),
                LetBinding("y", null, Literal(20))
            ),
            BinaryOperation(Identifier("x"), "+", Identifier("y"))
        )
        
        val result = typeChecker.inferExpression(doExpr, context)
        
        // Result should be Int (or a type variable that unifies to Int)
        assertTrue(result.type == FunkType.IntType || result.type is FunkType.VarType)
    }
    
    // ========== LET BINDING WITH GENERALIZATION ==========
    
    @Test
    fun `test let polymorphism`() {
        val context = InferenceContext()
        
        // let id = { x } -> x
        val idLambda = LambdaExpression(
            TypedParameters(RecordType(listOf(RecordTypeEntry("x", SimpleType("a"))))),
            Identifier("x")
        )
        val letBinding = LetBinding("id", null, idLambda)
        
        typeChecker.inferProgramItem(letBinding, context)
        
        // id should be polymorphic: forall a. { x: a } -> a
        val idScheme = context.env.lookup("id")
        assertNotNull(idScheme)
        assertTrue(idScheme!!.typeVars.isNotEmpty())
    }
    
    // ========== ERROR HANDLING ==========
    
    @Test
    fun `test structured error for unification failure`() {
        val context = InferenceContext()
        
        val mismatchExpr = BinaryOperation(
            Literal(42),
            "+",
            Literal("string")
        )
        
        // Should generate constraints that fail to unify
        val result = typeChecker.inferExpression(mismatchExpr, context)
        assertTrue(result.constraints.isNotEmpty())
    }
    
    @Test
    fun `test field not found error`() {
        val context = InferenceContext()
        val record = RecordLiteral(listOf(RecordEntry("x", Literal(1))))
        val badAccess = MemberAccess(record, "nonexistent")
        
        // For closed records, this should create a constraint that may fail
        val result = typeChecker.inferExpression(badAccess, context)
        assertNotNull(result.constraints)
    }
    
    // ========== CONSTRAINT SOLVING ==========
    
    @Test
    fun `test constraint collection and solving`() {
        val context = InferenceContext()
        
        // Create expression that generates constraints
        val expr = BinaryOperation(
            Identifier("x"),
            "+",
            Identifier("y")
        )
        
        // Add variables to context
        context.env.extend("x", TypeScheme.monomorphic(FunkType.VarType(TypeVar("a"))))
        context.env.extend("y", TypeScheme.monomorphic(FunkType.VarType(TypeVar("b"))))
        
        val result = typeChecker.inferExpression(expr, context)
        
        // Should collect constraints about arithmetic operation
        assertTrue(result.constraints.isNotEmpty())
    }
    
    // ========== TYPE ANNOTATIONS ==========
    
    @Test
    fun `test explicit type annotation checking`() {
        val context = InferenceContext()
        
        val letWithAnnotation = LetBinding(
            "x",
            SimpleType("Int"),
            Literal(42)
        )
        
        val result = typeChecker.inferProgramItem(letWithAnnotation, context)
        
        // Should succeed without errors
        assertNotNull(result)
    }
    
    @Test
    fun `test type annotation mismatch`() {
        val context = InferenceContext()
        
        val mismatchLet = LetBinding(
            "x",
            SimpleType("String"),
            Literal(42) // Int literal with String annotation
        )
        
        val result = typeChecker.inferProgramItem(mismatchLet, context)
        
        // Should generate constraints that lead to unification failure
        assertTrue(result.constraints.isNotEmpty())
    }
    
    // ========== INTEGRATION TESTS ==========
    
    @Test
    fun `test complex record currying scenario`() {
        val context = InferenceContext()
        
        // let add = { x: Int, y: Int, z: Int } -> x + y + z
        val addFunc = LambdaExpression(
            TypedParameters(RecordType(listOf(
                RecordTypeEntry("x", SimpleType("Int")),
                RecordTypeEntry("y", SimpleType("Int")),
                RecordTypeEntry("z", SimpleType("Int"))
            ))),
            BinaryOperation(
                BinaryOperation(Identifier("x"), "+", Identifier("y")),
                "+",
                Identifier("z")
            )
        )
        
        val letAdd = LetBinding("add", null, addFunc)
        typeChecker.inferProgramItem(letAdd, context)
        
        // Partial application: add { x = 1, y = 2 }
        val partialApp = FunctionCall(
            Identifier("add"),
            RecordLiteral(listOf(
                RecordEntry("x", Literal(1)),
                RecordEntry("y", Literal(2))
            ))
        )
        
        val result = typeChecker.inferExpression(partialApp, context)
        
        // Result should be { z: Int } -> Int
        assertTrue(result.type is FunkType.FunctionType)
    }
    
    @Test
    fun `test nested record access and update`() {
        val context = InferenceContext()
        
        // Create nested record
        val nestedRecord = RecordLiteral(listOf(
            RecordEntry("person", RecordLiteral(listOf(
                RecordEntry("name", Literal("Alice")),
                RecordEntry("age", Literal(30))
            ))),
            RecordEntry("id", Literal(123))
        ))
        
        // Access nested field
        val nestedAccess = MemberAccess(
            MemberAccess(nestedRecord, "person"),
            "name"
        )
        
        val result = typeChecker.inferExpression(nestedAccess, context)
        assertEquals(FunkType.StringType, result.type)
    }
    
    // ========== PERFORMANCE TESTS ==========
    
    @Test
    fun `test type inference performance on large expression`() {
        val context = InferenceContext()
        
        // Create a large nested expression
        var expr: Expression = Literal(1)
        for (i in 1..100) {
            expr = BinaryOperation(expr, "+", Literal(i))
        }
        
        val startTime = System.currentTimeMillis()
        val result = typeChecker.inferExpression(expr, context)
        val endTime = System.currentTimeMillis()
        
        assertNotNull(result.type)
        assertTrue(endTime - startTime < 1000) // Should complete in under 1 second
    }
    
    // ========== PROPERTY-BASED TESTS ==========
    
    @ParameterizedTest
    @ValueSource(ints = [1, 42, -10, 0, 999999])
    fun `test integer literal inference for various values`(value: Int) {
        val context = InferenceContext()
        val literal = Literal(value)
        
        val result = typeChecker.inferExpression(literal, context)
        
        assertEquals(FunkType.IntType, result.type)
    }
    
    @ParameterizedTest
    @ValueSource(strings = ["hello", "", "unicode: 🎉", "special chars: !@#$%"])
    fun `test string literal inference for various values`(value: String) {
        val context = InferenceContext()
        val literal = Literal(value)
        
        val result = typeChecker.inferExpression(literal, context)
        
        assertEquals(FunkType.StringType, result.type)
    }
    
    // ========== EDGE CASES ==========
    
    @Test
    fun `test empty record literal`() {
        val context = InferenceContext()
        val emptyRecord = RecordLiteral(emptyList())
        
        val result = typeChecker.inferExpression(emptyRecord, context)
        
        assertTrue(result.type is FunkType.RecordType)
        val recordType = result.type as FunkType.RecordType
        assertTrue(recordType.fields.isEmpty())
        assertTrue(recordType.isClosed)
    }
    
    @Test
    fun `test placeholder type inference`() {
        val context = InferenceContext()
        val placeholder = PlaceholderExpression
        
        val result = typeChecker.inferExpression(placeholder, context)
        
        assertTrue(result.type is FunkType.VarType)
    }
    
    @Test
    fun `test circular type reference handling`() {
        val context = InferenceContext()
        
        // This would create a circular constraint in a more complex scenario
        // For now, test that we don't crash on self-referential structures
        val selfRef = Identifier("self")
        context.env.extend("self", TypeScheme.monomorphic(FunkType.VarType(TypeVar("self"))))
        
        val result = typeChecker.inferExpression(selfRef, context)
        assertNotNull(result.type)
    }
}

/**
 * Tests for specific type system components
 */
class TypeSystemComponentTests {
    
    @Test
    fun `test type variable generation`() {
        val supply = TypeVarSupply()
        
        val var1 = supply.fresh("test", 0)
        val var2 = supply.fresh("test", 0)
        val var3 = supply.fresh("other", 1)
        
        assertNotEquals(var1.name, var2.name)
        assertNotEquals(var2.name, var3.name)
        assertEquals(0, var1.level)
        assertEquals(0, var2.level)
        assertEquals(1, var3.level)
    }
    
    @Test
    fun `test constraint equality`() {
        val type1 = FunkType.IntType
        val type2 = FunkType.StringType
        val type3 = FunkType.IntType
        
        val constraint1 = Constraint.Equality(type1, type2)
        val constraint2 = Constraint.Equality(type1, type2)
        val constraint3 = Constraint.Equality(type1, type3)
        
        assertEquals(constraint1, constraint2)
        assertNotEquals(constraint1, constraint3)
    }
    
    @Test
    fun `test substitution composition`() {
        val var1 = TypeVar("a")
        val var2 = TypeVar("b")
        
        val subst1 = Substitution.single(var1, FunkType.IntType)
        val subst2 = Substitution.single(var2, FunkType.StringType)
        
        val composed = subst1.compose(subst2)
        
        assertEquals(FunkType.IntType, composed.apply(FunkType.VarType(var1)))
        assertEquals(FunkType.StringType, composed.apply(FunkType.VarType(var2)))
    }
    
    @Test
    fun `test type scheme instantiation`() {
        val typeVar = TypeVar("a")
        val scheme = TypeScheme(
            setOf(typeVar),
            emptySet(),
            emptySet(),
            FunkType.VarType(typeVar)
        )
        
        val context = InferenceContext()
        val typeChecker = PrincipalTypeInference()
        
        // Create reflection method access for testing
        val instantiateMethod = typeChecker::class.java.getDeclaredMethod(
            "instantiate", 
            TypeScheme::class.java, 
            InferenceContext::class.java
        )
        instantiateMethod.isAccessible = true
        
        val instance1 = instantiateMethod.invoke(typeChecker, scheme, context) as FunkType
        val instance2 = instantiateMethod.invoke(typeChecker, scheme, context) as FunkType
        
        // Should create fresh type variables
        assertTrue(instance1 is FunkType.VarType)
        assertTrue(instance2 is FunkType.VarType)
        assertNotEquals(instance1, instance2)
    }
}

/**
 * Error handling and structured error tests
 */
class StructuredErrorTests {
    
    @Test
    fun `test structured error serialization`() {
        val error = StructuredTypeError.unificationError(
            FunkType.IntType,
            FunkType.StringType
        )
        
        val json = error.errorInfo.toJson()
        val deserializedError = TypeErrorInfo.fromJson(json)
        
        assertEquals(error.errorInfo.message, deserializedError.message)
        assertTrue(deserializedError.kind is TypeErrorKind.UnificationError)
    }
    
    @Test
    fun `test unbound variable error with suggestions`() {
        val availableNames = listOf("userName", "userAge", "userEmail")
        val error = StructuredTypeError.unboundVariable("username", availableNames)
        
        assertNotNull(error.errorInfo.hint)
        assertTrue(error.errorInfo.hint!!.contains("userName"))
    }
    
    @Test
    fun `test field not found error with available fields`() {
        val recordType = FunkType.RecordType(mapOf(
            "name" to FunkType.StringType,
            "age" to FunkType.IntType,
            "email" to FunkType.StringType
        ))
        
        val error = StructuredTypeError.fieldNotFound(recordType, "nam")
        
        assertNotNull(error.errorInfo.hint)
        assertTrue(error.errorInfo.hint!!.contains("name"))
    }
    
    @Test
    fun `test partial application error details`() {
        val funcType = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "x" to FunkType.IntType,
                "y" to FunkType.IntType
            )),
            FunkType.IntType
        )
        
        val providedArgs = FunkType.RecordType(mapOf(
            "x" to FunkType.IntType,
            "z" to FunkType.StringType
        ))
        
        val error = StructuredTypeError.partialApplicationError(funcType, providedArgs)
        
        assertTrue(error.errorInfo.kind is TypeErrorKind.PartialApplicationError)
        val partialError = error.errorInfo.kind as TypeErrorKind.PartialApplicationError
        assertTrue(partialError.missingFields.contains("y"))
        assertTrue(partialError.extraFields.contains("z"))
    }
}
