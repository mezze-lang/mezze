package com.funk.language.typechecker

import com.funk.language.ast.*
import com.funk.language.parser.FunkParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

class ComprehensiveEffectTypeCheckerTest {

    private lateinit var parser: FunkParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()  // Clear module cache to prevent state pollution between tests
        parser = FunkParser()
        typeChecker = TypeChecker()
    }

    @Test
    fun testEffectDeclarationTypeChecking() {
        val source = """
            effect Log {
              print : {msg: String} -> {},
              debug : {msg: String, level: Int} -> {}
            }
        """.trimIndent()

        val program = parser.parse(source)
        val checkedProgram = typeChecker.typeCheck(program)

        assertEquals(1, checkedProgram.items.size)
        val effectDecl = checkedProgram.items[0] as EffectDeclaration

        assertEquals("Log", effectDecl.name)
        assertEquals(2, effectDecl.operations.size)

        // Check that operations have correct types
        val printOp = effectDecl.operations[0]
        assertEquals("print", printOp.name)
        assertTrue(printOp.paramType is RecordTypeAnnotation)
        assertTrue(printOp.returnType is RecordTypeAnnotation)

        val debugOp = effectDecl.operations[1]
        assertEquals("debug", debugOp.name)
        val debugParams = debugOp.paramType as RecordTypeAnnotation
        assertEquals(2, debugParams.entries.size)
        assertEquals("msg", debugParams.entries[0].name)
        assertEquals("level", debugParams.entries[1].name)
    }

    @Test
    fun testSimpleImplDeclarationTypeChecking() {
        val source = """
            effect Log {
              print : {msg: String} -> {}
            }

            impl Log for Console where {
              print : {msg: String} -> {} = {msg} -> native_print {msg = msg}
            }
        """.trimIndent()

        val program = parser.parse(source)
        val checkedProgram = typeChecker.typeCheck(program)

        assertEquals(2, checkedProgram.items.size)

        val effectDecl = checkedProgram.items[0] as EffectDeclaration
        val implDecl = checkedProgram.items[1] as ImplDeclaration

        assertEquals("Log", effectDecl.name)
        assertEquals("Log", implDecl.effectName)
        assertEquals("Console", implDecl.performerName)

        // Check that impl operation matches effect operation
        val effectOp = effectDecl.operations[0]
        val implOp = implDecl.operations[0]
        assertEquals(effectOp.name, implOp.name)

        // Implementation should be a lambda expression
        assertTrue(implOp.implementation is LambdaExpression)
    }

    @Test
    fun testPerformExpressionTypeChecking() {
        val source = """
            effect Log {
              print : {msg: String} -> {}
            }

            impl Log for Console where {
              print : {msg: String} -> {} = {msg} -> native_print {msg = msg}
            }

            perform Console in Log.print {msg = "Hello"}
        """.trimIndent()

        val program = parser.parse(source)
        val checkedProgram = typeChecker.typeCheck(program)

        assertEquals(3, checkedProgram.items.size)

        val performExpr = (checkedProgram.items[2] as TopLevelExpression).expression as PerformExpression
        assertEquals(1, performExpr.handlers.size)
        assertEquals("Console", performExpr.handlers[0].name)

        // The performed expression should be a function call
        assertTrue(performExpr.expression is FunctionCall)
        val funcCall = performExpr.expression as FunctionCall
        assertTrue(funcCall.function is MemberAccess)
    }

    @Test
    fun testEffectCallTypeChecking() {
        val source = """
            effect Random {
              nextInt : {min: Int, max: Int} -> Int
            }

            impl Random for MathRandom where {
              nextInt : {min: Int, max: Int} -> Int = {min, max} -> native_random_int {min = min, max = max}
            }

            let randomValue = perform MathRandom in Random.nextInt {min = 1, max = 10}
        """.trimIndent()

        val program = parser.parse(source)
        val checkedProgram = typeChecker.typeCheck(program)

        assertEquals(3, checkedProgram.items.size)

        val letBinding = checkedProgram.items[2] as LetBinding
        assertEquals("randomValue", letBinding.variableName)

        // The let binding expression should be a perform expression
        assertTrue(letBinding.expression is PerformExpression)
        val performExpr = letBinding.expression as PerformExpression

        // The performed expression should be an effect call (function call on member access)
        assertTrue(performExpr.expression is FunctionCall)
        val funcCall = performExpr.expression as FunctionCall
        assertTrue(funcCall.function is MemberAccess)

        val memberAccess = funcCall.function as MemberAccess
        // Handle both Identifier and VariantConstruction cases
        val receiverName = when (memberAccess.receiver) {
            is Identifier -> (memberAccess.receiver as Identifier).name
            is VariantConstruction -> (memberAccess.receiver as VariantConstruction).constructor
            else -> fail("Expected receiver to be Identifier or VariantConstruction, got ${memberAccess.receiver::class.simpleName}")
        }
        assertEquals("Random", receiverName)
        assertEquals("nextInt", memberAccess.member)
    }

    @Test
    fun testMultipleEffectsTypeChecking() {
        val source = """
            effect Log {
              print : {msg: String} -> {}
            }

            effect Random {
              nextInt : {min: Int, max: Int} -> Int
            }

            impl Log for Console where {
              print : {msg: String} -> {} = {msg} -> native_print {msg = msg}
            }

            impl Random for MathRandom where {
              nextInt : {min: Int, max: Int} -> Int = {min, max} -> native_random_int {min = min, max = max}
            }

            let gameLogic = {} ->
              do
                Log.print {msg = "Starting game"}
                let score = Random.nextInt {min = 100, max = 1000}
                Log.print {msg = "Score: 42"}
                score

            # Test the type of gameLogic
            gameLogic
        """.trimIndent()

        val program = parser.parse(source)
        val checkedProgram = typeChecker.typeCheck(program)

        assertEquals(6, checkedProgram.items.size)

        // Check effect declarations
        val logEffect = checkedProgram.items[0] as EffectDeclaration
        val randomEffect = checkedProgram.items[1] as EffectDeclaration
        assertEquals("Log", logEffect.name)
        assertEquals("Random", randomEffect.name)

        // Check impl declarations
        val logImpl = checkedProgram.items[2] as ImplDeclaration
        val randomImpl = checkedProgram.items[3] as ImplDeclaration
        assertEquals("Log", logImpl.effectName)
        assertEquals("Random", randomImpl.effectName)

        // Check let binding
        val letBinding = checkedProgram.items[4] as LetBinding
        assertEquals("gameLogic", letBinding.variableName)
        assertTrue(letBinding.expression is LambdaExpression)

        // Check final expression (gameLogic identifier)
        val finalExpr = checkedProgram.items[5] as TopLevelExpression
        assertTrue(finalExpr.expression is Identifier)
        val identifier = finalExpr.expression as Identifier
        assertEquals("gameLogic", identifier.name)
    }

    @Test
    fun testDoBlockWithEffectsTypeChecking() {
        val source = """
            effect Log {
              info : {msg: String} -> {}
            }

            effect Random {
              nextInt : {min: Int, max: Int} -> Int
            }

            impl Log for Console where {
              info : {msg: String} -> {} = {msg} -> native_print {msg = "[INFO] $(msg)"}
            }

            impl Random for MathRandom where {
              nextInt : {min: Int, max: Int} -> Int = {min, max} -> native_random_int {min = min, max = max}
            }

            let computation = {} ->
              do
                Log.info {msg = "Computing"}
                let x = Random.nextInt {min = 1, max = 5}
                let y = Random.nextInt {min = x, max = 10}
                Log.info {msg = "Result computed"}
                x + y
        """.trimIndent()

        val program = parser.parse(source)
        val checkedProgram = typeChecker.typeCheck(program)

        assertEquals(5, checkedProgram.items.size)

        val letBinding = checkedProgram.items[4] as LetBinding
        assertEquals("computation", letBinding.variableName)

        // The lambda body should be a do expression
        val lambda = letBinding.expression as LambdaExpression
        assertTrue(lambda.body is DoExpression)

        val doExpr = lambda.body as DoExpression
        assertEquals(4, doExpr.statements.size)

        // Check that effect calls in do block are typed correctly
        val firstStmt = doExpr.statements[0] as ExpressionStatement
        assertTrue(firstStmt.expression is FunctionCall)

        val funcCall = firstStmt.expression as FunctionCall
        assertTrue(funcCall.function is MemberAccess)
    }


    @Test
    fun testEffectOperationParameterMatching() {
        val source = """
            effect FileSystem {
              readFile : {path: String} -> String,
              writeFile : {path: String, content: String} -> {}
            }

            impl FileSystem for MockFS where {
              readFile : {path: String} -> String = {path} -> "mock content"
              writeFile : {path: String, content: String} -> {} = {path, content} -> native_print {msg = "Writing to $(path)"}
            }
        """.trimIndent()

        val program = parser.parse(source)
        val checkedProgram = typeChecker.typeCheck(program)

        assertEquals(2, checkedProgram.items.size)

        val effectDecl = checkedProgram.items[0] as EffectDeclaration
        val implDecl = checkedProgram.items[1] as ImplDeclaration

        assertEquals(2, effectDecl.operations.size)
        assertEquals(2, implDecl.operations.size)

        // Check that operation signatures match
        for (i in 0 until effectDecl.operations.size) {
            val effectOp = effectDecl.operations[i]
            val implOp = implDecl.operations[i]
            assertEquals(effectOp.name, implOp.name)
            // Parameter types should match (this would be enforced by type checker)
        }
    }

    @Test
    fun testEffectRowInferenceInFunctions() {
        val source = """
            effect Log {
              print : {msg: String} -> {}
            }

            impl Log for Console where {
              print : {msg: String} -> {} = {msg} -> native_print {msg = msg}
            }

            let loggedFunction = {name} -> do
              Log.print {msg = "Processing $(name)"}
              "$(name) processed"

            let result = perform Console in loggedFunction {name = "test"}
        """.trimIndent()

        val program = parser.parse(source)
        val checkedProgram = typeChecker.typeCheck(program)

        assertEquals(4, checkedProgram.items.size)

        val loggedFunctionBinding = checkedProgram.items[2] as LetBinding
        assertEquals("loggedFunction", loggedFunctionBinding.variableName)

        // The function should have inferred type information
        assertTrue(loggedFunctionBinding.expression is LambdaExpression)
        val lambda = loggedFunctionBinding.expression as LambdaExpression
        assertTrue(lambda.body is DoExpression)

        // Check result binding
        val resultBinding = checkedProgram.items[3] as LetBinding
        assertEquals("result", resultBinding.variableName)
        assertTrue(resultBinding.expression is PerformExpression)
    }

    @Test
    fun testNestedEffectCallsTypeChecking() {
        val source = """
            effect Log {
              info : {msg: String} -> {},
              debug : {msg: String} -> {}
            }

            impl Log for Console where {
              info : {msg: String} -> {} = {msg} -> native_print {msg = "[INFO] $(msg)"}
              debug : {msg: String} -> {} = {msg} -> native_print {msg = "[DEBUG] $(msg)"}
            }

            let nestedLogging = {} ->
              do
                Log.info {msg = "Starting nested operation"}
                do
                  Log.debug {msg = "Inner debug message"}
                  Log.info {msg = "Inner info message"}
                Log.info {msg = "Completed nested operation"}
        """.trimIndent()

        val program = parser.parse(source)
        val checkedProgram = typeChecker.typeCheck(program)

        assertEquals(3, checkedProgram.items.size)

        val letBinding = checkedProgram.items[2] as LetBinding
        assertEquals("nestedLogging", letBinding.variableName)

        val lambda = letBinding.expression as LambdaExpression
        assertTrue(lambda.body is DoExpression)

        val outerDo = lambda.body as DoExpression
        assertEquals(2, outerDo.statements.size)

        // Check that statements are present (simplified test)
        assertTrue(outerDo.statements.isNotEmpty())
    }
}
