package com.funk.language.interpreter

import com.funk.language.parser.*
import com.funk.language.typechecker.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Runtime effect system for Funk
 * Handles effect invocation, handlers, and continuation management
 */
class EffectRuntime {
    
    private val handlers = mutableMapOf<String, EffectHandler>()
    private val continuations = mutableListOf<FunkContinuation>()
    
    /**
     * Invoke an effect with given arguments
     */
    fun invokeEffect(effectName: String, arguments: FunkValue, context: FunkContext): FunkValue {
        val handler = handlers[effectName]
            ?: throw FunkException.runtimeError("No handler found for effect '$effectName'")
        
        return handler.handle(arguments, context, this)
    }
    
    /**
     * Register an effect handler
     */
    fun registerHandler(effectName: String, handler: EffectHandler) {
        handlers[effectName] = handler
    }
    
    /**
     * Resume a computation with a value
     */
    fun resume(value: FunkValue, context: FunkContext): FunkValue {
        val continuation = continuations.removeLastOrNull()
            ?: throw FunkException.runtimeError("No continuation to resume")
        
        return continuation.resume(value, context)
    }
    
    /**
     * Push a continuation onto the stack
     */
    fun pushContinuation(continuation: FunkContinuation) {
        continuations.add(continuation)
    }
    
    /**
     * Clear all handlers and continuations (for testing)
     */
    fun clear() {
        handlers.clear()
        continuations.clear()
    }
}

/**
 * Interface for effect handlers
 */
interface EffectHandler {
    fun handle(arguments: FunkValue, context: FunkContext, effectRuntime: EffectRuntime): FunkValue
}

/**
 * Simple effect handler implementation
 */
class SimpleEffectHandler(
    private val handlerFunction: (FunkValue, FunkContext, EffectRuntime) -> FunkValue
) : EffectHandler {
    
    override fun handle(arguments: FunkValue, context: FunkContext, effectRuntime: EffectRuntime): FunkValue {
        return handlerFunction(arguments, context, effectRuntime)
    }
}

/**
 * Represents a continuation for resumable computations
 */
class FunkContinuation(
    private val resumeFunction: (FunkValue, FunkContext) -> FunkValue
) {
    
    fun resume(value: FunkValue, context: FunkContext): FunkValue {
        return resumeFunction(value, context)
    }
}

/**
 * Built-in effects for common operations
 */
object BuiltinEffects {
    
    /**
     * Print effect - outputs a message
     */
    fun createPrintEffect(): EffectHandler {
        return SimpleEffectHandler { arguments, _, _ ->
            when (arguments) {
                is FunkRecord -> {
                    val message = arguments.fields["msg"]?.getDisplayString() ?: "null"
                    println(message)
                    FunkVariant("Ok", null)
                }
                else -> throw FunkException.runtimeError("Print effect expects record with 'msg' field")
            }
        }
    }
    
    /**
     * ReadFile effect - reads a file (simplified implementation)
     */
    fun createReadFileEffect(): EffectHandler {
        return SimpleEffectHandler { arguments, _, _ ->
            when (arguments) {
                is FunkRecord -> {
                    val path = arguments.fields["path"]
                    if (path is FunkString) {
                        try {
                            val content = java.io.File(path.value).readText()
                            FunkVariant("Ok", FunkRecord(mapOf("content" to FunkString(content))))
                        } catch (e: Exception) {
                            FunkVariant("NotFound", null)
                        }
                    } else {
                        throw FunkException.runtimeError("ReadFile effect expects record with 'path' string field")
                    }
                }
                else -> throw FunkException.runtimeError("ReadFile effect expects record with 'path' field")
            }
        }
    }
    
    /**
     * WriteFile effect - writes to a file (simplified implementation)
     */
    fun createWriteFileEffect(): EffectHandler {
        return SimpleEffectHandler { arguments, _, _ ->
            when (arguments) {
                is FunkRecord -> {
                    val path = arguments.fields["path"]
                    val content = arguments.fields["content"]
                    if (path is FunkString && content is FunkString) {
                        try {
                            java.io.File(path.value).writeText(content.value)
                            FunkVariant("Ok", null)
                        } catch (e: Exception) {
                            FunkVariant("PermissionDenied", null)
                        }
                    } else {
                        throw FunkException.runtimeError("WriteFile effect expects record with 'path' and 'content' string fields")
                    }
                }
                else -> throw FunkException.runtimeError("WriteFile effect expects record with 'path' and 'content' fields")
            }
        }
    }
}

/**
 * Effect invocation node for Truffle
 */
class FunkEffectInvocationNode(
    private val effectName: String,
    @com.oracle.truffle.api.nodes.Node.Child private var argumentsNode: com.funk.language.interpreter.nodes.FunkExpressionNode
) : com.funk.language.interpreter.nodes.FunkExpressionNode() {
    
    override fun executeGeneric(frame: com.oracle.truffle.api.frame.VirtualFrame): FunkValue {
        val context = FunkLanguage.getCurrentContext(this)
        val arguments = argumentsNode.executeGeneric(frame)
        
        return context.effectRuntime.invokeEffect(effectName, arguments, context)
    }
}

/**
 * Handle expression node for Truffle
 */
class FunkHandleNode(
    @com.oracle.truffle.api.nodes.Node.Child private var expressionNode: com.funk.language.interpreter.nodes.FunkExpressionNode,
    private val handlers: Map<String, com.funk.language.interpreter.nodes.FunkExpressionNode>
) : com.funk.language.interpreter.nodes.FunkExpressionNode() {
    
    override fun executeGeneric(frame: com.oracle.truffle.api.frame.VirtualFrame): FunkValue {
        val context = FunkLanguage.getCurrentContext(this)
        
        // Register temporary handlers
        val originalHandlers = mutableMapOf<String, EffectHandler>()
        
        try {
            // Install handlers
            for ((effectName, handlerNode) in handlers) {
                // Save original handler if it exists
                // originalHandlers[effectName] = context.effectRuntime.getHandler(effectName)
                
                // Create new handler
                val handler = SimpleEffectHandler { arguments, ctx, effectRuntime ->
                    // Execute the handler expression
                    handlerNode.executeGeneric(frame)
                }
                
                context.effectRuntime.registerHandler(effectName, handler)
            }
            
            // Execute the handled expression
            return expressionNode.executeGeneric(frame)
            
        } finally {
            // Restore original handlers
            for (entry in originalHandlers.entries) {
                context.effectRuntime.registerHandler(entry.key, entry.value)
            }
        }
    }
}

/**
 * Resume expression node for Truffle
 */
class FunkResumeNode(
    @com.oracle.truffle.api.nodes.Node.Child private var valueNode: com.funk.language.interpreter.nodes.FunkExpressionNode
) : com.funk.language.interpreter.nodes.FunkExpressionNode() {
    
    override fun executeGeneric(frame: com.oracle.truffle.api.frame.VirtualFrame): FunkValue {
        val context = FunkLanguage.getCurrentContext(this)
        val value = valueNode.executeGeneric(frame)
        
        return context.effectRuntime.resume(value, context)
    }
}
