package com.funk.language.typechecker.types.concurrent

import com.funk.language.ast.FunkType
import com.funk.language.ast.TypeVar

/**
 * Type definitions for Clojure-style agents (async atoms).
 *
 * Agents provide asynchronous, queued state updates with these properties:
 * - State changes happen asynchronously (send returns immediately)
 * - Actions are processed sequentially in order
 * - If an action throws, the agent enters error state
 *
 * IMPORTANT: Each function gets its own fresh type variables!
 */
object AgentTypes {

    /**
     * Register all agent native function types.
     */
    fun registerAll(context: MutableMap<String, FunkType>) {
        // __native_agent_new: {value: 'a} -> Agent 'a
        run {
            val elementType = FunkType.VarType(TypeVar("a"))
            val agentType = FunkType.TypeApplication("Agent", listOf(elementType))
            context["__native_agent_new"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "value" to elementType
                )),
                agentType
            )
        }

        // __native_agent_deref: {agent: Agent 'a} -> 'a
        run {
            val elementType = FunkType.VarType(TypeVar("a"))
            val agentType = FunkType.TypeApplication("Agent", listOf(elementType))
            context["__native_agent_deref"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "agent" to agentType
                )),
                elementType
            )
        }

        // __native_agent_send: {agent: Agent 'a, f: {current: 'a} -> 'a} -> {}
        run {
            val elementType = FunkType.VarType(TypeVar("a"))
            val agentType = FunkType.TypeApplication("Agent", listOf(elementType))
            val funcType = FunkType.FunctionType(
                FunkType.RecordType(mapOf("current" to elementType)),
                elementType
            )
            context["__native_agent_send"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "agent" to agentType,
                    "f" to funcType
                )),
                FunkType.RecordType(emptyMap())
            )
        }

        // __native_agent_send_off: {agent: Agent 'a, f: {current: 'a} -> 'a} -> {}
        run {
            val elementType = FunkType.VarType(TypeVar("a"))
            val agentType = FunkType.TypeApplication("Agent", listOf(elementType))
            val funcType = FunkType.FunctionType(
                FunkType.RecordType(mapOf("current" to elementType)),
                elementType
            )
            context["__native_agent_send_off"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "agent" to agentType,
                    "f" to funcType
                )),
                FunkType.RecordType(emptyMap())
            )
        }

        // __native_agent_await: {agent: Agent 'a, timeout_ms: Int} -> Bool
        run {
            val elementType = FunkType.VarType(TypeVar("a"))
            val agentType = FunkType.TypeApplication("Agent", listOf(elementType))
            context["__native_agent_await"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "agent" to agentType,
                    "timeout_ms" to FunkType.IntType
                )),
                FunkType.BooleanType
            )
        }

        // __native_agent_error: {agent: Agent 'a} -> Option String
        run {
            val elementType = FunkType.VarType(TypeVar("a"))
            val agentType = FunkType.TypeApplication("Agent", listOf(elementType))
            val optionStringType = FunkType.VariantType(
                mapOf(
                    "Some" to FunkType.RecordType(mapOf("val" to FunkType.StringType)),
                    "None" to null
                ),
                null
            )
            context["__native_agent_error"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "agent" to agentType
                )),
                optionStringType
            )
        }

        // __native_agent_restart: {agent: Agent 'a, value: 'a} -> {}
        run {
            val elementType = FunkType.VarType(TypeVar("a"))
            val agentType = FunkType.TypeApplication("Agent", listOf(elementType))
            context["__native_agent_restart"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "agent" to agentType,
                    "value" to elementType
                )),
                FunkType.RecordType(emptyMap())
            )
        }

        // __native_agent_set_error_handler: {agent: Agent 'a, handler: {agent: Agent 'a, error: String} -> {}} -> {}
        run {
            val elementType = FunkType.VarType(TypeVar("a"))
            val agentType = FunkType.TypeApplication("Agent", listOf(elementType))
            val handlerType = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "agent" to agentType,
                    "error" to FunkType.StringType
                )),
                FunkType.RecordType(emptyMap())
            )
            context["__native_agent_set_error_handler"] = FunkType.FunctionType(
                FunkType.RecordType(mapOf(
                    "agent" to agentType,
                    "handler" to handlerType
                )),
                FunkType.RecordType(emptyMap())
            )
        }
    }
}
