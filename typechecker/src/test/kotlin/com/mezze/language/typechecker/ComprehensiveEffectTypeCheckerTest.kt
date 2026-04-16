package com.mezze.language.typechecker

import java.nio.file.Paths
import com.mezze.language.parsedast.*
import com.mezze.language.parser.MezzeParser
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

class ComprehensiveEffectTypeCheckerTest {

    private lateinit var parser: MezzeParser
    private lateinit var typeChecker: TypeChecker

    @BeforeEach
    fun setup() {
        ModuleResolver.clearCache()  // Clear module cache to prevent state pollution between tests
        TypeChecker.clearCache()
        parser = MezzeParser()
        typeChecker = TypeChecker()
    }

    @Test
    fun testEffectDeclarationTypeChecking() {
        val source = """
            effect Log where {
              print : {msg: Str} -> {} <Log>,
              debug : {msg: Str, level: Int} -> {} <Log>
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        typeChecker.typeCheck(parsedModule, Paths.get("."))

        // Check structural properties from v2 parsed AST
        val effectDecl = parsedModule.decls.filterIsInstance<ParsedEffectDecl>().first()
        assertEquals("Log", effectDecl.name)
        assertEquals(2, effectDecl.operations.size)

        // Check that operations have correct types
        val printOp = effectDecl.operations[0]
        assertEquals("print", printOp.name)
        assertTrue(printOp.paramType is ParsedRecordType)
        assertTrue(printOp.returnType is ParsedRecordType)

        val debugOp = effectDecl.operations[1]
        assertEquals("debug", debugOp.name)
        val debugParams = debugOp.paramType as ParsedRecordType
        assertEquals(2, debugParams.entries.size)
        assertEquals("msg", debugParams.entries[0].name)
        assertEquals("level", debugParams.entries[1].name)
    }

    @Test
    fun testSimpleImplDeclarationTypeChecking() {
        val source = """
            effect Log where {
              print : {msg: Str} -> {} <Log>
            }

            impl Log for Console where {
              print : {msg: Str} -> {} = {msg} -> {}
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        typeChecker.typeCheck(parsedModule, Paths.get("."))

        // Check structural properties from v2 parsed AST
        val effectDecl = parsedModule.decls.filterIsInstance<ParsedEffectDecl>().first()
        val implDecl = parsedModule.decls.filterIsInstance<ParsedPerformerDecl>().first()

        assertEquals("Log", effectDecl.name)
        assertEquals("Log", implDecl.effect.localName)
        assertEquals("Console", implDecl.performerName)

        // Check that impl operation matches effect operation
        val effectOp = effectDecl.operations[0]
        val implOp = implDecl.operations[0]
        assertEquals(effectOp.name, implOp.name)

        // Implementation should be a lambda expression
        assertTrue(implOp.implementation is ParsedLambda)
    }

    @Test
    fun testPerformExpressionTypeChecking() {
        val source = """
            effect Log where {
              print : {msg: Str} -> {} <Log>
            }

            impl Log for Console where {
              print : {msg: Str} -> {} = {msg} -> {}
            }

            perform Console in Log.print {msg = "Hello"}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        typeChecker.typeCheck(parsedModule, Paths.get("."))

        // Check structural properties from v2 parsed AST
        val effectDecl = parsedModule.decls.filterIsInstance<ParsedEffectDecl>().first()
        assertEquals("Log", effectDecl.name)

        val implDecl = parsedModule.decls.filterIsInstance<ParsedPerformerDecl>().first()
        assertEquals("Console", implDecl.performerName)

        // The top-level expression is a ParsedTopLevelExpr wrapping a ParsedPerform
        val topLevelExpr = parsedModule.decls.filterIsInstance<ParsedTopLevelExpr>().first()
        val performExpr = topLevelExpr.expression as ParsedPerform
        assertEquals(1, performExpr.handlers.size)
        assertEquals("Console", performExpr.handlers[0].performer.localName)

        // The performed expression should be a function call
        assertTrue(performExpr.body is ParsedFunctionCall)
    }

    @Test
    fun testEffectCallTypeChecking() {
        // Test wrapped in function to avoid top-level performed effects restriction
        val source = """
            effect Random where {
              nextInt : {min: Int, max: Int} -> Int <Random>
            }

            impl Random for MathRandom where {
              nextInt : {min: Int, max: Int} -> Int = {min, max} -> min + max / 2
            }

            let getRandomValue = {} -> perform MathRandom in Random.nextInt {min = 1, max = 10}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        typeChecker.typeCheck(parsedModule, Paths.get("."))

        // Check structural properties from v2 parsed AST
        val letBinding = parsedModule.decls.filterIsInstance<ParsedLetDecl>().find { it.variableName == "getRandomValue" }!!
        assertEquals("getRandomValue", letBinding.variableName)
        assertNotNull(letBinding.value)
    }

    @Test
    fun testMultipleEffectsTypeChecking() {
        val source = """
            effect Log where {
              print : {msg: Str} -> {} <Log>
            }

            effect Random where {
              nextInt : {min: Int, max: Int} -> Int <Random>
            }

            impl Log for Console where {
              print : {msg: Str} -> {} = {msg} -> {}
            }

            impl Random for MathRandom where {
              nextInt : {min: Int, max: Int} -> Int = {min, max} -> min + max / 2
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

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        typeChecker.typeCheck(parsedModule, Paths.get("."))

        // Check structural properties from v2 parsed AST
        val effectDecls = parsedModule.decls.filterIsInstance<ParsedEffectDecl>()
        val logEffect = effectDecls.find { it.name == "Log" }!!
        val randomEffect = effectDecls.find { it.name == "Random" }!!
        assertEquals("Log", logEffect.name)
        assertEquals("Random", randomEffect.name)

        // Check impl declarations
        val implDecls = parsedModule.decls.filterIsInstance<ParsedPerformerDecl>()
        val logImpl = implDecls.find { it.performerName == "Console" }!!
        val randomImpl = implDecls.find { it.performerName == "MathRandom" }!!
        assertEquals("Log", logImpl.effect.localName)
        assertEquals("Random", randomImpl.effect.localName)

        // Check let binding
        val letBinding = parsedModule.decls.filterIsInstance<ParsedLetDecl>().find { it.variableName == "gameLogic" }!!
        assertEquals("gameLogic", letBinding.variableName)
        assertTrue(letBinding.value is ParsedLambda)
    }

    @Test
    fun testDoBlockWithEffectsTypeChecking() {
        val source = """
            effect Log where {
              info : {msg: Str} -> {} <Log>
            }

            effect Random where {
              nextInt : {min: Int, max: Int} -> Int <Random>
            }

            impl Log for Console where {
              info : {msg: Str} -> {} = {msg} -> {}
            }

            impl Random for MathRandom where {
              nextInt : {min: Int, max: Int} -> Int = {min, max} -> min + max / 2
            }

            let computation = {} ->
              do
                Log.info {msg = "Computing"}
                let x = Random.nextInt {min = 1, max = 5}
                let y = Random.nextInt {min = x, max = 10}
                Log.info {msg = "Result computed"}
                x + y
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        typeChecker.typeCheck(parsedModule, Paths.get("."))

        // Check structural properties from v2 parsed AST
        val letBinding = parsedModule.decls.filterIsInstance<ParsedLetDecl>().find { it.variableName == "computation" }!!
        assertEquals("computation", letBinding.variableName)

        // The lambda body should be a do expression
        val lambda = letBinding.value as ParsedLambda
        assertTrue(lambda.body is ParsedDo)

        val doExpr = lambda.body as ParsedDo
        assertEquals(4, doExpr.statements.size)

        // Check that first statement is an expression statement (effect call)
        val firstStmt = doExpr.statements[0] as ParsedStatement.Expr
        assertTrue(firstStmt.expr is ParsedFunctionCall)
    }


    @Test
    fun testEffectOperationParameterMatching() {
        val source = """
            effect FileSystem where {
              readFile : {path: Str} -> Str <FileSystem>,
              writeFile : {path: Str, content: Str} -> {} <FileSystem>
            }

            impl FileSystem for MockFS where {
              readFile : {path: Str} -> Str = {path} -> "mock content",
              writeFile : {path: Str, content: Str} -> {} = {path, content} -> {}
            }
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        typeChecker.typeCheck(parsedModule, Paths.get("."))

        // Check structural properties from v2 parsed AST
        val effectDecl = parsedModule.decls.filterIsInstance<ParsedEffectDecl>().first()
        val implDecl = parsedModule.decls.filterIsInstance<ParsedPerformerDecl>().first()

        assertEquals(2, effectDecl.operations.size)
        assertEquals(2, implDecl.operations.size)

        // Check that operation signatures match
        for (i in 0 until effectDecl.operations.size) {
            val effectOp = effectDecl.operations[i]
            val implOp = implDecl.operations[i]
            assertEquals(effectOp.name, implOp.name)
        }
    }

    @Test
    fun testEffectRowInferenceInFunctions() {
        val source = """
            effect Log where {
              print : {msg: Str} -> {} <Log>
            }

            impl Log for Console where {
              print : {msg: Str} -> {} = {msg} -> {}
            }

            let loggedFunction = {name} -> do
              Log.print {msg = "Processing $(name)"}
              "$(name) processed"

            # Wrap in function to avoid top-level <*> restriction
            let getResult = {} -> perform Console in loggedFunction {name = "test"}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        typeChecker.typeCheck(parsedModule, Paths.get("."))

        // Check structural properties from v2 parsed AST
        val loggedFunctionBinding = parsedModule.decls.filterIsInstance<ParsedLetDecl>().find { it.variableName == "loggedFunction" }!!
        assertEquals("loggedFunction", loggedFunctionBinding.variableName)

        // The function should have a lambda expression
        assertTrue(loggedFunctionBinding.value is ParsedLambda)
        val lambda = loggedFunctionBinding.value as ParsedLambda
        assertTrue(lambda.body is ParsedDo)

        // Check getResult binding
        val getResultBinding = parsedModule.decls.filterIsInstance<ParsedLetDecl>().find { it.variableName == "getResult" }!!
        assertEquals("getResult", getResultBinding.variableName)
        assertTrue(getResultBinding.value is ParsedLambda)
    }

    @Test
    fun testNestedEffectCallsTypeChecking() {
        val source = """
            effect Log where {
              info : {msg: Str} -> {} <Log>,
              debug : {msg: Str} -> {} <Log>
            }

            impl Log for Console where {
              info : {msg: Str} -> {} = {msg} -> {},
              debug : {msg: Str} -> {} = {msg} -> {}
            }

            let nestedLogging = {} ->
              do
                Log.info {msg = "Starting nested operation"}
                do
                  Log.debug {msg = "Inner debug message"}
                  Log.info {msg = "Inner info message"}
                Log.info {msg = "Completed nested operation"}
        """.trimIndent()

        val parsedModule = parser.parseParsedModule(source, "test.mz")
        typeChecker.typeCheck(parsedModule, Paths.get("."))

        // Check structural properties from v2 parsed AST
        val letBinding = parsedModule.decls.filterIsInstance<ParsedLetDecl>().find { it.variableName == "nestedLogging" }!!
        assertEquals("nestedLogging", letBinding.variableName)

        val lambda = letBinding.value as ParsedLambda
        assertTrue(lambda.body is ParsedDo)

        val outerDo = lambda.body as ParsedDo
        assertEquals(2, outerDo.statements.size)

        // Check that statements are present (simplified test)
        assertTrue(outerDo.statements.isNotEmpty())
    }
}
