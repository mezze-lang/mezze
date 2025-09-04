package com.funk.language.typechecker

import com.funk.language.parser.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

/**
 * Comprehensive tests for the effect system
 */
class EffectSystemTest {
    
    private lateinit var effectChecker: EffectSystemChecker
    
    @BeforeEach
    fun setup() {
        effectChecker = EffectSystemChecker()
    }
    
    // ========== BASIC EFFECT INFERENCE ==========
    
    @Test
    fun `test pure expression has no effects`() {
        val context = TypeContext()
        val pureExpr = BinaryOperation(Literal(1), "+", Literal(2))
        
        val (type, effects) = effectChecker.inferEffects(pureExpr, context)
        
        assertEquals(FunkType.IntType, type)
        assertTrue(effects.isEmpty())
    }
    
    @Test
    fun `test effectful function call collects effects`() {
        val context = TypeContext()
        
        // Create an effectful function type
        val ioEffect = EffectType("IO", FunkType.StringType, FunkType.StringType)
        val effectfulFuncType = FunkType.FunctionType(
            FunkType.IntType,
            FunkType.StringType,
            setOf(Effect("IO"))
        )
        
        context.env.extend("readFile", TypeScheme.monomorphic(effectfulFuncType))
        
        val call = FunctionCall(
            Identifier("readFile"),
            Literal(1)
        )
        
        val (type, effects) = effectChecker.inferEffects(call, context)
        
        assertEquals(FunkType.StringType, type)
        assertTrue(effects.isNotEmpty())
        assertTrue(effects.any { it.name == "IO" })
    }
    
    // ========== EFFECT COMPOSITION ==========
    
    @Test
    fun `test effect composition in do blocks`() {
        val context = TypeContext()
        
        // Create effectful functions
        val ioEffect = Effect("IO")
        val netEffect = Effect("Network")
        
        val ioFuncType = FunkType.FunctionType(
            FunkType.StringType,
            FunkType.StringType,
            setOf(ioEffect)
        )
        val netFuncType = FunkType.FunctionType(
            FunkType.StringType,
            FunkType.StringType,
            setOf(netEffect)
        )
        
        context.env.extend("readFile", TypeScheme.monomorphic(ioFuncType))
        context.env.extend("httpGet", TypeScheme.monomorphic(netFuncType))
        
        val doExpr = DoExpression(
            listOf(
                ExpressionStatement(FunctionCall(Identifier("readFile"), Literal("config.txt"))),
                ExpressionStatement(FunctionCall(Identifier("httpGet"), Literal("http://api.com")))
            ),
            Literal("done")
        )
        
        val (type, effects) = effectChecker.inferEffects(doExpr, context)
        
        assertEquals(FunkType.StringType, type)
        assertEquals(2, effects.size)
        assertTrue(effects.any { it.name == "IO" })
        assertTrue(effects.any { it.name == "Network" })
    }
    
    @Test
    fun `test effect composition is commutative`() {
        val ioEffect = EffectType("IO", FunkType.StringType, FunkType.StringType)
        val netEffect = EffectType("Network", FunkType.StringType, FunkType.StringType)
        
        val effects1 = setOf(ioEffect, netEffect)
        val effects2 = setOf(netEffect, ioEffect)
        
        val composed1 = EffectComposition.composeEffects(effects1, emptySet())
        val composed2 = EffectComposition.composeEffects(effects2, emptySet())
        
        assertEquals(composed1, composed2)
    }
    
    // ========== EFFECT HANDLERS ==========
    
    @Test
    fun `test handle expression removes handled effects`() {
        val context = TypeContext()
        
        val ioEffect = EffectType("IO", FunkType.StringType, FunkType.StringType)
        val effectfulExpr = FunctionCall(
            Identifier("readFile"),
            Literal("test.txt")
        )
        
        // Mock handler that handles IO effect
        val handler = EffectHandler("IO", LambdaExpression(
            RecordType(listOf(RecordTypeEntry("input", SimpleType("String")))),
            Literal("mocked")
        ))
        
        val handleExpr = HandleExpression(
            effectfulExpr,
            listOf(handler)
        )
        
        val (type, effects) = effectChecker.inferEffects(handleExpr, context)
        
        // IO effect should be handled and removed
        assertFalse(effects.any { it.name == "IO" })
    }
    
