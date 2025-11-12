package com.funk.language.interpreter.nodes.function

import com.oracle.truffle.api.CallTarget
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.dsl.Cached
import com.oracle.truffle.api.dsl.Specialization
import com.oracle.truffle.api.frame.FrameDescriptor
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.DirectCallNode
import com.oracle.truffle.api.nodes.IndirectCallNode
import com.oracle.truffle.api.nodes.NodeInfo
import com.oracle.truffle.api.nodes.RootNode
import com.funk.language.interpreter.nodes.FunkNode
import com.funk.language.interpreter.FunkValue
import com.funk.language.interpreter.FunkRecord
import com.funk.language.interpreter.FunkLanguage
import com.funk.language.interpreter.FunkNull
import com.funk.language.interpreter.NativeFunkFunction
import com.funk.language.interpreter.FunkTruffleFunction

/**
 * Function definition node that creates a callable function
 */
@NodeInfo(shortName = "function-def")
class FunctionDefinitionNode(
    private val name: String,
    private val parameterNames: List<String>,
    @field:Child private var bodyNode: FunkNode,
    private val frameDescriptor: FrameDescriptor
) : FunkNode() {
    
    private val callTarget: CallTarget by lazy {
        val rootNode = FunkFunctionRootNode(
            language = getLanguage(),
            name = name,
            parameterNames = parameterNames,
            bodyNode = bodyNode,
            frameDescriptor = frameDescriptor
        )
        rootNode.callTarget
    }
    
    override fun execute(frame: VirtualFrame): FunkValue {
        return FunkTruffleFunction(callTarget, name)
    }
}

/**
 * Function call node with manual call site optimization
 */
@NodeInfo(shortName = "function-call")
class FunctionCallNode(
    @field:Child private var functionNode: FunkNode,
    @field:Child private var argumentsNode: FunkNode
) : FunkNode() {
    
    override fun execute(frame: VirtualFrame): FunkValue {
        val function = functionNode.execute(frame)
        val arguments = argumentsNode.execute(frame)
        
        return when (function) {
            is FunkTruffleFunction -> {
                // Direct call to Truffle function
                function.callTarget.call(arguments) as FunkValue
            }
            is NativeFunkFunction -> {
                // Call native function
                function.call(listOf(arguments))
            }
            else -> {
                throw RuntimeException("Cannot call non-function value: ${function::class.simpleName}")
            }
        }
    }
}

/**
 * Root node for Funk functions
 */
class FunkFunctionRootNode(
    language: FunkLanguage,
    private val name: String,
    private val parameterNames: List<String>,
    @field:Child private var bodyNode: FunkNode,
    frameDescriptor: FrameDescriptor
) : RootNode(language, frameDescriptor) {
    
    override fun execute(frame: VirtualFrame): Any {
        // Arguments are passed as a single FunkRecord
        val arguments = frame.getArguments()
        if (arguments.isNotEmpty()) {
            val argsRecord = arguments[0] as? FunkRecord
            if (argsRecord != null) {
                // Bind parameters to frame slots
                for ((index, paramName) in parameterNames.withIndex()) {
                    val value = argsRecord.fields[paramName] ?: FunkNull
                    frame.setObject(index, value)
                }
            }
        }
        
        return bodyNode.execute(frame)
    }
    
    override fun getName(): String = name
    
    override fun toString(): String = "FunkFunction($name)"
}

/**
 * Truffle function value that wraps a CallTarget
 */
class FunkTruffleFunction(
    val callTarget: CallTarget,
    private val name: String? = null
) : FunkValue() {
    
    override fun getDisplayString(): String = name ?: "<function>"
    
    override fun toString(): String = "FunkTruffleFunction(${name ?: "anonymous"})"
}