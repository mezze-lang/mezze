package com.mezze.language.typechecker.types.concurrent

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.TypeVar

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
    fun registerAll(context: MutableMap<String, MezzeType>) {
        // __native_agent_new: {value: 'a} -> Agent 'a
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            val agentType = MezzeType.TypeApplication("Agent", listOf(elementType))
            context["__native_agent_new"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "value" to elementType
                )),
                agentType
            )
        }

        // __native_agent_deref: {agent: Agent 'a} -> 'a
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            val agentType = MezzeType.TypeApplication("Agent", listOf(elementType))
            context["__native_agent_deref"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "agent" to agentType
                )),
                elementType
            )
        }

        // __native_agent_send: {agent: Agent 'a, f: {current: 'a} -> 'a} -> {}
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            val agentType = MezzeType.TypeApplication("Agent", listOf(elementType))
            val funcType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("current" to elementType)),
                elementType
            )
            context["__native_agent_send"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "agent" to agentType,
                    "f" to funcType
                )),
                MezzeType.RecordType(emptyMap())
            )
        }

        // __native_agent_send_off: {agent: Agent 'a, f: {current: 'a} -> 'a} -> {}
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            val agentType = MezzeType.TypeApplication("Agent", listOf(elementType))
            val funcType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("current" to elementType)),
                elementType
            )
            context["__native_agent_send_off"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "agent" to agentType,
                    "f" to funcType
                )),
                MezzeType.RecordType(emptyMap())
            )
        }

        // __native_agent_await: {agent: Agent 'a, timeout_ms: Int} -> Bool
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            val agentType = MezzeType.TypeApplication("Agent", listOf(elementType))
            context["__native_agent_await"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "agent" to agentType,
                    "timeout_ms" to MezzeType.IntType
                )),
                MezzeType.BoolType
            )
        }

        // __native_agent_error: {agent: Agent 'a} -> Option String
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            val agentType = MezzeType.TypeApplication("Agent", listOf(elementType))
            val optionStringType = MezzeType.VariantType(
                mapOf(
                    "Some" to MezzeType.RecordType(mapOf("val" to MezzeType.StringType)),
                    "None" to null
                ),
                null
            )
            context["__native_agent_error"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "agent" to agentType
                )),
                optionStringType
            )
        }

        // __native_agent_restart: {agent: Agent 'a, value: 'a} -> {}
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            val agentType = MezzeType.TypeApplication("Agent", listOf(elementType))
            context["__native_agent_restart"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "agent" to agentType,
                    "value" to elementType
                )),
                MezzeType.RecordType(emptyMap())
            )
        }

        // __native_agent_set_error_handler: {agent: Agent 'a, handler: {agent: Agent 'a, error: String} -> {}} -> {}
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            val agentType = MezzeType.TypeApplication("Agent", listOf(elementType))
            val handlerType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "agent" to agentType,
                    "error" to MezzeType.StringType
                )),
                MezzeType.RecordType(emptyMap())
            )
            context["__native_agent_set_error_handler"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "agent" to agentType,
                    "handler" to handlerType
                )),
                MezzeType.RecordType(emptyMap())
            )
        }
    }
}