    @Test
    fun `test partial effect handling`() {
        val context = TypeContext()
        
        val ioEffect = EffectType("IO", FunkType.StringType, FunkType.StringType)
        val netEffect = EffectType("Network", FunkType.StringType, FunkType.StringType)
        
        // Expression with both IO and Network effects
        val multiEffectExpr = DoExpression(
            listOf(
                ExpressionStatement(FunctionCall(Identifier("readFile"), Literal("config"))),
                ExpressionStatement(FunctionCall(Identifier("httpGet"), Literal("url")))
            ),
            Literal("result")
        )
        
        // Handle only IO effect
        val ioHandler = EffectHandler("IO", LambdaExpression(
            RecordType(listOf(RecordTypeEntry("input", SimpleType("String")))),
            Literal("handled")
        ))
        
        val handleExpr = HandleExpression(
            multiEffectExpr,
            listOf(ioHandler)
        )
        
        val (type, effects) = effectChecker.inferEffects(handleExpr, context)
        
        // IO should be handled, Network should remain
        assertFalse(effects.any { it.name == "IO" })
        assertTrue(effects.any { it.name == "Network" })
    }
    
    // ========== EFFECT UNIFICATION ==========
    
    @Test
    fun `test effect unification success`() {
        val ioEffect1 = EffectType("IO", FunkType.StringType, FunkType.StringType)
        val ioEffect2 = EffectType("IO", FunkType.StringType, FunkType.StringType)
        
        val result = EffectComposition.unifyEffects(
            setOf(ioEffect1),
            setOf(ioEffect2)
        )
        
        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrNull()!!.size)
    }
    
    @Test
    fun `test effect unification failure - missing effect`() {
        val ioEffect = EffectType("IO", FunkType.StringType, FunkType.StringType)
        val netEffect = EffectType("Network", FunkType.StringType, FunkType.StringType)
        
        val result = EffectComposition.unifyEffects(
            setOf(ioEffect, netEffect), // Expected
            setOf(ioEffect)             // Actual - missing Network
        )
        
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull() as StructuredTypeError
        assertTrue(error.errorInfo.kind is TypeErrorKind.EffectNotFound)
    }
    
    @Test
    fun `test effect unification failure - type mismatch`() {
        val ioEffect1 = EffectType("IO", FunkType.StringType, FunkType.StringType)
        val ioEffect2 = EffectType("IO", FunkType.IntType, FunkType.StringType) // Different input type
        
        val result = EffectComposition.unifyEffects(
            setOf(ioEffect1),
            setOf(ioEffect2)
        )
        
        assertTrue(result.isFailure)
    }
    
    // ========== EFFECT SUBSET CHECKING ==========
    
    @Test
    fun `test effect subset relationship`() {
        val ioEffect = EffectType("IO", FunkType.StringType, FunkType.StringType)
        val netEffect = EffectType("Network", FunkType.StringType, FunkType.StringType)
        
        val subset = setOf(ioEffect)
        val superset = setOf(ioEffect, netEffect)
        
        assertTrue(EffectComposition.isEffectSubset(subset, superset))
        assertFalse(EffectComposition.isEffectSubset(superset, subset))
    }
    
    @Test
    fun `test empty effect set is subset of any set`() {
        val ioEffect = EffectType("IO", FunkType.StringType, FunkType.StringType)
        val effects = setOf(ioEffect)
        
        assertTrue(EffectComposition.isEffectSubset(emptySet(), effects))
        assertTrue(EffectComposition.isEffectSubset(emptySet(), emptySet()))
    }
    
    // ========== EFFECT HANDLER VALIDATION ==========
    
    @Test
    fun `test effect handler type validation success`() {
        val context = TypeContext()
        val effect = EffectType("IO", FunkType.StringType, FunkType.IntType)
        val continuationType = FunkType.StringType
        
        // Handler: String -> (Int -> String) -> String
        val handler = LambdaExpression(
            RecordType(listOf(RecordTypeEntry("input", SimpleType("String")))),
            LambdaExpression(
                RecordType(listOf(RecordTypeEntry("resume", SimpleType("Function")))),
                Literal("handled")
            )
        )
        
        val result = EffectHandlerValidator.validateHandler(
            effect,
            handler,
            continuationType,
            context
        )
        
        assertTrue(result.isSuccess)
    }
    
    @Test
    fun `test effect handler type validation failure`() {
        val context = TypeContext()
        val effect = EffectType("IO", FunkType.StringType, FunkType.IntType)
        val continuationType = FunkType.StringType
        
        // Wrong handler type: Int -> String (should be String -> ...)
        val wrongHandler = LambdaExpression(
            RecordType(listOf(RecordTypeEntry("input", SimpleType("Int")))),
            Literal("wrong")
        )
        
        val result = EffectHandlerValidator.validateHandler(
            effect,
            wrongHandler,
            continuationType,
            context
        )
        
        assertTrue(result.isFailure)
    }
    
    // ========== CONDITIONAL EFFECTS ==========
    
    @Test
    fun `test if expression with different effects in branches`() {
        val context = TypeContext()
        
        val ioCall = FunctionCall(Identifier("readFile"), Literal("file.txt"))
        val netCall = FunctionCall(Identifier("httpGet"), Literal("url"))
        
        val ifExpr = IfExpression(
            Literal(true),
            ioCall,    // IO effect
            netCall    // Network effect
        )
        
        val (type, effects) = effectChecker.inferEffects(ifExpr, context)
        
        // Should collect effects from both branches
        assertTrue(effects.any { it.name == "IO" })
        assertTrue(effects.any { it.name == "Network" })
    }
    
    // ========== MATCH EXPRESSION EFFECTS ==========
    
    @Test
    fun `test match expression collects effects from all cases`() {
        val context = TypeContext()
        
        val matchExpr = MatchExpression(
            Literal(1),
            listOf(
                MatchCase(WildcardPattern, FunctionCall(Identifier("readFile"), Literal("a.txt"))),
                MatchCase(WildcardPattern, FunctionCall(Identifier("httpGet"), Literal("url"))),
                MatchCase(WildcardPattern, Literal("pure"))
            )
        )
        
        val (type, effects) = effectChecker.inferEffects(matchExpr, context)
        
        // Should collect effects from all cases
        assertTrue(effects.any { it.name == "IO" })
        assertTrue(effects.any { it.name == "Network" })
    }
    
    // ========== HIGHER-ORDER EFFECTS ==========
    
    @Test
    fun `test higher-order function with effects`() {
        val context = TypeContext()
        
        // map : (a -> b [effects]) -> List a -> List b [effects]
        val mapType = FunkType.FunctionType(
            FunkType.FunctionType(
                FunkType.VarType(TypeVar("a")),
                FunkType.VarType(TypeVar("b")),
                setOf(Effect("SomeEffect"))
            ),
            FunkType.FunctionType(
                FunkType.TypeConstructor("List", listOf(FunkType.VarType(TypeVar("a")))),
                FunkType.TypeConstructor("List", listOf(FunkType.VarType(TypeVar("b")))),
                setOf(Effect("SomeEffect"))
            )
        )
        
        context.env.extend("map", TypeScheme.monomorphic(mapType))
        
        val effectfulLambda = LambdaExpression(
            RecordType(listOf(RecordTypeEntry("x", SimpleType("Int")))),
            FunctionCall(Identifier("effectfulOp"), Identifier("x"))
        )
        
        val mapCall = FunctionCall(
            FunctionCall(Identifier("map"), effectfulLambda),
            Identifier("someList")
        )
        
        val (type, effects) = effectChecker.inferEffects(mapCall, context)
        
        // Should propagate effects from the mapped function
        assertTrue(effects.isNotEmpty())
    }
    
    // ========== EFFECT POLYMORPHISM ==========
    
    @Test
    fun `test effect polymorphic function`() {
        val context = TypeContext()
        
        // A function that can work with any set of effects
        // runWith : (a -> b [e]) -> a -> b [e]
        val runWithType = FunkType.FunctionType(
            FunkType.FunctionType(
                FunkType.VarType(TypeVar("a")),
                FunkType.VarType(TypeVar("b")),
                emptySet() // Effect polymorphic
            ),
            FunkType.FunctionType(
                FunkType.VarType(TypeVar("a")),
                FunkType.VarType(TypeVar("b")),
                emptySet()
            )
        )
        
        context.env.extend("runWith", TypeScheme.monomorphic(runWithType))
        
        // Should work with both pure and effectful functions
        val pureFunc = LambdaExpression(
            RecordType(listOf(RecordTypeEntry("x", SimpleType("Int")))),
            BinaryOperation(Identifier("x"), "+", Literal(1))
        )
        
        val runPure = FunctionCall(
            FunctionCall(Identifier("runWith"), pureFunc),
            Literal(5)
        )
        
        val (pureType, pureEffects) = effectChecker.inferEffects(runPure, context)
        
        assertEquals(FunkType.IntType, pureType)
        assertTrue(pureEffects.isEmpty())
    }
    
    // ========== ERROR CASES ==========
    
    @Test
    fun `test unhandled effect error`() {
        val context = TypeContext()
        
        // Function that requires IO effect but no handler provided
        val ioFuncType = FunkType.FunctionType(
            FunkType.StringType,
            FunkType.StringType,
            setOf(Effect("IO"))
        )
        
        context.env.extend("readFile", TypeScheme.monomorphic(ioFuncType))
        
        val call = FunctionCall(Identifier("readFile"), Literal("test.txt"))
        val (type, effects) = effectChecker.inferEffects(call, context)
        
        // Should have unhandled IO effect
        assertTrue(effects.any { it.name == "IO" })
    }
    
    @Test
    fun `test effect handler mismatch error`() {
        val context = TypeContext()
        
        val wrongHandler = EffectHandler("IO", LambdaExpression(
            RecordType(listOf(RecordTypeEntry("wrong", SimpleType("Int")))),
            Literal("bad")
        ))
        
        val handleExpr = HandleExpression(
            FunctionCall(Identifier("readFile"), Literal("test.txt")),
            listOf(wrongHandler)
        )
        
        // Should detect handler type mismatch
        assertThrows<StructuredTypeError> {
            effectChecker.inferEffects(handleExpr, context)
        }
    }
    
    // ========== INTEGRATION WITH TYPE SYSTEM ==========
    
    @Test
    fun `test effect system integration with record currying`() {
        val context = TypeContext()
        
        // Effectful function with record parameters
        val effectfulFunc = FunkType.FunctionType(
            FunkType.RecordType(mapOf(
                "filename" to FunkType.StringType,
                "mode" to FunkType.StringType
            )),
            FunkType.StringType,
            setOf(Effect("IO"))
        )
        
        context.env.extend("openFile", TypeScheme.monomorphic(effectfulFunc))
        
        // Partial application should preserve effects
        val partialCall = FunctionCall(
            Identifier("openFile"),
            RecordLiteral(listOf(RecordEntry("filename", Literal("test.txt"))))
        )
        
        val (type, effects) = effectChecker.inferEffects(partialCall, context)
        
        // Result should be a function that still has IO effect
        assertTrue(type is FunkType.FunctionType)
        val funcType = type as FunkType.FunctionType
        assertTrue(funcType.effects.any { it.name == "IO" })
    }
}
