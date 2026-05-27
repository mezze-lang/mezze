package com.mezze.language.typechecker.types.concurrent

import com.mezze.language.ast.MezzeType
import com.mezze.language.ast.TypeVar

/**
 * Type definitions for Clojure-style agents (async atoms).
 *
 * Every native carries `<Async>` (post-#170).
 *
 * Agents provide asynchronous, queued state updates with these properties:
 * - State changes happen asynchronously (send returns immediately)
 * - Actions are processed sequentially in order
 * - If an action throws, the agent enters error state
 *
 * IMPORTANT: Each function gets its own fresh type variables!
 */
object AgentTypes {

    fun registerAll(context: MutableMap<String, MezzeType>) {
        val asyncEffect = MezzeType.EffectRow(effects = setOf("Async"))

        // __native_agent_new: {value: 'a} -> Agent 'a <Async>
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            val agentType = com.mezze.language.typechecker.util.WellKnownTypeApp.of("Agent", listOf(elementType))
            context["__native_agent_new"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "value" to elementType
                )),
                agentType,
                effects = asyncEffect
            )
        }

        // __native_agent_deref: {agent: Agent 'a} -> 'a <Async>
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            val agentType = com.mezze.language.typechecker.util.WellKnownTypeApp.of("Agent", listOf(elementType))
            context["__native_agent_deref"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "agent" to agentType
                )),
                elementType,
                effects = asyncEffect
            )
        }

        // __native_agent_send: {agent: Agent 'a, f: {current: 'a} -> 'a} -> {} <Async>
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            val agentType = com.mezze.language.typechecker.util.WellKnownTypeApp.of("Agent", listOf(elementType))
            val funcType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("current" to elementType)),
                elementType
            )
            context["__native_agent_send"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "agent" to agentType,
                    "f" to funcType
                )),
                MezzeType.RecordType(emptyMap()),
                effects = asyncEffect
            )
        }

        // __native_agent_send_off: {agent: Agent 'a, f: {current: 'a} -> 'a} -> {} <Async>
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            val agentType = com.mezze.language.typechecker.util.WellKnownTypeApp.of("Agent", listOf(elementType))
            val funcType = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf("current" to elementType)),
                elementType
            )
            context["__native_agent_send_off"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "agent" to agentType,
                    "f" to funcType
                )),
                MezzeType.RecordType(emptyMap()),
                effects = asyncEffect
            )
        }

        // __native_agent_await: {agent: Agent 'a, timeout_ms: Int} -> Bool <Async>
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            val agentType = com.mezze.language.typechecker.util.WellKnownTypeApp.of("Agent", listOf(elementType))
            context["__native_agent_await"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "agent" to agentType,
                    "timeout_ms" to MezzeType.IntType
                )),
                MezzeType.BoolType,
                effects = asyncEffect
            )
        }

        // __native_agent_error: {agent: Agent 'a} -> Option String <Async>
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            val agentType = com.mezze.language.typechecker.util.WellKnownTypeApp.of("Agent", listOf(elementType))
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
                optionStringType,
                effects = asyncEffect
            )
        }

        // __native_agent_restart: {agent: Agent 'a, value: 'a} -> {} <Async>
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            val agentType = com.mezze.language.typechecker.util.WellKnownTypeApp.of("Agent", listOf(elementType))
            context["__native_agent_restart"] = MezzeType.FunctionType(
                MezzeType.RecordType(mapOf(
                    "agent" to agentType,
                    "value" to elementType
                )),
                MezzeType.RecordType(emptyMap()),
                effects = asyncEffect
            )
        }

        // __native_agent_set_error_handler: {agent: Agent 'a, handler: {agent: Agent 'a, error: String} -> {}} -> {} <Async>
        run {
            val elementType = MezzeType.VarType(TypeVar("a"))
            val agentType = com.mezze.language.typechecker.util.WellKnownTypeApp.of("Agent", listOf(elementType))
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
                MezzeType.RecordType(emptyMap()),
                effects = asyncEffect
            )
        }
    }
}
